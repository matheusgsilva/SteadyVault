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
 * Fluxo óptico bidirecional entre dois quadros vizinhos.
 *
 * Mantemos campos independentes A->B e B->A. Cada campo carrega sua própria
 * confiança forward/backward para o shader poder decidir qual lado é mais seguro
 * em bordas de oclusão, em vez de reduzir tudo a um único vetor médio.
 */
object OpenCvMotionEstimator {
    data class Field(
        val forwardRgba: ByteArray,
        val backwardRgba: ByteArray,
        val flowScaleX: Float,
        val flowScaleY: Float,
        val meanConfidence: Float,
        val meanMotionPixels: Float,
        val globalReliability: Float,
        val sceneChangeLikely: Boolean,
        val globalForwardUvX: Float,
        val globalForwardUvY: Float,
        val globalBackwardUvX: Float,
        val globalBackwardUvY: Float,
        val globalRotationRadians: Float,
        val globalRotationReliability: Float,
        val motionIsNearlyStatic: Boolean,
        val globalMotionIsUnstable: Boolean,
        val localWarpSafe: Boolean
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

            val levels = if (highQuality) 8 else 4
            val window = if (highQuality) 39 else 21
            val iterations = if (highQuality) 10 else 3
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

            // A mediana do fluxo denso pode ficar próxima de zero quando a cena
            // possui grandes áreas sem textura. A correlação de fase fornece uma
            // translação global independente do Farneback e funciona bem como
            // fallback rígido para panorâmicas e saltos de câmera.
            val previousFloat = Mat()
            val currentFloat = Mat()
            previousGray.convertTo(previousFloat, CvType.CV_32F)
            currentGray.convertTo(currentFloat, CvType.CV_32F)
            val phaseResponse = doubleArrayOf(0.0)
            val phaseShift = Imgproc.phaseCorrelate(
                previousFloat,
                currentFloat,
                Mat(),
                phaseResponse
            )
            previousFloat.release()
            currentFloat.release()

            val phaseForwardX = phaseShift.x.toFloat()
            val phaseForwardY = phaseShift.y.toFloat()
            val phaseReliability = phaseResponse[0].toFloat().coerceIn(0f, 1f)

            val forwardData = FloatArray(pixels * 2)
            val backwardData = FloatArray(pixels * 2)
            val previousLuma = ByteArray(pixels)
            val currentLuma = ByteArray(pixels)
            forward.get(0, 0, forwardData)
            backward.get(0, 0, backwardData)
            previousGray.get(0, 0, previousLuma)
            currentGray.get(0, 0, currentLuma)

            // Em 4K o campo é calculado em resolução reduzida. 32 px no mapa de
            // análise representa deslocamento bem maior na imagem final e evita
            // cortar panorâmicas rápidas.
            val maxFlow = if (highQuality) 32f else 14f
            val forwardEncoded = ByteArray(pixels * 4)
            val backwardEncoded = ByteArray(pixels * 4)
            var confidenceSum = 0.0
            var motionSum = 0.0
            var photoDifferenceSum = 0.0
            var lowConfidencePixels = 0
            var forwardWeightedX = 0.0
            var forwardWeightedY = 0.0
            var forwardWeight = 0.0
            var backwardWeightedX = 0.0
            var backwardWeightedY = 0.0
            var backwardWeight = 0.0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val fdxRaw = forwardData[index * 2]
                    val fdyRaw = forwardData[index * 2 + 1]
                    val bdxRaw = backwardData[index * 2]
                    val bdyRaw = backwardData[index * 2 + 1]
                    val fdx = fdxRaw.coerceIn(-maxFlow, maxFlow)
                    val fdy = fdyRaw.coerceIn(-maxFlow, maxFlow)
                    val bdx = bdxRaw.coerceIn(-maxFlow, maxFlow)
                    val bdy = bdyRaw.coerceIn(-maxFlow, maxFlow)

                    val forwardConfidence = directionalConfidence(
                        primaryDx = fdx,
                        primaryDy = fdy,
                        opposite = backwardData,
                        oppositeLuma = currentLuma,
                        sourceLuma = previousLuma,
                        sourceValue = previousLuma[index].toInt() and 0xff,
                        width = width,
                        height = height,
                        x = x,
                        y = y,
                        highQuality = highQuality,
                        maxFlow = maxFlow,
                        rawDx = fdxRaw,
                        rawDy = fdyRaw
                    )
                    val backwardConfidence = directionalConfidence(
                        primaryDx = bdx,
                        primaryDy = bdy,
                        opposite = forwardData,
                        oppositeLuma = previousLuma,
                        sourceLuma = currentLuma,
                        sourceValue = currentLuma[index].toInt() and 0xff,
                        width = width,
                        height = height,
                        x = x,
                        y = y,
                        highQuality = highQuality,
                        maxFlow = maxFlow,
                        rawDx = bdxRaw,
                        rawDy = bdyRaw
                    )

                    val pairConfidence = (forwardConfidence + backwardConfidence) * 0.5f
                    confidenceSum += pairConfidence.toDouble()
                    if (pairConfidence < 0.22f) lowConfidencePixels++
                    val previousValue = previousLuma[index].toInt() and 0xff
                    val currentValue = currentLuma[index].toInt() and 0xff
                    photoDifferenceSum += abs(previousValue - currentValue).toDouble() / 255.0

                    // Movimento global robusto: só regiões com confiança razoável
                    // contribuem. Serve de fallback para áreas borradas/sem textura.
                    if (forwardConfidence > 0.28f) {
                        val w = forwardConfidence.toDouble() * forwardConfidence.toDouble()
                        forwardWeightedX += fdx.toDouble() * w
                        forwardWeightedY += fdy.toDouble() * w
                        forwardWeight += w
                    }
                    if (backwardConfidence > 0.28f) {
                        val w = backwardConfidence.toDouble() * backwardConfidence.toDouble()
                        backwardWeightedX += bdx.toDouble() * w
                        backwardWeightedY += bdy.toDouble() * w
                        backwardWeight += w
                    }

                    motionSum += (
                        hypot(fdx.toDouble(), fdy.toDouble()) +
                            hypot(bdx.toDouble(), bdy.toDouble())
                        ) * 0.5

                    encodeVector(forwardEncoded, index, fdx, fdy, forwardConfidence, maxFlow)
                    encodeVector(backwardEncoded, index, bdx, bdy, backwardConfidence, maxFlow)
                }
            }

            val meanConfidence = (confidenceSum / pixels.coerceAtLeast(1)).toFloat()
            val meanPhotoDifference = (photoDifferenceSum / pixels.coerceAtLeast(1)).toFloat()
            val lowConfidenceRatio = lowConfidencePixels.toFloat() / pixels.coerceAtLeast(1).toFloat()
            // Um corte real costuma combinar diferença fotométrica alta com fluxo
            // inconsistente em grande parte da imagem. Não tratamos panorâmica rápida
            // como corte apenas por haver movimento.
            val sceneChangeLikely =
                meanPhotoDifference > 0.34f && meanConfidence < 0.24f && lowConfidenceRatio > 0.58f
            var globalReliability = if (sceneChangeLikely) {
                0f
            } else {
                (meanConfidence * (1f - lowConfidenceRatio * 0.45f)).coerceIn(0.18f, 1f)
            }

            val meanMotionPixels = (motionSum / pixels.coerceAtLeast(1).toDouble()).toFloat()

            // Para movimento global, mediana por histograma é muito mais robusta
            // que média ponderada: um objeto rápido ou vetor errado não arrasta a
            // cena inteira e não cria um frame sintético "torto".
            val medianForwardX = robustMedianComponent(forwardData, forwardEncoded, 0, maxFlow)
            val medianForwardY = robustMedianComponent(forwardData, forwardEncoded, 1, maxFlow)
            val medianBackwardX = robustMedianComponent(backwardData, backwardEncoded, 0, maxFlow)
            val medianBackwardY = robustMedianComponent(backwardData, backwardEncoded, 1, maxFlow)

            val medianMagnitude = hypot(
                medianForwardX.toDouble(),
                medianForwardY.toDouble()
            ).toFloat()
            val phaseMagnitude = hypot(
                phaseForwardX.toDouble(),
                phaseForwardY.toDouble()
            ).toFloat()

            val usePhaseGlobal =
                !sceneChangeLikely &&
                    phaseReliability >= PHASE_MIN_RELIABILITY &&
                    phaseMagnitude >= PHASE_MIN_MOTION_PX &&
                    (
                        medianMagnitude < PHASE_MEDIAN_NEAR_ZERO_PX ||
                            phaseMagnitude > medianMagnitude * PHASE_DOMINANCE_RATIO
                    )

            val globalForwardX = if (usePhaseGlobal) phaseForwardX else medianForwardX
            val globalForwardY = if (usePhaseGlobal) phaseForwardY else medianForwardY
            val globalBackwardX = if (usePhaseGlobal) -phaseForwardX else medianBackwardX
            val globalBackwardY = if (usePhaseGlobal) -phaseForwardY else medianBackwardY
            val globalForwardMagnitude = hypot(globalForwardX.toDouble(), globalForwardY.toDouble()).toFloat()
            val globalBackwardMagnitude = hypot(globalBackwardX.toDouble(), globalBackwardY.toDouble()).toFloat()
            if (usePhaseGlobal) {
                globalReliability = maxOf(
                    globalReliability,
                    (0.45f + phaseReliability * 0.5f).coerceAtMost(0.98f)
                )
            }
            val rotationEstimate = estimateRotationRadians(
                flow = forwardData,
                encoded = forwardEncoded,
                width = width,
                height = height,
                translationX = globalForwardX,
                translationY = globalForwardY,
                maxFlow = maxFlow
            )
            val motionIsNearlyStatic = meanMotionPixels < 0.45f &&
                globalForwardMagnitude < 0.35f &&
                globalBackwardMagnitude < 0.35f
            val directionDot = globalForwardX * -globalBackwardX + globalForwardY * -globalBackwardY
            val directionDen = (globalForwardMagnitude * globalBackwardMagnitude).coerceAtLeast(0.0001f)
            val directionAgreement = directionDot / directionDen
            val globalMotionIsUnstable =
                !motionIsNearlyStatic &&
                globalForwardMagnitude > 0.2f &&
                globalBackwardMagnitude > 0.2f &&
                directionAgreement < 0.25f

            // Warp denso só é permitido quando o campo é realmente estável.
            // Se não passar, o shader usa apenas movimento global rígido, evitando
            // deformações locais que aparecem como "frame bugado".
            val localWarpSafe =
                !usePhaseGlobal &&
                !sceneChangeLikely &&
                !globalMotionIsUnstable &&
                meanConfidence >= 0.40f &&
                lowConfidenceRatio <= 0.38f &&
                directionAgreement >= 0.45f

            return Field(
                forwardRgba = forwardEncoded,
                backwardRgba = backwardEncoded,
                flowScaleX = maxFlow / width.toFloat(),
                flowScaleY = maxFlow / height.toFloat(),
                meanConfidence = meanConfidence,
                meanMotionPixels = meanMotionPixels,
                globalReliability = globalReliability,
                sceneChangeLikely = sceneChangeLikely,
                globalForwardUvX = globalForwardX / width.toFloat(),
                globalForwardUvY = globalForwardY / height.toFloat(),
                globalBackwardUvX = globalBackwardX / width.toFloat(),
                globalBackwardUvY = globalBackwardY / height.toFloat(),
                globalRotationRadians = rotationEstimate.first,
                globalRotationReliability = rotationEstimate.second,
                motionIsNearlyStatic = motionIsNearlyStatic,
                globalMotionIsUnstable = globalMotionIsUnstable,
                localWarpSafe = localWarpSafe
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

    private fun robustMedianComponent(
        flow: FloatArray,
        encoded: ByteArray,
        channel: Int,
        maxFlow: Float
    ): Float {
        val bins = IntArray(ROBUST_FLOW_BINS)
        var accepted = 0
        val pixels = encoded.size / 4
        for (index in 0 until pixels) {
            val confidence = (encoded[index * 4 + 2].toInt() and 0xff) / 255f
            if (confidence < ROBUST_GLOBAL_CONFIDENCE) continue
            val value = flow[index * 2 + channel].coerceIn(-maxFlow, maxFlow)
            val normalized = ((value + maxFlow) / (2f * maxFlow)).coerceIn(0f, 1f)
            val bin = (normalized * (ROBUST_FLOW_BINS - 1)).roundToInt()
            bins[bin]++
            accepted++
        }
        if (accepted < (pixels * 0.03f).toInt().coerceAtLeast(32)) return 0f
        val target = (accepted + 1) / 2
        var cumulative = 0
        for (bin in bins.indices) {
            cumulative += bins[bin]
            if (cumulative >= target) {
                val normalized = bin.toFloat() / (ROBUST_FLOW_BINS - 1).toFloat()
                return normalized * (2f * maxFlow) - maxFlow
            }
        }
        return 0f
    }

    private fun estimateRotationRadians(
        flow: FloatArray,
        encoded: ByteArray,
        width: Int,
        height: Int,
        translationX: Float,
        translationY: Float,
        maxFlow: Float
    ): Pair<Float, Float> {
        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        var numerator = 0.0
        var denominator = 0.0
        var acceptedWeight = 0.0
        var accepted = 0

        val step = if (width * height > 250_000) 2 else 1
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                val confidence = (encoded[index * 4 + 2].toInt() and 0xff) / 255f
                if (confidence >= ROBUST_ROTATION_CONFIDENCE) {
                    val dx = flow[index * 2].coerceIn(-maxFlow, maxFlow) - translationX
                    val dy = flow[index * 2 + 1].coerceIn(-maxFlow, maxFlow) - translationY
                    val cx = x - centerX
                    val cy = y - centerY
                    val radius2 = cx * cx + cy * cy
                    if (radius2 > 16f) {
                        val w = confidence.toDouble() * confidence.toDouble()
                        numerator += (cx.toDouble() * dy.toDouble() - cy.toDouble() * dx.toDouble()) * w
                        denominator += radius2.toDouble() * w
                        acceptedWeight += w
                        accepted++
                    }
                }
                x += step
            }
            y += step
        }

        if (accepted < 48 || denominator <= 1e-6) return 0f to 0f
        val radians = (numerator / denominator).toFloat().coerceIn(-MAX_GLOBAL_ROTATION_RAD, MAX_GLOBAL_ROTATION_RAD)
        val sampled = ((width + step - 1) / step) * ((height + step - 1) / step)
        val coverage = (accepted.toFloat() / sampled.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val meanWeight = (acceptedWeight / accepted.coerceAtLeast(1).toDouble()).toFloat().coerceIn(0f, 1f)
        val reliability = (coverage * 1.8f * meanWeight).coerceIn(0f, 1f)
        return radians to reliability
    }

    private fun directionalConfidence(
        primaryDx: Float,
        primaryDy: Float,
        opposite: FloatArray,
        oppositeLuma: ByteArray,
        sourceLuma: ByteArray,
        sourceValue: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        highQuality: Boolean,
        maxFlow: Float,
        rawDx: Float,
        rawDy: Float
    ): Float {
        val targetX = x + primaryDx
        val targetY = y + primaryDy
        val oppositeDx = sampleFlow(opposite, width, height, targetX, targetY, 0)
        val oppositeDy = sampleFlow(opposite, width, height, targetX, targetY, 1)
        val consistencyError = hypot(
            (primaryDx + oppositeDx).toDouble(),
            (primaryDy + oppositeDy).toDouble()
        ).toFloat()
        val consistencyConfidence = exp(
            (-consistencyError / if (highQuality) 2.0f else 2.5f).toDouble()
        ).toFloat()

        val targetValue = sampleGray(oppositeLuma, width, height, targetX, targetY)
        val photoError = abs(sourceValue.toFloat() - targetValue) / 255f
        val photoConfidence = (1f - photoError * 2.0f).coerceIn(0f, 1f)

        val inside = targetX >= 0.5f && targetX <= width - 1.5f &&
            targetY >= 0.5f && targetY <= height - 1.5f
        val clipped = abs(rawDx) > maxFlow || abs(rawDy) > maxFlow
        val boundaryPenalty = when {
            !inside -> 0.25f
            clipped -> 0.45f
            else -> 1f
        }

        // Consistência bidirecional domina. Fotometria ajuda a detectar oclusões
        // e objetos revelados entre A e B.
        return (
            (consistencyConfidence * 0.82f + photoConfidence * 0.18f) * boundaryPenalty
            ).coerceIn(0f, 1f)
    }

    private fun encodeVector(
        target: ByteArray,
        index: Int,
        dx: Float,
        dy: Float,
        confidence: Float,
        maximum: Float
    ) {
        val out = index * 4
        target[out] = encodeFlow(dx, maximum)
        target[out + 1] = encodeFlow(dy, maximum)
        // Confiança real, sem piso artificial. O shader possui fallback temporal
        // contínuo quando o fluxo não é confiável.
        target[out + 2] = (confidence.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()
        target[out + 3] = 0xff.toByte()
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
    private const val ROBUST_FLOW_BINS = 257
    private const val ROBUST_GLOBAL_CONFIDENCE = 0.38f
    private const val ROBUST_ROTATION_CONFIDENCE = 0.46f
    private const val MAX_GLOBAL_ROTATION_RAD = 0.06f
    private const val PHASE_MIN_RELIABILITY = 0.18f
    private const val PHASE_MIN_MOTION_PX = 0.75f
    private const val PHASE_MEDIAN_NEAR_ZERO_PX = 0.9f
    private const val PHASE_DOMINANCE_RATIO = 1.65f

}
