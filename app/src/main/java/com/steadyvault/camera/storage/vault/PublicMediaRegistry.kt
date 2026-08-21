package com.steadyvault.camera.storage.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object PublicMediaRegistry {
    private const val PREFS = "steadyvault_public_media"
    private const val KEY_ITEMS = "items"
    private const val SEPARATOR = '\u001F'

    data class Entry(val uri: Uri, val name: String)
    data class Metrics(val files: Int, val bytes: Long)

    fun add(context: Context, uri: Uri, name: String) {
        val current = entries(context).associateBy { it.uri.toString() }.toMutableMap()
        current[uri.toString()] = Entry(uri, name)
        save(context, current.values)
    }

    fun entries(context: Context): List<Entry> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_ITEMS, emptySet()).orEmpty().mapNotNull { raw ->
            val separator = raw.indexOf(SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            runCatching { Entry(Uri.parse(raw.substring(0, separator)), raw.substring(separator + 1)) }.getOrNull()
        }

    fun metrics(context: Context): Metrics {
        var files = 0
        var bytes = 0L
        val registered = entries(context)
        val alive = mutableListOf<Entry>()
        registered.forEach { entry ->
            val query = runCatching {
                context.contentResolver.query(entry.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else null
                }
            }
            if (query.isFailure) {
                alive += entry
                return@forEach
            }
            query.getOrNull()?.let { size ->
                files++
                bytes += size
                alive += entry
            }
        }
        if (alive.size != registered.size) save(context, alive)
        return Metrics(files, bytes)
    }

    fun remove(context: Context, uri: Uri): Boolean {
        val deleted = deletePublic(context, uri)
        if (!deleted) return false
        save(context, entries(context).filterNot { it.uri == uri })
        return true
    }

    fun removeAll(context: Context): Boolean {
        val remaining = entries(context).filterNot { deletePublic(context, it.uri) }
        save(context, remaining)
        return remaining.isEmpty()
    }

    private fun deletePublic(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) >= 0
    }.getOrDefault(false)

    private fun save(context: Context, entries: Collection<Entry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ITEMS, entries.map { "${it.uri}$SEPARATOR${it.name.replace(SEPARATOR, '_')}" }.toSet())
            .apply()
    }
}
