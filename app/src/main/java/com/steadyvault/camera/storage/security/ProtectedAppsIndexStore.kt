package com.steadyvault.camera.storage.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Índice persistente e não sensível dos atalhos lançáveis (pacote e rótulo). */
object ProtectedAppsIndexStore {
    data class Entry(val packageName: String, val label: String)
    data class Snapshot(val entries: List<Entry>, val savedAtMs: Long)

    fun read(context: Context): Snapshot {
        val preferences = prefs(context)
        val entries = runCatching {
            val array = JSONArray(preferences.getString(KEY_ENTRIES, "[]"))
            buildList(array.length()) {
                repeat(array.length()) { index ->
                    val value = array.optJSONObject(index) ?: return@repeat
                    val packageName = value.optString("package").trim()
                    if (packageName.isNotBlank()) add(Entry(packageName, value.optString("label").ifBlank { packageName }))
                }
            }
        }.getOrDefault(emptyList())
        return Snapshot(entries, preferences.getLong(KEY_SAVED_AT, 0L))
    }

    fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("package", entry.packageName).put("label", entry.label))
        }
        prefs(context).edit()
            .putString(KEY_ENTRIES, array.toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun invalidate(context: Context) {
        prefs(context).edit().putLong(KEY_SAVED_AT, 0L).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "steadyvault_launcher_apps_index"
    private const val KEY_ENTRIES = "entries_v1"
    private const val KEY_SAVED_AT = "saved_at_v1"
}
