package com.steadyvault.camera.ui.gesture

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Análise leve de timestamps e carga de decodificação usada somente para ajustar o player. */
data class PlaybackProfile(
    val fps: Float,
    val width: Int,
    val height: Int,
    val bitrateMbps: Double,
    val cadenceScore: Int,
    val largeGapCount: Int,
    val jitterPercent: Int,
    val demandingDecode: Boolean,
    val cadenceUnstable: Boolean,
    val recommendedCacheMs: Int
) {
    fun summary(): String = when {
        cadenceUnstable -> "Cadência irregular detectada; reprodução adaptativa ativada"
        demandingDecode -> "Vídeo pesado detectado; player ajustado para alta taxa"
        else -> "Cadência estável; reprodução direta por hardware"
    }
}

object PlaybackIntelligence {
    fun analyze(context: Context, uri: Uri, fallbackFps: Float, fallbackWidth: Int, fallbackHeight: Int): PlaybackProfile {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme.equals("file", true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(requireNotNull(uri.path))
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var videoTrack = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = index
                    format = candidate
                    break
                }
            }
            if (videoTrack < 0 || format == null) return fallback(fallbackFps, fallbackWidth, fallbackHeight)
            val videoFormat = format

            val width = videoFormat.getIntegerOr(MediaFormat.KEY_WIDTH, fallbackWidth)
            val height = videoFormat.getIntegerOr(MediaFormat.KEY_HEIGHT, fallbackHeight)
            val formatFps = videoFormat.getIntegerOr(
                MediaFormat.KEY_FRAME_RATE,
                fallbackFps.takeIf { it > 0f }?.roundToInt() ?: 30
            ).toFloat().takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS } ?: fallbackFps.coerceAtLeast(1f)
            val bitrate = videoFormat.getIntegerOr(MediaFormat.KEY_BIT_RATE, 0)

            extractor.selectTrack(videoTrack)
            val presentationTimes = ArrayList<Long>(MAX_TIMESTAMP_SAMPLES)
            while (extractor.sampleTrackIndex >= 0 && presentationTimes.size < MAX_TIMESTAMP_SAMPLES) {
                val pts = extractor.sampleTime
                if (pts < 0L) break
                presentationTimes += pts
                if (!extractor.advance()) break
            }

            // PTS de HEVC com B-frames podem chegar em ordem de decodificação. Ordenar antes de
            // medir a cadência evita marcar um vídeo 120/240 FPS normal como instável.
            presentationTimes.sort()
            val deltas = ArrayList<Long>((presentationTimes.size - 1).coerceAtLeast(0))
            var duplicateTimestamps = 0
            for (index in 1 until presentationTimes.size) {
                val delta = presentationTimes[index] - presentationTimes[index - 1]
                if (delta > 0L) deltas += delta else duplicateTimestamps++
            }

            val medianDeltaUs = if (deltas.isNotEmpty()) {
                deltas.sorted()[deltas.size / 2].coerceAtLeast(1L)
            } else {
                (1_000_000.0 / formatFps.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
            }
            val measuredFps = (1_000_000.0 / medianDeltaUs.toDouble()).toFloat()
            val fps = measuredFps.takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS } ?: formatFps

            var squaredDeviation = 0.0
            var gaps = 0
            deltas.forEach { delta ->
                val difference = delta.toDouble() - medianDeltaUs.toDouble()
                squaredDeviation += difference * difference
                if (delta > medianDeltaUs * LARGE_GAP_NUMERATOR / LARGE_GAP_DENOMINATOR) gaps++
            }
            val deviation = if (deltas.size > 1) sqrt(squaredDeviation / (deltas.size - 1)) else 0.0
            val jitter = ((deviation / medianDeltaUs.toDouble()) * 100.0).roundToInt().coerceIn(0, 999)
            val anomalyPenalty = duplicateTimestamps.coerceAtMost(12) * 4
            val cadenceScore = (
                100 - gaps.coerceAtMost(25) * 3 - anomalyPenalty - (jitter / 2).coerceAtMost(40)
            ).coerceIn(0, 100)

            val durationUs = videoFormat.getLongOr(MediaFormat.KEY_DURATION, 0L)
            val fileBitrate = if (bitrate > 0) bitrate / 1_000_000.0 else {
                val path = uri.path
                if (!path.isNullOrBlank() && durationUs > 0) {
                    java.io.File(path).length() * 8.0 / durationUs
                } else {
                    0.0
                }
            }
            val load = width.toLong() * height.toLong() * fps.toDouble()
            val demanding = load >= 1920L * 1080L * 90.0 || fileBitrate >= 55.0
            val allowedDuplicates = max(2, presentationTimes.size / 500)
            val unstable = duplicateTimestamps > allowedDuplicates ||
                gaps > max(2, deltas.size / 500) || jitter >= 15 || cadenceScore < 86

            PlaybackProfile(
                fps = fps,
                width = width,
                height = height,
                bitrateMbps = fileBitrate,
                cadenceScore = cadenceScore,
                largeGapCount = gaps,
                jitterPercent = jitter,
                demandingDecode = demanding,
                cadenceUnstable = unstable,
                recommendedCacheMs = when {
                    fps >= 180f && unstable -> 2_000
                    fps >= 180f -> 1_500
                    demanding && unstable -> 2_000
                    demanding -> 1_500
                    unstable -> 1_200
                    else -> 700
                }
            )
        } catch (_: Throwable) {
            fallback(fallbackFps, fallbackWidth, fallbackHeight)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun fallback(fps: Float, width: Int, height: Int): PlaybackProfile {
        val safeFps = fps.takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS } ?: 30f
        val demanding = width.toLong() * height.toLong() * safeFps >= 1920L * 1080L * 90f
        return PlaybackProfile(
            safeFps,
            width,
            height,
            0.0,
            100,
            0,
            0,
            demanding,
            false,
            if (safeFps >= 180f || demanding) 1_500 else 700
        )
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        runCatching { if (containsKey(key)) getInteger(key) else fallback }.getOrDefault(fallback)

    private fun MediaFormat.getLongOr(key: String, fallback: Long): Long =
        runCatching { if (containsKey(key)) getLong(key) else fallback }.getOrDefault(fallback)

    private const val MIN_VALID_FPS = 1f
    private const val MAX_VALID_FPS = 250f
    private const val MAX_TIMESTAMP_SAMPLES = 720
    private const val LARGE_GAP_NUMERATOR = 17L
    private const val LARGE_GAP_DENOMINATOR = 10L
}
