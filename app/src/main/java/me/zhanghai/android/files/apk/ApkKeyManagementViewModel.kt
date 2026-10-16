/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.zhanghai.android.files.R

/** Owns key operations independently of the APK form and never retains a password in UI state. */
class ApkKeyManagementViewModel : ViewModel() {
    private val _state = MutableStateFlow(ApkKeyManagementState())
    val state = _state.asStateFlow()
    private lateinit var context: Context
    private var initialized = false
    private var operationJob: Job? = null
    private var previewPending = false

    fun initialize(context: Context, initialState: ApkKeyManagementState, interrupted: Boolean) {
        if (initialized) return
        initialized = true
        this.context = context.applicationContext
        _state.value = initialState
        previewPending = initialState.importSource != null
        refresh(if (interrupted) context.getString(R.string.apk_key_operation_interrupted) else null)
    }

    fun refresh(message: String? = null) {
        runOperation(ApkKeyOperation.LOAD, action = { ApkOperations.listPrivateKeys(context) }) { keys ->
            _state.update { state ->
                // A process may end after unlinking a key but before delivering the UI result.
                val paths = keys.map { it.path }.toSet()
                val deleted = state.pendingDelete?.takeUnless { it in paths }
                state.copy(keys = keys, loaded = true, message = message, pendingDelete = null,
                    checkedPaths = state.checkedPaths.intersect(paths),
                    selectedPath = state.selectedPath.takeUnless { it == deleted }, deletedPath = deleted)
            }
        }
    }

    fun prepareImport(source: Path?) {
        if (_state.value.operation != null && _state.value.operation != ApkKeyOperation.LOAD) return
        previewPending = source != null
        _state.update { it.copy(importSource = source, importPreview = null, message = null,
            creatingKey = false, selectionMode = false, checkedPaths = emptySet()) }
        if (_state.value.operation == null) readImportPreview()
    }

    private fun readImportPreview() {
        if (!previewPending) return
        previewPending = false
        val source = _state.value.importSource ?: return
        runOperation(ApkKeyOperation.PREVIEW, action = {
            ApkOperations.previewKeyImport(context, source)
        }) { entries -> _state.update { it.copy(importPreview = entries) } }
    }

    fun importKeys(commonPassword: CharArray, overrides: Map<String, CharArray>) {
        val state = _state.value
        val source = state.importSource
        val preview = state.importPreview
        if (source == null || preview.isNullOrEmpty() || state.operation != null) {
            commonPassword.fill('\u0000')
            overrides.values.forEach { it.fill('\u0000') }
            return
        }
        val passwords = preview.associate { it.id to (overrides[it.id] ?: commonPassword).copyOf() }
        // Copy only the selected inputs; passwords never enter StateFlow or saved instance state.
        commonPassword.fill('\u0000')
        overrides.values.forEach { it.fill('\u0000') }
        runOperation(ApkKeyOperation.IMPORT, passwords.values.toList(), {
            ApkOperations.importPrivateKeys(context, source, passwords)
        }) { imported ->
            val first = imported.first()
            val paths = imported.map { it.key.path }.toSet()
            val addedCount = imported.count { !it.reused }
            val skippedCount = preview.size - addedCount
            val message = when {
                addedCount == 0 && preview.size == 1 ->
                    context.getString(R.string.apk_key_existing_selected, first.key.displayName)
                addedCount == 0 ->
                    context.getString(R.string.apk_key_import_all_duplicates, skippedCount)
                skippedCount > 0 ->
                    context.getString(R.string.apk_key_import_deduplicated, addedCount, skippedCount)
                imported.size == 1 ->
                    context.getString(R.string.apk_key_imported, first.key.displayName)
                else -> context.getString(R.string.apk_key_batch_imported, addedCount)
            }
            _state.update {
                it.copy(
                    keys = imported.map { key -> key.key } + it.keys.filter { key -> key.path !in paths },
                    selectedPath = first.key.path, importSource = null, importPreview = null,
                    selection = ApkKeySelection(first.key.path, first.aliases, first.format),
                    message = message
                )
            }
        }
    }

    fun setCreatingKey(creating: Boolean) {
        if (!canEdit()) return
        previewPending = false
        _state.update { it.copy(creatingKey = creating, importSource = null, importPreview = null,
            selectionMode = false, checkedPaths = emptySet(), message = null) }
    }

    fun setNewKeyAlias(alias: String) {
        if (canEdit()) _state.update { it.copy(newKeyAlias = alias) }
    }

    fun generateKey(alias: String, password: CharArray) {
        runOperation(ApkKeyOperation.GENERATE, listOf(password), {
            val path = ApkOperations.generateKey(context, alias, password, alias)
            val file = path.toFile()
            ApkPrivateKey(path, file.lastModified(), file.length())
        }) { key ->
            _state.update { it.copy(keys = listOf(key) + it.keys, selectedPath = key.path,
                creatingKey = false, newKeyAlias = "",
                selection = ApkKeySelection(key.path, listOf(alias), "PKCS12", created = true),
                message = context.getString(R.string.apk_signing_key_created)) }
        }
    }

    fun startSelection(path: Path) {
        if (!canEdit() || _state.value.keys.none { it.path == path }) return
        previewPending = false
        _state.update { it.copy(selectionMode = true, checkedPaths = setOf(path), creatingKey = false,
            importSource = null, importPreview = null, message = null) }
    }

    fun toggleSelection(path: Path) {
        if (!canEdit() || _state.value.keys.none { it.path == path }) return
        _state.update { state ->
            // Path is also Iterable<Path>; +/- would iterate it instead of changing one entry.
            val checkedPaths = state.checkedPaths.toMutableSet()
            if (!checkedPaths.add(path)) checkedPaths.remove(path)
            state.copy(selectionMode = true, checkedPaths = checkedPaths)
        }
    }

    fun selectAll() {
        if (canEdit()) _state.update { it.copy(selectionMode = true,
            checkedPaths = it.keys.map { key -> key.path }.toSet()) }
    }

    fun clearSelection() {
        if (canEdit()) _state.update { it.copy(checkedPaths = emptySet()) }
    }

    fun exitSelection() {
        if (canEdit()) _state.update { it.copy(selectionMode = false, checkedPaths = emptySet()) }
    }

    fun prepareExport(paths: List<Path>, asZip: Boolean): Boolean {
        if (!canEdit() || paths.isEmpty()) return false
        _state.update { it.copy(pendingExport = ApkKeyExportRequest(paths.distinct(), asZip), message = null) }
        return true
    }

    fun exportTo(destination: Uri?) {
        if (destination != null && _state.value.operation == ApkKeyOperation.LOAD) {
            // Activity results can arrive while a recreated dialog is refreshing its list.
            _state.update { it.copy(exportDestination = destination) }
            return
        }
        val request = _state.value.pendingExport ?: return
        _state.update { it.copy(pendingExport = null, exportDestination = null) }
        if (destination == null) return
        runOperation(ApkKeyOperation.EXPORT, action = {
            if (request.asZip) ApkOperations.exportPrivateKeys(context, request.paths, destination)
            else ApkOperations.exportPrivateKey(context, request.paths.single(), destination)
        }) {
            _state.update { it.copy(message = if (request.asZip) {
                context.getString(R.string.apk_key_zip_exported, request.paths.size)
            } else context.getString(R.string.apk_key_exported)) }
        }
    }

    fun deleteKey(path: Path) {
        if (!canEdit()) return
        _state.update { it.copy(pendingDelete = path) }
        runOperation(ApkKeyOperation.DELETE, action = { ApkOperations.deletePrivateKey(context, path) }) {
            _state.update {
                it.copy(keys = it.keys.filter { key -> key.path != path },
                    checkedPaths = it.checkedPaths.toMutableSet().apply { remove(path) },
                    selectedPath = it.selectedPath.takeUnless { selected -> selected == path }, deletedPath = path,
                    message = context.getString(R.string.apk_key_deleted))
            }
        }
    }

    fun consumeChanges() {
        _state.update { it.copy(selection = null, deletedPath = null,
            pendingDelete = if (it.deletedPath != null) null else it.pendingDelete) }
    }

    fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun cancel() {
        if (_state.value.operation == null || _state.value.operation == ApkKeyOperation.DELETE) return
        _state.update { it.copy(canceling = true) }
        operationJob?.cancel()
    }

    private fun canEdit(): Boolean = _state.value.operation == null && _state.value.pendingExport == null

    private fun <T> runOperation(
        operation: ApkKeyOperation,
        passwords: List<CharArray> = emptyList(),
        action: () -> T,
        onSuccess: (T) -> Unit
    ) {
        if (_state.value.operation != null) {
            passwords.forEach { it.fill('\u0000') }
            return
        }
        _state.update { it.copy(operation = operation, canceling = false, message = null) }
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = runInterruptible(Dispatchers.IO, action)
                onSuccess(result)
            } catch (_: CancellationException) {
                showMessage(context.getString(R.string.apk_signing_canceled))
            } catch (exception: Exception) {
                showMessage(if (!isActive || _state.value.canceling) {
                    context.getString(R.string.apk_signing_canceled)
                } else {
                    context.getString(R.string.apk_signing_error_format,
                        exception.localizedMessage ?: exception.javaClass.simpleName)
                })
            } finally {
                passwords.forEach { it.fill('\u0000') }
                _state.update { it.copy(operation = null, canceling = false) }
                val destination = _state.value.exportDestination
                if (destination != null) exportTo(destination) else readImportPreview()
            }
        }
    }
}

data class ApkKeySelection(
    val path: Path, val aliases: List<String>, val format: String, val created: Boolean = false
)

data class ApkKeyExportRequest(val paths: List<Path>, val asZip: Boolean)

data class ApkKeyManagementState(
    val keys: List<ApkPrivateKey> = emptyList(),
    val loaded: Boolean = false,
    val selectedPath: Path? = null,
    val importSource: Path? = null,
    val importPreview: List<ApkKeyImportEntry>? = null,
    val pendingExport: ApkKeyExportRequest? = null,
    val exportDestination: Uri? = null,
    val pendingDelete: Path? = null,
    val operation: ApkKeyOperation? = null,
    val canceling: Boolean = false,
    val message: String? = null,
    val selection: ApkKeySelection? = null,
    val deletedPath: Path? = null,
    val selectionMode: Boolean = false,
    val checkedPaths: Set<Path> = emptySet(),
    val creatingKey: Boolean = false,
    val newKeyAlias: String = ""
)

enum class ApkKeyOperation { LOAD, PREVIEW, IMPORT, GENERATE, EXPORT, DELETE }
