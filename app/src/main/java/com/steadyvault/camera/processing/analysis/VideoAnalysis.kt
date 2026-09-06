package com.steadyvault.camera.processing.analysis

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VideoAnalysis(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    /** Distância real entre o primeiro e o último PTS do vídeo, sem duração de cauda do container. */
    val presentationSpanUs: Long,
    val frameCount: Int,
    /** FPS efetivo resolvido pela cadência. Nunca usa um harmônico enganoso do container como fonte de verdade. */
    val estimatedFps: Int,
    val nominalFpsConfidence: Int,
    val exactFps: Double,
    val sourceMime: String,
    val hasAudio: Boolean,
    val hdrHlg10: Boolean,
    val largeGapCount: Int,
    val duplicateTimestampCount: Int,
    val estimatedMissingFrames: Int,
    val minimumFrameDeltaUs: Long,
    val maximumFrameDeltaUs: Long,
    val frameJitterPercent: Int,
    val sourceBitrateMbps: Double,
    val cadenceScore: Int
) {
    val hasCadenceProblems: Boolean
        get() = largeGapCount > 0 || duplicateTimestampCount > 0 || frameJitterPercent >= 18

    val cadenceLabel: String
        get() = when {
            cadenceScore >= 92 -> "excelente"
            cadenceScore >= 78 -> "boa"
            cadenceScore >= 60 -> "irregular"
            else -> "instável"
        }

    companion object {
        private val FPS_CANDIDATES = intArrayOf(24, 25, 30, 48, 50, 60, 90, 120, 144, 240)

        fun read(file: File): VideoAnalysis {
            require(file.isFile && file.length() > 0L) { "Vídeo de origem inválido" }
            val metadata = readMetadata(file)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var videoTrack = -1
                var sourceMime = MediaFormat.MIMETYPE_VIDEO_AVC
                var hasAudio = false
                var hdrHlg10 = false
                var width = metadata.width
                var height = metadata.height
                var videoTrackDurationUs = 0L

                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    when {
                        videoTrack < 0 && mime.startsWith("video/") -> {
                            videoTrack = index
                            sourceMime = mime
                            if (width <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                            if (height <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                videoTrackDurationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                            }
                            val transfer = runCatching {
                                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                                } else 0
                            }.getOrDefault(0)
                            hdrHlg10 = transfer == MediaFormat.COLOR_TRANSFER_HLG
                        }
                        mime.startsWith("audio/") -> hasAudio = true
                    }
                }

                require(videoTrack >= 0) { "Faixa de vídeo não encontrada" }
                require(width > 0 && height > 0) { "Dimensões do vídeo não identificadas" }
                extractor.selectTrack(videoTrack)

                var frameCount = 0
                var firstPts = -1L
                var lastPts = -1L
                var previousPts = -1L
                var duplicateTimestampCount = 0
                var minimumDeltaUs = Long.MAX_VALUE
                var maximumDeltaUs = 0L
                var meanDelta = 0.0
                var deltaM2 = 0.0
                var deltaCount = 0
                val fpsCandidateCounts = IntArray(FPS_CANDIDATES.size)

                while (extractor.sampleTrackIndex >= 0) {
                    val pts = extractor.sampleTime
                    if (pts < 0L) break
                    if (firstPts < 0L) firstPts = pts
                    if (previousPts >= 0L) {
                        val delta = pts - previousPts
                        if (delta <= 0L) {
                            duplicateTimestampCount++
                        } else {
                            minimumDeltaUs = minOf(minimumDeltaUs, delta)
                            maximumDeltaUs = max(maximumDeltaUs, delta)
                            deltaCount++
                            val instantaneousFps = 1_000_000.0 / delta.toDouble()
                            var closestIndex = -1
                            var closestError = Double.MAX_VALUE
                            FPS_CANDIDATES.forEachIndexed { index, candidate ->
                                val error = abs(instantaneousFps - candidate.toDouble()) / candidate.toDouble()
                                if (error < closestError) {
                                    closestError = error
                                    closestIndex = index
                                }
                            }
                            if (closestIndex >= 0 && closestError <= 0.14) fpsCandidateCounts[closestIndex]++
                            val difference = delta - meanDelta
                            meanDelta += difference / deltaCount.toDouble()
                            deltaM2 += difference * (delta - meanDelta)
                        }
                    }
                    previousPts = pts
                    lastPts = pts
                    frameCount++
                    if (!extractor.advance()) break
                }

                require(frameCount > 0) { "O vídeo não possui quadros legíveis" }
                val spanUs = max(1L, lastPts - firstPts)
                val exactFps = if (frameCount > 1) {
                    (frameCount - 1L) * 1_000_000.0 / spanUs.toDouble()
                } else 30.0

                val bestCandidateIndex = fpsCandidateCounts.indices.maxByOrNull { fpsCandidateCounts[it] } ?: -1
                val bestCandidateCount = bestCandidateIndex.takeIf { it >= 0 }?.let { fpsCandidateCounts[it] } ?: 0
                val histogramConfidence = if (deltaCount > 0) {
                    (bestCandidateCount * 100 / deltaCount).coerceIn(0, 100)
                } else 0

                // Fonte principal: quantidade de quadros / span real. Isso evita o falso 120 FPS
                // que alguns parsers inferem quando uma gravação de 60 FPS possui deltas de
                // 25/33 ms: o GCD dos timestamps pode sugerir 120 mesmo sem haver 120 quadros/s.
                val exactCandidateIndex = FPS_CANDIDATES.indices.minByOrNull { index ->
                    abs(exactFps - FPS_CANDIDATES[index].toDouble()) / FPS_CANDIDATES[index].toDouble()
                } ?: -1
                val exactCandidate = exactCandidateIndex.takeIf { it >= 0 }?.let { FPS_CANDIDATES[it] }
                val exactCandidateError = exactCandidate?.let {
                    abs(exactFps - it.toDouble()) / it.toDouble()
                } ?: Double.MAX_VALUE

                val estimatedFps = when {
                    // Até 12% de perda de quadros ainda preserva a intenção nominal de captura.
                    // 59.x -> 60, 118.x -> 120 e ~227 -> 240, sem promover 60 -> 120.
                    exactCandidate != null && exactCandidateError <= 0.12 -> exactCandidate
                    bestCandidateCount >= 4 && histogramConfidence >= 30 -> FPS_CANDIDATES[bestCandidateIndex]
                    else -> exactFps.roundToInt().coerceIn(1, 240)
                }
                val exactConfidence = if (exactCandidate != null) {
                    (100.0 - exactCandidateError * 500.0).roundToInt().coerceIn(0, 100)
                } else 0
                val nominalFpsConfidence = maxOf(histogramConfidence, exactConfidence)

                val nominalDeltaUs = (1_000_000.0 / estimatedFps.toDouble()).roundToInt().toLong().coerceAtLeast(1L)
                val durationUs = when {
                    videoTrackDurationUs > 0L -> videoTrackDurationUs
                    metadata.durationUs > spanUs -> metadata.durationUs
                    else -> spanUs + nominalDeltaUs
                }

                val gaps = countGaps(file, videoTrack, nominalDeltaUs)
                val largeGapCount = gaps.first
                val estimatedMissingFrames = gaps.second

                val standardDeviation = if (deltaCount > 1) sqrt(deltaM2 / (deltaCount - 1).toDouble()) else 0.0
                val frameJitterPercent = ((standardDeviation / nominalDeltaUs.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 999)
                val sourceBitrateMbps = if (durationUs > 0L) {
                    file.length().toDouble() * 8.0 / durationUs.toDouble()
                } else 0.0
                val cadencePenalty = largeGapCount.coerceAtMost(30) * 2 +
                    duplicateTimestampCount.coerceAtMost(20) * 3 +
                    (frameJitterPercent / 2).coerceAtMost(35)
                val cadenceScore = (100 - cadencePenalty).coerceIn(0, 100)

                return VideoAnalysis(
                    width = width,
                    height = height,
                    rotation = metadata.rotation,
                    durationUs = durationUs,
                    presentationSpanUs = spanUs,
                    frameCount = frameCount,
                    estimatedFps = estimatedFps,
                    nominalFpsConfidence = nominalFpsConfidence,
                    exactFps = exactFps,
                    sourceMime = sourceMime,
                    hasAudio = hasAudio,
                    hdrHlg10 = hdrHlg10,
                    largeGapCount = largeGapCount,
                    duplicateTimestampCount = duplicateTimestampCount,
                    estimatedMissingFrames = estimatedMissingFrames,
                    minimumFrameDeltaUs = minimumDeltaUs.takeUnless { it == Long.MAX_VALUE } ?: nominalDeltaUs,
                    maximumFrameDeltaUs = maximumDeltaUs.takeIf { it > 0L } ?: nominalDeltaUs,
                    frameJitterPercent = frameJitterPercent,
                    sourceBitrateMbps = sourceBitrateMbps,
                    cadenceScore = cadenceScore
                )
            } finally {
                extractor.release()
            }
        }

        private fun countGaps(file: File, videoTrack: Int, nominalDeltaUs: Long): Pair<Int, Int> {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.selectTrack(videoTrack)
                var previousPts = -1L
                var largeGapCount = 0
                var estimatedMissingFrames = 0
                while (extractor.sampleTrackIndex >= 0) {
                    val pts = extractor.sampleTime
                    if (pts < 0L) break
                    if (previousPts >= 0L) {
                        val delta = pts - previousPts
                        if (delta > nominalDeltaUs * 3L / 2L) {
                            largeGapCount++
                            estimatedMissingFrames += ((delta + nominalDeltaUs / 2L) / nominalDeltaUs - 1L)
                                .coerceIn(1L, 240L)
                                .toInt()
                        }
                    }
                    previousPts = pts
                    if (!extractor.advance()) break
                }
                return largeGapCount to estimatedMissingFrames
            } finally {
                extractor.release()
            }
        }

        private fun readMetadata(file: File): Metadata {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                Metadata(
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000L
                )
            } finally {
                retriever.release()
            }
        }

        private data class Metadata(val width: Int, val height: Int, val rotation: Int, val durationUs: Long)
    }
}
