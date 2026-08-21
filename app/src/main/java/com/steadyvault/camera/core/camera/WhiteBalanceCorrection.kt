package com.steadyvault.camera.core.camera

import android.hardware.camera2.params.RggbChannelVector
import com.steadyvault.camera.core.settings.CaptureSettings

object WhiteBalanceCorrection {
    fun strength(mode: String, whiteBalanceMode: String, gains: RggbChannelVector?): Float {
        val automatic = if (
            mode == CaptureSettings.YELLOW_REDUCTION_AUTO &&
            whiteBalanceMode == CaptureSettings.WHITE_BALANCE_AUTO &&
            gains != null
        ) {
            val warmRatio = gains.blue / gains.red.coerceAtLeast(0.1f)
            (((warmRatio - 1.25f) / 1.25f) * 0.09f).coerceIn(0f, 0.09f)
        } else {
            0f
        }
        return when (mode) {
            CaptureSettings.YELLOW_REDUCTION_LIGHT -> 0.06f
            CaptureSettings.YELLOW_REDUCTION_MEDIUM -> 0.12f
            CaptureSettings.YELLOW_REDUCTION_STRONG -> 0.20f
            CaptureSettings.YELLOW_REDUCTION_AUTO -> automatic
            else -> 0f
        }
    }

    fun adjustedGains(gains: RggbChannelVector, strength: Float): RggbChannelVector =
        RggbChannelVector(
            (gains.red * (1f - strength * 0.55f)).coerceIn(1f, 8f),
            gains.greenEven.coerceIn(1f, 8f),
            gains.greenOdd.coerceIn(1f, 8f),
            (gains.blue * (1f + strength)).coerceIn(1f, 8f)
        )

    fun useIncandescentFallback(strength: Float): Boolean = strength >= 0.10f
}
