/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * Copyright (c) 2026 Material Files contributors.
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.util.Date
import java8.nio.channels.FileChannels
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.posixFileType
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.IOutCreateArchive
import net.sf.sevenzipjbinding.IOutCreateArchive7z
import net.sf.sevenzipjbinding.IOutCreateArchiveBZip2
import net.sf.sevenzipjbinding.IOutCreateArchiveXz
import net.sf.sevenzipjbinding.IOutCreateArchiveGZip
import net.sf.sevenzipjbinding.IOutCreateArchiveTar
import net.sf.sevenzipjbinding.IOutCreateArchiveZip
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItemAllFormats
import net.sf.sevenzipjbinding.IOutStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory

/** One complete JBinding create operation. The caller owns and commits the output channel. */
class ArchiveWriter @Throws(IOException::class) constructor(
    private val channel: SeekableByteChannel,
    private val format: ArchiveCreationFormat,
    private val password: String?,
    private val encryptFileNames: Boolean = false,
    private val compressionPreset: ArchiveCompressionPreset = ArchiveCompressionPreset.STANDARD
) : Closeable {
    private var written = false
    private var closed = false

    init {
        require(password.isNullOrEmpty() || format.supportsPassword)
        require(format != ArchiveCreationFormat.ZIP || password.isNullOrEmpty()
            || password.all { it in ' '..'~' }) {
            "ZIP passwords must contain only printable ASCII characters"
        }
        require(format != ArchiveCreationFormat.ZIP || password == null
            || password.length <= MAX_ZIP_PASSWORD_LENGTH) {
            "ZIP AES passwords must contain at most $MAX_ZIP_PASSWORD_LENGTH characters"
        }
        require(!encryptFileNames || format == ArchiveCreationFormat.SEVEN_Z
            && !password.isNullOrEmpty())
        SevenZipRuntime.ensureInitialized()
    }

    @Throws(IOException::class)
    fun writeEntries(
        entries: List<Pair<Path, Path>>,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?,
        onEntry: ((Path) -> Unit)? = null,
        onEntryComplete: ((Path) -> Unit)? = null
    ) {
        check(!closed && !written) { "Archive entries must be supplied in one batch" }
        written = true
        val ownerThread = Thread.currentThread()
        val sources = entries.map { (file, name) ->
            checkInterrupted(ownerThread)
            Source(file, name.toString())
        }
        if (format.singleFileOnly
            && (sources.size != 1 || !sources.single().attributes.isRegularFile)) {
            throw IOException("Single-file compression requires exactly one regular file")
        }
        val progress = Progress(
            sources, format == ArchiveCreationFormat.SEVEN_Z || format == ArchiveCreationFormat.XZ,
            intervalMillis, listener, onEntry, onEntryComplete
        )
        create(channel, sources, progress, ownerThread)
        checkInterrupted(ownerThread)
        // Empty entries may never receive getStream(), so validate every source again.
        sources.forEach {
            checkInterrupted(ownerThread)
            it.verifyUnchanged()
        }
        progress.finish()
    }

    private fun create(
        output: SeekableByteChannel,
        sources: List<Source>,
        progress: Progress?,
        ownerThread: Thread
    ) {
        val inputs = mutableListOf<ManagedInput>()
        val zipInputs = mutableMapOf<Int, SeekableSourceInput>()
        var failure: Throwable? = null
        try {
            // Use typed factories so each format's supported settings are explicit and
            // a missing feature interface cannot silently skip a requested option.
            val opened: IOutCreateArchive<*> = when (format) {
                ArchiveCreationFormat.SEVEN_Z -> SevenZip.openOutArchive7z()
                ArchiveCreationFormat.ZIP -> SevenZip.openOutArchiveZip()
                ArchiveCreationFormat.TAR -> SevenZip.openOutArchiveTar()
                ArchiveCreationFormat.GZIP -> SevenZip.openOutArchiveGZip()
                ArchiveCreationFormat.BZIP2 -> SevenZip.openOutArchiveBZip2()
                ArchiveCreationFormat.XZ -> SevenZip.openOutArchiveXz()
            }
            opened.use { archive ->
                val callback = object : IOutCreateCallback<IOutItemAllFormats> {
                    override fun getItemInformation(
                        index: Int,
                        outItemFactory: OutItemFactory<IOutItemAllFormats>
                    ): IOutItemAllFormats {
                        checkInterrupted(ownerThread)
                        val source = sources[index]
                        // 7-Zip can omit getStream() for empty files and metadata-only items.
                        if (source.size == 0L || source.attributes.isDirectory
                            || format == ArchiveCreationFormat.TAR && source.attributes.isSymbolicLink) {
                            progress?.start(source)
                        }
                        return source.newItem(outItemFactory, format)
                    }

                    @Synchronized
                    override fun getStream(index: Int): ISequentialInStream? {
                        checkInterrupted(ownerThread)
                        val source = sources[index]
                        progress?.start(source)
                        if (format == ArchiveCreationFormat.ZIP) {
                            zipInputs[index]?.let {
                                source.verifyUnchanged()
                                it.seek(0, ISeekableStream.SEEK_SET)
                                return it
                            }
                            return source.openSeekable(progress, ownerThread)?.also {
                                inputs += it
                                zipInputs[index] = it
                            }
                        }
                        val input = source.open(format) ?: return null
                        return SourceInput(source, input, progress, ownerThread).also { inputs += it }
                    }

                    override fun setOperationResult(operationResultOk: Boolean) {
                        checkInterrupted(ownerThread)
                        if (!operationResultOk) {
                            throw IOException("7-Zip could not create an archive entry")
                        }
                    }

                    override fun setTotal(total: Long) {
                        checkInterrupted(ownerThread)
                        progress?.setTotal(total)
                    }

                    override fun setCompleted(complete: Long) {
                        checkInterrupted(ownerThread)
                        progress?.setCompleted(complete)
                    }
                }
                val createCallback = if (!password.isNullOrEmpty()) {
                    PasswordCallback(callback, password)
                } else {
                    callback
                }
                val nativeOutput = ChannelOutput(output, ownerThread)
                when (archive) {
                    is IOutCreateArchive7z -> {
                        // Upstream's individual setters reset the other properties. Apply
                        // the level and header encryption together before one create pass.
                        SevenZipArchiveProperties.configure(
                            archive, compressionPreset.level, encryptHeaders = encryptFileNames
                        )
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    }
                    is IOutCreateArchiveZip -> {
                        SevenZipArchiveProperties.configure(
                            archive, compressionPreset.level, zipAes = !password.isNullOrEmpty()
                        )
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    }
                    is IOutCreateArchiveTar ->
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    is IOutCreateArchiveGZip -> {
                        archive.setLevel(compressionPreset.level)
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    }
                    is IOutCreateArchiveBZip2 -> {
                        archive.setLevel(compressionPreset.level)
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    }
                    is IOutCreateArchiveXz -> {
                        SevenZipArchiveProperties.configure(archive, compressionPreset.level)
                        archive.createArchive(nativeOutput, sources.size, createCallback)
                    }
                    else -> throw IOException("Unsupported archive writer: ${archive.javaClass.name}")
                }
            }
            for (input in inputs) input.finish()
        } catch (e: SevenZipException) {
            val exception = e.toIOException()
            failure = exception
            throw exception
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            var closeFailure: IOException? = null
            for (input in inputs) {
                try {
                    input.close()
                } catch (e: IOException) {
                    if (failure != null) failure.addSuppressed(e)
                    else if (closeFailure == null) closeFailure = e
                    else closeFailure.addSuppressed(e)
                }
            }
            if (failure == null) closeFailure?.let { throw it }
        }
    }

    override fun close() {
        // createArchive() finalizes the archive. close() must never turn a failed batch into
        // a successful empty archive or close an output owned by ArchiveOutput.
        closed = true
    }

    private class PasswordCallback(
        callback: IOutCreateCallback<IOutItemAllFormats>,
        private val password: String
    ) : IOutCreateCallback<IOutItemAllFormats> by callback, ICryptoGetTextPassword {
        override fun cryptoGetTextPassword(): String = password
    }

    private class Source(val path: Path, val name: String) {
        val attributes = path.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        private val type = attributes.posixFileType
        private val mode = (attributes as? PosixFileAttributes)?.mode() ?: when (type) {
            PosixFileType.DIRECTORY -> PosixFileMode.DIRECTORY_DEFAULT
            PosixFileType.SYMBOLIC_LINK -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
            else -> PosixFileMode.FILE_DEFAULT
        }
        val link = if (attributes.isSymbolicLink) {
            path.readSymbolicLinkByteString().toBytes()
        } else {
            null
        }
        val size: Long = link?.size?.toLong() ?: if (attributes.isDirectory) 0 else attributes.size()

        init {
            if (type != PosixFileType.REGULAR_FILE && type != PosixFileType.DIRECTORY
                && type != PosixFileType.SYMBOLIC_LINK) {
                throw IOException("Unsupported archive source type $type: $path")
            }
            // A backslash can be a Linux filename character, but archive readers also treat
            // it as a path separator. Refuse ambiguous names instead of changing their path.
            if (name.isEmpty() || name.startsWith('/') || name.split('/').any { it == ".." }
                || '\\' in name || '\u0000' in name || Regex("^[A-Za-z]:").containsMatchIn(name)) {
                throw IOException("Invalid archive entry name: $name")
            }
        }

        fun newItem(
            factory: OutItemFactory<IOutItemAllFormats>, format: ArchiveCreationFormat
        ): IOutItemAllFormats = factory.createOutItem().apply {
            propertyPath = name
            propertyIsDir = attributes.isDirectory
            dataSize = if (format == ArchiveCreationFormat.TAR && link != null) 0 else size
            propertyLastModificationTime = Date(attributes.lastModifiedTime().toMillis())
            propertyAttributes = ((type.mode or mode.toInt()) shl 16) or 0x8000 or
                if (attributes.isDirectory) 0x10 else 0x20
            if (format == ArchiveCreationFormat.TAR) {
                propertyPosixAttributes = type.mode or mode.toInt()
                propertyUser = (attributes as? PosixFileAttributes)?.owner()?.name?.toString()
                propertyGroup = (attributes as? PosixFileAttributes)?.group()?.name?.toString()
                propertySymLink = link?.toString(Charsets.UTF_8)
            }
        }

        fun open(format: ArchiveCreationFormat): InputStream? {
            verifyUnchanged()
            if (attributes.isDirectory || format == ArchiveCreationFormat.TAR && link != null) {
                return null
            }
            return link?.let(::ByteArrayInputStream) ?: path.newInputStream(LinkOption.NOFOLLOW_LINKS)
        }

        fun openSeekable(progress: Progress?, ownerThread: Thread): SeekableSourceInput? {
            checkInterrupted(ownerThread)
            verifyUnchanged()
            if (attributes.isDirectory) return null
            if (link != null) return SeekableSourceInput(this, null, null, progress, ownerThread)
            tryOpenByteChannel(ownerThread)?.let { input ->
                try {
                    if (input.size() != size) {
                        throw IOException("The source changed while creating the archive: $path")
                    }
                    verifyUnchanged()
                    return SeekableSourceInput(this, input, null, progress, ownerThread)
                } catch (e: Throwable) {
                    try { input.close() } catch (close: Throwable) { e.addSuppressed(close) }
                    throw e
                }
            }
            // The ZIP handler rewinds its input when comparing compression methods. A
            // sequential provider therefore needs one bounded, seekable cache copy.
            val context = if (isRunningAsRoot) rootContext else application
            val temporary = File.createTempFile("archive-zip-source-", ".tmp", context.cacheDir)
            var input: SeekableByteChannel? = null
            try {
                SourceInput(this, open(ArchiveCreationFormat.ZIP)!!, progress, ownerThread).use { source ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count == 0) break
                            checkInterrupted(ownerThread)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                checkInterrupted(ownerThread)
                verifyUnchanged()
                input = FileChannels.from(RandomAccessFile(temporary, "r").channel)
                return SeekableSourceInput(this, input, temporary, progress, ownerThread)
            } catch (e: Throwable) {
                try { input?.close() } catch (close: Throwable) { e.addSuppressed(close) }
                if (!temporary.delete() && temporary.exists()) {
                    e.addSuppressed(IOException("Unable to remove archive staging file: $temporary"))
                }
                throw e
            }
        }

        fun tryOpenByteChannel(ownerThread: Thread): SeekableByteChannel? {
            var input: SeekableByteChannel? = null
            try {
                checkInterrupted(ownerThread)
                input = path.newByteChannel(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                input.size()
                // A document provider can return a pipe despite accepting newByteChannel().
                input.position(size)
                input.position(0)
                checkInterrupted(ownerThread)
                return input
            } catch (e: Throwable) {
                var closeFailed = false
                try { input?.close() } catch (close: Throwable) {
                    e.addSuppressed(close)
                    closeFailed = true
                }
                checkInterrupted(ownerThread)
                if (closeFailed || e is InterruptedIOException || e is ClosedByInterruptException
                    || e !is IOException && e !is UnsupportedOperationException) {
                    throw e
                }
                return null
            }
        }

        fun verifyUnchanged() {
            val current = path.readAttributes(
                BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            if (current.isDirectory != attributes.isDirectory
                || current.isRegularFile != attributes.isRegularFile
                || current.isSymbolicLink != attributes.isSymbolicLink
                || !attributes.isDirectory && (current.size() != attributes.size()
                    || current.lastModifiedTime() != attributes.lastModifiedTime())
                || attributes.fileKey() != null && current.fileKey() != attributes.fileKey()
                || link != null && !path.readSymbolicLinkByteString().toBytes().contentEquals(link)) {
                throw IOException("The source changed while creating the archive: $path")
            }
        }
    }

    private interface ManagedInput : ISequentialInStream, Closeable {
        fun finish()
    }

    private class SourceInput(
        private val source: Source,
        private val input: InputStream,
        private val progress: Progress?,
        private val ownerThread: Thread
    ) : ManagedInput {
        private var count = 0L
        private var closed = false

        @Synchronized
        override fun read(data: ByteArray): Int {
            checkInterrupted(ownerThread)
            if (data.isEmpty()) return 0
            if (closed) return 0
            val size = input.read(data)
            if (size == 0) {
                throw IOException("The archive source made no read progress: ${source.path}")
            }
            if (size > 0) {
                if (size > source.size - count) {
                    throw IOException("The archive source grew: ${source.path}")
                }
                count += size
                progress?.advance(source, count)
            }
            if (size < 0 || count == source.size) finish()
            return size.coerceAtLeast(0) // JBinding uses zero for EOF.
        }

        @Synchronized
        override fun finish() {
            checkInterrupted(ownerThread)
            if (closed) return
            if (count != source.size) {
                throw IOException("The archive source was truncated: ${source.path}")
            }
            // Some handlers stop at dataSize without asking for EOF. Check for growth too.
            if (input.read() != -1) throw IOException("The archive source grew: ${source.path}")
            close()
            source.verifyUnchanged()
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                closed = true
                input.close()
            }
        }
    }

    private class SeekableSourceInput(
        private val source: Source,
        private var input: SeekableByteChannel?,
        private val temporary: File?,
        private val progress: Progress?,
        private val ownerThread: Thread
    ) : IInStream, ManagedInput {
        private var position = 0L
        private var readUntil = 0L
        private var endVerified = false
        private var closed = false

        @Synchronized
        override fun read(data: ByteArray): Int {
            checkInterrupted(ownerThread)
            if (closed) throw IOException("The archive source is closed: ${source.path}")
            if (data.isEmpty()) return 0
            if (position >= source.size) {
                if (!endVerified) verifyEndAndRelease()
                return 0
            }
            endVerified = false
            val length = minOf(data.size.toLong(), source.size - position).toInt()
            val count = if (source.link == null) {
                requireInput().read(ByteBuffer.wrap(data, 0, length)).also {
                    if (it < 0) throw IOException("The archive source was truncated: ${source.path}")
                    if (it == 0) {
                        throw IOException("The archive source made no read progress: ${source.path}")
                    }
                }
            } else {
                source.link!!.copyInto(data, 0, position.toInt(), position.toInt() + length)
                length
            }
            position += count
            readUntil = maxOf(readUntil, position)
            progress?.advance(source, readUntil)
            if (position == source.size) verifyEndAndRelease()
            return count
        }

        private fun requireInput(): SeekableByteChannel {
            input?.let { return it }
            checkInterrupted(ownerThread)
            source.verifyUnchanged()
            val opened = if (temporary != null) {
                FileChannels.from(RandomAccessFile(temporary, "r").channel)
            } else {
                source.tryOpenByteChannel(ownerThread)
                    ?: throw IOException("The archive source no longer supports seeking: ${source.path}")
            }
            try {
                if (opened.size() != source.size) {
                    throw IOException("The source changed while creating the archive: ${source.path}")
                }
                opened.position(position)
                input = opened
                return opened
            } catch (e: Throwable) {
                try { opened.close() } catch (close: Throwable) { e.addSuppressed(close) }
                throw e
            }
        }

        @Synchronized
        override fun seek(offset: Long, seekOrigin: Int): Long {
            checkInterrupted(ownerThread)
            if (closed) throw IOException("The archive source is closed: ${source.path}")
            val origin = when (seekOrigin) {
                ISeekableStream.SEEK_SET -> 0L
                ISeekableStream.SEEK_CUR -> position
                ISeekableStream.SEEK_END -> source.size
                else -> throw IOException("Invalid archive seek origin: $seekOrigin")
            }
            val newPosition = try { Math.addExact(origin, offset) } catch (e: ArithmeticException) {
                throw IOException("Archive source seek overflow", e)
            }
            if (newPosition < 0) throw IOException("Negative archive source position")
            input?.position(newPosition)
            position = newPosition
            return position
        }

        @Synchronized
        override fun finish() {
            checkInterrupted(ownerThread)
            if (closed) throw IOException("The archive source is closed: ${source.path}")
            if (readUntil != source.size) {
                throw IOException("The archive source was truncated: ${source.path}")
            }
            if (!endVerified || input != null) verifyEndAndRelease()
            checkInterrupted(ownerThread)
            source.verifyUnchanged()
        }

        private fun verifyEndAndRelease() {
            var failure: Throwable? = null
            try {
                checkInterrupted(ownerThread)
                if (source.link == null) {
                    val opened = requireInput()
                    if (opened.size() != source.size) {
                        throw IOException("The source changed while creating the archive: ${source.path}")
                    }
                    // Some handlers stop at dataSize without asking for EOF.
                    opened.position(source.size)
                    val extra = opened.read(ByteBuffer.allocate(1))
                    if (extra == 0) {
                        throw IOException("The archive source made no read progress: ${source.path}")
                    }
                    if (extra > 0) throw IOException("The archive source grew: ${source.path}")
                }
                checkInterrupted(ownerThread)
                source.verifyUnchanged()
                endVerified = true
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                // Keep the IInStream and cache copy alive until native creation finishes,
                // but avoid one open descriptor per completed ZIP entry. A rewind reopens
                // the backing channel and checks the source snapshot before reading again.
                try { closeInput() } catch (e: Throwable) {
                    if (failure != null) failure.addSuppressed(e) else throw e
                }
            }
        }

        private fun closeInput() {
            val opened = input
            input = null
            opened?.close()
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            var failure: IOException? = null
            try {
                closeInput()
            } catch (e: IOException) {
                failure = e
                throw e
            } finally {
                if (temporary != null && !temporary.delete() && temporary.exists()) {
                    val exception = IOException("Unable to remove archive staging file: $temporary")
                    if (failure != null) failure.addSuppressed(exception) else throw exception
                }
            }
        }
    }

    private class Progress(
        private val sources: List<Source>,
        private val useNativeProgress: Boolean,
        private val intervalMillis: Long,
        private val listener: ((Long) -> Unit)?,
        private val onEntry: ((Path) -> Unit)?,
        private val onEntryComplete: ((Path) -> Unit)?
    ) {
        private val total = sources.sumOf { it.attributes.size() }
        // One remaining byte can round to 100% in the FAB. Leave a visible margin until
        // creation, header writing and source validation have all succeeded.
        private val maximumBeforeFinish =
            (total - total / 100 - if (total % 100 != 0L) 1 else 0).coerceAtLeast(0)
        private val started = mutableSetOf<Source>()
        private val sourceReadUntil = mutableMapOf<Source, Long>()
        private var nativeTotal = 0L
        private var read = 0L
        private var reported = 0L
        private var lastUpdate = System.nanoTime()

        @Synchronized
        fun start(source: Source) {
            if (started.add(source)) onEntry?.invoke(source.path)
        }

        @Synchronized
        fun advance(source: Source, position: Long) {
            // LZMA2 can read entire blocks ahead of encoding them. For 7z and XZ, use the
            // encoder's completed input bytes rather than its input stream's read position.
            if (useNativeProgress) return
            val previous = sourceReadUntil[source] ?: 0
            if (position <= previous) return
            sourceReadUntil[source] = position
            read += position - previous
            report(read)
        }

        @Synchronized
        fun setTotal(total: Long) {
            nativeTotal = total.coerceAtLeast(0)
        }

        @Synchronized
        fun setCompleted(complete: Long) {
            if (!useNativeProgress || nativeTotal == 0L) return
            // The file-job scan also counts directory metadata. Normalize the native work
            // total to that same scale so the notification and FAB show the encoded fraction.
            val fraction = complete.coerceIn(0, nativeTotal).toDouble() / nativeTotal
            report((fraction * total).toLong())
        }

        private fun report(completed: Long) {
            val completed = completed.coerceIn(reported, maximumBeforeFinish)
            if (completed == reported) return
            val now = System.nanoTime()
            if ((now - lastUpdate) / 1_000_000 < intervalMillis) return
            listener?.invoke(completed - reported)
            reported = completed
            lastUpdate = now
        }

        @Synchronized
        fun finish() {
            if (total > reported) listener?.invoke(total - reported)
            sources.forEach { onEntryComplete?.invoke(it.path) }
        }
    }

    private class ChannelOutput(
        private val channel: SeekableByteChannel,
        private val ownerThread: Thread
    ) : IOutStream {
        @Synchronized
        override fun write(data: ByteArray): Int {
            checkInterrupted(ownerThread)
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                checkInterrupted(ownerThread)
                if (channel.write(buffer) <= 0) {
                    throw IOException("The archive destination made no write progress")
                }
            }
            return data.size
        }

        @Synchronized
        override fun seek(offset: Long, seekOrigin: Int): Long {
            checkInterrupted(ownerThread)
            val origin = when (seekOrigin) {
                ISeekableStream.SEEK_SET -> 0L
                ISeekableStream.SEEK_CUR -> channel.position()
                ISeekableStream.SEEK_END -> channel.size()
                else -> throw IOException("Invalid archive seek origin: $seekOrigin")
            }
            val position = try { Math.addExact(origin, offset) } catch (e: ArithmeticException) {
                throw IOException("Archive output seek overflow", e)
            }
            if (position < 0) throw IOException("Negative archive output position")
            channel.position(position)
            return position
        }

        @Synchronized
        override fun setSize(newSize: Long) {
            checkInterrupted(ownerThread)
            if (newSize < 0) throw IOException("Negative archive output size")
            val position = channel.position()
            try {
                if (newSize <= channel.size()) {
                    channel.truncate(newSize)
                } else {
                    channel.position(newSize - 1)
                    write(byteArrayOf(0))
                }
            } finally {
                channel.position(position)
            }
        }
    }

    companion object {
        private fun checkInterrupted(ownerThread: Thread) {
            if (ownerThread.isInterrupted || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Archive creation canceled")
            }
        }
    }
}
