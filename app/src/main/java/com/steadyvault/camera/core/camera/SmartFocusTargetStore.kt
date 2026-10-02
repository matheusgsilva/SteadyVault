package com.steadyvault.camera.core.camera

import android.os.SystemClock

/**
 * Short-lived handoff from the live preview pose detector to the recording service.
 * Coordinates are normalized in the upright preview coordinate space.
 */
object SmartFocusTargetStore {

    data class Target(
        val x: Float,
        val y: Float,
        val kind: SmartPoseFocusAnalyzer.Kind,
        val updatedAtElapsedMs: Long
    )

    @Volatile
    private var latest: Target? = null

    fun update(target: SmartPoseFocusAnalyzer.Target) {
        latest = Target(
            x = target.x.coerceIn(0f, 1f),
            y = target.y.coerceIn(0f, 1f),
            kind = target.kind,
            updatedAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    fun fresh(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Target? {
        val value = latest ?: return null
        return value.takeIf {
            SystemClock.elapsedRealtime() - it.updatedAtElapsedMs <= maxAgeMs
        }
    }

    fun clear() {
        latest = null
    }

    private const val DEFAULT_MAX_AGE_MS = 8_000L
}
