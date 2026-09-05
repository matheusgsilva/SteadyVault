package com.steadyvault.camera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Resolve OIS pelos metadados disponíveis, mas a aplicação final faz uma tentativa
 * real no CaptureRequest. Alguns firmwares Samsung omitem a chave OIS dos metadados
 * da câmera lógica mesmo quando o builder aceita o controle. A sessão real, e não
 * somente a tabela de capacidades, é a autoridade final.
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
        val mode = if (enabled) {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }

        // Não bloqueie pela tabela de características: em alguns Samsung a câmera
        // lógica não anuncia a chave embora o CaptureRequest real aceite ON/OFF.
        // Se o builder/serviço HAL recusar, a exceção é capturada e a configuração
        // escolhida pelo usuário é reportada como incompatível sem fechar o app.
        return runCatching {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == mode
        }.getOrDefault(false)
    }

}
