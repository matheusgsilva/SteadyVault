package com.steadyvault.camera.capture.timing

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.util.Locale

object HighFpsCadenceValidator {
    data class Result(
        val samples: Int,
        val actualFps: Double,
        val largeGapRatio: Double,
        val shortBurstRatio: Double,
        val stable: Boolean
    ) {
        val label: String get() = String.format(Locale.getDefault(), "%.1f", actualFps)
    }

    fun inspect(file: File, targetFps: Int): Result? {
        if (!file.isFile || targetFps < 120) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(videoTrack)
            val pts = ArrayList<Long>(2048)
            while (extractor.sampleTrackIndex >= 0 && pts.size < MAX_SAMPLES) {
                extractor.sampleTime.takeIf { it >= 0L }?.let(pts::add)
                if (!extractor.advance()) break
            }
            if (pts.size < MIN_SAMPLES) return null
            pts.sort()
            val distinct = pts.distinct()
            if (distinct.size < MIN_SAMPLES) return null
            val spanUs = distinct.last() - distinct.first()
            if (spanUs < MIN_SPAN_US) return null
            val nominalUs = 1_000_000.0 / targetFps.toDouble()
            val deltas = distinct.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0.0 }
            if (deltas.isEmpty()) return null
            val actualFps = (distinct.size - 1).toDouble() * 1_000_000.0 / spanUs.toDouble()
            val largeGapRatio = deltas.count { it > nominalUs * LARGE_GAP_FACTOR }.toDouble() / deltas.size
            val shortBurstRatio = deltas.count { it < nominalUs * SHORT_BURST_FACTOR }.toDouble() / deltas.size
            val fpsRatio = actualFps / targetFps.toDouble()

            // High FPS precisa ser muito mais rigoroso que vídeo comum: poucos gaps de
            // 20 ms em 240 FPS já removem vários quadros e são claramente percebidos.
            // Também rejeite cadência em rajadas (ex.: 120 FPS entregue como blocos de
            // timestamps a 240 FPS seguidos de pausas), mesmo que a média fique perto de 120.
            val stable = fpsRatio in MIN_FPS_RATIO..MAX_FPS_RATIO &&
                largeGapRatio <= MAX_LARGE_GAP_RATIO && shortBurstRatio <= MAX_SHORT_BURST_RATIO
            Result(distinct.size, actualFps, largeGapRatio, shortBurstRatio, stable)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private const val MAX_SAMPLES = 6000
    private const val MIN_SAMPLES = 40
    private const val MIN_SPAN_US = 500_000L
    private const val LARGE_GAP_FACTOR = 1.50
    private const val SHORT_BURST_FACTOR = 0.72
    private const val MIN_FPS_RATIO = 0.985
    private const val MAX_FPS_RATIO = 1.015
    private const val MAX_LARGE_GAP_RATIO = 0.005
    private const val MAX_SHORT_BURST_RATIO = 0.02
}
