/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
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
    private const val MAX_KEY_STORE_BYTES = ApkKeyArchive.MAX_KEY_BYTES
    private const val SPACE_MARGIN = 32L * 1024 * 1024
    private val KEY_STORE_EXTENSIONS = ApkKeyArchive.KEY_EXTENSIONS
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
        coexistence: Boolean = false,
        inputs: List<Path> = listOf(input),
        editMode: ApkEditMode = ApkEditMode.NONE
    ): ApkInspection = withWorkspace(context, progress) { directory ->
        val callback = callback(context, progress)
        val apk = prepareInput(context, inputs, directory, editMode, coexistence, callback)
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
        val directory = keyDirectory(context)
        directory.listFiles().orEmpty()
            .filter { isPrivateKeyFile(directory, it) }
            .map(::privateKeyInfo)
            .sortedByDescending { it.lastModified }
    }

    /** Imports the original store bytes only after its integrity/password and aliases are checked. */
    fun importPrivateKey(
        context: Context,
        path: Path,
        format: String,
        password: CharArray
    ): ApkImportedKey = try {
        withWorkspace(context) { directory ->
            val temporary = stage(
                context, path, File(directory, "imported-keystore"), MAX_KEY_STORE_BYTES,
                callback(context) {}
            )
            val aliases = SigningKeyStoreService().aliases(temporary, format, password)
            if (aliases.isEmpty()) {
                throw IOException(context.getString(R.string.apk_key_operation_no_private_keys))
            }
            val key = ApkKeyArchive.describe(context, temporary, path.fileName.toString(), ::checkCanceled)
            importValidatedKeys(context, listOf(key), listOf(aliases)).single()
        }
    } finally {
        password.fill('\u0000')
    }

    fun previewKeyImport(context: Context, path: Path): List<ApkKeyImportEntry> =
        withWorkspace(context) { directory ->
            stageKeyImports(context, path, directory).map { it.preview }
        }

    /** Every current entry needs a password; IDs reject stale previews before any key is saved. */
    fun importPrivateKeys(
        context: Context, path: Path, passwords: Map<String, CharArray>
    ): List<ApkImportedKey> = try {
        withWorkspace(context) { directory ->
            val staged = stageKeyImports(context, path, directory)
            if (passwords.keys != staged.map { it.preview.id }.toSet()) {
                throw IOException(context.getString(R.string.apk_key_operation_preview_changed))
            }
            val aliases = staged.map { key ->
                // Multiple entries may intentionally share one caller-owned password array.
                val password = passwords.getValue(key.preview.id).copyOf()
                try {
                    SigningKeyStoreService().aliases(key.file, key.preview.format, password).also {
                        if (it.isEmpty()) {
                            throw IOException(context.getString(R.string.apk_key_operation_no_private_keys))
                        }
                    }
                } catch (exception: InterruptedIOException) {
                    throw exception
                } catch (exception: Exception) {
                    throw IOException(context.getString(R.string.apk_key_operation_entry_failed,
                        key.preview.name, exception.localizedMessage ?: exception.javaClass.simpleName), exception)
                } finally {
                    password.fill('\u0000')
                }
            }
            importValidatedKeys(context, staged, aliases)
        }
    } finally {
        passwords.values.forEach { it.fill('\u0000') }
    }

    /** The destination must be the newly created document returned by ACTION_CREATE_DOCUMENT. */
    fun exportPrivateKey(context: Context, path: Path, destination: Uri) {
        withWorkspace(context) { directory ->
            requirePrivateKeyFile(context, path)
            requireKeyExportUri(context, destination)
            val source = stage(
                context, path, File(directory, "exported-keystore"), MAX_KEY_STORE_BYTES,
                callback(context) {}
            )
            writeKeyExport(context, source, destination)
        }
    }

    /** Exports a selection as a ZIP whose key entries keep their original protected bytes. */
    fun exportPrivateKeys(context: Context, paths: List<Path>, destination: Uri) {
        withWorkspace(context) { directory ->
            if (paths.size !in 1..ApkKeyArchive.MAX_KEYS || paths.distinct().size != paths.size) {
                ApkKeyArchive.limits(context)
            }
            val files = paths.map { requirePrivateKeyFile(context, it) }
            requireKeyExportUri(context, destination)
            var total = 0L
            val names = mutableSetOf<String>()
            val keys = paths.mapIndexed { index, path ->
                val staged = stage(context, path, File(directory, "export-key-$index"),
                    MAX_KEY_STORE_BYTES, callback(context) {})
                val key = ApkKeyArchive.describe(context, staged, files[index].name, ::checkCanceled)
                total += key.preview.size
                if (total > ApkKeyArchive.MAX_TOTAL_BYTES) ApkKeyArchive.limits(context)
                val prefix = privateKeyPrefix(privateKeyInfo(files[index]).displayName)
                val extension = if (key.preview.format == "JKS") "jks" else "p12"
                var name = "$prefix.$extension"
                var suffix = 2
                while (!names.add(name.lowercase())) name = "$prefix (${suffix++}).$extension"
                name to staged
            }
            requireSpace(context, directory, total + 1024 * 1024)
            val archive = File(directory, "exported-keystore")
            ApkKeyArchive.write(context, keys, archive, ::checkCanceled)
            writeKeyExport(context, archive, destination)
        }
    }

    /** Removes only a managed store itself; never follows links or traverses directories. */
    fun deletePrivateKey(context: Context, path: Path) {
        withWorkspace(context) {
            val file = requirePrivateKeyFile(context, path)
            checkCanceled()
            try {
                Os.remove(file.path)
            } catch (exception: ErrnoException) {
                throw IOException(context.getString(R.string.apk_key_operation_delete_failed), exception)
            }
        }
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
            val target = savePrivateKey(context, temporary, prefix, "p12")
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
            val input = prepareInput(context, request.inputs, directory, request.editMode,
                request.targetPackageName != null, callback)
            val signingService = ApkSigningService()
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
                // Preview and compare metadata against the original bytes. Resource edits
                // invalidate that signature and belong only in the explicit signing operation.
                val edited = editResources(input, directory, request.editMode, callback)
                val signingInput = request.targetPackageName?.let { targetPackage ->
                    val rebuilt = File(directory, "rebuilt.apk")
                    val report = ApkCoexistenceService().rebuild(
                        edited, rebuilt, directory, targetPackage, original.label,
                        manifestValueResolver(context, edited, original.packageName), callback
                    )
                    coexistenceReport = report.summary
                    rebuilt
                } ?: edited
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

    private fun prepareInput(
        context: Context, inputs: List<Path>, workspace: File, mode: ApkEditMode,
        coexistence: Boolean, callback: ApkOperationCallback
    ): File {
        if (inputs.isEmpty() || inputs.size > 256) throw IOException("请选择 1 至 256 个输入文件。")
        val engine = ApkEditorService()
        if (mode == ApkEditMode.BUILD) {
            if (inputs.size != 1 || !inputs.single().isLinuxPath) {
                throw IOException("回编译需要本地 APKEditor 项目目录。")
            }
            val source = inputs.single().toFile()
            val tree = ApkEditorService.validateTree(source, callback)
            val copy = File(workspace, "project")
            requireSpace(context, workspace, tree.filter { it.isFile }.sumOf { it.length() } * 4)
            ensureDirectory(copy)
            for (file in tree) {
                checkCanceled()
                val relative = source.toPath().relativize(file.toPath()).toString()
                if (relative.isEmpty()) continue
                val target = File(copy, relative)
                if (file.isDirectory) ensureDirectory(target)
                else stage(context, Paths.get(file.path), target, ApkEditorService.MAX_EXPANDED, callback)
            }
            return File(workspace, "built.apk").also {
                engine.build(copy, it, callback)
                ApkCoexistenceService.validateInput(it, workspace, callback)
            }
        }
        val container = inputs.size == 1 && inputs.single().fileName.toString()
            .substringAfterLast('.').lowercase() in setOf("apks", "xapk", "apkm")
        val needsEditor = coexistence || container || inputs.size > 1 || mode != ApkEditMode.NONE
        if (needsEditor) {
            var bytes = 0L
            for (path in inputs) {
                val size = path.readAttributes(BasicFileAttributes::class.java).size()
                if (size < 0 || size > ApkEditorService.MAX_BYTES - bytes) {
                    throw IOException("所选 APK 的总大小超过 512 MiB。")
                }
                bytes += size
            }
            requireSpace(context, workspace, bytes * 6)
        }
        val files = inputs.mapIndexed { index, path ->
            stage(context, path, File(workspace, "input-$index.apk"),
                if (needsEditor) ApkEditorService.MAX_BYTES else MAX_APK_BYTES, callback, reservedCopies = 4)
        }
        val modules = if (container) engine.extractContainer(files.single(), File(workspace, "splits"), callback)
            else files
        val apk = if (modules.size > 1 || mode == ApkEditMode.MERGE) {
            File(workspace, "merged.apk").also { engine.merge(modules, it, workspace, callback) }
        } else modules.single()
        if (needsEditor) ApkCoexistenceService.validateInput(apk, workspace, callback)
        ApkSigningService().validateInput(apk, callback)
        if (ApkManifestReader.read(apk).requiresSplits) {
            throw IOException("缺少分包：请多选 base 和所需 split，或选择完整 APKS/XAPK/APKM 容器。")
        }
        return apk
    }

    private fun editResources(
        input: File, workspace: File, mode: ApkEditMode, callback: ApkOperationCallback
    ): File {
        if (mode != ApkEditMode.REFACTOR && mode != ApkEditMode.PROTECT) return input
        val original = ApkManifestReader.read(input)
        val engine = ApkEditorService()
        val output = File(workspace, "edited.apk")
        if (mode == ApkEditMode.PROTECT) engine.protect(input, output, callback)
        else engine.refactor(input, output, callback)
        ApkCoexistenceService.validateInput(output, workspace, callback)
        val result = ApkManifestReader.read(output)
        if (original.packageName != result.packageName || original.versionCode != result.versionCode
            || original.minSdk != result.minSdk || original.targetSdk != result.targetSdk) {
            throw IOException("处理结果改变了应用的包名、版本或 SDK。")
        }
        return output
    }

    fun editorInfo(context: Context, inputs: List<Path>, progress: (ApkProgress) -> Unit): String =
        withWorkspace(context, progress) { directory ->
            val callback = callback(context, progress)
            val engine = ApkEditorService()
            buildString {
                for ((index, path) in inputs.withIndex()) {
                    val source = stage(context, path, File(directory, "info-$index.apk"),
                        ApkEditorService.MAX_BYTES, callback)
                    val files = if (path.fileName.toString().substringAfterLast('.').lowercase()
                        in setOf("apks", "xapk", "apkm")) {
                        engine.extractContainer(source, File(directory, "info-$index"), callback)
                    } else listOf(source)
                    append(path.fileName.toString()).append("\n")
                    for (file in files) {
                        ApkCoexistenceService.validateInput(file, directory, callback)
                        ApkSigningService().validateInput(file, callback)
                        append(engine.info(file, callback)).append("\n")
                        if (length > 262144) throw IOException("APK 信息过多，请减少所选分包数量。")
                    }
                }
            }
        }

    /** A ZIP makes the decoded project use the same crash-safe publication as signed APKs. */
    fun decode(context: Context, inputs: List<Path>, outputName: String,
               progress: (ApkProgress) -> Unit): Path = withWorkspace(context, progress) { directory ->
        val source = inputs.first()
        val parentPath = source.parent ?: throw IOException("没有输出目录。")
        if (!parentPath.isLinuxPath || !outputName.endsWith(".zip", true)
            || outputName.any { it == '/' || it == '\\' || it.code < 32 }
            || outputName.toByteArray(Charsets.UTF_8).size > 255) throw IOException("请输入有效的 ZIP 文件名。")
        val target = File(parentPath.toFile().canonicalFile, outputName)
        if (statOrNull(target) != null) throw IOException("输出文件已存在。")
        val callback = callback(context, progress)
        val apk = prepareInput(context, inputs, directory, ApkEditMode.NONE, true, callback)
        ApkSigningService().validateInput(apk, callback)
        val project = File(directory, "decoded")
        ApkEditorService().decode(apk, project, callback)
        val zip = File(directory, "project.zip")
        ApkEditorService.zipProject(project, zip, callback)
        publish(context, zip, target, callback)
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
        if (maximumBytes == ApkKeyArchive.MAX_ARCHIVE_BYTES && before.size() > maximumBytes) {
            ApkKeyArchive.limits(context)
        }
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

    private fun stageKeyImports(
        context: Context, path: Path, directory: File
    ): List<ApkKeyArchive.StagedKey> {
        val source = stage(context, path, File(directory, "key-import-source"),
            ApkKeyArchive.MAX_ARCHIVE_BYTES, callback(context) {})
        return ApkKeyArchive.read(context, source, path.fileName.toString(), directory,
            ::checkCanceled) { needed -> requireSpace(context, directory, needed) }
    }

    private fun writeKeyExport(context: Context, source: File, destination: Uri) {
        FileInputStream(source).use { it.fd.sync() }
        val expectedDigest = FileInputStream(source).use { digest(it) }
        checkCanceled()
        val journal = File(keyExportJournalDirectory(context), "${UUID.randomUUID()}.json")
        // "rw" does not truncate. Reject an existing nonempty document before writing bytes.
        val descriptor = context.contentResolver.openFileDescriptor(destination, "rw")
            ?: throw IOException(context.getString(R.string.apk_key_operation_export_open_failed))
        var owned: OwnedKeyExport? = null
        var completed = false
        try {
            descriptor.use {
                val stat = Os.fstat(it.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L) {
                    throw IOException(context.getString(R.string.apk_key_operation_export_not_empty))
                }
                owned = OwnedKeyExport(
                    destination, stat.st_dev, stat.st_ino, source.length(), expectedDigest, source
                )
                FileOutputStream(journal).use { record ->
                    record.write(checkNotNull(owned).toJson().toByteArray(Charsets.UTF_8))
                    record.fd.sync()
                }
                ParcelFileDescriptor.AutoCloseOutputStream(it).use { output ->
                    FileInputStream(source).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
            }
            checkCanceled()
            if (!checkNotNull(owned).isComplete(context)) {
                throw IOException(context.getString(R.string.apk_key_operation_export_changed))
            }
            checkCanceled()
            completed = true
            journal.delete()
        } finally {
            if (!completed) {
                val interrupted = Thread.interrupted()
                try {
                    if (owned?.removeIfOwned(context) == true) journal.delete()
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
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
                    recoverKeyExports(context, progress)
                    recoverKeyImports(context, progress)
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

    private fun recoverKeyExports(context: Context, progress: (ApkProgress) -> Unit) {
        val directory = keyExportJournalDirectory(context)
        for (journal in directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }) {
            checkCanceled()
            progress(ApkProgress(context.getString(R.string.apk_operation_recover)))
            try {
                if (journal.length() > 64 * 1024) continue
                val output = OwnedKeyExport.fromJson(context, JSONObject(journal.readText()))
                if (output.isComplete(context) || output.removeIfOwned(context)) {
                    journal.delete()
                }
            } catch (exception: InterruptedIOException) {
                throw exception
            } catch (_: Exception) {
                // A revoked document grant cannot justify deleting an unverified export. Keep the
                // password-free journal; a later operation may still have access to the document.
            }
        }
    }

    private fun recoverKeyImports(context: Context, progress: (ApkProgress) -> Unit) {
        for (journal in keyImportJournalDirectory(context).listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }) {
            checkCanceled()
            progress(ApkProgress(context.getString(R.string.apk_operation_recover)))
            try {
                if (journal.length() > 64 * 1024) continue
                val json = JSONObject(journal.readText()).getJSONArray("keys")
                if (json.length() !in 1..ApkKeyArchive.MAX_KEYS) continue
                val keys = (0 until json.length()).map {
                    OwnedImportedKey.fromJson(context, json.getJSONObject(it))
                }
                // A fully published batch survives a crash; an unfinished batch is rolled back.
                if (keys.all { it.isComplete() }
                    || keys.map { it.removeIfOwned() }.all { it }) journal.delete()
            } catch (exception: InterruptedIOException) {
                throw exception
            } catch (_: Exception) {
                // Preserve changed files and the password-free transaction record for a retry.
            }
        }
    }

    private fun privateDirectory(context: Context): File =
        File(context.noBackupFilesDir.canonicalFile, "apk-signing").also {
            ensurePrivateDirectory(context, it)
        }

    private fun keyDirectory(context: Context): File =
        File(privateDirectory(context), "keys").also { ensurePrivateDirectory(context, it) }

    private fun journalDirectory(context: Context): File =
        File(privateDirectory(context), "pending-outputs").also(::ensureDirectory)

    private fun keyExportJournalDirectory(context: Context): File =
        File(privateDirectory(context), "pending-key-exports").also {
            ensurePrivateDirectory(context, it)
        }

    private fun keyImportJournalDirectory(context: Context): File =
        File(privateDirectory(context), "pending-key-imports").also {
            ensurePrivateDirectory(context, it)
        }

    private fun ensurePrivateDirectory(context: Context, directory: File) {
        if (directory.canonicalFile != directory.absoluteFile) {
            throw IOException(context.getString(R.string.apk_key_operation_invalid_private_path))
        }
        ensureDirectory(directory)
    }

    private fun isPrivateKeyFile(directory: File, file: File): Boolean {
        if (!file.isAbsolute || file.parentFile != directory || file.canonicalFile != file.absoluteFile
            || file.extension.lowercase() !in KEY_STORE_EXTENSIONS) {
            return false
        }
        val stat = statOrNull(file) ?: return false
        return OsConstants.S_ISREG(stat.st_mode)
    }

    private fun requirePrivateKeyFile(context: Context, path: Path): File {
        if (path.isLinuxPath) {
            val file = path.toFile()
            if (isPrivateKeyFile(keyDirectory(context), file)) return file
        }
        throw IOException(context.getString(R.string.apk_key_operation_invalid_private_path))
    }

    private fun privateKeyInfo(file: File): ApkPrivateKey =
        ApkPrivateKey(Paths.get(file.path), file.lastModified(), file.length())

    private fun privateKeyPrefix(name: String): String {
        val fileName = name.substringAfterLast('/')
        val baseName = fileName.substringBeforeLast('.', fileName)
        return baseName.filter { !it.isISOControl() && it != '/' && it != '\\' && it != ':' }
            .take(64).trim().trimEnd { Character.isHighSurrogate(it) }
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "key"
    }

    /** Called only after every input password has been checked, including duplicate inputs. */
    private fun importValidatedKeys(
        context: Context, staged: List<ApkKeyArchive.StagedKey>, aliases: List<List<String>>
    ): List<ApkImportedKey> {
        val unique = mutableListOf<Int>()
        for ((index, key) in staged.withIndex()) {
            checkCanceled()
            if (unique.none { previous ->
                    val other = staged[previous]
                    key.preview.size == other.preview.size && key.sha256 == other.sha256
                        && keyFilesEqual(context, key.file, other.file, key.preview.size)
                }) unique.add(index)
        }
        val reused = arrayOfNulls<ApkPrivateKey>(unique.size)
        val directory = keyDirectory(context)
        // Prefer the oldest existing copy consistently; never migrate or remove legacy duplicates.
        val existing = directory.listFiles().orEmpty().filter { isPrivateKeyFile(directory, it) }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path })
        for (file in existing) {
            checkCanceled()
            if (reused.all { it != null }) break
            val size = file.length()
            val candidates = unique.indices.filter {
                reused[it] == null && staged[unique[it]].preview.size == size
            }
            if (candidates.isEmpty()) continue
            val sha256 = withStableKeyInput(context, file, size) { input -> digest(input) }
            val match = candidates.firstOrNull {
                val key = staged[unique[it]]
                key.sha256 == sha256 && keyFilesEqual(context, key.file, file, size)
            } ?: continue
            reused[match] = privateKeyInfo(file)
        }
        val fresh = unique.indices.filter { reused[it] == null }.map { staged[unique[it]] }
        checkCanceled()
        // The existing-file scan and all comparisons finish before the transaction can publish.
        // Only genuinely new files enter the transaction and its rollback/recovery journal.
        val saved = if (fresh.isEmpty()) emptyList() else savePrivateKeyBatch(context, fresh)
        var nextSaved = 0
        return unique.mapIndexed { resultIndex, inputIndex ->
            val existingKey = reused[resultIndex]
            ApkImportedKey(existingKey ?: privateKeyInfo(saved[nextSaved++]), aliases[inputIndex],
                staged[inputIndex].preview.format, reused = existingKey != null)
        }
    }

    /** Hash matches are confirmed against the complete original store bytes. */
    private fun keyFilesEqual(context: Context, first: File, second: File, size: Long): Boolean =
        withStableKeyInput(context, first, size) { firstInput ->
            withStableKeyInput(context, second, size) { secondInput ->
                val firstData = DataInputStream(firstInput)
                val secondData = DataInputStream(secondInput)
                val firstBytes = ByteArray(64 * 1024)
                val secondBytes = ByteArray(firstBytes.size)
                var remaining = size
                var equal = true
                while (remaining > 0) {
                    checkCanceled()
                    val count = minOf(remaining, firstBytes.size.toLong()).toInt()
                    firstData.readFully(firstBytes, 0, count)
                    secondData.readFully(secondBytes, 0, count)
                    for (index in 0 until count) {
                        if (firstBytes[index] != secondBytes[index]) equal = false
                    }
                    remaining -= count
                }
                if (firstData.read() != -1 || secondData.read() != -1) {
                    throw IOException(context.getString(R.string.apk_operation_input_changed))
                }
                equal
            }
        }

    private inline fun <T> withStableKeyInput(
        context: Context, file: File, size: Long, action: (InputStream) -> T
    ): T {
        val before = statOrNull(file)
        val modified = file.lastModified()
        if (before == null || !OsConstants.S_ISREG(before.st_mode) || before.st_size != size
            || size !in 4L..MAX_KEY_STORE_BYTES) {
            throw IOException(context.getString(R.string.apk_operation_input_changed))
        }
        val descriptor = openFile(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        FileInputStream(descriptor).use { input ->
            val opened = Os.fstat(descriptor)
            if (!sameKeyFile(before, opened)) {
                throw IOException(context.getString(R.string.apk_operation_input_changed))
            }
            val result = action(input)
            val after = statOrNull(file)
            if (after == null || !sameKeyFile(before, after)
                || !sameKeyFile(before, Os.fstat(descriptor)) || file.lastModified() != modified) {
                throw IOException(context.getString(R.string.apk_operation_input_changed))
            }
            return result
        }
    }

    private fun sameKeyFile(first: android.system.StructStat, second: android.system.StructStat): Boolean =
        OsConstants.S_ISREG(second.st_mode) && first.st_dev == second.st_dev
            && first.st_ino == second.st_ino && first.st_size == second.st_size

    private fun savePrivateKeyBatch(
        context: Context, staged: List<ApkKeyArchive.StagedKey>
    ): List<File> {
        val directory = keyDirectory(context)
        val owned = staged.map { key ->
            val prefix = privateKeyPrefix(key.preview.name)
            val extension = if (key.preview.format == "JKS") "jks" else "p12"
            val target = File(directory, "$prefix-${UUID.randomUUID()}.$extension")
            Os.chmod(key.file.path, 0x180 /* 0600 */)
            FileInputStream(key.file).use { it.fd.sync() }
            val stat = Os.lstat(key.file.path)
            OwnedImportedKey(target, stat.st_dev, stat.st_ino, key.preview.size, key.sha256)
        }
        val journal = File(keyImportJournalDirectory(context), "${UUID.randomUUID()}.json")
        var completed = false
        try {
            val records = JSONArray().apply { owned.forEach { put(it.toJson()) } }
            FileOutputStream(journal).use { output ->
                output.write(JSONObject().put("keys", records).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            for ((index, key) in staged.withIndex()) {
                checkCanceled()
                val target = owned[index].file
                if (statOrNull(target) != null || !key.file.renameTo(target)) {
                    throw IOException(context.getString(R.string.apk_operation_key_save_failed))
                }
            }
            checkCanceled()
            if (!owned.all { it.isComplete() }) {
                throw IOException(context.getString(R.string.apk_operation_key_save_failed))
            }
            checkCanceled()
            completed = true
            journal.delete()
            return owned.map { it.file }
        } finally {
            if (!completed) {
                val interrupted = Thread.interrupted()
                try {
                    if (owned.map { it.removeIfOwned() }.all { it }) journal.delete()
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun savePrivateKey(
        context: Context, temporary: File, prefix: String, extension: String
    ): File {
        val target = File(keyDirectory(context), "$prefix-${UUID.randomUUID()}.$extension")
        Os.chmod(temporary.path, 0x180 /* 0600 */)
        FileInputStream(temporary).use { it.fd.sync() }
        checkCanceled()
        // Both locations are app private, on the same volume, under the operation lock. The
        // validated bytes become visible to the manager in one rename; duplicates get new names.
        if (statOrNull(target) != null || !temporary.renameTo(target)) {
            throw IOException(context.getString(R.string.apk_operation_key_save_failed))
        }
        return target
    }

    private fun requireKeyExportUri(context: Context, uri: Uri) {
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) {
            throw IOException(context.getString(R.string.apk_key_operation_export_document_required))
        }
    }

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
                    "merge" -> R.string.apk_editor_merging
                    "refactor" -> R.string.apk_editor_refactoring
                    "protect" -> R.string.apk_editor_protecting
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

    private class OwnedImportedKey(
        val file: File, val device: Long, val inode: Long, val size: Long, val sha256: String
    ) {
        private fun matchesIdentity(): Boolean {
            val stat = statOrNull(file) ?: return false
            return OsConstants.S_ISREG(stat.st_mode) && stat.st_dev == device && stat.st_ino == inode
        }

        fun isComplete(): Boolean {
            if (!matchesIdentity()) return false
            val descriptor = openFile(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            return FileInputStream(descriptor).use { input ->
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != device || stat.st_ino != inode
                    || stat.st_size != size) return@use false
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    checkCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > size) return@use false
                    hash.update(buffer, 0, count)
                }
                copied == size && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256
                    && matchesIdentity()
            }
        }

        fun removeIfOwned(): Boolean = try {
            if (!matchesIdentity()) {
                true
            } else if (isComplete() && matchesIdentity()) {
                Os.remove(file.path)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        fun toJson(): JSONObject = JSONObject().put("path", file.path).put("device", device)
            .put("inode", inode).put("size", size).put("sha256", sha256)

        companion object {
            fun fromJson(context: Context, json: JSONObject): OwnedImportedKey {
                val file = File(json.getString("path"))
                val size = json.getLong("size")
                if (!file.isAbsolute || file.parentFile != keyDirectory(context)
                    || file.extension.lowercase() !in KEY_STORE_EXTENSIONS
                    || size !in 4L..MAX_KEY_STORE_BYTES) {
                    throw IOException(context.getString(R.string.apk_key_operation_invalid_private_path))
                }
                return OwnedImportedKey(file, json.getLong("device"), json.getLong("inode"),
                    size, json.getString("sha256"))
            }
        }
    }

    private class OwnedKeyExport(
        val uri: Uri,
        val device: Long,
        val inode: Long,
        val size: Long,
        val sha256: String,
        val source: File?
    ) {
        fun isComplete(context: Context): Boolean = open(context).use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!matchesIdentity(stat) || stat.st_size != size) return@use false
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(::matchesDigest)
        }

        fun removeIfOwned(context: Context): Boolean {
            return try {
                val canRemove = open(context).use { descriptor ->
                    val stat = Os.fstat(descriptor.fileDescriptor)
                    // The document URI now refers to somebody else's file. Leave that file alone.
                    if (!matchesIdentity(stat)) return true
                    if (stat.st_size > size) return false
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(::matchesOriginalPrefix)
                }
                if (!canRemove) {
                    false
                } else if (!open(context).use { matchesIdentity(Os.fstat(it.fileDescriptor)) }) {
                    true
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun open(context: Context): ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException(context.getString(R.string.apk_key_operation_export_open_failed))

        private fun matchesIdentity(stat: android.system.StructStat): Boolean =
            OsConstants.S_ISREG(stat.st_mode) && stat.st_dev == device && stat.st_ino == inode

        private fun matchesDigest(input: InputStream): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                checkCanceled()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > size) return false
                digest.update(buffer, 0, read)
            }
            return count == size
                && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } == sha256
        }

        private fun matchesOriginalPrefix(output: InputStream): Boolean {
            val source = source ?: return false
            if (source.canonicalFile != source.absoluteFile || !source.isFile || source.length() != size
                || !FileInputStream(source).use(::matchesDigest)) return false
            FileInputStream(source).use { input ->
                val actual = ByteArray(64 * 1024)
                val expected = ByteArray(actual.size)
                var copied = 0L
                while (true) {
                    checkCanceled()
                    val count = output.read(actual)
                    if (count < 0) return true
                    copied += count
                    if (copied > size) return false
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

        fun toJson(): String = JSONObject().put("uri", uri.toString()).put("device", device)
            .put("inode", inode).put("size", size).put("sha256", sha256)
            .put("source", source?.path).toString()

        companion object {
            fun fromJson(context: Context, json: JSONObject): OwnedKeyExport {
                val uri = Uri.parse(json.getString("uri"))
                requireKeyExportUri(context, uri)
                val size = json.getLong("size")
                if (size !in 0L..ApkKeyArchive.MAX_ARCHIVE_BYTES) {
                    throw IOException("Invalid key export size")
                }
                val source = json.optString("source").takeIf { it.isNotEmpty() }?.let(::File)
                val cache = File(context.cacheDir, "apk-signing").canonicalFile
                val trustedSource = source?.takeIf {
                    it.parentFile?.parentFile == cache
                        && it.parentFile?.name?.startsWith("job-") == true
                        && it.name == "exported-keystore"
                }
                return OwnedKeyExport(
                    uri, json.getLong("device"), json.getLong("inode"), size,
                    json.getString("sha256"), trustedSource
                )
            }
        }
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
