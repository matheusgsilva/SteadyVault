package com.steadyvault.camera.storage.vault

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.UUID

object VaultAlbumStore {
    data class Album(val id: String, val name: String)

    private const val PREFS = "steadyvault_vault_albums"
    private const val KEY_ALBUM_IDS = "album_ids"
    private const val PREFIX_NAME = "album_name_"
    private const val PREFIX_ASSIGNMENT = "media_album_"

    fun albums(context: Context): List<Album> {
        val prefs = prefs(context)
        return prefs.getStringSet(KEY_ALBUM_IDS, emptySet()).orEmpty()
            .mapNotNull { id -> prefs.getString(PREFIX_NAME + id, null)?.let { Album(id, it) } }
            .sortedBy { it.name.lowercase() }
    }

    fun create(context: Context, requestedName: String): Album {
        val name = requestedName.trim().replace(Regex("\\s+"), " ").take(40)
        require(name.isNotBlank()) { "Informe um nome para o álbum" }
        require(albums(context).none { it.name.equals(name, ignoreCase = true) }) { "Já existe um álbum com esse nome" }
        val album = Album(UUID.randomUUID().toString(), name)
        val prefs = prefs(context)
        val ids = prefs.getStringSet(KEY_ALBUM_IDS, emptySet()).orEmpty().toMutableSet().apply { add(album.id) }
        prefs.edit().putStringSet(KEY_ALBUM_IDS, ids).putString(PREFIX_NAME + album.id, album.name).apply()
        return album
    }

    fun rename(context: Context, albumId: String, requestedName: String) {
        val name = requestedName.trim().replace(Regex("\\s+"), " ").take(40)
        require(name.isNotBlank()) { "Informe um nome para o álbum" }
        require(albums(context).none { it.id != albumId && it.name.equals(name, ignoreCase = true) }) { "Já existe um álbum com esse nome" }
        prefs(context).edit().putString(PREFIX_NAME + albumId, name).apply()
    }

    fun delete(context: Context, albumId: String) {
        val prefs = prefs(context)
        val ids = prefs.getStringSet(KEY_ALBUM_IDS, emptySet()).orEmpty().toMutableSet().apply { remove(albumId) }
        val editor = prefs.edit().putStringSet(KEY_ALBUM_IDS, ids).remove(PREFIX_NAME + albumId)
        prefs.all.keys.filter { it.startsWith(PREFIX_ASSIGNMENT) && prefs.getString(it, null) == albumId }
            .forEach(editor::remove)
        editor.apply()
    }

    fun albumIdFor(context: Context, file: File): String? = prefs(context).getString(assignmentKey(file), null)

    fun assignmentSnapshot(context: Context, files: Collection<File>): Map<String, String?> {
        val prefs = prefs(context)
        return files.associate { file -> file.absolutePath to prefs.getString(assignmentKey(file), null) }
    }

    fun assign(context: Context, files: Collection<File>, albumId: String?) {
        if (albumId != null) require(albums(context).any { it.id == albumId }) { "Álbum inválido" }
        val editor = prefs(context).edit()
        files.forEach { file ->
            val key = assignmentKey(file)
            if (albumId == null) editor.remove(key) else editor.putString(key, albumId)
        }
        editor.apply()
    }

    fun removeMetadata(context: Context, file: File) {
        prefs(context).edit().remove(assignmentKey(file)).apply()
    }

    fun cleanMissing(context: Context, existingFiles: Collection<File>): Int {
        val validKeys = existingFiles.mapTo(hashSetOf(), ::assignmentKey)
        val prefs = prefs(context)
        val editor = prefs.edit()
        var cleaned = 0
        prefs.all.keys.filter { it.startsWith(PREFIX_ASSIGNMENT) && it !in validKeys }.forEach {
            editor.remove(it)
            cleaned++
        }
        if (cleaned > 0) editor.apply()
        return cleaned
    }

    private fun assignmentKey(file: File): String {
        val encoded = Base64.encodeToString(file.absolutePath.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        return PREFIX_ASSIGNMENT + encoded
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
