package com.steadyvault.camera.core.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build

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

    /**
     * Retângulo em que as regiões de medição (AF/AE/AWB) são expressas. Com CONTROL_ZOOM_RATIO
     * (Android 11+) o sistema de coordenadas é PÓS-zoom: o array ativo inteiro cobre o campo de
     * visão já com zoom. Usar o recorte físico (sensorRegion) deslocava o foco em direção ao
     * centro (com 2x, um alvo em x=0,9 virava ~0,7). Só no caminho antigo (SCALER_CROP_REGION)
     * as regiões ficam dentro do recorte.
     */
    fun meteringArray(characteristics: CameraCharacteristics, requested: Float): Rect? {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
        ) {
            return Rect(active)
        }
        return sensorRegion(characteristics, requested)
    }

    fun apply(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        requested: Float
    ): Float {
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


}
