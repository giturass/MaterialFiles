/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DatabaseEditorFragmentBinding
import me.zhanghai.android.files.databinding.DatabaseEditorNewTableItemBinding
import me.zhanghai.android.files.databinding.DatabaseEditorTableItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.ui.FixQueryChangeSearchView
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import java.util.Locale

class DatabaseEditorFragment : Fragment(), RowEditorDialogFragment.Listener,
    SqlConsoleDialogFragment.Listener, CreateTableDialogFragment.Listener,
    AddColumnDialogFragment.Listener, RenameTableDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsPath: Path

    private lateinit var binding: DatabaseEditorFragmentBinding
    private lateinit var menuBinding: MenuBinding
    private lateinit var adapter: DatabaseRowAdapter

    private val viewModel by viewModels { { DatabaseEditorViewModel(argsPath) } }

    override val databaseEditorViewModel: DatabaseEditorViewModel
        get() = viewModel

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    /** Closes the table list before the unsaved-changes prompt gets a say. */
    private lateinit var tableListOnBackPressedCallback: OnBackPressedCallback

    // One launcher per destination rather than one plus a remembered intent, so that a result
    // arriving after the process was recreated still knows what it was for.
    private val exportCsvLauncher = registerForActivityResult(
        FileListActivity.CreateFileContract()
    ) { path -> path?.let { viewModel.exportTableCsv(it) } }

    private val exportJsonLauncher = registerForActivityResult(
        FileListActivity.CreateFileContract()
    ) { path -> path?.let { viewModel.exportTableJson(it) } }

    private val exportSqlLauncher = registerForActivityResult(
        FileListActivity.CreateFileContract()
    ) { path -> path?.let { viewModel.exportDatabaseSql(it) } }

    private val exportDatabaseLauncher = registerForActivityResult(
        FileListActivity.CreateFileContract()
    ) { path -> path?.let { viewModel.exportDatabaseFile(it) } }

    private val importLauncher = registerForActivityResult(
        FileListActivity.OpenFileContract()
    ) { path -> path?.let { importFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        DatabaseEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsPath = args.intent.extraPath
        if (argsPath == null) {
            finish()
            return
        }
        this.argsPath = argsPath

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        activity.title = getString(R.string.database_editor_title_format, argsPath.name)

        adapter = DatabaseRowAdapter(
            onRowClick = { position -> onRowClicked(adapter.getRow(position)) },
            onRowLongClick = { position -> confirmDeleteRow(adapter.getRow(position)) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@DatabaseEditorFragment.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) {
                        return
                    }
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= layoutManager.itemCount - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMoreRows()
                    }
                }
            })
        }
        binding.tableButton.setOnClickListener { setTableListExpanded(!isTableListExpanded) }
        binding.tableListScrim.setOnClickListener { setTableListExpanded(false) }
        binding.addRowButton.setOnClickListener { onAddRowClicked() }
        binding.writeBackButton.setOnClickListener { writeBack() }

        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                confirmClose()
            }
        }
        addOnBackPressedCallback(onBackPressedCallback)
        tableListOnBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                setTableListExpanded(false)
            }
        }
        // Added last, so it is asked first.
        addOnBackPressedCallback(tableListOnBackPressedCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            launch { viewModel.databaseState.collect { onDatabaseStateChanged(it) } }
            launch { viewModel.tables.collect { onTablesChanged() } }
            launch { viewModel.tableDataState.collect { onTableDataStateChanged(it) } }
            launch { viewModel.searchQuery.collect { onSearchQueryChanged(it) } }
            launch { viewModel.hasUnsavedChanges.collect { onHasUnsavedChangesChanged(it) } }
            launch { viewModel.writeBackState.collect { onWriteBackStateChanged(it) } }
            launch { viewModel.ioState.collect { onIoStateChanged(it) } }
            launch { viewModel.errorEvents.collect { onErrorEvent(it) } }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
        setUpSearchView()
        updateMenuItems()
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        searchView.queryHint = getString(R.string.database_editor_search_hint)
        val query = viewModel.searchQuery.value
        if (query.isNotEmpty()) {
            // Survives the menu being rebuilt, which happens on every rotation.
            menuBinding.searchItem.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                viewModel.setSearchQuery(query)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.setSearchQuery(newText)
                return true
            }
        })
        menuBinding.searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    // Closing the box goes back to the whole table.
                    viewModel.setSearchQuery("")
                    return true
                }
            }
        )
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateMenuItems()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_sql_console -> {
                SqlConsoleDialogFragment.show(this)
                true
            }
            R.id.action_write_back -> {
                writeBack()
                true
            }
            R.id.action_export_csv -> {
                launchExport(exportCsvLauncher, CSV_MIME_TYPE, "csv")
                true
            }
            R.id.action_export_json -> {
                launchExport(exportJsonLauncher, JSON_MIME_TYPE, "json")
                true
            }
            R.id.action_export_sql -> {
                exportSqlLauncher.launch(
                    Triple(SQL_MIME_TYPE, "${argsPath.name.substringBeforeLast('.')}.sql", pickerDirectory)
                )
                true
            }
            R.id.action_export_database -> {
                exportDatabaseLauncher.launch(
                    Triple(MimeType.ANY, argsPath.name, pickerDirectory)
                )
                true
            }
            R.id.action_import -> {
                importLauncher.launch(listOf(CSV_MIME_TYPE, JSON_MIME_TYPE, MimeType.ANY))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    /** Reads the picked file as whichever of the two formats its name says it is. */
    private fun importFile(path: Path) {
        when (path.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv" -> viewModel.importTableCsv(path)
            "json" -> viewModel.importTableJson(path)
            else -> showToast(R.string.database_editor_import_unsupported)
        }
    }

    /** Exports the current table, named after it so that several exports don't collide. */
    private fun launchExport(
        launcher: androidx.activity.result.ActivityResultLauncher<Triple<MimeType, String?, Path?>>,
        mimeType: MimeType,
        extension: String
    ) {
        val table = currentTable ?: return
        launcher.launch(Triple(mimeType, "${table.name}.$extension", pickerDirectory))
    }

    /** Where a picker opens: next to the database, which is where an export usually belongs. */
    private val pickerDirectory: Path?
        get() = argsPath.parent

    fun onSupportNavigateUp(): Boolean {
        if (isTableListExpanded) {
            setTableListExpanded(false)
            return true
        }
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    private fun finish() {
        requireActivity().finish()
    }

    private fun onDatabaseStateChanged(state: DataState<DatabaseEditorViewModel.OpenedDatabase>) {
        when (state) {
            is DataState.Loading -> {
                binding.progress.isVisible = true
                binding.emptyText.isVisible = false
            }
            is DataState.Success -> {
                binding.cacheCopyBanner.isVisible = state.data.isCacheCopy
            }
            is DataState.Error -> {
                binding.progress.isVisible = false
                binding.tableScroll.isVisible = false
                binding.addRowButton.isVisible = false
                binding.emptyText.isVisible = true
                binding.emptyText.text = state.throwable.toString()
            }
        }
        updateTableBar()
        updateMenuItems()
    }

    private fun onTablesChanged() {
        updateTableBar()
        if (isTableListExpanded) {
            bindTableList()
        }
        updateMenuItems()
    }

    private fun onTableDataStateChanged(state: DataState<DatabaseEditorViewModel.TableData>?) {
        if (viewModel.databaseState.value is DataState.Error) {
            return
        }
        when (state) {
            null -> {
                // The database opened, but there is nothing in it to show. The bar stays, because
                // it is where the first table gets made.
                binding.progress.isVisible =
                    viewModel.databaseState.value is DataState.Loading
                binding.tableScroll.isVisible = false
                binding.addRowButton.isVisible = false
                binding.tableButton.setText(R.string.database_editor_tables)
                binding.rowCountText.text = null
                binding.emptyText.isVisible =
                    viewModel.databaseState.value is DataState.Success
                binding.emptyText.setText(R.string.database_editor_no_tables)
            }
            is DataState.Loading -> {
                binding.progress.isVisible = true
                binding.emptyText.isVisible = false
            }
            is DataState.Success -> {
                val data = state.data
                binding.progress.isVisible = false
                binding.tableButton.text = if (data.table.isView) {
                    getString(R.string.database_editor_view_suffix_format, data.table.name)
                } else {
                    data.table.name
                }
                val isSearching = data.query.isNotEmpty()
                binding.rowCountText.text = if (data.rows.size.toLong() < data.totalRows) {
                    getString(
                        if (isSearching) {
                            R.string.database_editor_row_count_matching_loaded_format
                        } else {
                            R.string.database_editor_row_count_loaded_format
                        },
                        data.rows.size, data.totalRows
                    )
                } else {
                    getString(
                        if (isSearching) {
                            R.string.database_editor_row_count_matching_format
                        } else {
                            R.string.database_editor_row_count_format
                        },
                        data.totalRows
                    )
                }
                val columnNames = data.table.columns.map { it.name }
                binding.headerRow.bindHeaderRow(columnNames)
                setTableWidth(columnNames.size)
                adapter.setRows(columnNames.size, data.rows)
                val hasRows = data.rows.isNotEmpty()
                // The header stays up for a table with no rows in it, because it is the only place
                // the columns are shown: hiding it made a column that had just been added look
                // like it had not been added at all.
                binding.tableScroll.isVisible = columnNames.isNotEmpty()
                binding.emptyText.isVisible = !hasRows
                binding.emptyText.setText(
                    if (isSearching) {
                        R.string.database_editor_no_matching_rows
                    } else {
                        R.string.database_editor_no_rows
                    }
                )
                binding.addRowButton.isVisible =
                    data.table.isEditable && !viewModel.isDatabaseReadOnly
            }
            is DataState.Error -> {
                binding.progress.isVisible = false
                binding.tableScroll.isVisible = false
                binding.addRowButton.isVisible = false
                binding.emptyText.isVisible = true
                binding.emptyText.text = state.throwable.toString()
            }
        }
        if (isTableListExpanded) {
            // The highlight follows whichever table is now open.
            bindTableList()
        }
        updateTableBar()
        updateMenuItems()
    }

    /**
     * Cells have a fixed width, so the table is exactly as wide as its columns and the enclosing
     * [android.widget.HorizontalScrollView] can scroll it.
     */
    private fun setTableWidth(columnCount: Int) {
        val width = columnCount * resources.getDimensionPixelSize(
            R.dimen.database_editor_cell_width
        )
        binding.headerRow.layoutParams = binding.headerRow.layoutParams.apply { this.width = width }
        binding.recyclerView.layoutParams =
            binding.recyclerView.layoutParams.apply { this.width = width }
    }

    private fun onHasUnsavedChangesChanged(hasUnsavedChanges: Boolean) {
        onBackPressedCallback.isEnabled = hasUnsavedChanges
        updateMenuItems()
    }

    private fun onWriteBackStateChanged(state: ActionState<Path, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {}
            is ActionState.Success -> {
                showToast(R.string.database_editor_write_back_success)
                viewModel.finishWritingBack()
            }
            is ActionState.Error -> {
                showToast(state.throwable.toString())
                viewModel.finishWritingBack()
            }
        }
        updateMenuItems()
    }

    private fun onErrorEvent(throwable: Throwable?) {
        throwable ?: return
        val message = if (throwable is DatabaseRepository.AmbiguousRowException) {
            getString(R.string.database_editor_ambiguous_row)
        } else {
            throwable.toString()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        viewModel.consumeError()
    }

    private fun updateMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val isOpen = viewModel.databaseState.value is DataState.Success
        val hasTable = isOpen && viewModel.tableDataState.value is DataState.Success
        val isAlterable = hasTable && isCurrentTableAlterable
        menuBinding.searchItem.isVisible = hasTable
        menuBinding.sqlConsoleItem.isVisible = isOpen
        // An import puts rows into the table on screen, so it needs one it is allowed to write to.
        menuBinding.importItem.isVisible = isAlterable
        menuBinding.exportItem.isVisible = isOpen
        menuBinding.exportCsvItem.isVisible = hasTable
        menuBinding.exportJsonItem.isVisible = hasTable
        val isCacheCopy =
            (viewModel.databaseState.value as? DataState.Success)?.data?.isCacheCopy == true
        menuBinding.writeBackItem.isVisible = isCacheCopy
        menuBinding.writeBackItem.isEnabled =
            viewModel.hasUnsavedChanges.value && viewModel.writeBackState.value !is
                ActionState.Running
    }

    /**
     * The bar carries the table list, so it stays for a database with no tables in it yet: that is
     * where the first one gets made. It only goes away when there is nothing to list and nothing
     * that could be added.
     */
    private fun updateTableBar() {
        val isOpen = viewModel.databaseState.value is DataState.Success
        val isVisible =
            isOpen && (viewModel.tables.value.isNotEmpty() || !viewModel.isDatabaseReadOnly)
        binding.tableBar.isVisible = isVisible
        if (!isVisible) {
            setTableListExpanded(false)
        }
    }

    private val isTableListExpanded: Boolean
        get() = binding.tableListPanel.isVisible

    private fun setTableListExpanded(expanded: Boolean) {
        if (expanded == isTableListExpanded) {
            return
        }
        if (expanded) {
            bindTableList()
        } else {
            binding.tableList.removeAllViews()
        }
        binding.tableListPanel.isVisible = expanded
        binding.tableListScrim.isVisible = expanded
        binding.tableButton.setIconResource(
            if (expanded) {
                R.drawable.keyboard_arrow_up_icon_control_normal_24dp
            } else {
                R.drawable.keyboard_arrow_down_icon_control_normal_24dp
            }
        )
        tableListOnBackPressedCallback.isEnabled = expanded
    }

    /**
     * Fills the panel in from scratch. The list is as long as the database has tables, which is
     * short enough that rebuilding it costs less than keeping an adapter in step with the schema.
     */
    private fun bindTableList() {
        val inflater = requireContext().layoutInflater
        binding.tableList.removeAllViews()
        val currentName = currentTable?.name
        val isWritable = !viewModel.isDatabaseReadOnly
        for (table in viewModel.tables.value) {
            val itemBinding =
                DatabaseEditorTableItemBinding.inflate(inflater, binding.tableList, true)
            itemBinding.nameText.text = if (table.isView) {
                getString(R.string.database_editor_view_suffix_format, table.name)
            } else {
                table.name
            }
            // The table on screen is picked out, so the list also says where you are.
            if (table.name == currentName) {
                itemBinding.nameText.setTextColor(
                    requireContext().getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
                )
            }
            itemBinding.itemLayout.setOnClickListener {
                setTableListExpanded(false)
                viewModel.selectTable(table.name)
            }
            // Nothing in the menu can be done to a database opened read-only.
            itemBinding.menuButton.isVisible = isWritable
            itemBinding.menuButton.setOnClickListener { showTableActionMenu(it, table) }
        }
        if (isWritable) {
            val newTableBinding =
                DatabaseEditorNewTableItemBinding.inflate(inflater, binding.tableList, true)
            newTableBinding.itemLayout.setOnClickListener {
                setTableListExpanded(false)
                CreateTableDialogFragment.show(viewModel.tableNames, this)
            }
        }
    }

    private fun showTableActionMenu(anchor: View, table: SqlTableRef) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.database_editor_table, menu)
            // A view has no columns of its own to change, but it can still be dropped.
            menu.findItem(R.id.action_add_column).isVisible = !table.isView
            menu.findItem(R.id.action_rename_table).isVisible = !table.isView
            menu.findItem(R.id.action_truncate_table).isVisible = !table.isView
            setOnMenuItemClickListener { item ->
                val handled = when (item.itemId) {
                    R.id.action_add_column -> {
                        onAddColumnClicked(table.name)
                        true
                    }
                    R.id.action_drop_table -> {
                        confirmDropTable(table.name)
                        true
                    }
                    R.id.action_rename_table -> {
                        onRenameTableClicked(table.name)
                        true
                    }
                    R.id.action_truncate_table -> {
                        confirmTruncateTable(table.name)
                        true
                    }
                    else -> false
                }
                if (handled) {
                    // After the action, so that the anchor is not taken out from under the menu
                    // that is still dismissing itself.
                    setTableListExpanded(false)
                }
                handled
            }
        }.show()
    }

    /**
     * Keeps the box in step when something other than typing clears the search, such as switching
     * tables or a schema change.
     */
    private fun onSearchQueryChanged(query: String) {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val searchView = menuBinding.searchItem.actionView as? SearchView ?: return
        if (query.isEmpty() && searchView.query.isNotEmpty()) {
            menuBinding.searchItem.collapseActionView()
        }
    }

    private val currentTable: SqlTable?
        get() = (viewModel.tableDataState.value as? DataState.Success)?.data?.table

    /** Schema changes only make sense on a real table we are allowed to write to. */
    private val isCurrentTableAlterable: Boolean
        get() = currentTable?.isView == false && !viewModel.isDatabaseReadOnly

    private fun onAddColumnClicked(tableName: String) {
        // The dialog only needs the row count to know whether a NOT NULL column has existing rows
        // to fill in, and the table may not be the one on screen.
        viewModel.getRowCount(tableName) { rowCount ->
            // Counted on a background dispatcher, so by the time it comes back the fragment may be
            // gone, or still here but with its state already saved - in which case showing the
            // dialog would be a transaction after onSaveInstanceState(), which throws.
            if (!isAdded || childFragmentManager.isStateSaved) {
                return@getRowCount
            }
            AddColumnDialogFragment.show(tableName, rowCount > 0, this)
        }
    }

    private fun onRenameTableClicked(tableName: String) {
        RenameTableDialogFragment.show(tableName, viewModel.tableNames, this)
    }

    private fun confirmTruncateTable(tableName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(
                getString(R.string.database_editor_truncate_table_message_format, tableName)
            )
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.truncateTable(tableName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDropTable(tableName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.database_editor_drop_table_message_format, tableName))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.dropTable(tableName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun createTable(name: String, columns: List<NewColumn>, withoutRowId: Boolean) {
        viewModel.createTable(name, columns, withoutRowId)
    }

    override fun addColumn(tableName: String, column: NewColumn) {
        viewModel.addColumn(tableName, column)
    }

    override fun renameTable(tableName: String, newName: String) {
        viewModel.renameTable(tableName, newName)
    }

    private fun onIoStateChanged(state: ActionState<Unit, DatabaseEditorViewModel.IoResult>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {}
            is ActionState.Success -> {
                showToast(
                    when (val result = state.result) {
                        is DatabaseEditorViewModel.IoResult.TableExported ->
                            getString(R.string.database_editor_exported_format, result.rows)
                        is DatabaseEditorViewModel.IoResult.RowsImported ->
                            getString(R.string.database_editor_imported_format, result.rows)
                        DatabaseEditorViewModel.IoResult.DatabaseExported ->
                            getString(R.string.database_editor_exported_database)
                    }
                )
                viewModel.finishIo()
            }
            is ActionState.Error -> {
                showToast(state.throwable.toString())
                viewModel.finishIo()
            }
        }
        updateMenuItems()
    }

    private fun onAddRowClicked() {
        // The button sits above the table list's scrim, so it has to close the list itself.
        setTableListExpanded(false)
        val table = (viewModel.tableDataState.value as? DataState.Success)?.data?.table ?: return
        RowEditorDialogFragment.show(table, null, this)
    }

    private fun onRowClicked(row: SqlRow) {
        val table = (viewModel.tableDataState.value as? DataState.Success)?.data?.table ?: return
        if (!table.isEditable) {
            showToast(R.string.database_editor_view_read_only)
            return
        }
        if (viewModel.isDatabaseReadOnly) {
            showToast(R.string.database_editor_read_only)
            return
        }
        RowEditorDialogFragment.show(table, row, this)
    }

    private fun confirmDeleteRow(row: SqlRow) {
        val table = (viewModel.tableDataState.value as? DataState.Success)?.data?.table ?: return
        if (!table.isEditable || viewModel.isDatabaseReadOnly) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.database_editor_delete_row_message)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteRow(row) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun insertRow(values: List<SqlValue>) {
        viewModel.insertRow(values)
    }

    override fun updateRow(row: SqlRow, values: List<SqlValue>) {
        viewModel.updateRow(row, values)
    }

    override fun deleteRow(row: SqlRow) {
        confirmDeleteRow(row)
    }

    private fun writeBack() {
        viewModel.writeBack()
    }

    private fun confirmClose() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.database_editor_close_message)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .setNeutralButton(R.string.database_editor_write_back) { _, _ -> writeBack() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val searchItem: MenuItem,
        val sqlConsoleItem: MenuItem,
        val writeBackItem: MenuItem,
        val importItem: MenuItem,
        val exportItem: MenuItem,
        val exportCsvItem: MenuItem,
        val exportJsonItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.database_editor, menu)
                return MenuBinding(
                    menu.findItem(R.id.action_search),
                    menu.findItem(R.id.action_sql_console),
                    menu.findItem(R.id.action_write_back),
                    menu.findItem(R.id.action_import),
                    menu.findItem(R.id.action_export),
                    menu.findItem(R.id.action_export_csv),
                    menu.findItem(R.id.action_export_json)
                )
            }
        }
    }

    companion object {
        /** How close to the end of the loaded rows we get before fetching the next page. */
        private const val LOAD_MORE_THRESHOLD = 10

        private val CSV_MIME_TYPE = "text/csv".asMimeType()

        private val JSON_MIME_TYPE = "application/json".asMimeType()

        private val SQL_MIME_TYPE = "application/sql".asMimeType()
    }
}
