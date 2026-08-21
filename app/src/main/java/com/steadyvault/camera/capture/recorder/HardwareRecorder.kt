package com.steadyvault.camera.capture.recorder

import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.capture.timing.VideoTimestampNormalizer
import com.steadyvault.camera.capture.finalize.RecordingFinalizer

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HardwareRecorder(
    private val outputFile: File,
    private val videoConfig: VideoConfig,
    private val audioConfig: AudioConfig,
    private val onError: (Throwable) -> Unit
) {

    data class VideoConfig(
        val codecName: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val orientationHint: Int,
        val hdrHlg10: Boolean,
        val profile: Int?,
        val level: Int?,
        val iFrameIntervalSeconds: Int
    )

    data class AudioConfig(
        val enabled: Boolean = true,
        val sampleRate: Int,
        val bitrate: Int,
        val channels: String,
        val gainDb: Int,
        val agcEnabled: Boolean,
        val noiseSuppressorEnabled: Boolean,
        val lowCutEnabled: Boolean,
        val microphoneDirection: Int = MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
    )

    private data class BufferedVideoSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private val stateLock = Any()
    private val armed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val videoDone = CountDownLatch(1)
    private val audioDone = CountDownLatch(1)
    private val audioPreparationDone = CountDownLatch(1)
    private val recordingStartedElapsedMs = AtomicLong(0L)
    private val recordingStartedCaptureNs = AtomicLong(0L)
    private val lastVideoSampleElapsedMs = AtomicLong(0L)
    private val firstVideoSampleNs = AtomicLong(0L)
    private val requestedAudioEndPtsUs = AtomicLong(0L)
    private val audioCaptureStarted = AtomicBoolean(false)
    private val audioAvailable = AtomicBoolean(false)
    private val audioPreparationAbandoned = AtomicBoolean(false)
    private val videoWriteInProgress = AtomicBoolean(false)
    private val videoDrainActive = AtomicBoolean(false)
    private val audioCaptureActive = AtomicBoolean(false)
    private val audioPreparationActive = AtomicBoolean(false)
    private val audioResourcesReleased = AtomicBoolean(false)
    private val pipelineResourcesReleased = AtomicBoolean(false)

    private lateinit var videoCodec: MediaCodec
    private lateinit var audioCodec: MediaCodec
    private lateinit var audioRecord: AudioRecord
    private lateinit var muxerCoordinator: MuxerCoordinator
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioPreparationThread: Thread? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioChannelCount = 1
    private var audioChannelMask = AudioFormat.CHANNEL_IN_MONO

    fun prepare(): Surface {
        require(!outputFile.exists() || outputFile.delete()) {
            "não foi possível preparar o arquivo bruto"
        }
        outputFile.parentFile?.mkdirs()

        muxerCoordinator = MuxerCoordinator(
            outputFile = outputFile,
            orientationHint = videoConfig.orientationHint,
            audioRequired = audioConfig.enabled,
            hdrHlg10 = videoConfig.hdrHlg10,
            nominalVideoFps = videoConfig.fps
        )

        try {
            prepareVideoEncoder()
            // A Surface de vídeo fica disponível imediatamente. Microfone e AAC são
            // preparados em paralelo e nunca atrasam a criação da sessão Camera2.
            startVideoDrainThread()
            if (audioConfig.enabled) {
                startAudioPreparationThread()
            } else {
                disableAudioPermanently()
                audioPreparationDone.countDown()
            }
            return requireNotNull(inputSurface) { "superfície do encoder indisponível" }
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    /**
     * Prepara o estado da captura, mas ainda não abre o gate do vídeo.
     *
     * O CaptureService chama este método antes de submeter o único repeating request.
     * Qualquer saída antiga que já estivesse na fila do codec continua sendo drenada e
     * descartada porque [commitStart] ainda não confirmou a época da gravação.
     */
    fun arm() {
        synchronized(stateLock) {
            check(!released.get()) { "gravador já liberado" }
            if (armed.get()) return
            check(!recording.get()) { "gravação já iniciada" }
            lastVideoSampleElapsedMs.set(0L)
            firstVideoSampleNs.set(0L)
            audioCaptureStarted.set(false)
            stopRequested.set(false)
            armed.set(true)
        }
    }

    /**
     * Confirma a gravação imediatamente depois que Camera2 aceitou o repeating request.
     * A partir daqui o primeiro IDR pertencente a esta época pode abrir o MP4.
     */
    fun commitStart() {
        synchronized(stateLock) {
            check(!released.get()) { "gravador já liberado" }
            check(armed.get()) { "gravador ainda não foi armado" }
            if (recording.get()) return
            recordingStartedElapsedMs.set(SystemClock.elapsedRealtime())
            recordingStartedCaptureNs.set(SystemClock.elapsedRealtimeNanos())
            recording.set(true)

            // O codec já está ativo e ligado à Surface Camera2. Falhar ao pedir o
            // IDR não cancela a captura: o gate aguarda o próximo IDR natural e seu
            // fallback curto preserva um GOP anterior válido quando necessário.
            requestVideoSyncFrame()
        }
    }

    /** Compatibilidade para chamadores que não precisam do protocolo Camera2 em duas fases. */
    fun start() {
        arm()
        commitStart()
    }

    /**
     * Tempo sem um quadro efetivamente produzido pelo encoder.
     *
     * O valor parte do início da gravação até a primeira amostra e depois usa a
     * última amostra recebida do encoder. Assim uma espera transitória do armazenamento
     * não é confundida com Camera2/HAL congelada ao apagar ou acender a tela.
     * A detecção continua sem adicionar callbacks por quadro à câmera.
     */
    fun videoSilenceDurationMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val lastSample = lastVideoSampleElapsedMs.get()
        val referenceTimestamp = if (lastSample > 0L) lastSample else recordingStartedElapsedMs.get()
        return if (referenceTimestamp > 0L) {
            (nowElapsedMs - referenceTimestamp).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    fun hasProducedVideoSample(): Boolean = lastVideoSampleElapsedMs.get() > 0L

    fun isVideoWriteInProgress(): Boolean = videoWriteInProgress.get()


    fun stop(): Boolean {
        if (!recording.get() || released.get()) return false
        stopRequested.set(true)

        if (audioCaptureStarted.get() && ::audioRecord.isInitialized) {
            runCatching { audioRecord.stop() }
        } else {
            // Clipe muito curto ou promoção tardia do microfone: publique o vídeo
            // imediatamente, sem deixar o muxer esperando um track AAC inexistente.
            disableAudioPermanently()
        }
        runCatching { videoCodec.signalEndOfInputStream() }

        val videoFinished = videoDone.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val audioFinished = !audioAvailable.get() || audioDone.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        recording.set(false)

        if (!videoFinished || !audioFinished) {
            release()
            return false
        }

        return runCatching {
            muxerCoordinator.finish()
            muxerCoordinator.hasWrittenVideoSample() && outputFile.isFile && outputFile.length() > 0L
        }.getOrElse {
            onError(it)
            false
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        stopRequested.set(true)
        recording.set(false)
        armed.set(false)
        audioPreparationAbandoned.set(true)

        if (audioCaptureStarted.get() && ::audioRecord.isInitialized) runCatching { audioRecord.stop() }
        if (::videoCodec.isInitialized) runCatching { videoCodec.signalEndOfInputStream() }
        val current = Thread.currentThread()
        if (videoThread !== current) runCatching { videoThread?.join(RELEASE_JOIN_TIMEOUT_MS) }
        if (audioThread !== current) runCatching { audioThread?.join(RELEASE_JOIN_TIMEOUT_MS) }
        if (audioPreparationThread !== current) {
            runCatching { audioPreparationThread?.join(AUDIO_PREPARATION_JOIN_TIMEOUT_MS) }
        }

        // Nunca libere codec/muxer por baixo de uma thread que ainda esteja em
        // configure(), releaseOutputBuffer() ou writeSampleData(). Se o driver/disk
        // atrasar além do deadline, release() retorna e a última thread viva faz a
        // liberação assim que sair, em vez de congelar o serviço indefinidamente.
        releasePipelineResourcesWhenQuiescent()
    }

    private fun prepareVideoEncoder() {
        val format = MediaFormat.createVideoFormat(
            videoConfig.mime,
            videoConfig.width,
            videoConfig.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoConfig.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoConfig.fps)
            // A cadência é definida pela sessão Camera2 e pelo KEY_FRAME_RATE. Não use
            // CAPTURE_RATE/MAX_FPS_TO_ENCODER: em alguns encoders Samsung essas chaves
            // criam uma segunda política temporal desnecessária para gravação em tempo real.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoConfig.iFrameIntervalSeconds)
            // Em SDR deixe o encoder escolher profile/level internamente, como nas
            // versões estáveis. HDR continua exigindo o profile/level de 10 bits.
            if (videoConfig.hdrHlg10 && videoConfig.profile != null && videoConfig.level != null) {
                setInteger(MediaFormat.KEY_PROFILE, videoConfig.profile)
                setInteger(MediaFormat.KEY_LEVEL, videoConfig.level)
            }
            // Um único perfil do encoder em todos os FPS: tempo real, sem reordenação,
            // baixa latência, taxa operacional explícita e sem descarte pela Surface.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, videoConfig.fps.toFloat())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) setInteger(MediaFormat.KEY_IMPORTANCE, 0)
        }

        videoCodec = MediaCodec.createByCodecName(videoConfig.codecName)
        val capabilities = videoCodec.codecInfo.getCapabilitiesForType(videoConfig.mime)
        val encoderCapabilities = capabilities.encoderCapabilities
        when {
            encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true ->
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true ->
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        if (videoConfig.hdrHlg10) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            val hlgFeature = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                    capabilities.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing
                    ) -> MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing

                capabilities.isFeatureSupported(
                    MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing
                ) -> MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing

                else -> null
            }
            hlgFeature?.let { format.setFeatureEnabled(it, true) }
        } else {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()
        videoCodec.start()
    }

    private fun prepareAudioEncoder() {
        val selected = when (audioConfig.channels) {
            CaptureSettings.CHANNELS_MONO -> createAudioRecord(AudioFormat.CHANNEL_IN_MONO, 1)
            CaptureSettings.CHANNELS_STEREO -> createAudioRecord(AudioFormat.CHANNEL_IN_STEREO, 2)
                ?: createAudioRecord(AudioFormat.CHANNEL_IN_MONO, 1)
            else -> createAudioRecord(AudioFormat.CHANNEL_IN_STEREO, 2)
                ?: createAudioRecord(AudioFormat.CHANNEL_IN_MONO, 1)
        } ?: throw IllegalStateException("nenhuma entrada de microfone compatível")

        audioRecord = selected.first
        audioChannelMask = selected.second
        audioChannelCount = if (audioChannelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching {
                AutomaticGainControl.create(audioRecord.audioSessionId)?.apply { enabled = audioConfig.agcEnabled }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = audioConfig.noiseSuppressorEnabled }
            }.getOrNull()
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            audioConfig.sampleRate,
            audioChannelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioConfig.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CODEC_INPUT_BYTES)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_CHANNEL_MASK, audioChannelMask)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setInteger(MediaFormat.KEY_IMPORTANCE, AUDIO_CODEC_IMPORTANCE)
            }
        }

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()
    }

    /**
     * Faz o AAC publicar o formato antes de o vídeo começar a acumular buffers.
     * Um curto buffer AAC de silêncio deixa a faixa pronta sem abrir o microfone;
     * o áudio real conserva depois o atraso monotônico em relação ao primeiro vídeo.
     */
    private fun createAudioRecord(channelMask: Int, channels: Int): Pair<AudioRecord, Int>? {
        val minBuffer = AudioRecord.getMinBufferSize(
            audioConfig.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null

        val bufferBytes = max(
            minBuffer * 4,
            AUDIO_FRAMES_PER_READ * channels * PCM_BYTES_PER_SAMPLE * 4
        )

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audioConfig.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrNull() ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        runCatching { record.setPreferredMicrophoneDirection(audioConfig.microphoneDirection) }
        return record to channelMask
    }

    private fun startVideoDrainThread() {
        videoDrainActive.set(true)
        videoThread = Thread(
            {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    drainVideoEncoder()
                } catch (t: Throwable) {
                    onError(t)
                } finally {
                    videoDone.countDown()
                    videoDrainActive.set(false)
                    releasePipelineResourcesWhenQuiescent()
                }
            },
            "SteadyVault-VideoEncoder"
        ).apply { start() }
    }

    private fun startAudioPreparationThread() {
        audioPreparationActive.set(true)
        audioPreparationThread = Thread(
            {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    prepareAudioEncoder()
                    if (released.get() || audioPreparationAbandoned.get()) {
                        releaseAudioResources()
                    } else {
                        audioAvailable.set(true)
                    }
                } catch (_: Throwable) {
                    releaseAudioResources()
                    disableAudioPermanently()
                } finally {
                    audioPreparationDone.countDown()
                    audioPreparationActive.set(false)
                    releasePipelineResourcesWhenQuiescent()
                }
            },
            "SteadyVault-AudioPrepare"
        ).apply { start() }
    }

    private fun startAudioThread() {
        audioCaptureActive.set(true)
        audioThread = Thread(
            {
                try {
                    // Vídeo é a carga irrecuperável. O buffer do AudioRecord absorve
                    // pequenas variações sem deixar o áudio preemptar o drain visual.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    captureAndEncodeAudio()
                } catch (_: Throwable) {
                    // Áudio é complementar. Uma falha do microfone/AAC não deve
                    // interromper nem perder a gravação de vídeo já em andamento.
                    runCatching { if (::audioRecord.isInitialized) audioRecord.stop() }
                    disableAudioPermanently()
                } finally {
                    audioDone.countDown()
                    audioCaptureActive.set(false)
                    releasePipelineResourcesWhenQuiescent()
                }
            },
            "SteadyVault-AudioEncoder"
        ).apply { start() }
    }

    /**
     * Caminho direto de vídeo, inspirado no gravador antigo que produzia a melhor fluidez:
     * Camera2 -> Surface do MediaCodec -> MediaCodec -> MediaMuxer.
     *
     * Não há reorder buffer, interpolação, repetição nem encaixe na grade nominal.
     * Todo PTS válido da câmera/encoder é preservado; apenas duplicados ou regressivos
     * avançam 1 us para cumprir a monotonicidade exigida pelo MediaMuxer. O primeiro
     * IDR pertencente à época confirmada é sempre o tempo zero do arquivo.
     */
    private fun drainVideoEncoder() {
        val info = MediaCodec.BufferInfo()
        val timestampNormalizer = VideoTimestampNormalizer(videoConfig.fps)
        val startupGate = StartupVideoGate(
            maxFramesBeforeFallback = max(4, videoConfig.fps / 4),
            maxWaitBeforeFallbackUs = STARTUP_IDR_FALLBACK_US,
            maxBufferedBytes = MAX_STARTUP_BUFFER_BYTES
        )
        val startupBuffer = ArrayDeque<BufferedVideoSample>()
        var startupBufferBytes = 0L
        var startupAccepted = false
        var muxWaitAnchorPtsUs = Long.MIN_VALUE
        var firstVideoPtsUs = Long.MIN_VALUE
        var lastVideoOutputPtsUs = -1L

        fun establishVideoEpoch(sourcePtsUs: Long) {
            if (firstVideoPtsUs != Long.MIN_VALUE) return
            firstVideoPtsUs = sourcePtsUs
            firstVideoSampleNs.compareAndSet(0L, resolveFirstVideoCaptureNs(sourcePtsUs))
        }

        fun writeVideoSample(buffer: ByteBuffer, sampleInfo: MediaCodec.BufferInfo) {
            if (firstVideoPtsUs == Long.MIN_VALUE) establishVideoEpoch(sampleInfo.presentationTimeUs)
            val sourcePtsUs = (sampleInfo.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L)
            val outputPtsUs = timestampNormalizer.normalize(sourcePtsUs)
            lastVideoOutputPtsUs = outputPtsUs
            videoWriteInProgress.set(true)
            try {
                muxerCoordinator.writeVideo(buffer, sampleInfo, outputPtsUs)
            } finally {
                videoWriteInProgress.set(false)
            }
        }

        fun clearStartupBuffer() {
            startupBuffer.clear()
            startupBufferBytes = 0L
        }

        fun bufferStartupSample(buffer: ByteBuffer, sampleInfo: MediaCodec.BufferInfo) {
            val duplicate = buffer.duplicate().apply {
                position(sampleInfo.offset)
                limit(sampleInfo.offset + sampleInfo.size)
            }
            val bytes = ByteArray(sampleInfo.size)
            duplicate.get(bytes)
            startupBuffer.addLast(BufferedVideoSample(bytes, sampleInfo.presentationTimeUs, sampleInfo.flags))
            startupBufferBytes += bytes.size.toLong()
        }

        fun flushStartupBuffer() {
            if (!muxerCoordinator.isStarted()) return
            while (startupBuffer.isNotEmpty()) {
                val sample = startupBuffer.removeFirst()
                val sampleInfo = MediaCodec.BufferInfo().apply {
                    set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                }
                writeVideoSample(ByteBuffer.wrap(sample.data), sampleInfo)
            }
            startupBufferBytes = 0L
        }

        fun openMuxerIfReady(currentPtsUs: Long, forceVideoOnly: Boolean = false) {
            if (!startupAccepted) return
            if (!muxerCoordinator.isStarted()) {
                val waitedUs = if (muxWaitAnchorPtsUs != Long.MIN_VALUE && currentPtsUs >= muxWaitAnchorPtsUs) {
                    currentPtsUs - muxWaitAnchorPtsUs
                } else 0L
                if (forceVideoOnly || waitedUs >= MAX_AUDIO_TRACK_WAIT_US || startupBufferBytes >= MAX_STARTUP_BUFFER_BYTES) {
                    muxerCoordinator.disableAudioRequirement()
                }
            }
            flushStartupBuffer()
        }

        while (!released.get()) {
            val index = videoCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (stopRequested.get() && !recording.get()) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerCoordinator.setVideoFormat(videoCodec.outputFormat)
                    if (startupAccepted) openMuxerIfReady(muxWaitAnchorPtsUs)
                }
                index >= 0 -> {
                    val output = videoCodec.getOutputBuffer(index)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!codecConfig && info.size > 0 && output != null && recording.get()) {
                        lastVideoSampleElapsedMs.set(SystemClock.elapsedRealtime())
                        if (startupAccepted) {
                            if (muxerCoordinator.isStarted() && startupBuffer.isEmpty()) {
                                writeVideoSample(output, info)
                            } else {
                                bufferStartupSample(output, info)
                                openMuxerIfReady(info.presentationTimeUs)
                            }
                        } else {
                            val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val clearlyBeforeCommit = isClearlyBeforeRecordingCommit(info.presentationTimeUs)
                            if (keyFrame && clearlyBeforeCommit) clearStartupBuffer()
                            if (startupBuffer.isNotEmpty() || (keyFrame && clearlyBeforeCommit)) {
                                bufferStartupSample(output, info)
                            }
                            var decision = startupGate.onSample(
                                isKeyFrame = keyFrame,
                                clearlyBeforeCommit = clearlyBeforeCommit,
                                sourcePtsUs = info.presentationTimeUs,
                                bufferedBytes = startupBufferBytes,
                                hasBufferedKeyFrame = startupBuffer.isNotEmpty()
                            )
                            if (decision.action == StartupVideoGate.Action.HOLD && stopRequested.get()) {
                                decision = startupGate.forceFallback(startupBuffer.isNotEmpty())
                            }
                            if (decision.requestSyncFrame) requestVideoSyncFrame()
                            when (decision.action) {
                                StartupVideoGate.Action.HOLD -> Unit
                                StartupVideoGate.Action.START_WITH_CURRENT_KEY_FRAME -> {
                                    clearStartupBuffer()
                                    bufferStartupSample(output, info)
                                    startupAccepted = true
                                    muxWaitAnchorPtsUs = info.presentationTimeUs
                                    establishVideoEpoch(info.presentationTimeUs)
                                    openMuxerIfReady(info.presentationTimeUs)
                                }
                                StartupVideoGate.Action.START_WITH_BUFFERED_GOP -> {
                                    startupBuffer.firstOrNull()?.let { first ->
                                        startupAccepted = true
                                        muxWaitAnchorPtsUs = info.presentationTimeUs
                                        establishVideoEpoch(first.presentationTimeUs)
                                        openMuxerIfReady(info.presentationTimeUs)
                                    }
                                }
                                StartupVideoGate.Action.WRITE_CURRENT -> {
                                    clearStartupBuffer()
                                    bufferStartupSample(output, info)
                                    startupAccepted = true
                                    muxWaitAnchorPtsUs = info.presentationTimeUs
                                    establishVideoEpoch(info.presentationTimeUs)
                                    openMuxerIfReady(info.presentationTimeUs)
                                }
                            }
                        }
                    }

                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos && !startupAccepted && startupBuffer.isNotEmpty()) {
                        val fallback = startupGate.forceFallback(true)
                        if (fallback.action == StartupVideoGate.Action.START_WITH_BUFFERED_GOP) {
                            startupAccepted = true
                            muxWaitAnchorPtsUs = info.presentationTimeUs
                            establishVideoEpoch(startupBuffer.first().presentationTimeUs)
                        }
                    }
                    if (eos) openMuxerIfReady(info.presentationTimeUs, forceVideoOnly = true)
                    if (eos && lastVideoOutputPtsUs >= 0L) {
                        muxerCoordinator.writeVideoEndOfStream(lastVideoOutputPtsUs + 1_000_000L / videoConfig.fps.coerceAtLeast(1))
                    }
                    videoCodec.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
        }
    }

    private fun requestVideoSyncFrame() {
        runCatching {
            videoCodec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }
    }

    private fun isClearlyBeforeRecordingCommit(sourcePtsUs: Long): Boolean {
        val commitNs = recordingStartedCaptureNs.get()
        if (commitNs <= 0L || sourcePtsUs <= 0L || sourcePtsUs > Long.MAX_VALUE / 1_000L) {
            return false
        }

        val sourceNs = sourcePtsUs * 1_000L
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val minimumComparableNs = (nowNs - MAX_CAPTURE_TIMESTAMP_DISTANCE_NS).coerceAtLeast(0L)
        val maximumComparableNs = nowNs + MAX_CAPTURE_TIMESTAMP_DISTANCE_NS
        if (sourceNs !in minimumComparableNs..maximumComparableNs) return false

        // O commit ocorre logo depois de setRepeating*. Um timestamp anterior a essa
        // fronteira pertence ao arm/pre-request e não pode virar o IDR inicial. É mais
        // seguro perder no máximo a exposição que cruzou a fronteira do que republicar
        // o prólogo preto que motivou este gate.
        return sourceNs < commitNs
    }

    private fun resolveFirstVideoCaptureNs(sourcePtsUs: Long): Long {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val sourceNs = if (sourcePtsUs > 0L && sourcePtsUs <= Long.MAX_VALUE / 1_000L) {
            sourcePtsUs * 1_000L
        } else {
            0L
        }
        val minimumComparableNs = (nowNs - MAX_CAPTURE_TIMESTAMP_DISTANCE_NS).coerceAtLeast(0L)
        val maximumComparableNs = nowNs + MAX_CAPTURE_TIMESTAMP_DISTANCE_NS
        if (sourceNs in minimumComparableNs..maximumComparableNs) return sourceNs

        // SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN não é comparável ao relógio do áudio.
        // Nesse caso, o arm imediatamente anterior ao único repeating request é o
        // melhor limite monotônico disponível e evita usar a latência do encoder.
        return recordingStartedCaptureNs.get().takeIf { it > 0L } ?: nowNs
    }

    /**
     * Inicia o áudio sem bloquear o começo do vídeo. O CaptureService chama este
     * método somente depois que a promoção do foreground service para microfone
     * foi aceita. Se o Android demorar nessa promoção, o vídeo continua capturando
     * normalmente e o MP4 ganha áudio assim que ele estiver disponível.
     */
    fun startAudioCapture(): Boolean {
        if (!audioConfig.enabled) return false
        if (!audioPreparationDone.await(AUDIO_PREPARATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            disableAudioPermanently()
            return false
        }

        return synchronized(stateLock) {
            if (!audioAvailable.get() || !muxerCoordinator.acceptsAudio()) {
                disableAudioPermanently()
                return@synchronized false
            }
            if (!recording.get() || released.get() || stopRequested.get()) return@synchronized false
            if (!audioCaptureStarted.compareAndSet(false, true)) return@synchronized true

            return@synchronized try {
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "o microfone não iniciou"
                }
                startAudioThread()
                true
            } catch (_: Throwable) {
                audioCaptureStarted.set(false)
                disableAudioPermanently()
                false
            }
        }
    }

    /**
     * Falha de permissão/AppOp do microfone nunca deve cancelar uma captura visual.
     * Se o áudio ainda não começou, libera o muxer para publicar vídeo-only.
     */
    fun continueWithoutAudio() {
        if (audioCaptureStarted.get()) return
        disableAudioPermanently()
    }

    private fun disableAudioPermanently() {
        audioPreparationAbandoned.set(true)
        audioAvailable.set(false)
        muxerCoordinator.writeAudioEndOfStream(requestedAudioEndPtsUs.get())
        muxerCoordinator.disableAudioRequirement()
        audioDone.countDown()
    }

    private fun releaseAudioResources() {
        if (!audioResourcesReleased.compareAndSet(false, true)) return
        runCatching { agc?.release() }
        runCatching { noiseSuppressor?.release() }
        agc = null
        noiseSuppressor = null
        if (::audioRecord.isInitialized) runCatching { audioRecord.release() }
        if (::audioCodec.isInitialized) runCatching { audioCodec.stop() }
        if (::audioCodec.isInitialized) runCatching { audioCodec.release() }
    }

    private fun releasePipelineResourcesWhenQuiescent() {
        if (!released.get()) return
        if (
            videoDrainActive.get() ||
            audioCaptureActive.get() ||
            audioPreparationActive.get()
        ) {
            return
        }
        if (!pipelineResourcesReleased.compareAndSet(false, true)) return

        releaseAudioResources()
        runCatching { inputSurface?.release() }
        inputSurface = null
        if (::videoCodec.isInitialized) runCatching { videoCodec.stop() }
        if (::videoCodec.isInitialized) runCatching { videoCodec.release() }
        if (::muxerCoordinator.isInitialized) runCatching { muxerCoordinator.releaseWithoutFinish() }
    }

    private fun captureAndEncodeAudio() {
        val samples = ShortArray(AUDIO_FRAMES_PER_READ * audioChannelCount)
        val processor = if (audioConfig.gainDb > 0 || audioConfig.lowCutEnabled) {
            AudioProcessor(audioChannelCount, audioConfig.sampleRate, audioConfig.gainDb, audioConfig.lowCutEnabled)
        } else {
            null
        }
        val outputInfo = MediaCodec.BufferInfo()
        val captureTimestamp = AudioTimestamp()
        var totalFrames = 0L
        var fallbackTimelineStartNs: Long? = null
        var lastInputEndPtsUs = 0L

        while (!stopRequested.get() && !released.get()) {
            val read = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue

            val frames = read / audioChannelCount
            val bufferDurationNs = frames.toLong() * 1_000_000_000L / audioConfig.sampleRate
            val fallbackStartNs = fallbackTimelineStartNs ?: (SystemClock.elapsedRealtimeNanos() - bufferDurationNs).also {
                fallbackTimelineStartNs = it
            }
            val timestampStartNs = runCatching {
                if (audioRecord.getTimestamp(captureTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME) != AudioRecord.SUCCESS) return@runCatching null
                val frameDelta = totalFrames - captureTimestamp.framePosition
                val frameDeltaNs = frameDelta * 1_000_000_000L / audioConfig.sampleRate
                captureTimestamp.nanoTime + frameDeltaNs
            }.getOrNull()
            val bufferStartNs = timestampStartNs
                ?: fallbackStartNs + totalFrames * 1_000_000_000L / audioConfig.sampleRate
            totalFrames += frames

            // Áudio anterior ao primeiro quadro visual não é reposicionado para zero.
            // Ele é descartado até existir uma época comum; áudio iniciado depois mantém
            // seu atraso real no MP4 em vez de ficar artificialmente adiantado.
            val videoStartNs = firstVideoSampleNs.get()
            if (videoStartNs <= 0L || bufferStartNs + bufferDurationNs <= videoStartNs) continue
            val framesToTrim = if (bufferStartNs < videoStartNs) {
                val deltaNs = videoStartNs - bufferStartNs
                ((deltaNs * audioConfig.sampleRate + 999_999_999L) / 1_000_000_000L)
                    .coerceIn(0L, frames.toLong())
                    .toInt()
            } else {
                0
            }
            val validFrames = frames - framesToTrim
            if (validFrames <= 0) continue
            val sampleOffset = framesToTrim * audioChannelCount
            val validSamples = validFrames * audioChannelCount
            val alignedBufferStartNs = bufferStartNs +
                framesToTrim.toLong() * 1_000_000_000L / audioConfig.sampleRate
            val estimatedPtsUs = ((alignedBufferStartNs - videoStartNs) / 1_000L).coerceAtLeast(0L)
            val ptsUs = max(estimatedPtsUs, lastInputEndPtsUs)

            processor?.process(samples, sampleOffset, validSamples)
            queueAudioInput(
                samples = samples,
                offset = sampleOffset,
                length = validSamples,
                ptsUs = ptsUs,
                endOfStream = false,
                outputInfo = outputInfo
            )
            lastInputEndPtsUs = ptsUs + validFrames.toLong() * 1_000_000L / audioConfig.sampleRate
            requestedAudioEndPtsUs.set(lastInputEndPtsUs)
            drainAudioEncoder(outputInfo, endOfStream = false)
        }

        requestedAudioEndPtsUs.set(lastInputEndPtsUs)
        queueAudioInput(
            samples = EMPTY_AUDIO_SAMPLES,
            offset = 0,
            length = 0,
            ptsUs = lastInputEndPtsUs,
            endOfStream = true,
            outputInfo = outputInfo
        )
        drainAudioEncoder(outputInfo, endOfStream = true)
    }

    private fun queueAudioInput(
        samples: ShortArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        endOfStream: Boolean,
        outputInfo: MediaCodec.BufferInfo
    ) {
        while (!released.get()) {
            val inputIndex = audioCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex < 0) {
                drainAudioEncoder(outputInfo, endOfStream = false)
                continue
            }

            val input = audioCodec.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("buffer AAC indisponível")
            input.clear()
            input.order(ByteOrder.LITTLE_ENDIAN)
            for (index in offset until offset + length) input.putShort(samples[index])

            audioCodec.queueInputBuffer(
                inputIndex,
                0,
                length * PCM_BYTES_PER_SAMPLE,
                ptsUs,
                if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
            return
        }
    }

    private fun drainAudioEncoder(info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var idleCount = 0

        while (!released.get()) {
            val index = audioCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleCount >= AUDIO_EOS_IDLE_LIMIT) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerCoordinator.setAudioFormat(audioCodec.outputFormat)
                    idleCount = 0
                }

                index >= 0 -> {
                    idleCount = 0
                    val output = audioCodec.getOutputBuffer(index)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!codecConfig && info.size > 0 && output != null) {
                        muxerCoordinator.writeAudio(output, info, max(0L, info.presentationTimeUs))
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) {
                        muxerCoordinator.writeAudioEndOfStream(
                            max(requestedAudioEndPtsUs.get(), info.presentationTimeUs)
                        )
                    }
                    audioCodec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private class AudioProcessor(
        private val channels: Int,
        sampleRate: Int,
        gainDb: Int,
        private val lowCutEnabled: Boolean
    ) {
        private val previousInput = DoubleArray(channels)
        private val previousOutput = DoubleArray(channels)
        private var filteredBuffer = DoubleArray(AUDIO_FRAMES_PER_READ * channels)
        private val highPassAlpha: Double
        private val maxManualGain = Math.pow(10.0, gainDb.coerceIn(0, 30) / 20.0)
        private val limiterThreshold = LIMIT_PEAK * SOFT_LIMIT_THRESHOLD
        private val limiterSpan = LIMIT_PEAK - limiterThreshold
        private var currentGain = min(INITIAL_MANUAL_GAIN, maxManualGain)

        init {
            val rc = 1.0 / (2.0 * Math.PI * HIGH_PASS_HZ)
            val dt = 1.0 / sampleRate.toDouble()
            highPassAlpha = rc / (rc + dt)
        }

        fun process(samples: ShortArray, offset: Int, length: Int) {
            if (length <= 0) return

            if (filteredBuffer.size < length) filteredBuffer = DoubleArray(length)
            var sumSquares = 0.0
            var peak = 0.0

            for (relativeIndex in 0 until length) {
                val index = offset + relativeIndex
                val channel = relativeIndex % channels
                val input = samples[index].toDouble()
                val output = if (lowCutEnabled) {
                    highPassAlpha * (previousOutput[channel] + input - previousInput[channel])
                } else input
                previousInput[channel] = input
                previousOutput[channel] = output
                filteredBuffer[relativeIndex] = output
                sumSquares += output * output
                peak = max(peak, abs(output))
            }

            val rms = sqrt(sumSquares / length.coerceAtLeast(1))
            val rmsGain = TARGET_RMS / max(rms, MIN_MEASURABLE_RMS)
            val peakGain = if (peak > 0.0) LIMIT_PEAK / peak else maxManualGain
            val desiredGain = min(rmsGain, peakGain).coerceIn(MIN_MANUAL_GAIN, maxManualGain)
            val smoothing = if (desiredGain < currentGain) GAIN_REDUCTION_SPEED else GAIN_RAISE_SPEED
            currentGain += (desiredGain - currentGain) * smoothing

            for (relativeIndex in 0 until length) {
                val amplified = filteredBuffer[relativeIndex] * currentGain
                samples[offset + relativeIndex] = softLimit(amplified)
                    .toInt()
                    .toShort()
            }
        }

        private fun softLimit(value: Double): Double {
            val magnitude = abs(value)
            if (magnitude <= limiterThreshold) return value

            val over = magnitude - limiterThreshold
            val compressed = limiterThreshold + limiterSpan * over / (over + limiterSpan)
            return if (value < 0.0) -compressed else compressed
        }
    }

    private class MuxerCoordinator(
        private val outputFile: File,
        orientationHint: Int,
        private var audioRequired: Boolean,
        private val hdrHlg10: Boolean,
        private val nominalVideoFps: Int
    ) {
        private val lock = Any()
        private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val directInfo = MediaCodec.BufferInfo()
        private var videoTrack = -1
        private var audioTrack = -1
        private var pendingAudioFormat: MediaFormat? = null
        private var started = false
        private var finished = false
        private var lastVideoPtsUs = -1L
        private var lastAudioPtsUs = -1L
        private var writtenVideoSamples = 0L

        init {
            muxer.setOrientationHint(((orientationHint % 360) + 360) % 360)
        }

        fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
            if (finished) return@synchronized
            check(videoTrack < 0) { "formato de vídeo informado duas vezes" }
            reinforceColorMetadata(format, hdrHlg10)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, nominalVideoFps)
            videoTrack = muxer.addTrack(format)
            startIfReady()
        }

        fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
            if (!audioRequired || audioTrack >= 0 || pendingAudioFormat != null || finished || started) return@synchronized
            pendingAudioFormat = format
        }

        fun acceptsAudio(): Boolean = synchronized(lock) { audioRequired && !finished && (!started || audioTrack >= 0) }
        fun isStarted(): Boolean = synchronized(lock) { started && !finished }
        fun hasWrittenVideoSample(): Boolean = synchronized(lock) { writtenVideoSamples > 0L }

        fun disableAudioRequirement() = synchronized(lock) {
            if (!audioRequired || finished) return@synchronized
            audioRequired = false
            pendingAudioFormat = null
            startIfReady()
        }

        fun writeVideo(buffer: ByteBuffer, sourceInfo: MediaCodec.BufferInfo, ptsUs: Long) = writeSample(true, buffer, sourceInfo, ptsUs)
        fun writeAudio(buffer: ByteBuffer, sourceInfo: MediaCodec.BufferInfo, ptsUs: Long) = writeSample(false, buffer, sourceInfo, ptsUs)
        fun writeVideoEndOfStream(ptsUs: Long) = writeEndOfStream(true, ptsUs)
        fun writeAudioEndOfStream(ptsUs: Long) = writeEndOfStream(false, ptsUs)

        private fun writeSample(video: Boolean, buffer: ByteBuffer, sourceInfo: MediaCodec.BufferInfo, requestedPtsUs: Long) = synchronized(lock) {
            if (finished || sourceInfo.size <= 0) return@synchronized
            if (!video && !audioRequired) return@synchronized
            if (!video && audioTrack < 0) {
                val format = pendingAudioFormat ?: return@synchronized
                if (started) return@synchronized
                audioTrack = muxer.addTrack(format)
                pendingAudioFormat = null
                startIfReady()
            }
            if (!started) return@synchronized
            val track = if (video) videoTrack else audioTrack
            if (track < 0) return@synchronized
            val lastPts = if (video) lastVideoPtsUs else lastAudioPtsUs
            val ptsUs = max(requestedPtsUs, lastPts + 1L)
            if (video) lastVideoPtsUs = ptsUs else lastAudioPtsUs = ptsUs
            val flags = sourceInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
            directInfo.set(sourceInfo.offset, sourceInfo.size, ptsUs, flags)
            muxer.writeSampleData(track, buffer, directInfo)
            if (video) writtenVideoSamples++
        }

        private fun writeEndOfStream(video: Boolean, requestedPtsUs: Long) = synchronized(lock) {
            if (finished || !started) return@synchronized
            val track = if (video) videoTrack else audioTrack
            if (track < 0) return@synchronized
            val lastPts = if (video) lastVideoPtsUs else lastAudioPtsUs
            val ptsUs = max(requestedPtsUs, lastPts + 1L)
            if (video) lastVideoPtsUs = ptsUs else lastAudioPtsUs = ptsUs
            val info = MediaCodec.BufferInfo().apply { set(0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, ByteBuffer.allocate(0), info)
        }

        private fun startIfReady() {
            if (finished || started || videoTrack < 0 || (audioRequired && audioTrack < 0)) return
            muxer.start()
            started = true
        }

        fun finish() = synchronized(lock) {
            if (finished) return@synchronized
            if (!started && videoTrack >= 0) {
                audioRequired = false
                pendingAudioFormat = null
                startIfReady()
            }
            if (!started) {
                finished = true
                runCatching { muxer.release() }
                throw IllegalStateException("encoder não produziu vídeo")
            }
            finished = true
            try { muxer.stop() } finally { muxer.release() }
            RecordingFinalizer.syncAfterMuxerStop(outputFile)
        }

        fun releaseWithoutFinish() = synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        private fun reinforceColorMetadata(format: MediaFormat, hdr: Boolean) {
            if (hdr) {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
        }
    }

    companion object {
        private const val AUDIO_FRAMES_PER_READ = 2_048
        private const val AUDIO_CODEC_INPUT_BYTES = 32 * 1024
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val AUDIO_EOS_IDLE_LIMIT = 300
        private const val STOP_TIMEOUT_SECONDS = 8L
        private const val RELEASE_JOIN_TIMEOUT_MS = 1_500L
        private const val AUDIO_PREPARATION_JOIN_TIMEOUT_MS = 500L
        private const val AUDIO_PREPARATION_TIMEOUT_MS = 1_000L
        private const val STARTUP_IDR_FALLBACK_US = 250_000L
        private const val MAX_AUDIO_TRACK_WAIT_US = 750_000L
        private const val MAX_STARTUP_BUFFER_BYTES = 16L * 1024L * 1024L
        private const val MAX_CAPTURE_TIMESTAMP_DISTANCE_NS = 10_000_000_000L
        private const val AUDIO_CODEC_IMPORTANCE = 100

        private const val HIGH_PASS_HZ = 75.0
        private const val TARGET_RMS = 6_500.0
        private const val MIN_MEASURABLE_RMS = 80.0
        private const val LIMIT_PEAK = 29_200.0
        private const val MIN_MANUAL_GAIN = 1.0
        private const val INITIAL_MANUAL_GAIN = 4.0
        private const val GAIN_REDUCTION_SPEED = 0.42
        private const val GAIN_RAISE_SPEED = 0.08
        private const val SOFT_LIMIT_THRESHOLD = 0.88
        private val EMPTY_AUDIO_SAMPLES = ShortArray(0)
    }
}
