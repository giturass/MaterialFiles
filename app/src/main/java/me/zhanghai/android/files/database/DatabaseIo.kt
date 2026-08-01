/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/**
 * The literal form of this value inside a statement, for the places a value cannot be bound: the
 * SQL script exporter.
 */
fun SqlValue.toSqlLiteral(): String =
    when (this) {
        is SqlValue.Null -> "NULL"
        is SqlValue.Integer -> value.toString()
        // SQLite has no way to write these, and stores them as NULL if asked to.
        is SqlValue.Real -> if (value.isFinite()) value.toString() else "NULL"
        is SqlValue.Text -> value.quoteSqlString()
        is SqlValue.Blob -> value.joinToString("", "X'", "'") { "%02x".format(it) }
    }

/**
 * Turns text into a value of the storage class the column asks for, following SQLite's type
 * affinity rules and falling back to text when the input doesn't fit.
 *
 * @see <a href="https://www.sqlite.org/datatype3.html#determination_of_column_affinity">Column
 *     Affinity</a>
 */
fun parseSqlValue(text: String, column: SqlColumn): SqlValue {
    val type = column.declaredType.uppercase()
    return when {
        type.contains("INT") -> text.toLongOrNull()?.let { SqlValue.Integer(it) }
            ?: text.toDoubleOrNull()?.let { SqlValue.Real(it) }
            ?: SqlValue.Text(text)
        type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT") ->
            SqlValue.Text(text)
        type.contains("BLOB") -> SqlValue.Text(text)
        type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") ->
            text.toDoubleOrNull()?.let { SqlValue.Real(it) } ?: SqlValue.Text(text)
        // NUMERIC affinity, and columns without a declared type in a query result.
        else -> text.toLongOrNull()?.let { SqlValue.Integer(it) }
            ?: text.toDoubleOrNull()?.let { SqlValue.Real(it) }
            ?: SqlValue.Text(text)
    }
}

/**
 * Writes rows as RFC 4180 CSV.
 *
 * Every value except NULL is quoted, and NULL is written as an empty unquoted field, which is what
 * lets the two be told apart again on the way back in. Blobs become Base64, and are read back as
 * whatever their column's affinity says, so CSV is the lossy format of the two and JSON is the one
 * that round trips exactly.
 */
class CsvWriter(private val out: Appendable) {
    fun writeRow(values: List<String?>) {
        values.forEachIndexed { index, value ->
            if (index > 0) {
                out.append(',')
            }
            value ?: return@forEachIndexed
            out.append('"').append(value.replace("\"", "\"\"")).append('"')
        }
        out.append("\r\n")
    }

    fun writeValues(values: List<SqlValue>) {
        writeRow(values.map { it.toCsvField() })
    }
}

private fun SqlValue.toCsvField(): String? =
    when (this) {
        is SqlValue.Null -> null
        is SqlValue.Integer -> value.toString()
        is SqlValue.Real -> value.toString()
        is SqlValue.Text -> value
        is SqlValue.Blob -> Base64.encodeToString(value, Base64.NO_WRAP)
    }

/** One parsed CSV field. Whether it was quoted is what distinguishes an empty string from NULL. */
class CsvField(val text: String, val isQuoted: Boolean)

/**
 * Parses RFC 4180 CSV, accepting either line ending and tolerating a final line without one. A
 * quote inside a quoted field is written doubled.
 *
 * Produced a row at a time off [reader], so that importing a file larger than memory still works.
 * The sequence may only be consumed once, and only while [reader] is open.
 */
fun parseCsvRows(reader: Reader): Sequence<List<CsvField>> = sequence {
    val input = PeekingReader(reader)
    var row = mutableListOf<CsvField>()
    val field = StringBuilder()
    var isQuoted = false
    var wasQuoted = false
    var hasField = false
    fun endField() {
        row += CsvField(field.toString(), wasQuoted)
        field.setLength(0)
        wasQuoted = false
        hasField = false
    }
    while (true) {
        val next = input.read()
        if (next == -1) {
            break
        }
        val char = next.toChar()
        when {
            isQuoted -> when {
                char == '"' && input.peek() == QUOTE_CODE -> {
                    field.append('"')
                    input.read()
                }
                char == '"' -> isQuoted = false
                else -> field.append(char)
            }
            char == '"' -> {
                isQuoted = true
                wasQuoted = true
                hasField = true
            }
            char == ',' -> endField()
            char == '\r' || char == '\n' -> {
                if (char == '\r' && input.peek() == NEWLINE_CODE) {
                    input.read()
                }
                // A trailing newline ends the last row rather than starting an empty one.
                endField()
                yield(row)
                row = mutableListOf()
            }
            else -> {
                field.append(char)
                hasField = true
            }
        }
    }
    if (hasField || field.isNotEmpty() || row.isNotEmpty()) {
        endField()
        yield(row)
    }
}

private const val QUOTE_CODE = '"'.code
private const val NEWLINE_CODE = '\n'.code

/** A [Reader] with the single character of lookahead that CRLF and doubled quotes need. */
private class PeekingReader(private val reader: Reader) {
    private var peeked = NOTHING

    fun read(): Int {
        val peeked = peeked
        if (peeked != NOTHING) {
            this.peeked = NOTHING
            return peeked
        }
        return reader.read()
    }

    fun peek(): Int {
        if (peeked == NOTHING) {
            peeked = reader.read()
        }
        return peeked
    }

    companion object {
        /** Distinct from -1, which is a real end of input. */
        private const val NOTHING = -2
    }
}

/** The value a parsed field stands for, given the column it is going into. */
fun CsvField.toSqlValue(column: SqlColumn): SqlValue =
    if (!isQuoted && text.isEmpty()) SqlValue.Null else parseSqlValue(text, column)

/**
 * Writes rows as a JSON array of objects, one per row, keyed by column name.
 *
 * Written a row at a time rather than built up as a [JSONArray], so that exporting a table larger
 * than memory still works. Blobs become `{"$BLOB_KEY": "<base64>"}`, which is what lets them come
 * back as blobs instead of as text.
 */
class JsonRowWriter(private val out: Appendable, private val columnNames: List<String>) {
    private var hasWrittenRow = false

    fun begin() {
        out.append("[")
    }

    fun writeValues(values: List<SqlValue>) {
        if (hasWrittenRow) {
            out.append(",")
        }
        hasWrittenRow = true
        out.append("\n  {")
        values.forEachIndexed { index, value ->
            if (index > 0) {
                out.append(", ")
            }
            out.append(JSONObject.quote(columnNames.getOrElse(index) { index.toString() }))
            out.append(": ")
            out.append(value.toJsonLiteral())
        }
        out.append("}")
    }

    fun end() {
        if (hasWrittenRow) {
            out.append("\n")
        }
        out.append("]\n")
    }
}

private fun SqlValue.toJsonLiteral(): String =
    when (this) {
        is SqlValue.Null -> "null"
        is SqlValue.Integer -> value.toString()
        // JSON has no way to write these, so they become null rather than invalid JSON.
        is SqlValue.Real -> if (value.isFinite()) value.toString() else "null"
        is SqlValue.Text -> JSONObject.quote(value)
        is SqlValue.Blob ->
            "{${JSONObject.quote(BLOB_KEY)}: " +
                "${JSONObject.quote(Base64.encodeToString(value, Base64.NO_WRAP))}}"
    }

/** Marks an object as a wrapped blob rather than a nested value. */
private const val BLOB_KEY = "\$blob"

/** The rows of a JSON export, in the order their columns first appeared. */
class JsonRows(val columnNames: List<String>, val rows: List<Map<String, SqlValue>>)

/**
 * Parses what [JsonRowWriter] writes: an array of objects. Columns are collected across every row,
 * so a file whose rows omit their NULLs still imports.
 */
fun parseJsonRows(text: String): JsonRows {
    val array = JSONArray(text)
    val columnNames = mutableListOf<String>()
    val rows = mutableListOf<Map<String, SqlValue>>()
    for (index in 0 until array.length()) {
        val jsonObject = array.optJSONObject(index)
            ?: throw IllegalArgumentException("Element $index is not an object")
        val row = mutableMapOf<String, SqlValue>()
        for (key in jsonObject.keys()) {
            if (key !in columnNames) {
                columnNames += key
            }
            row[key] = jsonObject.get(key).toSqlValue()
        }
        rows += row
    }
    return JsonRows(columnNames, rows)
}

private fun Any?.toSqlValue(): SqlValue =
    when (this) {
        null, JSONObject.NULL -> SqlValue.Null
        is Int -> SqlValue.Integer(toLong())
        is Long -> SqlValue.Integer(this)
        is Double -> SqlValue.Real(this)
        is Float -> SqlValue.Real(toDouble())
        is Boolean -> SqlValue.Integer(if (this) 1L else 0L)
        is JSONObject -> {
            val blob = optString(BLOB_KEY, null)
            if (blob != null) {
                SqlValue.Blob(Base64.decode(blob, Base64.DEFAULT))
            } else {
                SqlValue.Text(toString())
            }
        }
        else -> SqlValue.Text(toString())
    }
