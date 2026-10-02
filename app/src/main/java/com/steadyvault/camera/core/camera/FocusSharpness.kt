package com.steadyvault.camera.core.camera

import android.graphics.Bitmap

/**
 * Medida de nitidez (variância do Laplaciano da luma) no quadro de análise, só para diagnóstico
 * por log: dá para ver se a região escolhida pelo foco inteligente está mais nítida (ou não) que
 * o resto do quadro, sem precisar do vídeo.
 */
internal object FocusSharpness {

    data class Result(val region: Float, val frame: Float)

    /**
     * [uprightX]/[uprightY]: alvo no espaço "em pé" (0..1), como o detector de pose entrega;
     * [rotationDegrees]: rotação que o bitmap precisa para ficar em pé (orientação do sensor).
     */
    fun measure(bitmap: Bitmap, uprightX: Float?, uprightY: Float?, rotationDegrees: Int): Result? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 16 || height < 16) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }

        // Alvo (espaço em pé) -> espaço do bitmap.
        var cx = -1f
        var cy = -1f
        if (uprightX != null && uprightY != null) {
            when (((rotationDegrees % 360) + 360) % 360) {
                90 -> { cx = uprightY; cy = 1f - uprightX }
                180 -> { cx = 1f - uprightX; cy = 1f - uprightY }
                270 -> { cx = 1f - uprightY; cy = uprightX }
                else -> { cx = uprightX; cy = uprightY }
            }
        }
        val halfW = (width * 0.12f).toInt().coerceAtLeast(6)
        val halfH = (height * 0.12f).toInt().coerceAtLeast(6)
        val x0 = ((cx * width).toInt() - halfW).coerceIn(1, width - 2)
        val x1 = ((cx * width).toInt() + halfW).coerceIn(1, width - 2)
        val y0 = ((cy * height).toInt() - halfH).coerceIn(1, height - 2)
        val y1 = ((cy * height).toInt() + halfH).coerceIn(1, height - 2)

        var sumAll = 0.0
        var sumSqAll = 0.0
        var nAll = 0
        var sumReg = 0.0
        var sumSqReg = 0.0
        var nReg = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val lap = 4f * luma[row + x] - luma[row + x - 1] - luma[row + x + 1] -
                    luma[row - width + x] - luma[row + width + x]
                sumAll += lap
                sumSqAll += lap * lap
                nAll++
                if (cx >= 0f && x in x0..x1 && y in y0..y1) {
                    sumReg += lap
                    sumSqReg += lap * lap
                    nReg++
                }
            }
        }
        if (nAll == 0) return null
        val frameVar = (sumSqAll / nAll - (sumAll / nAll) * (sumAll / nAll)).toFloat()
        val regionVar = if (nReg > 0) (sumSqReg / nReg - (sumReg / nReg) * (sumReg / nReg)).toFloat() else Float.NaN
        return Result(regionVar, frameVar)
    }
}
