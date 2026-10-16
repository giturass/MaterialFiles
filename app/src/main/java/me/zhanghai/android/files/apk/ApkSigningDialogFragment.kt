/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.apk.core.ApkVerificationService
import me.zhanghai.android.files.databinding.ApkSigningDialogBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.setButtonBarEqualWidth
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getColorStateListByAttr
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.hideSoftInput
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import java.text.DateFormat
import java.util.Date
import com.google.android.material.R as MaterialR

class ApkSigningDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private val viewModel by viewModels { { ApkSigningViewModel() } }
    private var binding: ApkSigningDialogBinding? = null
    private var rendering = false
    private var renderedAliases: List<String>? = null
    private var renderedOperation: ApkSigningOperation? = null
    private var renderedMessage: String? = null
    private var detailsDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedForm = savedInstanceState?.getState<SavedForm>()
        viewModel.initialize(
            requireContext(), savedForm?.toForm()
                ?: ApkSigningForm(args.input, coexistence = args.coexistence),
            savedForm?.wasRunning == true, savedForm?.hadInspection == true
        )
        ApkKeyManagementDialogFragment.setResultListener(this) { result ->
            when (result.action) {
                ApkKeyManagementDialogFragment.Action.SELECT -> result.path?.let { path ->
                    if (result.imported || result.created || viewModel.state.value?.form?.keyStore != path) {
                        onKeyStoreSelected(path, result.aliases, result.format)
                    }
                    if (result.imported || result.created) {
                        viewModel.showMessage(getString(if (result.created)
                            R.string.apk_key_created_for_signing else R.string.apk_key_imported_for_signing))
                    }
                }
                ApkKeyManagementDialogFragment.Action.DELETE -> result.path?.let { path ->
                    if (viewModel.state.value?.form?.keyStore == path) clearKeyPasswords()
                    viewModel.onPrivateKeyDeleted(path)
                }
            }
        }
        // DialogFragment has no viewLifecycleOwner when its content is supplied by onCreateDialog.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> state?.let(::render) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), theme)
        val binding = ApkSigningDialogBinding.inflate(builder.context.layoutInflater)
        this.binding = binding
        // Save the non-sensitive form explicitly. Never let Dialog/Autofill parcel passwords.
        binding.root.isSaveFromParentEnabled = false
        binding.storePasswordEdit.isSaveFromParentEnabled = false
        binding.keyPasswordEdit.isSaveFromParentEnabled = false
        binding.chooseKeyStoreButton.setOnClickListener { chooseKeyStore() }
        binding.keyStoreFormatEdit.setSimpleItems(
            arrayOf(getString(R.string.apk_signing_key_store_auto), "PKCS12", "JKS")
        )
        binding.keyStoreFormatEdit.setOnItemClickListener { _, _, position, _ ->
            if (!rendering) {
                viewModel.updateForm { it.copy(keyStoreFormat = KEY_STORE_FORMATS[position]) }
            }
        }
        binding.aliasEdit.doAfterTextChanged {
            binding.aliasLayout.error = null
            if (!rendering) {
                val alias = it?.toString().orEmpty()
                val aliasChanged = viewModel.state.value?.form?.alias != alias
                if (aliasChanged) {
                    binding.keyPasswordEdit.text?.clear()
                }
                viewModel.updateForm { form ->
                    form.copy(alias = alias,
                        useDifferentKeyPassword = form.useDifferentKeyPassword && !aliasChanged)
                }
            }
        }
        binding.keyOptionsButton.setOnClickListener {
            viewModel.updateForm { it.copy(keyOptionsExpanded = !it.keyOptionsExpanded) }
        }
        binding.differentKeyPasswordCheck.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                if (!checked) binding.keyPasswordEdit.text?.clear()
                viewModel.updateForm { it.copy(useDifferentKeyPassword = checked) }
            }
        }
        binding.outputNameEdit.doAfterTextChanged {
            binding.outputNameLayout.error = null
            if (!rendering) {
                viewModel.updateForm { form -> form.copy(outputName = it?.toString().orEmpty()) }
            }
        }
        binding.targetPackageEdit.doAfterTextChanged {
            binding.targetPackageLayout.error = null
            if (!rendering) {
                viewModel.updateForm { form -> form.copy(targetPackageName = it?.toString().orEmpty()) }
            }
        }
        binding.v1Check.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.updateForm { it.copy(v1 = checked) }
        }
        binding.v2Check.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.updateForm { it.copy(v2 = checked) }
        }
        binding.v3Check.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.updateForm { it.copy(v3 = checked) }
        }
        binding.storePasswordEdit.doAfterTextChanged {
            binding.storePasswordLayout.error = null
        }
        binding.keyPasswordEdit.doAfterTextChanged { binding.keyPasswordLayout.error = null }
        binding.loadAliasesButton.setOnClickListener {
            viewModel.readAliases(binding.storePasswordEdit.copyPassword())
        }
        val coexistence = viewModel.state.value?.form?.coexistence == true
        return builder
            .setTitle(if (coexistence) R.string.apk_coexistence_title else R.string.apk_signing_title)
            .setView(binding.root)
            .setPositiveButton(
                if (coexistence) R.string.apk_coexistence_start else R.string.apk_signing_start, null
            )
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.apk_signing_details, null)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                window!!.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
    }

    override fun onStart() {
        super.onStart()

        val dialog = requireDialog() as AlertDialog
        dialog.setButtonBarEqualWidth(
            AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (viewModel.state.value?.result != null) dismiss() else sign()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val state = viewModel.state.value
            when {
                state?.result != null -> installApk(state.result.output)
                state?.operation != null -> viewModel.cancel()
                else -> dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showDetails() }
        viewModel.state.value?.let(::render)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        viewModel.state.value?.let { state ->
            outState.putState(SavedForm(state.form, state.operation != null, state.inspection != null))
        }
    }

    override fun onDestroyView() {
        detailsDialog?.dismiss()
        detailsDialog = null
        clearKeyPasswords()
        binding = null
        renderedAliases = null
        super.onDestroyView()
        // Dismissing the fragment clears its ViewModel and cancels IO. Rotation retains the model.
    }

    private fun chooseKeyStore() {
        ApkKeyManagementDialogFragment.show(viewModel.state.value?.form?.keyStore, this)
    }

    private fun onKeyStoreSelected(
        path: Path, aliases: List<String> = emptyList(), format: String = "AUTO"
    ) {
        val state = viewModel.state.value ?: return
        // A result from a picker in another window must not change an already running request.
        if (state.operation != null || state.result != null) return
        clearKeyPasswords()
        binding?.aliasLayout?.error = null
        viewModel.selectKeyStore(path, aliases, format)
    }

    private fun clearKeyPasswords() {
        binding?.apply {
            storePasswordEdit.text?.clear()
            keyPasswordEdit.text?.clear()
            storePasswordLayout.error = null
            keyPasswordLayout.error = null
        }
    }

    private fun sign() {
        val binding = binding ?: return
        val state = viewModel.state.value ?: return
        if (state.operation != null || state.inspection == null
            || !validateForm(state.form)) {
            return
        }
        viewModel.sign(
            binding.storePasswordEdit.copyPassword(), binding.keyPasswordEdit.copyPassword()
        )
        clearKeyPasswords()
        binding.root.hideSoftInput()
        binding.root.clearFocus()
    }

    private fun validateForm(form: ApkSigningForm): Boolean {
        val binding = binding ?: return false
        if (form.coexistence) {
            val packageError = when {
                !ApkSigningViewModel.isValidTargetPackageName(form.targetPackageName) ->
                    R.string.apk_coexistence_invalid_package
                form.targetPackageName == viewModel.state.value?.inspection?.packageName ->
                    R.string.apk_coexistence_same_package
                else -> null
            }
            if (packageError != null) {
                binding.targetPackageLayout.error = getString(packageError)
                binding.targetPackageEdit.requestFocus()
                return false
            }
        }
        if (form.keyStore == null) {
            viewModel.showMessage(getString(R.string.apk_signing_required_key_store))
            return false
        }
        if (form.alias.isBlank()) {
            binding.aliasLayout.error = getString(R.string.apk_signing_required_alias)
            binding.aliasEdit.requestFocus()
            return false
        }
        val minSdk = viewModel.state.value?.inspection?.minSdk ?: return false
        val sdkError = when {
            minSdk !in 1..10000 -> R.string.apk_signing_invalid_min_sdk
            !form.v1 && !form.v2 && !form.v3 -> R.string.apk_signing_required_scheme
            minSdk < 24 && !form.v1 -> R.string.apk_signing_required_v1
            minSdk < 28 && !form.v1 && !form.v2 -> R.string.apk_signing_required_v2
            else -> null
        }
        if (sdkError != null) {
            viewModel.showMessage(getString(sdkError))
            return false
        }
        val outputDirectory = form.outputDirectory
        if (outputDirectory == null) {
            viewModel.showMessage(getString(R.string.apk_signing_no_output_directory))
            return false
        }
        if (!outputDirectory.isLinuxPath) {
            viewModel.showMessage(getString(R.string.apk_operation_local_output_required))
            return false
        }
        val name = form.outputName
        if (name.isBlank() || name.length <= 4 || !name.endsWith(".apk", ignoreCase = true)
            || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            binding.outputNameLayout.error = getString(R.string.apk_signing_invalid_name)
            binding.outputNameEdit.requestFocus()
            return false
        }
        if (name == form.input.fileName.toString()) {
            binding.outputNameLayout.error = getString(R.string.apk_signing_input_must_differ)
            binding.outputNameEdit.requestFocus()
            return false
        }
        return true
    }

    private fun render(state: ApkSigningUiState) {
        val binding = binding ?: return
        rendering = true
        try {
            val form = state.form
            val busy = state.operation != null
            val result = state.result?.takeIf { !busy }
            renderApkInfo(state, result)
            val hasSelectedKey = form.keyStore != null
            binding.selectedKeyCard.isVisible = hasSelectedKey
            binding.keyStoreNameText.setTextIfChanged(
                form.keyStore?.fileName?.toString() ?: getString(R.string.apk_signing_no_key_store)
            )
            binding.keyStorePathText.setTextIfChanged(form.keyStore?.toUserFriendlyString().orEmpty())
            binding.storePasswordLayout.isVisible = hasSelectedKey
            binding.aliasRow.isVisible = hasSelectedKey
            binding.loadAliasesButton.setText(when {
                state.operation == ApkSigningOperation.ALIASES -> R.string.apk_signing_loading_aliases_short
                state.aliases.isNotEmpty() -> R.string.apk_signing_refresh_aliases
                else -> R.string.apk_signing_load_aliases_short
            })
            binding.keyOptionsButton.isVisible = hasSelectedKey
            binding.keyOptionsButton.stateDescription = getString(if (form.keyOptionsExpanded)
                R.string.apk_signing_key_options_expanded else R.string.apk_signing_key_options_collapsed)
            if (binding.keyOptionsButton.isActivated != form.keyOptionsExpanded) {
                binding.keyOptionsButton.isActivated = form.keyOptionsExpanded
                binding.keyOptionsButton.setIconResource(if (form.keyOptionsExpanded)
                    R.drawable.expand_less_icon_control_normal_24dp
                    else R.drawable.expand_more_icon_control_normal_24dp)
            }
            binding.keyOptionsLayout.isVisible = hasSelectedKey && form.keyOptionsExpanded
            binding.differentKeyPasswordCheck.isChecked = form.useDifferentKeyPassword
            binding.keyPasswordLayout.isVisible = form.useDifferentKeyPassword
            val formatIndex = KEY_STORE_FORMATS.indexOf(form.keyStoreFormat).coerceAtLeast(0)
            val format = if (formatIndex == 0) getString(R.string.apk_signing_key_store_auto)
                else KEY_STORE_FORMATS[formatIndex]
            if (binding.keyStoreFormatEdit.text.toString() != format) {
                binding.keyStoreFormatEdit.setText(format, false)
            }
            if (renderedAliases != state.aliases) {
                binding.aliasEdit.setSimpleItems(state.aliases.toTypedArray())
                renderedAliases = state.aliases
            }
            if (binding.aliasEdit.text.toString() != form.alias) {
                binding.keyPasswordEdit.text?.clear()
                binding.aliasEdit.setTextIfChanged(form.alias)
            }
            binding.outputNameEdit.setTextIfChanged(form.outputName)
            binding.coexistenceLayout.isVisible = form.coexistence
            binding.targetPackageEdit.setTextIfChanged(form.targetPackageName)
            binding.v1Check.isChecked = form.v1
            binding.v2Check.isChecked = form.v2
            binding.v3Check.isChecked = form.v3
            binding.formLayout.isVisible = result == null && state.operation != ApkSigningOperation.SIGN
            binding.formLayout.setEnabledRecursively(!busy)
            binding.progress.isVisible = busy
            val progress = state.progress
            val determinate = progress != null && progress.total > 0
            binding.progress.isIndeterminate = !determinate
            val percent = if (determinate) {
                ((progress!!.completed.coerceAtLeast(0).toDouble() / progress.total) * 100)
                    .toInt().coerceIn(0, 100)
            } else null
            if (percent != null) binding.progress.setProgressCompat(percent, binding.progress.isLaidOut)
            binding.statusText.text = when {
                state.canceling -> getString(R.string.apk_signing_canceling)
                progress != null -> progress.stage + if (percent != null) {
                    " · " + getString(R.string.apk_signing_percent_format, percent)
                } else ""
                state.operation == ApkSigningOperation.INSPECT -> getString(R.string.apk_signing_inspecting)
                state.operation == ApkSigningOperation.ALIASES -> getString(R.string.apk_signing_loading_aliases)
                state.operation == ApkSigningOperation.SIGN -> getString(
                    if (form.coexistence) R.string.apk_coexistence_working
                    else R.string.apk_signing_working
                )
                state.inspectionError != null && state.message == getString(
                    R.string.apk_signing_error_format, state.inspectionError
                ) -> getString(R.string.apk_signing_inspection_failed)
                else -> state.message
            }
            binding.statusText.isVisible = !binding.statusText.text.isNullOrEmpty()
            if (renderedOperation != state.operation || renderedMessage != state.message) {
                binding.root.post { binding.root.smoothScrollTo(0, 0) }
                renderedOperation = state.operation
                renderedMessage = state.message
            }
            isCancelable = !busy
            (dialog as? AlertDialog)?.let { dialog ->
                dialog.setTitle(when {
                    result != null && form.coexistence -> R.string.apk_coexistence_result
                    result != null -> R.string.apk_signing_result
                    form.coexistence -> R.string.apk_coexistence_title
                    else -> R.string.apk_signing_title
                })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    setText(when {
                        result != null -> android.R.string.ok
                        form.coexistence -> R.string.apk_coexistence_start
                        else -> R.string.apk_signing_start
                    })
                    isEnabled = !busy && (result != null
                        || (state.inspection != null && hasSelectedKey))
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setText(when {
                        result != null -> R.string.apk_signing_install
                        busy -> R.string.apk_signing_cancel
                        else -> R.string.close
                    })
                    isVisible = true
                    isEnabled = !state.canceling
                }
                // Full paths stay available even while metadata is loading or after cancellation.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                    isVisible = result == null
                    isEnabled = true
                }
                // The hidden details button takes no space; install and OK split the row equally.
            }
        } finally {
            rendering = false
        }
        detailsDialog?.takeIf { it.isShowing }?.let { dialog ->
            // A details window opened during inspection must also receive the finished result.
            val message = formatDetails(state)
            if (!TextUtils.equals(dialog.findViewById<TextView>(android.R.id.message)?.text, message)) {
                dialog.setMessage(message)
            }
        }
    }

    private fun renderApkInfo(state: ApkSigningUiState, result: ApkSignResult?) {
        val binding = binding ?: return
        val path = result?.output ?: state.form.input
        val inspection = result?.inspection ?: state.inspection
        binding.apkNameText.setTextIfChanged(path.fileName.toString())
        binding.apkPathText.setTextIfChanged(path.toUserFriendlyString())
        val unknown = getString(R.string.apk_signing_value_unknown)
        binding.apkPackageText.setTextIfChanged(getString(
            R.string.apk_signing_package_format, inspection?.packageName ?: unknown
        ))
        binding.apkSizeText.setTextIfChanged(getString(
            R.string.apk_signing_size_format,
            inspection?.let { Formatter.formatShortFileSize(requireContext(), it.size) } ?: unknown
        ))
        val verified = inspection?.verification?.verified
        val signatureStatus = when {
            verified == true -> R.string.apk_signing_valid
            verified == false -> R.string.apk_signing_signature_not_verified
            state.canceling -> R.string.apk_signing_signature_not_checked
            state.operation == ApkSigningOperation.INSPECT -> R.string.apk_signing_signature_reading
            state.inspectionError != null -> R.string.apk_signing_signature_unavailable
            else -> R.string.apk_signing_signature_not_checked
        }
        val signatureLabel = getString(if (result == null) R.string.apk_signing_original_signature
            else R.string.apk_signing_output_signature)
        binding.apkSignatureText.setTextIfChanged(
            if (verified != null) getString(signatureStatus)
            else getString(R.string.apk_signing_signature_status_format,
                signatureLabel, getString(signatureStatus))
        )
        // Keep the full verification meaning for accessibility; the compact label is only visual.
        binding.apkSignatureText.contentDescription = getString(
            R.string.apk_signing_signature_status_format, signatureLabel,
            getString(when (verified) {
                true -> R.string.apk_signing_valid
                false -> R.string.apk_signing_invalid
                null -> signatureStatus
            })
        )
        val context = binding.apkSignatureText.context
        if (verified == true) {
            binding.apkSignatureText.backgroundTintList =
                context.getColorStateList(R.color.apk_signature_success_background)
            binding.apkSignatureText.setTextColor(context.getColor(R.color.apk_signature_success_text))
        } else {
            val (backgroundAttr, foregroundAttr) =
                if (verified == false || state.inspectionError != null) {
                    MaterialR.attr.colorErrorContainer to MaterialR.attr.colorOnErrorContainer
                } else {
                    MaterialR.attr.colorSurfaceContainerHighest to MaterialR.attr.colorOnSurfaceVariant
                }
            binding.apkSignatureText.backgroundTintList = context.getColorStateListByAttr(backgroundAttr)
            binding.apkSignatureText.setTextColor(context.getColorByAttr(foregroundAttr))
        }
    }

    private fun showDetails() {
        val state = viewModel.state.value ?: return
        detailsDialog?.dismiss()
        detailsDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_signing_details_title)
            .setMessage(formatDetails(state))
            .setPositiveButton(R.string.close, null)
            .show()
            .apply { findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true) }
    }

    private fun formatDetails(state: ApkSigningUiState): String = buildString {
        append(getString(R.string.apk_signing_original))
        append('\n')
        append(state.form.input.toUserFriendlyString())
        append("\n\n")
        append(state.inspection?.let(::formatInspection) ?: state.inspectionError ?: getString(
            if (state.canceling) R.string.apk_signing_canceling
            else if (state.operation == ApkSigningOperation.INSPECT) R.string.apk_signing_inspecting
            else R.string.apk_signing_not_inspected
        ))
        state.form.keyStore?.let { keyStore ->
            append("\n\n")
            append(getString(R.string.apk_signing_key_store))
            append('\n')
            append(keyStore.toUserFriendlyString())
            append('\n')
            append(getString(
                R.string.apk_signing_key_details_format,
                if (state.form.keyStoreFormat == "AUTO") getString(R.string.apk_signing_key_store_auto)
                else state.form.keyStoreFormat,
                state.form.alias.ifEmpty { getString(R.string.apk_signing_value_unknown) }
            ))
        }
        state.result?.let { result ->
            append("\n\n")
            append(getString(if (state.form.coexistence) R.string.apk_coexistence_result
                else R.string.apk_signing_result))
            append('\n')
            append(getString(R.string.apk_signing_result_path_format,
                result.output.toUserFriendlyString()))
            append("\n\n")
            append(formatInspection(result.inspection))
            result.coexistenceReport?.takeIf { it.isNotBlank() }?.let { report ->
                append("\n\n")
                append(getString(R.string.apk_coexistence_report_format, report))
            }
        }
    }

    private fun formatInspection(inspection: ApkInspection): String =
        getString(
            R.string.apk_signing_application_format, inspection.label, inspection.packageName,
            "${inspection.versionName} (${inspection.versionCode})", inspection.minSdk, inspection.targetSdk
        ) + "\n" + getString(
            R.string.apk_signing_size_format, inspection.size.asFileSize().formatInBytes(requireContext())
        ) + "\n" + getString(
            R.string.apk_signing_checked_min_sdk_format, inspection.verificationMinSdk.toString()
        ) + "\n\n" + formatVerification(inspection.verification)

    private fun formatVerification(verification: ApkVerificationService.Result): String = buildString {
        append(getString(
            R.string.apk_signing_verification_format,
            getString(if (verification.verified) R.string.apk_signing_valid else R.string.apk_signing_invalid),
            verification.schemes.joinToString().ifEmpty { "—" }
        ))
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (certificate in verification.certificates) {
            append("\n\n")
            append(getString(
                R.string.apk_signing_certificate_format, certificate.subject, certificate.issuer,
                certificate.sha256Fingerprint
            ))
            append('\n')
            append(getString(
                R.string.apk_signing_certificate_details_format, certificate.serialNumber,
                certificate.publicKeyAlgorithm, dateFormat.format(Date(certificate.notBefore)),
                dateFormat.format(Date(certificate.notAfter))
            ))
        }
        if (verification.errors.isNotEmpty()) {
            append("\n\n")
            append(getString(R.string.apk_signing_error_format, verification.errors.joinToString("\n")))
        }
    }

    private fun installApk(path: Path) {
        startActivitySafe(path.fileProviderUri.createInstallPackageIntent())
    }

    private fun EditText.copyPassword(): CharArray {
        val text = text ?: return charArrayOf()
        return CharArray(text.length).also { TextUtils.getChars(text, 0, text.length, it, 0) }
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun View.setEnabledRecursively(enabled: Boolean) {
        isEnabled = enabled
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).setEnabledRecursively(enabled)
        }
    }

    companion object {
        private val KEY_STORE_FORMATS = listOf("AUTO", "PKCS12", "JKS")
        private val TAG = ApkSigningDialogFragment::class.java.name

        fun show(input: Path, fragment: Fragment, coexistence: Boolean = false) {
            if (!fragment.isAdded) return
            val manager = fragment.childFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            ApkSigningDialogFragment().putArgs(Args(input, coexistence)).showNow(manager, TAG)
        }
    }

    @Parcelize
    private class Args(
        val input: @WriteWith<ParcelableParceler> Path,
        val coexistence: Boolean
    ) : ParcelableArgs

    @Parcelize
    private class SavedForm(
        val input: @WriteWith<ParcelableParceler> Path,
        val outputName: String,
        val keyStore: @WriteWith<ParcelableParceler> Path?,
        val keyStoreFormat: String,
        val alias: String,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val coexistence: Boolean,
        val targetPackageName: String,
        val keyOptionsExpanded: Boolean,
        val useDifferentKeyPassword: Boolean,
        val wasRunning: Boolean,
        val hadInspection: Boolean
    ) : ParcelableState {
        constructor(form: ApkSigningForm, wasRunning: Boolean, hadInspection: Boolean) : this(
            form.input, form.outputName, form.keyStore, form.keyStoreFormat,
            form.alias, form.v1, form.v2, form.v3, form.coexistence,
            form.targetPackageName,
            form.keyOptionsExpanded, form.useDifferentKeyPassword, wasRunning, hadInspection
        )

        fun toForm() = ApkSigningForm(
            input = input, coexistence = coexistence, targetPackageName = targetPackageName,
            outputName = outputName, keyStore = keyStore,
            keyStoreFormat = keyStoreFormat, alias = alias,
            v1 = v1, v2 = v2, v3 = v3,
            keyOptionsExpanded = keyOptionsExpanded, useDifferentKeyPassword = useDifferentKeyPassword
        )
    }
}
