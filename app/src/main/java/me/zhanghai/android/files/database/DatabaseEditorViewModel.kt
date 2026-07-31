/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.copyToCacheFile
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.localFileOrNull
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading
import java.io.File

class DatabaseEditorViewModel(val path: Path) : ViewModel() {
    /**
     * SQLite itself is thread safe, but serializing our work keeps the visible row list consistent
     * with the edits the user just made.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val databaseDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _databaseState = MutableStateFlow<DataState<OpenedDatabase>>(DataState.Loading())
    val databaseState = _databaseState.asStateFlow()

    private val _tables = MutableStateFlow<List<SqlTableRef>>(emptyList())
    val tables = _tables.asStateFlow()

    /** Just the names, for the dialogs that check a new name against the ones already taken. */
    val tableNames: List<String>
        get() = _tables.value.map { it.name }

    private val _tableDataState = MutableStateFlow<DataState<TableData>?>(null)
    val tableDataState = _tableDataState.asStateFlow()

    /** What the search box holds. Empty lists the whole table. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private var searchJob: Job? = null

    /** Set once an edit has been made to a cached copy of a file that lives elsewhere. */
    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges = _hasUnsavedChanges.asStateFlow()

    private val _writeBackState = MutableStateFlow<ActionState<Path, Unit>>(ActionState.Ready())
    val writeBackState = _writeBackState.asStateFlow()

    /** The most recent error from an edit or a statement, for one-shot display. */
    private val _errorEvents = MutableStateFlow<Throwable?>(null)
    val errorEvents = _errorEvents.asStateFlow()

    private var loadJob: Job? = null

    init {
        openDatabase()
    }

    private fun openDatabase() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _databaseState.value = _databaseState.value.toLoading()
            try {
                val database = withContext(databaseDispatcher) {
                    val localFile = path.localFileOrNull
                    val file = localFile ?: path.copyToCacheFile(application, CACHE_SUBDIRECTORY)
                    // A file we only have a copy of can be edited, but the copy is what gets
                    // written, so the user has to push it back explicitly.
                    val repository = DatabaseRepository.open(file, readOnly = false)
                    OpenedDatabase(repository, file, isCacheCopy = localFile == null)
                }
                currentCoroutineContext().ensureActive()
                _databaseState.value = DataState.Success(database)
                refreshTables()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _databaseState.value = _databaseState.value.toError(e)
            }
        }
    }

    /**
     * Reloads the table list, keeping the user on the table they were looking at. [preferredTable]
     * moves them somewhere else instead, for when an operation created or renamed one.
     */
    private suspend fun refreshTables(preferredTable: String? = null) {
        val repository = repositoryOrNull ?: return
        val tables = withContext(databaseDispatcher) { repository.listTables() }
        _tables.value = tables
        val currentTable = preferredTable ?: (_tableDataState.value?.data)?.table?.name
        when {
            currentTable != null && tables.any { it.name == currentTable } ->
                selectTableInternal(currentTable)
            tables.isNotEmpty() -> selectTableInternal(tables.first().name)
            else -> _tableDataState.value = null
        }
    }

    fun selectTable(name: String) {
        // A search belongs to the table it was typed for.
        _searchQuery.value = ""
        viewModelScope.launch { selectTableInternal(name) }
    }

    private suspend fun selectTableInternal(name: String) {
        val repository = repositoryOrNull ?: return
        _tableDataState.value = DataState.Loading()
        try {
            val query = _searchQuery.value.takeIf { it.isNotEmpty() }
            val data = withContext(databaseDispatcher) {
                val table = repository.getTable(name)
                val totalRows = repository.countRows(table, query)
                val rows = repository.queryPage(table, 0, PAGE_SIZE, query)
                TableData(table, rows, totalRows, query = query.orEmpty())
            }
            currentCoroutineContext().ensureActive()
            _tableDataState.value = DataState.Success(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            _tableDataState.value = _tableDataState.value?.toError(e) ?: DataState.Error(null, e)
        }
    }

    fun loadMoreRows() {
        val current = _tableDataState.value as? DataState.Success ?: return
        val data = current.data
        if (!data.hasMore || data.isLoadingMore) {
            return
        }
        _tableDataState.value = DataState.Success(data.copy(isLoadingMore = true))
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            try {
                val moreRows = withContext(databaseDispatcher) {
                    repository.queryPage(
                        data.table, data.rows.size.toLong(), PAGE_SIZE,
                        data.query.takeIf { it.isNotEmpty() }
                    )
                }
                currentCoroutineContext().ensureActive()
                val latest = (_tableDataState.value as? DataState.Success)?.data ?: return@launch
                if (latest.table.name != data.table.name || latest.query != data.query) {
                    return@launch
                }
                _tableDataState.value = DataState.Success(
                    latest.copy(rows = latest.rows + moreRows, isLoadingMore = false)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                val latest = (_tableDataState.value as? DataState.Success)?.data
                if (latest != null) {
                    _tableDataState.value = DataState.Success(latest.copy(isLoadingMore = false))
                }
                postError(e)
            }
        }
    }

    fun insertRow(values: List<SqlValue>) {
        mutate { repository, table -> repository.insertRow(table, values) }
    }

    fun updateRow(row: SqlRow, values: List<SqlValue>) {
        mutate { repository, table -> repository.updateRow(table, row, values) }
    }

    fun deleteRow(row: SqlRow) {
        mutate { repository, table -> repository.deleteRow(table, row) }
    }

    private fun mutate(block: (DatabaseRepository, SqlTable) -> Unit) {
        val table = (_tableDataState.value as? DataState.Success)?.data?.table ?: return
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            try {
                withContext(databaseDispatcher) { block(repository, table) }
                markChanged()
                selectTableInternal(table.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                postError(e)
            }
        }
    }

    private val _statementState =
        MutableStateFlow<ActionState<String, SqlStatementResult>>(ActionState.Ready())
    val statementState = _statementState.asStateFlow()

    fun executeStatement(sql: String) {
        if (!_statementState.value.isReady) {
            return
        }
        _statementState.value = ActionState.Running(sql)
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            try {
                val result = withContext(databaseDispatcher) { repository.execute(sql) }
                currentCoroutineContext().ensureActive()
                if (result is SqlStatementResult.Update) {
                    markChanged()
                }
                // The statement may have created or dropped tables, or changed the current one.
                refreshTables()
                _statementState.value = ActionState.Success(sql, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _statementState.value = ActionState.Error(sql, e)
            }
        }
    }

    fun finishExecutingStatement() {
        if (_statementState.value.isFinished) {
            _statementState.value = ActionState.Ready()
        }
    }

    /**
     * Counts the rows of [name] on its own, for the add-column dialog: it has to know whether
     * existing rows would need a default filled in, and the table may not be the one on screen.
     */
    fun getRowCount(name: String, onResult: (Long) -> Unit) {
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            val rowCount = try {
                withContext(databaseDispatcher) { repository.countRows(name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // Erring towards asking for a default is the safe way to be wrong here.
                1L
            }
            onResult(rowCount)
        }
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value == query) {
            return
        }
        _searchQuery.value = query
        val name = (_tableDataState.value?.data)?.table?.name ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            selectTableInternal(name)
        }
    }

    fun createTable(name: String, columns: List<NewColumn>, withoutRowId: Boolean) {
        runDdl(name) { it.createTable(name, columns, withoutRowId) }
    }

    fun addColumn(tableName: String, column: NewColumn) {
        runDdl(tableName) { it.addColumn(tableName, column) }
    }

    fun renameTable(tableName: String, newName: String) {
        runDdl(newName) { it.renameTable(tableName, newName) }
    }

    fun dropTable(tableName: String) {
        val table = _tables.value.firstOrNull { it.name == tableName } ?: return
        // No preferred table: if this is the one being shown, it is about to stop existing.
        runDdl { it.drop(table.name, table.isView) }
    }

    fun truncateTable(tableName: String) {
        runDdl(tableName) { it.truncateTable(tableName) }
    }

    /** Runs a schema change, then reloads the table list because it may have changed shape. */
    private fun runDdl(preferredTable: String? = null, block: (DatabaseRepository) -> Unit) {
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            try {
                withContext(databaseDispatcher) { block(repository) }
                markChanged()
                _searchQuery.value = ""
                refreshTables(preferredTable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                postError(e)
            }
        }
    }

    private val _ioState = MutableStateFlow<ActionState<Unit, IoResult>>(ActionState.Ready())
    val ioState = _ioState.asStateFlow()

    fun finishIo() {
        if (_ioState.value.isFinished) {
            _ioState.value = ActionState.Ready()
        }
    }

    fun exportTableCsv(path: Path) {
        val table = currentTable ?: return
        runIo { repository ->
            var exportedRows = 0L
            path.newOutputStream().use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    val csvWriter = CsvWriter(writer)
                    csvWriter.writeRow(table.columns.map { it.name })
                    repository.forEachRow(table) {
                        csvWriter.writeValues(it.values)
                        exportedRows++
                    }
                }
            }
            IoResult.TableExported(exportedRows)
        }
    }

    fun exportTableJson(path: Path) {
        val table = currentTable ?: return
        runIo { repository ->
            var exportedRows = 0L
            path.newOutputStream().use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    val jsonWriter = JsonRowWriter(writer, table.columns.map { it.name })
                    jsonWriter.begin()
                    repository.forEachRow(table) {
                        jsonWriter.writeValues(it.values)
                        exportedRows++
                    }
                    jsonWriter.end()
                }
            }
            IoResult.TableExported(exportedRows)
        }
    }

    fun exportDatabaseSql(path: Path) {
        runIo { repository ->
            path.newOutputStream().use { outputStream ->
                outputStream.bufferedWriter().use { repository.dumpTo(it) }
            }
            IoResult.DatabaseExported
        }
    }

    fun importTableCsv(path: Path) {
        val table = currentTable ?: return
        runIo { repository ->
            val csvRows = parseCsv(path.readImportText())
            require(csvRows.isNotEmpty()) { "The file has no rows" }
            // Columns are matched by header name, so a file holding extra or reordered columns
            // still imports, and one that shares no column at all is refused rather than guessed at.
            val mappings = csvRows.first().mapIndexedNotNull { index, field ->
                table.columns.firstOrNull { it.name.equals(field.text, ignoreCase = true) }
                    ?.let { index to it }
            }
            require(mappings.isNotEmpty()) { "No column in this file matches ${table.name}" }
            val values = csvRows.asSequence()
                .drop(1)
                .filterNot { it.size == 1 && !it[0].isQuoted && it[0].text.isEmpty() }
                .map { fields ->
                    mappings.map { (index, column) ->
                        fields.getOrNull(index)?.toSqlValue(column) ?: SqlValue.Null
                    }
                }
            IoResult.RowsImported(
                repository.insertRows(table.name, mappings.map { it.second.name }, values)
            )
        }
    }

    fun importTableJson(path: Path) {
        val table = currentTable ?: return
        runIo { repository ->
            val jsonRows = parseJsonRows(path.readImportText())
            val mappings = jsonRows.columnNames.mapNotNull { name ->
                table.columns.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { name to it }
            }
            require(mappings.isNotEmpty()) { "No column in this file matches ${table.name}" }
            val values = jsonRows.rows.asSequence().map { row ->
                mappings.map { (name, _) -> row[name] ?: SqlValue.Null }
            }
            IoResult.RowsImported(
                repository.insertRows(table.name, mappings.map { it.second.name }, values)
            )
        }
    }

    /** Copies the database file itself to [path], flushing anything still in the log first. */
    fun exportDatabaseFile(path: Path, context: Context) {
        val database = (_databaseState.value as? DataState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                withContext(databaseDispatcher) { database.repository.checkpoint() }
                FileJobService.save(Paths.get(database.file.path), path, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                postError(e)
            }
        }
    }

    private fun runIo(block: (DatabaseRepository) -> IoResult) {
        if (!_ioState.value.isReady) {
            return
        }
        _ioState.value = ActionState.Running(Unit)
        viewModelScope.launch {
            val repository = repositoryOrNull ?: return@launch
            try {
                val result = withContext(databaseDispatcher) { block(repository) }
                currentCoroutineContext().ensureActive()
                if (result !is IoResult.TableExported && result !is IoResult.DatabaseExported) {
                    markChanged()
                    // An import may have added rows, tables, or both.
                    refreshTables()
                }
                _ioState.value = ActionState.Success(Unit, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _ioState.value = ActionState.Error(Unit, e)
            }
        }
    }

    private val currentTable: SqlTable?
        get() = (_tableDataState.value as? DataState.Success)?.data?.table

    /**
     * Copies the edited cache file back over the original. Only meaningful when the database didn't
     * live on the local file system to begin with.
     */
    fun writeBack(context: Context) {
        val database = (_databaseState.value as? DataState.Success)?.data ?: return
        if (!database.isCacheCopy || !_writeBackState.value.isReady) {
            return
        }
        _writeBackState.value = ActionState.Running(path)
        viewModelScope.launch {
            try {
                // Force everything still buffered out to the cache file before copying it.
                withContext(databaseDispatcher) { database.repository.checkpoint() }
                FileJobService.save(Paths.get(database.file.path), path, context)
                _hasUnsavedChanges.value = false
                _writeBackState.value = ActionState.Success(path, Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _writeBackState.value = ActionState.Error(path, e)
            }
        }
    }

    fun finishWritingBack() {
        if (_writeBackState.value.isFinished) {
            _writeBackState.value = ActionState.Ready()
        }
    }

    fun consumeError() {
        _errorEvents.value = null
    }

    private fun postError(throwable: Throwable) {
        _errorEvents.value = throwable
    }

    private fun markChanged() {
        if ((_databaseState.value as? DataState.Success)?.data?.isCacheCopy == true) {
            _hasUnsavedChanges.value = true
        }
    }

    private val repositoryOrNull: DatabaseRepository?
        get() = (_databaseState.value as? DataState.Success)?.data?.repository

    /** True when the file could only be opened for reading, so edits have to be refused. */
    val isDatabaseReadOnly: Boolean
        get() = repositoryOrNull?.isReadOnly ?: false

    override fun onCleared() {
        super.onCleared()

        val database = (_databaseState.value as? DataState.Success)?.data
        // Not on the database dispatcher: onCleared() must not outlive the ViewModel scope, and
        // closing is quick.
        try {
            database?.repository?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (database?.isCacheCopy == true) {
            database.file.delete()
        }
    }

    /** What a finished import or export has to report back. */
    sealed interface IoResult {
        class TableExported(val rows: Long) : IoResult

        class RowsImported(val rows: Int) : IoResult

        data object DatabaseExported : IoResult
    }

    class OpenedDatabase(
        val repository: DatabaseRepository,
        val file: File,
        /** True when [file] is a copy in our cache rather than the file the user picked. */
        val isCacheCopy: Boolean
    )

    data class TableData(
        val table: SqlTable,
        val rows: List<SqlRow>,
        val totalRows: Long,
        val isLoadingMore: Boolean = false,
        /** The search these rows were listed for, empty when they are the whole table. */
        val query: String = ""
    ) {
        val hasMore: Boolean
            get() = rows.size < totalRows
    }

    companion object {
        const val PAGE_SIZE = 100

        private const val CACHE_SUBDIRECTORY = "database_editor"

        /** Long enough that typing a word doesn't run a query per keystroke. */
        private const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}

/**
 * Reads a file being imported. Whole rather than streamed, because both parsers need the entire
 * document, and because an import large enough to matter belongs in the SQL console instead.
 */
private fun Path.readImportText(): String =
    newInputStream().use { it.reader().readText() }
