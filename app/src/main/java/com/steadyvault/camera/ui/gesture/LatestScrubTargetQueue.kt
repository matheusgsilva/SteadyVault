package com.steadyvault.camera.ui.gesture

/** Mantém somente o destino mais recente sem reagendar uma atualização já pendente. */
internal class LatestScrubTargetQueue {
    private var latestTargetMs = NO_TARGET
    private var updateScheduled = false

    fun offer(targetMs: Long): Boolean {
        latestTargetMs = targetMs.coerceAtLeast(0L)
        if (updateScheduled) return false
        updateScheduled = true
        return true
    }

    fun consume(): Long {
        updateScheduled = false
        return latestTargetMs.also { latestTargetMs = NO_TARGET }
    }

    fun hasPending(): Boolean = updateScheduled

    fun clear() {
        latestTargetMs = NO_TARGET
        updateScheduled = false
    }

    companion object {
        const val NO_TARGET = -1L
    }
}
