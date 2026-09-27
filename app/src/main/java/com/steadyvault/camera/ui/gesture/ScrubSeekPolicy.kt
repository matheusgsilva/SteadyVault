package com.steadyvault.camera.ui.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * Escolhe entre resposta imediata e precisão absoluta durante o arraste.
 *
 * Buscas exatas muito distantes obrigam o decodificador a percorrer todos os quadros
 * desde o keyframe anterior. Durante um movimento rápido, isso cria uma fila que fica
 * perceptível principalmente depois dos primeiros GOPs do vídeo. Nessa situação, uma
 * busca sincronizada mantém a imagem acompanhando o dedo. Atualizações exatas periódicas
 * continuam ocorrendo durante o movimento e assumem completamente quando o ajuste fica fino.
 */
object ScrubSeekPolicy {
    enum class Mode { EXACT, FAST_SYNC }

    private const val MIN_FAST_JUMP_MS = 300L
    private const val FAST_JUMP_FRAME_COUNT = 12L
    private const val MIN_FAST_VELOCITY = 2.5
    private const val DEFAULT_FRAME_INTERVAL_MS = 33L

    fun chooseMode(
        previousPositionMs: Long,
        targetPositionMs: Long,
        elapsedRealtimeMs: Long,
        frameRate: Float
    ): Mode {
        if (previousPositionMs < 0L) return Mode.EXACT

        val distanceMs = abs(targetPositionMs - previousPositionMs)
        val elapsedMs = elapsedRealtimeMs.coerceAtLeast(1L)
        val frameIntervalMs = ScrubFramePolicy.frameIntervalUs(frameRate)
            ?.let { intervalUs -> ((intervalUs + 999L) / 1_000L).coerceAtLeast(1L) }
            ?: DEFAULT_FRAME_INTERVAL_MS
        val fastJumpThresholdMs = max(MIN_FAST_JUMP_MS, frameIntervalMs * FAST_JUMP_FRAME_COUNT)
        val velocity = distanceMs.toDouble() / elapsedMs.toDouble()

        return if (distanceMs >= fastJumpThresholdMs && velocity >= MIN_FAST_VELOCITY) {
            Mode.FAST_SYNC
        } else {
            Mode.EXACT
        }
    }

    fun chooseCommitMode(
        startPositionMs: Long,
        targetPositionMs: Long,
        resumePlayback: Boolean,
        precisionRequired: Boolean = false
    ): Mode {
        if (precisionRequired || startPositionMs < 0L) return Mode.EXACT
        val distanceMs = abs(targetPositionMs - startPositionMs)
        return if (distanceMs >= MIN_FAST_JUMP_MS && resumePlayback) Mode.FAST_SYNC else Mode.EXACT
    }
}
