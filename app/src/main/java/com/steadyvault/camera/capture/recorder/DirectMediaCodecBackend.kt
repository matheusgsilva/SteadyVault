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
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Backend de vídeo com cadência CFR em tempo real.
 *
 * Camera2 escreve em uma SurfaceTexture OES. Na main5, gaps curtos são estimados
 * e interpolados em GPU com motion field reduzido, sem OpenCV/readback no caminho
 * crítico. A Surface resultante segue direto para o MediaCodec em timeline CFR.
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
    private val superStabilizationEnabled: Boolean = false,
    private val analysisEnabled: Boolean = false,
    private val onAnalysisFrame: ((ByteArray, Int, Int) -> Unit)? = null,
    private val onError: (Throwable) -> Unit
) : RecordingBackend {

    override val backendName: String = if (superStabilizationEnabled) "MediaCodec + CFR GPU + Super Estável" else "MediaCodec + CFR GPU"
    override val videoBitrateBps: Long get() = videoBitrate.toLong()
    override val audioBitrateBps: Long get() = if (integratedAudio) audioBitrate.toLong() else 0L

    // Retrato: o arquivo já sai girado pela GPU (dimensões trocadas, hint 0). Girar só pelo
    // hint do muxer deixa players/galerias exibindo o vídeo de lado.
    private val physicallyRotatePortrait = orientationHint == 90 || orientationHint == 270
    private val encoderWidth = if (physicallyRotatePortrait) height else width
    private val encoderHeight = if (physicallyRotatePortrait) width else height
    private val encoderOrientationHint = if (physicallyRotatePortrait) 0 else orientationHint

    val profileDescription: String
        get() = "${encoderWidth}x${encoderHeight} ${targetFps} FPS " +
            "${videoMime.substringAfter('/').uppercase()} ${videoBitrate / 1_000_000} Mbps • CFR GPU-only"

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
    private var encoderInputSurface: Surface? = null
    private var cameraInputSurface: Surface? = null
    private var cfrBridge: RealTimeCfrSurfaceBridge? = null
    private var muxer: MediaMuxer? = null
    private var drainFuture: Future<*>? = null
    private var audioRecorder: DirectAacAudioRecorder? = null
    private var audioTempFile: File? = null
    private val audioStarted = AtomicBoolean(false)

    @Volatile private var activeCodecName: String = "?"
    @Volatile private var realtimeTuningApplied = false
    @Volatile private var encodedSamples = 0L
    @Volatile private var writtenSamples = 0L
    @Volatile private var discardedBeforeGate = 0L
    @Volatile private var ptsHoles = 0L
    @Volatile private var framesLostByEncoder = 0L
    @Volatile private var maxPtsDeltaUs = 0L

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
        try {
            activeCodecName = codecInfo.name
            val mediaCodec = createConfiguredEncoder(codecInfo.name)
            codec = mediaCodec

            val encoderSurface = mediaCodec.createInputSurface()
            encoderInputSurface = encoderSurface

            val mediaMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            mediaMuxer.setOrientationHint(encoderOrientationHint)
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

            val bridge = RealTimeCfrSurfaceBridge(
                encoderSurface = encoderSurface,
                sourceWidth = width,
                sourceHeight = height,
                outputWidth = encoderWidth,
                outputHeight = encoderHeight,
                physicalRotationDegrees = if (physicallyRotatePortrait) orientationHint else 0,
                fps = targetFps,
                superStabilizationEnabled = superStabilizationEnabled,
                analysisEnabled = analysisEnabled,
                onAnalysisFrame = onAnalysisFrame,
                onError = onError
            )
            cfrBridge = bridge
            val cameraSurface = bridge.prepare()
            cameraInputSurface = cameraSurface

            prepared = true
            return cameraSurface
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
        cfrBridge?.startOutput()
    }

    override fun stop(): Boolean {
        if (!started || released.get()) return false

        stopRequested.set(true)
        cfrBridge?.stopOutput()
        val audioOk = if (integratedAudio && audioStarted.get()) {
            runCatching { audioRecorder?.stop() == true }.getOrDefault(false)
        } else {
            !integratedAudio
        }

        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainFuture?.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        logEncoderSummary()

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
        runCatching { cfrBridge?.stopOutput() }
        runCatching { cfrBridge?.release() }
        cfrBridge = null
        cameraInputSurface = null
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

        val nominalDeltaUs = 1_000_000L / targetFps.coerceAtLeast(1)
        val toleranceUs = maxOf(1_500L, nominalDeltaUs / 8L)
        val holeThresholdUs = nominalDeltaUs + nominalDeltaUs / 2L
        var lastWrittenPtsUs = Long.MIN_VALUE

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
                            encodedSamples++

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
                                writtenSamples++

                                // Buraco de PTS na SAÍDA do encoder: a ponte entrega uma grade
                                // perfeita, então qualquer intervalo > 1,5 frame aqui é frame
                                // que o próprio codec pulou.
                                if (lastWrittenPtsUs != Long.MIN_VALUE) {
                                    val deltaUs = ptsUs - lastWrittenPtsUs
                                    if (deltaUs > maxPtsDeltaUs) maxPtsDeltaUs = deltaUs
                                    if (deltaUs > holeThresholdUs) {
                                        ptsHoles++
                                        framesLostByEncoder +=
                                            ((deltaUs + nominalDeltaUs / 2L) / nominalDeltaUs - 1L)
                                                .coerceAtLeast(0L)
                                    }
                                }
                                lastWrittenPtsUs = ptsUs
                            } else {
                                discardedBeforeGate++
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
            localMuxer.setOrientationHint(encoderOrientationHint)

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

    /**
     * Formato do encoder.
     *
     * [realtimeTuning] restaura os parâmetros que o histórico do projeto associa ao
     * 4K60 sem perdas no S25 Ultra (1.8.209–1.8.211) e que a main3 havia removido:
     *  - ALLOW_FRAME_DROP=0: o encoder não pode pular frames de entrada para "caber" no
     *    bitrate. Um frame pulado vira buraco no PTS DEPOIS da ponte CFR, onde nada mais
     *    consegue preenchê-lo — é o único gap que a ponte não enxerga;
     *  - PRIORITY=0 + OPERATING_RATE=fps: sinaliza tempo real e mantém os clocks do
     *    codec no ritmo da captura em vez de tratá-lo como transcodificação;
     *  - LATENCY=1 e MAX_B_FRAMES=0: sem fila de reordenação, saída na ordem de entrada.
     */
    private fun buildVideoFormat(realtimeTuning: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(videoMime, encoderWidth, encoderHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                iFrameIntervalSeconds.coerceAtLeast(1)
            )
            if (realtimeTuning) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
                }
            }
        }

    /**
     * Tenta primeiro a configuração realtime; se o codec do fabricante recusar algum
     * parâmetro, cai para a configuração mínima anterior em vez de falhar a gravação.
     */
    private fun createConfiguredEncoder(codecName: String): MediaCodec {
        var tuned: MediaCodec? = null
        try {
            val created = MediaCodec.createByCodecName(codecName)
            tuned = created
            created.configure(
                buildVideoFormat(realtimeTuning = true),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            realtimeTuningApplied = true
            return created
        } catch (throwable: Throwable) {
            runCatching { tuned?.release() }
            Log.w(TAG, "encoder $codecName recusou os parâmetros realtime; usando configuração mínima", throwable)
        }

        val minimal = MediaCodec.createByCodecName(codecName)
        try {
            minimal.configure(
                buildVideoFormat(realtimeTuning = false),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        } catch (throwable: Throwable) {
            runCatching { minimal.release() }
            throw throwable
        }
        realtimeTuningApplied = false
        return minimal
    }

    private fun logEncoderSummary() {
        val bridge = cfrBridge?.stats()
        val sentByBridge = bridge?.let { it.realFrames + it.interpolatedFrames } ?: -1L
        Log.i(
            TAG,
            "resumo encoder: codec=$activeCodecName realtime=$realtimeTuningApplied " +
                "enviadosPelaPonte=$sentByBridge saidas=$encodedSamples gravados=$writtenSamples " +
                "descartadosNoInicio=$discardedBeforeGate buracosPTS=$ptsHoles " +
                "quadrosPerdidosNoEncoder=$framesLostByEncoder " +
                "maiorIntervalo=${maxPtsDeltaUs / 1000}ms fps=$targetFps"
        )
        if (framesLostByEncoder > 0L) {
            Log.w(
                TAG,
                "o ENCODER pulou $framesLostByEncoder frame(s): o gap está depois da ponte CFR " +
                    "(bitrate/clock do codec), não na câmera"
            )
        }
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

        runCatching { cfrBridge?.release() }
        cfrBridge = null
        cameraInputSurface = null

        val localSurface = encoderInputSurface
        encoderInputSurface = null
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
        private const val TAG = "SteadyVaultCfr"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_SECONDS = 4L
        private const val RELEASE_TIMEOUT_MS = 350L
        private const val STABLE_INTERVALS_BEFORE_FILE = 4
        private const val REMUX_BUFFER_BYTES = 16 * 1024 * 1024
    }
}
