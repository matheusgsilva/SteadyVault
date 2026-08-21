package com.steadyvault.camera.ui.browser

import android.app.DownloadManager
import android.content.Context

object BrowserDownloadRegistry {
    private const val PREFS = "steadyvault_browser_downloads"
    private const val KEY_IDS = "download_ids"

    data class Entry(val id: Long, val name: String)

    fun add(context: Context, id: Long, name: String) {
        val current = entries(context).associateBy { it.id }.toMutableMap()
        current[id] = Entry(id, name)
        save(context, current.values)
    }

    fun entries(context: Context): List<Entry> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { raw ->
        val separator = raw.indexOf('|')
        if (separator <= 0) return@mapNotNull null
        raw.substring(0, separator).toLongOrNull()?.let { Entry(it, raw.substring(separator + 1)) }
    }.sortedByDescending { it.id }

    fun removeAll(context: Context): Int {
        val entries = entries(context)
        if (entries.isNotEmpty()) runCatching { context.getSystemService(DownloadManager::class.java)?.remove(*entries.map { it.id }.toLongArray()) }
        save(context, emptyList())
        return entries.size
    }

    private fun save(context: Context, entries: Collection<Entry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_IDS, entries.map { "${it.id}|${it.name.replace('|', '_')}" }.toSet()).apply()
    }
}
