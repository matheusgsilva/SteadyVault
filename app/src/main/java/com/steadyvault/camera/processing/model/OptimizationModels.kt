package com.steadyvault.camera.processing.model

enum class OptimizationPreset {
    REPAIR_ONLY,
    HIGH_QUALITY;

    companion object {
        fun from(value: String?): OptimizationPreset =
            entries.firstOrNull { it.name == value } ?: REPAIR_ONLY
    }
}
enum class FrameRepairMode {
    NONE,
    SMOOTH_TIMELINE,
    FILL_MISSING_FRAMES,
    ADAPTIVE_BLEND,
    MOTION_COMPENSATED;

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

data class OptimizationConfig(
    val preset: OptimizationPreset = OptimizationPreset.REPAIR_ONLY,
    val frameRepair: FrameRepairMode = FrameRepairMode.SMOOTH_TIMELINE,
    val codec: OutputCodec = OutputCodec.HEVC,
    val rateMode: OptimizationRateMode = OptimizationRateMode.AUTO,
    val targetFps: Int = 0,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val bitrateMbps: Int = 0,
    val keepAudio: Boolean = true,
    val replaceOriginal: Boolean = false,
    val maxInterpolatedFramesPerGap: Int = 8,
    val thermalProtection: Boolean = true,
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
