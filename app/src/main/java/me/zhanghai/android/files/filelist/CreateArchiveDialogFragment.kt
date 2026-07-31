/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CreateArchiveDialogBinding
import me.zhanghai.android.files.databinding.NameDialogNameIncludeBinding
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.UnfilteredArrayAdapter
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setTextWithSelection
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat

class CreateArchiveDialogFragment : FileNameDialogFragment() {
    private val args by args<Args>()

    private val types = CreateArchiveType.supportedEntries

    override val binding: Binding
        get() = super.binding as Binding

    override val listener: Listener
        get() = super.listener as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        if (savedInstanceState == null) {
            val files = args.files
            var name: String? = null
            if (files.size == 1) {
                name = files.single().path.fileName.toString()
            } else {
                val parent = files.mapTo(mutableSetOf()) { it.path.parent }.singleOrNull()
                if (parent != null && parent.nameCount > 0) {
                    name = parent.fileName.toString()
                }
            }
            name?.let { binding.nameEdit.setTextWithSelection(it) }
        }
        binding.typeEdit.setAdapter(
            UnfilteredArrayAdapter(
                binding.typeEdit.context, R.layout.dropdown_item,
                objects = types.map { getString(it.labelRes) }
            )
        )
        binding.typeEdit.doAfterTextChanged { updateOptionsState() }
        binding.typeEdit.setOnItemClickListener { _, _, _, _ ->
            Settings.CREATE_ARCHIVE_TYPE.putValue(type)
        }
        binding.passwordEdit.doAfterTextChanged { updateOptionsState() }
        if (savedInstanceState == null) {
            type = Settings.CREATE_ARCHIVE_TYPE.valueCompat.takeIf { it.isSupported }
                ?: types.first()
        }
        updateOptionsState()
        return dialog
    }

    @StringRes
    override val titleRes: Int = R.string.file_create_archive_title

    // The name is prefilled from the selection, so opening the soft input right away mostly just
    // covers up the format and password fields below it.
    override val isSoftInputVisibleOnCreate: Boolean = false

    override fun onInflateBinding(inflater: LayoutInflater): NameDialogFragment.Binding =
        Binding.inflate(inflater)

    override val name: String
        get() = "${super.name}.${type.extension}"

    private var type: CreateArchiveType
        get() {
            val selectedItem = binding.typeEdit.text
            val index = types.indexOfFirst {
                TextUtils.equals(getString(it.labelRes), selectedItem)
            }
            return types.getOrElse(index) { types.first() }
        }
        set(value) {
            binding.typeEdit.setText(getString(value.labelRes), false)
        }

    private fun updateOptionsState() {
        val type = type
        binding.passwordLayout.isGone = !type.isPasswordSupported
        binding.encryptFileNamesCheck.isGone = !type.isFileNameEncryptionSupported
        // File names are encrypted with the same password as the content, so without one there is
        // nothing to encrypt them with.
        val hasPassword = binding.passwordEdit.text?.isNotEmpty() == true
        binding.encryptFileNamesCheck.isEnabled = hasPassword
        if (!hasPassword) {
            binding.encryptFileNamesCheck.isChecked = false
        }
    }

    override fun onOk(name: String) {
        val type = type
        val password = if (type.isPasswordSupported) {
            binding.passwordEdit.text!!.toString().takeIfNotEmpty()
        } else {
            null
        }
        val encryptFileNames = password != null && type.isFileNameEncryptionSupported
            && binding.encryptFileNamesCheck.isChecked
        listener.archive(
            args.files, name, type.format, type.filter, password, encryptFileNames,
            binding.deleteSourcesCheck.isChecked
        )
    }

    companion object {
        fun show(files: FileItemSet, fragment: Fragment) {
            CreateArchiveDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    protected class Binding private constructor(
        root: View,
        nameLayout: TextInputLayout,
        nameEdit: EditText,
        val typeEdit: AutoCompleteTextView,
        val passwordLayout: TextInputLayout,
        val passwordEdit: TextInputEditText,
        val encryptFileNamesCheck: MaterialCheckBox,
        val deleteSourcesCheck: MaterialCheckBox
    ) : NameDialogFragment.Binding(root, nameLayout, nameEdit) {
        companion object {
            fun inflate(inflater: LayoutInflater): Binding {
                val binding = CreateArchiveDialogBinding.inflate(inflater)
                val bindingRoot = binding.root
                val nameBinding = NameDialogNameIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, nameBinding.nameLayout, nameBinding.nameEdit, binding.typeEdit,
                    binding.passwordLayout, binding.passwordEdit, binding.encryptFileNamesCheck,
                    binding.deleteSourcesCheck
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
            deleteSources: Boolean
        )
    }
}
