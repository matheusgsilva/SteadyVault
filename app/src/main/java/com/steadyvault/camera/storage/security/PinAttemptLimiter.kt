package com.steadyvault.camera.storage.security

import android.content.Context
import kotlin.math.min

/** Limita tentativas locais repetidas sem armazenar o PIN. */
object PinAttemptLimiter {
    fun canAttempt(context: Context, scope: String): Boolean =
        System.currentTimeMillis() >= prefs(context).getLong(blockedKey(scope), 0L)

    fun record(context: Context, scope: String, success: Boolean) {
        val preferences = prefs(context)
        if (success) {
            preferences.edit().remove(failuresKey(scope)).remove(blockedKey(scope)).apply()
            return
        }
        val failures = preferences.getInt(failuresKey(scope), 0).plus(1).coerceAtMost(MAX_FAILURES)
        val penalty = if (failures < FAILURES_BEFORE_DELAY) 0L else {
            val shift = min(failures - FAILURES_BEFORE_DELAY, MAX_SHIFT)
            (INITIAL_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        }
        preferences.edit()
            .putInt(failuresKey(scope), failures)
            .putLong(blockedKey(scope), System.currentTimeMillis() + penalty)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun failuresKey(scope: String) = "${scope}_failures"
    private fun blockedKey(scope: String) = "${scope}_blocked_until"

    private const val PREFS = "steadyvault_pin_attempt_limiter"
    private const val FAILURES_BEFORE_DELAY = 5
    private const val MAX_FAILURES = 20
    private const val MAX_SHIFT = 5
    private const val INITIAL_DELAY_MS = 15_000L
    private const val MAX_DELAY_MS = 8L * 60L * 1_000L
}
