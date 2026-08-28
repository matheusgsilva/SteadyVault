package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object VaultImportQueueStore {
    private const val PREFS = "vault_import_queue"
    private const val TYPE_DOCUMENTS = "documents"
    private const val TYPE_TREE = "tree"
    private val lock = Any()

    data class Job(
        val area: String,
        val type: String,
        val label: String,
        val treeUri: Uri?,
        val nextIndex: Int,
        val queueReady: Boolean,
        val expectedCount: Int,
        val keepDuplicates: Boolean
    ) {
        val isTree: Boolean get() = type == TYPE_TREE
    }

    data class EnqueueResult(val total: Int, val createdNewJob: Boolean, val waitingForTreeScan: Boolean)

    fun createDocuments(context: Context, area: String, uris: List<Uri>, label: String, keepDuplicates: Boolean) = synchronized(lock) {
        requireValidArea(area)
        pendingAppendFile(context, area).delete()
        writeQueueLocked(context, area, uris)
        prefs(context).edit()
            .putBoolean(key(area, "active"), true)
            .putString(key(area, "type"), TYPE_DOCUMENTS)
            .putString(key(area, "label"), label)
            .remove(key(area, "tree_uri"))
            .putInt(key(area, "next_index"), 0)
            .putInt(key(area, "expected_count"), uris.size)
            .putBoolean(key(area, "queue_ready"), true)
            .putBoolean(key(area, "keep_duplicates"), keepDuplicates)
            .commit()
    }

    fun enqueueDocuments(context: Context, area: String, uris: List<Uri>, label: String, keepDuplicates: Boolean): EnqueueResult = synchronized(lock) {
        requireValidArea(area)
        if (uris.isEmpty()) return@synchronized EnqueueResult(currentQueueCountLocked(context, area), false, false)
        val existing = jobLocked(context, area)
        if (existing == null) {
            createDocuments(context, area, uris, label, keepDuplicates)
            return@synchronized EnqueueResult(uris.size, true, false)
        }
        check(existing.keepDuplicates == keepDuplicates) { "A importação atual usa outra opção para arquivos repetidos. Aguarde a fila terminar." }
        if (!existing.queueReady) {
            val deferred = readQueueFileLocked(pendingAppendFile(context, area), allowMissing = true) + uris
            writeQueueFileLocked(pendingAppendFile(context, area), deferred)
            return@synchronized EnqueueResult(existing.expectedCount.coerceAtLeast(0) + deferred.size, false, true)
        }
        val current = readQueueLocked(context, area)
        val combined = current + uris
        writeQueueLocked(context, area, combined)
        prefs(context).edit().putInt(key(area, "expected_count"), combined.size).commit()
        EnqueueResult(combined.size, false, false)
    }

    fun createTree(context: Context, area: String, treeUri: Uri, label: String, keepDuplicates: Boolean) = synchronized(lock) {
        requireValidArea(area)
        queueFile(context, area).delete()
        pendingAppendFile(context, area).delete()
        prefs(context).edit()
            .putBoolean(key(area, "active"), true)
            .putString(key(area, "type"), TYPE_TREE)
            .putString(key(area, "label"), label)
            .putString(key(area, "tree_uri"), treeUri.toString())
            .putInt(key(area, "next_index"), 0)
            .putInt(key(area, "expected_count"), 0)
            .putBoolean(key(area, "queue_ready"), false)
            .putBoolean(key(area, "keep_duplicates"), keepDuplicates)
            .commit()
    }

    fun prepareTreeQueue(context: Context, area: String, uris: List<Uri>) = synchronized(lock) {
        requireValidArea(area)
        val deferred = readQueueFileLocked(pendingAppendFile(context, area), allowMissing = true)
        val combined = uris + deferred
        writeQueueLocked(context, area, combined)
        pendingAppendFile(context, area).delete()
        prefs(context).edit()
            .putInt(key(area, "next_index"), 0)
            .putInt(key(area, "expected_count"), combined.size)
            .putBoolean(key(area, "queue_ready"), true)
            .commit()
    }

    fun job(context: Context, area: String): Job? = synchronized(lock) {
        requireValidArea(area)
        jobLocked(context, area)
    }

    private fun jobLocked(context: Context, area: String): Job? {
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
            queueReady = prefs.getBoolean(key(area, "queue_ready"), false),
            expectedCount = prefs.getInt(key(area, "expected_count"), 0).coerceAtLeast(0),
            keepDuplicates = prefs.getBoolean(key(area, "keep_duplicates"), false)
        )
    }

    fun hasPending(context: Context, area: String): Boolean = job(context, area) != null

    fun pendingAreas(context: Context): List<String> = VaultAreaId.ALL.filter { hasPending(context, it) }

    fun readQueue(context: Context, area: String): List<Uri> = synchronized(lock) {
        requireValidArea(area)
        readQueueLocked(context, area)
    }

    private fun readQueueLocked(context: Context, area: String): List<Uri> = readQueueFileLocked(queueFile(context, area), allowMissing = true)

    fun currentQueueCount(context: Context, area: String): Int = synchronized(lock) {
        requireValidArea(area)
        currentQueueCountLocked(context, area)
    }

    private fun currentQueueCountLocked(context: Context, area: String): Int = readQueueLocked(context, area).size

    fun setNextIndex(context: Context, area: String, value: Int) {
        requireValidArea(area)
        prefs(context).edit().putInt(key(area, "next_index"), value.coerceAtLeast(0)).commit()
    }

    fun clear(context: Context, area: String) = synchronized(lock) {
        requireValidArea(area)
        queueFile(context, area).delete()
        pendingAppendFile(context, area).delete()
        prefs(context).edit()
            .remove(key(area, "active"))
            .remove(key(area, "type"))
            .remove(key(area, "label"))
            .remove(key(area, "tree_uri"))
            .remove(key(area, "next_index"))
            .remove(key(area, "expected_count"))
            .remove(key(area, "queue_ready"))
            .remove(key(area, "keep_duplicates"))
            .commit()
    }

    private fun writeQueueLocked(context: Context, area: String, uris: List<Uri>) = writeQueueFileLocked(queueFile(context, area), uris)

    private fun writeQueueFileLocked(target: File, uris: List<Uri>) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            try {
                uris.forEach { uri ->
                    writer.append(Base64.encodeToString(uri.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE))
                    writer.newLine()
                }
                writer.flush()
                output.fd.sync()
            } finally {
                writer.close()
            }
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        val persistedCount = readQueueFileLocked(target, allowMissing = false).size
        check(persistedCount == uris.size) { "Fila de importação incompleta: esperado ${uris.size}, persistido $persistedCount" }
    }

    private fun readQueueFileLocked(file: File, allowMissing: Boolean): List<Uri> {
        if (!file.isFile) {
            if (allowMissing) return emptyList()
            error("Fila de importação ausente")
        }
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapIndexed { index, raw ->
                runCatching {
                    val decoded = Base64.decode(raw, Base64.NO_WRAP or Base64.URL_SAFE)
                    Uri.parse(decoded.toString(Charsets.UTF_8))
                }.getOrElse { throw IllegalStateException("Fila de importação corrompida no item ${index + 1}", it) }
            }.toList()
        }
    }

    private fun queueFile(context: Context, area: String): File = File(File(context.noBackupFilesDir, "vault_import_jobs"), "$area.queue")
    private fun pendingAppendFile(context: Context, area: String): File = File(File(context.noBackupFilesDir, "vault_import_jobs"), "$area.append.queue")

    private fun requireValidArea(area: String) {
        require(VaultAreaId.isValid(area)) { "Cofre inválido" }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(area: String, name: String) = "$area.$name"
}
