package com.steadyvault.camera.capture.timing

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * AE por software para a cadência fixa de 60 fps.
 *
 * Com AE_MODE_OFF o sensor mantém 16,666 ms por quadro (sem o quadro pulado que o AE da HAL faz
 * no escuro), mas exposição/ISO ficavam congelados no valor do início: cena clara -> escura
 * ficava preta. Este controlador mede a luminância média do quadro e ajusta a "luz" total
 * (exposição x ISO) devagar e sem oscilar; [SensorCadencePolicy.resolveFromLight] reparte essa luz
 * entre tempo de exposição e ISO.
 *
 * Puro Kotlin, sem Android: testável em JVM.
 */
class SoftAutoExposure(
    initialLight: Double,
    private val minLight: Double,
    private val maxLight: Double,
    fixedTarget: Double? = null
) {
    var light: Double = initialLight.coerceIn(minLight, maxLight)
        private set

    /** Luminância alvo (0..1), calibrada nas duas primeiras medidas (o AE da HAL já convergiu). */
    var target: Double = fixedTarget ?: 0.45
        private set

    private var calibrationCount = if (fixedTarget != null) CALIBRATION_SAMPLES else 0
    private var calibrationSum = 0.0

    /** Atualiza com a luminância média (0..1) e a fração de pixels saturados; devolve a nova luz. */
    fun update(meanLuma: Double, saturatedFraction: Double): Double {
        val m = meanLuma.coerceIn(0.01, 1.0)
        if (calibrationCount < CALIBRATION_SAMPLES) {
            calibrationSum += m
            calibrationCount++
            if (calibrationCount == CALIBRATION_SAMPLES) {
                target = (calibrationSum / calibrationCount).coerceIn(TARGET_MIN, TARGET_MAX)
            }
            return light
        }

        var ratio = (target / m).pow(GAIN)
        val blown = m >= 0.95 || saturatedFraction > 0.35
        if (blown && ratio > 0.5) ratio = 0.5
        val lnRatio = abs(ln(ratio))
        if (!blown && lnRatio < DEAD_BAND) return light

        val limit = when {
            lnRatio > 0.7 -> 2.5
            lnRatio > 0.25 -> 1.3
            else -> 1.12
        }
        ratio = ratio.coerceIn(1.0 / limit, limit)
        light = (light * ratio).coerceIn(minLight, maxLight)
        return light
    }

    companion object {
        private const val CALIBRATION_SAMPLES = 2
        private const val TARGET_MIN = 0.35
        private const val TARGET_MAX = 0.55
        private const val DEAD_BAND = 0.06
        // luma (gamma) ~ luz^(1/2.2); passo em luz = (alvo/medido)^(2,2) amortecido em 0,8.
        private const val GAIN = 2.2 * 0.8

        /** Luminância média (0..1) da região central e fração saturada, de um RGBA row-major. */
        fun measure(rgba: ByteArray, width: Int, height: Int): Pair<Double, Double> {
            if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return 0.0 to 0.0
            val x0 = width / 6
            val x1 = width - width / 6
            val y0 = height / 6
            val y1 = height - height / 6
            var sum = 0.0
            var sat = 0
            var count = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val i = (y * width + x) * 4
                    val r = rgba[i].toInt() and 0xFF
                    val g = rgba[i + 1].toInt() and 0xFF
                    val b = rgba[i + 2].toInt() and 0xFF
                    val luma = 0.299 * r + 0.587 * g + 0.114 * b
                    sum += luma
                    if (luma >= 250.0) sat++
                    count++
                    x += 2
                }
                y += 2
            }
            if (count == 0) return 0.0 to 0.0
            return (sum / count / 255.0) to (sat.toDouble() / count)
        }
    }
}
