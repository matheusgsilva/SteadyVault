package com.steadyvault.camera.capture.recorder

import kotlin.math.sqrt

/**
 * Último ISO visto pela câmera, usado pelo denoise temporal da GPU para escalar o limiar de
 * "movimento": em ISO alto o ruído do sensor já passa do limiar fixo, o filtro se desligava
 * em área parada e o granulado ficava inteiro.
 */
internal object SensorNoiseHint {
    @Volatile var iso: Int = 0

    /** 1,0 em ISO ~1600; limitado a 0,7..1,8 (ruído cresce com a raiz do ganho). */
    fun motionGateScale(): Float {
        val value = iso
        if (value <= 0) return 1f
        return sqrt(value / 1600f).coerceIn(0.7f, 1.8f)
    }
}
