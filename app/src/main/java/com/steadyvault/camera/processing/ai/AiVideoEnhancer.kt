package com.steadyvault.camera.processing.ai

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.model.FilterStrength
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.VideoFilterConfig
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Análise visual local baseada em métricas de quadros e uma pequena função de decisão.
 * Não é um modelo treinado em nuvem; nenhum quadro ou dado sai do aparelho.
 */
object AiVideoEnhancer {
    data class Report(
        val sampledFrames: Int,
        val confidence: Int,
        val blurProbability: Float,
        val noiseProbability: Float,
        val compressionProbability: Float,
        val underExposureProbability: Float,
        val overExposureProbability: Float,
        val yellowCastProbability: Float,
        val motionIntensity: Float,
        val improvementNeeded: Boolean,
        val filters: VideoFilterConfig,
        val preferredRepairMode: FrameRepairMode?,
        val reasons: List<String>
    ) {
        fun summary(): String = buildString {
            append("Análise visual local: ").append(confidence).append("% de confiança")
            append(" • desfoque ").append(percent(blurProbability))
            append(" • ruído ").append(percent(noiseProbability))
            append(" • compressão ").append(percent(compressionProbability))
            append(" • escuro ").append(percent(underExposureProbability))
            append(" • estouro ").append(percent(overExposureProbability))
            append(" • amarelado ").append(percent(yellowCastProbability))
            append(" • movimento ").append(percent(motionIntensity))
            append(if (improvementNeeded) " • melhoria recomendada" else " • nenhuma melhoria necessária")
        }

        private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"
    }

    private data class FrameFeatures(
        val brightness: Float,
        val contrast: Float,
        val sharpness: Float,
        val noise: Float,
        val blockiness: Float,
        val darkClipping: Float,
        val lightClipping: Float,
        val yellowCast: Float,
        val gray: FloatArray
    )

    fun analyze(
        file: File,
        analysis: VideoAnalysis,
        progress: ((Int, String) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): Report {
        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<FrameFeatures>()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationUs = analysis.durationUs.coerceAtLeast(1L)
            val count = when {
                durationUs < 3_000_000L -> 5
                durationUs < 20_000_000L -> 8
                else -> 12
            }
            for (index in 0 until count) {
                if (cancelled()) throw InterruptedException("Análise local cancelada")
                val fraction = (index + 1).toDouble() / (count + 1).toDouble()
                val timeUs = (durationUs * fraction).toLong()
                val bitmap = runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        SAMPLE_WIDTH,
                        SAMPLE_HEIGHT
                    )
                }.getOrNull() ?: continue
                try {
                    frames += extractFeatures(bitmap)
                } finally {
                    bitmap.recycle()
                }
                progress?.invoke(
                    2 + ((index + 1) * 5 / count),
                    "Análise local avaliando quadro ${index + 1}/$count"
                )
            }
        } finally {
            retriever.release()
        }
        require(frames.isNotEmpty()) { "A análise local não conseguiu extrair amostras do vídeo" }

        val meanBrightness = frames.map { it.brightness }.average().toFloat()
        val meanContrast = frames.map { it.contrast }.average().toFloat()
        val meanSharpness = frames.map { it.sharpness }.average().toFloat()
        val meanNoise = frames.map { it.noise }.average().toFloat()
        val meanBlockiness = frames.map { it.blockiness }.average().toFloat()
        val meanDark = frames.map { it.darkClipping }.average().toFloat()
        val meanLight = frames.map { it.lightClipping }.average().toFloat()
        val meanYellow = frames.map { it.yellowCast }.average().toFloat()
        val motion = if (frames.size > 1) {
            frames.zipWithNext().map { (a, b) -> frameDifference(a.gray, b.gray) }.average().toFloat()
        } else 0f

        val input = floatArrayOf(
            meanBrightness,
            meanContrast,
            meanSharpness,
            meanNoise,
            meanBlockiness,
            meanDark,
            meanLight,
            motion
        )
        val output = QualityNetwork.infer(input)
        val blur = output[0]
        val noise = output[1]
        val compression = output[2]
        val underExposure = output[3]
        val overExposure = output[4]
        val yellowCast = ((meanYellow - 0.035f) / 0.13f).coerceIn(0f, 1f)

        val denoise = when {
            noise >= 0.75f -> FilterStrength.MEDIUM
            noise >= 0.48f -> FilterStrength.LIGHT
            else -> FilterStrength.OFF
        }
        val deblock = when {
            compression >= 0.76f -> FilterStrength.MEDIUM
            compression >= 0.48f -> FilterStrength.LIGHT
            else -> FilterStrength.OFF
        }
        val sharpen = when {
            motion > 0.10f -> FilterStrength.OFF
            blur >= 0.80f && noise < 0.72f -> FilterStrength.MEDIUM
            blur >= 0.62f && noise < 0.82f -> FilterStrength.LIGHT
            else -> FilterStrength.OFF
        }
        val brightness = when {
            underExposure >= 0.78f -> 8
            underExposure >= 0.52f -> 4
            overExposure >= 0.78f -> -8
            overExposure >= 0.52f -> -4
            else -> 0
        }
        val contrast = when {
            meanContrast < 0.12f -> 108
            meanContrast > 0.34f -> 96
            else -> 100
        }
        val temperature = when {
            yellowCast >= 0.75f -> -22
            yellowCast >= 0.55f -> -14
            yellowCast >= 0.42f -> -8
            else -> 0
        }
        val missingRatio = analysis.estimatedMissingFrames.toDouble() /
            max(1, analysis.frameCount).toDouble()
        val maximumGapFrames = analysis.maximumFrameDeltaUs.toDouble() *
            analysis.estimatedFps.toDouble() / 1_000_000.0
        val safeForBlend = motion < 0.14f &&
            missingRatio <= 0.01 &&
            analysis.largeGapCount in 1..4 &&
            maximumGapFrames <= 2.4
        val preferredRepair = when {
            !analysis.hasCadenceProblems -> FrameRepairMode.NONE
            analysis.estimatedMissingFrames <= 0 -> FrameRepairMode.SMOOTH_TIMELINE
            safeForBlend -> FrameRepairMode.ADAPTIVE_BLEND
            else -> FrameRepairMode.FILL_MISSING_FRAMES
        }
        val decision = AiImprovementPolicy.decide(
            blur, noise, compression, underExposure, overExposure, yellowCast, motion, analysis.hasCadenceProblems
        )
        val reasons = buildList {
            add("Análise visual local avaliou ${frames.size} amostras; nenhum conteúdo saiu do aparelho")
            if (noise >= 0.48f) add("Ruído visual detectado; redução ${strengthLabel(denoise)} recomendada")
            if (compression >= 0.48f) add("Blocos de compressão detectados; deblock ${strengthLabel(deblock)} recomendado")
            if (sharpen != FilterStrength.OFF) add("Perda de detalhe detectada; nitidez ${strengthLabel(sharpen)} recomendada")
            if (brightness > 0) add("Vídeo escuro; correção moderada de brilho recomendada")
            if (brightness < 0) add("Áreas claras estouradas; redução moderada de brilho recomendada")
            if (temperature < 0) add("Dominante amarela detectada; temperatura ${temperature} recomendada para esfriar a imagem")
            if (analysis.hasCadenceProblems && !safeForBlend) {
                add("Mistura temporal foi evitada para reduzir rastros e artefatos")
            }
            if (!decision.needsImprovement) add("Cadência e imagem já estão dentro dos limites; recodificação automática não é necessária")
        }
        val sampleConfidence = (frames.size.toFloat() / 12f).coerceIn(0.35f, 1f)
        val cadenceConfidence = analysis.nominalFpsConfidence.coerceIn(0, 100) / 100f
        val confidence = (45f + sampleConfidence * 40f + cadenceConfidence * 15f).toInt().coerceIn(50, 100)
        return Report(
            sampledFrames = frames.size,
            confidence = confidence,
            blurProbability = blur,
            noiseProbability = noise,
            compressionProbability = compression,
            underExposureProbability = underExposure,
            overExposureProbability = overExposure,
            yellowCastProbability = yellowCast,
            motionIntensity = motion.coerceIn(0f, 1f),
            improvementNeeded = decision.needsImprovement,
            filters = if (decision.needsImprovement) {
                VideoFilterConfig(
                    denoise = denoise,
                    sharpen = sharpen,
                    deblock = deblock,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = 100,
                    temperature = temperature,
                    tint = 0
                )
            } else {
                VideoFilterConfig()
            },
            preferredRepairMode = preferredRepair,
            reasons = reasons
        )
    }

    private fun extractFeatures(bitmap: Bitmap): FrameFeatures {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = FloatArray(pixels.size)
        var sum = 0.0
        var sumRed = 0.0
        var sumGreen = 0.0
        var sumBlue = 0.0
        var dark = 0
        var light = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            val value = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            gray[index] = value
            sum += value
            sumRed += r / 255.0
            sumGreen += g / 255.0
            sumBlue += b / 255.0
            if (value < 0.055f) dark++
            if (value > 0.945f) light++
        }
        val mean = (sum / gray.size).toFloat()
        val meanRed = (sumRed / gray.size).toFloat()
        val meanGreen = (sumGreen / gray.size).toFloat()
        val meanBlue = (sumBlue / gray.size).toFloat()
        val darkRatio = dark.toFloat() / gray.size
        val yellowCast = (((meanRed + meanGreen) * 0.5f - meanBlue).coerceAtLeast(0f) * (1f - darkRatio * 0.65f)).coerceIn(0f, 1f)
        var variance = 0.0
        var gradient = 0.0
        var residual = 0.0
        var gradientCount = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val value = gray[index]
                variance += (value - mean) * (value - mean)
                val gx = abs(gray[index + 1] - gray[index - 1])
                val gy = abs(gray[index + width] - gray[index - width])
                gradient += gx + gy
                val localMean = (gray[index - 1] + gray[index + 1] + gray[index - width] + gray[index + width]) * 0.25f
                residual += abs(value - localMean)
                gradientCount++
            }
        }
        val contrast = sqrt(variance / max(1, gradientCount)).toFloat().coerceIn(0f, 1f)
        val sharpness = (gradient / max(1, gradientCount)).toFloat().coerceIn(0f, 1f)
        val noise = (residual / max(1, gradientCount)).toFloat().coerceIn(0f, 1f)
        val blockiness = estimateBlockiness(gray, width, height)
        return FrameFeatures(
            brightness = mean,
            contrast = contrast,
            sharpness = sharpness,
            noise = noise,
            blockiness = blockiness,
            darkClipping = darkRatio,
            lightClipping = light.toFloat() / gray.size,
            yellowCast = yellowCast,
            gray = gray
        )
    }

    private fun estimateBlockiness(gray: FloatArray, width: Int, height: Int): Float {
        var boundary = 0.0
        var normal = 0.0
        var boundaryCount = 0
        var normalCount = 0
        for (y in 0 until height) {
            for (x in 1 until width) {
                val difference = abs(gray[y * width + x] - gray[y * width + x - 1])
                if (x % 8 == 0) { boundary += difference; boundaryCount++ } else { normal += difference; normalCount++ }
            }
        }
        for (y in 1 until height) {
            for (x in 0 until width) {
                val difference = abs(gray[y * width + x] - gray[(y - 1) * width + x])
                if (y % 8 == 0) { boundary += difference; boundaryCount++ } else { normal += difference; normalCount++ }
            }
        }
        val boundaryAverage = boundary / max(1, boundaryCount)
        val normalAverage = normal / max(1, normalCount)
        return ((boundaryAverage - normalAverage) * 5.5).toFloat().coerceIn(0f, 1f)
    }

    private fun frameDifference(first: FloatArray, second: FloatArray): Float {
        val length = min(first.size, second.size)
        if (length == 0) return 0f
        var difference = 0.0
        for (index in 0 until length) difference += abs(first[index] - second[index])
        return (difference / length).toFloat().coerceIn(0f, 1f)
    }

    private fun strengthLabel(strength: FilterStrength): String = when (strength) {
        FilterStrength.OFF -> "desligada"
        FilterStrength.LIGHT -> "leve"
        FilterStrength.MEDIUM -> "média"
        FilterStrength.STRONG -> "forte"
    }

    /** Função heurística de duas camadas com pesos fixos, executada diretamente em Kotlin. */
    private object QualityNetwork {
        private val hiddenWeights = arrayOf(
            floatArrayOf(-1.2f, -0.8f, -4.8f, 0.8f, 0.4f, 0.5f, 0.2f, 0.4f),
            floatArrayOf(0.1f, 0.2f, -0.7f, 5.2f, 1.0f, 0.2f, 0.2f, 0.4f),
            floatArrayOf(0.0f, -0.3f, -0.4f, 1.1f, 5.5f, 0.1f, 0.1f, 0.2f),
            floatArrayOf(-4.6f, -0.2f, 0.1f, 0.2f, 0.0f, 4.8f, -0.5f, 0.0f),
            floatArrayOf(4.8f, 0.1f, 0.0f, 0.1f, 0.0f, -0.4f, 5.0f, 0.0f),
            floatArrayOf(0.0f, 0.2f, 0.2f, 0.1f, 0.1f, 0.0f, 0.0f, 5.0f)
        )
        private val hiddenBias = floatArrayOf(1.1f, -1.4f, -1.2f, 0.8f, -3.8f, -1.2f)
        private val outputWeights = arrayOf(
            floatArrayOf(4.0f, 0.2f, 0.2f, 0.1f, 0.0f, 0.2f),
            floatArrayOf(0.1f, 4.2f, 0.8f, 0.0f, 0.0f, 0.3f),
            floatArrayOf(0.1f, 0.8f, 4.3f, 0.0f, 0.0f, 0.1f),
            floatArrayOf(0.0f, 0.1f, 0.0f, 4.4f, -0.4f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, -0.3f, 4.5f, 0.0f)
        )
        private val outputBias = floatArrayOf(-2.2f, -2.2f, -2.2f, -2.3f, -2.4f)

        fun infer(input: FloatArray): FloatArray {
            val hidden = FloatArray(hiddenWeights.size)
            for (row in hiddenWeights.indices) {
                var value = hiddenBias[row]
                for (column in input.indices) value += hiddenWeights[row][column] * input[column]
                hidden[row] = sigmoid(value)
            }
            return FloatArray(outputWeights.size) { row ->
                var value = outputBias[row]
                for (column in hidden.indices) value += outputWeights[row][column] * hidden[column]
                sigmoid(value)
            }
        }

        private fun sigmoid(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
    }

    private const val SAMPLE_WIDTH = 64
    private const val SAMPLE_HEIGHT = 36
}
