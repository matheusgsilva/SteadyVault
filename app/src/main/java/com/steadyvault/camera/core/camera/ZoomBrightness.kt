package com.steadyvault.camera.core.camera

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.roundToInt

/**
 * Mantém o brilho do zoom parecido com o do 1x. Nas câmeras lógicas da Samsung, 3x/5x trocam para
 * lentes telefoto menos luminosas (f/2,4 e f/3,4 contra f/1,7 da principal) e a ultra-wide é f/1,9.
 * A HAL não compensa isso sozinha com a taxa de quadros fixa, então soma-se EV equivalente à
 * diferença de abertura em stops, limitado pelo alcance de compensação da câmera. A compensação só
 * levanta o alvo da AE: se o ISO já estiver no limite, não há mais o que ganhar.
 */
object ZoomBrightness {
    /** EV extra (em stops) para a lente que atende o [zoomRatio] numa câmera lógica com telefoto. */
    fun extraEv(characteristics: CameraCharacteristics, zoomRatio: Float): Float {
        val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return 0f
        // Só câmeras lógicas multi-lente com alcance de zoom de telefoto (S25 Ultra: 0,6x–100x).
        if (range.upper < 5f) return 0f
        return when {
            zoomRatio < 0.95f -> 0.5f
            zoomRatio < 2.95f -> 0f
            zoomRatio < 4.95f -> 1.0f
            else -> 2.0f
        }
    }

    /** Passos de compensação (unidade da câmera) a somar ao valor escolhido pelo usuário. */
    fun extraSteps(characteristics: CameraCharacteristics, zoomRatio: Float): Int {
        val ev = extraEv(characteristics, zoomRatio)
        if (ev == 0f) return 0
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val stepEv = step?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: return 0
        if (stepEv <= 0f) return 0
        return (ev / stepEv).roundToInt()
    }
}
