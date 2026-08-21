package com.steadyvault.camera.ui.vault

internal class VaultImportSession(
    private val gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS
) {
    private var active = false
    private var expiresAtElapsedMs = 0L

    fun begin(nowElapsedMs: Long = monotonicNowMs()) {
        active = true
        expiresAtElapsedMs = nowElapsedMs + gracePeriodMs
    }

    fun isTrusted(nowElapsedMs: Long = monotonicNowMs()): Boolean {
        if (!active) return false
        if (nowElapsedMs <= expiresAtElapsedMs) return true
        cancel()
        return false
    }

    fun consume(nowElapsedMs: Long = monotonicNowMs()): Boolean {
        val trusted = isTrusted(nowElapsedMs)
        cancel()
        return trusted
    }

    fun cancel() {
        active = false
        expiresAtElapsedMs = 0L
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DEFAULT_GRACE_PERIOD_MS = 15 * 60_000L
    }
}
