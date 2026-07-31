/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SqlConsoleDialogBinding
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.isRunning
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.show

/** Runs arbitrary SQL against the open database and shows what came back. */
class SqlConsoleDialogFragment : AppCompatDialogFragment() {
    private lateinit var binding: SqlConsoleDialogBinding
    private lateinit var adapter: DatabaseRowAdapter

    private val viewModel: DatabaseEditorViewModel
        get() = (requireParentFragment() as Listener).databaseEditorViewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = SqlConsoleDialogBinding.inflate(requireContext().layoutInflater)
        adapter = DatabaseRowAdapter()
        binding.resultRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SqlConsoleDialogFragment.adapter
        }
        savedInstanceState?.getString(STATE_SQL)?.let { binding.sqlEdit.setText(it) }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.database_editor_sql_console)
            .setView(binding.root)
            .setPositiveButton(R.string.database_editor_execute, null)
            .setNegativeButton(R.string.close, null)
            .create()
            .apply {
                // Override the listener so that running a statement keeps the dialog open.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { execute() }
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            viewModel.statementState.collect { onStatementStateChanged(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(STATE_SQL, binding.sqlEdit.text?.toString())
    }

    private fun execute() {
        val sql = binding.sqlEdit.text?.toString()?.trim().orEmpty()
        if (sql.isEmpty()) {
            return
        }
        viewModel.executeStatement(sql)
    }

    private fun onStatementStateChanged(state: ActionState<String, SqlStatementResult>) {
        if (!this::binding.isInitialized) {
            return
        }
        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            !state.isRunning
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {}
            is ActionState.Success -> {
                showResult(state.result)
                viewModel.finishExecutingStatement()
            }
            is ActionState.Error -> {
                binding.resultScroll.isVisible = false
                binding.resultText.isVisible = true
                binding.resultText.text = state.throwable.toString()
                viewModel.finishExecutingStatement()
            }
        }
    }

    private fun showResult(result: SqlStatementResult) {
        when (result) {
            is SqlStatementResult.Query -> {
                val rows = result.result.rows
                val columns = result.result.columns
                binding.resultText.isVisible = true
                binding.resultText.text = if (rows.size >= DatabaseRepository.MAX_QUERY_ROWS) {
                    getString(
                        R.string.database_editor_execute_rows_truncated_format, rows.size
                    )
                } else {
                    getString(R.string.database_editor_execute_rows_format, rows.size)
                }
                binding.resultScroll.isVisible = rows.isNotEmpty()
                binding.resultHeaderRow.bindHeaderRow(columns)
                adapter.setRows(columns.size, rows)
            }
            is SqlStatementResult.Update -> {
                binding.resultScroll.isVisible = false
                binding.resultText.isVisible = true
                binding.resultText.text = getString(
                    R.string.database_editor_execute_affected_format, result.affectedRows
                )
            }
        }
    }

    companion object {
        private const val STATE_SQL = "sql"

        fun show(fragment: Fragment) {
            SqlConsoleDialogFragment().show(fragment)
        }
    }

    interface Listener {
        val databaseEditorViewModel: DatabaseEditorViewModel
    }
}
