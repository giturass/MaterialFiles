/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteStatement
import java.io.File

/**
 * All SQLite access for the database editor. Every method blocks, so callers are expected to be on
 * a background dispatcher.
 *
 * Backed by requery's SQLite build rather than the framework one so that databases relying on newer
 * features open the same way on every supported OS version.
 */
class DatabaseRepository private constructor(private val database: SQLiteDatabase) : AutoCloseable {
    val isReadOnly: Boolean
        get() = database.isReadOnly

    override fun close() {
        database.close()
    }

    /**
     * Flushes a write-ahead log back into the main database file, so that copying the file alone
     * carries every committed change. A no-op for the journal modes that don't use a separate log.
     */
    fun checkpoint() {
        try {
            database.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
    }

    /** The tables and views the user can browse, internal `sqlite_*` bookkeeping excluded. */
    fun listTables(): List<SqlTableRef> =
        database.rawQuery(
            "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view')" +
                " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name COLLATE NOCASE",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(SqlTableRef(cursor.getString(0), cursor.getString(1) == "view"))
                }
            }
        }

    /** Reads the schema of [name], which must be a table or view that exists. */
    fun getTable(name: String): SqlTable {
        val isView = database.rawQuery(
            "SELECT type FROM sqlite_master WHERE name = ?", arrayOf<Any?>(name)
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "view" }
        val columns = database.rawQuery("PRAGMA table_info(${name.quoteSqlIdentifier()})", emptyArray())
            .use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
                val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SqlColumn(
                                cursor.getString(nameIndex),
                                cursor.getString(typeIndex).orEmpty(),
                                cursor.getInt(notNullIndex) != 0,
                                if (cursor.isNull(defaultValueIndex)) {
                                    null
                                } else {
                                    cursor.getString(defaultValueIndex)
                                },
                                cursor.getInt(primaryKeyIndex) != 0
                            )
                        )
                    }
                }
            }
        return SqlTable(name, isView, columns, !isView && hasRowId(name))
    }

    /** `WITHOUT ROWID` tables have no `rowid` to select, which is exactly what we probe for. */
    private fun hasRowId(name: String): Boolean =
        try {
            database.rawQuery("SELECT rowid FROM ${name.quoteSqlIdentifier()} LIMIT 0", emptyArray())
                .use { true }
        } catch (e: RuntimeException) {
            false
        }

    /** How many rows [table] holds, or how many of them match [query] when one is given. */
    fun countRows(table: SqlTable, query: String? = null): Long {
        val (whereClause, arguments) = table.filterClause(query)
        val sql = "SELECT COUNT(*) FROM ${table.name.quoteSqlIdentifier()}$whereClause"
        if (arguments.isEmpty()) {
            return database.compileStatementCompat(sql).use { it.simpleQueryForLong() }
        }
        return database.rawQuery(sql, arguments.toBindArguments()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** How many rows [name] holds, for callers that only have the name of a table. */
    fun countRows(name: String): Long =
        database.compileStatementCompat("SELECT COUNT(*) FROM ${name.quoteSqlIdentifier()}")
            .use { it.simpleQueryForLong() }

    /**
     * Reads one page of [table], keeping each row's `rowid` when the table has one. A [query]
     * restricts the page to rows holding it in any column.
     *
     * A table with a `rowid` is paged by it rather than by `OFFSET`, because `OFFSET` makes SQLite
     * count its way past every skipped row and so gets slower the further down the table the user
     * scrolls. [afterRowId] is the last row of the previous page; null asks for the first one.
     */
    fun queryPage(
        table: SqlTable,
        offset: Long,
        limit: Int,
        query: String? = null,
        afterRowId: Long? = null
    ): List<SqlRow> {
        val quotedName = table.name.quoteSqlIdentifier()
        val (whereClause, whereArguments) = table.filterClause(query)
        val arguments = mutableListOf<Any?>()
        val sql = if (table.hasRowId) {
            buildString {
                append("SELECT rowid AS ").append(ROW_ID_COLUMN).append(", * FROM ")
                append(quotedName)
                append(whereClause)
                whereArguments.mapTo(arguments) { it.bindArgument }
                if (afterRowId != null) {
                    append(if (whereClause.isEmpty()) " WHERE" else " AND")
                    append(" rowid > ?")
                    arguments += afterRowId
                }
                append(" ORDER BY rowid LIMIT ?")
                arguments += limit.toLong()
            }
        } else {
            whereArguments.mapTo(arguments) { it.bindArgument }
            arguments += limit.toLong()
            arguments += offset
            // OFFSET only means something against a defined order: without one SQLite may hand back
            // the rows of two queries in two different orders, and paging would then repeat some
            // rows and skip others.
            "SELECT * FROM $quotedName$whereClause${table.pageOrderClause()} LIMIT ? OFFSET ?"
        }
        return database.rawQuery(sql, arguments.toTypedArray()).use { cursor ->
            val valueOffset = if (table.hasRowId) 1 else 0
            buildList {
                while (cursor.moveToNext()) {
                    val rowId = if (table.hasRowId) cursor.getLong(0) else null
                    val values = (valueOffset until cursor.columnCount).map { cursor.getSqlValue(it) }
                    add(SqlRow(rowId, values))
                }
            }
        }
    }

    /**
     * Streams every row of [table] in storage order. Used by the exporters, so that a table larger
     * than memory can still be written out.
     */
    fun forEachRow(table: SqlTable, block: (SqlRow) -> Unit) {
        database.rawQuery("SELECT * FROM ${table.name.quoteSqlIdentifier()}", emptyArray())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    block(
                        SqlRow(null, (0 until cursor.columnCount).map { cursor.getSqlValue(it) })
                    )
                }
            }
    }

    fun insertRow(table: SqlTable, values: List<SqlValue>) {
        require(values.size == table.columns.size)
        val columnNames = table.columns.joinToString(", ") { it.name.quoteSqlIdentifier() }
        val placeholders = values.joinToString(", ") { "?" }
        val sql =
            "INSERT INTO ${table.name.quoteSqlIdentifier()} ($columnNames) VALUES ($placeholders)"
        database.compileStatementCompat(sql).use { statement ->
            statement.bindValues(values)
            statement.executeInsert()
        }
    }

    /**
     * Rewrites [row] with [values]. Rows of a `WITHOUT ROWID` table are addressed by matching every
     * column, so an ambiguous match is rejected rather than silently changing several rows.
     */
    @Throws(AmbiguousRowException::class)
    fun updateRow(table: SqlTable, row: SqlRow, values: List<SqlValue>) {
        require(values.size == table.columns.size)
        val assignments = table.columns.joinToString(", ") { "${it.name.quoteSqlIdentifier()} = ?" }
        val (whereClause, whereArguments) = table.identityClause(row)
        val sql = "UPDATE ${table.name.quoteSqlIdentifier()} SET $assignments WHERE $whereClause"
        executeExpectingSingleRow(table, sql, values + whereArguments)
    }

    @Throws(AmbiguousRowException::class)
    fun deleteRow(table: SqlTable, row: SqlRow) {
        val (whereClause, whereArguments) = table.identityClause(row)
        val sql = "DELETE FROM ${table.name.quoteSqlIdentifier()} WHERE $whereClause"
        executeExpectingSingleRow(table, sql, whereArguments)
    }

    private fun executeExpectingSingleRow(
        table: SqlTable,
        sql: String,
        values: List<SqlValue>
    ) {
        database.beginTransaction()
        try {
            val affectedRows = database.compileStatementCompat(sql).use { statement ->
                statement.bindValues(values)
                statement.executeUpdateDelete()
            }
            if (!table.hasRowId && affectedRows != 1) {
                // Roll back by returning without marking the transaction successful.
                throw AmbiguousRowException(affectedRows)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Runs [sql], which may contain several statements. Returns the result of the last one: its rows
     * if it was a query, otherwise how many rows it changed.
     *
     * A script of several statements runs as one transaction, so that a failure halfway through
     * leaves the database as it was rather than half changed. A statement that manages transactions
     * itself takes the script out of ours, since SQLite has no nested transactions.
     */
    fun execute(sql: String): SqlStatementResult {
        val statements = sql.splitSqlStatements()
        require(statements.isNotEmpty()) { "No statement to run" }
        if (statements.size == 1) {
            return executeSingle(statements.single())
        }
        val isTransactional = statements.none { it.isTransactionStatement() }
        if (isTransactional) {
            database.beginTransaction()
        }
        try {
            var result: SqlStatementResult = SqlStatementResult.Update(0)
            for (statement in statements) {
                result = executeSingle(statement)
            }
            if (isTransactional) {
                database.setTransactionSuccessful()
            }
            return result
        } finally {
            if (isTransactional) {
                database.endTransaction()
            }
        }
    }

    private fun executeSingle(sql: String): SqlStatementResult =
        if (sql.isQueryStatement()) {
            database.rawQuery(sql, emptyArray()).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = buildList {
                    while (size < MAX_QUERY_ROWS && cursor.moveToNext()) {
                        add(SqlRow(null, (0 until cursor.columnCount).map { cursor.getSqlValue(it) }))
                    }
                }
                SqlStatementResult.Query(SqlQueryResult(columns, rows))
            }
        } else {
            database.compileStatementCompat(sql).use { statement ->
                SqlStatementResult.Update(
                    if (sql.isRowChangingStatement()) {
                        statement.executeUpdateDelete()
                    } else {
                        statement.execute()
                        0
                    }
                )
            }
        }

    fun createTable(name: String, columns: List<NewColumn>, withoutRowId: Boolean) {
        database.execSQL(buildCreateTableSql(name, columns, withoutRowId))
    }

    fun addColumn(table: String, column: NewColumn) {
        database.execSQL(buildAddColumnSql(table, column))
    }

    fun renameTable(name: String, newName: String) {
        database.execSQL(buildRenameTableSql(name, newName))
    }

    fun drop(name: String, isView: Boolean) {
        database.execSQL(buildDropSql(name, isView))
    }

    /**
     * Empties [name] and forgets its recorded `AUTOINCREMENT` sequence, so that ids start over the
     * way they would in a table that had never been written to.
     */
    fun truncateTable(name: String): Int {
        database.beginTransaction()
        try {
            val deletedRows = database.compileStatementCompat(buildTruncateSql(name))
                .use { it.executeUpdateDelete() }
            if (hasTable(SEQUENCE_TABLE)) {
                database.compileStatementCompat(
                    "DELETE FROM $SEQUENCE_TABLE WHERE name = ${name.quoteSqlString()}"
                ).use { it.executeUpdateDelete() }
            }
            database.setTransactionSuccessful()
            return deletedRows
        } finally {
            database.endTransaction()
        }
    }

    fun hasTable(name: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ? LIMIT 1",
            arrayOf<Any?>(name)
        ).use { it.moveToFirst() }

    /** Inserts [rows] into [table] under [columnNames], as one transaction. Used by the importers. */
    fun insertRows(
        table: String,
        columnNames: List<String>,
        rows: Sequence<List<SqlValue>>
    ): Int {
        require(columnNames.isNotEmpty())
        val sql = buildString {
            append("INSERT INTO ")
            append(table.quoteSqlIdentifier())
            append(columnNames.joinToString(", ", " (", ")") { it.quoteSqlIdentifier() })
            append(columnNames.joinToString(", ", " VALUES (", ")") { "?" })
        }
        var insertedRows = 0
        database.beginTransaction()
        try {
            database.compileStatementCompat(sql).use { statement ->
                for (values in rows) {
                    require(values.size == columnNames.size)
                    statement.clearBindings()
                    statement.bindValues(values)
                    statement.executeInsert()
                    insertedRows++
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return insertedRows
    }

    /**
     * Writes the whole database out as a SQL script: the statements that recreate every object, and
     * the inserts that refill every table.
     *
     * Tables and their rows come first and indexes and triggers last, so that replaying the script
     * neither indexes rows twice nor fires a trigger on the data being restored.
     */
    fun dumpTo(out: Appendable) {
        out.append("PRAGMA foreign_keys = off;\n")
        out.append("BEGIN TRANSACTION;\n")
        val objects = database.rawQuery(
            "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL" +
                " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY CASE type" +
                " WHEN 'table' THEN 0 WHEN 'view' THEN 1 WHEN 'index' THEN 2 ELSE 3 END, name",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        for ((type, name, sql) in objects) {
            out.append(sql).append(";\n")
            if (type == "table") {
                dumpRows(name, out)
            }
        }
        out.append("COMMIT;\n")
    }

    private fun dumpRows(table: String, out: Appendable) {
        val quotedName = table.quoteSqlIdentifier()
        database.rawQuery("SELECT * FROM $quotedName", emptyArray()).use { cursor ->
            val columnNames = cursor.columnNames
                .joinToString(", ", " (", ")") { it.quoteSqlIdentifier() }
            while (cursor.moveToNext()) {
                out.append("INSERT INTO ").append(quotedName).append(columnNames).append(" VALUES (")
                for (index in 0 until cursor.columnCount) {
                    if (index > 0) {
                        out.append(", ")
                    }
                    out.append(cursor.getSqlValue(index).toSqlLiteral())
                }
                out.append(");\n")
            }
        }
    }

    /** Thrown when a row of a `WITHOUT ROWID` table cannot be addressed unambiguously. */
    class AmbiguousRowException(val matchedRows: Int) : RuntimeException(
        "Expected to match exactly one row, but matched $matchedRows"
    )

    companion object {
        /** Alias for the selected `rowid`, chosen not to collide with a real column name. */
        private const val ROW_ID_COLUMN = "__material_files_rowid"

        /** Where SQLite records the high-water mark of every `AUTOINCREMENT` column. */
        private const val SEQUENCE_TABLE = "sqlite_sequence"

        /** Guards against a `SELECT *` on a huge table exhausting memory. */
        const val MAX_QUERY_ROWS = 1000

        fun open(file: File, readOnly: Boolean): DatabaseRepository {
            val flags = when {
                readOnly -> SQLiteDatabase.OPEN_READONLY
                // Opening a WAL database without this would rewrite it into another journal mode.
                file.isWalSqliteDatabase() ->
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                else -> SQLiteDatabase.CREATE_IF_NECESSARY
            }
            return DatabaseRepository(SQLiteDatabase.openDatabase(file.path, null, flags))
        }

        /**
         * Opens [file] for writing, falling back to read-only when it cannot be written to. A
         * database on a read-only mount, or one whose directory we may not create a journal in,
         * is still worth showing; only the editing has to be refused.
         */
        fun openWritableOrReadOnly(file: File): DatabaseRepository =
            try {
                open(file, readOnly = false)
            } catch (e: RuntimeException) {
                e.printStackTrace()
                open(file, readOnly = true)
            }
    }
}

private fun SQLiteDatabase.compileStatementCompat(sql: String): SQLiteStatement =
    compileStatement(sql)

private fun SQLiteStatement.bindValues(values: List<SqlValue>) {
    values.forEachIndexed { index, value ->
        val argumentIndex = index + 1
        when (value) {
            is SqlValue.Null -> bindNull(argumentIndex)
            is SqlValue.Integer -> bindLong(argumentIndex, value.value)
            is SqlValue.Real -> bindDouble(argumentIndex, value.value)
            is SqlValue.Text -> bindString(argumentIndex, value.value)
            is SqlValue.Blob -> bindBlob(argumentIndex, value.value)
        }
    }
}

private fun Cursor.getSqlValue(index: Int): SqlValue =
    when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> SqlValue.Null
        Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(getDouble(index))
        Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(getBlob(index))
        else -> SqlValue.Text(getString(index).orEmpty())
    }

/**
 * A stable order for the tables that have no `rowid` to page by, i.e. `WITHOUT ROWID` tables and
 * views.
 *
 * The primary key is what a `WITHOUT ROWID` table is physically stored in, so ordering by it is
 * free. A view has no key to lean on, and every one of its columns has to go into the order for it
 * to be unambiguous - which costs a sort, and is the price of paging a view at all.
 */
private fun SqlTable.pageOrderClause(): String {
    val orderColumns = columns.filter { it.isPrimaryKey }.ifEmpty { columns }
    if (orderColumns.isEmpty()) {
        return ""
    }
    return orderColumns.joinToString(", ", " ORDER BY ") { it.name.quoteSqlIdentifier() }
}

/**
 * Builds the `WHERE` clause addressing [row]: by `rowid` when there is one, otherwise by matching
 * every column value.
 */
private fun SqlTable.identityClause(row: SqlRow): Pair<String, List<SqlValue>> {
    val rowId = row.rowId
    if (hasRowId && rowId != null) {
        return "rowid = ?" to listOf(SqlValue.Integer(rowId))
    }
    check(row.values.size == columns.size)
    val conditions = mutableListOf<String>()
    val arguments = mutableListOf<SqlValue>()
    columns.forEachIndexed { index, column ->
        val value = row.values[index]
        val quotedName = column.name.quoteSqlIdentifier()
        if (value is SqlValue.Null) {
            conditions += "$quotedName IS NULL"
        } else {
            conditions += "$quotedName = ?"
            arguments += value
        }
    }
    return conditions.joinToString(" AND ") to arguments
}

/**
 * Builds the `WHERE` clause restricting a listing to rows holding [query] in any column, along with
 * the values to bind for it. Empty when there is nothing to search for.
 *
 * Every column is cast to text so that numbers and blobs can be searched the same way as strings.
 * `LIKE` is already case insensitive for ASCII in SQLite, which is what a search box is expected to
 * be.
 */
private fun SqlTable.filterClause(query: String?): Pair<String, List<SqlValue>> {
    if (query.isNullOrEmpty() || columns.isEmpty()) {
        return "" to emptyList()
    }
    val conditions = columns.joinToString(" OR ") {
        "CAST(${it.name.quoteSqlIdentifier()} AS TEXT) LIKE ? ESCAPE '\\'"
    }
    val pattern = SqlValue.Text("%${query.escapeLikeWildcards()}%")
    return " WHERE ($conditions)" to List(columns.size) { pattern }
}

/** Escapes what `LIKE` reads as wildcards, so that searching for them finds them literally. */
private fun String.escapeLikeWildcards(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun List<SqlValue>.toBindArguments(): Array<Any?> =
    Array(size) { this[it].bindArgument }
