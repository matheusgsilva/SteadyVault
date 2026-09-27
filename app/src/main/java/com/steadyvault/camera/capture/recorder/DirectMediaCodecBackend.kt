package com.steadyvault.camera.capture.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Backend de vídeo direto para priorizar cadência.
 *
 * Camera2 escreve em uma única Surface de entrada do MediaCodec. O app nunca toca
 * nos pixels. Antes de abrir a época útil do MP4, alguns samples comprimidos são
 * descartados até a cadência entrar em regime e o encoder entregar um keyframe.
 */
class DirectMediaCodecBackend(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val videoMime: String,
    private val videoBitrate: Int,
    private val iFrameIntervalSeconds: Int,
    private val orientationHint: Int,
    override val integratedAudio: Boolean,
    private val audioSampleRate: Int,
    private val audioBitrate: Int,
    private val requestedAudioChannels: Int,
    private val audioGainDb: Int,
    private val audioAgc: Boolean,
    private val audioNoiseSuppressor: Boolean,
    private val audioLowCut: Boolean,
    private val onError: (Throwable) -> Unit
) : RecordingBackend {

    override val backendName: String = "MediaCodec direto"
    override val videoBitrateBps: Long get() = videoBitrate.toLong()
    override val audioBitrateBps: Long get() = if (integratedAudio) audioBitrate.toLong() else 0L

    val profileDescription: String
        get() = "${width}x${height} ${targetFps} FPS " +
            "${videoMime.substringAfter('/').uppercase()} ${videoBitrate / 1_000_000} Mbps • hardware"

    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                runnable.run()
            },
            "SteadyVault-MediaCodecDrain"
        )
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var drainFuture: Future<*>? = null
    private var audioRecorder: DirectAacAudioRecorder? = null
    private var audioTempFile: File? = null
    private val audioStarted = AtomicBoolean(false)

    private val committed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile private var muxerStarted = false
    @Volatile private var prepared = false
    @Volatile private var armed = false
    @Volatile private var started = false

    override fun prepare(): Surface {
        check(!released.get()) { "MediaCodec já liberado" }
        require(!outputFile.exists() || outputFile.delete()) {
            "não foi possível preparar o arquivo final"
        }
        outputFile.parentFile?.mkdirs()

        val codecInfo = selectEncoder()
            ?: throw IllegalStateException(
                "nenhum encoder de hardware suporta ${width}x${height} ${targetFps} FPS ${videoMime.substringAfter('/').uppercase()}"
            )
        val capabilities = codecInfo.getCapabilitiesForType(videoMime)
        val format = MediaFormat.createVideoFormat(videoMime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, targetFps.toFloat())

            // Surface-input não garante CFR por si só: o encoder apenas recebe os
            // buffers produzidos pela câmera. Se a HAL atrasar um frame, repetimos o
            // último após um período nominal e, no Android 12+, proibimos a Surface
            // de descartar buffers para "alcançar" o produtor.
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                1_000_000L / targetFps.coerceAtLeast(1)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            }

            val encoderCaps = capabilities.encoderCapabilities
            if (
                encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true
            ) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
        }

        try {
            val mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val surface = mediaCodec.createInputSurface()
            inputSurface = surface

            val mediaMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            mediaMuxer.setOrientationHint(orientationHint)
            muxer = mediaMuxer

            if (integratedAudio) {
                val temp = File(
                    outputFile.parentFile,
                    outputFile.nameWithoutExtension + ".audio.m4a"
                )
                val audio = DirectAacAudioRecorder(
                    outputFile = temp,
                    sampleRate = audioSampleRate,
                    bitrate = audioBitrate,
                    requestedChannels = requestedAudioChannels,
                    gainDb = audioGainDb,
                    useAgc = audioAgc,
                    useNoiseSuppressor = audioNoiseSuppressor,
                    useLowCut = audioLowCut
                )
                audio.prepare()
                audioTempFile = temp
                audioRecorder = audio
            }

            mediaCodec.start()
            drainFuture = drainExecutor.submit {
                drain(mediaCodec, mediaMuxer)
            }

            prepared = true
            return surface
        } catch (throwable: Throwable) {
            release()
            throw throwable
        }
    }

    override fun arm() {
        check(prepared && !released.get()) { "MediaCodec não preparado" }
        armed = true
    }

    override fun commitStart() {
        check(armed && prepared && !released.get()) { "MediaCodec não armado" }
        if (started) return
        started = true
        committed.set(true)
    }

    override fun stop(): Boolean {
        if (!started || released.get()) return false

        stopRequested.set(true)
        val audioOk = if (integratedAudio && audioStarted.get()) {
            runCatching { audioRecorder?.stop() == true }.getOrDefault(false)
        } else {
            !integratedAudio
        }

        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainFuture?.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        closeCodecAndMuxer()
        started = false

        if (!outputFile.isFile || outputFile.length() <= 0L) return false
        if (!integratedAudio) return true
        if (!audioOk) return false

        val audioFile = audioTempFile
        if (audioFile == null || !audioFile.isFile || audioFile.length() <= 0L) return false

        val merged = mergeAudioIntoVideo(audioFile)
        if (merged) runCatching { audioFile.delete() }
        return merged
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return

        stopRequested.set(true)
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainFuture?.get(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        closeCodecAndMuxer()
        runCatching { audioRecorder?.release() }
        runCatching { audioTempFile?.delete() }
        audioRecorder = null
        audioTempFile = null
        audioStarted.set(false)
        drainExecutor.shutdownNow()

        prepared = false
        armed = false
        started = false
    }

    private fun drain(
        mediaCodec: MediaCodec,
        mediaMuxer: MediaMuxer
    ) {
        val info = MediaCodec.BufferInfo()
        var videoTrack = -1

        var previousPtsUs = Long.MIN_VALUE
        var stableIntervals = 0
        var syncRequested = false
        var gateOpen = false
        var firstPtsUs = 0L
        var writtenFrames = 0L
        var firstWrittenPtsUs = Long.MIN_VALUE
        var lastWrittenPtsUs = Long.MIN_VALUE
        var maxWrittenGapUs = 0L
        var longGapCount = 0L

        val nominalDeltaUs = 1_000_000L / targetFps.coerceAtLeast(1)
        val toleranceUs = maxOf(1_500L, nominalDeltaUs / 8L)

        try {
            while (true) {
                val index = mediaCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)

                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested.get() && released.get()) break
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(videoTrack < 0) { "formato do MediaCodec mudou duas vezes" }
                        videoTrack = mediaMuxer.addTrack(mediaCodec.outputFormat)
                        mediaMuxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        val buffer = mediaCodec.getOutputBuffer(index)
                            ?: throw IllegalStateException("MediaCodec retornou buffer nulo")

                        val isConfig =
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val isKeyFrame =
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (!isConfig && info.size > 0) {
                            val ptsUs = info.presentationTimeUs

                            if (!committed.get()) {
                                previousPtsUs = Long.MIN_VALUE
                                stableIntervals = 0
                            } else if (!gateOpen) {
                                if (previousPtsUs != Long.MIN_VALUE && ptsUs > previousPtsUs) {
                                    val deltaUs = ptsUs - previousPtsUs
                                    stableIntervals =
                                        if (abs(deltaUs - nominalDeltaUs) <= toleranceUs) {
                                            stableIntervals + 1
                                        } else {
                                            0
                                        }
                                }
                                previousPtsUs = ptsUs

                                if (
                                    stableIntervals >= STABLE_INTERVALS_BEFORE_FILE &&
                                    !syncRequested
                                ) {
                                    val params = Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    }
                                    runCatching { mediaCodec.setParameters(params) }
                                    syncRequested = true
                                }

                                if (syncRequested && isKeyFrame) {
                                    gateOpen = true
                                    firstPtsUs = ptsUs
                                    if (
                                        integratedAudio &&
                                        audioStarted.compareAndSet(false, true)
                                    ) {
                                        runCatching { audioRecorder?.start() }
                                            .onFailure { onError(it) }
                                    }
                                }
                            }

                            if (gateOpen && muxerStarted && videoTrack >= 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)

                                val adjusted = MediaCodec.BufferInfo().apply {
                                    set(
                                        info.offset,
                                        info.size,
                                        (ptsUs - firstPtsUs).coerceAtLeast(0L),
                                        info.flags
                                    )
                                }
                                mediaMuxer.writeSampleData(videoTrack, buffer, adjusted)

                                val writtenPtsUs = adjusted.presentationTimeUs
                                if (firstWrittenPtsUs == Long.MIN_VALUE) {
                                    firstWrittenPtsUs = writtenPtsUs
                                }
                                if (lastWrittenPtsUs != Long.MIN_VALUE) {
                                    val gapUs = writtenPtsUs - lastWrittenPtsUs
                                    if (gapUs > maxWrittenGapUs) maxWrittenGapUs = gapUs
                                    if (gapUs > nominalDeltaUs + toleranceUs) longGapCount++
                                }
                                lastWrittenPtsUs = writtenPtsUs
                                writtenFrames++
                            }
                        }

                        mediaCodec.releaseOutputBuffer(index, false)

                        if (isEos) break
                    }
                }
            }

            if (writtenFrames > 1L && lastWrittenPtsUs > firstWrittenPtsUs) {
                val durationUs = lastWrittenPtsUs - firstWrittenPtsUs
                val measuredFps =
                    (writtenFrames - 1L) * 1_000_000.0 / durationUs.toDouble()
                android.util.Log.i(
                    "SteadyVaultCapture",
                    "MediaCodec cadence: frames=$writtenFrames, fps=" +
                        java.lang.String.format(java.util.Locale.US, "%.3f", measuredFps) +
                        ", maxGapUs=$maxWrittenGapUs, longGaps=$longGapCount"
                )
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && !released.get()) {
                onError(throwable)
            }
        }
    }

    private fun mergeAudioIntoVideo(audioFile: File): Boolean {
        val mergedFile = File(
            outputFile.parentFile,
            outputFile.nameWithoutExtension + ".mux.mp4"
        )
        runCatching { mergedFile.delete() }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var mergedMuxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(outputFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoSourceTrack = findTrack(videoExtractor, "video/")
            val audioSourceTrack = findTrack(audioExtractor, "audio/")
            require(videoSourceTrack >= 0) { "faixa de vídeo não encontrada no remux" }
            require(audioSourceTrack >= 0) { "faixa de áudio não encontrada no remux" }

            val localMuxer = MediaMuxer(
                mergedFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            mergedMuxer = localMuxer
            localMuxer.setOrientationHint(orientationHint)

            val videoTargetTrack =
                localMuxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack))
            val audioTargetTrack =
                localMuxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
            localMuxer.start()

            copyTrack(videoExtractor, videoSourceTrack, localMuxer, videoTargetTrack)
            copyTrack(audioExtractor, audioSourceTrack, localMuxer, audioTargetTrack)

            localMuxer.stop()
            localMuxer.release()
            mergedMuxer = null

            require(mergedFile.isFile && mergedFile.length() > 0L) {
                "remux de áudio não produziu arquivo"
            }

            val original = File(
                outputFile.parentFile,
                outputFile.nameWithoutExtension + ".video-only.mp4"
            )
            runCatching { original.delete() }
            require(outputFile.renameTo(original)) {
                "não foi possível reservar o vídeo antes do remux"
            }
            if (!mergedFile.renameTo(outputFile)) {
                original.renameTo(outputFile)
                error("não foi possível publicar o MP4 com áudio")
            }
            original.delete()
            true
        } catch (_: Throwable) {
            runCatching { mergedMuxer?.stop() }
            runCatching { mergedMuxer?.release() }
            runCatching { mergedFile.delete() }
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                .orEmpty()
            if (mime.startsWith(prefix)) return index
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        targetMuxer: MediaMuxer,
        targetTrack: Int
    ) {
        extractor.selectTrack(sourceTrack)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocateDirect(REMUX_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var firstPtsUs = -1L

        while (extractor.sampleTrackIndex >= 0) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val sourcePts = extractor.sampleTime
            if (firstPtsUs < 0L) firstPtsUs = sourcePts
            info.set(
                0,
                size,
                (sourcePts - firstPtsUs).coerceAtLeast(0L),
                extractor.sampleFlags
            )
            targetMuxer.writeSampleData(targetTrack, buffer, info)

            if (!extractor.advance()) break
        }
        extractor.unselectTrack(sourceTrack)
    }

    private fun selectEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info ->
                info.supportedTypes.any { it.equals(videoMime, ignoreCase = true) }
            }
            .filter { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(videoMime)
                    val videoCaps = caps.videoCapabilities
                    videoCaps != null &&
                        videoCaps.isSizeSupported(width, height) &&
                        videoCaps.areSizeAndRateSupported(
                            width,
                            height,
                            targetFps.toDouble()
                        )
                }.getOrDefault(false)
            }
            .sortedByDescending { info ->
                runCatching { info.isHardwareAccelerated }.getOrDefault(false)
            }
            .firstOrNull()

    private fun closeCodecAndMuxer() {
        val localCodec = codec
        codec = null
        runCatching { localCodec?.stop() }
        runCatching { localCodec?.release() }

        val localSurface = inputSurface
        inputSurface = null
        runCatching { localSurface?.release() }

        val localMuxer = muxer
        muxer = null
        if (muxerStarted) {
            runCatching { localMuxer?.stop() }
        }
        runCatching { localMuxer?.release() }
        muxerStarted = false
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_SECONDS = 4L
        private const val RELEASE_TIMEOUT_MS = 350L
        private const val STABLE_INTERVALS_BEFORE_FILE = 4
        private const val REMUX_BUFFER_BYTES = 16 * 1024 * 1024
    }
}
