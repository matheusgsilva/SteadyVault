package com.steadyvault.camera.capture.recorder

import kotlin.math.roundToInt

/**
 * Planeja quantos slots CFR existem entre dois frames reais e quais posições
 * intermediárias devem ser sintetizadas. Alphas nunca incluem 0 ou 1.
 */
object CfrInterpolationPlanner {
    fun sourceSteps(deltaNs: Long, frameIntervalNs: Long): Int {
        if (deltaNs <= 0L || frameIntervalNs <= 0L) return 0
        return (deltaNs.toDouble() / frameIntervalNs.toDouble())
            .roundToInt()
            .coerceAtLeast(1)
    }

    fun interpolationAlphas(sourceSteps: Int): FloatArray {
        if (sourceSteps <= 1) return floatArrayOf()
        return FloatArray(sourceSteps - 1) { index ->
            (index + 1).toFloat() / sourceSteps.toFloat()
        }
    }
}
