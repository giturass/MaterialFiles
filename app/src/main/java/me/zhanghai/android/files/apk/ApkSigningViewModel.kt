/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import android.content.Context
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

/** The dialog owns the UI; only this model invokes blocking APK and keystore operations. */
class ApkSigningViewModel : ViewModel() {
    private val _state = MutableStateFlow<ApkSigningUiState?>(null)
    val state = _state.asStateFlow()

    private lateinit var context: Context
    private var operationJob: Job? = null

    fun initialize(
        context: Context,
        form: ApkSigningForm,
        interrupted: Boolean,
        preserveOptions: Boolean
    ) {
        // Fragment recreation must neither repeat a signing operation nor discard its result.
        if (_state.value != null) {
            return
        }
        this.context = context.applicationContext
        _state.value = ApkSigningUiState(form = form)
        inspect(
            if (interrupted) this.context.getString(R.string.apk_signing_restart) else null,
            resetOptions = !preserveOptions
        )
    }

    fun updateForm(update: (ApkSigningForm) -> ApkSigningForm) {
        _state.update { state ->
            if (state?.operation != null || state?.result != null) state
            else state?.copy(form = update(state.form))
        }
    }

    fun selectKeyStore(path: Path, aliases: List<String> = emptyList(), format: String = "AUTO") {
        _state.update { state ->
            if (state?.operation != null || state?.result != null) state else state?.copy(
                form = state.form.copy(
                    keyStore = path,
                    alias = aliases.firstOrNull().orEmpty(),
                    keyStoreFormat = format,
                    keyOptionsExpanded = false,
                    useDifferentKeyPassword = false
                ),
                aliases = aliases,
                message = null
            )
        }
    }

    fun onPrivateKeyDeleted(path: Path) {
        _state.update { state ->
            if (state?.form?.keyStore != path) state else state.copy(
                form = state.form.copy(keyStore = null, alias = "", keyStoreFormat = "AUTO",
                    useDifferentKeyPassword = false, keyOptionsExpanded = false),
                aliases = emptyList(),
                message = context.getString(R.string.apk_key_selected_deleted)
            )
        }
    }

    fun readAliases(password: CharArray) {
        val form = checkNotNull(_state.value).form
        val path = form.keyStore
        if (path == null) {
            password.fill('\u0000')
            showMessage(context.getString(R.string.apk_signing_required_key_store))
            return
        }
        runOperation(
            ApkSigningOperation.ALIASES, listOf(password),
            { ApkOperations.readAliases(context, path, form.keyStoreFormat, password) }
        ) { aliases ->
            val alias = form.alias.takeIf { it in aliases } ?: aliases.firstOrNull().orEmpty()
            _state.update { state ->
                state?.copy(
                    aliases = aliases,
                    form = state.form.copy(
                        alias = alias,
                        useDifferentKeyPassword = state.form.useDifferentKeyPassword
                            && alias == form.alias
                    ),
                    message = context.getString(
                        if (aliases.isEmpty()) R.string.apk_signing_no_aliases
                        else R.string.apk_signing_aliases_loaded
                    )
                )
            }
        }
    }

    fun sign(storePassword: CharArray, keyPassword: CharArray) {
        val state = checkNotNull(_state.value)
        val form = state.form
        val inspection = state.inspection
        if (state.operation != null || state.result != null || inspection == null) {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
            return
        }
        val keyStore = form.keyStore
        val directory = form.outputDirectory
        if (keyStore == null || directory == null) {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
            showMessage(context.getString(
                if (keyStore == null) R.string.apk_signing_required_key_store
                else R.string.apk_signing_no_output_directory
            ))
            return
        }
        val actualKeyPassword = if (form.useDifferentKeyPassword && keyPassword.isNotEmpty()) {
            keyPassword
        } else {
            storePassword.copyOf()
        }
        runOperation(
            ApkSigningOperation.SIGN, listOf(storePassword, keyPassword, actualKeyPassword),
            {
                val request = ApkSignRequest(
                    form.input, directory, form.outputName, keyStore, form.keyStoreFormat, form.alias,
                    storePassword, actualKeyPassword, inspection.minSdk, form.v1, form.v2, form.v3,
                    targetPackageName = form.targetPackageName.takeIf { form.coexistence },
                    inputs = form.inputs, editMode = form.editMode
                )
                ApkOperations.sign(context, request, ::onProgress)
            }
        ) { result ->
            // ApkOperations publishes only a separately saved, fully verified output APK.
            check(result.inspection.verification.verified) {
                context.getString(R.string.apk_signing_invalid)
            }
            _state.update { it?.copy(result = result, message = null) }
        }
    }

    fun cancel() {
        if (_state.value?.operation == null) {
            return
        }
        _state.update { it?.copy(canceling = true) }
        operationJob?.cancel()
    }

    fun showMessage(message: String) {
        _state.update { it?.copy(message = message) }
    }

    private fun inspect(restoredMessage: String? = null, resetOptions: Boolean = true) {
        val form = checkNotNull(_state.value).form
        val input = form.input
        runOperation(
            ApkSigningOperation.INSPECT, emptyList(),
            { ApkOperations.inspect(context, input, ::onProgress, form.coexistence, form.inputs, form.editMode) }
        ) { inspection ->
            _state.update { state ->
                state?.copy(
                    inspection = inspection,
                    inspectionError = null,
                    form = if (resetOptions) state.form.copy(
                        v1 = inspection.minSdk < 24,
                        targetPackageName = if (state.form.coexistence) {
                            inspection.packageName + ".clone"
                        } else ""
                    ) else state.form,
                    message = restoredMessage
                )
            }
        }
    }

    private fun onProgress(progress: ApkProgress) {
        _state.update { it?.copy(progress = progress) }
    }

    private fun <T> runOperation(
        operation: ApkSigningOperation,
        passwords: List<CharArray>,
        action: () -> T,
        onSuccess: (T) -> Unit
    ) {
        if (_state.value?.operation != null) {
            passwords.forEach { it.fill('\u0000') }
            return
        }
        _state.update {
            it?.copy(operation = operation, canceling = false, progress = null, message = null)
        }
        // Enter try/finally immediately so even cancellation before IO dispatch erases passwords.
        operationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = runInterruptible(Dispatchers.IO) { action() }
                onSuccess(result)
            } catch (exception: CancellationException) {
                _state.update {
                    it?.copy(message = context.getString(R.string.apk_signing_canceled))
                }
            } catch (exception: Exception) {
                // Keystore exceptions may contain user input; never log request objects or keys.
                if (!isActive || _state.value?.canceling == true) {
                    // Interrupted file IO may report InterruptedIOException rather than a
                    // coroutine CancellationException. It is still a requested cancellation.
                    _state.update {
                        it?.copy(message = context.getString(R.string.apk_signing_canceled))
                    }
                } else {
                    val message = exception.localizedMessage ?: exception.javaClass.simpleName
                    _state.update {
                        it?.copy(
                            inspectionError = if (operation == ApkSigningOperation.INSPECT) message
                                else it.inspectionError,
                            message = context.getString(R.string.apk_signing_error_format, message)
                        )
                    }
                }
            } finally {
                passwords.forEach { it.fill('\u0000') }
                _state.update { it?.copy(operation = null, progress = null, canceling = false) }
            }
        }
    }

    companion object {
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun isValidTargetPackageName(name: String): Boolean =
            name.length <= 255 && PACKAGE_NAME_PATTERN.matches(name)

        fun suggestedOutputName(input: Path, coexistence: Boolean = false): String {
            val name = input.fileName.toString()
            val baseName = name.substringBeforeLast('.', name)
            return baseName + if (coexistence) "-clone.apk" else "-signed.apk"
        }
    }
}

data class ApkSigningForm(
    val input: Path,
    val inputs: List<Path> = listOf(input),
    val editMode: ApkEditMode = ApkEditMode.NONE,
    val coexistence: Boolean = false,
    val targetPackageName: String = "",
    val outputName: String = when (editMode) {
        ApkEditMode.BUILD -> input.fileName.toString() + "-built.apk"
        ApkEditMode.PROTECT -> input.fileName.toString().substringBeforeLast('.') + "-protected.apk"
        ApkEditMode.REFACTOR -> input.fileName.toString().substringBeforeLast('.') + "-refactored.apk"
        ApkEditMode.MERGE -> input.fileName.toString().substringBeforeLast('.') + "-merged.apk"
        else -> ApkSigningViewModel.suggestedOutputName(input, coexistence)
    },
    val keyStore: Path? = null,
    val keyStoreFormat: String = "AUTO",
    val alias: String = "",
    val v1: Boolean = false,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val keyOptionsExpanded: Boolean = false,
    val useDifferentKeyPassword: Boolean = false
) {
    val outputDirectory: Path?
        get() = input.parent
}

data class ApkSigningUiState(
    val form: ApkSigningForm,
    val operation: ApkSigningOperation? = null,
    val progress: ApkProgress? = null,
    val canceling: Boolean = false,
    val inspection: ApkInspection? = null,
    val inspectionError: String? = null,
    val aliases: List<String> = emptyList(),
    val message: String? = null,
    val result: ApkSignResult? = null
)

enum class ApkSigningOperation { INSPECT, ALIASES, SIGN }
