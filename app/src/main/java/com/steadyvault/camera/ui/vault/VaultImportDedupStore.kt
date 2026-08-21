package com.steadyvault.camera.ui.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

internal object VaultImportDedupStore {
    private val lock = Any()
    @Volatile private var helper: Helper? = null

    fun containsValid(context: Context, area: String, sourceKey: String, directory: File): Boolean = synchronized(lock) {
        val database = database(context).writableDatabase
        database.query(TABLE, arrayOf(COLUMN_FILE_NAME, COLUMN_SIZE), "$COLUMN_AREA = ? AND $COLUMN_SOURCE_KEY = ?", arrayOf(area, sourceKey), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return@synchronized false
            val file = File(directory, cursor.getString(0))
            val expectedSize = cursor.getLong(1)
            if (file.isFile && (expectedSize <= 0L || file.length() == expectedSize)) return@synchronized true
        }
        database.delete(TABLE, "$COLUMN_AREA = ? AND $COLUMN_SOURCE_KEY = ?", arrayOf(area, sourceKey))
        false
    }

    fun remember(context: Context, area: String, sourceKey: String, file: File) = synchronized(lock) {
        val values = ContentValues().apply {
            put(COLUMN_AREA, area)
            put(COLUMN_SOURCE_KEY, sourceKey)
            put(COLUMN_FILE_NAME, file.name)
            put(COLUMN_SIZE, file.length())
        }
        database(context).writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    fun cleanupArea(context: Context, area: String, directory: File): Int = synchronized(lock) {
        val database = database(context).writableDatabase
        val staleKeys = mutableListOf<String>()
        database.query(TABLE, arrayOf(COLUMN_SOURCE_KEY, COLUMN_FILE_NAME, COLUMN_SIZE), "$COLUMN_AREA = ?", arrayOf(area), null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val file = File(directory, cursor.getString(1))
                val size = cursor.getLong(2)
                if (!file.isFile || (size > 0L && file.length() != size)) staleKeys += cursor.getString(0)
            }
        }
        if (staleKeys.isNotEmpty()) {
            database.beginTransaction()
            try {
                staleKeys.forEach { sourceKey -> database.delete(TABLE, "$COLUMN_AREA = ? AND $COLUMN_SOURCE_KEY = ?", arrayOf(area, sourceKey)) }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        staleKeys.size
    }

    private fun database(context: Context): Helper = helper ?: synchronized(lock) {
        helper ?: Helper(context.applicationContext).also { it.setWriteAheadLoggingEnabled(true); helper = it }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE $TABLE ($COLUMN_AREA TEXT NOT NULL, $COLUMN_SOURCE_KEY TEXT NOT NULL, $COLUMN_FILE_NAME TEXT NOT NULL, $COLUMN_SIZE INTEGER NOT NULL, PRIMARY KEY($COLUMN_AREA, $COLUMN_SOURCE_KEY))")
            database.execSQL("CREATE INDEX import_dedup_file_idx ON $TABLE($COLUMN_AREA, $COLUMN_FILE_NAME)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            database.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(database)
        }
    }

    private const val DATABASE_NAME = "steadyvault_import_dedup.db"
    private const val DATABASE_VERSION = 1
    private const val TABLE = "imported_sources"
    private const val COLUMN_AREA = "area"
    private const val COLUMN_SOURCE_KEY = "source_key"
    private const val COLUMN_FILE_NAME = "file_name"
    private const val COLUMN_SIZE = "size_bytes"
}
