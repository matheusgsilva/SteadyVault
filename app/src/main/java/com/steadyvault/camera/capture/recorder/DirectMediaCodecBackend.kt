package com.steadyvault.camera.capture.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Process
import android.view.Surface
import java.io.File
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
    private val onError: (Throwable) -> Unit
) : RecordingBackend {

    override val backendName: String = "MediaCodec direto"
    override val integratedAudio: Boolean = false
    override val videoBitrateBps: Long get() = videoBitrate.toLong()
    override val audioBitrateBps: Long = 0L

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
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainFuture?.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        closeCodecAndMuxer()
        started = false

        return outputFile.isFile && outputFile.length() > 0L
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return

        stopRequested.set(true)
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainFuture?.get(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        closeCodecAndMuxer()
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
                            }
                        }

                        mediaCodec.releaseOutputBuffer(index, false)

                        if (isEos) break
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && !released.get()) {
                onError(throwable)
            }
        }
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
    }
}
