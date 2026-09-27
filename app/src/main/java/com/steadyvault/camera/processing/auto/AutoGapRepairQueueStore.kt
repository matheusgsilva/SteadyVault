package com.steadyvault.camera.processing.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Fila persistente e idempotente do reparo automático.
 * O original nunca é alterado por esta fila; outputPath aponta somente para uma cópia concluída.
 */
object AutoGapRepairQueueStore {
    enum class Status { PENDING, RUNNING, SUCCESS, SKIPPED, ERROR }

    data class Job(
        val id: String,
        val sourcePath: String,
        val targetFps: Int,
        val status: Status,
        val attempts: Int,
        val progress: Int,
        val message: String,
        val lastError: String?,
        val outputPath: String?,
        val createdAtMs: Long,
        val updatedAtMs: Long
    )

    data class Summary(
        val pending: Int,
        val running: Int,
        val success: Int,
        val skipped: Int,
        val error: Int,
        val lastError: String?
    ) {
        val hasWork: Boolean get() = pending > 0 || running > 0

        fun text(): String = buildString {
            append("Fila automática: ")
            append(pending).append(" pendente(s)")
            if (running > 0) append(" • ").append(running).append(" em processamento")
            if (success > 0) append(" • ").append(success).append(" reparado(s)")
            if (skipped > 0) append(" • ").append(skipped).append(" sem gaps")
            if (error > 0) append(" • ").append(error).append(" com erro")
            lastError?.takeIf { it.isNotBlank() }?.let { append("\nÚltimo erro: ").append(it) }
        }
    }

    private const val PREFS = "steadyvault_auto_gap_repair_queue"
    private const val KEY_QUEUE = "queue_json"
    private const val MAX_FINISHED_JOBS = 80

    @Synchronized
    fun recoverInterrupted(context: Context) {
        val now = System.currentTimeMillis()
        val jobs = load(context).map { job ->
            if (job.status == Status.RUNNING) {
                job.copy(
                    status = Status.PENDING,
                    progress = 0,
                    message = "Reparo interrompido; será retomado do original",
                    lastError = "O processo anterior foi encerrado durante o reparo",
                    updatedAtMs = now
                )
            } else job
        }
        save(context, jobs)
    }

    @Synchronized
    fun enqueue(context: Context, source: File, targetFps: Int): Job {
        val path = source.absoluteFile.normalize().path
        val jobs = load(context).toMutableList()
        jobs.firstOrNull {
            it.sourcePath == path && it.status in setOf(Status.PENDING, Status.RUNNING)
        }?.let { return it }
        val now = System.currentTimeMillis()
        val job = Job(
            id = UUID.randomUUID().toString(),
            sourcePath = path,
            // 0 significa "inferir o FPS do próprio vídeo" no worker.
            targetFps = targetFps.coerceIn(0, 240),
            status = Status.PENDING,
            attempts = 0,
            progress = 0,
            message = "Aguardando reparo de cadência",
            lastError = null,
            outputPath = null,
            createdAtMs = now,
            updatedAtMs = now
        )
        jobs += job
        save(context, jobs)
        return job
    }

    @Synchronized
    fun nextPending(context: Context): Job? = load(context)
        .filter { it.status == Status.PENDING }
        .minByOrNull { it.createdAtMs }

    @Synchronized
    fun markRunning(context: Context, id: String, message: String): Job? = update(context, id) {
        it.copy(
            status = Status.RUNNING,
            attempts = it.attempts + 1,
            progress = 0,
            message = message,
            lastError = null,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun markPending(context: Context, id: String, message: String): Job? = update(context, id) {
        it.copy(
            status = Status.PENDING,
            progress = 0,
            message = message,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun markSuccess(context: Context, id: String, output: File, message: String): Job? = update(context, id) {
        it.copy(
            status = Status.SUCCESS,
            progress = 100,
            message = message,
            lastError = null,
            outputPath = output.absoluteFile.normalize().path,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun markSkipped(context: Context, id: String, message: String): Job? = update(context, id) {
        it.copy(
            status = Status.SKIPPED,
            progress = 100,
            message = message,
            lastError = null,
            outputPath = null,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun markError(context: Context, id: String, error: String): Job? = update(context, id) {
        it.copy(
            status = Status.ERROR,
            progress = 0,
            message = "Falha no reparo automático",
            lastError = error,
            outputPath = null,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun retryFailed(context: Context): Int {
        val now = System.currentTimeMillis()
        var changed = 0
        val jobs = load(context).map { job ->
            if (job.status == Status.ERROR) {
                changed++
                job.copy(
                    status = Status.PENDING,
                    progress = 0,
                    message = "Reparo reenfileirado manualmente",
                    lastError = job.lastError,
                    updatedAtMs = now
                )
            } else job
        }
        save(context, jobs)
        return changed
    }

    @Synchronized
    fun summary(context: Context): Summary {
        val jobs = load(context)
        return Summary(
            pending = jobs.count { it.status == Status.PENDING },
            running = jobs.count { it.status == Status.RUNNING },
            success = jobs.count { it.status == Status.SUCCESS },
            skipped = jobs.count { it.status == Status.SKIPPED },
            error = jobs.count { it.status == Status.ERROR },
            lastError = jobs.asSequence()
                .filter { it.status == Status.ERROR && !it.lastError.isNullOrBlank() }
                .maxByOrNull { it.updatedAtMs }
                ?.lastError
        )
    }

    @Synchronized
    fun updateProgress(context: Context, id: String, progress: Int, message: String): Job? = update(context, id) {
        if (it.status != Status.RUNNING) it
        else it.copy(
            progress = progress.coerceIn(0, 100),
            message = message,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun runningJob(context: Context): Job? = load(context)
        .filter { it.status == Status.RUNNING }
        .maxByOrNull { it.updatedAtMs }

    @Synchronized
    fun hasPending(context: Context): Boolean = load(context).any { it.status == Status.PENDING }

    @Synchronized
    fun removeSource(context: Context, source: File): Int {
        val path = source.absoluteFile.normalize().path
        val jobs = load(context)
        val kept = jobs.filterNot { it.sourcePath == path }
        if (kept.size != jobs.size) save(context, kept)
        return jobs.size - kept.size
    }

    @Synchronized
    fun removeMissingSources(context: Context) {
        val jobs = load(context).filter { job ->
            job.status in setOf(Status.SUCCESS, Status.SKIPPED) || File(job.sourcePath).isFile
        }
        save(context, jobs)
    }

    private fun update(context: Context, id: String, block: (Job) -> Job): Job? {
        val jobs = load(context).toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = block(jobs[index])
        jobs[index] = updated
        save(context, jobs)
        return updated
    }

    private fun load(context: Context): List<Job> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, values: List<Job>) {
        val active = values.filter { it.status == Status.PENDING || it.status == Status.RUNNING || it.status == Status.ERROR }
        val finished = values.filter { it.status == Status.SUCCESS || it.status == Status.SKIPPED }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_FINISHED_JOBS)
        val normalized = (active + finished).distinctBy { it.id }
        val array = JSONArray().apply { normalized.forEach { put(toJson(it)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    private fun toJson(job: Job) = JSONObject()
        .put("id", job.id)
        .put("sourcePath", job.sourcePath)
        .put("targetFps", job.targetFps)
        .put("status", job.status.name)
        .put("attempts", job.attempts)
        .put("progress", job.progress)
        .put("message", job.message)
        .put("lastError", job.lastError ?: JSONObject.NULL)
        .put("outputPath", job.outputPath ?: JSONObject.NULL)
        .put("createdAtMs", job.createdAtMs)
        .put("updatedAtMs", job.updatedAtMs)

    private fun fromJson(json: JSONObject) = Job(
        id = json.getString("id"),
        sourcePath = json.getString("sourcePath"),
        targetFps = json.optInt("targetFps", 0).coerceIn(0, 240),
        status = runCatching { Status.valueOf(json.optString("status")) }.getOrDefault(Status.ERROR),
        attempts = json.optInt("attempts", 0).coerceAtLeast(0),
        progress = json.optInt("progress", 0).coerceIn(0, 100),
        message = json.optString("message", ""),
        lastError = json.optString("lastError", "").takeIf { it.isNotBlank() && it != "null" },
        outputPath = json.optString("outputPath", "").takeIf { it.isNotBlank() && it != "null" },
        createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis()),
        updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())
    )
}
