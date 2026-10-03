package com.steadyvault.camera.capture.timing

/**
 * Classifica se a própria HAL confirma que uma Surface regular consegue sustentar
 * a cadência pedida. A informação pública de minFrameDuration não é usada para
 * impedir a gravação: ela define prioridade. Se não houver rota confirmada, o fluxo
 * normal ainda pode tentar a rota desconhecida/lenta e manter o fallback existente.
 */
object CaptureCadencePolicy {
    enum class Confidence { CONFIRMED, UNKNOWN, TOO_SLOW }

    fun confidence(minFrameDurationNs: Long, targetFps: Int, toleranceNs: Long): Confidence {
        if (targetFps < 60) return Confidence.CONFIRMED
        if (minFrameDurationNs <= 0L) return Confidence.UNKNOWN
        val nominalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
        return if (minFrameDurationNs <= nominalNs + toleranceNs.coerceAtLeast(0L)) {
            Confidence.CONFIRMED
        } else {
            Confidence.TOO_SLOW
        }
    }

    fun priorityScore(confidence: Confidence, targetFps: Int): Long {
        if (targetFps < 60) return 0L
        return when (confidence) {
            Confidence.CONFIRMED -> 80_000_000_000L
            Confidence.UNKNOWN -> 25_000_000_000L
            Confidence.TOO_SLOW -> -40_000_000_000L
        }
    }

    fun measuredCadenceAcceptable(
        measuredFps: Double,
        targetFps: Int,
        longGaps: Int,
        minimumRatio: Double = 0.997
    ): Boolean {
        if (measuredFps <= 0.0 || longGaps > 0) return false
        val safeRatio = minimumRatio.coerceIn(0.90, 1.0)
        val maximumRatio = 2.0 - safeRatio
        return measuredFps >= targetFps * safeRatio && measuredFps <= targetFps * maximumRatio
    }

}
