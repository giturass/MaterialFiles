/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.FileChannels
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One JBinding handle and its source/volume streams, owned by one browsing or extraction job. */
internal class SevenZipArchiveReader private constructor(
    private val file: Path,
    private val source: ArchiveSource,
    private val passwords: List<String>,
    private var forcedFormat: ArchiveFormat? = null
) : Closeable {
    private var archive: IInArchive? = null
    private var openCallback: OpenCallback? = null
    private var metadata: List<ArchiveEntry>? = null
    private var currentPassword: String? = null
    private var passwordVerified = false
    private var extracted = false
    private var inputStream: EntryInputStream? = null
    private var closed = false

    private fun openAttempt(password: String?): IInArchive {
        closeArchive()
        source.operationThread = Thread.currentThread()
        checkInterrupted()
        source.channel.position(0)
        val callback = OpenCallback(password)
        try {
            val opened = SevenZip.openInArchive(forcedFormat, source.stream, callback)
            archive = opened
            openCallback = callback
            currentPassword = password
            val errors = (opened.getArchiveProperty(PropID.ERROR_FLAGS) as? Number)?.toLong() ?: 0
            if (errors != 0L) throw IOException("The archive headers are damaged or incomplete ($errors)")
            return opened
        } catch (e: Throwable) {
            val failure = if (e is SevenZipException) {
                val translated = e.toIOException()
                when {
                    callback.passwordRequested && translated.cause === e ->
                        ArchivePassphraseException(password != null, translated)
                    forcedFormat == null && !callback.passwordRequested
                        && e.message?.contains("can't be opened with any of the registered codecs") == true
                        && e.cause == null && e.causeLastThrown == null
                        && e.causeFirstPotentialThrown == null && e.causeLastPotentialThrown == null ->
                        UnrecognizedArchiveException(translated)
                    else -> translated
                }
            } else e
            try {
                // Native handles must release their references before any volume stream closes.
                if (archive != null) closeArchive() else callback.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private inline fun <T> withPassword(block: (IInArchive) -> T): T {
        ensureOpen()
        val candidates = buildList {
            if (archive != null) add(currentPassword)
            addAll(passwords.asReversed())
            add(null)
        }.distinct()
        var lastFailure: ArchivePassphraseException? = null
        for (password in candidates) {
            try {
                checkInterrupted()
                return block(archive?.takeIf { currentPassword == password } ?: openAttempt(password))
            } catch (e: ArchivePassphraseException) {
                lastFailure = e
            } catch (e: SevenZipException) {
                val failure = e.toIOException()
                if (failure !is ArchivePassphraseException) throw failure
                lastFailure = failure
            }
        }
        throw ArchivePassphraseException(passwords.isNotEmpty(), lastFailure)
    }

    private fun entries(): List<ArchiveEntry> = metadata ?: withPassword { opened ->
        val count = opened.numberOfItems
        if (count < 0) throw IOException("Invalid archive entry count")
        val hardLinks = mutableMapOf<Int, String>()
        val listed = buildList {
            for (index in 0 until count) {
                checkInterrupted()
                if (opened.getProperty(index, PropID.IS_ANTI) == true) continue
                fun property(id: PropID) = opened.getProperty(index, id)
                val name = (property(PropID.PATH) as? String)?.takeIf { it.isNotEmpty() }
                    ?: rawEntryName(source.name)
                val attributes = (property(PropID.ATTRIBUTES) as? Number)?.toInt()
                val posixMode = (property(PropID.POSIX_ATTRIB) as? Number)?.toInt()
                    ?: attributes?.takeIf { it and 0x8000 != 0 }?.ushr(16)
                val directory = property(PropID.IS_FOLDER) == true
                val link = (property(PropID.SYM_LINK) as? String)?.takeIf { it.isNotEmpty() }
                if (opened.archiveFormat == ArchiveFormat.TAR) {
                    (property(PropID.HARD_LINK) as? String)?.let {
                        hardLinks[index] = it
                    }
                }
                val type = when {
                    directory -> PosixFileType.DIRECTORY
                    link != null -> PosixFileType.SYMBOLIC_LINK
                    posixMode != null -> PosixFileType.fromMode(posixMode)
                        .takeUnless { it == PosixFileType.UNKNOWN } ?: PosixFileType.REGULAR_FILE
                    else -> PosixFileType.REGULAR_FILE
                }
                val mode = posixMode?.let { PosixFileMode.fromInt(it) } ?: when (type) {
                    PosixFileType.DIRECTORY -> PosixFileMode.DIRECTORY_DEFAULT
                    PosixFileType.SYMBOLIC_LINK -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
                    else -> PosixFileMode.FILE_DEFAULT
                }
                add(ArchiveEntry(
                    name, property(PropID.ENCRYPTED) == true,
                    (property(PropID.LAST_MODIFICATION_TIME) as? Date)?.toFileTime(),
                    (property(PropID.LAST_ACCESS_TIME) as? Date)?.toFileTime(),
                    (property(PropID.CREATION_TIME) as? Date)?.toFileTime(),
                    type, ((property(PropID.SIZE) as? Number)?.toLong() ?: 0).coerceAtLeast(0),
                    null, null, mode, link, index
                ))
            }
        }
        val adapted = if (opened.archiveFormat == ArchiveFormat.TAR) {
            applyPaxHeaders(opened, listed, hardLinks)
        } else listed
        resolveHardLinks(adapted, hardLinks).also { metadata = it }
    }

    /** The bundled TAR handler exposes PAX records as files but does not apply their metadata. */
    private fun applyPaxHeaders(
        opened: IInArchive,
        entries: List<ArchiveEntry>,
        hardLinks: MutableMap<Int, String>
    ): List<ArchiveEntry> {
        val headers = PaxTarHeaders.read(source.channel, LongArray(opened.numberOfItems) { index ->
            checkInterrupted()
            (opened.getProperty(index, PropID.PACKED_SIZE) as? Number)?.toLong()
                ?: throw IOException("The TAR entry has no packed size")
        })
        val extended = entries.filter { headers[it.index].typeFlag in listOf('g', 'x', 'X') }
        if (extended.isEmpty()) return entries
        val records = mutableMapOf<Int, Map<String, String>>()
        var buffer: ByteArrayOutputStream? = null
        extractNative(opened, extended, object : ExtractionCallback {
            override fun openOutput(entry: ArchiveEntry): OutputStream {
                if (entry.size > MAX_PAX_HEADER_SIZE) {
                    throw IOException("The PAX extended header is too large")
                }
                val output = ByteArrayOutputStream()
                buffer = output
                return object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length > MAX_PAX_HEADER_SIZE - output.size()) {
                            throw IOException("The PAX extended header is too large")
                        }
                        output.write(bytes, offset, length)
                    }
                }
            }

            override fun onResult(entry: ArchiveEntry, failure: IOException?) {
                if (failure != null) throw failure
                val bytes = buffer!!.toByteArray()
                buffer = null
                if (bytes.size.toLong() != entry.size) throw IOException("The PAX header was truncated")
                records[entry.index] = PaxTarHeaders.parse(bytes).filterKeys {
                    it in PAX_METADATA_KEYS || it.startsWith("GNU.sparse.")
                }
            }
        })
        val global = mutableMapOf<String, String>()
        val local = mutableMapOf<String, String>()
        var localPending = false
        val result = buildList {
            for (entry in entries) {
                checkInterrupted()
                val header = headers[entry.index]
                when (header.typeFlag) {
                    'g' -> {
                        global.putAll(records.getValue(entry.index))
                        continue
                    }
                    'x', 'X' -> {
                        local.putAll(records.getValue(entry.index))
                        localPending = true
                        continue
                    }
                }
                val values = global + local
                local.clear()
                localPending = false
                val charset = values["hdrcharset"]
                if (!charset.isNullOrEmpty() && charset != "ISO-IR 10646 2000 UTF-8"
                    && charset != "UTF-8") {
                    throw IOException("Unsupported PAX character encoding: $charset")
                }
                if (values.any { (key, value) -> key.startsWith("GNU.sparse.") && value.isNotEmpty() }
                    || !values["SCHILY.realsize"].isNullOrEmpty()
                    || values["SCHILY.filetype"] == "sparse") {
                    throw IOException("The bundled 7-Zip cannot extract this PAX sparse-file variant")
                }
                values["size"]?.let { value ->
                    val size = value.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
                        ?.toLongOrNull() ?: throw IOException("Invalid PAX file size")
                    // Changing only provider metadata would let native extraction silently
                    // stop at the legacy header's size. Reject unsupported layouts first.
                    if (size != header.size || size != entry.size) {
                        throw IOException("The bundled 7-Zip cannot extract a PAX size override")
                    }
                }
                val name = values["path"]?.also {
                    if (it.isEmpty()) throw IOException("The PAX header removes the entry path")
                } ?: entry.name
                var symbolicLink = entry.symbolicLinkTarget
                if (header.typeFlag == '1' || header.typeFlag == '2') {
                    values["linkpath"]?.let { target ->
                        if (target.isEmpty()) throw IOException("The PAX header removes a link target")
                        if (header.typeFlag == '1') hardLinks[entry.index] = target
                        else symbolicLink = target
                    }
                }
                val modified = if (values.containsKey("mtime")) {
                    values.getValue("mtime").takeIf { it.isNotEmpty() }?.let(PaxTarHeaders::fileTime)
                } else entry.lastModifiedTime
                val accessed = if (values.containsKey("atime")) {
                    values.getValue("atime").takeIf { it.isNotEmpty() }?.let(PaxTarHeaders::fileTime)
                } else entry.lastAccessTime
                add(entry.copy(
                    name = name, symbolicLinkTarget = symbolicLink,
                    type = if (header.typeFlag == '2') PosixFileType.SYMBOLIC_LINK else entry.type,
                    lastModifiedTime = modified, lastAccessTime = accessed
                ))
            }
        }
        if (localPending) throw IOException("The PAX extended header has no following entry")
        return result
    }

    /** TAR's native extractor returns no data for hard links; resolve their archive item. */
    private fun resolveHardLinks(
        entries: List<ArchiveEntry>, hardLinks: Map<Int, String>
    ): List<ArchiveEntry> {
        if (hardLinks.isEmpty()) return entries
        val byName = linkedMapOf<String, ArchiveEntry>()
        for (entry in entries) {
            ArchiveReader.safeEntryName(entry.name)?.let { byName.putIfAbsent(it, entry) }
        }
        val resolved = entries.filter { it.index !in hardLinks }.associateBy { it.index }.toMutableMap()
        for (entry in entries) {
            if (entry.index in resolved) continue
            val chain = mutableListOf<ArchiveEntry>()
            val visited = mutableSetOf<Int>()
            var current = entry
            while (current.index !in resolved) {
                if (!visited.add(current.index)) throw IOException("Circular archive hard link: ${entry.name}")
                chain += current
                val target = ArchiveReader.safeEntryName(hardLinks.getValue(current.index))
                    ?: throw IOException("Invalid archive hard link target: ${current.name}")
                current = byName[target]
                    ?: throw IOException("Missing archive hard link target: ${current.name}")
            }
            val target = resolved.getValue(current.index)
            if (target.type != PosixFileType.REGULAR_FILE && !target.isSymbolicLink) {
                throw IOException("Unsupported archive hard link target: ${entry.name}")
            }
            for (link in chain) {
                resolved[link.index] = link.copy(
                    size = target.size, type = target.type, isEncrypted = target.isEncrypted,
                    symbolicLinkTarget = target.symbolicLinkTarget, contentIndex = target.contentIndex
                )
            }
        }
        return entries.map { resolved.getValue(it.index) }
    }

    fun readEntries(): List<ArchiveEntry> {
        val entries = entries()
        val links = entries.filter { it.isSymbolicLink && it.symbolicLinkTarget == null }
        if (links.isEmpty()) return entries
        val targets = mutableMapOf<Int, String>()
        var output: ByteArrayOutputStream? = null
        extract(links, object : ExtractionCallback {
            override fun openOutput(entry: ArchiveEntry): OutputStream {
                val buffer = ByteArrayOutputStream()
                output = buffer
                return object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length > MAX_SYMBOLIC_LINK_SIZE - buffer.size()) {
                            throw IOException("The archived symbolic link is too long")
                        }
                        buffer.write(bytes, offset, length)
                    }
                }
            }

            override fun onResult(entry: ArchiveEntry, failure: IOException?) {
                if (failure != null) throw failure
                targets[entry.index] = output!!.toByteArray().toString(Charsets.UTF_8)
                output = null
            }
        })
        return entries.map { entry ->
            targets[entry.index]?.let { entry.copy(symbolicLinkTarget = it) } ?: entry
        }.also { metadata = it }
    }

    /** Matches by native item identity, then processes solid blocks in their natural order. */
    fun orderedEntries(entries: List<ArchiveEntry>): List<ArchiveEntry> {
        val available = entries()
        val byIndex = available.associateBy { it.index }
        val byName = available.groupBy { it.name }
        return entries.map { requested ->
            val matched = if (requested.index >= 0) {
                byIndex[requested.index]?.takeIf { it.name == requested.name }
            } else byName[requested.name]?.firstOrNull()
            if (matched == null) throw IOException("An entry is missing from the archive: ${requested.name}")
            requested.copy(index = matched.index, contentIndex = matched.contentIndex)
        }.distinctBy { it.index }.sortedBy { it.index }
    }

    interface ExtractionCallback {
        /** Returning null skips the entry; the caller owns and closes the returned stream. */
        fun openOutput(entry: ArchiveEntry): OutputStream?
        /** A successful result is delivered only after the engine has verified this item. */
        fun onResult(entry: ArchiveEntry, failure: IOException?)
    }

    fun extract(entries: List<ArchiveEntry>, callback: ExtractionCallback) {
        val selected = orderedEntries(entries)
        if (selected.isEmpty()) return
        val opened = prepareExtraction(selected)
        val batch = mutableListOf<ArchiveEntry>()
        for (entry in selected) {
            if (entry.contentIndex != entry.index) {
                if (batch.isNotEmpty()) {
                    extractNative(opened, batch, callback)
                    batch.clear()
                }
                // TAR is not solid. A separate call supplies a hard link's target bytes in
                // the same order that the provider's sequential copy loop requests aliases.
                extractNative(opened, listOf(entry), callback)
            } else {
                batch += entry
            }
        }
        if (batch.isNotEmpty()) extractNative(opened, batch, callback)
    }

    private fun prepareExtraction(entries: List<ArchiveEntry>): IInArchive {
        ensureOpen()
        if (inputStream != null) throw IOException("An archive entry stream is already open")
        // The bundled PPMd handler retains decoder state after Extract. Its next operation
        // needs a fresh native archive even after the prior stream was fully consumed.
        if (extracted && source.magic.hasSignature(PPMD_SIGNATURE)) closeArchive()
        val encrypted = entries.firstOrNull { it.isEncrypted && !it.isDirectory }
        if (encrypted != null) {
            if (passwords.isEmpty()) throw ArchivePassphraseException(false)
            if (!passwordVerified && passwords.distinct().size > 1) {
                // Verify a candidate before writing any destination when multiple passwords
                // are remembered. Solid extraction itself still uses one native Extract call.
                withPassword { opened ->
                    extractNative(opened, listOf(encrypted), object : ExtractionCallback {
                        override fun openOutput(entry: ArchiveEntry): OutputStream? = null
                        override fun onResult(entry: ArchiveEntry, failure: IOException?) {
                            if (failure != null) throw failure
                        }
                    }, true)
                }
                passwordVerified = true
            }
        }
        return archive ?: withPassword { it }
    }

    private fun extractNative(
        opened: IInArchive,
        selected: List<ArchiveEntry>,
        callback: ExtractionCallback,
        testMode: Boolean = false
    ) {
        val operationThread = Thread.currentThread()
        extracted = true
        source.operationThread = operationThread
        openCallback?.setOperationThread(operationThread)
        val byIndex = selected.associateBy { it.contentIndex }
        try {
            opened.extract(selected.map { it.contentIndex }.toIntArray(), testMode,
                object : IArchiveExtractCallback, ICryptoGetTextPassword {
                    @Volatile
                    private var current: ArchiveEntry? = null

                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? =
                        nativeCallback(operationThread) {
                            current = byIndex[index]?.takeIf { mode != ExtractAskMode.SKIP }
                            val entry = current ?: return@nativeCallback null
                            if (testMode) return@nativeCallback null
                            val output = callback.openOutput(entry) ?: return@nativeCallback null
                            ISequentialOutStream { data ->
                                nativeCallback(operationThread) {
                                    output.write(data)
                                    data.size
                                }
                            }
                        }

                    override fun prepareOperation(mode: ExtractAskMode) = nativeCallback(operationThread) {}

                    override fun setOperationResult(result: ExtractOperationResult) = nativeCallback(operationThread) {
                        val entry = current ?: return@nativeCallback
                        val failure = when {
                            result == ExtractOperationResult.OK -> null
                            result == ExtractOperationResult.WRONG_PASSWORD
                                || entry.isEncrypted && result in listOf(
                                    ExtractOperationResult.DATAERROR, ExtractOperationResult.CRCERROR
                                ) -> ArchivePassphraseException(passwords.isNotEmpty())
                            else -> IOException("Unable to extract ${entry.name}: $result")
                        }
                        callback.onResult(entry, failure)
                        if (failure != null) throw failure
                    }

                    override fun setTotal(total: Long) = nativeCallback(operationThread) {}
                    override fun setCompleted(complete: Long) = nativeCallback(operationThread) {}
                    override fun cryptoGetTextPassword(): String = nativeCallback(operationThread) {
                        currentPassword ?: throw ArchivePassphraseException(false)
                    }
                })
        } catch (e: SevenZipException) {
            throw e.toIOException()
        }
    }

    fun newInputStream(name: String): InputStream? {
        val entry = entries().firstOrNull { it.name == name } ?: return null
        val opened = prepareExtraction(listOf(entry))
        return EntryInputStream(opened, entry).also { inputStream = it; it.start() }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("The archive is closed")
    }

    private fun closeArchive() {
        val opened = archive
        archive = null
        metadata = null
        passwordVerified = false
        extracted = false
        try {
            try {
                opened?.close()
            } catch (e: SevenZipException) {
                throw e.toIOException()
            }
        } finally {
            openCallback?.close()
            openCallback = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            try {
                inputStream?.close()
            } finally {
                closeArchive()
            }
        } finally {
            source.close()
        }
    }

    private inner class OpenCallback(private val password: String?) :
        IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword, Closeable {
        private val volumes = mutableMapOf<String, ArchiveSource>()
        @Volatile private var operationThread = Thread.currentThread()
        @Volatile
        var passwordRequested = false
            private set

        fun setOperationThread(thread: Thread) {
            operationThread = thread
            volumes.values.forEach { it.operationThread = thread }
        }

        override fun setTotal(files: Long?, bytes: Long?) = nativeCallback(operationThread) {}
        override fun setCompleted(files: Long?, bytes: Long?) = nativeCallback(operationThread) {}
        override fun cryptoGetTextPassword(): String = nativeCallback(operationThread) {
            passwordRequested = true
            password ?: throw ArchivePassphraseException(false)
        }

        override fun getProperty(propID: PropID): Any? = nativeCallback(operationThread) {
            when (propID) {
                // The native TAR handler requires a .tar name even when explicitly opening
                // a valid empty TAR whose on-disk file was renamed to another extension.
                PropID.NAME -> if (forcedFormat == ArchiveFormat.TAR
                    && !source.name.endsWith(".tar", true)) source.name + ".tar" else source.name
                PropID.IS_FOLDER -> false
                PropID.SIZE -> source.size
                PropID.ATTRIBUTES -> 0
                PropID.LAST_ACCESS_TIME, PropID.CREATION_TIME, PropID.LAST_MODIFICATION_TIME -> Date(0)
                else -> null
            }
        }

        @Synchronized
        override fun getStream(filename: String): IInStream? = nativeCallback(operationThread) {
            // Volume names come from untrusted archive headers. They may only name siblings.
            if (filename.isEmpty() || filename == "." || filename == ".."
                || filename.any { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) {
                throw IOException("Invalid archive volume name")
            }
            volumes[filename]?.let { return@nativeCallback it.stream }
            val volume = try {
                openSource(file.resolveSibling(filename), false)
            } catch (e: NoSuchFileException) {
                return@nativeCallback null
            } catch (e: FileNotFoundException) {
                return@nativeCallback null
            }
            volume.operationThread = operationThread
            volumes[filename] = volume
            volume.stream
        }

        override fun close() {
            var failure: IOException? = null
            for (volume in volumes.values) {
                try {
                    volume.close()
                } catch (e: IOException) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            volumes.clear()
            failure?.let { throw it }
        }
    }

    /** A bounded push/pull adapter. Native callbacks may originate on different threads. */
    private inner class EntryInputStream(opened: IInArchive, entry: ArchiveEntry) : InputStream() {
        private val queue = ArrayBlockingQueue<ByteArray>(8)
        @Volatile private var finished = false
        @Volatile private var streamClosed = false
        @Volatile private var failure: IOException? = null
        private var current = ByteArray(0)
        private var offset = 0
        private val worker = Thread({
            try {
                extractNative(opened, listOf(entry), object : ExtractionCallback {
                    override fun openOutput(entry: ArchiveEntry): OutputStream = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            var position = offset
                            while (position < offset + length) {
                                val end = minOf(position + 8192, offset + length)
                                val data = bytes.copyOfRange(position, end)
                                while (true) {
                                    checkInterrupted()
                                    if (streamClosed) throw InterruptedIOException("Entry stream closed")
                                    try {
                                        if (queue.offer(data, 100, TimeUnit.MILLISECONDS)) break
                                    } catch (e: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        throw InterruptedIOException().apply { initCause(e) }
                                    }
                                }
                                position = end
                            }
                        }
                    }

                    override fun onResult(entry: ArchiveEntry, failure: IOException?) {
                        if (failure != null) throw failure
                    }
                })
            } catch (e: Throwable) {
                failure = e as? IOException ?: IOException("Unable to extract the archive entry", e)
            } finally {
                finished = true
            }
        }, "archive-entry-reader").apply { isDaemon = true }

        fun start() {
            try {
                worker.start()
            } catch (e: Throwable) {
                inputStream = null
                throw IOException("Unable to start archive extraction", e)
            }
        }

        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
            if (streamClosed) throw IOException("The archive entry stream is closed")
            if (len == 0) return 0
            while (offset == current.size) {
                checkInterrupted()
                val data = try {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException().apply { initCause(e) }
                }
                if (data != null) {
                    current = data
                    offset = 0
                } else if (finished) {
                    failure?.let { throw it }
                    return -1
                }
            }
            val count = minOf(len, current.size - offset)
            current.copyInto(bytes, off, offset, offset + count)
            offset += count
            return count
        }

        override fun available(): Int = current.size - offset

        override fun close() {
            if (streamClosed) return
            streamClosed = true
            queue.clear()
            if (!finished) worker.interrupt()
            joinUninterruptibly(worker)
            if (inputStream === this) inputStream = null
        }
    }

    /** Only raw, unencrypted compression wrappers are materialized for a seekable inner TAR. */
    private fun unwrapTar(): SevenZipArchiveReader? {
        val entries = entries()
        val format = archive!!.archiveFormat
        // These additional native handlers have no corresponding Java enum value. Hxs is
        // also enum-less, but its ITOLITLS header is a container rather than a raw stream.
        val raw = format in RAW_FORMATS || format == null && (
            source.magic.hasSignature(XZ_SIGNATURE) || source.magic.hasSignature(PPMD_SIGNATURE)
                || source.magic.firstOrNull() in listOf(0.toByte(), 1.toByte())
            )
        if (!raw || entries.size != 1 || entries[0].isDirectory) return null
        val entry = entries.single()
        val tarName = entry.name.endsWith(".tar", true) || TAR_SUFFIXES.any {
            source.name.endsWith(it, true)
        }
        val input = newInputStream(entry.name)!!
        var temporary: File? = null
        try {
            input.use {
                val header = ByteArray(512)
                var count = 0
                while (count < header.size) {
                    checkInterrupted()
                    val read = input.read(header, count, header.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count != header.size || !isTarHeader(header, tarName)) return null
                val cache = newTemporaryFile("archive-tar-")
                temporary = cache
                cache.outputStream().use { output ->
                    output.write(header)
                    copyInterruptibly(input, output)
                }
            }
            val nestedSource = temporarySource(temporary!!, entry.name)
            temporary = null
            val nested = SevenZipArchiveReader(file, nestedSource, passwords, ArchiveFormat.TAR)
            try {
                nested.entries()
                close()
                return nested
            } catch (e: Throwable) {
                nested.close()
                throw e
            }
        } finally {
            temporary?.delete()
            // A prefix probe cancels its extraction worker. Reopen before another extraction.
            if (!closed) closeArchive()
        }
    }

    companion object {
        private const val MAX_SYMBOLIC_LINK_SIZE = 64 * 1024
        private const val MAX_PAX_HEADER_SIZE = 1024 * 1024
        private val PAX_METADATA_KEYS = setOf(
            "path", "linkpath", "size", "mtime", "atime", "hdrcharset",
            "SCHILY.realsize", "SCHILY.filetype"
        )
        private val XZ_SIGNATURE = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)
        private val PPMD_SIGNATURE = byteArrayOf(0x8F.toByte(), 0xAF.toByte(), 0xAC.toByte(), 0x84.toByte())
        private val ARCHIVE_SIGNATURES = listOf(
            byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04), byteArrayOf(0x50, 0x4B, 0x05, 0x06),
            byteArrayOf(0x50, 0x4B, 0x07, 0x08),
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07),
            byteArrayOf(0x1F, 0x8B.toByte()), byteArrayOf(0x1F, 0x9D.toByte()), byteArrayOf(0x60, 0xEA.toByte()),
            byteArrayOf(0xED.toByte(), 0xAB.toByte(), 0xEE.toByte(), 0xDB.toByte()),
            PPMD_SIGNATURE,
            XZ_SIGNATURE
        ) + listOf("BZh", "MSCF", "ITSF", "ITOLITLS", "!<arch", "xar!", "MSWIM", "070701", "070702", "070707")
            .map { it.toByteArray(Charsets.US_ASCII) }
        private val ARCHIVE_EXTENSIONS = listOf(
            "7z", "zip", "jar", "apk", "rar", "arj", "tar", "gz", "gzip", "bz2", "bzip2",
            "xz", "lzma", "lzma86", "cab", "chm", "cpio", "ar", "a", "deb", "lib", "fat",
            "hfs", "iso", "lzh", "lha", "ntfs", "rpm", "udf", "wim", "xar", "z", "pmd", "hxs",
            "tgz", "tbz", "tbz2", "txz", "tlz", "taz"
        )
        private val RAW_FORMATS = setOf(ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.LZMA, ArchiveFormat.Z)
        private val TAR_SUFFIXES = listOf(".tgz", ".tbz", ".tbz2", ".txz", ".tlz", ".taz", ".tar.gz", ".tar.gzip", ".tar.bz2", ".tar.bzip2", ".tar.xz", ".tar.lzma", ".tar.lzma86", ".tar.pmd", ".tar.z")

        fun open(file: Path, passwords: List<String>): SevenZipArchiveReader {
            checkInterrupted()
            SevenZipRuntime.ensureInitialized()
            var reader = SevenZipArchiveReader(file, openSource(file, true), passwords)
            try {
                try {
                    reader.entries()
                } catch (e: UnrecognizedArchiveException) {
                    if (reader.source.isEmptyTar()) {
                        reader.forcedFormat = ArchiveFormat.TAR
                        reader.entries()
                    } else {
                        if (!file.isFirstArchiveVolume || hasArchiveIdentity(reader.source)) throw e
                        // A plain .001 series has no inner archive signature. Let the native
                        // Split handler request its further volumes individually.
                        reader.close()
                        reader = SevenZipArchiveReader(file, openSource(file, false), passwords, ArchiveFormat.SPLIT)
                        reader.entries()
                    }
                }
                return reader.unwrapTar() ?: reader
            } catch (e: Throwable) {
                try {
                    reader.close()
                } catch (closeFailure: Throwable) {
                    e.addSuppressed(closeFailure)
                }
                throw e
            }
        }

        private fun rawEntryName(name: String): String {
            val tarSuffix = mapOf(".tgz" to ".tar", ".tbz" to ".tar", ".tbz2" to ".tar",
                ".txz" to ".tar", ".tlz" to ".tar", ".taz" to ".tar")
            tarSuffix.entries.firstOrNull { name.endsWith(it.key, true) }?.let {
                return name.dropLast(it.key.length) + it.value
            }
            val suffix = listOf(".gzip", ".bzip2", ".lzma86", ".lzma", ".pmd", ".bz2", ".gz", ".xz", ".Z", ".001")
                .firstOrNull { name.endsWith(it, true) }
            return (if (suffix != null) name.dropLast(suffix.length) else name).ifEmpty { "data" }
        }

        private fun isTarHeader(header: ByteArray, allowEmpty: Boolean): Boolean {
            if (header.all { it == 0.toByte() }) return allowEmpty
            val checksum = header.copyOfRange(148, 156).toString(Charsets.US_ASCII)
                .trim(' ', '\u0000').toLongOrNull(8) ?: return false
            var sum = 0L
            var signedSum = 0L
            for (i in header.indices) {
                sum += if (i in 148..155) 32 else header[i].toInt() and 0xFF
                signedSum += if (i in 148..155) 32 else header[i].toInt()
            }
            return checksum == sum || checksum == signedSum
        }

        private fun hasArchiveIdentity(source: ArchiveSource): Boolean =
            ARCHIVE_EXTENSIONS.any { source.name.endsWith(".$it", true) }
                || ARCHIVE_SIGNATURES.any { source.magic.hasSignature(it) }
                || source.magic.hasSignature("ustar".toByteArray(Charsets.US_ASCII), 257)
                || source.magic.hasSignature("NTFS    ".toByteArray(Charsets.US_ASCII), 3)
                || source.magic.size == 512 && isTarHeader(source.magic, false)

        private fun ByteArray.hasSignature(signature: ByteArray, offset: Int = 0): Boolean =
            size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

        private fun openSource(file: Path, combineVolumes: Boolean): ArchiveSource {
            val name = if (combineVolumes) file.archiveFileNameWithoutVolume() else file.fileName.toString()
            var channel: SeekableByteChannel? = null
            var successful = false
            try {
                channel = if (combineVolumes) file.newArchiveByteChannel() else file.newByteChannel()
                return ArchiveSource(channel, channel, name).also { successful = true }
            } catch (e: IOException) {
                if (e is InterruptedIOException || e is ClosedByInterruptException) throw e
            } catch (e: UnsupportedOperationException) {
                // A document or remote provider can support sequential reads without seeking.
            } finally {
                if (!successful) channel?.close()
            }
            val temporary = newTemporaryFile("archive-source-")
            try {
                (if (combineVolumes) file.newArchiveInputStream() else file.newInputStream()).use { input ->
                    temporary.outputStream().use { output -> copyInterruptibly(input, output) }
                }
                return temporarySource(temporary, name)
            } catch (e: Throwable) {
                temporary.delete()
                throw e
            }
        }

        private fun newTemporaryFile(prefix: String): File {
            val context = if (isRunningAsRoot) rootContext else application
            return File.createTempFile(prefix, ".tmp", context.cacheDir)
        }

        private fun temporarySource(file: File, name: String): ArchiveSource {
            val randomAccess = RandomAccessFile(file, "r")
            val owner = Closeable {
                try {
                    randomAccess.close()
                } finally {
                    file.delete()
                }
            }
            try {
                return ArchiveSource(FileChannels.from(randomAccess.channel), owner, name)
            } catch (e: Throwable) {
                owner.close()
                throw e
            }
        }

        private fun copyInterruptibly(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) return
                if (count == 0) throw IOException("The archive source made no read progress")
                output.write(buffer, 0, count)
            }
        }

        private fun Date.toFileTime(): FileTime = FileTime.fromMillis(time)

        private inline fun <T> nativeCallback(operationThread: Thread?, block: () -> T): T = try {
            checkInterrupted(operationThread)
            block()
        } catch (e: SevenZipException) {
            throw e
        } catch (e: Exception) {
            throw SevenZipException(e)
        }

        private fun checkInterrupted(operationThread: Thread? = null) {
            if (Thread.currentThread().isInterrupted || operationThread?.isInterrupted == true) {
                throw InterruptedIOException()
            }
        }

        private fun joinUninterruptibly(thread: Thread) {
            var interrupted = Thread.interrupted()
            try {
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private class ArchiveSource(
        val channel: SeekableByteChannel,
        private val owner: Closeable,
        val name: String
    ) : Closeable {
        @Volatile var operationThread: Thread? = Thread.currentThread()
        val size = channel.size()
        val magic: ByteArray
        private var closed = false

        init {
            val header = ByteBuffer.allocate(512)
            channel.position(0)
            while (header.hasRemaining()) {
                checkInterrupted()
                val count = channel.read(header)
                if (count < 0) break
                if (count == 0) throw IOException("The archive source made no read progress")
            }
            channel.position(0)
            magic = header.array().copyOf(header.position())
        }

        val stream = object : IInStream {
            @Synchronized
            override fun read(data: ByteArray): Int = nativeCallback(operationThread) {
                if (data.isEmpty()) return@nativeCallback 0
                val count = channel.read(ByteBuffer.wrap(data))
                if (count == 0) throw IOException("The archive source made no read progress")
                count.coerceAtLeast(0)
            }

            @Synchronized
            override fun seek(offset: Long, seekOrigin: Int): Long = nativeCallback(operationThread) {
                val base = when (seekOrigin) {
                    IInStream.SEEK_SET -> 0L
                    IInStream.SEEK_CUR -> channel.position()
                    IInStream.SEEK_END -> size
                    else -> throw IOException("Invalid archive seek origin")
                }
                val position = Math.addExact(base, offset)
                if (position < 0) throw IOException("Invalid archive seek position")
                channel.position(position)
                position
            }

            // The enclosing source owns the channel until all native handles are closed.
            override fun close() {}
        }

        fun isEmptyTar(): Boolean {
            if (size < 1024 || size % 512 != 0L || magic.any { it != 0.toByte() }) return false
            channel.position(0)
            try {
                val buffer = ByteBuffer.allocate(32 * 1024)
                var read = 0L
                while (read < size) {
                    checkInterrupted()
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), size - read).toInt())
                    val count = channel.read(buffer)
                    if (count <= 0) throw IOException("The archive source ended unexpectedly")
                    for (i in 0 until count) if (buffer.get(i) != 0.toByte()) return false
                    read += count
                }
                return true
            } finally {
                channel.position(0)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            owner.close()
        }
    }

    private class UnrecognizedArchiveException(cause: IOException) : IOException(cause.message, cause)
}
