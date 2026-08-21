package com.steadyvault.camera.ui.gesture

/** Classificação pura do avanço do relógio do player, independente do motor usado. */
object PlaybackHealthPolicy {
    enum class State { HEALTHY, SLOW, STALLED }

    fun classify(elapsedMs: Long, advancedMs: Long, speed: Float): State {
        val elapsed = elapsedMs.coerceAtLeast(1L)
        val advanced = advancedMs.coerceAtLeast(0L)
        val expected = (elapsed * speed.coerceAtLeast(0.25f)).toLong().coerceAtLeast(1L)
        val minimumAdvance = maxOf(180L, (expected * 0.20).toLong())
        if (advanced < minimumAdvance) return State.STALLED
        return if (advanced.toDouble() / expected.toDouble() < 0.72) State.SLOW else State.HEALTHY
    }
}
