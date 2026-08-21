package com.steadyvault.camera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Resolve OIS usando metadados da câmera lógica e das lentes físicas, mas aplica
 * somente chaves do request lógico. Em aparelhos multi-câmera, a característica
 * lógica pode ser incompleta mesmo quando uma lente física anuncia OIS; esse dado
 * físico permanece diagnóstico e nunca é forçado em um request lógico.
 */
object OpticalStabilizationCapability {
    data class Capability(
        val decision: OisSupportPolicy.Decision,
        val logicalRequestAvailable: Boolean,
        val resultMetadataAvailable: Boolean,
        val physicalOisCameraIds: Set<String>
    ) {
        val supported: Boolean
            get() = decision.supported && logicalRequestAvailable
    }

    fun inspect(
        manager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics
    ): Capability {
        val logicalModes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )
        val logicalRequestAvailable = runCatching {
            characteristics.availableCaptureRequestKeys.contains(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
            )
        }.getOrDefault(false)
        val resultMetadataAvailable = runCatching {
            characteristics.availableCaptureResultKeys.contains(
                CaptureResult.LENS_OPTICAL_STABILIZATION_MODE
            )
        }.getOrDefault(false)
        val physicalOverrideAvailable = runCatching {
            characteristics.availablePhysicalCameraRequestKeys.contains(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
            )
        }.getOrDefault(false)

        val physicalModes = mutableListOf<IntArray?>()
        val physicalOisCameraIds = linkedSetOf<String>()
        val physicalIds = runCatching { characteristics.physicalCameraIds.toSet() }
            .getOrDefault(emptySet())
        for (physicalId in physicalIds) {
            if (physicalId == cameraId) continue
            val modes = runCatching {
                manager.getCameraCharacteristics(physicalId).get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                )
            }.getOrNull()
            physicalModes += modes
            if (modes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                physicalOisCameraIds += physicalId
            }
        }

        val decision = OisSupportPolicy.resolve(
            logicalModes = logicalModes,
            physicalModes = physicalModes,
            logicalRequestAvailable = logicalRequestAvailable,
            physicalOverrideAvailable = physicalOverrideAvailable,
            onMode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        )
        return Capability(
            decision = decision,
            logicalRequestAvailable = logicalRequestAvailable,
            resultMetadataAvailable = resultMetadataAvailable,
            physicalOisCameraIds = physicalOisCameraIds
        )
    }

    fun apply(
        builder: CaptureRequest.Builder,
        capability: Capability,
        enabled: Boolean
    ): Boolean {
        if (enabled && !capability.supported) return false
        if (!capability.logicalRequestAvailable) return !enabled

        val mode = if (enabled) {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        return runCatching {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == mode
        }.getOrDefault(false)
    }

}
