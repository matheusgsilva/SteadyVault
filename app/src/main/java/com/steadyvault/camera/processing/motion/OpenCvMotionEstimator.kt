package com.steadyvault.camera.processing.motion

import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Fluxo óptico local entre dois quadros vizinhos.
 *
 * A entrada vem de um downsample feito pela GPU. Farneback calcula movimento nos
 * dois sentidos e a confiança usa consistência forward/backward + erro fotométrico.
 * O resultado volta como textura RGBA compacta para o shader fazer o warp em 4K.
 */
object OpenCvMotionEstimator {
    data class Field(
        val rgba: ByteArray,
        val flowScaleX: Float,
        val flowScaleY: Float,
        val meanConfidence: Float
    )

    @Volatile
    private var loadState: Boolean? = null

    @Synchronized
    private fun ensureLoaded() {
        loadState?.let { loaded ->
            check(loaded) { "OpenCV não pôde ser carregado no aparelho" }
            return
        }
        val loaded = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        loadState = loaded
        check(loaded) { "OpenCV não pôde ser carregado no aparelho" }
    }

    fun estimate(
        previousRgba: ByteArray,
        currentRgba: ByteArray,
        width: Int,
        height: Int,
        highQuality: Boolean
    ): Field {
        require(width > 8 && height > 8) { "Resolução insuficiente para fluxo óptico" }
        val pixels = width * height
        require(previousRgba.size == pixels * 4 && currentRgba.size == pixels * 4) {
            "Quadros de análise inválidos"
        }
        ensureLoaded()

        val previous = Mat(height, width, CvType.CV_8UC4)
        val current = Mat(height, width, CvType.CV_8UC4)
        val previousGray = Mat()
        val currentGray = Mat()
        val forward = Mat()
        val backward = Mat()
        try {
            previous.put(0, 0, previousRgba)
            current.put(0, 0, currentRgba)
            Imgproc.cvtColor(previous, previousGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(current, currentGray, Imgproc.COLOR_RGBA2GRAY)

            val levels = if (highQuality) 5 else 4
            val window = if (highQuality) 25 else 21
            val iterations = if (highQuality) 5 else 3
            val polyN = if (highQuality) 7 else 5
            val polySigma = if (highQuality) 1.5 else 1.2
            Video.calcOpticalFlowFarneback(
                previousGray, currentGray, forward,
                0.5, levels, window, iterations, polyN, polySigma, 0
            )
            Video.calcOpticalFlowFarneback(
                currentGray, previousGray, backward,
                0.5, levels, window, iterations, polyN, polySigma, 0
            )

            val forwardData = FloatArray(pixels * 2)
            val backwardData = FloatArray(pixels * 2)
            val previousLuma = ByteArray(pixels)
            val currentLuma = ByteArray(pixels)
            forward.get(0, 0, forwardData)
            backward.get(0, 0, backwardData)
            previousGray.get(0, 0, previousLuma)
            currentGray.get(0, 0, currentLuma)

            val maxFlow = if (highQuality) 14f else 10f
            val encoded = ByteArray(pixels * 4)
            var confidenceSum = 0.0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val rawDx = forwardData[index * 2]
                    val rawDy = forwardData[index * 2 + 1]
                    val dx = rawDx.coerceIn(-maxFlow, maxFlow)
                    val dy = rawDy.coerceIn(-maxFlow, maxFlow)
                    val targetX = x + dx
                    val targetY = y + dy
                    val backDx = sampleFlow(backwardData, width, height, targetX, targetY, 0)
                    val backDy = sampleFlow(backwardData, width, height, targetX, targetY, 1)
                    val consistencyError = hypot((dx + backDx).toDouble(), (dy + backDy).toDouble()).toFloat()
                    val previousValue = previousLuma[index].toInt() and 0xff
                    val currentValue = sampleGray(currentLuma, width, height, targetX, targetY)
                    val photoError = abs(previousValue.toFloat() - currentValue) / 255f

                    val consistencyConfidence = exp((-consistencyError / if (highQuality) 1.7f else 2.1f).toDouble()).toFloat()
                    val photoConfidence = (1f - photoError * 2.4f).coerceIn(0f, 1f)
                    val clipped = abs(rawDx) > maxFlow || abs(rawDy) > maxFlow
                    val boundaryPenalty = if (clipped) 0.45f else 1f
                    val confidence = ((consistencyConfidence * 0.72f + photoConfidence * 0.28f) * boundaryPenalty)
                        .coerceIn(0f, 1f)
                    confidenceSum += confidence.toDouble()

                    val out = index * 4
                    encoded[out] = encodeFlow(dx, maxFlow)
                    encoded[out + 1] = encodeFlow(dy, maxFlow)
                    encoded[out + 2] = (confidence * 255f).roundToInt().coerceIn(0, 255).toByte()
                    encoded[out + 3] = 0xff.toByte()
                }
            }
            return Field(
                rgba = encoded,
                flowScaleX = maxFlow / width.toFloat(),
                flowScaleY = maxFlow / height.toFloat(),
                meanConfidence = (confidenceSum / pixels.coerceAtLeast(1).toDouble()).toFloat()
            )
        } finally {
            previous.release()
            current.release()
            previousGray.release()
            currentGray.release()
            forward.release()
            backward.release()
        }
    }

    private fun encodeFlow(value: Float, maximum: Float): Byte {
        val encoded = (((value / maximum).coerceIn(-1f, 1f) * 0.5f + 0.5f) * 255f)
            .roundToInt().coerceIn(0, 255)
        return encoded.toByte()
    }

    private fun sampleFlow(
        data: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        channel: Int
    ): Float {
        val safeX = x.coerceIn(0f, (width - 1).toFloat())
        val safeY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(safeX.toDouble()).toInt()
        val y0 = floor(safeY.toDouble()).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        fun at(px: Int, py: Int): Float = data[(py * width + px) * 2 + channel]
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return top + (bottom - top) * fy
    }

    private fun sampleGray(data: ByteArray, width: Int, height: Int, x: Float, y: Float): Float {
        val safeX = x.coerceIn(0f, (width - 1).toFloat())
        val safeY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(safeX.toDouble()).toInt()
        val y0 = floor(safeY.toDouble()).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        fun at(px: Int, py: Int): Float = (data[py * width + px].toInt() and 0xff).toFloat()
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return top + (bottom - top) * fy
    }
}
