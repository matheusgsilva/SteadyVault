package com.steadyvault.camera.capture.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    private val preferredCodecName: String?,
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

    // Orientação sempre por metadado (setOrientationHint no muxer), igual ao
    // DirectMediaRecorderBackend. Nunca giramos os pixels fisicamente: um único
    // caminho de orientação para todos os FPS, sem swap de largura/altura.
    private val normalizedRotation = ((orientationHint % 360) + 360) % 360
    private val encoderWidth = width
    private val encoderHeight = height
    private val muxerOrientationHint = normalizedRotation

    override val backendName: String = "MediaCodec direto"
    override val videoBitrateBps: Long get() = videoBitrate.toLong()
    override val audioBitrateBps: Long get() = if (integratedAudio) audioBitrate.toLong() else 0L

    val profileDescription: String
        get() = "${encoderWidth}x${encoderHeight} ${targetFps} FPS " +
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

    // Gravar em disco é a parte mais lenta e mais variável do pipeline (I/O de
    // armazenamento pode ter picos, principalmente com o cofre em bitrate alto).
    // O executor isola picos curtos de I/O. O pool limita samples pendentes e
    // reutiliza sua memória; se o disco não acompanhar, reportamos falha em vez
    // de bloquear o drain ou crescer a fila indefinidamente.
    private val muxerWriteExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SteadyVault-MuxerWrite")
    }

    private val samplePool = EncodedBufferPool(32L * 1024 * 1024, 120, 16L * 1024 * 1024)
    private val pipelineFailure = AtomicReference<Throwable?>(null)
    private val maxWriteNs = AtomicLong(0L)

    private fun recordPipelineFailure(throwable: Throwable) {
        if (pipelineFailure.compareAndSet(null, throwable)) {
            Log.e(LOG_TAG, "ENCODER_PRESSURE: pipeline failed", throwable)
            if (!stopRequested.get() && !released.get()) onError(throwable)
        }
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
                "nenhum encoder de hardware suporta ${encoderWidth}x${encoderHeight} ${targetFps} FPS ${videoMime.substringAfter('/').uppercase()}"
            )
        val capabilities = codecInfo.getCapabilitiesForType(videoMime)
        val format = MediaFormat.createVideoFormat(videoMime, encoderWidth, encoderHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)

            // A saída SDR precisa carregar sinalização explícita de vídeo HD.
            // Sem estas chaves alguns codecs Qualcomm/Samsung escrevem metadados
            // legados BT.470/SMPTE170M no MP4 4K, alterando gama/contraste na reprodução.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

            // Prioriza processamento em tempo real e informa explicitamente ao
            // encoder a taxa de operação esperada. Isso não corrige gaps que já
            // chegam da Camera2/HAL, mas reduz a chance de o próprio MediaCodec
            // introduzir atraso quando o sistema estiver sob contenção.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)

            setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                iFrameIntervalSeconds.coerceAtLeast(1)
            )
        }

        try {
            val mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val encoderSurface = mediaCodec.createInputSurface()
            inputSurface = encoderSurface

            val mediaMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            mediaMuxer.setOrientationHint(muxerOrientationHint)
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
            return encoderSurface
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

        runCatching { codec?.signalEndOfInputStream() }.onFailure { recordPipelineFailure(it) }
        runCatching { drainFuture?.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { recordPipelineFailure(it) }

        closeCodecAndMuxer()
        started = false

        if (pipelineFailure.get() != null) return false
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
        muxerWriteExecutor.shutdownNow()

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
        var outputSamples = 0L
        var outputGaps = 0L
        var lastOutputPtsUs = Long.MIN_VALUE
        var maxHeldNs = 0L
        var lastPressureLogNs = SystemClock.elapsedRealtimeNanos()

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
                        val heldSinceNs = SystemClock.elapsedRealtimeNanos()
                        var isEos = false
                        try {
                            val isConfig =
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val isKeyFrame =
                                (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (!isConfig && info.size > 0) {
                                val buffer = mediaCodec.getOutputBuffer(index)
                                    ?: throw IllegalStateException("MediaCodec retornou buffer nulo")
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

                                    val copy = samplePool.acquire(info.size)
                                        ?: error("fila de vídeo saturada: ${samplePool.inFlightCount} samples, " +
                                            "${samplePool.inFlightBytes} bytes; armazenamento não acompanha o bitrate")
                                    var submitted = false
                                    try {
                                        copy.put(buffer)
                                        copy.flip()
                                        val adjusted = MediaCodec.BufferInfo().apply {
                                            set(
                                                0,
                                                info.size,
                                                (ptsUs - firstPtsUs).coerceAtLeast(0L),
                                                info.flags
                                            )
                                        }
                                        val track = videoTrack
                                        muxerWriteExecutor.execute {
                                            val writeSinceNs = SystemClock.elapsedRealtimeNanos()
                                            try {
                                                mediaMuxer.writeSampleData(track, copy, adjusted)
                                            } catch (throwable: Throwable) {
                                                recordPipelineFailure(throwable)
                                            } finally {
                                                maxWriteNs.accumulateAndGet(
                                                    SystemClock.elapsedRealtimeNanos() - writeSinceNs,
                                                    { previous, current -> maxOf(previous, current) }
                                                )
                                                samplePool.recycle(copy)
                                            }
                                        }
                                        submitted = true
                                    } finally {
                                        if (!submitted) samplePool.recycle(copy)
                                    }
                                    outputSamples++
                                    if (lastOutputPtsUs != Long.MIN_VALUE &&
                                        ptsUs - lastOutputPtsUs > nominalDeltaUs * 3L / 2L) outputGaps++
                                    lastOutputPtsUs = ptsUs
                                }
                            }
                        } finally {
                            mediaCodec.releaseOutputBuffer(index, false)
                            maxHeldNs = maxOf(maxHeldNs, SystemClock.elapsedRealtimeNanos() - heldSinceNs)
                        }

                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        if (nowNs - lastPressureLogNs >= 2_000_000_000L || isEos) {
                            Log.i(LOG_TAG, "ENCODER_PRESSURE: samples=$outputSamples ptsGaps=$outputGaps " +
                                "pending=${samplePool.inFlightCount} bytes=${samplePool.inFlightBytes} " +
                                "peakPending=${samplePool.peakInFlightCount} peakBytes=${samplePool.peakInFlightBytes} " +
                                "allocations=${samplePool.allocations} " +
                                "windowMaxHoldMs=${maxHeldNs / 1_000_000.0} " +
                                "windowMaxWriteMs=${maxWriteNs.getAndSet(0L) / 1_000_000.0}")
                            maxHeldNs = 0L
                            lastPressureLogNs = nowNs
                        }
                        if (isEos) break
                    }
                }
            }
        } catch (throwable: Throwable) {
            recordPipelineFailure(throwable)
        } finally {
            // Garante que toda escrita já enfileirada termine antes do muxer ser
            // parado/liberado em closeCodecAndMuxer().
            runCatching {
                muxerWriteExecutor.submit {}.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onFailure { recordPipelineFailure(it) }
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
            localMuxer.setOrientationHint(muxerOrientationHint)

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

    private fun selectEncoder(): MediaCodecInfo? {
        val compatible = MediaCodecList(MediaCodecList.ALL_CODECS)
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
                        videoCaps.isSizeSupported(encoderWidth, encoderHeight) &&
                        videoCaps.areSizeAndRateSupported(
                            encoderWidth,
                            encoderHeight,
                            targetFps.toDouble()
                        )
                }.getOrDefault(false)
            }
            .toList()

        preferredCodecName
            ?.let { preferred ->
                compatible.firstOrNull { it.name == preferred }
            }
            ?.let { return it }

        return compatible
            .sortedWith(
                compareByDescending<MediaCodecInfo> {
                    runCatching { it.isHardwareAccelerated }.getOrDefault(false)
                }.thenBy { it.name }
            )
            .firstOrNull()
    }

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
        private const val LOG_TAG = "SteadyVaultCapture"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_SECONDS = 4L
        private const val RELEASE_TIMEOUT_MS = 350L
        private const val STABLE_INTERVALS_BEFORE_FILE = 4
        private const val REMUX_BUFFER_BYTES = 16 * 1024 * 1024
    }
}

