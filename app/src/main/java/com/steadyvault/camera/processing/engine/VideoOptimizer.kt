package com.steadyvault.camera.processing.engine

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import com.steadyvault.camera.processing.analysis.OptimizationAdvisor
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.ai.AiVideoEnhancer
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
        val analysis: VideoAnalysis,
        val aiReport: AiVideoEnhancer.Report? = null
    )

    fun optimize(
        input: File,
        output: File,
        requestedConfig: OptimizationConfig,
        progress: (Int, String) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false }
    ): Result {
        if (cancelled()) throw InterruptedException("Otimização cancelada")
        val normalizedConfig = requestedConfig.normalized()
        progress(1, "Analisando cadência, resolução e codecs")
        val analysis = VideoAnalysis.read(input)
        val aiReport = if (normalizedConfig.aiAssisted || (normalizedConfig.preset == OptimizationPreset.SMART && normalizedConfig.smartAutoTune)) {
            progress(2, "Análise visual local avaliando cadência, cor, ruído, exposição e movimento")
            runCatching { AiVideoEnhancer.analyze(input, analysis, progress, cancelled) }.getOrNull()
        } else null
        val config = when {
            normalizedConfig.preset == OptimizationPreset.SMART && normalizedConfig.smartAutoTune -> {
                OptimizationAdvisor.recommend(analysis, aiReport).config.copy(
                    keepAudio = normalizedConfig.keepAudio,
                    replaceOriginal = normalizedConfig.replaceOriginal,
                    targetWidth = normalizedConfig.targetWidth,
                    targetHeight = normalizedConfig.targetHeight,
                    thermalProtection = normalizedConfig.thermalProtection,
                    smartAutoTune = false,
                    aiAssisted = aiReport != null,
                    trimStartMs = normalizedConfig.trimStartMs,
                    trimEndMs = normalizedConfig.trimEndMs
                ).normalized()
            }
            aiReport != null && normalizedConfig.preset != OptimizationPreset.REPAIR_ONLY -> normalizedConfig.copy(
                filters = normalizedConfig.filters.copy(
                    denoise = if (normalizedConfig.filters.denoise.name == "OFF") aiReport.filters.denoise else normalizedConfig.filters.denoise,
                    sharpen = if (normalizedConfig.filters.sharpen.name == "OFF") aiReport.filters.sharpen else normalizedConfig.filters.sharpen,
                    deblock = if (normalizedConfig.filters.deblock.name == "OFF") aiReport.filters.deblock else normalizedConfig.filters.deblock,
                    brightness = if (normalizedConfig.filters.brightness == 0) aiReport.filters.brightness else normalizedConfig.filters.brightness,
                    contrast = if (normalizedConfig.filters.contrast == 100) aiReport.filters.contrast else normalizedConfig.filters.contrast,
                    saturation = if (normalizedConfig.filters.saturation == 100) aiReport.filters.saturation else normalizedConfig.filters.saturation,
                    temperature = if (normalizedConfig.filters.temperature == 0) aiReport.filters.temperature else normalizedConfig.filters.temperature,
                    tint = if (normalizedConfig.filters.tint == 0) aiReport.filters.tint else normalizedConfig.filters.tint
                ),
                aiAssisted = true
            ).normalized()
            aiReport != null -> normalizedConfig
            else -> normalizedConfig
        }
        val requestedFps = config.targetFps.takeIf { it in 1..240 }
        val targetFps = (requestedFps ?: analysis.estimatedFps).coerceIn(1, 240)
        val needsVisualProcessing = config.filters.enabled

        if (!config.hasTrim() && config.preset == OptimizationPreset.REPAIR_ONLY && config.frameRepair == FrameRepairMode.NONE && !needsVisualProcessing) {
            progress(10, "Copiando sem alterar o vídeo")
            if (cancelled()) throw InterruptedException("Otimização cancelada")
            input.copyTo(output, overwrite = true)
            progress(100, "Validando cópia sem recodificação")
            return Result(output, input.length(), output.length(), analysis.frameCount, 0, 0, 0, false, analysis, aiReport)
        }

        if (config.preset == OptimizationPreset.REPAIR_ONLY &&
            config.frameRepair == FrameRepairMode.SMOOTH_TIMELINE &&
            !needsVisualProcessing &&
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
                throw InterruptedException("Otimização cancelada")
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
                analysis,
                aiReport
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
                sourceDurationUs = analysis.durationUs,
                sourceFrameCount = analysis.frameCount,
                keepAudio = config.keepAudio,
                filters = config.filters,
                maxInterpolatedFramesPerGap = config.maxInterpolatedFramesPerGap,
                // Compensação de movimento é sempre um reparo de qualidade. A opção
                // aiAssisted continua controlando análise/filtros inteligentes, mas não
                // reduz mais a precisão básica do optical flow quando há frames ausentes.
                highQualityMotion = config.frameRepair == FrameRepairMode.MOTION_COMPENSATED || config.aiAssisted,
                trimStartUs = config.trimStartUs(analysis.durationUs),
                trimEndUs = config.trimEndUs(analysis.durationUs)
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
            analysis = analysis,
            aiReport = aiReport
        )
    }

    private fun targetDimensions(analysis: VideoAnalysis, config: OptimizationConfig): Pair<Int, Int> {
        val sourceWidth = even(analysis.width)
        val sourceHeight = even(analysis.height)
        if (config.targetWidth > 0 && config.targetHeight > 0) {
            return fitWithin(sourceWidth, sourceHeight, config.targetWidth, config.targetHeight)
        }
        return when (config.preset) {
            OptimizationPreset.SMALL_FILE,
            OptimizationPreset.VERY_FAST_1080P,
            OptimizationPreset.FAST_1080P,
            OptimizationPreset.HQ_1080P,
            OptimizationPreset.CREATOR_1080P,
            OptimizationPreset.APPLE_1080P_SURROUND,
            OptimizationPreset.ANDROID_1080P,
            OptimizationPreset.WEB_1080P ->
                fitWithin(sourceWidth, sourceHeight, 1920, 1080)
            OptimizationPreset.FAST_720P,
            OptimizationPreset.HQ_720P,
            OptimizationPreset.SOCIAL_720P,
            OptimizationPreset.ANDROID_720P -> fitWithin(sourceWidth, sourceHeight, 1280, 720)
            OptimizationPreset.CREATOR_2160P_4K,
            OptimizationPreset.APPLE_2160P_4K_HEVC,
            OptimizationPreset.ARCHIVE_4K -> fitWithin(sourceWidth, sourceHeight, 3840, 2160)
            else -> sourceWidth to sourceHeight
        }
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
            OptimizationPreset.HIGH_QUALITY -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.14 else 0.20
            OptimizationPreset.ARCHIVE_4K -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.13 else 0.19
            OptimizationPreset.VERY_FAST_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.055 else 0.085
            OptimizationPreset.FAST_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.072 else 0.11
            OptimizationPreset.HQ_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.11 else 0.17
            OptimizationPreset.FAST_720P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.065 else 0.10
            OptimizationPreset.HQ_720P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.10 else 0.15
            OptimizationPreset.CREATOR_2160P_4K -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.12 else 0.18
            OptimizationPreset.CREATOR_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.115 else 0.175
            OptimizationPreset.SOCIAL_720P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.05 else 0.078
            OptimizationPreset.APPLE_2160P_4K_HEVC -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.12 else 0.18
            OptimizationPreset.APPLE_1080P_SURROUND -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.10 else 0.15
            OptimizationPreset.ANDROID_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.078 else 0.12
            OptimizationPreset.ANDROID_720P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.075 else 0.11
            OptimizationPreset.WEB_1080P -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.085 else 0.125
            OptimizationPreset.SMALL_FILE -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.045 else 0.07
            OptimizationPreset.REPAIR_ONLY -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.085 else 0.12
            else -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.075 else 0.105
        }
        val calculated = (pixelsPerSecond * bitsPerPixel).toLong().coerceIn(4_000_000L, 200_000_000L)
        return max(4_000_000, calculated.toInt())
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2) and 1.inv()
}
