package com.steadyvault.camera.capture.recorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.max

/**
 * Corrige somente o defeito de timestamp observado no constrained 120 FPS do S25:
 * os quadros chegam visualmente em cadencia uniforme, mas o MediaRecorder grava PTS
 * em lotes (~4/4/4/21 ms). Nao decodifica, nao cria quadros e nao altera o reparo.
 */
object HighSpeedTimestampNormalizer {
    private const val TARGET_FPS = 120
    private const val PROBE_SAMPLES = 192
    private const val MIN_PROBE_INTERVALS = 40
    private const val FALLBACK_BUFFER_BYTES = 16 * 1024 * 1024

    fun normalize120IfBatched(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L || !has120BatchingSignature(file)) return false
        val parent = file.parentFile ?: return false
        val temp = File(parent, ".${file.nameWithoutExtension}.cfr120-${System.nanoTime()}.mp4")
        if (!remuxAsFixed120(file, temp)) {
            runCatching { temp.delete() }
            return false
        }
        if (!hasUsableVideo(temp)) {
            runCatching { temp.delete() }
            return false
        }
        return replaceOriginal(temp, file)
    }

    private fun has120BatchingSignature(file: File): Boolean {
        val times = videoSampleTimes(file, PROBE_SAMPLES)
        if (times.size <= MIN_PROBE_INTERVALS) return false
        val expectedUs = 1_000_000.0 / TARGET_FPS.toDouble()
        val deltas = times.zipWithNext { a, b -> b - a }.filter { it > 0L }
        if (deltas.size < MIN_PROBE_INTERVALS) return false

        val short = deltas.count { it >= expectedUs * 0.35 && it <= expectedUs * 0.75 }
        val long = deltas.count { it >= expectedUs * 1.9 && it <= expectedUs * 2.8 }
        val meanUs = (times.last() - times.first()).toDouble() / (times.size - 1).toDouble()
        val meanError = abs(meanUs - expectedUs) / expectedUs
        return short.toDouble() / deltas.size >= 0.55 &&
            long.toDouble() / deltas.size >= 0.15 &&
            meanError <= 0.08
    }

    private fun videoSampleTimes(file: File, maxSamples: Int): List<Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: return emptyList()
            extractor.selectTrack(videoTrack)
            buildList {
                while (size < maxSamples) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0L) break
                    add(timeUs)
                    if (!extractor.advance()) break
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun remuxAsFixed120(source: File, target: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(source.absolutePath)
            if (extractor.trackCount <= 0) return false

            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val videoTrack = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            if (videoTrack < 0) return false

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val rotation = runCatching {
                formats[videoTrack].takeIf { it.containsKey(MediaFormat.KEY_ROTATION) }
                    ?.getInteger(MediaFormat.KEY_ROTATION)
            }.getOrNull()
            if (rotation in setOf(0, 90, 180, 270) && rotation != 0) muxer.setOrientationHint(rotation!!)

            val outputTracks = IntArray(extractor.trackCount)
            var maxInputSize = FALLBACK_BUFFER_BYTES
            formats.forEachIndexed { index, format ->
                outputTracks[index] = muxer.addTrack(format)
                extractor.selectTrack(index)
                val declared = runCatching {
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                }.getOrDefault(0)
                maxInputSize = max(maxInputSize, declared)
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val info = MediaCodec.BufferInfo()
            muxer.start()
            muxerStarted = true

            var firstVideoPtsUs = Long.MIN_VALUE
            var videoFrameIndex = 0L
            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sourcePtsUs = extractor.sampleTime
                val outputPtsUs = if (inputTrack == videoTrack) {
                    if (firstVideoPtsUs == Long.MIN_VALUE) firstVideoPtsUs = sourcePtsUs.coerceAtLeast(0L)
                    firstVideoPtsUs + ((videoFrameIndex * 1_000_000L + TARGET_FPS / 2L) / TARGET_FPS)
                        .also { videoFrameIndex++ }
                } else {
                    sourcePtsUs.coerceAtLeast(0L)
                }

                info.set(0, size, outputPtsUs, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outputTracks[inputTrack], buffer, info)
                if (!extractor.advance()) break
            }
            videoFrameIndex > 1L && target.isFile && target.length() > 0L
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun hasUsableVideo(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: return false
            extractor.selectTrack(videoTrack)
            var samples = 0
            while (samples < 3 && extractor.sampleTime >= 0L) {
                samples++
                if (!extractor.advance()) break
            }
            samples >= 2
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun replaceOriginal(temp: File, original: File): Boolean {
        val replaced = runCatching {
            Files.move(
                temp.toPath(),
                original.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            Files.move(temp.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!replaced) runCatching { temp.delete() }
        return replaced
    }
}
