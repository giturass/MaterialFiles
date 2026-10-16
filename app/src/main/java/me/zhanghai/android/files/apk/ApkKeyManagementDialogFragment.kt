/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.appcompat.widget.PopupMenu
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
import me.zhanghai.android.files.databinding.ApkKeyImportItemBinding
import me.zhanghai.android.files.databinding.ApkKeyManagementDialogBinding
import me.zhanghai.android.files.databinding.ApkSigningKeyItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.hideSoftInput
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.viewModels
import java.text.DateFormat
import java.util.Date
import com.google.android.material.R as MaterialR

class ApkKeyManagementDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private val viewModel by viewModels { { ApkKeyManagementViewModel() } }
    private var binding: ApkKeyManagementDialogBinding? = null
    private var renderedKeys: List<ApkPrivateKey>? = null
    private var renderedSelected: Path? = null
    private var renderedImport: Path? = null
    private var renderedPreview: List<ApkKeyImportEntry>? = null
    private var renderedCreating = false
    private var renderedOperation: ApkKeyOperation? = null
    private var renderedMessage: String? = null
    private var rendering = false
    private val keyRows = linkedMapOf<Path, ApkSigningKeyItemBinding>()
    private val importRows = linkedMapOf<String, ApkKeyImportItemBinding>()
    private var actionMenu: PopupMenu? = null
    private var confirmationDialog: AlertDialog? = null
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.exitSelection()
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val intent = result.data
            val path = runCatching { intent?.extraPath }.getOrNull()
            if (path == null) {
                viewModel.showMessage(getString(R.string.apk_signing_invalid_picker_result))
            } else {
                val uri = intent?.data
                if (uri != null
                    && intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        // This provider's grant remains usable for the current session.
                    }
                }
                clearImportPasswords(resetOverrides = true)
                viewModel.prepareImport(path)
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val intent = result.data
            val uri = intent?.data.takeIf { result.resultCode == Activity.RESULT_OK }
            if (uri != null && intent != null
                && intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                val flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (flags != 0) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (_: SecurityException) {
                        // Providers without persistent grants can still export in this task.
                    }
                }
            }
            viewModel.exportTo(uri)
            if (result.resultCode == Activity.RESULT_OK && uri == null) {
                viewModel.showMessage(getString(R.string.apk_signing_invalid_picker_result))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getState<SavedState>()
        viewModel.initialize(
            requireContext(), saved?.toState() ?: ApkKeyManagementState(selectedPath = args.selectedPath),
            saved?.wasRunning == true
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), theme)
        val binding = ApkKeyManagementDialogBinding.inflate(builder.context.layoutInflater)
        this.binding = binding
        binding.root.isSaveFromParentEnabled = false
        binding.passwordEdit.isSaveFromParentEnabled = false
        binding.newKeyPasswordEdit.isSaveFromParentEnabled = false
        binding.newKeyConfirmPasswordEdit.isSaveFromParentEnabled = false
        binding.importButton.setOnClickListener { chooseImport() }
        binding.createKeyButton.setOnClickListener { setCreatingKey(true) }
        binding.generateKeyButton.setOnClickListener { generateKey() }
        binding.cancelCreateButton.setOnClickListener { setCreatingKey(false) }
        binding.newKeyAliasEdit.doAfterTextChanged {
            binding.newKeyAliasLayout.error = null
            if (!rendering) viewModel.setNewKeyAlias(it?.toString().orEmpty())
        }
        binding.newKeyPasswordEdit.doAfterTextChanged {
            binding.newKeyPasswordLayout.error = null
            binding.newKeyConfirmPasswordLayout.error = null
        }
        binding.newKeyConfirmPasswordEdit.doAfterTextChanged {
            binding.newKeyConfirmPasswordLayout.error = null
        }
        binding.newKeyConfirmPasswordEdit.setOnEditorConfirmActionListener { generateKey() }
        binding.confirmImportButton.setOnClickListener { importKeys() }
        binding.passwordEdit.setOnEditorConfirmActionListener { importKeys() }
        binding.cancelImportButton.setOnClickListener {
            clearImportPasswords(resetOverrides = true)
            binding.root.hideSoftInput()
            viewModel.prepareImport(null)
        }
        binding.refreshButton.setOnClickListener { viewModel.refresh() }
        binding.selectAllButton.setOnClickListener {
            val state = viewModel.state.value
            if (state.keys.all { it.path in state.checkedPaths }) viewModel.clearSelection()
            else viewModel.selectAll()
        }
        return builder.setTitle(R.string.apk_key_management)
            .setView(binding.root)
            .setPositiveButton(R.string.apk_key_export_zip, null)
            .setNegativeButton(R.string.close, null)
            .create().apply {
                onBackPressedDispatcher.addCallback(this@ApkKeyManagementDialogFragment, selectionBackCallback)
                setCanceledOnTouchOutside(false)
                window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { exportSelectedKeys() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val state = viewModel.state.value
            when {
                state.operation != null -> viewModel.cancel()
                state.selectionMode -> viewModel.exitSelection()
                else -> dismiss()
            }
        }
        render(viewModel.state.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putState(SavedState(viewModel.state.value))
    }

    override fun onDestroyView() {
        actionMenu?.dismiss()
        actionMenu = null
        confirmationDialog?.dismiss()
        confirmationDialog = null
        selectionBackCallback.remove()
        clearImportPasswords(resetOverrides = true)
        clearCreatePasswords()
        keyRows.clear()
        importRows.clear()
        binding = null
        renderedKeys = null
        renderedSelected = null
        renderedImport = null
        renderedPreview = null
        renderedCreating = false
        renderedOperation = null
        renderedMessage = null
        super.onDestroyView()
    }

    private fun chooseImport() {
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null || state.selectionMode) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(MimeType.ANY.value)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            importLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.showMessage(getString(R.string.activity_not_found))
        }
    }

    private fun importKeys() {
        val binding = binding ?: return
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null || state.importPreview.isNullOrEmpty()) return
        val password = binding.passwordEdit.copyPassword()
        val overrides = importRows.filterValues { it.overridePasswordCheck.isChecked }
            .mapValues { it.value.passwordEdit.copyPassword() }
        clearImportPasswords()
        binding.root.hideSoftInput()
        binding.root.clearFocus()
        viewModel.importKeys(password, overrides)
    }

    private fun clearImportPasswords(resetOverrides: Boolean = false) {
        binding?.passwordEdit?.text?.clear()
        importRows.values.forEach { row ->
            row.passwordEdit.text?.clear()
            if (resetOverrides) row.overridePasswordCheck.isChecked = false
        }
    }

    private fun setCreatingKey(creating: Boolean) {
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null) return
        clearCreatePasswords()
        clearImportPasswords(resetOverrides = true)
        binding?.root?.hideSoftInput()
        binding?.root?.clearFocus()
        viewModel.setCreatingKey(creating)
    }

    private fun generateKey() {
        val binding = binding ?: return
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null || !state.creatingKey) return
        val alias = binding.newKeyAliasEdit.text?.toString().orEmpty()
        if (alias.isBlank()) {
            binding.newKeyAliasLayout.error = getString(R.string.apk_signing_required_new_key_alias)
            binding.newKeyAliasEdit.requestFocus()
            return
        }
        if ((binding.newKeyPasswordEdit.text?.length ?: 0) < 8) {
            binding.newKeyPasswordLayout.error = getString(R.string.apk_signing_required_password)
            binding.newKeyPasswordEdit.requestFocus()
            return
        }
        if (!TextUtils.equals(binding.newKeyPasswordEdit.text, binding.newKeyConfirmPasswordEdit.text)) {
            binding.newKeyConfirmPasswordLayout.error = getString(R.string.apk_signing_password_mismatch)
            binding.newKeyConfirmPasswordEdit.requestFocus()
            return
        }
        val password = binding.newKeyPasswordEdit.copyPassword()
        clearCreatePasswords()
        binding.root.hideSoftInput()
        binding.root.clearFocus()
        viewModel.generateKey(alias, password)
    }

    private fun clearCreatePasswords() {
        binding?.apply {
            newKeyPasswordEdit.text?.clear()
            newKeyConfirmPasswordEdit.text?.clear()
            newKeyAliasLayout.error = null
            newKeyPasswordLayout.error = null
            newKeyConfirmPasswordLayout.error = null
        }
    }

    private fun exportKey(key: ApkPrivateKey) {
        startExport(listOf(key.path), false, key.displayName)
    }

    private fun exportSelectedKeys() {
        val state = viewModel.state.value
        if (!state.selectionMode) return
        val paths = state.keys.filter { it.path in state.checkedPaths }.map { it.path }
        if (paths.isEmpty()) return
        startExport(paths, true, getString(R.string.apk_key_export_zip_name))
    }

    private fun startExport(paths: List<Path>, asZip: Boolean, name: String) {
        if (!viewModel.prepareExport(paths, asZip)) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (asZip) "application/zip" else "application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            exportLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.exportTo(null)
            viewModel.showMessage(getString(R.string.activity_not_found))
        }
    }

    private fun render(state: ApkKeyManagementState) {
        val binding = binding ?: return
        val busy = state.operation != null
        val enabled = !busy && state.pendingExport == null
        val formActive = state.creatingKey || state.importSource != null
        rendering = true
        try {
            binding.managementHintText.isVisible = !formActive && !state.selectionMode
            binding.sourceButtons.isVisible = !formActive && !state.selectionMode
            binding.sourceButtons.setEnabledRecursively(enabled)
            binding.keyListSection.isVisible = !formActive
            binding.createLayout.isVisible = state.creatingKey
            binding.createLayout.setEnabledRecursively(enabled)
            binding.importLayout.isVisible = state.importSource != null
            if (renderedImport != state.importSource || renderedCreating != state.creatingKey) {
                clearImportPasswords(resetOverrides = true)
                clearCreatePasswords()
                renderedImport = state.importSource
                renderedCreating = state.creatingKey
                binding.root.post { binding.root.smoothScrollTo(0, 0) }
            }
            if (binding.newKeyAliasEdit.text?.toString() != state.newKeyAlias) {
                binding.newKeyAliasEdit.setText(state.newKeyAlias)
            }
            binding.generateKeyButton.setText(if (state.operation == ApkKeyOperation.GENERATE)
                R.string.apk_signing_generating_key_short else R.string.apk_signing_generate_key)
            binding.importNameText.text = state.importSource?.fileName?.toString()
            renderImportPreview(state.importPreview)
            binding.importLayout.setEnabledRecursively(enabled)
            binding.confirmImportButton.isEnabled = enabled && !state.importPreview.isNullOrEmpty()
            binding.progress.isVisible = busy
            binding.statusText.text = when {
                state.canceling -> getString(R.string.apk_signing_canceling)
                state.operation == ApkKeyOperation.LOAD -> getString(R.string.apk_signing_loading_private_keys)
                state.operation == ApkKeyOperation.PREVIEW -> getString(R.string.apk_key_import_previewing)
                state.operation == ApkKeyOperation.IMPORT -> getString(R.string.apk_key_importing)
                state.operation == ApkKeyOperation.GENERATE -> getString(R.string.apk_signing_generating_key)
                state.operation == ApkKeyOperation.EXPORT -> getString(R.string.apk_key_exporting)
                state.operation == ApkKeyOperation.DELETE -> getString(R.string.apk_key_deleting)
                else -> state.message
            }
            binding.statusText.isVisible = !binding.statusText.text.isNullOrEmpty()
            if (busy && renderedOperation != state.operation
                || !state.message.isNullOrEmpty() && renderedMessage != state.message) {
                // The import action may be below many password fields; keep failures in view.
                binding.root.post { binding.root.smoothScrollTo(0, 0) }
            }
            renderedOperation = state.operation
            renderedMessage = state.message
            binding.countText.text = if (state.selectionMode) getString(
                R.string.apk_key_selected_count, state.checkedPaths.size, state.keys.size
            ) else getString(R.string.apk_key_count, state.keys.size)
            binding.refreshButton.isVisible = !state.selectionMode
            binding.refreshButton.isEnabled = enabled
            binding.selectAllButton.isVisible = state.selectionMode
            binding.selectAllButton.isEnabled = enabled && state.keys.isNotEmpty()
            binding.selectAllButton.setText(if (state.keys.isNotEmpty()
                && state.keys.all { it.path in state.checkedPaths }) R.string.apk_key_deselect_all
                else R.string.apk_key_select_all)
            binding.selectionHintText.isVisible = state.selectionMode
            binding.emptyText.isVisible = state.loaded && state.keys.isEmpty()
            renderKeys(state)
            binding.keysLayout.setEnabledRecursively(enabled && !formActive)
            selectionBackCallback.isEnabled = enabled && state.selectionMode
            isCancelable = enabled
            (dialog as? AlertDialog)?.let { dialog ->
                dialog.setTitle(when {
                    state.selectionMode -> R.string.apk_key_select_multiple
                    state.creatingKey -> R.string.apk_signing_create_key
                    state.importSource != null -> R.string.apk_key_import
                    else -> R.string.apk_key_management
                })
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    isVisible = state.selectionMode
                    isEnabled = enabled && state.checkedPaths.isNotEmpty()
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setText(when {
                        busy -> R.string.apk_signing_cancel
                        state.selectionMode -> R.string.apk_key_cancel_selection
                        else -> R.string.close
                    })
                    isEnabled = !state.canceling && state.operation != ApkKeyOperation.DELETE
                        && state.pendingExport == null
                }
            }
        } finally {
            rendering = false
        }
        if (!busy && (state.selection != null || state.deletedPath != null)) {
            state.selection?.let { selected ->
                sendResult(Result(Action.SELECT, selected.path, selected.aliases, selected.format,
                    imported = !selected.created, created = selected.created))
            }
            state.deletedPath?.let { sendResult(Result(Action.DELETE, it)) }
            viewModel.consumeChanges()
        }
    }

    private fun renderImportPreview(preview: List<ApkKeyImportEntry>?) {
        val binding = binding ?: return
        val multiple = (preview?.size ?: 0) > 1
        binding.importHintText.text = if (multiple) getString(
            R.string.apk_key_import_zip_hint, preview!!.size
        ) else getString(R.string.apk_key_import_hint)
        binding.passwordLayout.hint = getString(if (multiple) R.string.apk_key_import_common_password
            else R.string.apk_signing_store_password)
        binding.passwordLayout.helperText = if (multiple)
            getString(R.string.apk_key_import_common_password_hint) else null
        binding.passwordLayout.isVisible = !preview.isNullOrEmpty()
        binding.importEntriesLayout.isVisible = !preview.isNullOrEmpty()
        binding.confirmImportButton.text = if (multiple) getString(
            R.string.apk_key_import_zip_confirm, preview!!.size
        ) else getString(R.string.apk_key_confirm_import)
        if (renderedPreview == preview) return
        clearImportPasswords(resetOverrides = true)
        importRows.clear()
        binding.importEntriesLayout.removeAllViews()
        preview.orEmpty().forEach { entry ->
            val row = ApkKeyImportItemBinding.inflate(
                binding.root.context.layoutInflater, binding.importEntriesLayout, false
            )
            row.root.isSaveFromParentEnabled = false
            row.passwordEdit.isSaveFromParentEnabled = false
            row.nameText.text = entry.name
            row.summaryText.text = getString(R.string.apk_key_import_entry_summary, entry.format,
                entry.size.asFileSize().formatInBytes(requireContext()))
            row.overridePasswordCheck.isVisible = multiple
            row.overridePasswordCheck.setOnCheckedChangeListener { _, checked ->
                row.passwordLayout.isVisible = checked
                if (!checked) row.passwordEdit.text?.clear()
            }
            row.passwordEdit.setOnEditorConfirmActionListener { importKeys() }
            importRows[entry.id] = row
            binding.importEntriesLayout.addView(row.root)
        }
        renderedPreview = preview
    }

    private fun renderKeys(state: ApkKeyManagementState) {
        val binding = binding ?: return
        if (renderedKeys != state.keys || renderedSelected != state.selectedPath) {
            actionMenu?.dismiss()
            keyRows.clear()
            binding.keysLayout.removeAllViews()
            state.keys.forEach { key ->
                val row = ApkSigningKeyItemBinding.inflate(
                    binding.root.context.layoutInflater, binding.keysLayout, false
                )
                val selected = key.path == state.selectedPath
                row.keyNameText.text = key.displayName
                row.keyNameText.setTextColor(row.root.context.getColorByAttr(
                    if (selected) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurface
                ))
                val summary = getString(R.string.apk_key_summary, key.format,
                    key.size.asFileSize().formatInBytes(requireContext()), modifiedTime(key))
                row.keySummaryText.text = if (selected)
                    getString(R.string.apk_signing_current_key_format, summary) else summary
                row.selectKeyLayout.setOnClickListener { onKeyClick(key) }
                row.selectKeyLayout.setOnLongClickListener { startSelection(key) }
                row.selectionCheck.contentDescription = getString(R.string.apk_key_select_entry, key.displayName)
                row.selectionCheck.setOnClickListener { viewModel.toggleSelection(key.path) }
                row.actionsButton.contentDescription = getString(R.string.apk_key_actions, key.displayName)
                row.actionsButton.setOnClickListener { showActions(key, it) }
                keyRows[key.path] = row
                binding.keysLayout.addView(row.root)
            }
            renderedKeys = state.keys
            renderedSelected = state.selectedPath
        }
        keyRows.forEach { (path, row) ->
            val checked = path in state.checkedPaths
            row.selectionCheck.isVisible = state.selectionMode
            row.selectionCheck.isChecked = checked
            row.selectKeyLayout.isSelected = if (state.selectionMode) checked else path == state.selectedPath
            row.actionsButton.isVisible = !state.selectionMode
        }
        if (state.selectionMode) actionMenu?.dismiss()
    }

    private fun onKeyClick(key: ApkPrivateKey) {
        if (viewModel.state.value.selectionMode) viewModel.toggleSelection(key.path) else selectKey(key)
    }

    private fun startSelection(key: ApkPrivateKey): Boolean {
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null
            || state.creatingKey || state.importSource != null) return false
        if (state.selectionMode) {
            viewModel.toggleSelection(key.path)
        } else {
            viewModel.startSelection(key.path)
        }
        return true
    }

    private fun selectKey(key: ApkPrivateKey) {
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null || state.selectionMode) return
        finishWith(Result(Action.SELECT, key.path, format = key.format))
    }

    private fun showActions(key: ApkPrivateKey, anchor: View) {
        val state = viewModel.state.value
        if (state.operation != null || state.pendingExport != null || state.selectionMode) return
        actionMenu?.dismiss()
        actionMenu = PopupMenu(requireContext(), anchor, Gravity.END).apply {
            val actions = listOf(R.string.apk_key_export, R.string.apk_key_delete)
            actions.forEachIndexed { index, title -> menu.add(0, index, index, title) }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> exportKey(key)
                    1 -> confirmDelete(key)
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(key: ApkPrivateKey) {
        confirmationDialog?.dismiss()
        confirmationDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_key_delete)
            .setMessage(getString(if (key.path == viewModel.state.value.selectedPath)
                R.string.apk_key_delete_selected_confirmation else R.string.apk_key_delete_confirmation,
                key.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apk_key_delete) { _, _ -> viewModel.deleteKey(key.path) }
            .show()
    }

    private fun modifiedTime(key: ApkPrivateKey): String = if (key.lastModified > 0) {
        getString(R.string.apk_signing_key_modified_format,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(key.lastModified)))
    } else getString(R.string.apk_signing_key_modified_unknown)

    private fun sendResult(result: Result) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply { putState(result) })
    }

    private fun finishWith(result: Result) {
        sendResult(result)
        dismiss()
    }

    private fun EditText.copyPassword(): CharArray {
        val text = text ?: return charArrayOf()
        return CharArray(text.length).also { TextUtils.getChars(text, 0, text.length, it, 0) }
    }

    private fun View.setEnabledRecursively(enabled: Boolean) {
        isEnabled = enabled
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).setEnabledRecursively(enabled)
        }
    }

    companion object {
        private val TAG = ApkKeyManagementDialogFragment::class.java.name
        private val RESULT_KEY = "$TAG.result"

        fun show(selectedPath: Path?, fragment: Fragment) {
            if (!fragment.isAdded) return
            val manager = fragment.childFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            ApkKeyManagementDialogFragment().putArgs(Args(selectedPath)).show(manager, TAG)
        }

        fun setResultListener(fragment: Fragment, listener: (Result) -> Unit) {
            fragment.childFragmentManager.setFragmentResultListener(RESULT_KEY, fragment) { _, bundle ->
                listener(bundle.getState())
            }
        }
    }

    enum class Action { SELECT, DELETE }

    @Parcelize
    class Result(
        val action: Action,
        val path: @WriteWith<ParcelableParceler> Path? = null,
        val aliases: List<String> = emptyList(),
        val format: String = "AUTO",
        val imported: Boolean = false,
        val created: Boolean = false
    ) : ParcelableState

    @Parcelize
    private class Args(val selectedPath: @WriteWith<ParcelableParceler> Path?) : ParcelableArgs

    @Parcelize
    private class SavedState(
        val selectedPath: @WriteWith<ParcelableParceler> Path?,
        val importSource: @WriteWith<ParcelableParceler> Path?,
        val pendingExportPaths: @WriteWith<ParcelableListParceler> List<Path>,
        val pendingExportAsZip: Boolean,
        val exportDestination: Uri?,
        val pendingDelete: @WriteWith<ParcelableParceler> Path?,
        val selectionMode: Boolean,
        val checkedPaths: @WriteWith<ParcelableListParceler> List<Path>,
        val creatingKey: Boolean,
        val newKeyAlias: String,
        val wasRunning: Boolean
    ) : ParcelableState {
        constructor(state: ApkKeyManagementState) : this(
            state.selectedPath, state.importSource, state.pendingExport?.paths.orEmpty(),
            state.pendingExport?.asZip ?: false, state.exportDestination, state.pendingDelete,
            state.selectionMode, state.checkedPaths.toList(), state.creatingKey, state.newKeyAlias,
            state.operation != null
        )

        fun toState() = ApkKeyManagementState(
            selectedPath = selectedPath, importSource = importSource,
            pendingExport = pendingExportPaths.takeIf { it.isNotEmpty() }?.let {
                ApkKeyExportRequest(it, pendingExportAsZip)
            },
            exportDestination = exportDestination, pendingDelete = pendingDelete,
            selectionMode = selectionMode, checkedPaths = checkedPaths.toSet(),
            creatingKey = creatingKey, newKeyAlias = newKeyAlias
        )
    }
}
