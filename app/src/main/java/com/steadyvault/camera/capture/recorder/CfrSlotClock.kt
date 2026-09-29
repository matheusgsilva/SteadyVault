package com.steadyvault.camera.capture.recorder

import kotlin.math.floor

/**
 * Relógio CFR por slots acumulados.
 *
 * O agendamento anterior comparava só dois frames vizinhos (round(delta / intervalo)).
 * Isso não tem memória: um delta de 1,6 intervalo por jitter virava um blend falso e o
 * delta seguinte (0,4) não o compensava, então a linha do tempo de saída corria à frente
 * do tempo real e o vídeo ganhava frames sintetizados sem haver gap algum. O mesmo
 * acontecia com timestamps pareados/quantizados (120/240 FPS) e com câmera acima do FPS
 * nominal (a saída nunca descartava o excesso).
 *
 * Aqui cada frame real é posicionado pelo tempo acumulado desde o primeiro frame. O erro
 * entre a posição real e o próximo slot livre é o que decide:
 *  - erro ≥ 0,75 slot: a câmera perdeu frames; preenche os slots ausentes (blend);
 *  - erro ≤ -0,75 slot: a saída está uma fração de quadro à frente; descarta o frame;
 *  - caso contrário: emite normalmente. Jitter de até ±0,75 intervalo é absorvido.
 *
 * Gaps reais continuam sendo preenchidos exatamente como antes; o que muda é que a saída
 * passa a seguir o tempo real, sem blends falsos e sem deriva.
 */
class CfrSlotClock(private val frameIntervalNs: Long) {

    data class Decision(
        /** Frame real deve ser descartado (saída já está à frente do tempo real). */
        val drop: Boolean,
        /** Quantidade de slots ausentes que precisam de frame sintetizado antes do real. */
        val missingSlots: Int
    )

    private var baseSourceNs = 0L
    private var nextSlot = 0L
    private var started = false

    /** O primeiro frame real ocupa o slot 0; o próximo slot livre passa a ser o 1. */
    fun start(firstSourceTimestampNs: Long) {
        baseSourceNs = firstSourceTimestampNs
        nextSlot = 1L
        started = true
    }

    fun reset() {
        started = false
    }

    fun next(sourceTimestampNs: Long): Decision {
        check(started) { "CfrSlotClock não iniciado" }
        require(frameIntervalNs > 0L) { "intervalo de frame inválido" }

        val positionSlots =
            (sourceTimestampNs - baseSourceNs).toDouble() / frameIntervalNs.toDouble()
        val error = positionSlots - nextSlot.toDouble()

        if (error <= -DROP_THRESHOLD_SLOTS) return DROP

        val missing = if (error >= FILL_THRESHOLD_SLOTS) {
            floor(error + FILL_ROUNDING_SLOTS).toInt().coerceAtLeast(0)
        } else {
            0
        }
        nextSlot += missing.toLong() + 1L
        return if (missing == 0) KEEP else Decision(drop = false, missingSlots = missing)
    }

    companion object {
        const val FILL_THRESHOLD_SLOTS = 0.75
        const val FILL_ROUNDING_SLOTS = 0.25
        const val DROP_THRESHOLD_SLOTS = 0.75

        private val DROP = Decision(drop = true, missingSlots = 0)
        private val KEEP = Decision(drop = false, missingSlots = 0)
    }
}
