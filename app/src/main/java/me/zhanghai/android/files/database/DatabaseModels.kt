/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A single SQLite value, carrying its storage class so that it survives a round trip. */
sealed class SqlValue : Parcelable {
    /** The object to bind for this value in a parameterized statement. */
    abstract val bindArgument: Any?

    @Parcelize
    data object Null : SqlValue() {
        override val bindArgument: Any?
            get() = null
    }

    @Parcelize
    data class Integer(val value: Long) : SqlValue() {
        override val bindArgument: Any
            get() = value
    }

    @Parcelize
    data class Real(val value: Double) : SqlValue() {
        override val bindArgument: Any
            get() = value
    }

    @Parcelize
    data class Text(val value: String) : SqlValue() {
        override val bindArgument: Any
            get() = value
    }

    @Parcelize
    class Blob(val value: ByteArray) : SqlValue() {
        override val bindArgument: Any
            get() = value

        override fun equals(other: Any?): Boolean =
            this === other || (other is Blob && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }
}

/** One column of a table or of a query result. */
@Parcelize
data class SqlColumn(
    val name: String,
    /** The declared type, empty for expression columns in a query result. */
    val declaredType: String,
    val isNotNull: Boolean,
    val defaultValue: String?,
    val isPrimaryKey: Boolean
) : Parcelable

/** A column to be created, either as part of a new table or added to an existing one. */
@Parcelize
data class NewColumn(
    val name: String,
    /** The declared type, blank for a column without one. */
    val type: String,
    val isNotNull: Boolean,
    val isPrimaryKey: Boolean,
    /** Raw SQL for the default, such as `0`, `'none'` or `CURRENT_TIMESTAMP`. */
    val defaultValue: String?
) : Parcelable

/**
 * A row, together with whatever lets us address it again for updates and deletes: [rowId] when the
 * table has one, otherwise the values themselves are matched.
 */
@Parcelize
data class SqlRow(val rowId: Long?, val values: List<SqlValue>) : Parcelable

/**
 * A table or view as the table list knows it: enough to name it and to tell which of the schema
 * operations apply, without reading its columns.
 */
data class SqlTableRef(val name: String, val isView: Boolean)

/** A table or view in the database. */
@Parcelize
data class SqlTable(
    val name: String,
    val isView: Boolean,
    val columns: List<SqlColumn>,
    /**
     * Whether rows can be addressed by `rowid`. False for views and `WITHOUT ROWID` tables, which
     * fall back to matching every column and therefore cannot update rows that aren't unique.
     */
    val hasRowId: Boolean
) : Parcelable {
    val isEditable: Boolean
        get() = !isView && columns.isNotEmpty()
}

/** The rows returned by a query, plus the columns they are laid out by. */
data class SqlQueryResult(val columns: List<String>, val rows: List<SqlRow>)

/** What running a statement produced: rows for a query, an affected count for everything else. */
sealed interface SqlStatementResult {
    data class Query(val result: SqlQueryResult) : SqlStatementResult

    data class Update(val affectedRows: Int) : SqlStatementResult
}
