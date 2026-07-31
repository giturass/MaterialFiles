/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.content.Context
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DatabaseColumnEditorBinding

/** The storage classes offered as suggestions. The field stays typable for anything else. */
private val COLUMN_TYPES = listOf("TEXT", "INTEGER", "REAL", "BLOB", "NUMERIC")

/**
 * One inflated column definition, and the reading of a [NewColumn] back out of it.
 *
 * [isPrimaryKeySupported] is false when adding a column to a table that already exists, because
 * SQLite's `ALTER TABLE ADD COLUMN` refuses a primary key.
 */
class ColumnEditor(
    private val binding: DatabaseColumnEditorBinding,
    isPrimaryKeySupported: Boolean
) {
    private val context: Context
        get() = binding.root.context

    /**
     * What this column is headed with, or null to leave it unheaded: a dialog showing a single
     * column has already said which column it means in its own title.
     */
    var title: CharSequence? = null
        set(value) {
            field = value
            binding.titleText.text = value
            updateHeader()
        }

    var isRemovable: Boolean = false
        set(value) {
            field = value
            updateHeader()
        }

    init {
        binding.typeEdit.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, COLUMN_TYPES)
        )
        binding.typeEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.typeEdit.showDropDown()
            }
        }
        binding.primaryKeyCheckBox.isVisible = isPrimaryKeySupported
        // Every editor is inflated from the same layout, so they all share view IDs and automatic
        // state restore would mix them up.
        binding.nameEdit.isSaveEnabled = false
        binding.typeEdit.isSaveEnabled = false
        binding.defaultEdit.isSaveEnabled = false
        binding.primaryKeyCheckBox.isSaveEnabled = false
        binding.notNullCheckBox.isSaveEnabled = false
        updateHeader()
    }

    /** The heading is only worth its height when it carries something. */
    private fun updateHeader() {
        binding.titleText.isVisible = title != null
        binding.removeButton.isVisible = isRemovable
        binding.headerLayout.isVisible = title != null || isRemovable
    }

    val root
        get() = binding.root

    fun setOnRemoveListener(listener: () -> Unit) {
        binding.removeButton.setOnClickListener { listener() }
    }

    fun setDefaultHelperText(text: String) {
        binding.defaultLayout.helperText = text
    }

    /** Reads the column, or marks what is missing and returns null. */
    fun readColumn(): NewColumn? {
        val name = binding.nameEdit.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error =
                context.getString(R.string.database_editor_column_name_required)
            return null
        }
        binding.nameLayout.error = null
        val defaultValue = binding.defaultEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val isNotNull = binding.notNullCheckBox.isChecked
        return NewColumn(
            name,
            binding.typeEdit.text?.toString()?.trim().orEmpty(),
            isNotNull,
            binding.primaryKeyCheckBox.isVisible && binding.primaryKeyCheckBox.isChecked,
            defaultValue
        )
    }

    /**
     * A column added to a table that already has rows has to say what those rows hold in it, so
     * NOT NULL without a default is refused before SQLite refuses it less helpfully.
     */
    fun requireDefaultForNotNull(): Boolean {
        if (binding.notNullCheckBox.isChecked &&
            binding.defaultEdit.text?.toString()?.isBlank() != false) {
            binding.defaultLayout.error =
                context.getString(R.string.database_editor_add_column_not_null_helper)
            return false
        }
        binding.defaultLayout.error = null
        return true
    }
}
