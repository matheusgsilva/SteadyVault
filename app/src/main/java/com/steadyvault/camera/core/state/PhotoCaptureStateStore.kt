package com.steadyvault.camera.core.state

import android.content.Context

object PhotoCaptureStateStore {
    data class Snapshot(
        val running: Boolean,
        val startedAtMillis: Long,
        val finishedAtMillis: Long,
        val success: Boolean?,
        val message: String?
    )

    private const val PREFS = "steadyvault_photo_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_FINISHED_AT = "finished_at"
    private const val KEY_HAS_RESULT = "has_result"
    private const val KEY_SUCCESS = "success"
    private const val KEY_MESSAGE = "message"
    private const val STALE_AFTER_MS = 90_000L

    fun begin(context: Context) {
        preferences(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_FINISHED_AT)
            .remove(KEY_HAS_RESULT)
            .remove(KEY_SUCCESS)
            .remove(KEY_MESSAGE)
            .apply()
    }

    /** Limpa somente o resultado terminal anterior sem fingir que uma captura já começou. */

    /**
     * Finaliza o estado compartilhado. Chamadas sem resultado continuam compatíveis com
     * capturas feitas dentro do preview; o PhotoService informa sucesso/erro explicitamente
     * para que consumidores do estado não dependam de uma corrida entre startForegroundService e begin().
     */
    fun finish(context: Context, success: Boolean? = null, message: String? = null) {
        val prefs = preferences(context)
        val wasRunning = prefs.getBoolean(KEY_RUNNING, false)
        val editor = prefs.edit().putBoolean(KEY_RUNNING, false)
        if (wasRunning || success != null) {
            editor.putLong(KEY_FINISHED_AT, System.currentTimeMillis())
        }
        if (success != null) {
            editor.putBoolean(KEY_HAS_RESULT, true)
                .putBoolean(KEY_SUCCESS, success)
            if (!message.isNullOrBlank()) editor.putString(KEY_MESSAGE, message) else editor.remove(KEY_MESSAGE)
        }
        editor.apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = preferences(context)
        val hasResult = prefs.getBoolean(KEY_HAS_RESULT, false)
        return Snapshot(
            running = prefs.getBoolean(KEY_RUNNING, false),
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
            finishedAtMillis = prefs.getLong(KEY_FINISHED_AT, 0L),
            success = if (hasResult) prefs.getBoolean(KEY_SUCCESS, false) else null,
            message = prefs.getString(KEY_MESSAGE, null)
        )
    }

    fun isBusy(context: Context): Boolean {
        val state = snapshot(context)
        if (!state.running) return false
        if (state.startedAtMillis <= 0L || System.currentTimeMillis() - state.startedAtMillis > STALE_AFTER_MS) {
            finish(context, success = false, message = "Estado de captura fotográfica expirou")
            return false
        }
        return true
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
