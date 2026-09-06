package com.steadyvault.camera.processing.analysis

import com.steadyvault.camera.processing.ai.AiVideoEnhancer
import com.steadyvault.camera.processing.model.FilterStrength
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.model.VideoFilterConfig
import kotlin.math.max

data class OptimizationRecommendation(
    val config: OptimizationConfig,
    val title: String,
    val reasons: List<String>,
    val estimatedOutputMbps: Int,
    val aiReport: AiVideoEnhancer.Report? = null
) {
    fun summary(): String = buildString {
        append(title)
        aiReport?.let { append("\n• ").append(it.summary()) }
        reasons.forEach { append("\n• ").append(it) }
        append("\n• Bitrate estimado: ").append(estimatedOutputMbps).append(" Mbps")
    }
}

object OptimizationAdvisor {
    fun recommend(
        analysis: VideoAnalysis,
        aiReport: AiVideoEnhancer.Report? = null
    ): OptimizationRecommendation {
        val missingRatio = analysis.estimatedMissingFrames.toDouble() / max(1, analysis.frameCount).toDouble()
        val maximumGapFrames = analysis.maximumFrameDeltaUs.toDouble() *
            analysis.estimatedFps.toDouble() / 1_000_000.0
        val cadenceRepair = when {
            !analysis.hasCadenceProblems -> FrameRepairMode.NONE
            analysis.estimatedMissingFrames <= 0 -> FrameRepairMode.SMOOTH_TIMELINE
            missingRatio <= 0.01 &&
                analysis.largeGapCount in 1..4 &&
                maximumGapFrames <= 2.4 &&
                (aiReport?.motionIntensity ?: 0f) < 0.14f ->
                FrameRepairMode.ADAPTIVE_BLEND
            else -> FrameRepairMode.FILL_MISSING_FRAMES
        }
        val repair = aiReport?.preferredRepairMode ?: cadenceRepair
        val pixels = analysis.width.toLong() * analysis.height.toLong()
        val lowBitrateForSize = when {
            pixels >= 3_500_000L -> analysis.sourceBitrateMbps < 18.0
            pixels >= 1_500_000L -> analysis.sourceBitrateMbps < 8.0
            else -> analysis.sourceBitrateMbps < 4.0
        }
        val improvementsNeeded = aiReport?.improvementNeeded
            ?: (analysis.hasCadenceProblems || lowBitrateForSize)
        val effectiveRepair = if (improvementsNeeded) repair else FrameRepairMode.NONE
        val filters = aiReport?.filters ?: VideoFilterConfig(
            denoise = if (lowBitrateForSize) FilterStrength.LIGHT else FilterStrength.OFF,
            deblock = if (lowBitrateForSize) FilterStrength.LIGHT else FilterStrength.OFF,
            sharpen = FilterStrength.OFF
        )
        val bitrate = recommendedBitrateMbps(analysis, OutputCodec.HEVC, OptimizationPreset.BALANCED)
        val reasons = buildList {
            add("Cadência ${analysis.cadenceLabel} (${analysis.cadenceScore}/100)")
            add("FPS nominal ${analysis.estimatedFps} com ${analysis.nominalFpsConfidence}% de confiança; média real ${"%.2f".format(analysis.exactFps)}")
            when (effectiveRepair) {
                FrameRepairMode.NONE -> add("Não há evidência suficiente para fabricar quadros")
                FrameRepairMode.SMOOTH_TIMELINE -> add("Os timestamps serão regularizados sem criar imagens")
                FrameRepairMode.FILL_MISSING_FRAMES -> add("Lacunas usarão o quadro temporalmente mais próximo")
                FrameRepairMode.ADAPTIVE_BLEND -> add("Lacunas curtas receberão mistura temporal simples")
                FrameRepairMode.MOTION_COMPENSATED -> add("Lacunas receberão interpolação compensada por movimento")
            }
            if (aiReport != null) addAll(aiReport.reasons)
            else if (lowBitrateForSize) add("Filtro leve para reduzir blocos e ruído de compressão")
            add("HEVC por hardware para equilibrar tamanho e qualidade")
        }
        return OptimizationRecommendation(
            config = OptimizationConfig(
                preset = if (improvementsNeeded) OptimizationPreset.SMART else OptimizationPreset.REPAIR_ONLY,
                frameRepair = effectiveRepair,
                codec = OutputCodec.HEVC,
                rateMode = OptimizationRateMode.AUTO,
                targetFps = if (effectiveRepair == FrameRepairMode.NONE) 0 else analysis.estimatedFps,
                bitrateMbps = bitrate,
                keepAudio = analysis.hasAudio,
                filters = filters,
                maxInterpolatedFramesPerGap = if (
                    effectiveRepair == FrameRepairMode.ADAPTIVE_BLEND ||
                    effectiveRepair == FrameRepairMode.MOTION_COMPENSATED
                ) 2 else 1,
                thermalProtection = true,
                aiAssisted = aiReport != null
            ),
            title = when {
                !improvementsNeeded -> "Vídeo já está estável; não recodificar"
                aiReport != null -> "Melhoria local recomendada"
                else -> "Otimização inteligente recomendada"
            },
            reasons = reasons,
            estimatedOutputMbps = bitrate,
            aiReport = aiReport
        )
    }

    fun recommendedBitrateMbps(
        analysis: VideoAnalysis,
        codec: OutputCodec,
        preset: OptimizationPreset,
        targetWidth: Int = analysis.width,
        targetHeight: Int = analysis.height,
        targetFps: Int = analysis.estimatedFps
    ): Int {
        val hevc = codec != OutputCodec.AVC
        val bitsPerPixel = when (preset) {
            OptimizationPreset.HIGH_QUALITY -> if (hevc) 0.115 else 0.16
            OptimizationPreset.ARCHIVE_4K -> if (hevc) 0.12 else 0.18
            OptimizationPreset.VERY_FAST_1080P -> if (hevc) 0.05 else 0.08
            OptimizationPreset.FAST_1080P -> if (hevc) 0.068 else 0.105
            OptimizationPreset.HQ_1080P -> if (hevc) 0.105 else 0.16
            OptimizationPreset.FAST_720P -> if (hevc) 0.06 else 0.095
            OptimizationPreset.HQ_720P -> if (hevc) 0.095 else 0.145
            OptimizationPreset.CREATOR_2160P_4K -> if (hevc) 0.115 else 0.175
            OptimizationPreset.CREATOR_1080P -> if (hevc) 0.11 else 0.165
            OptimizationPreset.SOCIAL_720P -> if (hevc) 0.048 else 0.075
            OptimizationPreset.APPLE_2160P_4K_HEVC -> if (hevc) 0.115 else 0.175
            OptimizationPreset.APPLE_1080P_SURROUND -> if (hevc) 0.09 else 0.14
            OptimizationPreset.ANDROID_1080P -> if (hevc) 0.075 else 0.115
            OptimizationPreset.ANDROID_720P -> if (hevc) 0.07 else 0.105
            OptimizationPreset.WEB_1080P -> if (hevc) 0.08 else 0.12
            OptimizationPreset.SMALL_FILE -> if (hevc) 0.045 else 0.07
            OptimizationPreset.REPAIR_ONLY -> if (hevc) 0.075 else 0.105
            else -> if (hevc) 0.075 else 0.105
        }
        return ((targetWidth.toLong() * targetHeight.toLong() * targetFps.toLong() * bitsPerPixel) / 1_000_000.0)
            .toInt()
            .coerceIn(4, 200)
    }
}
