package com.steadyvault.camera.processing.motion

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Suaviza apenas micro-trancos da trajetoria global da camera.
 *
 * A ideia nao e "estabilizar tudo": movimentos longos e intencionais continuam
 * acompanhando a camera. O filtro reage principalmente a aceleracoes curtas e
 * discrepantes, que sao o padrao visto em saltos de EIS/OIS.
 */
class MotionTrajectoryStabilizer(
    private val historySize: Int = 7
) {
    data class Correction(
        val xUv: Float = 0f,
        val yUv: Float = 0f,
        val rotationRad: Float = 0f,
        val zoom: Float = 1f,
        val jankDetected: Boolean = false,
        val confidence: Float = 0f
    )

    private val historyX = FloatArray(historySize)
    private val historyY = FloatArray(historySize)
    private val historyR = FloatArray(historySize)
    private var historyCount = 0
    private var historyIndex = 0

    private var correctionX = 0f
    private var correctionY = 0f
    private var correctionR = 0f

    fun reset() {
        historyCount = 0
        historyIndex = 0
        correctionX = 0f
        correctionY = 0f
        correctionR = 0f
    }

    fun update(
        motionXUv: Float,
        motionYUv: Float,
        rotationRad: Float,
        reliability: Float,
        sceneChange: Boolean,
        unstable: Boolean,
        nearlyStatic: Boolean
    ): Correction {
        if (sceneChange) {
            reset()
            return Correction()
        }

        if (unstable || reliability < MIN_RELIABILITY) {
            decay(LOW_CONFIDENCE_DECAY)
            return buildCorrection(false, reliability)
        }

        if (nearlyStatic) {
            push(0f, 0f, 0f)
            decay(STATIC_DECAY)
            return buildCorrection(false, reliability)
        }

        val expectedX = median(historyX, historyCount)
        val expectedY = median(historyY, historyCount)
        val expectedR = median(historyR, historyCount)

        if (historyCount < MIN_HISTORY) {
            push(motionXUv, motionYUv, rotationRad)
            decay(NORMAL_DECAY)
            return buildCorrection(false, reliability)
        }

        val residualX = expectedX - motionXUv
        val residualY = expectedY - motionYUv
        val residualR = expectedR - rotationRad
        val residualMagnitude = hypot(residualX.toDouble(), residualY.toDouble()).toFloat()
        val expectedMagnitude = hypot(expectedX.toDouble(), expectedY.toDouble()).toFloat()

        val translationThreshold = max(
            MIN_TRANSLATION_JANK_UV,
            expectedMagnitude * RELATIVE_TRANSLATION_THRESHOLD + BASE_TRANSLATION_THRESHOLD_UV
        )
        val rotationThreshold = max(
            MIN_ROTATION_JANK_RAD,
            abs(expectedR) * RELATIVE_ROTATION_THRESHOLD + BASE_ROTATION_THRESHOLD_RAD
        )

        val translationJank = residualMagnitude > translationThreshold
        val rotationJank = abs(residualR) > rotationThreshold
        val jank = translationJank || rotationJank

        if (jank) {
            val strength = ((reliability - MIN_RELIABILITY) / (1f - MIN_RELIABILITY))
                .coerceIn(0.25f, 1f)
            correctionX = (correctionX * JANK_MEMORY + residualX * strength)
                .coerceIn(-MAX_CORRECTION_X_UV, MAX_CORRECTION_X_UV)
            correctionY = (correctionY * JANK_MEMORY + residualY * strength)
                .coerceIn(-MAX_CORRECTION_Y_UV, MAX_CORRECTION_Y_UV)
            correctionR = (correctionR * JANK_MEMORY + residualR * strength)
                .coerceIn(-MAX_CORRECTION_ROT_RAD, MAX_CORRECTION_ROT_RAD)

            // Nao deixa o proprio tranco contaminar o modelo de movimento normal.
            push(expectedX, expectedY, expectedR)
        } else {
            decay(NORMAL_DECAY)
            push(motionXUv, motionYUv, rotationRad)
        }

        return buildCorrection(jank, reliability)
    }

    private fun buildCorrection(jank: Boolean, reliability: Float): Correction {
        val requiredZoom = (
            1f +
                abs(correctionX) * 2.2f +
                abs(correctionY) * 2.2f +
                abs(correctionR) * 0.55f
            ).coerceIn(1f, MAX_ZOOM)

        return Correction(
            xUv = correctionX,
            yUv = correctionY,
            rotationRad = correctionR,
            zoom = requiredZoom,
            jankDetected = jank,
            confidence = reliability.coerceIn(0f, 1f)
        )
    }

    private fun decay(amount: Float) {
        correctionX *= amount
        correctionY *= amount
        correctionR *= amount
        if (abs(correctionX) < 0.00005f) correctionX = 0f
        if (abs(correctionY) < 0.00005f) correctionY = 0f
        if (abs(correctionR) < 0.00005f) correctionR = 0f
    }

    private fun push(x: Float, y: Float, r: Float) {
        historyX[historyIndex] = x
        historyY[historyIndex] = y
        historyR[historyIndex] = r
        historyIndex = (historyIndex + 1) % historySize
        if (historyCount < historySize) historyCount++
    }

    private fun median(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        val tmp = FloatArray(count)
        for (i in 0 until count) tmp[i] = values[i]
        tmp.sort()
        val middle = count / 2
        return if (count % 2 == 0) (tmp[middle - 1] + tmp[middle]) * 0.5f else tmp[middle]
    }

    private companion object {
        const val MIN_HISTORY = 3
        const val MIN_RELIABILITY = 0.34f

        const val BASE_TRANSLATION_THRESHOLD_UV = 0.00075f
        const val MIN_TRANSLATION_JANK_UV = 0.0016f
        const val RELATIVE_TRANSLATION_THRESHOLD = 0.34f

        const val BASE_ROTATION_THRESHOLD_RAD = 0.0012f
        const val MIN_ROTATION_JANK_RAD = 0.0022f
        const val RELATIVE_ROTATION_THRESHOLD = 0.42f

        const val MAX_CORRECTION_X_UV = 0.014f
        const val MAX_CORRECTION_Y_UV = 0.020f
        const val MAX_CORRECTION_ROT_RAD = 0.020f
        const val MAX_ZOOM = 1.045f

        const val JANK_MEMORY = 0.62f
        const val NORMAL_DECAY = 0.84f
        const val STATIC_DECAY = 0.70f
        const val LOW_CONFIDENCE_DECAY = 0.78f
    }
}
