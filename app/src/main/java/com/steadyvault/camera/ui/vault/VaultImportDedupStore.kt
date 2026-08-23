package com.steadyvault.camera.ui.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.steadyvault.camera.storage.vault.PrivateMediaFileWriter
import com.steadyvault.camera.storage.vault.VaultMediaFormats
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

    fun remember(context: Context, area: String, sourceKey: String, file: File, contentSha256: String = "") = synchronized(lock) {
        val values = ContentValues().apply {
            put(COLUMN_AREA, area)
            put(COLUMN_SOURCE_KEY, sourceKey)
            put(COLUMN_FILE_NAME, file.name)
            put(COLUMN_SIZE, file.length())
            put(COLUMN_MODIFIED, file.lastModified())
            put(COLUMN_CONTENT_HASH, contentSha256)
        }
        database(context).writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    fun contentHashForFile(context: Context, area: String, file: File): String? = synchronized(lock) {
        if (!file.isFile || file.length() <= 0L) return@synchronized null
        database(context).readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_CONTENT_HASH),
            "$COLUMN_AREA = ? AND $COLUMN_FILE_NAME = ? AND $COLUMN_SIZE = ? AND $COLUMN_MODIFIED = ? AND $COLUMN_CONTENT_HASH <> ''",
            arrayOf(area, file.name, file.length().toString(), file.lastModified().toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null
        }
    }

    fun rememberContentHash(context: Context, area: String, file: File, contentSha256: String) {
        if (!file.isFile || contentSha256.isBlank()) return
        remember(context, area, "$LOCAL_HASH_SOURCE_PREFIX${file.name}", file, contentSha256)
    }

    /**
     * Deduplicação por conteúdo real. Primeiro usa o índice persistente; para arquivos
     * antigos ainda sem hash, calcula somente em candidatos com o mesmo tamanho.
     */
    fun findContentDuplicate(context: Context, area: String, contentSha256: String, sizeBytes: Long, directory: File, exclude: File? = null, onProgress: () -> Unit = {}): File? = synchronized(lock) {
        if (contentSha256.isBlank() || sizeBytes <= 0L) return@synchronized null
        val database = database(context).writableDatabase
        database.query(
            TABLE,
            arrayOf(COLUMN_FILE_NAME, COLUMN_MODIFIED),
            "$COLUMN_AREA = ? AND $COLUMN_CONTENT_HASH = ? AND $COLUMN_SIZE = ?",
            arrayOf(area, contentSha256, sizeBytes.toString()),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = File(directory, cursor.getString(0))
                val indexedModified = cursor.getLong(1)
                if (candidate.isFile && candidate.length() == sizeBytes && candidate.lastModified() == indexedModified && !sameFile(candidate, exclude)) return@synchronized candidate
            }
        }

        var checked = 0
        val candidates = directory.listFiles { file ->
            file.isFile && file.length() == sizeBytes && VaultMediaFormats.isSupported(file.name) && !sameFile(file, exclude)
        }.orEmpty()
        val maxCandidates = when {
            sizeBytes >= 512L * 1024L * 1024L -> 2
            sizeBytes >= 128L * 1024L * 1024L -> 4
            sizeBytes >= 32L * 1024L * 1024L -> 8
            else -> MAX_LEGACY_HASH_CANDIDATES
        }
        for (candidate in candidates) {
            if (checked++ >= maxCandidates) break
            onProgress()
            val hash = runCatching { PrivateMediaFileWriter.sha256(candidate) }.getOrNull() ?: continue
            onProgress()
            if (hash == contentSha256) return@synchronized candidate
        }
        null
    }

    fun cleanupArea(context: Context, area: String, directory: File): Int = synchronized(lock) {
        val database = database(context).writableDatabase
        val staleKeys = mutableListOf<String>()
        database.query(TABLE, arrayOf(COLUMN_SOURCE_KEY, COLUMN_FILE_NAME, COLUMN_SIZE, COLUMN_MODIFIED), "$COLUMN_AREA = ?", arrayOf(area), null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val file = File(directory, cursor.getString(1))
                val size = cursor.getLong(2)
                val modified = cursor.getLong(3)
                if (!file.isFile || (size > 0L && file.length() != size) || (modified > 0L && file.lastModified() != modified)) staleKeys += cursor.getString(0)
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

    private fun sameFile(first: File, second: File?): Boolean = second != null && runCatching { first.canonicalPath == second.canonicalPath }.getOrDefault(first.absolutePath == second.absolutePath)

    private fun database(context: Context): Helper = helper ?: synchronized(lock) {
        helper ?: Helper(context.applicationContext).also { it.setWriteAheadLoggingEnabled(true); helper = it }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE $TABLE ($COLUMN_AREA TEXT NOT NULL, $COLUMN_SOURCE_KEY TEXT NOT NULL, $COLUMN_FILE_NAME TEXT NOT NULL, $COLUMN_SIZE INTEGER NOT NULL, $COLUMN_MODIFIED INTEGER NOT NULL DEFAULT 0, $COLUMN_CONTENT_HASH TEXT NOT NULL DEFAULT '', PRIMARY KEY($COLUMN_AREA, $COLUMN_SOURCE_KEY))")
            database.execSQL("CREATE INDEX import_dedup_file_idx ON $TABLE($COLUMN_AREA, $COLUMN_FILE_NAME)")
            database.execSQL("CREATE INDEX import_dedup_content_idx ON $TABLE($COLUMN_AREA, $COLUMN_CONTENT_HASH, $COLUMN_SIZE)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                database.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_CONTENT_HASH TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS import_dedup_content_idx ON $TABLE($COLUMN_AREA, $COLUMN_CONTENT_HASH, $COLUMN_SIZE)")
            }
            if (oldVersion < 3) {
                database.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_MODIFIED INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private const val DATABASE_NAME = "steadyvault_import_dedup.db"
    private const val DATABASE_VERSION = 3
    private const val TABLE = "imported_sources"
    private const val COLUMN_AREA = "area"
    private const val COLUMN_SOURCE_KEY = "source_key"
    private const val COLUMN_FILE_NAME = "file_name"
    private const val COLUMN_SIZE = "size_bytes"
    private const val COLUMN_MODIFIED = "modified_at"
    private const val COLUMN_CONTENT_HASH = "content_sha256"
    private const val MAX_LEGACY_HASH_CANDIDATES = 64
    private const val LOCAL_HASH_SOURCE_PREFIX = "local_hash:"
}
