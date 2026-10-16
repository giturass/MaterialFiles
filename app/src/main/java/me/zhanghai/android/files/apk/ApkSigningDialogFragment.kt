/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import me.zhanghai.android.files.databinding.ApkSigningKeyItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.setButtonBarEqualWidth
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getColorStateListByAttr
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.hideSoftInput
import me.zhanghai.android.files.util.launchSafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.util.withChooser
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
    private var keyPickerDialog: AlertDialog? = null
    private var detailsDialog: AlertDialog? = null

    private val chooseSystemKeyStoreLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data
                val path = runCatching { intent?.extraPath }.getOrNull()
                if (path == null) {
                    viewModel.showMessage(getString(R.string.apk_signing_invalid_picker_result))
                } else {
                    val uri = intent?.data
                    if (uri != null && intent != null
                        && (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                        // A system document selection grants read access to this selected file.
                        try {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                            // Providers without persistable grants remain usable for this session.
                        }
                    }
                    onKeyStoreSelected(path)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedForm = savedInstanceState?.getState<SavedForm>()
        viewModel.initialize(
            requireContext(), savedForm?.toForm()
                ?: ApkSigningForm(args.input, coexistence = args.coexistence),
            savedForm?.wasRunning == true, savedForm?.hadInspection == true
        )
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
        binding.newKeyConfirmPasswordEdit.isSaveFromParentEnabled = false
        binding.chooseKeyStoreButton.setOnClickListener { chooseKeyStore() }
        binding.createKeyButton.setOnClickListener {
            setCreatingKey(viewModel.state.value?.form?.creatingKey != true)
        }
        binding.newKeyAliasEdit.doAfterTextChanged {
            binding.newKeyAliasLayout.error = null
            if (!rendering) {
                viewModel.updateForm { form -> form.copy(newKeyAlias = it?.toString().orEmpty()) }
            }
        }
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
            binding.newKeyConfirmPasswordLayout.error = null
        }
        binding.keyPasswordEdit.doAfterTextChanged { binding.keyPasswordLayout.error = null }
        binding.newKeyConfirmPasswordEdit.doAfterTextChanged {
            binding.newKeyConfirmPasswordLayout.error = null
        }
        binding.loadAliasesButton.setOnClickListener {
            viewModel.readAliases(binding.storePasswordEdit.copyPassword())
        }
        binding.generateKeyButton.setOnClickListener { generateKey() }
        binding.openButton.setOnClickListener {
            withResult {
                startActivitySafe(FileListActivity.createViewIntent(it.output.createArchiveRootPath()))
            }
        }
        binding.installButton.setOnClickListener { withResult { installApk(it.output) } }
        binding.shareButton.setOnClickListener {
            withResult {
                startActivitySafe(
                    it.output.fileProviderUri.createSendStreamIntent(MimeType.APK).withChooser()
                )
            }
        }
        binding.directoryButton.setOnClickListener {
            withResult { result ->
                result.output.parent?.let { startActivitySafe(FileListActivity.createViewIntent(it)) }
            }
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
            if (viewModel.state.value?.operation != null) viewModel.cancel() else dismiss()
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
        keyPickerDialog?.dismiss()
        keyPickerDialog = null
        detailsDialog?.dismiss()
        detailsDialog = null
        clearKeyPasswords()
        binding = null
        renderedAliases = null
        super.onDestroyView()
        // Dismissing the fragment clears its ViewModel and cancels IO. Rotation retains the model.
    }

    private fun chooseKeyStore() {
        keyPickerDialog?.dismiss()
        keyPickerDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_signing_choose_source)
            .setItems(arrayOf(
                getString(R.string.apk_signing_system_picker),
                getString(R.string.apk_signing_private_keys)
            )) { _, position ->
                when (position) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(MimeType.ANY.value)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        chooseSystemKeyStoreLauncher.launchSafe(intent, this)
                    }
                    1 -> viewModel.listPrivateKeys()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onKeyStoreSelected(path: Path) {
        val state = viewModel.state.value ?: return
        // A result from a picker in another window must not change an already running request.
        if (state.operation != null || state.result != null) return
        clearKeyPasswords()
        binding?.aliasLayout?.error = null
        viewModel.selectKeyStore(path)
    }

    private fun setCreatingKey(creating: Boolean) {
        val state = viewModel.state.value ?: return
        if (state.operation != null || state.result != null) return
        clearKeyPasswords()
        binding?.newKeyAliasLayout?.error = null
        viewModel.setCreatingKey(creating)
    }

    private fun clearKeyPasswords() {
        binding?.apply {
            storePasswordEdit.text?.clear()
            keyPasswordEdit.text?.clear()
            newKeyConfirmPasswordEdit.text?.clear()
            storePasswordLayout.error = null
            keyPasswordLayout.error = null
            newKeyConfirmPasswordLayout.error = null
        }
    }

    private fun generateKey() {
        val binding = binding ?: return
        val state = viewModel.state.value ?: return
        if (state.operation != null || state.result != null || !state.form.creatingKey) return
        val alias = state.form.newKeyAlias
        if (alias.isBlank()) {
            binding.newKeyAliasLayout.error = getString(R.string.apk_signing_required_new_key_alias)
            binding.newKeyAliasEdit.requestFocus()
            return
        }
        if ((binding.storePasswordEdit.text?.length ?: 0) < 8) {
            binding.storePasswordLayout.error = getString(R.string.apk_signing_required_password)
            binding.storePasswordEdit.requestFocus()
            return
        }
        if (!TextUtils.equals(binding.storePasswordEdit.text, binding.newKeyConfirmPasswordEdit.text)) {
            binding.newKeyConfirmPasswordLayout.error = getString(R.string.apk_signing_password_mismatch)
            binding.newKeyConfirmPasswordEdit.requestFocus()
            return
        }
        // Generated PKCS12 keys use the keystore password for the private key as well.
        binding.keyPasswordEdit.text?.clear()
        binding.newKeyConfirmPasswordEdit.text?.clear()
        viewModel.generateKey(alias, binding.storePasswordEdit.copyPassword())
        binding.root.hideSoftInput()
        binding.root.clearFocus()
    }

    private fun sign() {
        val binding = binding ?: return
        val state = viewModel.state.value ?: return
        if (state.operation != null || state.inspection == null || state.form.creatingKey
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
            val hasSelectedKey = form.keyStore != null && !form.creatingKey
            binding.noKeyStoreText.isVisible = form.keyStore == null && !form.creatingKey
            binding.selectedKeyCard.isVisible = hasSelectedKey
            binding.chooseKeyStoreButton.setText(if (hasSelectedKey)
                R.string.apk_signing_replace_key_store else R.string.apk_signing_choose_key_store_short)
            binding.createKeyButton.setText(if (form.creatingKey)
                R.string.apk_signing_cancel_create_key else R.string.apk_signing_create_key)
            binding.keyStoreNameText.setTextIfChanged(
                form.keyStore?.fileName?.toString() ?: getString(R.string.apk_signing_no_key_store)
            )
            binding.keyStorePathText.setTextIfChanged(form.keyStore?.toUserFriendlyString().orEmpty())
            binding.newKeyAliasLayout.isVisible = form.creatingKey
            binding.newKeyAliasEdit.setTextIfChanged(form.newKeyAlias)
            binding.storePasswordLayout.isVisible = hasSelectedKey || form.creatingKey
            binding.storePasswordLayout.hint = getString(if (form.creatingKey)
                R.string.apk_signing_new_store_password else R.string.apk_signing_store_password)
            binding.storePasswordLayout.helperText = if (form.creatingKey)
                getString(R.string.apk_signing_new_password_hint) else null
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
            binding.newKeyConfirmPasswordLayout.isVisible = form.creatingKey
            binding.createKeyLayout.isVisible = form.creatingKey
            binding.generateKeyButton.setText(if (state.operation == ApkSigningOperation.GENERATE_KEY)
                R.string.apk_signing_generating_key_short else R.string.apk_signing_generate_key)
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
                state.operation == ApkSigningOperation.PRIVATE_KEYS -> getString(R.string.apk_signing_loading_private_keys)
                state.operation == ApkSigningOperation.GENERATE_KEY -> getString(R.string.apk_signing_generating_key)
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
            binding.resultLayout.isVisible = result != null
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
                        result != null -> R.string.close
                        form.coexistence -> R.string.apk_coexistence_start
                        else -> R.string.apk_signing_start
                    })
                    isEnabled = !busy && (result != null
                        || (state.inspection != null && hasSelectedKey))
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setText(if (busy) R.string.apk_signing_cancel else R.string.close)
                    isVisible = result == null
                    isEnabled = !state.canceling
                }
                // Full paths stay available even while metadata is loading or after cancellation.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
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
        if (state.operation == null && state.privateKeys != null) {
            viewModel.consumePrivateKeys()
            showPrivateKeys(state.privateKeys)
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
            if (verified == true) getString(signatureStatus)
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

    private fun showPrivateKeys(keys: List<ApkPrivateKey>) {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_signing_private_keys)
            .setNegativeButton(R.string.close, null)
        if (keys.isEmpty()) {
            builder.setMessage(R.string.apk_signing_no_private_keys)
                .setPositiveButton(R.string.apk_signing_create_key) { _, _ -> setCreatingKey(true) }
        } else {
            val selected = viewModel.state.value?.form?.keyStore
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val adapter = object : ArrayAdapter<ApkPrivateKey>(builder.context, 0, keys) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView?.let(ApkSigningKeyItemBinding::bind)
                        ?: ApkSigningKeyItemBinding.inflate(context.layoutInflater, parent, false)
                    val key = keys[position]
                    row.keyNameText.text = key.path.fileName.toString()
                    row.keyNameText.setTextColor(context.getColorByAttr(
                        if (key.path == selected) androidx.appcompat.R.attr.colorPrimary
                        else MaterialR.attr.colorOnSurface
                    ))
                    val modified = if (key.lastModified > 0) getString(
                        R.string.apk_signing_key_modified_format, dateFormat.format(Date(key.lastModified))
                    ) else getString(R.string.apk_signing_key_modified_unknown)
                    row.keySummaryText.text = if (key.path == selected)
                        getString(R.string.apk_signing_current_key_format, modified) else modified
                    return row.root
                }
            }
            builder.setAdapter(adapter) { _, position -> onKeyStoreSelected(keys[position].path) }
        }
        keyPickerDialog?.dismiss()
        keyPickerDialog = builder.show()
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

    private fun withResult(action: (ApkSignResult) -> Unit) {
        viewModel.state.value?.result?.let(action)
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
        val creatingKey: Boolean,
        val newKeyAlias: String,
        val keyOptionsExpanded: Boolean,
        val useDifferentKeyPassword: Boolean,
        val wasRunning: Boolean,
        val hadInspection: Boolean
    ) : ParcelableState {
        constructor(form: ApkSigningForm, wasRunning: Boolean, hadInspection: Boolean) : this(
            form.input, form.outputName, form.keyStore, form.keyStoreFormat,
            form.alias, form.v1, form.v2, form.v3, form.coexistence,
            form.targetPackageName, form.creatingKey, form.newKeyAlias,
            form.keyOptionsExpanded, form.useDifferentKeyPassword, wasRunning, hadInspection
        )

        fun toForm() = ApkSigningForm(
            input = input, coexistence = coexistence, targetPackageName = targetPackageName,
            outputName = outputName, keyStore = keyStore,
            keyStoreFormat = keyStoreFormat, alias = alias,
            v1 = v1, v2 = v2, v3 = v3,
            creatingKey = creatingKey, newKeyAlias = newKeyAlias,
            keyOptionsExpanded = keyOptionsExpanded, useDifferentKeyPassword = useDifferentKeyPassword
        )
    }
}
