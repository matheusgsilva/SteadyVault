package com.steadyvault.camera.storage.vault

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import java.io.File

object PrivateVaultMediaIndex {
    data class Summary(val count: Int, val bytes: Long)

    private val lock = Any()
    @Volatile private var helper: Helper? = null
    private val invalidatedAreas = hashSetOf<String>()
    private val lastReconciled = hashMapOf<String, Long>()

    fun invalidate(area: String) = synchronized(lock) { invalidatedAreas += area }

    fun reconcile(context: Context, area: String, directory: File, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val last = lastReconciled[area] ?: 0L
            if (!force && area !in invalidatedAreas && now - last < RECONCILE_TTL_MS) return
            val database = database(context).writableDatabase
            val files = directory.listFiles { file -> file.isFile && VaultMediaFormats.isSupported(file.name) }.orEmpty()
            database.beginTransaction()
            try {
                val existing = existingFingerprints(database, area)
                val livePaths = HashSet<String>(files.size)
                files.forEach { file ->
                    val path = normalizedPath(file)
                    livePaths += path
                    val fingerprint = existing[path]
                    if (fingerprint?.first == file.length() && fingerprint.second == file.lastModified()) return@forEach
                    val item = VaultRepository.readItem(file, fast = true)?.let { VaultRepository.applyCachedMetadata(context, it) } ?: return@forEach
                    database.insertWithOnConflict(TABLE_MEDIA, null, values(area, item), SQLiteDatabase.CONFLICT_REPLACE)
                }
                existing.keys.filterNot(livePaths::contains).forEach { path ->
                    database.delete(TABLE_MEDIA, "$COLUMN_AREA = ? AND $COLUMN_PATH = ?", arrayOf(area, path))
                }
                database.setTransactionSuccessful()
                invalidatedAreas -= area
                lastReconciled[area] = now
            } finally {
                database.endTransaction()
            }
        }
    }


    fun listAll(context: Context, area: String): List<VaultRepository.MediaItem> = synchronized(lock) {
        database(context).readableDatabase.query(TABLE_MEDIA, COLUMNS, "$COLUMN_AREA = ?", arrayOf(area), null, null, "$COLUMN_MODIFIED DESC").use(::readItems)
    }

    fun summary(context: Context, area: String): Summary = synchronized(lock) {
        database(context).readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM($COLUMN_SIZE), 0) FROM $TABLE_MEDIA WHERE $COLUMN_AREA = ?",
            arrayOf(area)
        ).use { cursor -> if (cursor.moveToFirst()) Summary(cursor.getInt(0), cursor.getLong(1)) else Summary(0, 0L) }
    }

    fun update(context: Context, area: String, item: VaultRepository.MediaItem) = synchronized(lock) {
        database(context).writableDatabase.insertWithOnConflict(TABLE_MEDIA, null, values(area, item), SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    fun remove(context: Context, area: String, file: File) = synchronized(lock) {
        database(context).writableDatabase.delete(TABLE_MEDIA, "$COLUMN_AREA = ? AND $COLUMN_PATH = ?", arrayOf(area, normalizedPath(file)))
        Unit
    }

    private fun existingFingerprints(database: SQLiteDatabase, area: String): Map<String, Pair<Long, Long>> {
        val result = HashMap<String, Pair<Long, Long>>()
        database.query(TABLE_MEDIA, arrayOf(COLUMN_PATH, COLUMN_SIZE, COLUMN_MODIFIED), "$COLUMN_AREA = ?", arrayOf(area), null, null, null).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getLong(1) to cursor.getLong(2)
        }
        return result
    }

    private fun values(area: String, item: VaultRepository.MediaItem): ContentValues = ContentValues().apply {
        put(COLUMN_AREA, area)
        put(COLUMN_PATH, normalizedPath(item.file))
        put(COLUMN_NAME, item.name)
        put(COLUMN_MIME, item.mime)
        put(COLUMN_VIDEO, if (item.video) 1 else 0)
        put(COLUMN_SIZE, item.sizeBytes)
        put(COLUMN_MODIFIED, item.modifiedAt)
        put(COLUMN_DURATION, item.durationMs)
        put(COLUMN_WIDTH, item.width)
        put(COLUMN_HEIGHT, item.height)
        put(COLUMN_ROTATION, item.rotationDegrees)
    }

    private fun readItems(cursor: Cursor): List<VaultRepository.MediaItem> {
        val items = ArrayList<VaultRepository.MediaItem>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            items += VaultRepository.MediaItem(
                file = File(cursor.getString(0)), name = cursor.getString(1), mime = cursor.getString(2), video = cursor.getInt(3) != 0,
                sizeBytes = cursor.getLong(4), modifiedAt = cursor.getLong(5), durationMs = cursor.getLong(6), width = cursor.getInt(7),
                height = cursor.getInt(8), rotationDegrees = cursor.getInt(9)
            )
        }
        return items
    }

    private fun normalizedPath(file: File): String = file.absoluteFile.normalize().path

    private fun database(context: Context): Helper = helper ?: synchronized(lock) {
        helper ?: Helper(context.applicationContext).also { it.setWriteAheadLoggingEnabled(true); helper = it }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE $TABLE_MEDIA ($COLUMN_AREA TEXT NOT NULL, $COLUMN_PATH TEXT NOT NULL, $COLUMN_NAME TEXT NOT NULL, $COLUMN_MIME TEXT NOT NULL, $COLUMN_VIDEO INTEGER NOT NULL, $COLUMN_SIZE INTEGER NOT NULL, $COLUMN_MODIFIED INTEGER NOT NULL, $COLUMN_DURATION INTEGER NOT NULL, $COLUMN_WIDTH INTEGER NOT NULL, $COLUMN_HEIGHT INTEGER NOT NULL, $COLUMN_ROTATION INTEGER NOT NULL, PRIMARY KEY($COLUMN_AREA, $COLUMN_PATH))")
            database.execSQL("CREATE INDEX private_media_area_modified_idx ON $TABLE_MEDIA($COLUMN_AREA, $COLUMN_MODIFIED DESC)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            database.execSQL("DROP TABLE IF EXISTS $TABLE_MEDIA")
            onCreate(database)
        }
    }

    private const val DATABASE_NAME = "steadyvault_private_media_index.db"
    private const val DATABASE_VERSION = 1
    private const val TABLE_MEDIA = "media"
    private const val COLUMN_AREA = "area"
    private const val COLUMN_PATH = "path"
    private const val COLUMN_NAME = "name"
    private const val COLUMN_MIME = "mime"
    private const val COLUMN_VIDEO = "video"
    private const val COLUMN_SIZE = "size_bytes"
    private const val COLUMN_MODIFIED = "modified_at"
    private const val COLUMN_DURATION = "duration_ms"
    private const val COLUMN_WIDTH = "width"
    private const val COLUMN_HEIGHT = "height"
    private const val COLUMN_ROTATION = "rotation"
    private val COLUMNS = arrayOf(COLUMN_PATH, COLUMN_NAME, COLUMN_MIME, COLUMN_VIDEO, COLUMN_SIZE, COLUMN_MODIFIED, COLUMN_DURATION, COLUMN_WIDTH, COLUMN_HEIGHT, COLUMN_ROTATION)
    private const val RECONCILE_TTL_MS = 2_000L
}
