package com.steadyvault.camera.processing.timeline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToLong

class TimelineRepairer {
    data class Result(val frameCount: Int, val durationUs: Long, val correctedGapCount: Int)

    fun process(
        inputFile: File,
        outputFileDescriptor: FileDescriptor,
        targetFps: Int,
        orientationHint: Int,
        progress: (Int, String) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false }
    ): Result {
        require(inputFile.isFile && inputFile.length() > 0L) { "Arquivo bruto de gravação inválido" }
        require(targetFps > 0) { "FPS de saída inválido" }

        progress(2, "Analisando timestamps da gravação")
        val trackInfo = readTrackInfo(inputFile)
        val timeline = analyzeTimeline(inputFile, trackInfo.videoTrackIndex, targetFps, progress, cancelled)
        val videoFormat = trackInfo.videoFormat.apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            if (containsKey(MediaFormat.KEY_ROTATION)) setInteger(MediaFormat.KEY_ROTATION, 0)
        }
        val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false

        try {
            muxer.setOrientationHint(((orientationHint % 360) + 360) % 360)
            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = trackInfo.audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            started = true
            val bufferSize = max(
                maxInputSize(videoFormat, DEFAULT_VIDEO_BUFFER_SIZE),
                trackInfo.audioFormat?.let { maxInputSize(it, DEFAULT_AUDIO_BUFFER_SIZE) } ?: DEFAULT_AUDIO_BUFFER_SIZE
            )
            writeTracks(
                inputFile,
                trackInfo,
                outputVideoTrack,
                outputAudioTrack,
                muxer,
                timeline,
                bufferSize,
                progress,
                cancelled
            )
            if (cancelled()) throw InterruptedException("Otimização cancelada")
            muxer.stop()
            started = false
            progress(100, "Cadência CFR regularizada")
            return Result(timeline.frameCount, timeline.outputDurationUs, timeline.correctedGapCount)
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun readTrackInfo(inputFile: File): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    videoTrackIndex < 0 && mime.startsWith("video/") -> {
                        videoTrackIndex = index
                        videoFormat = format
                    }
                    audioTrackIndex < 0 && mime.startsWith("audio/") -> {
                        audioTrackIndex = index
                        audioFormat = format
                    }
                }
            }
            require(videoTrackIndex >= 0 && videoFormat != null) { "Faixa de vídeo não encontrada" }
            return TrackInfo(videoTrackIndex, audioTrackIndex, videoFormat, audioFormat)
        } finally {
            extractor.release()
        }
    }

    private fun analyzeTimeline(
        inputFile: File,
        videoTrackIndex: Int,
        targetFps: Int,
        progress: (Int, String) -> Unit,
        cancelled: () -> Boolean
    ): TimelinePlan {
        val extractor = MediaExtractor()
        val nominalFrameUs = TIMEBASE_US / targetFps.coerceAtLeast(1)
        var frameCount = 0
        var firstPts = -1L
        var lastPts = -1L
        var previousPts = -1L
        var correctedGapCount = 0
        try {
            extractor.setDataSource(inputFile.absolutePath)
            extractor.selectTrack(videoTrackIndex)
            while (extractor.sampleTrackIndex >= 0) {
                if (cancelled()) throw InterruptedException("Otimização cancelada")
                val pts = extractor.sampleTime
                if (pts < 0L) break
                if (firstPts < 0L) firstPts = pts
                if (previousPts >= 0L && pts - previousPts > nominalFrameUs * GAP_DETECTION_RATIO_NUM / GAP_DETECTION_RATIO_DEN) {
                    correctedGapCount++
                }
                previousPts = pts
                lastPts = pts
                frameCount++
                if (frameCount % ANALYSIS_PROGRESS_INTERVAL == 0) {
                    progress(4, "Analisando cadência • $frameCount quadros")
                }
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
        }
        require(frameCount > 0 && firstPts >= 0L) { "Nenhum quadro de vídeo foi encontrado" }
        val sourceSpanUs = if (frameCount > 1) (lastPts - firstPts).coerceAtLeast(nominalFrameUs) else 0L
        // A correção de lacunas deve voltar a ser CFR real: cada quadro recebe o
        // intervalo nominal do FPS escolhido. Ex.: 60 FPS = 16.666 us; um salto
        // gravado de ~33 ms deixa de virar pausa na reprodução e passa a ocupar
        // apenas o próximo slot da grade.
        val outputFrameIntervalUs = nominalFrameUs.toDouble()
        return TimelinePlan(
            firstSourcePts = firstPts,
            sourceSpanUs = sourceSpanUs,
            nominalFrameUs = nominalFrameUs,
            outputFrameIntervalUs = outputFrameIntervalUs,
            frameCount = frameCount,
            correctedGapCount = correctedGapCount,
            outputDurationUs = fixedCadenceDurationUs(frameCount, outputFrameIntervalUs)
        )
    }

    private fun fixedCadenceDurationUs(frameCount: Int, frameIntervalUs: Double): Long {
        val lastFramePts = ((frameCount - 1).coerceAtLeast(0).toDouble() * frameIntervalUs).roundToLong()
        return lastFramePts + frameIntervalUs.roundToLong()
    }

    private fun writeTracks(
        inputFile: File,
        trackInfo: TrackInfo,
        outputVideoTrack: Int,
        outputAudioTrack: Int,
        muxer: MediaMuxer,
        timeline: TimelinePlan,
        bufferSize: Int,
        progress: (Int, String) -> Unit,
        cancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        val info = MediaCodec.BufferInfo()
        var videoSampleIndex = 0
        var lastVideoOutputStep = -1L
        var lastVideoOutputPts = -1L
        var lastAudioPts = -1L

        try {
            extractor.setDataSource(inputFile.absolutePath)
            extractor.selectTrack(trackInfo.videoTrackIndex)
            if (trackInfo.audioTrackIndex >= 0 && outputAudioTrack >= 0) extractor.selectTrack(trackInfo.audioTrackIndex)

            while (extractor.sampleTrackIndex >= 0) {
                if (cancelled()) throw InterruptedException("Otimização cancelada")
                val sourceTrack = extractor.sampleTrackIndex
                val targetTrack = when (sourceTrack) {
                    trackInfo.videoTrackIndex -> outputVideoTrack
                    trackInfo.audioTrackIndex -> outputAudioTrack
                    else -> -1
                }
                if (targetTrack < 0) {
                    if (!extractor.advance()) break
                    continue
                }

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sourcePts = extractor.sampleTime
                val outputPts = if (sourceTrack == trackInfo.videoTrackIndex) {
                    val mapped = (videoSampleIndex.toDouble() * timeline.outputFrameIntervalUs).roundToLong()
                    lastVideoOutputStep = videoSampleIndex.toLong()
                    videoSampleIndex++
                    max(lastVideoOutputPts + 1L, mapped).also { lastVideoOutputPts = it }
                } else {
                    val mapped = (sourcePts - timeline.firstSourcePts).coerceAtLeast(0L)
                    if (mapped > timeline.maxAudioPresentationTimeUs) {
                        if (!extractor.advance()) break
                        continue
                    }
                    max(lastAudioPts + 1L, mapped).also { lastAudioPts = it }
                }

                buffer.position(0)
                buffer.limit(size)
                info.set(0, size, outputPts, extractor.sampleFlags)
                muxer.writeSampleData(targetTrack, buffer, info)
                if (sourceTrack == trackInfo.videoTrackIndex && videoSampleIndex % WRITE_PROGRESS_INTERVAL == 0) {
                    val percent = (8 + videoSampleIndex * 88 / timeline.frameCount.coerceAtLeast(1)).coerceIn(8, 96)
                    progress(percent, "Forçando cadência fixa • $videoSampleIndex/${timeline.frameCount} quadros")
                }
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
        }
    }

    private fun maxInputSize(format: MediaFormat, fallback: Int): Int {
        val declared = runCatching {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        }.getOrDefault(0)
        return max(fallback, declared.coerceAtMost(MAX_BUFFER_SIZE))
    }

    private data class TrackInfo(
        val videoTrackIndex: Int,
        val audioTrackIndex: Int,
        val videoFormat: MediaFormat,
        val audioFormat: MediaFormat?
    )

    private data class TimelinePlan(
        val firstSourcePts: Long,
        val sourceSpanUs: Long,
        val nominalFrameUs: Long,
        val outputFrameIntervalUs: Double,
        val frameCount: Int,
        val correctedGapCount: Int,
        val outputDurationUs: Long
    ) {
        val maxAudioPresentationTimeUs: Long = max(sourceSpanUs, outputDurationUs) + AUDIO_END_TOLERANCE_US
    }

    companion object {
        private const val DEFAULT_VIDEO_BUFFER_SIZE = 24 * 1024 * 1024
        private const val DEFAULT_AUDIO_BUFFER_SIZE = 512 * 1024
        private const val MAX_BUFFER_SIZE = 96 * 1024 * 1024
        private const val AUDIO_END_TOLERANCE_US = 150_000L
        private const val TIMEBASE_US = 1_000_000L
        private const val ANALYSIS_PROGRESS_INTERVAL = 2_000
        private const val WRITE_PROGRESS_INTERVAL = 300
        private const val GAP_DETECTION_RATIO_NUM = 3L
        private const val GAP_DETECTION_RATIO_DEN = 2L
    }
}
