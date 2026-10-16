/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import android.content.Context
import android.content.res.Resources
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import brut.androlib.android.ApktoolAndroid
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.apk.core.ApkCoexistenceRewriter
import me.zhanghai.android.files.apk.core.ApkOperationCallback
import me.zhanghai.android.files.apk.core.ApkSigningService
import me.zhanghai.android.files.apk.core.ApkVerificationService
import me.zhanghai.android.files.apk.core.SigningKeyStoreService
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.linux.isLinuxPath
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** Blocking operations, invoked only through the ViewModel's interruptible IO dispatcher. */
object ApkOperations {
    private const val MAX_APK_BYTES = 0xFFFFFFFFL
    private const val MAX_KEY_STORE_BYTES = 16L * 1024 * 1024
    private const val SPACE_MARGIN = 32L * 1024 * 1024
    private val operationLock = ReentrantLock(true)

    /** Called on the app's existing background executor, without retaining an Activity. */
    fun cleanInterruptedTasks(context: Context) {
        if (!File(context.noBackupFilesDir, "apk-signing").exists()
            && !File(context.cacheDir, "apk-signing").exists()) return
        try {
            withWorkspace(context) { }
        } catch (_: Exception) {
            // Journals and private work files remain available for the next operation to retry.
        }
    }

    fun inspect(
        context: Context,
        input: Path,
        progress: (ApkProgress) -> Unit = {},
        coexistence: Boolean = false
    ): ApkInspection = withWorkspace(context, progress) { directory ->
        val callback = callback(context, progress)
        val apk = stage(context, input, File(directory, "input.apk"),
            if (coexistence) ApkCoexistenceService.MAX_INPUT_BYTES else MAX_APK_BYTES, callback)
        if (coexistence) ApkCoexistenceService.validateInput(apk, directory, callback)
        ApkSigningService().validateInput(apk, callback)
        inspectFile(context, apk, callback)
    }

    fun readAliases(
        context: Context,
        path: Path,
        format: String,
        password: CharArray
    ): List<String> = try {
        withWorkspace(context) { directory ->
            val keyStore = stage(
                context, path, File(directory, "keystore"), MAX_KEY_STORE_BYTES,
                callback(context) {}
            )
            SigningKeyStoreService().aliases(keyStore, format, password)
        }
    } finally {
        password.fill('\u0000')
    }

    fun listPrivateKeys(context: Context): List<ApkPrivateKey> = withWorkspace(context) {
        keyDirectory(context).listFiles().orEmpty()
            .filter { it.name.endsWith(".p12") && it.isFile && it.canonicalFile == it.absoluteFile }
            .map { ApkPrivateKey(Paths.get(it.path), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    fun generateKey(
        context: Context,
        alias: String,
        password: CharArray,
        commonName: String
    ): Path = try {
        withWorkspace(context) { directory ->
            val temporary = File(directory, "generated.p12")
            SigningKeyStoreService().generate(temporary, alias, password, commonName).use { }
            checkCanceled()
            val prefix = alias.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(32).ifEmpty { "key" }
            val target = File(keyDirectory(context), "$prefix-${UUID.randomUUID()}.p12")
            // Both locations are app private, on the same volume, under the operation lock.
            if (target.exists() || !temporary.renameTo(target)) {
                throw IOException(context.getString(R.string.apk_operation_key_save_failed))
            }
            Paths.get(target.path)
        }
    } finally {
        password.fill('\u0000')
    }

    fun sign(
        context: Context,
        request: ApkSignRequest,
        progress: (ApkProgress) -> Unit
    ): ApkSignResult = try {
        withWorkspace(context, progress) { directory ->
            val target = outputFile(context, request)
            val callback = callback(context, progress)
            val options = ApkSigningService.Options(request.minSdk, request.v1, request.v2, request.v3)
            val input = stage(
                context, request.input, File(directory, "input.apk"),
                if (request.targetPackageName != null) ApkCoexistenceService.MAX_INPUT_BYTES
                else MAX_APK_BYTES, callback,
                reservedCopies = 4
            )
            val signingService = ApkSigningService()
            if (request.targetPackageName != null) {
                ApkCoexistenceService.validateInput(input, directory, callback)
            }
            signingService.validateInput(input, callback)
            val original = inspectFile(context, input, callback)
            val keyStore = stage(
                context, request.keyStore, File(directory, "keystore"), MAX_KEY_STORE_BYTES,
                callback
            )
            val signed = File(directory, "verified.apk")
            var coexistenceReport: String? = null
            val verification = SigningKeyStoreService().load(
                keyStore, request.keyStoreFormat, request.storePassword, request.alias,
                request.keyPassword
            ).use { key ->
                val signingInput = request.targetPackageName?.let { targetPackage ->
                    val rebuilt = File(directory, "rebuilt.apk")
                    val config = ApktoolAndroid.configure(context)
                    val report = ApkCoexistenceService().rebuild(
                        input, rebuilt, directory, config, targetPackage, original.label,
                        manifestValueResolver(context, input, original.packageName), callback
                    )
                    coexistenceReport = report.summary
                    rebuilt
                } ?: input
                signingService.sign(signingInput, signed, directory, key, options, callback)
            }
            checkCanceled()
            val savedVerification = ApkVerificationService().verify(signed, request.minSdk, callback)
            if (!savedVerification.verified || savedVerification.certificates.map { it.sha256Fingerprint }
                != verification.certificates.map { it.sha256Fingerprint }) {
                throw IOException(context.getString(R.string.apk_operation_output_changed))
            }
            val result = inspectFile(context, signed, callback, savedVerification, request.minSdk)
            if (result.packageName != (request.targetPackageName ?: original.packageName)
                || result.versionCode != original.versionCode
                || result.versionName != original.versionName || result.minSdk != original.minSdk
                || result.targetSdk != original.targetSdk || result.label != original.label) {
                throw IOException(context.getString(R.string.apk_operation_metadata_changed))
            }
            val output = publish(context, signed, target, callback)
            ApkSignResult(output, result, coexistenceReport)
        }
    } finally {
        request.storePassword.fill('\u0000')
        request.keyPassword.fill('\u0000')
    }

    private fun inspectFile(
        context: Context,
        file: File,
        callback: ApkOperationCallback,
        verified: ApkVerificationService.Result? = null,
        checkedMinSdk: Int? = null
    ): ApkInspection {
        callback.onProgress("inspect", 0, -1)
        val manifest = ApkManifestReader.read(file)
        if (manifest.requiresSplits) {
            throw IOException(context.getString(R.string.apk_operation_split_unsupported))
        }
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val info = manager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException(context.getString(R.string.apk_operation_invalid_package))
        if (!info.splitNames.isNullOrEmpty() || info.packageName != manifest.packageName) {
            throw IOException(context.getString(R.string.apk_operation_split_unsupported))
        }
        val application = info.applicationInfo
            ?: throw IOException(context.getString(R.string.apk_operation_invalid_package))
        application.sourceDir = file.path
        application.publicSourceDir = file.path
        val label = application.loadLabel(manager).toString()
        checkCanceled()
        val verificationMinSdk = checkedMinSdk ?: manifest.minSdk
        val verification = verified
            ?: ApkVerificationService().verify(file, verificationMinSdk, callback)
        return ApkInspection(
            manifest.packageName, info.versionName.orEmpty(), manifest.versionCode, label,
            manifest.minSdk, manifest.targetSdk, file.length(), verification, verificationMinSdk
        )
    }

    private fun manifestValueResolver(
        context: Context, input: File, originalPackage: String
    ): ApkCoexistenceRewriter.ManifestValueResolver {
        // Resolve only Manifest identity/class attributes that are stored as string resources.
        // The APK's compiled resources remain unchanged.
        val resources by lazy {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(input.path, 0)
                ?.applicationInfo ?: throw IOException("无法读取 APK 的 Manifest 资源引用。")
            info.sourceDir = input.path
            info.publicSourceDir = input.path
            context.packageManager.getResourcesForApplication(info)
        }
        return ApkCoexistenceRewriter.ManifestValueResolver { reference ->
            val name = reference.removePrefix("@").removePrefix("*").removePrefix("+")
            val id = if (name.startsWith("0x")) {
                name.substring(2).toLongOrNull(16)?.takeIf { it in 1..0xFFFFFFFFL }?.toInt() ?: 0
            } else {
                @Suppress("DiscouragedApi")
                resources.getIdentifier(name, "string", originalPackage)
            }
            try {
                if (id == 0) throw Resources.NotFoundException(reference)
                resources.getString(id)
            } catch (exception: Resources.NotFoundException) {
                throw IOException("无法读取 Manifest 中的字符串资源：$reference", exception)
            }
        }
    }

    private fun outputFile(context: Context, request: ApkSignRequest): File {
        val name = request.outputName
        if (name.isEmpty() || name == "." || name == ".." || !name.endsWith(".apk", true)
            || name.any { it == '/' || it == '\\' || it.code < 32 }
            || name.toByteArray(Charsets.UTF_8).size > 255) {
            throw IOException(context.getString(R.string.apk_signing_invalid_name))
        }
        // Our local provider maps to the same paths as Os.open. Document/cloud providers cannot
        // guarantee CREATE_NEW atomically, so they are not used to publish a signed package.
        if (!request.outputDirectory.isLinuxPath) {
            throw IOException(context.getString(R.string.apk_operation_local_output_required))
        }
        val parent = request.outputDirectory.toFile().canonicalFile
        if (!parent.isDirectory) {
            throw IOException(context.getString(R.string.apk_signing_no_output_directory))
        }
        val target = File(parent, name)
        if (request.input.isLinuxPath && target == request.input.toFile().canonicalFile) {
            throw IOException(context.getString(R.string.apk_signing_input_must_differ))
        }
        if (statOrNull(target) != null) {
            throw IOException(context.getString(R.string.apk_operation_output_exists, target.path))
        }
        return target
    }

    private fun stage(
        context: Context,
        source: Path,
        target: File,
        maximumBytes: Long,
        callback: ApkOperationCallback,
        reservedCopies: Int = 1
    ): File {
        val linkOptions = if (source.isLinuxPath) arrayOf(LinkOption.NOFOLLOW_LINKS) else emptyArray()
        val before = source.readAttributes(BasicFileAttributes::class.java, *linkOptions)
        if (maximumBytes == ApkCoexistenceService.MAX_INPUT_BYTES && before.size() > maximumBytes) {
            throw IOException(context.getString(R.string.apk_operation_coexistence_too_large))
        }
        if (!before.isRegularFile || before.isSymbolicLink || before.size() < 0
            || before.size() > maximumBytes) {
            throw IOException(context.getString(R.string.apk_operation_invalid_input))
        }
        requireSpace(context, checkNotNull(target.parentFile), before.size() * reservedCopies)
        var copied = 0L
        source.newInputStream(*linkOptions).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > maximumBytes || copied > before.size()) {
                        throw IOException(context.getString(R.string.apk_operation_input_changed))
                    }
                    output.write(buffer, 0, count)
                    callback.onProgress("copy", copied, before.size())
                }
            }
        }
        val after = source.readAttributes(BasicFileAttributes::class.java, *linkOptions)
        if (copied != before.size() || !after.isRegularFile || after.isSymbolicLink
            || after.size() != before.size() || after.lastModifiedTime() != before.lastModifiedTime()
            || before.fileKey() != null && before.fileKey() != after.fileKey()) {
            throw IOException(context.getString(R.string.apk_operation_input_changed))
        }
        return target
    }

    /** Uses O_EXCL, never a check-then-rename which could overwrite another window's output. */
    private fun publish(
        context: Context,
        signed: File,
        target: File,
        callback: ApkOperationCallback
    ): Path {
        requireSpace(context, checkNotNull(target.parentFile), signed.length())
        val expectedDigest = FileInputStream(signed).use { digest(it) }
        checkCanceled()
        val journal = File(journalDirectory(context), "${UUID.randomUUID()}.json")
        val fd = try {
            openFile(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL
                or OsConstants.O_NOFOLLOW, 0x180 /* 0600 */)
        } catch (exception: ErrnoException) {
            throw IOException(context.getString(R.string.apk_operation_output_create_failed,
                target.path, exception.message.orEmpty()), exception)
        }
        var owned: OwnedOutput? = null
        var completed = false
        try {
            FileOutputStream(fd).use { output ->
                val stat = Os.fstat(fd)
                owned = OwnedOutput(
                    target, stat.st_dev, stat.st_ino, signed.length(), expectedDigest, signed
                )
                FileOutputStream(journal).use { record ->
                    record.write(checkNotNull(owned).toJson().toByteArray(Charsets.UTF_8))
                    record.fd.sync()
                }
                FileInputStream(signed).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        callback.onProgress("save", copied, signed.length())
                    }
                }
                output.fd.sync()
            }
            checkCanceled()
            val actual = checkNotNull(owned)
            if (!actual.matchesIdentity() || target.length() != actual.size
                || FileInputStream(target).use { digest(it) } != expectedDigest) {
                throw IOException(context.getString(R.string.apk_operation_output_changed))
            }
            checkCanceled()
            // A crash after the complete bytes were synced but before deleting this journal is
            // recognized during recovery by size+digest and preserves the verified output.
            completed = true
            journal.delete()
            return Paths.get(target.path)
        } finally {
            if (!completed) {
                val interrupted = Thread.interrupted()
                try {
                    if (owned?.removeIfOwned() == true) journal.delete()
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private inline fun <T> withWorkspace(
        context: Context,
        noinline progress: (ApkProgress) -> Unit = {},
        action: (File) -> T
    ): T {
        try {
            operationLock.lockInterruptibly()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Canceled")
        }
        try {
            val root = privateDirectory(context)
            RandomAccessFile(File(root, "operation.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    checkCanceled()
                    recoverOutputs(context, progress)
                    val cache = File(context.cacheDir, "apk-signing").canonicalFile
                    ensureDirectory(cache)
                    // Exclusive process/thread locking guarantees these belong to interrupted jobs.
                    cache.listFiles().orEmpty().filter { it.name.startsWith("job-") }
                        .forEach(::deleteOwnedTree)
                    val directory = File(cache, "job-${UUID.randomUUID()}")
                    ensureDirectory(directory)
                    try {
                        return action(directory)
                    } finally {
                        deleteOwnedTree(directory)
                    }
                }
            }
        } finally {
            operationLock.unlock()
        }
    }

    private fun recoverOutputs(context: Context, progress: (ApkProgress) -> Unit) {
        val directory = journalDirectory(context)
        for (journal in directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }) {
            checkCanceled()
            progress(ApkProgress(context.getString(R.string.apk_operation_recover)))
            try {
                if (journal.length() > 64 * 1024) continue
                val output = OwnedOutput.fromJson(context, JSONObject(journal.readText()))
                if (!output.matchesIdentity()) {
                    // Another file has replaced our output. Its contents are never deleted.
                    journal.delete()
                } else if (output.file.length() == output.size
                    && FileInputStream(output.file).use { digest(it) } == output.sha256) {
                    journal.delete()
                } else if (output.removeIfOwned()) {
                    journal.delete()
                }
            } catch (exception: InterruptedIOException) {
                throw exception
            } catch (_: Exception) {
                // Keep this small, password-free journal for a later retry (e.g. storage access
                // was revoked). No untrusted path is removed without its original inode identity.
            }
        }
    }

    private fun privateDirectory(context: Context): File =
        File(context.noBackupFilesDir, "apk-signing").canonicalFile.also(::ensureDirectory)

    private fun keyDirectory(context: Context): File =
        File(privateDirectory(context), "keys").also(::ensureDirectory)

    private fun journalDirectory(context: Context): File =
        File(privateDirectory(context), "pending-outputs").also(::ensureDirectory)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create APK working directory: $directory")
        }
    }

    private fun deleteOwnedTree(file: File) {
        // The root is canonical. Never traverse a symlink if an interrupted workspace contains one.
        if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
            file.listFiles()?.forEach(::deleteOwnedTree)
        }
        file.delete()
    }

    private fun requireSpace(context: Context, directory: File, needed: Long) {
        if (directory.usableSpace < needed + SPACE_MARGIN) {
            throw IOException(context.getString(R.string.apk_operation_no_space))
        }
    }

    private fun callback(context: Context, progress: (ApkProgress) -> Unit) =
        object : ApkOperationCallback {
            private var lastUpdate = 0L
            private var lastPhase: String? = null

            override fun checkCancelled() = checkCanceled()

            override fun onProgress(phase: String, completed: Long, total: Long) {
                checkCanceled()
                val now = android.os.SystemClock.elapsedRealtime()
                if (phase == lastPhase && completed != total && now - lastUpdate < 100) return
                lastPhase = phase
                lastUpdate = now
                val resource = when (phase) {
                    "copy" -> R.string.apk_operation_copy
                    "align" -> R.string.apk_operation_align
                    "sign" -> R.string.apk_operation_sign
                    "verify" -> R.string.apk_operation_verify
                    "save" -> R.string.apk_operation_save
                    "key" -> R.string.apk_signing_generating_key
                    "analyze", "coexist_analyze" -> R.string.apk_operation_analyze
                    "decode" -> R.string.apk_operation_decode
                    "rewrite", "coexist_rewrite" -> R.string.apk_operation_rewrite
                    "rebuild" -> R.string.apk_operation_rebuild
                    else -> R.string.apk_signing_inspecting
                }
                progress(ApkProgress(context.getString(resource), completed, total))
            }
        }

    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCanceled()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun checkCanceled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Canceled")
    }

    private fun statOrNull(file: File): android.system.StructStat? = try {
        Os.lstat(file.path)
    } catch (exception: ErrnoException) {
        if (exception.errno != OsConstants.ENOENT) throw IOException(exception)
        null
    }

    private fun openFile(path: String, flags: Int, mode: Int): FileDescriptor {
        return Os.open(path, flags or OsConstants.O_CLOEXEC, mode)
    }

    private class OwnedOutput(
        val file: File,
        val device: Long,
        val inode: Long,
        val size: Long,
        val sha256: String,
        val verifiedSource: File?
    ) {
        fun matchesIdentity(): Boolean {
            val stat = statOrNull(file) ?: return false
            return OsConstants.S_ISREG(stat.st_mode) && stat.st_dev == device && stat.st_ino == inode
        }

        fun removeIfOwned(): Boolean = try {
            if (!matchesIdentity()) {
                true
            } else if (matchesOriginalPrefix() && matchesIdentity()) {
                Os.remove(file.path)
                true
            } else {
                // In-place edits keep the inode. Preserve anything we cannot prove we wrote.
                false
            }
        } catch (_: Exception) {
            false
        }

        private fun matchesOriginalPrefix(): Boolean {
            val source = verifiedSource ?: return false
            if (source.canonicalFile != source.absoluteFile || !source.isFile || source.length() != size
                || FileInputStream(source).use { digest(it) } != sha256) return false
            val descriptor = openFile(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            FileInputStream(descriptor).use { output ->
                val stat = Os.fstat(descriptor)
                if (stat.st_dev != device || stat.st_ino != inode || !OsConstants.S_ISREG(stat.st_mode)
                    || stat.st_size > size) return false
                FileInputStream(source).use { input ->
                    val actual = ByteArray(64 * 1024)
                    val expected = ByteArray(actual.size)
                    while (true) {
                        checkCanceled()
                        val count = output.read(actual)
                        if (count < 0) return true
                        var read = 0
                        while (read < count) {
                            val next = input.read(expected, read, count - read)
                            if (next < 0) return false
                            read += next
                        }
                        for (index in 0 until count) if (actual[index] != expected[index]) return false
                    }
                }
            }
        }

        fun toJson(): String = JSONObject().put("path", file.path).put("device", device)
            .put("inode", inode).put("size", size).put("sha256", sha256)
            .put("source", verifiedSource?.path).toString()

        companion object {
            fun fromJson(context: Context, json: JSONObject): OwnedOutput {
                val source = json.optString("source").takeIf { it.isNotEmpty() }?.let(::File)
                val cache = File(context.cacheDir, "apk-signing").canonicalFile
                val trustedSource = source?.takeIf {
                    it.parentFile?.parentFile == cache
                        && it.parentFile?.name?.startsWith("job-") == true
                        && it.name == "verified.apk"
                }
                return OwnedOutput(
                    File(json.getString("path")), json.getLong("device"), json.getLong("inode"),
                    json.getLong("size"), json.getString("sha256"), trustedSource
                )
            }
        }
    }
}
