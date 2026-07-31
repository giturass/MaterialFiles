/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

/**
 * Statements that hand back rows. Everything else is compiled and executed instead of queried.
 *
 * `PRAGMA` is included because the reading form of it returns rows, and the writing form simply
 * returns none.
 */
private val QUERY_KEYWORDS = setOf("SELECT", "WITH", "PRAGMA", "EXPLAIN", "VALUES")

/** Statements whose changed-row count is meaningful to report back to the user. */
private val ROW_CHANGING_KEYWORDS = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")

/**
 * Splits SQL into individual statements on top-level semicolons, leaving semicolons inside string
 * literals, quoted identifiers and comments alone. Blank statements are dropped.
 */
fun String.splitSqlStatements(): List<String> {
    val statements = mutableListOf<String>()
    val statement = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '-' && index + 1 < length && this[index + 1] == '-' -> {
                val end = indexOf('\n', index).takeIf { it != -1 } ?: length
                statement.append(this, index, end)
                index = end
            }
            char == '/' && index + 1 < length && this[index + 1] == '*' -> {
                val end = indexOf("*/", index + 2).let { if (it != -1) it + 2 else length }
                statement.append(this, index, end)
                index = end
            }
            char == '\'' || char == '"' || char == '`' -> {
                val end = findClosingQuote(index, char)
                statement.append(this, index, end)
                index = end
            }
            char == '[' -> {
                val end = (indexOf(']', index + 1).takeIf { it != -1 }?.plus(1)) ?: length
                statement.append(this, index, end)
                index = end
            }
            char == ';' -> {
                statements.addIfNotBlank(statement)
                index++
            }
            else -> {
                statement.append(char)
                index++
            }
        }
    }
    statements.addIfNotBlank(statement)
    return statements
}

/**
 * Returns the index just past the literal starting at [start]. SQL escapes a quote by doubling it,
 * so a doubled quote continues the literal.
 */
private fun String.findClosingQuote(start: Int, quote: Char): Int {
    var index = start + 1
    while (index < length) {
        if (this[index] == quote) {
            if (index + 1 < length && this[index + 1] == quote) {
                index += 2
                continue
            }
            return index + 1
        }
        index++
    }
    return length
}

private fun MutableList<String>.addIfNotBlank(builder: StringBuilder) {
    val statement = builder.toString().trim()
    builder.setLength(0)
    if (statement.isNotEmpty()) {
        add(statement)
    }
}

fun String.isQueryStatement(): Boolean = firstSqlKeyword() in QUERY_KEYWORDS

fun String.isRowChangingStatement(): Boolean = firstSqlKeyword() in ROW_CHANGING_KEYWORDS

/** The leading keyword, skipping whitespace and comments, uppercased. */
private fun String.firstSqlKeyword(): String {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isWhitespace() -> index++
            char == '-' && index + 1 < length && this[index + 1] == '-' ->
                index = indexOf('\n', index).takeIf { it != -1 }?.plus(1) ?: length
            char == '/' && index + 1 < length && this[index + 1] == '*' ->
                index = indexOf("*/", index + 2).let { if (it != -1) it + 2 else length }
            else -> {
                val end = index + (substring(index).indexOfFirst { !it.isLetter() }
                    .takeIf { it != -1 } ?: (length - index))
                return substring(index, end).uppercase()
            }
        }
    }
    return ""
}
