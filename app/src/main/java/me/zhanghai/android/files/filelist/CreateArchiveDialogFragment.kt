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
import me.zhanghai.android.files.provider.archive.archiver.ArchiveCompressionPreset
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

    private val supportsSingleFileFormats by lazy {
        args.files.singleOrNull()?.attributesNoFollowLinks?.isRegularFile == true
    }

    private val archiveTypes by lazy {
        ArchiveType.entries.filter { !it.singleFileOnly || supportsSingleFileFormats }
    }

    private var archiveType = ArchiveType.ZIP
    private var compressionPreset = ArchiveCompressionPreset.STANDARD
    private var splitOption = SplitOption.NONE

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

        compressionPreset = ArchiveCompressionPreset.entries.find {
            it.name == savedInstanceState?.getString(STATE_COMPRESSION_PRESET)
        } ?: ArchiveCompressionPreset.STANDARD
        binding.compressionEdit.setSimpleItems(
            ArchiveCompressionPreset.entries.map { getString(it.titleRes) }.toTypedArray()
        )
        binding.compressionEdit.setText(getString(compressionPreset.titleRes), false)
        binding.compressionEdit.setOnItemClickListener { _, _, position, _ ->
            compressionPreset = ArchiveCompressionPreset.entries[position]
        }
        splitOption = SplitOption.entries.find {
            it.name == savedInstanceState?.getString(STATE_SPLIT_OPTION)
        } ?: SplitOption.NONE
        binding.splitEdit.setSimpleItems(
            SplitOption.entries.map { getString(it.titleRes) }.toTypedArray()
        )
        binding.splitEdit.setText(getString(splitOption.titleRes), false)
        binding.splitEdit.setOnItemClickListener { _, _, position, _ ->
            splitOption = SplitOption.entries[position]
            binding.nameLayout.error = null
            updateSplitFields()
        }
        binding.customSplitSizeEdit.doAfterTextChanged {
            binding.customSplitSizeLayout.error = null
        }
        binding.customSplitSizeEdit.setOnEditorConfirmActionListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        // Deletion is opt-in for every new compression task. Rotation preserves that choice.
        binding.deleteSourcesCheck.isChecked = savedInstanceState?.getBoolean(STATE_DELETE_SOURCES)
            ?: false
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
        } else {
            binding.nameEdit.setText(savedInstanceState.getString(STATE_NAME))
            binding.passwordEdit.setText(savedInstanceState.getString(STATE_PASSWORD))
            binding.encryptFileNamesCheck.isChecked =
                savedInstanceState.getBoolean(STATE_ENCRYPT_FILE_NAMES)
            binding.customSplitSizeEdit.setText(savedInstanceState.getString(STATE_CUSTOM_SPLIT_SIZE))
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
        outState.putString(STATE_COMPRESSION_PRESET, compressionPreset.name)
        outState.putString(STATE_SPLIT_OPTION, splitOption.name)
        outState.putString(STATE_CUSTOM_SPLIT_SIZE, binding.customSplitSizeEdit.text?.toString())
        outState.putBoolean(STATE_DELETE_SOURCES, binding.deleteSourcesCheck.isChecked)
        outState.putString(STATE_NAME, binding.nameEdit.text.toString())
        outState.putString(STATE_PASSWORD, binding.passwordEdit.text?.toString())
        outState.putBoolean(STATE_ENCRYPT_FILE_NAMES, binding.encryptFileNamesCheck.isChecked)
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
            val baseName = if (splitOption != SplitOption.NONE
                && name.endsWith("$suffix.001", ignoreCase = true)) {
                name.dropLast(4)
            } else {
                name
            }
            return if (baseName.endsWith(suffix, ignoreCase = true)) baseName else baseName + suffix
        }

    private fun updateNamePreview() {
        binding.nameLayout.helperText = name.takeIfNotEmpty()?.let {
            getString(R.string.file_create_archive_name_preview_format, firstOutputName(it))
        }
    }

    private fun updateFormatFields() {
        binding.passwordLayout.isGone = !archiveType.supportsPassword
        binding.passwordLayout.error = null
        binding.encryptFileNamesCheck.isGone = archiveType != ArchiveType.SEVEN_Z
        binding.compressionLayout.isGone = archiveType == ArchiveType.TAR
        binding.typeLayout.helperText = when {
            archiveType.singleFileOnly -> getString(R.string.file_create_archive_single_file_hint)
            !archiveType.supportsPassword -> getString(R.string.file_create_archive_no_encryption)
            else -> null
        }
        updateSplitFields()
    }

    private fun updateSplitFields() {
        binding.customSplitSizeLayout.isGone = splitOption != SplitOption.CUSTOM
        binding.customSplitSizeLayout.error = null
        binding.splitLayout.helperText = if (splitOption != SplitOption.NONE) {
            getString(R.string.file_create_archive_split_hint)
        } else {
            null
        }
        updateNamePreview()
    }

    private fun firstOutputName(name: String): String =
        if (splitOption == SplitOption.NONE) name else "$name.001"

    private val splitSize: Long?
        get() = if (splitOption == SplitOption.CUSTOM) {
            binding.customSplitSizeEdit.text?.toString()?.trim()?.toLongOrNull()
                ?.takeIf { it > 0 && it <= Long.MAX_VALUE / MEBIBYTE }?.times(MEBIBYTE)
        } else {
            splitOption.megabytes * MEBIBYTE
        }

    override fun isNameValid(name: String): Boolean {
        if (!super.isNameValid(if (name.isEmpty()) name else firstOutputName(name))) {
            return false
        }
        if (archiveType.singleFileOnly && !supportsSingleFileFormats) {
            binding.typeLayout.error = getString(R.string.file_create_archive_single_file_required)
            return false
        }
        if (splitOption == SplitOption.CUSTOM && splitSize == null) {
            val text = binding.customSplitSizeEdit.text?.toString()?.trim().orEmpty()
            val isPositiveNumber = text.isNotEmpty() && text.all { it in '0'..'9' }
                && text.any { it != '0' }
            binding.customSplitSizeLayout.error = getString(
                if (isPositiveNumber) R.string.file_create_archive_split_size_too_large
                else R.string.file_create_archive_split_size_invalid
            )
            binding.customSplitSizeEdit.requestFocus()
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
            runInBackground, compressionPreset, checkNotNull(splitSize),
            binding.deleteSourcesCheck.isChecked
        )
    }

    private val ArchiveCompressionPreset.titleRes: Int
        @StringRes get() = when (this) {
            ArchiveCompressionPreset.SPEED -> R.string.file_create_archive_compression_speed
            ArchiveCompressionPreset.STANDARD -> R.string.file_create_archive_compression_standard
            ArchiveCompressionPreset.QUALITY -> R.string.file_create_archive_compression_quality
        }

    companion object {
        private const val MEBIBYTE = 1024L * 1024
        private const val STATE_ARCHIVE_TYPE = "archiveType"
        private const val STATE_COMPRESSION_PRESET = "compressionPreset"
        private const val STATE_SPLIT_OPTION = "splitOption"
        private const val STATE_CUSTOM_SPLIT_SIZE = "customSplitSize"
        private const val STATE_DELETE_SOURCES = "deleteSources"
        private const val STATE_NAME = "archiveName"
        private const val STATE_PASSWORD = "password"
        private const val STATE_ENCRYPT_FILE_NAMES = "encryptFileNames"
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
        val compressionLayout: TextInputLayout,
        val compressionEdit: MaterialAutoCompleteTextView,
        val splitLayout: TextInputLayout,
        val splitEdit: MaterialAutoCompleteTextView,
        val customSplitSizeLayout: TextInputLayout,
        val customSplitSizeEdit: TextInputEditText,
        val passwordLayout: TextInputLayout,
        val passwordEdit: TextInputEditText,
        val encryptFileNamesCheck: MaterialCheckBox,
        val deleteSourcesCheck: MaterialCheckBox
    ) : NameDialogFragment.Binding(root, nameLayout, nameEdit) {
        companion object {
            fun inflate(inflater: LayoutInflater): Binding {
                val binding = CreateArchiveDialogBinding.inflate(inflater)
                val bindingRoot = binding.root
                return Binding(
                    bindingRoot, binding.nameLayout, binding.nameEdit, binding.typeLayout,
                    binding.typeEdit, binding.compressionLayout, binding.compressionEdit,
                    binding.splitLayout, binding.splitEdit, binding.customSplitSizeLayout,
                    binding.customSplitSizeEdit, binding.passwordLayout, binding.passwordEdit,
                    binding.encryptFileNamesCheck, binding.deleteSourcesCheck
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
            runInBackground: Boolean,
            compressionPreset: ArchiveCompressionPreset,
            splitSize: Long,
            deleteSources: Boolean
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
        SEVEN_Z(R.id.sevenZRadio, R.string.file_create_archive_type_7z, "7z",
            Archive.FORMAT_7ZIP, Archive.FILTER_NONE),
        TAR(R.id.tarRadio, R.string.file_create_archive_type_tar, "tar",
            Archive.FORMAT_TAR, Archive.FILTER_NONE),
        TAR_GZ(R.id.tarGzRadio, R.string.file_create_archive_type_tar_gz, "tar.gz",
            Archive.FORMAT_TAR, Archive.FILTER_GZIP),
        TAR_BZ2(R.id.tarBz2Radio, R.string.file_create_archive_type_tar_bz2, "tar.bz2",
            Archive.FORMAT_TAR, Archive.FILTER_BZIP2),
        TAR_XZ(R.id.tarXzRadio, R.string.file_create_archive_type_tar_xz, "tar.xz",
            Archive.FORMAT_TAR, Archive.FILTER_XZ),
        GZ(R.id.gzipRadio, R.string.file_create_archive_type_gz, "gz",
            Archive.FORMAT_RAW, Archive.FILTER_GZIP),
        BZ2(R.id.bzip2Radio, R.string.file_create_archive_type_bz2, "bz2",
            Archive.FORMAT_RAW, Archive.FILTER_BZIP2),
        XZ(R.id.xzRadio, R.string.file_create_archive_type_xz, "xz",
            Archive.FORMAT_RAW, Archive.FILTER_XZ);

        val supportsPassword: Boolean
            get() = this == ZIP || this == SEVEN_Z

        val singleFileOnly: Boolean
            get() = format == Archive.FORMAT_RAW
    }

    private enum class SplitOption(@StringRes val titleRes: Int, val megabytes: Long) {
        NONE(R.string.file_create_archive_split_none, 0),
        FIVE_MB(R.string.file_create_archive_split_5mb, 5),
        TEN_MB(R.string.file_create_archive_split_10mb, 10),
        FIFTY_MB(R.string.file_create_archive_split_50mb, 50),
        CUSTOM(R.string.file_create_archive_split_custom, -1)
    }
}
