/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RenameTableDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.show

class RenameTableDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: RenameTableDialogBinding

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = RenameTableDialogBinding.inflate(requireContext().layoutInflater)
        if (savedInstanceState == null) {
            binding.nameEdit.setText(args.tableName)
            binding.nameEdit.setSelection(0, args.tableName.length)
        }
        binding.nameEdit.setOnEditorConfirmActionListener { onOk() }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.database_editor_rename_table)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
            }
    }

    private fun onOk() {
        val name = binding.nameEdit.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error = getString(R.string.database_editor_table_name_required)
            return
        }
        if (name == args.tableName) {
            dismiss()
            return
        }
        if (args.tableNames.any { it.equals(name, ignoreCase = true) }) {
            binding.nameLayout.error =
                getString(R.string.database_editor_table_exists_format, name)
            return
        }
        listener.renameTable(args.tableName, name)
        dismiss()
    }

    companion object {
        fun show(tableName: String, tableNames: List<String>, fragment: Fragment) {
            RenameTableDialogFragment().putArgs(Args(tableName, tableNames)).show(fragment)
        }
    }

    @Parcelize
    class Args(val tableName: String, val tableNames: List<String>) : ParcelableArgs

    interface Listener {
        fun renameTable(tableName: String, newName: String)
    }
}
