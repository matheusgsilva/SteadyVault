package com.steadyvault.camera.capture.timing

import kotlin.math.roundToLong

/**
 * Preserva o relógio real entregue pela câmera/encoder em todos os modos de FPS.
 *
 * Um timestamp válido passa sem arredondamento para a grade nominal: uma fonte que
 * entregue 56–57 FPS em um modo de 60 FPS continua com sua cadência real, sem saltos
 * artificiais, repetição ou criação de quadros. O FPS alvo é mantido apenas para quem
 * precisa consultar o intervalo nominal.
 *
 * MediaMuxer precisa receber amostras em ordem. Por isso, somente timestamps duplicados
 * ou regressivos são corrigidos, avançando o mínimo representável de um microssegundo.
 * Assim que o relógio bruto volta a ultrapassar a saída, ele volta a ser usado sem
 * acumular correções e sem deslocar o restante da gravação em relação ao áudio.
 */
class VideoTimestampNormalizer(targetFps: Int) {
    private val safeFps = targetFps.coerceIn(1, MAX_SUPPORTED_FPS)
    private val nominalIntervalUs = MICROSECONDS_PER_SECOND.toDouble() / safeFps.toDouble()
    private var lastOutputPtsUs = UNSET_PTS_US

    fun normalize(rawPtsUs: Long): Long {
        val sourcePtsUs = rawPtsUs.coerceAtLeast(0L)
        if (lastOutputPtsUs == UNSET_PTS_US) {
            lastOutputPtsUs = sourcePtsUs
            return sourcePtsUs
        }

        val outputPtsUs = if (sourcePtsUs > lastOutputPtsUs) {
            sourcePtsUs
        } else {
            // Um microssegundo é o menor avanço que mantém a amostra existente válida
            // sem fabricar um intervalo de quadro nominal nem alterar a cadência seguinte.
            lastOutputPtsUs + MINIMUM_PTS_ADVANCE_US
        }
        lastOutputPtsUs = outputPtsUs
        return outputPtsUs
    }

    fun frameIntervalUs(): Long = nominalIntervalUs.roundToLong().coerceAtLeast(1L)

    fun reset() {
        lastOutputPtsUs = UNSET_PTS_US
    }

    private companion object {
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val MINIMUM_PTS_ADVANCE_US = 1L
        const val MAX_SUPPORTED_FPS = 240
        const val UNSET_PTS_US = Long.MIN_VALUE
    }
}
