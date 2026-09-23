package com.steadyvault.camera.processing.transcode

import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.motion.OpenCvMotionEstimator
import com.steadyvault.camera.processing.motion.MotionTrajectoryStabilizer

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

class HardwareVideoTranscoder {
    data class Request(
        val outputMime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val preset: OptimizationPreset,
        val rateMode: OptimizationRateMode,
        val orientationHint: Int,
        val frameRepair: FrameRepairMode,
        val sourceDurationUs: Long,
        val sourceFrameCount: Int,
        val keepAudio: Boolean,
        val maxInterpolatedFramesPerGap: Int,
        val highQualityMotion: Boolean = false,
        val trimStartUs: Long = 0L,
        val trimEndUs: Long = 0L
    )

    data class Result(
        val outputFrames: Int,
        val createdFrames: Int,
        val blendedFrames: Int,
        val durationUs: Long
    )

    fun transcode(
        input: File,
        output: File,
        request: Request,
        progress: (Int, String) -> Unit,
        cancelled: () -> Boolean
    ): Result {
        require(input.isFile && input.length() > 0L) { "Vídeo de origem inválido" }
        val gpuWorkingBytes = request.width.toLong() * request.height.toLong() * RGBA_BYTES_PER_PIXEL * FRAME_TEXTURE_COUNT
        require(gpuWorkingBytes <= MAX_GPU_FRAME_BYTES) {
            "A resolução exige memória GPU excessiva. Reduza a saída para 4K, 1080p ou 720p"
        }
        if (output.exists()) output.delete()

        val source = SourceTracks.open(input)
        if (request.keepAudio && source.audioFormat != null) {
            val audioMime = source.audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            require(audioMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                "O áudio $audioMime não pode ser copiado para MP4. Desative Preservar áudio para este arquivo."
            }
        }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: EncoderInputSurface? = null
        var outputSurface: DecoderOutputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputVideoTrack = -1
        var outputAudioTrack = -1
        var encodedFrames = 0
        var createdFrames = 0
        var blendedFrames = 0
        var finalDurationUs = 0L

        try {
            extractor.setDataSource(input.absolutePath)
            extractor.selectTrack(source.videoTrack)
            val frameIntervalUs = 1_000_000L / request.fps.coerceAtLeast(1)
            val trimStartUs = request.trimStartUs.coerceIn(0L, (request.sourceDurationUs - frameIntervalUs).coerceAtLeast(0L))
            val requestedTrimEndUs = if (request.trimEndUs > trimStartUs) request.trimEndUs else request.sourceDurationUs
            val trimEndUs = requestedTrimEndUs.coerceIn(trimStartUs + frameIntervalUs, request.sourceDurationUs.coerceAtLeast(trimStartUs + frameIntervalUs))
            val sourceSpanUs = (trimEndUs - trimStartUs).coerceAtLeast(frameIntervalUs)
            if (trimStartUs > 0L) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val selectedEncoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(request.outputMime, true) } }
                .mapNotNull { info ->
                    val capabilities = runCatching { info.getCapabilitiesForType(request.outputMime) }.getOrNull() ?: return@mapNotNull null
                    val videoCapabilities = capabilities.videoCapabilities ?: return@mapNotNull null
                    if (!videoCapabilities.areSizeAndRateSupported(request.width, request.height, request.fps.toDouble())) return@mapNotNull null
                    Triple(info, capabilities, videoCapabilities)
                }
                .sortedByDescending { (info, _, _) -> info.isHardwareAccelerated }
                .firstOrNull()
                ?: throw IllegalStateException("Nenhum encoder suporta ${request.width}×${request.height} a ${request.fps} FPS")
            encoder = MediaCodec.createByCodecName(selectedEncoderInfo.first.name)
            val codecCapabilities = selectedEncoderInfo.second
            val videoCapabilities = selectedEncoderInfo.third
            val safeBitrate = request.bitrate.coerceIn(videoCapabilities.bitrateRange.lower, videoCapabilities.bitrateRange.upper)
            val encoderFormat = MediaFormat.createVideoFormat(request.outputMime, request.width, request.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, request.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                val encoderCapabilities = codecCapabilities.encoderCapabilities
                fun supportsRateMode(mode: Int): Boolean =
                    encoderCapabilities?.isBitrateModeSupported(mode) == true

                val desiredRateMode = when (request.rateMode) {
                    OptimizationRateMode.CONSTANT_QUALITY -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    OptimizationRateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    OptimizationRateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    OptimizationRateMode.AUTO -> if (
                        request.preset == OptimizationPreset.HIGH_QUALITY &&
                        supportsRateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                    ) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
                val appliedRateMode = if (supportsRateMode(desiredRateMode)) {
                    desiredRateMode
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
                if (supportsRateMode(appliedRateMode)) setInteger(MediaFormat.KEY_BITRATE_MODE, appliedRateMode)
                if (appliedRateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
                    encoderCapabilities?.qualityRange?.let { qualityRange ->
                        val fraction = when (request.preset) {
                            OptimizationPreset.HIGH_QUALITY -> 0.90
                            OptimizationPreset.REPAIR_ONLY -> 0.70
                        }
                        val quality = qualityRange.lower + ((qualityRange.upper - qualityRange.lower) * fraction).toInt()
                        setInteger(MediaFormat.KEY_QUALITY, quality.coerceIn(qualityRange.lower, qualityRange.upper))
                    }
                }
                encoderCapabilities?.complexityRange?.let { complexityRange ->
                    val complexity = when (request.preset) {
                        OptimizationPreset.HIGH_QUALITY -> complexityRange.upper
                        OptimizationPreset.REPAIR_ONLY ->
                            complexityRange.lower + (complexityRange.upper - complexityRange.lower) / 2
                    }
                    setInteger(MediaFormat.KEY_COMPLEXITY, complexity.coerceIn(complexityRange.lower, complexityRange.upper))
                }
                setInteger(MediaFormat.KEY_OPERATING_RATE, request.fps)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            encoder!!.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = EncoderInputSurface(encoder!!.createInputSurface()).also { it.makeCurrent() }
            encoder!!.start()

            outputSurface = DecoderOutputSurface(request.width, request.height, request.highQualityMotion)
            val sourceMime = source.videoFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Codec de origem ausente")
            val decoderCodecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(sourceMime, true) } }
                .sortedByDescending { it.isHardwareAccelerated }
                .firstOrNull()
                ?: throw IllegalStateException("Nenhum decoder suporta o vídeo de origem")
            val decoderFormat = source.videoFormat.apply {
                if (containsKey(MediaFormat.KEY_ROTATION)) {
                    setInteger(MediaFormat.KEY_ROTATION, 0)
                }
            }
            decoder = MediaCodec.createByCodecName(decoderCodecInfo.name).apply {
                configure(decoderFormat, outputSurface!!.surface, null, 0)
                start()
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(((request.orientationHint % 360) + 360) % 360)
            }

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var firstSourcePts = Long.MIN_VALUE
            var lastSourceRelativePts = 0L
            var sourceDecodedFrames = 0
            val smoothStepUs = frameIntervalUs
            var nextFillPtsUs = 0L
            var lastWrittenPtsUs = -1L
            var loadedFrame = false
            var previousSourceRelativePts = 0L
            val trajectoryStabilizer = MotionTrajectoryStabilizer()
            var previousCorrection = MotionTrajectoryStabilizer.Correction()
            var currentCorrection = MotionTrajectoryStabilizer.Correction()

            fun startMuxerIfReady(format: MediaFormat) {
                if (muxerStarted) return
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.setInteger(MediaFormat.KEY_ROTATION, 0)
                outputVideoTrack = muxer!!.addTrack(format)
                if (request.keepAudio && source.audioFormat != null) outputAudioTrack = muxer!!.addTrack(source.audioFormat)
                muxer!!.start()
                muxerStarted = true
            }

            var encoderEosSignaled = false
            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream && !encoderEosSignaled) {
                    encoder!!.signalEndOfInputStream()
                    encoderEosSignaled = true
                }
                var idleCount = 0
                while (true) {
                    val status = encoder!!.dequeueOutputBuffer(encoderBufferInfo, CODEC_TIMEOUT_US)
                    when {
                        status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            idleCount++
                            if (idleCount > MAX_ENCODER_IDLE_POLLS) throw IllegalStateException("Tempo excedido finalizando o encoder")
                        }
                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idleCount = 0
                            startMuxerIfReady(encoder!!.outputFormat)
                        }
                        status >= 0 -> {
                            idleCount = 0
                            val buffer = encoder!!.getOutputBuffer(status)
                                ?: throw IllegalStateException("Buffer do encoder indisponível")
                            if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderBufferInfo.size = 0
                            if (encoderBufferInfo.size > 0) {
                                check(muxerStarted) { "Muxer ainda não iniciado" }
                                buffer.position(encoderBufferInfo.offset)
                                buffer.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                                muxer!!.writeSampleData(outputVideoTrack, buffer, encoderBufferInfo)
                                finalDurationUs = max(finalDurationUs, encoderBufferInfo.presentationTimeUs)
                            }
                            encoderDone = encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder!!.releaseOutputBuffer(status, false)
                            if (encoderDone) return
                        }
                    }
                }
            }

            fun submitFrame(ptsUs: Long, draw: () -> Unit) {
                draw()
                val safePtsUs = max(lastWrittenPtsUs + 1L, ptsUs)
                inputSurface!!.setPresentationTime(safePtsUs * 1_000L)
                check(inputSurface!!.swapBuffers()) { "Falha enviando quadro ao encoder" }
                lastWrittenPtsUs = safePtsUs
                encodedFrames++
                drainEncoder(false)
            }

            fun interpolateCorrection(
                from: MotionTrajectoryStabilizer.Correction,
                to: MotionTrajectoryStabilizer.Correction,
                alpha: Float
            ): MotionTrajectoryStabilizer.Correction {
                val a = alpha.coerceIn(0f, 1f)
                return MotionTrajectoryStabilizer.Correction(
                    xUv = from.xUv + (to.xUv - from.xUv) * a,
                    yUv = from.yUv + (to.yUv - from.yUv) * a,
                    rotationRad = from.rotationRad + (to.rotationRad - from.rotationRad) * a,
                    zoom = from.zoom + (to.zoom - from.zoom) * a,
                    jankDetected = from.jankDetected || to.jankDetected,
                    confidence = from.confidence + (to.confidence - from.confidence) * a
                )
            }

            fun decayCorrection(
                correction: MotionTrajectoryStabilizer.Correction
            ): MotionTrajectoryStabilizer.Correction {
                val factor = 0.72f
                return MotionTrajectoryStabilizer.Correction(
                    xUv = correction.xUv * factor,
                    yUv = correction.yUv * factor,
                    rotationRad = correction.rotationRad * factor,
                    zoom = 1f + (correction.zoom - 1f) * factor,
                    jankDetected = false,
                    confidence = correction.confidence * factor
                )
            }

            fun writeCurrentFrame(
                ptsUs: Long,
                correction: MotionTrajectoryStabilizer.Correction = MotionTrajectoryStabilizer.Correction()
            ) = submitFrame(ptsUs) { outputSurface!!.drawCurrent(correction) }

            fun writePreviousFrame(
                ptsUs: Long,
                correction: MotionTrajectoryStabilizer.Correction = MotionTrajectoryStabilizer.Correction()
            ) = submitFrame(ptsUs) { outputSurface!!.drawPrevious(correction) }

            fun writeBlendedFrame(
                ptsUs: Long,
                alpha: Float,
                correction: MotionTrajectoryStabilizer.Correction = MotionTrajectoryStabilizer.Correction()
            ) {
                submitFrame(ptsUs) { outputSurface!!.drawBlend(alpha, correction) }
                if (alpha > 0.02f && alpha < 0.98f) blendedFrames++
            }

            fun writeMotionFrame(
                ptsUs: Long,
                alpha: Float,
                correction: MotionTrajectoryStabilizer.Correction = MotionTrajectoryStabilizer.Correction()
            ) {
                submitFrame(ptsUs) { outputSurface!!.drawMotion(alpha, correction) }
                if (alpha > 0.02f && alpha < 0.98f) blendedFrames++
            }

            while (!encoderDone) {
                if (cancelled()) throw InterruptedException("Otimização cancelada")

                if (!extractorDone) {
                    val inputIndex = decoder!!.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder!!.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Buffer do decoder indisponível")
                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || sampleTimeUs > trimEndUs) {
                            decoder!!.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder!!.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outputIndex = decoder!!.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outputIndex >= 0 -> {
                            val doRender = decoderInfo.size > 0
                            decoderDone = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val decodedPtsUs = decoderInfo.presentationTimeUs
                            val renderInsideTrim = doRender && decodedPtsUs >= trimStartUs && decodedPtsUs <= trimEndUs
                            if (renderInsideTrim && firstSourcePts == Long.MIN_VALUE) firstSourcePts = decodedPtsUs
                            val sourceRelative = if (renderInsideTrim) (decodedPtsUs - firstSourcePts).coerceAtLeast(0L) else 0L

                            decoder!!.releaseOutputBuffer(outputIndex, renderInsideTrim)
                            if (renderInsideTrim) {
                                outputSurface!!.awaitNewImage()
                                outputSurface!!.captureCurrent()
                                lastSourceRelativePts = max(lastSourceRelativePts, sourceRelative)

                                when (request.frameRepair) {
                                    FrameRepairMode.FILL_MISSING_FRAMES,
                                    FrameRepairMode.ADAPTIVE_BLEND,
                                    FrameRepairMode.MOTION_COMPENSATED -> {
                                        if (!loadedFrame) {
                                            previousCorrection = MotionTrajectoryStabilizer.Correction()
                                            currentCorrection = MotionTrajectoryStabilizer.Correction()
                                            writeCurrentFrame(0L, currentCorrection)
                                            nextFillPtsUs = frameIntervalUs
                                            loadedFrame = true
                                        } else {
                                            val intervalUs = (sourceRelative - previousSourceRelativePts).coerceAtLeast(1L)
                                            val nominalSteps = kotlin.math.round(
                                                intervalUs.toDouble() / frameIntervalUs.toDouble()
                                            ).toInt().coerceAtLeast(1)
                                            val missingFrames = (nominalSteps - 1).coerceAtLeast(0)
                                            val motionAllowed = request.frameRepair == FrameRepairMode.MOTION_COMPENSATED &&
                                                missingFrames in 1..minOf(
                                                    request.maxInterpolatedFramesPerGap,
                                                    MAX_DENSE_MOTION_GAP_FRAMES
                                                )
                                            val blendAllowed = request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND &&
                                                missingFrames in 1..request.maxInterpolatedFramesPerGap

                                            previousCorrection = currentCorrection
                                            currentCorrection = when {
                                                // Optical flow denso é a parte mais cara do pipeline.
                                                // Só faça GPU->CPU + Farneback bidirecional quando
                                                // realmente existe pelo menos um slot temporal ausente.
                                                motionAllowed -> {
                                                    val field = outputSurface!!.currentMotionField()
                                                    trajectoryStabilizer.update(
                                                        motionXUv = field.globalForwardUvX,
                                                        motionYUv = field.globalForwardUvY,
                                                        rotationRad = if (field.globalRotationReliability >= 0.24f) {
                                                            field.globalRotationRadians
                                                        } else {
                                                            0f
                                                        },
                                                        reliability = field.globalReliability,
                                                        sceneChange = field.sceneChangeLikely,
                                                        unstable = field.globalMotionIsUnstable,
                                                        nearlyStatic = field.motionIsNearlyStatic
                                                    )
                                                }
                                                request.frameRepair == FrameRepairMode.MOTION_COMPENSATED ->
                                                    decayCorrection(previousCorrection)
                                                else ->
                                                    MotionTrajectoryStabilizer.Correction()
                                            }

                                            // A posição visual é derivada somente da fração entre os dois
                                            // quadros reais. A timeline de saída usa apenas a grade CFR.
                                            // Não misturamos mais PTS original irregular com PTS reconstruído.
                                            for (step in 1 until nominalSteps) {
                                                val alpha = step.toFloat() / nominalSteps.toFloat()
                                                val correction = interpolateCorrection(
                                                    previousCorrection,
                                                    currentCorrection,
                                                    alpha
                                                )
                                                when {
                                                    motionAllowed -> writeMotionFrame(nextFillPtsUs, alpha, correction)
                                                    blendAllowed -> writeBlendedFrame(nextFillPtsUs, alpha, correction)
                                                    request.frameRepair == FrameRepairMode.MOTION_COMPENSATED ->
                                                        writeBlendedFrame(nextFillPtsUs, alpha, correction)
                                                    alpha < 0.5f -> writePreviousFrame(nextFillPtsUs, correction)
                                                    else -> writeCurrentFrame(nextFillPtsUs, correction)
                                                }
                                                nextFillPtsUs += frameIntervalUs
                                            }

                                            writeCurrentFrame(nextFillPtsUs, currentCorrection)
                                            nextFillPtsUs += frameIntervalUs
                                        }
                                        previousSourceRelativePts = sourceRelative
                                    }
                                    FrameRepairMode.SMOOTH_TIMELINE -> {
                                        writeCurrentFrame(sourceDecodedFrames.toLong() * smoothStepUs)
                                    }
                                    FrameRepairMode.NONE -> {
                                        writeCurrentFrame(max(lastWrittenPtsUs + 1L, sourceRelative))
                                    }
                                }
                                sourceDecodedFrames++
                                val percent = ((sourceRelative * 88L / sourceSpanUs).toInt() + 5).coerceIn(5, 93)
                                val message = when (request.frameRepair) {
                                    FrameRepairMode.MOTION_COMPENSATED -> "Reconstrução seletiva: GPU/optical flow apenas nos gaps"
                                    FrameRepairMode.ADAPTIVE_BLEND -> "Reconstruindo cadência com mistura temporal por GPU"
                                    FrameRepairMode.FILL_MISSING_FRAMES -> "Preenchendo lacunas com o quadro mais próximo"
                                    FrameRepairMode.SMOOTH_TIMELINE -> "Regularizando a timeline"
                                    FrameRepairMode.NONE -> "Recodificando por hardware"
                                }
                                progress(percent, message)
                            }
                        }
                    }
                }

                if (decoderDone) {
                    if ((request.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||
                                request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND ||
                                request.frameRepair == FrameRepairMode.MOTION_COMPENSATED) && loadedFrame
                    ) {
                        val targetEndExclusiveUs = sourceSpanUs.coerceAtLeast(lastSourceRelativePts + frameIntervalUs)
                        while (nextFillPtsUs < targetEndExclusiveUs) {
                            writeCurrentFrame(nextFillPtsUs, currentCorrection)
                            nextFillPtsUs += frameIntervalUs
                        }
                    }
                    drainEncoder(true)
                } else {
                    drainEncoder(false)
                }
            }

            if (!muxerStarted) throw IllegalStateException("Encoder não produziu formato de saída")
            if (request.keepAudio && outputAudioTrack >= 0 && source.audioTrack >= 0) {
                progress(95, "Preservando áudio")
                copyAudio(input, source.audioTrack, outputAudioTrack, muxer!!, sourceSpanUs + AUDIO_TOLERANCE_US, trimStartUs, cancelled)
            }
            createdFrames = (encodedFrames - request.sourceFrameCount).coerceAtLeast(0)
            progress(100, "Validando saída otimizada")
            return Result(encodedFrames, createdFrames, blendedFrames, max(sourceSpanUs, finalDurationUs + frameIntervalUs))
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun copyAudio(
        input: File,
        sourceTrack: Int,
        outputTrack: Int,
        muxer: MediaMuxer,
        maxPtsUs: Long,
        sourceAudioStartPtsUs: Long,
        cancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            extractor.selectTrack(sourceTrack)
            val format = extractor.getTrackFormat(sourceTrack)
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 4 * 1024 * 1024)
            } else 512 * 1024
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            if (sourceAudioStartPtsUs > 0L) extractor.seekTo(sourceAudioStartPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var lastPts = -1L
            while (extractor.sampleTrackIndex >= 0) {
                if (cancelled()) throw InterruptedException("Otimização cancelada")
                val sourcePts = extractor.sampleTime
                if (sourcePts < 0L) break
                if (sourcePts < sourceAudioStartPtsUs) {
                    if (!extractor.advance()) break
                    continue
                }
                val pts = (sourcePts - sourceAudioStartPtsUs).coerceAtLeast(0L)
                if (pts > maxPtsUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val safePts = max(lastPts + 1L, pts)
                info.set(0, size, safePts, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outputTrack, buffer, info)
                lastPts = safePts
                if (!extractor.advance()) break
            }
        } finally {
            extractor.release()
        }
    }

    private data class SourceTracks(
        val videoTrack: Int,
        val audioTrack: Int,
        val videoFormat: MediaFormat,
        val audioFormat: MediaFormat?
    ) {
        companion object {
            fun open(file: File): SourceTracks {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    var video = -1
                    var audio = -1
                    var videoFormat: MediaFormat? = null
                    var audioFormat: MediaFormat? = null
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (video < 0 && mime.startsWith("video/")) { video = index; videoFormat = format }
                        if (audio < 0 && mime.startsWith("audio/")) { audio = index; audioFormat = format }
                    }
                    return SourceTracks(video, audio, videoFormat ?: error("Faixa de vídeo ausente"), audioFormat)
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private class EncoderInputSurface(private val surface: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init { setup() }

        private fun setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display indisponível" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Falha ao inicializar EGL" }
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) { "Config EGL indisponível" }
            val config = configs[0] ?: throw IllegalStateException("Config EGL vazia")
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "Contexto EGL indisponível" }
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "Surface EGL indisponível" }
        }

        fun makeCurrent() { check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) }
        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)
        fun setPresentationTime(nsecs: Long) { EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs) }
        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            surface.release()
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private class DecoderOutputSurface(
        private val width: Int,
        private val height: Int,
        highQualityMotion: Boolean
    ) : SurfaceTexture.OnFrameAvailableListener {
        private val frameSync = Object()
        private var frameAvailable = false
        private val renderer = TextureRenderer(width, height, highQualityMotion)
        private val surfaceTexture: SurfaceTexture
        val surface: Surface

        init {
            renderer.surfaceCreated()
            surfaceTexture = SurfaceTexture(renderer.externalTextureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(this@DecoderOutputSurface)
            }
            surface = Surface(surfaceTexture)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(frameSync) {
                frameAvailable = true
                frameSync.notifyAll()
            }
        }

        fun awaitNewImage() {
            synchronized(frameSync) {
                while (!frameAvailable) {
                    frameSync.wait(FRAME_WAIT_MS)
                    if (!frameAvailable) throw RuntimeException("Tempo excedido aguardando quadro decodificado")
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun captureCurrent() = renderer.captureFrame(surfaceTexture)
        fun currentMotionField(): OpenCvMotionEstimator.Field = renderer.currentMotionField()
        fun drawCurrent(correction: MotionTrajectoryStabilizer.Correction) = renderer.drawCurrent(correction)
        fun drawPrevious(correction: MotionTrajectoryStabilizer.Correction) = renderer.drawPrevious(correction)
        fun drawBlend(alpha: Float, correction: MotionTrajectoryStabilizer.Correction) =
            renderer.drawBlend(alpha, correction)
        fun drawMotion(alpha: Float, correction: MotionTrajectoryStabilizer.Correction) =
            renderer.drawMotion(alpha, correction)

        fun release() {
            surface.release()
            surfaceTexture.release()
            renderer.release()
        }
    }

    private class TextureRenderer(
        private val width: Int,
        private val height: Int,
        private val highQualityMotion: Boolean
    ) {
        private val triangleVertices: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTICES).position(0) }
        private val transform = FloatArray(16)
        private val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        private var externalProgram = 0
        private var blendProgram = 0
        private var motionInterpolateProgram = 0
        private val frameTextures = IntArray(2)
        private val framebuffers = IntArray(2)
        private val motionTextures = IntArray(2)
        private val motionFramebuffers = IntArray(2)
        private val analysisTexture = IntArray(1)
        private val analysisFramebuffer = IntArray(1)
        private val motionWidth = if (highQualityMotion) (width / 4).coerceIn(360, 960) else (width / 16).coerceIn(120, 240)
        private val motionHeight = ((motionWidth.toLong() * height.toLong()) / width.coerceAtLeast(1).toLong()).toInt().coerceIn(90, 540)
        private val motionReadback = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4).order(ByteOrder.nativeOrder())
        private val previousMotionPixels = ByteArray(motionWidth * motionHeight * 4)
        private val currentMotionPixels = ByteArray(motionWidth * motionHeight * 4)
        private val forwardMotionUpload = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4).order(ByteOrder.nativeOrder())
        private val backwardMotionUpload = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4).order(ByteOrder.nativeOrder())
        private var motionFlowScaleX = 0f
        private var motionFlowScaleY = 0f
        private var motionGlobalReliability = 1f
        private var motionSceneChange = false
        private var globalForwardUvX = 0f
        private var globalForwardUvY = 0f
        private var globalBackwardUvX = 0f
        private var globalBackwardUvY = 0f
        private var motionNearlyStatic = false
        private var globalMotionUnstable = false
        private var localWarpSafe = false
        private var cachedMotionField: OpenCvMotionEstimator.Field? = null
        private var motionFieldDirty = true
        private var currentIndex = -1
        private var previousIndex = -1
        var externalTextureId: Int = -1
            private set

        fun surfaceCreated() {
            val maximumTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTextureSize, 0)
            require(width <= maximumTextureSize[0] && height <= maximumTextureSize[0]) {
                "A GPU não suporta textura de ${width}×${height}; reduza a resolução de saída"
            }
            externalProgram = createProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)
            blendProgram = createProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)
            motionInterpolateProgram = createProgram(VERTEX_SHADER, MOTION_INTERPOLATE_FRAGMENT_SHADER)

            val external = IntArray(1)
            GLES20.glGenTextures(1, external, 0)
            externalTextureId = external[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glGenTextures(2, frameTextures, 0)
            GLES20.glGenFramebuffers(2, framebuffers, 0)
            for (index in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[index])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    width,
                    height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    frameTextures[index],
                    0
                )
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "Framebuffer de processamento incompleto"
                }
            }
            GLES20.glGenTextures(2, motionTextures, 0)
            GLES20.glGenFramebuffers(2, motionFramebuffers, 0)
            for (index in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTextures[index])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionWidth, motionHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFramebuffers[index])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionTextures[index], 0
                )
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "Framebuffer do campo de movimento bidirecional incompleto"
                }
            }

            GLES20.glGenTextures(1, analysisTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionWidth, motionHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glGenFramebuffers(1, analysisFramebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFramebuffer[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, analysisTexture[0], 0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer de análise de movimento incompleto"
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        fun captureFrame(surfaceTexture: SurfaceTexture) {
            previousIndex = currentIndex
            currentIndex = if (currentIndex < 0) 0 else 1 - currentIndex
            surfaceTexture.getTransformMatrix(transform)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[currentIndex])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(externalProgram)
            bindGeometry(externalProgram, transform)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(externalProgram, "sTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (previousIndex < 0) previousIndex = currentIndex
            motionFieldDirty = true
            cachedMotionField = null
            checkGl("capturar quadro")
        }

        fun currentMotionField(): OpenCvMotionEstimator.Field {
            ensureMotionField()
            return cachedMotionField
                ?: throw IllegalStateException("Campo de movimento indisponível")
        }

        fun drawCurrent(correction: MotionTrajectoryStabilizer.Correction) =
            drawTextures(currentIndex, currentIndex, 1f, correction)

        fun drawPrevious(correction: MotionTrajectoryStabilizer.Correction) =
            drawTextures(previousIndex, previousIndex, 1f, correction)

        fun drawBlend(alpha: Float, correction: MotionTrajectoryStabilizer.Correction) =
            drawTextures(previousIndex, currentIndex, alpha.coerceIn(0f, 1f), correction)

        fun drawMotion(alpha: Float, correction: MotionTrajectoryStabilizer.Correction) {
            check(previousIndex >= 0 && currentIndex >= 0) { "Quadros insuficientes para interpolação de movimento" }
            ensureMotionField()
            val safeAlpha = alpha.coerceIn(0f, 1f)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(motionInterpolateProgram)
            bindGeometry(motionInterpolateProgram, identity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[previousIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uPrevious"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[currentIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uCurrent"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTextures[0])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uForwardMotion"), 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTextures[1])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uBackwardMotion"), 3)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(motionInterpolateProgram, "uAlpha"), safeAlpha)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uFlowScale"),
                motionFlowScaleX,
                motionFlowScaleY
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uGlobalReliability"),
                motionGlobalReliability
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uSceneChange"),
                if (motionSceneChange) 1f else 0f
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uGlobalForward"),
                globalForwardUvX,
                globalForwardUvY
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uGlobalBackward"),
                globalBackwardUvX,
                globalBackwardUvY
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uMotionTexel"),
                1f / motionWidth.coerceAtLeast(1).toFloat(),
                1f / motionHeight.coerceAtLeast(1).toFloat()
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uMotionNearlyStatic"),
                if (motionNearlyStatic) 1f else 0f
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uGlobalMotionUnstable"),
                if (globalMotionUnstable) 1f else 0f
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uLocalWarpSafe"),
                if (localWarpSafe) 1f else 0f
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uCorrectionTranslation"),
                correction.xUv,
                correction.yUv
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uCorrectionRotation"),
                correction.rotationRad
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(motionInterpolateProgram, "uCorrectionZoom"),
                correction.zoom.coerceAtLeast(1f)
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("interpolar movimento")
        }

        private fun ensureMotionField() {
            if (!motionFieldDirty) return
            readMotionFrame(previousIndex, previousMotionPixels)
            readMotionFrame(currentIndex, currentMotionPixels)
            val field = OpenCvMotionEstimator.estimate(
                previousRgba = previousMotionPixels,
                currentRgba = currentMotionPixels,
                width = motionWidth,
                height = motionHeight,
                highQuality = highQualityMotion
            )
            motionFlowScaleX = field.flowScaleX
            motionFlowScaleY = field.flowScaleY
            motionGlobalReliability = field.globalReliability
            motionSceneChange = field.sceneChangeLikely
            globalForwardUvX = field.globalForwardUvX
            globalForwardUvY = field.globalForwardUvY
            globalBackwardUvX = field.globalBackwardUvX
            globalBackwardUvY = field.globalBackwardUvY
            motionNearlyStatic = field.motionIsNearlyStatic
            globalMotionUnstable = field.globalMotionIsUnstable
            localWarpSafe = field.localWarpSafe
            cachedMotionField = field
            uploadMotionField(motionTextures[0], forwardMotionUpload, field.forwardRgba)
            uploadMotionField(motionTextures[1], backwardMotionUpload, field.backwardRgba)
            motionFieldDirty = false
            checkGl("enviar campo de movimento")
        }

        private fun uploadMotionField(textureId: Int, buffer: ByteBuffer, rgba: ByteArray) {
            buffer.clear()
            buffer.put(rgba)
            buffer.flip()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                motionWidth,
                motionHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        private fun readMotionFrame(textureIndex: Int, target: ByteArray) {
            check(textureIndex >= 0) { "Quadro de movimento indisponível" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFramebuffer[0])
            GLES20.glViewport(0, 0, motionWidth, motionHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(blendProgram)
            bindGeometry(blendProgram, identity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[textureIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrevious"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[textureIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uCurrent"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uAlpha"), 1f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(blendProgram, "uCorrectionTranslation"), 0f, 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uCorrectionRotation"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uCorrectionZoom"), 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            motionReadback.clear()
            GLES20.glReadPixels(
                0, 0, motionWidth, motionHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, motionReadback
            )
            motionReadback.position(0)
            motionReadback.get(target, 0, target.size)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            checkGl("ler quadro para fluxo óptico")
        }

        private fun drawTextures(
            firstIndex: Int,
            secondIndex: Int,
            alpha: Float,
            correction: MotionTrajectoryStabilizer.Correction
        ) {
            check(firstIndex >= 0 && secondIndex >= 0) { "Nenhum quadro foi capturado para renderização" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(blendProgram)
            bindGeometry(blendProgram, identity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[firstIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrevious"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[secondIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uCurrent"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uAlpha"), alpha)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(blendProgram, "uCorrectionTranslation"),
                correction.xUv,
                correction.yUv
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(blendProgram, "uCorrectionRotation"),
                correction.rotationRad
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(blendProgram, "uCorrectionZoom"),
                correction.zoom.coerceAtLeast(1f)
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("renderizar quadro")
        }

        private fun bindGeometry(program: Int, matrix: FloatArray) {
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            triangleVertices.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices)
            GLES20.glEnableVertexAttribArray(positionHandle)
            triangleVertices.position(2)
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices)
            GLES20.glEnableVertexAttribArray(textureHandle)
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)
        }

        fun release() {
            if (externalTextureId >= 0) GLES20.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
            GLES20.glDeleteTextures(2, frameTextures, 0)
            GLES20.glDeleteFramebuffers(2, framebuffers, 0)
            GLES20.glDeleteTextures(2, motionTextures, 0)
            GLES20.glDeleteFramebuffers(2, motionFramebuffers, 0)
            GLES20.glDeleteTextures(1, analysisTexture, 0)
            GLES20.glDeleteFramebuffers(1, analysisFramebuffer, 0)
            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)
            if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)
            if (motionInterpolateProgram != 0) GLES20.glDeleteProgram(motionInterpolateProgram)
            externalTextureId = -1
            externalProgram = 0
            blendProgram = 0
            motionInterpolateProgram = 0
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            check(status[0] == GLES20.GL_TRUE) { "Falha ao vincular shader: $log" }
            return program
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            val log = GLES20.glGetShaderInfoLog(shader)
            check(status[0] == GLES20.GL_TRUE) { "Falha ao compilar shader: $log" }
            return shader
        }

        private fun checkGl(operation: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "Erro OpenGL ao $operation: 0x${error.toString(16)}" }
        }

        companion object {
            private const val STRIDE_BYTES = 4 * 4
            private val VERTICES = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            private const val VERTEX_SHADER = "attribute vec4 aPosition; attribute vec4 aTextureCoord; uniform mat4 uTextureMatrix; varying vec2 vTextureCoord; void main(){ gl_Position=aPosition; vTextureCoord=(uTextureMatrix*aTextureCoord).xy; }"
            private const val EXTERNAL_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
void main(){
    gl_FragColor=texture2D(sTexture,vTextureCoord);
}"""
            private const val BLEND_FRAGMENT_SHADER = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D uPrevious;
uniform sampler2D uCurrent;
uniform float uAlpha;
uniform vec2 uCorrectionTranslation;
uniform float uCorrectionRotation;
uniform float uCorrectionZoom;

vec2 correctedUv(vec2 uv){
    vec2 p=(uv-vec2(0.5))/max(uCorrectionZoom,1.0)-uCorrectionTranslation;
    float cs=cos(-uCorrectionRotation);
    float sn=sin(-uCorrectionRotation);
    p=mat2(cs,-sn,sn,cs)*p;
    return clamp(p+vec2(0.5),vec2(0.0),vec2(1.0));
}

void main(){
    float eased=uAlpha*uAlpha*(3.0-2.0*uAlpha);
    vec2 uv=correctedUv(vTextureCoord);
    gl_FragColor=mix(texture2D(uPrevious,uv),texture2D(uCurrent,uv),eased);
}"""
            private const val MOTION_INTERPOLATE_FRAGMENT_SHADER = """precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D uPrevious;
uniform sampler2D uCurrent;
uniform sampler2D uForwardMotion;
uniform sampler2D uBackwardMotion;
uniform vec2 uFlowScale;
uniform float uAlpha;
uniform float uGlobalReliability;
uniform float uSceneChange;
uniform vec2 uGlobalForward;
uniform vec2 uGlobalBackward;
uniform vec2 uMotionTexel;
uniform float uMotionNearlyStatic;
uniform float uGlobalMotionUnstable;
uniform float uLocalWarpSafe;
uniform vec2 uCorrectionTranslation;
uniform float uCorrectionRotation;
uniform float uCorrectionZoom;

vec2 correctedUv(vec2 uv){
    vec2 p=(uv-vec2(0.5))/max(uCorrectionZoom,1.0)-uCorrectionTranslation;
    float cs=cos(-uCorrectionRotation);
    float sn=sin(-uCorrectionRotation);
    p=mat2(cs,-sn,sn,cs)*p;
    return clamp(p+vec2(0.5),vec2(0.0),vec2(1.0));
}

vec4 smoothFlow(sampler2D tex, vec2 uv){
    vec2 px=uMotionTexel;
    vec4 c=texture2D(tex,uv);
    vec4 l=texture2D(tex,clamp(uv-vec2(px.x,0.0),vec2(0.0),vec2(1.0)));
    vec4 r=texture2D(tex,clamp(uv+vec2(px.x,0.0),vec2(0.0),vec2(1.0)));
    vec4 u=texture2D(tex,clamp(uv+vec2(0.0,px.y),vec2(0.0),vec2(1.0)));
    vec4 d=texture2D(tex,clamp(uv-vec2(0.0,px.y),vec2(0.0),vec2(1.0)));
    float cw=0.52+0.48*c.b;
    float lw=0.12*l.b;
    float rw=0.12*r.b;
    float uw=0.12*u.b;
    float dw=0.12*d.b;
    float sum=max(cw+lw+rw+uw+dw,0.0001);
    return (c*cw+l*lw+r*rw+u*uw+d*dw)/sum;
}

void main(){
    float a=clamp(uAlpha,0.0,1.0);
    vec2 baseUv=correctedUv(vTextureCoord);
    vec4 previous=texture2D(uPrevious,baseUv);
    vec4 current=texture2D(uCurrent,baseUv);
    vec4 simple=mix(previous,current,a);

    if(uSceneChange>0.5){
        gl_FragColor=(a<0.5)?previous:current;
        return;
    }
    if(uMotionNearlyStatic>0.5){
        gl_FragColor=simple;
        return;
    }

    vec2 prevUv=baseUv;
    vec2 currUv=baseUv;
    vec4 forward=smoothFlow(uForwardMotion,prevUv);
    vec4 backward=smoothFlow(uBackwardMotion,currUv);

    // Duas iterações aproximam o inverse warp. Uma única amostra do fluxo no
    // pixel de saída deixa erro espacial visível em objetos rápidos.
    for(int i=0;i<2;i++){
        vec2 forwardDelta=(forward.rg*2.0-1.0)*uFlowScale;
        vec2 backwardDelta=(backward.rg*2.0-1.0)*uFlowScale;
        prevUv=clamp(baseUv-forwardDelta*a,vec2(0.0),vec2(1.0));
        currUv=clamp(baseUv-backwardDelta*(1.0-a),vec2(0.0),vec2(1.0));
        forward=smoothFlow(uForwardMotion,prevUv);
        backward=smoothFlow(uBackwardMotion,currUv);
    }

    float prevConfidence=smoothstep(0.08,0.78,forward.b);
    float currConfidence=smoothstep(0.08,0.78,backward.b);

    // Se a região local é ambígua, acompanha a panorâmica/movimento global
    // em vez de congelar ou dissolver no lugar errado.
    vec2 localForward=(forward.rg*2.0-1.0)*uFlowScale;
    vec2 localBackward=(backward.rg*2.0-1.0)*uFlowScale;
    // Em campo local inseguro, abandona completamente a malha densa e usa
    // apenas translação global. Isso evita uma região deformada isolada no meio
    // do vídeo. Campo local só entra quando a análise global o aprovou.
    float useLocal = step(0.5,uLocalWarpSafe);
    vec2 safeGlobalForward = (uGlobalMotionUnstable>0.5) ? vec2(0.0) : uGlobalForward;
    vec2 safeGlobalBackward = (uGlobalMotionUnstable>0.5) ? vec2(0.0) : uGlobalBackward;
    vec2 locallyBlendedForward=mix(safeGlobalForward,localForward,prevConfidence);
    vec2 locallyBlendedBackward=mix(safeGlobalBackward,localBackward,currConfidence);
    vec2 effectiveForward=mix(safeGlobalForward,locallyBlendedForward,useLocal);
    vec2 effectiveBackward=mix(safeGlobalBackward,locallyBlendedBackward,useLocal);
    prevUv=clamp(baseUv-effectiveForward*a,vec2(0.0),vec2(1.0));
    currUv=clamp(baseUv-effectiveBackward*(1.0-a),vec2(0.0),vec2(1.0));

    vec4 prevWarped=texture2D(uPrevious,prevUv);
    vec4 currWarped=texture2D(uCurrent,currUv);
    float prevWeight=(1.0-a)*prevConfidence;
    float currWeight=a*currConfidence;
    float weightSum=max(prevWeight+currWeight,0.0001);
    vec4 warped=(prevWarped*prevWeight+currWarped*currWeight)/weightSum;

    float localReliability=clamp(max(prevConfidence,currConfidence)*0.78+
                                 min(prevConfidence,currConfidence)*0.22,0.0,1.0);
    float reliability=clamp(localReliability*uGlobalReliability,0.0,1.0);

    // Não voltar para crossfade quando o fluxo local perde confiança: isso
    // preserva cor, mas congela a posição aparente e causa o salto no frame
    // real seguinte. A confiança agora escolhe local vs movimento global acima;
    // aqui mantemos o warp como saída principal.
    float warpWeight;
    if(uLocalWarpSafe>0.5){
        warpWeight=0.90+0.10*reliability;
    }else if(uGlobalMotionUnstable>0.5){
        // Nenhum movimento espacial é confiável: dissolve suavemente e não
        // deforma o frame. Melhor uma transição neutra que um quadro quebrado.
        warpWeight=0.0;
    }else{
        // Global rígido é seguro para câmera/panorâmica e não dobra objetos.
        warpWeight=0.92;
    }
    gl_FragColor=mix(simple,warped,warpWeight);
}"""
        }
    }

    companion object {
        private const val MAX_DENSE_MOTION_GAP_FRAMES = 6
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_ENCODER_IDLE_POLLS = 3_000
        private const val FRAME_WAIT_MS = 5_000L
        private const val AUDIO_TOLERANCE_US = 250_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val RGBA_BYTES_PER_PIXEL = 4L
        private const val FRAME_TEXTURE_COUNT = 2L
        private const val MAX_GPU_FRAME_BYTES = 160L * 1024L * 1024L
    }
}
