package com.steadyvault.camera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import com.steadyvault.camera.core.settings.CaptureSettings

object CctWhiteBalanceController {
    fun applyIfSupported(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        yellowReduction: String,
        whiteBalanceMode: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        if (whiteBalanceMode != CaptureSettings.WHITE_BALANCE_AUTO || yellowReduction == CaptureSettings.YELLOW_REDUCTION_OFF) return false
        val availableModes = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES) ?: return false
        if (!availableModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT)) return false
        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (!awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)) return false
        val range = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) ?: return false
        val requested = when (yellowReduction) {
            CaptureSettings.YELLOW_REDUCTION_STRONG -> 4_200
            CaptureSettings.YELLOW_REDUCTION_MEDIUM -> 4_600
            CaptureSettings.YELLOW_REDUCTION_LIGHT -> 5_000
            CaptureSettings.YELLOW_REDUCTION_WARM_LIGHT -> 6_000
            CaptureSettings.YELLOW_REDUCTION_WARM_MEDIUM -> 6_500
            CaptureSettings.YELLOW_REDUCTION_WARM_STRONG -> 7_200
            else -> 4_800
        }.coerceIn(range.lower, range.upper)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
        builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, requested)
        builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, 0)
        return true
    }
}
