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
import me.zhanghai.android.files.databinding.CreateTableDialogBinding
import me.zhanghai.android.files.databinding.DatabaseColumnEditorBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/** Composes a `CREATE TABLE`: a name, and one or more columns. */
class CreateTableDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: CreateTableDialogBinding
    private val columnEditors = mutableListOf<ColumnEditor>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = CreateTableDialogBinding.inflate(requireContext().layoutInflater)
        binding.addColumnButton.setOnClickListener { addColumnEditor() }
        // A table needs at least one column, so there is always one to fill in.
        addColumnEditor()
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.database_editor_create_table)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                // Bound after the dialog is shown so that a rejected input can keep it open.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
            }
    }

    private fun addColumnEditor() {
        val editorBinding = DatabaseColumnEditorBinding.inflate(
            requireContext().layoutInflater, binding.columnsLayout, true
        )
        val editor = ColumnEditor(editorBinding, isPrimaryKeySupported = true)
        editor.setOnRemoveListener {
            binding.columnsLayout.removeView(editor.root)
            columnEditors -= editor
            updateColumnHeaders()
        }
        // Only on the first column: the same line under every one of them would be noise rather
        // than help.
        if (columnEditors.isEmpty()) {
            editor.setDefaultHelperText(getString(R.string.database_editor_column_default_helper))
        }
        columnEditors += editor
        updateColumnHeaders()
    }

    /** Numbers the cards, and keeps the last remaining one, because a table needs a column. */
    private fun updateColumnHeaders() {
        val isRemovable = columnEditors.size > 1
        columnEditors.forEachIndexed { index, editor ->
            editor.title = getString(R.string.database_editor_column_index_format, index + 1)
            editor.isRemovable = isRemovable
        }
    }

    private fun onOk() {
        val name = binding.tableNameEdit.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.tableNameLayout.error =
                getString(R.string.database_editor_table_name_required)
            return
        }
        if (args.tableNames.any { it.equals(name, ignoreCase = true) }) {
            binding.tableNameLayout.error =
                getString(R.string.database_editor_table_exists_format, name)
            return
        }
        binding.tableNameLayout.error = null
        // Read every editor rather than stopping at the first, so all the gaps are marked at once.
        val columns = columnEditors.mapNotNull { it.readColumn() }
        if (columns.size != columnEditors.size) {
            return
        }
        val withoutRowId = binding.withoutRowIdCheckBox.isChecked
        if (withoutRowId && columns.none { it.isPrimaryKey }) {
            binding.withoutRowIdCheckBox.error =
                getString(R.string.database_editor_without_rowid_needs_primary_key)
            return
        }
        listener.createTable(name, columns, withoutRowId)
        dismiss()
    }

    companion object {
        fun show(tableNames: List<String>, fragment: Fragment) {
            CreateTableDialogFragment().putArgs(Args(tableNames)).show(fragment)
        }
    }

    @Parcelize
    class Args(val tableNames: List<String>) : ParcelableArgs

    interface Listener {
        fun createTable(name: String, columns: List<NewColumn>, withoutRowId: Boolean)
    }
}
