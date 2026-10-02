package com.steadyvault.camera.capture.recorder

import kotlin.math.floor

/**
 * Reamostrador CFR pelo TEMPO EXATO de captura.
 *
 * Por que existe: a ponte grava PTS uniformes (1/fps), mas o conteúdo de cada frame foi
 * capturado em instantes levemente irregulares. Exibir um frame capturado 4 ms depois do
 * esperado como se fosse regular é o "solavanco": os frames existem, o movimento anda
 * desigual. O relógio de slots anterior tolerava até ±0,75 frame (~12 ms a 60 FPS) de erro
 * sem corrigir nada.
 *
 * Aqui existe uma grade de instantes de saída T_k (um por 1/fps, no relógio do sensor).
 * Cada frame real novo "fecha" os ticks que caem entre o frame anterior e ele:
 *  - tick dentro da tolerância de um frame real: usa esse frame direto (alpha 1 = atual,
 *    0 = anterior), nitidez total;
 *  - tick no meio: mistura temporal linear com alpha = (T - anterior) / (atual - anterior),
 *    isto é, o frame que existiria exatamente naquele instante. Cobre jitter grande e
 *    frames perdidos com o mesmo mecanismo;
 *  - nenhum tick no intervalo (câmera acima do FPS nominal): o frame é descartado.
 *
 * A grade segue a fase da câmera com um PLL de ganho baixo (para que uma câmera estável
 * mas deslocada da grade passe frames direto, sem mistura permanente) e é ancorada ao
 * relógio: se a fase passa de meio frame, a grade repete ou pula UM tick. Assim o vídeo
 * não deriva do áudio.
 *
 * Todos os tempos internos são relativos ao primeiro frame (Double) para não perder
 * precisão com timestamps absolutos grandes.
 */
class CfrTimeResampler(
    private val frameIntervalNs: Long,
    private val snapFraction: Double = DEFAULT_SNAP_FRACTION,
    private val phaseGain: Double = DEFAULT_PHASE_GAIN,
    private val maxPhaseStepFraction: Double = DEFAULT_MAX_PHASE_STEP_FRACTION
) {
    private var originNs = 0L
    private var nextTick = 1L
    private var phaseNs = 0.0
    private var started = false
    private var alphas = FloatArray(INITIAL_CAPACITY)

    /** Quantidade de saídas geradas pelo último [plan]. */
    var count: Int = 0
        private set

    /** Peso do frame atual na saída [index] (1 = só atual, 0 = só anterior). */
    fun alphaAt(index: Int): Float = alphas[index]

    /** O primeiro frame real é o tick 0; a grade parte do timestamp dele. */
    fun start(firstTimestampNs: Long) {
        originNs = firstTimestampNs
        nextTick = 1L
        phaseNs = 0.0
        count = 0
        started = true
    }

    fun reset() {
        started = false
        count = 0
    }

    /**
     * Planeja as saídas para o frame [currentTimestampNs], dado o frame real anterior.
     * Retorna quantas saídas devem ser emitidas (0 = descartar); os pesos ficam em
     * [alphaAt].
     */
    fun plan(previousTimestampNs: Long, currentTimestampNs: Long): Int {
        check(started) { "CfrTimeResampler não iniciado" }
        require(frameIntervalNs > 0L) { "intervalo de frame inválido" }
        count = 0
        if (currentTimestampNs <= previousTimestampNs) return 0

        val interval = frameIntervalNs.toDouble()
        val snap = interval * snapFraction
        val previous = (previousTimestampNs - originNs).toDouble()
        val current = (currentTimestampNs - originNs).toDouble()
        val span = current - previous

        while (count < MAX_TICKS_PER_FRAME) {
            val tick = nextTick.toDouble() * interval + phaseNs
            if (tick > current + snap) break
            val alpha = when {
                current - tick <= snap -> 1f
                tick - previous <= snap -> 0f
                else -> ((tick - previous) / span).toFloat().coerceIn(0f, 1f)
            }
            push(alpha)
            nextTick++
        }
        if (count >= MAX_TICKS_PER_FRAME) {
            // Lacuna absurda: não gera milhares de blends; ressincroniza a grade no frame atual.
            nextTick = floor((current - phaseNs) / interval).toLong() + 1L
        }

        // PLL: o erro de fase de TODO frame (não só dos que geram saída) contra o tick mais
        // próximo. Medir só os que geram saída trava a grade numa fase ruim.
        val nearest = floor((current - phaseNs) / interval + 0.5)
        val error = current - (nearest * interval + phaseNs)
        val limit = interval * maxPhaseStepFraction
        phaseNs += (phaseGain * error).coerceIn(-limit, limit)

        // Âncora ao relógio: meia janela de fase = no máximo um tick repetido/pulado.
        val half = interval / 2.0
        if (phaseNs > half) {
            phaseNs -= interval
        } else if (phaseNs < -half) {
            phaseNs += interval
        }
        return count
    }

    private fun push(alpha: Float) {
        if (count == alphas.size) alphas = alphas.copyOf(alphas.size * 2)
        alphas[count] = alpha
        count++
    }

    companion object {
        /**
         * Tolerância para usar um frame real direto. 0,25 frame (~4 ms a 60 FPS): acima
         * disso o erro de tempo vira solavanco visível; abaixo o excesso de mistura borra
         * movimento. Ajustável: menor = movimento mais exato e mais mistura.
         */
        const val DEFAULT_SNAP_FRACTION = 0.25
        const val DEFAULT_PHASE_GAIN = 0.08
        const val DEFAULT_MAX_PHASE_STEP_FRACTION = 0.03
        private const val INITIAL_CAPACITY = 16
        private const val MAX_TICKS_PER_FRAME = 1200
    }
}
