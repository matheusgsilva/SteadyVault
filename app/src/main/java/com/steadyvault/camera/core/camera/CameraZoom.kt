package com.steadyvault.camera.core.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import kotlin.math.roundToInt

/** Aplica zoom lógico/digital preservando a troca automática de lentes em câmeras lógicas. */
object CameraZoom {
    fun clamp(characteristics: CameraCharacteristics, requested: Float): Float {
        val safe = requested.takeIf { it.isFinite() } ?: 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                return safe.coerceIn(range.lower, range.upper)
            }
        }
        val maximum = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.coerceAtLeast(1f) ?: 1f
        return safe.coerceIn(1f, maximum)
    }

    fun sensorRegion(characteristics: CameraCharacteristics, requested: Float): Rect? {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val ratio = clamp(characteristics, requested)
        if (ratio <= 1f) return Rect(active)
        val cropWidth = (active.width() / ratio).toInt().coerceAtLeast(2)
        val cropHeight = (active.height() / ratio).toInt().coerceAtLeast(2)
        val left = active.left + (active.width() - cropWidth) / 2
        val top = active.top + (active.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    fun apply(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        requested: Float
    ): Float {
        // O request constrained high-speed sai cedo de CaptureService e não passa pelo
        // bloco regular de compensação de exposição. Aplique aqui um bias moderado
        // somente quando o próprio request já está marcado como 120/240 FPS.
        applyHighSpeedExposureBias(builder, characteristics)

        val ratio = clamp(characteristics, requested)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                characteristics.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_ZOOM_METHOD)
            ) {
                runCatching { builder.set(CaptureRequest.CONTROL_ZOOM_METHOD, CameraMetadata.CONTROL_ZOOM_METHOD_ZOOM_RATIO) }
            }
            runCatching { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio) }
            return ratio
        }

        sensorRegion(characteristics, ratio)?.let { region ->
            runCatching { builder.set(CaptureRequest.SCALER_CROP_REGION, region) }
        }
        return ratio
    }

    private fun applyHighSpeedExposureBias(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        val fpsRange = runCatching {
            builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        }.getOrNull() ?: return
        val targetFps = fpsRange.upper
        if (targetFps < 120) return

        // Se outro caminho já definiu compensação explicitamente, não sobrescreva.
        val current = runCatching {
            builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
        }.getOrNull()
        if (current != null && current != 0) return

        val supportedRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return

        // 240 FPS dispõe de só ~4,17 ms por quadro; 120 FPS, ~8,33 ms. O bias abaixo
        // pede à AE que compense principalmente via ganho/ISO sem reduzir a cadência.
        val desiredEv = if (targetFps >= 240) 1.0f else 0.67f
        val compensation = (desiredEv / step).roundToInt()
            .coerceIn(supportedRange.lower, supportedRange.upper)
        if (compensation != 0) {
            runCatching {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            }
        }
    }
}
