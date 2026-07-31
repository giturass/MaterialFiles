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
import me.zhanghai.android.files.databinding.AddColumnDialogBinding
import me.zhanghai.android.files.databinding.DatabaseColumnEditorBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/** Composes the `ALTER TABLE ADD COLUMN` for a table that already exists. */
class AddColumnDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var editor: ColumnEditor

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = AddColumnDialogBinding.inflate(requireContext().layoutInflater)
        val editorBinding = DatabaseColumnEditorBinding.inflate(
            requireContext().layoutInflater, binding.columnLayout, true
        )
        // SQLite refuses a primary key on a column added to a table that already exists.
        editor = ColumnEditor(editorBinding, isPrimaryKeySupported = false)
        editor.isRemovable = false
        editor.setDefaultHelperText(getString(R.string.database_editor_column_default_helper))
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(getString(R.string.database_editor_add_column_title_format, args.tableName))
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
        val column = editor.readColumn() ?: return
        // Only a table with rows in it needs the existing rows filled in.
        if (args.hasRows && !editor.requireDefaultForNotNull()) {
            return
        }
        listener.addColumn(args.tableName, column)
        dismiss()
    }

    companion object {
        fun show(tableName: String, hasRows: Boolean, fragment: Fragment) {
            AddColumnDialogFragment().putArgs(Args(tableName, hasRows)).show(fragment)
        }
    }

    @Parcelize
    class Args(val tableName: String, val hasRows: Boolean) : ParcelableArgs

    interface Listener {
        fun addColumn(tableName: String, column: NewColumn)
    }
}
