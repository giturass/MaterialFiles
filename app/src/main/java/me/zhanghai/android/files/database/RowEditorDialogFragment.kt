/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RowEditorDialogBinding
import me.zhanghai.android.files.databinding.RowEditorFieldBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/** Edits a single row, or composes a new one when [Args.row] is null. */
class RowEditorDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: RowEditorDialogBinding
    private lateinit var fieldBindings: List<RowEditorFieldBinding>

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val table = args.table
        val row = args.row
        binding = RowEditorDialogBinding.inflate(requireContext().layoutInflater)
        fieldBindings = table.columns.mapIndexed { index, column ->
            RowEditorFieldBinding.inflate(
                requireContext().layoutInflater, binding.fieldsLayout, true
            ).apply {
                valueLayout.hint = if (column.declaredType.isNotEmpty()) {
                    getString(
                        R.string.database_editor_field_hint_format, column.name, column.declaredType
                    )
                } else {
                    getString(R.string.database_editor_field_hint_no_type_format, column.name)
                }
                val value = row?.values?.getOrNull(index)
                // Every field is inflated from the same layout, so they all share view IDs and
                // automatic state restore would mix them up. The values come from the arguments,
                // which survive recreation anyway.
                valueEdit.isSaveEnabled = false
                nullCheckBox.isSaveEnabled = false
                when (value) {
                    // A new row defaults to NULL, which lets SQLite fill in defaults and rowids.
                    null, is SqlValue.Null -> nullCheckBox.isChecked = true
                    is SqlValue.Integer -> valueEdit.setText(value.value.toString())
                    is SqlValue.Real -> valueEdit.setText(value.value.toString())
                    is SqlValue.Text -> valueEdit.setText(value.value)
                    is SqlValue.Blob -> valueEdit.setText(value.toDisplayText(requireContext()))
                }
                if (value is SqlValue.Blob) {
                    // We have no editor for binary content, so it is kept unless set to NULL.
                    valueEdit.isEnabled = false
                    valueLayout.helperText =
                        getString(R.string.database_editor_blob_not_editable)
                }
                nullCheckBox.setOnCheckedChangeListener { _, isChecked ->
                    valueEdit.isEnabled = !isChecked && value !is SqlValue.Blob
                }
                valueEdit.isEnabled = !nullCheckBox.isChecked && value !is SqlValue.Blob
            }
        }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(
                if (row != null) {
                    R.string.database_editor_edit_row
                } else {
                    R.string.database_editor_add_row
                }
            )
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (row != null) {
                    setNeutralButton(R.string.delete) { _, _ -> listener.deleteRow(row) }
                }
            }
            .create()
    }

    private fun onOk() {
        val table = args.table
        val row = args.row
        val values = table.columns.mapIndexed { index, column ->
            val fieldBinding = fieldBindings[index]
            if (fieldBinding.nullCheckBox.isChecked) {
                SqlValue.Null
            } else {
                val original = row?.values?.getOrNull(index)
                if (original is SqlValue.Blob) {
                    original
                } else {
                    parseSqlValue(fieldBinding.valueEdit.text?.toString().orEmpty(), column)
                }
            }
        }
        if (row != null) {
            listener.updateRow(row, values)
        } else {
            listener.insertRow(values)
        }
    }

    companion object {
        fun show(table: SqlTable, row: SqlRow?, fragment: Fragment) {
            RowEditorDialogFragment().putArgs(Args(table, row)).show(fragment)
        }
    }

    @Parcelize
    class Args(val table: SqlTable, val row: SqlRow?) : ParcelableArgs

    interface Listener {
        fun insertRow(values: List<SqlValue>)
        fun updateRow(row: SqlRow, values: List<SqlValue>)
        fun deleteRow(row: SqlRow)
    }
}
