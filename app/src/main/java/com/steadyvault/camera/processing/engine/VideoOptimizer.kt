package com.steadyvault.camera.processing.engine

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.timeline.TimelineRepairer
import com.steadyvault.camera.processing.transcode.HardwareVideoTranscoder
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class VideoOptimizer {
    data class Result(
        val output: File,
        val inputBytes: Long,
        val outputBytes: Long,
        val frameCount: Int,
        val repairedGaps: Int,
        val createdFrames: Int,
        val blendedFrames: Int,
        val transcoded: Boolean,
        val analysis: VideoAnalysis
    )

    fun optimize(
        input: File,
        output: File,
        requestedConfig: OptimizationConfig,
        progress: (Int, String) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false }
    ): Result {
        if (cancelled()) throw InterruptedException("Processamento cancelado")
        val config = requestedConfig.normalized()
        progress(1, "Analisando cadência e preparando reparo")
        val analysis = VideoAnalysis.read(input)
        val requestedFps = config.targetFps.takeIf { it in 1..240 }
        val targetFps = (requestedFps ?: analysis.estimatedFps).coerceIn(1, 240)
        if (!config.hasTrim() && config.preset == OptimizationPreset.REPAIR_ONLY && config.frameRepair == FrameRepairMode.NONE) {
            progress(10, "Copiando sem alterar o vídeo")
            if (cancelled()) throw InterruptedException("Processamento cancelado")
            input.copyTo(output, overwrite = true)
            progress(100, "Validando cópia sem recodificação")
            return Result(output, input.length(), output.length(), analysis.frameCount, 0, 0, 0, false, analysis)
        }

        if (config.preset == OptimizationPreset.REPAIR_ONLY &&
            config.frameRepair == FrameRepairMode.SMOOTH_TIMELINE &&
            !config.hasTrim()
        ) {
            progress(5, "Regularizando timestamps sem perda de imagem")
            val repaired = ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE
            ).use { descriptor ->
                TimelineRepairer().process(
                    input,
                    descriptor.fileDescriptor,
                    targetFps,
                    analysis.rotation,
                    progress,
                    cancelled
                )
            }
            if (cancelled()) {
                output.delete()
                throw InterruptedException("Processamento cancelado")
            }
            progress(100, "Validando reparo de timeline")
            return Result(
                output,
                input.length(),
                output.length(),
                repaired.frameCount,
                repaired.correctedGapCount,
                0,
                0,
                false,
                analysis
            )
        }

        val canResample = config.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||
            config.frameRepair == FrameRepairMode.ADAPTIVE_BLEND ||
            config.frameRepair == FrameRepairMode.MOTION_COMPENSATED
        if (requestedFps != null && abs(requestedFps.toDouble() - analysis.exactFps) > 0.75 && !canResample) {
            throw IllegalArgumentException("Para converter o FPS real, use um modo de reparo com interpolação")
        }

        require(!analysis.hdrHlg10) {
            "Vídeo HLG10 detectado. Para preservar HDR e cores, use Somente reparar com Regularizar timestamps; filtros e recodificação HDR exigem tone mapping dedicado."
        }

        val dimensions = targetDimensions(analysis, config)
        val codec = chooseCodec(analysis, config.codec, dimensions.first, dimensions.second, targetFps)
        val bitrate = targetBitrate(config, dimensions.first, dimensions.second, targetFps, codec)

        // O container pode declarar uma pequena cauda depois do último PTS de vídeo.
        // Para reparo CFR a fonte de verdade visual é o span entre primeiro e último PTS.
        // Isso preenche lacunas reais, mas não cria um quadro quase duplicado no encerramento.
        val nominalFrameUs = (1_000_000L / targetFps.coerceAtLeast(1)).coerceAtLeast(1L)
        val visualTimelineUs = analysis.presentationSpanUs.coerceAtLeast(nominalFrameUs)
        val trimStartUs = config.trimStartUs(visualTimelineUs)
        val trimEndUs = config.trimEndUs(visualTimelineUs)

        progress(3, "Preparando decoder, filtros GPU e encoder por hardware")
        val transcode = HardwareVideoTranscoder().transcode(
            input = input,
            output = output,
            request = HardwareVideoTranscoder.Request(
                outputMime = codec,
                width = dimensions.first,
                height = dimensions.second,
                fps = targetFps,
                bitrate = bitrate,
                preset = config.preset,
                rateMode = config.rateMode,
                orientationHint = analysis.rotation,
                frameRepair = config.frameRepair,
                sourceDurationUs = visualTimelineUs,
                sourceFrameCount = analysis.frameCount,
                keepAudio = config.keepAudio,
                maxInterpolatedFramesPerGap = config.maxInterpolatedFramesPerGap,
                // A fila automática usa REPAIR_ONLY. O campo denso em resolução alta
                // é caro demais para 4K60 e não traz ganho proporcional em gaps curtos.
                // Reserve-o apenas ao preset explícito HIGH_QUALITY.
                highQualityMotion =
                    config.frameRepair == FrameRepairMode.MOTION_COMPENSATED &&
                        config.preset == OptimizationPreset.HIGH_QUALITY,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs
            ),
            progress = progress,
            cancelled = cancelled
        )
        return Result(
            output = output,
            inputBytes = input.length(),
            outputBytes = output.length(),
            frameCount = transcode.outputFrames,
            repairedGaps = if (config.frameRepair == FrameRepairMode.NONE) 0 else analysis.largeGapCount + analysis.duplicateTimestampCount,
            createdFrames = transcode.createdFrames,
            blendedFrames = transcode.blendedFrames,
            transcoded = true,
            analysis = analysis
        )
    }

    private fun targetDimensions(analysis: VideoAnalysis, config: OptimizationConfig): Pair<Int, Int> {
        val sourceWidth = even(analysis.width)
        val sourceHeight = even(analysis.height)
        if (config.targetWidth > 0 && config.targetHeight > 0) {
            return fitWithin(sourceWidth, sourceHeight, config.targetWidth, config.targetHeight)
        }
        return sourceWidth to sourceHeight
    }

    private fun fitWithin(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= maxWidth && sourceHeight <= maxHeight) return sourceWidth to sourceHeight
        val scale = minOf(maxWidth.toDouble() / sourceWidth.toDouble(), maxHeight.toDouble() / sourceHeight.toDouble())
        return even((sourceWidth * scale).toInt()) to even((sourceHeight * scale).toInt())
    }

    private fun chooseCodec(analysis: VideoAnalysis, requested: OutputCodec, width: Int, height: Int, fps: Int): String {
        val candidates = when (requested) {
            OutputCodec.AVC -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
            OutputCodec.HEVC -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            OutputCodec.SOURCE -> listOfNotNull(
                analysis.sourceMime.takeIf { it == MediaFormat.MIMETYPE_VIDEO_HEVC || it == MediaFormat.MIMETYPE_VIDEO_AVC },
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaFormat.MIMETYPE_VIDEO_AVC
            ).distinct()
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return candidates.firstOrNull { mime ->
            codecs.any { info ->
                if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) return@any false
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities
                        ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
                }.getOrDefault(false)
            }
        } ?: throw IllegalStateException("Nenhum encoder suporta ${width}×${height} a $fps FPS")
    }

    private fun targetBitrate(config: OptimizationConfig, width: Int, height: Int, fps: Int, mime: String): Int {
        if (config.bitrateMbps > 0) return config.bitrateMbps.coerceIn(2, 240) * 1_000_000
        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
        val bitsPerPixel = when (config.preset) {
            OptimizationPreset.HIGH_QUALITY ->
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.14 else 0.20
            OptimizationPreset.REPAIR_ONLY ->
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.085 else 0.12
        }
        val calculated = (pixelsPerSecond * bitsPerPixel).toLong().coerceIn(4_000_000L, 200_000_000L)
        return max(4_000_000, calculated.toInt())
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2) and 1.inv()
}
