package com.steadyvault.camera.core.state

import android.content.Context
import java.io.File

object VideoProcessingStateStore {
    data class Snapshot(
        val state: String,
        val message: String,
        val progress: Int,
        val sourcePath: String,
        val outputPath: String?,
        val startedAtMs: Long,
        val updatedAtMs: Long
    ) {
        val running: Boolean
            get() = state == STATE_RUNNING
    }

    private const val PREFS = "steadyvault_video_processing_state"
    private const val KEY_STATE = "state"
    private const val KEY_MESSAGE = "message"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_SOURCE = "source"
    private const val KEY_OUTPUT = "output"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_UPDATED_AT = "updated_at"

    const val STATE_IDLE = "idle"
    const val STATE_RUNNING = "running"
    const val STATE_SUCCESS = "success"
    const val STATE_ERROR = "error"
    const val STATE_CANCELLED = "cancelled"

    fun begin(context: Context, source: File, message: String = "Preparando vídeo…") {
        val now = System.currentTimeMillis()
        preferences(context).edit()
            .putString(KEY_STATE, STATE_RUNNING)
            .putString(KEY_MESSAGE, message)
            .putInt(KEY_PROGRESS, 0)
            .putString(KEY_SOURCE, normalizedPath(source))
            .remove(KEY_OUTPUT)
            .putLong(KEY_STARTED_AT, now)
            .putLong(KEY_UPDATED_AT, now)
            .apply()
    }

    fun update(context: Context, source: File, progress: Int, message: String) {
        val prefs = preferences(context)
        val sourcePath = normalizedPath(source)
        val storedSource = prefs.getString(KEY_SOURCE, null)
        val startedAt = if (storedSource == sourcePath) {
            prefs.getLong(KEY_STARTED_AT, System.currentTimeMillis())
        } else {
            System.currentTimeMillis()
        }
        prefs.edit()
            .putString(KEY_STATE, STATE_RUNNING)
            .putString(KEY_MESSAGE, message)
            .putInt(KEY_PROGRESS, progress.coerceIn(0, 100))
            .putString(KEY_SOURCE, sourcePath)
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun finish(
        context: Context,
        source: File,
        state: String,
        progress: Int,
        message: String,
        outputPath: String? = null
    ) {
        val editor = preferences(context).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_MESSAGE, message)
            .putInt(KEY_PROGRESS, progress.coerceIn(0, 100))
            .putString(KEY_SOURCE, normalizedPath(source))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        if (outputPath.isNullOrBlank()) editor.remove(KEY_OUTPUT)
        else editor.putString(KEY_OUTPUT, outputPath)
        editor.apply()
    }

    fun snapshot(context: Context): Snapshot {
        recoverStale(context)
        val prefs = preferences(context)
        return Snapshot(
            state = prefs.getString(KEY_STATE, STATE_IDLE) ?: STATE_IDLE,
            message = prefs.getString(KEY_MESSAGE, "") ?: "",
            progress = prefs.getInt(KEY_PROGRESS, 0).coerceIn(0, 100),
            sourcePath = prefs.getString(KEY_SOURCE, "") ?: "",
            outputPath = prefs.getString(KEY_OUTPUT, null),
            startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun snapshotFor(context: Context, file: File): Snapshot? {
        val snapshot = snapshot(context)
        return snapshot.takeIf { it.sourcePath == normalizedPath(file) }
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    fun recoverStale(context: Context) {
        val prefs = preferences(context)
        val state = prefs.getString(KEY_STATE, STATE_IDLE) ?: STATE_IDLE
        if (state != STATE_RUNNING) return
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt <= STALE_RUNNING_MS) return
        prefs.edit()
            .putString(KEY_STATE, STATE_ERROR)
            .putString(KEY_MESSAGE, "O processamento foi interrompido antes de concluir")
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun normalizedPath(file: File): String =
        file.absoluteFile.normalize().path

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val STALE_RUNNING_MS = 5L * 60L * 1000L
}
