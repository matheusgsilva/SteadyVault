package com.steadyvault.camera.processing.model

enum class OptimizationPreset(
    val displayName: String,
    val description: String
) {
    REPAIR_ONLY(
        "Reparar fluidez sem mudar a imagem",
        "Regulariza a cadência e evita filtros visuais desnecessários."
    ),
    HIGH_QUALITY(
        "Qualidade máxima visual",
        "Usa mais bitrate e processamento para preservar detalhes."
    ),
    BALANCED(
        "Equilibrar qualidade e tamanho",
        "Mantém boa qualidade com carga e tamanho moderados."
    ),
    SMALL_FILE(
        "Reduzir tamanho do arquivo",
        "Usa HEVC e limita vídeos grandes a 1080p."
    ),
    VERY_FAST_1080P(
        "Very Fast 1080p",
        "AVC 1080p com processamento rápido, boa compatibilidade e FPS original."
    ),
    FAST_1080P(
        "Fast 1080p",
        "AVC 1080p equilibrado para uso geral, mantendo o FPS original."
    ),
    HQ_1080P(
        "HQ 1080p",
        "AVC 1080p com qualidade constante e bitrate maior para preservar detalhes."
    ),
    FAST_720P(
        "Fast 720p",
        "AVC 720p rápido e compatível para telas menores e compartilhamento."
    ),
    HQ_720P(
        "HQ 720p",
        "AVC 720p com prioridade para qualidade e áudio original."
    ),
    CREATOR_2160P_4K(
        "Creator 2160p 4K",
        "AVC até 4K com alta qualidade para plataformas que recodificam o envio."
    ),
    CREATOR_1080P(
        "Creator 1080p",
        "AVC 1080p de alta qualidade para publicação e nova recodificação."
    ),
    SOCIAL_720P(
        "Social 720p",
        "AVC 720p com tamanho moderado para redes sociais e mensageiros."
    ),
    APPLE_2160P_4K_HEVC(
        "Apple 2160p 4K HEVC",
        "HEVC até 4K com qualidade constante e áudio AAC original."
    ),
    APPLE_1080P_SURROUND(
        "Apple 1080p Surround",
        "AVC 1080p compatível; preserva a faixa AAC original, inclusive multicanal quando já existir."
    ),
    ANDROID_1080P(
        "Android 1080p",
        "AVC 1080p para aparelhos atuais, mantendo áudio e FPS originais."
    ),
    ANDROID_720P(
        "Android 720p",
        "AVC 720p para ampla compatibilidade e arquivo moderado."
    ),
    WEB_1080P(
        "Web 1080p",
        "AVC 1080p com VBR para envio e reprodução em navegadores."
    ),
    ARCHIVE_4K(
        "Arquivo 4K de alta qualidade",
        "HEVC até 4K com prioridade para detalhes e áudio original."
    ),
    SMART(
        "Escolher automaticamente",
        "Analisa o vídeo no aparelho e escolhe ajustes conservadores."
    ),
    CUSTOM(
        "Configuração manual",
        "Mantém exatamente os controles escolhidos nesta tela."
    );

    companion object {
        fun from(value: String?): OptimizationPreset = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

enum class FrameRepairMode {
    NONE,
    SMOOTH_TIMELINE,
    FILL_MISSING_FRAMES,
    ADAPTIVE_BLEND;

    companion object {
        fun from(value: String?): FrameRepairMode = entries.firstOrNull { it.name == value } ?: SMOOTH_TIMELINE
    }
}

enum class OptimizationRateMode {
    AUTO,
    CONSTANT_QUALITY,
    VBR,
    CBR;

    companion object {
        fun from(value: String?): OptimizationRateMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class OutputCodec {
    SOURCE,
    HEVC,
    AVC;

    companion object {
        fun from(value: String?): OutputCodec = entries.firstOrNull { it.name == value } ?: HEVC
    }
}

enum class FilterStrength(val amount: Float) {
    OFF(0f),
    LIGHT(0.25f),
    MEDIUM(0.55f),
    STRONG(0.85f);

    companion object {
        fun from(value: String?): FilterStrength = entries.firstOrNull { it.name == value } ?: OFF
    }
}

data class VideoFilterConfig(
    val denoise: FilterStrength = FilterStrength.OFF,
    val sharpen: FilterStrength = FilterStrength.OFF,
    val deblock: FilterStrength = FilterStrength.OFF,
    val brightness: Int = 0,
    val contrast: Int = 100,
    val saturation: Int = 100,
    val temperature: Int = 0,
    val tint: Int = 0
) {
    val enabled: Boolean
        get() = denoise != FilterStrength.OFF || sharpen != FilterStrength.OFF ||
            deblock != FilterStrength.OFF || brightness != 0 || contrast != 100 || saturation != 100 ||
            temperature != 0 || tint != 0

    fun normalized(): VideoFilterConfig = copy(
        brightness = brightness.coerceIn(-25, 25),
        contrast = contrast.coerceIn(75, 135),
        saturation = saturation.coerceIn(70, 140),
        temperature = temperature.coerceIn(-50, 50),
        tint = tint.coerceIn(-30, 30)
    )
}

data class OptimizationConfig(
    val preset: OptimizationPreset = OptimizationPreset.BALANCED,
    val frameRepair: FrameRepairMode = FrameRepairMode.SMOOTH_TIMELINE,
    val codec: OutputCodec = OutputCodec.HEVC,
    val rateMode: OptimizationRateMode = OptimizationRateMode.AUTO,
    val targetFps: Int = 0,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val bitrateMbps: Int = 0,
    val keepAudio: Boolean = true,
    val replaceOriginal: Boolean = false,
    val filters: VideoFilterConfig = VideoFilterConfig(),
    val maxInterpolatedFramesPerGap: Int = 8,
    val thermalProtection: Boolean = true,
    val smartAutoTune: Boolean = false,
    val aiAssisted: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L
) {
    fun normalized(): OptimizationConfig {
        val safeStart = trimStartMs.coerceAtLeast(0L)
        val safeEnd = trimEndMs.coerceAtLeast(0L)
        return copy(
            targetFps = targetFps.coerceIn(0, 240),
            targetWidth = targetWidth.coerceAtLeast(0),
            targetHeight = targetHeight.coerceAtLeast(0),
            bitrateMbps = bitrateMbps.coerceIn(0, 240),
            filters = filters.normalized(),
            maxInterpolatedFramesPerGap = maxInterpolatedFramesPerGap.coerceIn(1, 30),
            trimStartMs = safeStart,
            trimEndMs = if (safeEnd > safeStart) safeEnd else 0L
        )
    }

    fun hasTrim(): Boolean = trimStartMs > 0L || trimEndMs > 0L

    fun trimStartUs(sourceDurationUs: Long): Long = (trimStartMs * 1_000L)
        .coerceIn(0L, (sourceDurationUs - 1_000L).coerceAtLeast(0L))

    fun trimEndUs(sourceDurationUs: Long): Long {
        val duration = sourceDurationUs.coerceAtLeast(1_000L)
        val requestedEnd = if (trimEndMs > 0L) trimEndMs * 1_000L else duration
        return requestedEnd.coerceIn(trimStartUs(duration) + 1_000L, duration)
    }

    fun trimmedDurationUs(sourceDurationUs: Long): Long {
        val normalized = normalized()
        return (normalized.trimEndUs(sourceDurationUs) - normalized.trimStartUs(sourceDurationUs)).coerceAtLeast(1_000L)
    }
}
