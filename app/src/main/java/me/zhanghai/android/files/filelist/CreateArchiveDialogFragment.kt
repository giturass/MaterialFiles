/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CreateArchiveDialogBinding
import me.zhanghai.android.files.filejob.fileJobNotificationTemplate
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.setTextWithSelection
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.libarchive.Archive
import android.provider.Settings as SystemSettings

class CreateArchiveDialogFragment : FileNameDialogFragment() {
    private val args by args<Args>()

    private val archiveTypes = ArchiveType.entries.filter {
        it != ArchiveType.SEVEN_Z || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    private var archiveType = ArchiveType.ZIP

    override val binding: Binding
        get() = super.binding as Binding

    override val listener: Listener
        get() = super.listener as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as AlertDialog

        val savedTypeId = savedInstanceState?.getInt(STATE_ARCHIVE_TYPE)
            ?: Settings.CREATE_ARCHIVE_TYPE.valueCompat
        archiveType = archiveTypes.find { it.preferenceId == savedTypeId } ?: ArchiveType.ZIP
        binding.typeEdit.setSimpleItems(archiveTypes.map { getString(it.titleRes) }.toTypedArray())
        binding.typeEdit.setText(getString(archiveType.titleRes), false)
        binding.typeEdit.setOnItemClickListener { _, _, position, _ ->
            archiveType = archiveTypes[position]
            Settings.CREATE_ARCHIVE_TYPE.putValue(archiveType.preferenceId)
            binding.nameLayout.error = null
            updateFormatFields()
        }

        if (savedInstanceState == null) {
            val files = args.files
            var name: String? = null
            if (files.size == 1) {
                val sourceName = files.single().path.fileName.toString()
                val suffix = ".${archiveType.extension}"
                // Keep the source archive's extension as part of the new archive name.
                name = if (sourceName.endsWith(suffix, ignoreCase = true)) {
                    sourceName + suffix
                } else {
                    sourceName
                }
            } else {
                val parent = files.mapTo(mutableSetOf()) { it.path.parent }.singleOrNull()
                if (parent != null && parent.nameCount > 0) {
                    name = parent.fileName.toString()
                }
            }
            name?.let { binding.nameEdit.setTextWithSelection(it) }
        }
        binding.nameEdit.doAfterTextChanged { updateNamePreview() }
        binding.passwordEdit.doAfterTextChanged { binding.passwordLayout.error = null }
        binding.passwordEdit.setOnEditorConfirmActionListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        binding.encryptFileNamesCheck.setOnCheckedChangeListener { _, _ ->
            binding.passwordLayout.error = null
        }
        updateFormatFields()
        // This form has several choices; the keyboard should only open when an input is tapped.
        binding.root.requestFocus()
        dialog.window!!.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setButton(
            AlertDialog.BUTTON_NEUTRAL, getString(R.string.file_create_archive_background)
        ) { _, _ -> }
        return dialog
    }

    override fun onStart() {
        super.onStart()

        // Set this after the dialog creates its buttons so invalid input does not dismiss it.
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val name = name
            if (isNameValid(name) && ensureBackgroundNotifications()) {
                archive(name, true)
                dismiss()
            }
        }
        updateFormatFields()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ARCHIVE_TYPE, archiveType.preferenceId)
    }

    @StringRes
    override val titleRes: Int = R.string.file_create_archive_title

    override fun onInflateBinding(inflater: LayoutInflater): NameDialogFragment.Binding =
        Binding.inflate(inflater)

    override val name: String
        get() {
            val name = super.name
            if (name.isEmpty()) {
                return name
            }
            val suffix = ".${archiveType.extension}"
            return if (name.endsWith(suffix, ignoreCase = true)) name else name + suffix
        }

    private fun updateNamePreview() {
        binding.nameLayout.helperText = name.takeIfNotEmpty()?.let {
            getString(R.string.file_create_archive_name_preview_format, it)
        }
    }

    private fun updateFormatFields() {
        binding.passwordLayout.isGone = !archiveType.supportsPassword
        binding.passwordLayout.error = null
        binding.encryptFileNamesCheck.isGone = archiveType != ArchiveType.SEVEN_Z
        binding.typeLayout.helperText = if (archiveType.supportsPassword) {
            null
        } else {
            getString(R.string.file_create_archive_no_encryption)
        }
        updateNamePreview()
    }

    override fun isNameValid(name: String): Boolean {
        if (!super.isNameValid(name)) {
            return false
        }
        if (archiveType == ArchiveType.SEVEN_Z && binding.encryptFileNamesCheck.isChecked &&
            binding.passwordEdit.text.isNullOrEmpty()) {
            binding.passwordLayout.error =
                getString(R.string.file_create_archive_encrypt_file_names_password_required)
            binding.passwordEdit.requestFocus()
            return false
        }
        return true
    }

    override fun onOk(name: String) {
        archive(name, false)
    }

    private fun ensureBackgroundNotifications(): Boolean {
        val notificationManager = NotificationManagerCompat.from(requireContext())
        val appNotificationsEnabled = notificationManager.areNotificationsEnabled()
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(fileJobNotificationTemplate.channelTemplate.id)
                ?.importance == NotificationManagerCompat.IMPORTANCE_NONE
        if (appNotificationsEnabled && !channelBlocked) {
            return true
        }
        if (childFragmentManager.findFragmentByTag(BACKGROUND_NOTIFICATIONS_TAG) == null) {
            BackgroundNotificationsDialogFragment()
                .show(childFragmentManager, BACKGROUND_NOTIFICATIONS_TAG)
        }
        return false
    }

    private fun archive(name: String, runInBackground: Boolean) {
        val password = if (archiveType.supportsPassword) {
            binding.passwordEdit.text!!.toString().takeIfNotEmpty()
        } else {
            null
        }
        val encryptFileNames = archiveType == ArchiveType.SEVEN_Z &&
            binding.encryptFileNamesCheck.isChecked
        listener.archive(
            args.files, name, archiveType.format, archiveType.filter, password, encryptFileNames,
            runInBackground
        )
    }

    companion object {
        private const val STATE_ARCHIVE_TYPE = "archiveType"
        private const val BACKGROUND_NOTIFICATIONS_TAG = "backgroundNotifications"

        fun show(files: FileItemSet, fragment: Fragment) {
            CreateArchiveDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    class BackgroundNotificationsDialogFragment : AppCompatDialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            MaterialAlertDialogBuilder(requireContext(), theme)
                .setMessage(R.string.file_create_archive_notifications_required)
                .setPositiveButton(R.string.file_create_archive_enable_notifications) { _, _ ->
                    openNotificationSettings()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        private fun openNotificationSettings() {
            val context = requireContext()
            val applicationDetailsIntent = Intent(
                SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                startActivitySafe(applicationDetailsIntent)
                return
            }
            val intent = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Intent(SystemSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(
                        SystemSettings.EXTRA_CHANNEL_ID,
                        fileJobNotificationTemplate.channelTemplate.id
                    )
            } else {
                Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            }.putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                startActivitySafe(applicationDetailsIntent)
            }
        }
    }

    protected class Binding private constructor(
        root: View,
        nameLayout: TextInputLayout,
        nameEdit: EditText,
        val typeLayout: TextInputLayout,
        val typeEdit: MaterialAutoCompleteTextView,
        val passwordLayout: TextInputLayout,
        val passwordEdit: TextInputEditText,
        val encryptFileNamesCheck: MaterialCheckBox
    ) : NameDialogFragment.Binding(root, nameLayout, nameEdit) {
        companion object {
            fun inflate(inflater: LayoutInflater): Binding {
                val binding = CreateArchiveDialogBinding.inflate(inflater)
                val bindingRoot = binding.root
                return Binding(
                    bindingRoot, binding.nameLayout, binding.nameEdit, binding.typeLayout,
                    binding.typeEdit, binding.passwordLayout, binding.passwordEdit,
                    binding.encryptFileNamesCheck
                )
            }
        }
    }

    interface Listener : FileNameDialogFragment.Listener {
        fun archive(
            files: FileItemSet,
            name: String,
            format: Int,
            filter: Int,
            password: String?,
            encryptFileNames: Boolean,
            runInBackground: Boolean
        )
    }

    private enum class ArchiveType(
        @IdRes val preferenceId: Int,
        @StringRes val titleRes: Int,
        val extension: String,
        val format: Int,
        val filter: Int
    ) {
        ZIP(R.id.zipRadio, R.string.file_create_archive_type_zip, "zip",
            Archive.FORMAT_ZIP, Archive.FILTER_NONE),
        TAR_GZ(R.id.tarGzRadio, R.string.file_create_archive_type_tar_gz, "tar.gz",
            Archive.FORMAT_TAR, Archive.FILTER_GZIP),
        TAR_XZ(R.id.tarXzRadio, R.string.file_create_archive_type_tar_xz, "tar.xz",
            Archive.FORMAT_TAR, Archive.FILTER_XZ),
        SEVEN_Z(R.id.sevenZRadio, R.string.file_create_archive_type_7z, "7z",
            Archive.FORMAT_7ZIP, Archive.FILTER_NONE);

        val supportsPassword: Boolean
            get() = this == ZIP || this == SEVEN_Z
    }
}
