package com.steadyvault.camera.capture.timing

/**
 * Preserva a cauda já em voo na HAL antes de sinalizar EOS ao encoder.
 * stopRepeating() permite que capturas em andamento terminem; abortCaptures()
 * fica reservado a recuperação/limpeza forçada, nunca à parada normal.
 */
object RecordingStopPolicy {
    fun tailDrainMs(targetFps: Int): Long {
        val fps = targetFps.coerceIn(1, 240)
        val duration = (TAIL_FRAMES * 1_000L + fps - 1L) / fps
        return duration.coerceIn(MIN_TAIL_DRAIN_MS, MAX_TAIL_DRAIN_MS)
    }

    private const val TAIL_FRAMES = 3L
    private const val MIN_TAIL_DRAIN_MS = 20L
    private const val MAX_TAIL_DRAIN_MS = 120L
}
