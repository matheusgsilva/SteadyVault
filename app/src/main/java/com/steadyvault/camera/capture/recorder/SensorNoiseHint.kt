package com.steadyvault.camera.capture.recorder

import kotlin.math.sqrt

/**
 * Último ISO visto pela câmera, usado pelo denoise temporal da GPU para escalar o limiar de
 * "movimento": em ISO alto o ruído do sensor já passa do limiar fixo, o filtro se desligava
 * em área parada e o granulado ficava inteiro.
 */
internal object SensorNoiseHint {
    @Volatile var iso: Int = 0

    /** Zoom da gravação: o recorte digital/telefoto amplia o grão do sensor. */
    @Volatile var zoom: Float = 1f

    /** 1,0 em ISO ~1600; limitado a 0,7..2,2 (ruído cresce com a raiz do ganho). */
    fun motionGateScale(): Float {
        val value = iso
        val isoScale = if (value <= 0) 1f else sqrt(value / 1600f)
        // Com zoom o grão fica maior e mais "grosso": +8% de tolerância por 1x de zoom acima de 1.
        val zoomScale = (1f + 0.08f * (zoom - 1f)).coerceIn(1f, 1.5f)
        return (isoScale * zoomScale).coerceIn(0.7f, 2.2f)
    }
}
