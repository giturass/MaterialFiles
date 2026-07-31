/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.database

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The 16-byte magic every SQLite database file starts with, including its terminating NUL.
 *
 * @see <a href="https://www.sqlite.org/fileformat.html#the_database_header">Database Header</a>
 */
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

private const val HEADER_SIZE = 100

/** Offset of the write version in the header; 2 means the database is in WAL mode. */
private const val WRITE_VERSION_OFFSET = 18

private const val WRITE_VERSION_WAL = 2

/** File name extensions that we offer to open in the database editor even for an empty file. */
private val DATABASE_EXTENSIONS = setOf("db", "db3", "sqlite", "sqlite3", "sqlitedb", "gpkg")

private fun File.readHeader(): ByteArray? =
    try {
        RandomAccessFile(this, "r").use { randomAccessFile ->
            if (randomAccessFile.length() < HEADER_SIZE) {
                null
            } else {
                ByteArray(HEADER_SIZE).also { randomAccessFile.readFully(it) }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }

/** Whether this file starts with the SQLite magic. A newly created empty file does not. */
fun File.isSqliteDatabase(): Boolean {
    val header = readHeader() ?: return false
    return SQLITE_HEADER.indices.all { header[it] == SQLITE_HEADER[it] }
}

/**
 * Whether this database uses write-ahead logging. Opening a WAL database without asking for WAL
 * makes SQLite rewrite it into another journal mode, which we don't want to do behind the user's
 * back, so we detect it up front and open it the way it already is.
 */
fun File.isWalSqliteDatabase(): Boolean {
    val header = readHeader() ?: return false
    if (!SQLITE_HEADER.indices.all { header[it] == SQLITE_HEADER[it] }) {
        return false
    }
    return header[WRITE_VERSION_OFFSET].toInt() == WRITE_VERSION_WAL
}

/** Whether this name looks like a database, used to route files that we can't sniff. */
fun String.hasDatabaseExtension(): Boolean =
    substringAfterLast('.', "").lowercase() in DATABASE_EXTENSIONS
