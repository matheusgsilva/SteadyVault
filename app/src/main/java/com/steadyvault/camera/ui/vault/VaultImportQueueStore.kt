package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File

internal object VaultImportQueueStore {
    private const val PREFS = "vault_import_queue"
    private const val TYPE_DOCUMENTS = "documents"
    private const val TYPE_TREE = "tree"

    data class Job(
        val area: String,
        val type: String,
        val label: String,
        val treeUri: Uri?,
        val nextIndex: Int,
        val queueReady: Boolean
    ) {
        val isTree: Boolean get() = type == TYPE_TREE
    }

    fun createDocuments(context: Context, area: String, uris: List<Uri>, label: String) {
        requireValidArea(area)
        writeQueue(context, area, uris)
        prefs(context).edit()
            .putBoolean(key(area, "active"), true)
            .putString(key(area, "type"), TYPE_DOCUMENTS)
            .putString(key(area, "label"), label)
            .remove(key(area, "tree_uri"))
            .putInt(key(area, "next_index"), 0)
            .putBoolean(key(area, "queue_ready"), true)
            .apply()
    }

    fun createTree(context: Context, area: String, treeUri: Uri, label: String) {
        requireValidArea(area)
        queueFile(context, area).delete()
        prefs(context).edit()
            .putBoolean(key(area, "active"), true)
            .putString(key(area, "type"), TYPE_TREE)
            .putString(key(area, "label"), label)
            .putString(key(area, "tree_uri"), treeUri.toString())
            .putInt(key(area, "next_index"), 0)
            .putBoolean(key(area, "queue_ready"), false)
            .apply()
    }

    fun prepareTreeQueue(context: Context, area: String, uris: List<Uri>) {
        requireValidArea(area)
        writeQueue(context, area, uris)
        prefs(context).edit()
            .putInt(key(area, "next_index"), 0)
            .putBoolean(key(area, "queue_ready"), true)
            .apply()
    }

    fun job(context: Context, area: String): Job? {
        requireValidArea(area)
        val prefs = prefs(context)
        if (!prefs.getBoolean(key(area, "active"), false)) return null
        val type = prefs.getString(key(area, "type"), TYPE_DOCUMENTS).orEmpty()
        val treeUri = prefs.getString(key(area, "tree_uri"), null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        return Job(
            area = area,
            type = type,
            label = prefs.getString(key(area, "label"), "arquivo(s)").orEmpty(),
            treeUri = treeUri,
            nextIndex = prefs.getInt(key(area, "next_index"), 0).coerceAtLeast(0),
            queueReady = prefs.getBoolean(key(area, "queue_ready"), false)
        )
    }

    fun hasPending(context: Context, area: String): Boolean = job(context, area) != null

    fun pendingAreas(context: Context): List<String> = VaultAreaId.ALL
        .filter { hasPending(context, it) }

    fun readQueue(context: Context, area: String): List<Uri> {
        requireValidArea(area)
        val file = queueFile(context, area)
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { raw ->
                runCatching {
                    val decoded = Base64.decode(raw, Base64.NO_WRAP or Base64.URL_SAFE)
                    Uri.parse(decoded.toString(Charsets.UTF_8))
                }.getOrNull()
            }.toList()
        }
    }

    fun setNextIndex(context: Context, area: String, value: Int) {
        requireValidArea(area)
        prefs(context).edit().putInt(key(area, "next_index"), value.coerceAtLeast(0)).apply()
    }

    fun clear(context: Context, area: String) {
        requireValidArea(area)
        queueFile(context, area).delete()
        prefs(context).edit()
            .remove(key(area, "active"))
            .remove(key(area, "type"))
            .remove(key(area, "label"))
            .remove(key(area, "tree_uri"))
            .remove(key(area, "next_index"))
            .remove(key(area, "queue_ready"))
            .apply()
    }

    private fun writeQueue(context: Context, area: String, uris: List<Uri>) {
        val target = queueFile(context, area)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.bufferedWriter().use { writer ->
            uris.forEach { uri ->
                writer.append(Base64.encodeToString(uri.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE))
                writer.newLine()
            }
        }
        if (target.exists() && !target.delete()) error("Não foi possível substituir a fila de importação")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun queueFile(context: Context, area: String): File =
        File(File(context.noBackupFilesDir, "vault_import_jobs"), "$area.queue")

    private fun requireValidArea(area: String) {
        require(VaultAreaId.isValid(area)) { "Cofre inválido" }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(area: String, name: String) = "$area.$name"
}
