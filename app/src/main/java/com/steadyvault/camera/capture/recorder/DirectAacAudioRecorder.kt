package com.steadyvault.camera.capture.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * Captura AAC isolada do vídeo. A saída é um M4A temporário que depois é apenas
 * remuxado com o MP4 de vídeo; nenhum frame de vídeo é recodificado.
 */
class DirectAacAudioRecorder(
    private val outputFile: File,
    private val sampleRate: Int,
    private val bitrate: Int,
    /** 0=AUTO, 1=MONO, 2=STEREO */
    private val requestedChannels: Int,
    private val gainDb: Int,
    private val useAgc: Boolean,
    private val useNoiseSuppressor: Boolean,
    private val useLowCut: Boolean
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                runnable.run()
            },
            "SteadyVault-AAC"
        )
    }

    private val stopRequested = AtomicBoolean(false)
    private var worker: Future<*>? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var channels = 0
    private var prepared = false
    private var started = false

    fun prepare() {
        require(!prepared) { "áudio já preparado" }
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val candidates = when (requestedChannels) {
            1 -> listOf(1)
            2 -> listOf(2)
            else -> listOf(2, 1)
        }

        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                prepareForChannels(candidate)
                prepared = true
                return
            } catch (t: Throwable) {
                failure = t
                releaseInternal()
            }
        }

        throw IllegalStateException("não foi possível preparar o áudio configurado", failure)
    }

    private fun prepareForChannels(channelCount: Int) {
        val mask = if (channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "taxa/canais de áudio não suportados" }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, sampleRate * channelCount / 5)
        )
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "microfone não inicializou com $channelCount canal(is)"
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(minBuffer * 2, 16 * 1024))
        }

        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val audioMuxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        record = audioRecord
        codec = audioCodec
        muxer = audioMuxer
        channels = channelCount

        if (useAgc && AutomaticGainControl.isAvailable()) {
            agc = runCatching {
                AutomaticGainControl.create(audioRecord.audioSessionId)?.apply {
                    enabled = true
                }
            }.getOrNull()
        }
        if (useNoiseSuppressor && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                    enabled = true
                }
            }.getOrNull()
        }
    }

    fun start() {
        check(prepared) { "áudio não preparado" }
        if (started) return

        val localCodec = requireNotNull(codec)
        val localRecord = requireNotNull(record)
        val localMuxer = requireNotNull(muxer)

        stopRequested.set(false)
        localCodec.start()
        started = true
        worker = executor.submit {
            runLoop(localCodec, localRecord, localMuxer)
        }
    }

    fun stop(): Boolean {
        if (!started) return false
        stopRequested.set(true)
        runCatching { record?.stop() }
        runCatching { worker?.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        started = false
        closeAfterWorker()
        return outputFile.isFile && outputFile.length() > 0L
    }

    fun release() {
        stopRequested.set(true)
        runCatching { record?.stop() }
        runCatching { worker?.get(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        closeAfterWorker()
        executor.shutdownNow()
        runCatching { outputFile.delete() }
        prepared = false
        started = false
    }

    private fun runLoop(
        localCodec: MediaCodec,
        localRecord: AudioRecord,
        localMuxer: MediaMuxer
    ) {
        val mask = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val pcmSize = maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate,
                mask,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            4096
        )
        val pcm = ByteArray(pcmSize)
        val info = MediaCodec.BufferInfo()
        val gain = 10.0.pow(gainDb.coerceIn(0, 30) / 20.0).toFloat()
        val filter = if (useLowCut) HighPass(sampleRate, channels, 75f) else null

        var totalFrames = 0L
        var inputEos = false
        var outputEos = false
        var track = -1
        var muxerStarted = false

        try {
            localRecord.startRecording()
            require(localRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "microfone não iniciou"
            }

            while (!outputEos) {
                if (!inputEos) {
                    val inputIndex = localCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = localCodec.getInputBuffer(inputIndex)
                            ?: error("buffer AAC de entrada indisponível")

                        if (stopRequested.get()) {
                            localCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                ptsUs(totalFrames),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val maxRead = minOf(pcm.size, inputBuffer.capacity())
                            val read = localRecord.read(pcm, 0, maxRead)
                            if (read > 0) {
                                processPcm(pcm, read, gain, filter)
                                inputBuffer.clear()
                                inputBuffer.put(pcm, 0, read)
                                localCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    read,
                                    ptsUs(totalFrames),
                                    0
                                )
                                totalFrames += read / (2L * channels)
                            } else if (read < 0 && !stopRequested.get()) {
                                error("falha lendo microfone: $read")
                            }
                        }
                    }
                }

                val outputIndex = localCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "formato AAC mudou duas vezes" }
                        track = localMuxer.addTrack(localCodec.outputFormat)
                        localMuxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val buffer = localCodec.getOutputBuffer(outputIndex)
                            ?: error("buffer AAC de saída indisponível")
                        val isConfig =
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            localMuxer.writeSampleData(track, buffer, info)
                        }
                        outputEos =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        localCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { localRecord.stop() }
            if (muxerStarted) runCatching { localMuxer.stop() }
        }
    }

    private fun processPcm(
        bytes: ByteArray,
        size: Int,
        gain: Float,
        filter: HighPass?
    ) {
        if (gain == 1f && filter == null) return

        val buffer = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        var sampleIndex = 0
        while (buffer.remaining() >= 2) {
            val position = buffer.position()
            var value = buffer.short.toFloat()
            if (filter != null) value = filter.process(sampleIndex % channels, value)
            if (gain != 1f) value *= gain
            val clipped = value
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
            buffer.putShort(position, clipped)
            sampleIndex++
        }
    }

    private fun ptsUs(totalFrames: Long): Long =
        totalFrames * 1_000_000L / sampleRate.coerceAtLeast(1)

    private fun closeAfterWorker() {
        runCatching { agc?.release() }
        runCatching { noiseSuppressor?.release() }
        agc = null
        noiseSuppressor = null

        val localRecord = record
        record = null
        runCatching { localRecord?.release() }

        val localCodec = codec
        codec = null
        runCatching { localCodec?.stop() }
        runCatching { localCodec?.release() }

        val localMuxer = muxer
        muxer = null
        runCatching { localMuxer?.release() }

        worker = null
        prepared = false
    }

    private fun releaseInternal() {
        runCatching { agc?.release() }
        runCatching { noiseSuppressor?.release() }
        agc = null
        noiseSuppressor = null
        runCatching { record?.release() }
        record = null
        runCatching { codec?.release() }
        codec = null
        runCatching { muxer?.release() }
        muxer = null
    }

    private class HighPass(
        sampleRate: Int,
        channels: Int,
        cutoffHz: Float
    ) {
        private val previousInput = FloatArray(channels)
        private val previousOutput = FloatArray(channels)
        private val alpha: Float

        init {
            val dt = 1f / sampleRate.toFloat()
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            alpha = rc / (rc + dt)
        }

        fun process(channel: Int, input: Float): Float {
            val output =
                alpha * (previousOutput[channel] + input - previousInput[channel])
            previousInput[channel] = input
            previousOutput[channel] = output
            return output
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 5_000L
        private const val STOP_TIMEOUT_SECONDS = 4L
        private const val RELEASE_TIMEOUT_MS = 400L
    }
}
