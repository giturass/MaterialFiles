/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

/** Quotes an identifier so that reserved words and punctuation in user table names are safe. */
fun String.quoteSqlIdentifier(): String = "\"${replace("\"", "\"\"")}\""

/** Quotes a string as a SQL literal, for the places where a value cannot be bound. */
fun String.quoteSqlString(): String = "'${replace("'", "''")}'"

/**
 * What SQLite accepts as a column type: a name, optionally followed by one or two numbers in
 * parentheses, as in `VARCHAR(255)` or `DECIMAL(10, 5)`. Names may hold spaces, because several of
 * the ones SQLite documents do - `UNSIGNED BIG INT` among them.
 *
 * A type name cannot be quoted the way an identifier can, since SQLite takes any name at all and
 * works out an affinity for it, so it goes into the statement as typed. Checking it against this is
 * what keeps something that isn't a type name from changing the shape of the statement it lands in.
 */
private val COLUMN_TYPE_REGEX =
    Regex("""[A-Za-z_][A-Za-z0-9_ ]*(\(\s*-?\d+\s*(,\s*-?\d+\s*)?\))?""")

/** Whether this is a column type SQLite would accept. Blank counts: a type is optional. */
fun String.isSqlColumnType(): Boolean =
    trim().let { it.isEmpty() || COLUMN_TYPE_REGEX.matches(it) }

/**
 * Builds the `CREATE TABLE` statement for [columns].
 *
 * A single primary key column is declared inline, because that is what makes an `INTEGER PRIMARY
 * KEY` an alias for the rowid; several of them have to become a table constraint instead.
 */
fun buildCreateTableSql(
    name: String,
    columns: List<NewColumn>,
    withoutRowId: Boolean
): String {
    require(columns.isNotEmpty()) { "A table needs at least one column" }
    val primaryKeyColumns = columns.filter { it.isPrimaryKey }
    val isPrimaryKeyInline = primaryKeyColumns.size == 1
    val definitions = columns.mapTo(mutableListOf()) {
        it.toColumnDefinition(isPrimaryKeyInline && it.isPrimaryKey)
    }
    if (primaryKeyColumns.size > 1) {
        definitions += primaryKeyColumns.joinToString(
            separator = ", ", prefix = "PRIMARY KEY (", postfix = ")"
        ) { it.name.quoteSqlIdentifier() }
    }
    return buildString {
        append("CREATE TABLE ")
        append(name.quoteSqlIdentifier())
        append(" (")
        append(definitions.joinToString(", "))
        append(")")
        // WITHOUT ROWID is only legal with a primary key, and rows of such a table can only be
        // addressed by their values, so it is refused rather than silently dropped elsewhere.
        if (withoutRowId && primaryKeyColumns.isNotEmpty()) {
            append(" WITHOUT ROWID")
        }
    }
}

fun buildAddColumnSql(table: String, column: NewColumn): String =
    "ALTER TABLE ${table.quoteSqlIdentifier()} ADD COLUMN ${column.toColumnDefinition(false)}"

fun buildRenameTableSql(name: String, newName: String): String =
    "ALTER TABLE ${name.quoteSqlIdentifier()} RENAME TO ${newName.quoteSqlIdentifier()}"

fun buildDropSql(name: String, isView: Boolean): String =
    if (isView) {
        "DROP VIEW ${name.quoteSqlIdentifier()}"
    } else {
        "DROP TABLE ${name.quoteSqlIdentifier()}"
    }

/** SQLite has no `TRUNCATE`; deleting every row is the equivalent, and is optimised as one. */
fun buildTruncateSql(name: String): String = "DELETE FROM ${name.quoteSqlIdentifier()}"

private fun NewColumn.toColumnDefinition(withPrimaryKey: Boolean): String =
    buildString {
        append(name.quoteSqlIdentifier())
        if (type.isNotBlank()) {
            append(' ')
            append(type.trim())
        }
        if (withPrimaryKey) {
            append(" PRIMARY KEY")
        }
        if (isNotNull) {
            append(" NOT NULL")
        }
        // Written through as typed: SQLite wants a constant here, and quoting it ourselves would
        // stop CURRENT_TIMESTAMP and the like from working.
        defaultValue?.takeIf { it.isNotBlank() }?.let {
            append(" DEFAULT ")
            append(it.trim())
        }
    }
