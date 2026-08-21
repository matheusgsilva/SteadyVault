package com.steadyvault.camera.core.capability

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import com.steadyvault.camera.core.camera.OisSupportPolicy
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.settings.CaptureSettings
import java.util.Locale

object CapabilityReport {

    fun build(context: Context): String {
        val lines = mutableListOf<String>()
        val manager = context.getSystemService(CameraManager::class.java)

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)

            if (
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }

            val streamMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: continue

            val outputSizes = linkedSetOf<Size>().apply {
                runCatching { streamMap.getOutputSizes(ImageFormat.PRIVATE) }
                    .getOrNull()?.let(::addAll)
                runCatching { streamMap.getOutputSizes(MediaCodec::class.java) }
                    .getOrNull()?.let(::addAll)
                runCatching { streamMap.getOutputSizes(MediaRecorder::class.java) }
                    .getOrNull()?.let(::addAll)
            }

            val sizes = outputSizes
                .filter { it.width * 9 == it.height * 16 }
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .take(8)
                .joinToString { "${it.width}×${it.height}" }

            val fpsRanges: Array<Range<Int>> =
                characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                ) ?: emptyArray()

            val fps = fpsRanges
                .sortedByDescending { it.upper }
                .joinToString { "${it.lower}-${it.upper}" }

            val highSpeedSizes: Array<Size> = runCatching {
                streamMap.highSpeedVideoSizes
            }.getOrNull() ?: emptyArray()

            val highSpeed = highSpeedSizes.joinToString { size ->
                val ranges: Array<Range<Int>> = runCatching {
                    streamMap.getHighSpeedVideoFpsRangesFor(size)
                }.getOrNull() ?: emptyArray()

                val rangeText = ranges.joinToString { "${it.lower}-${it.upper}" }
                "${size.width}×${size.height} (${rangeText.ifBlank { "sem faixa anunciada" }})"
            }

            val stabilizationModes: IntArray =
                characteristics.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                ) ?: intArrayOf()

            val stabilization = stabilizationModes
                .map { mode ->
                    when (mode) {
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION ->
                            "Preview"

                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON ->
                            "EIS"

                        else ->
                            "Off"
                    }
                }
                .distinct()
                .joinToString()

            val oisCapability = OpticalStabilizationCapability.inspect(manager, cameraId, characteristics)
            val ois = when (oisCapability.decision.source) {
                OisSupportPolicy.Source.NONE -> "não anunciado"
                OisSupportPolicy.Source.REQUEST_KEY_FALLBACK ->
                    "testável pela sessão (HAL incompleta)"
                OisSupportPolicy.Source.PHYSICAL_METADATA ->
                    "detectado na lente física ${oisCapability.physicalOisCameraIds.joinToString()} • " +
                        if (oisCapability.logicalRequestAvailable) "controle lógico testável" else "controle lógico indisponível"
                OisSupportPolicy.Source.LOGICAL_METADATA -> "sim • câmera lógica"
            }

            val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
                )?.supportedProfiles?.let { profiles ->
                    buildList {
                        if (profiles.contains(DynamicRangeProfiles.HLG10)) add("HLG10")
                        if (profiles.contains(DynamicRangeProfiles.HDR10)) add("HDR10")
                        if (profiles.contains(DynamicRangeProfiles.HDR10_PLUS)) add("HDR10+")
                    }.joinToString().ifBlank { "não anunciado" }
                } ?: "não anunciado"
            } else {
                "Android anterior ao suporte"
            }

            lines += "Câmera $cameraId"
            lines += "Resoluções 16:9: ${sizes.ifBlank { "não anunciadas" }}"
            lines += "FPS normais: ${fps.ifBlank { "não anunciados" }}"
            lines += "High-speed: ${highSpeed.ifBlank { "não anunciado" }}"
            lines += "Estabilização: ${stabilization.ifBlank { "Off" }} • OIS: $ois"
            lines += "HDR 10-bit: $hdr"
            lines += ""
        }

        val matrix = runCatching { CaptureCapabilityMatrix.cached(context) ?: CaptureCapabilityMatrix.scan(context, force = true) }.getOrNull()
        lines += "Modos detectados (Camera2 + encoder)"
        if (matrix == null || matrix.modes.isEmpty()) {
            lines += "Nenhuma combinação foi confirmada pela análise"
        } else {
            matrix.modes.map { it.cameraId }.distinct().forEach { cameraId ->
                val cameraMatrix = matrix.forCamera(cameraId)
                val summaries = CaptureSettings.supportedFpsValues.mapNotNull { fps ->
                    cameraMatrix.maximumMode(fps)?.let { mode ->
                        val session = if (mode.highSpeed) "high-speed" else "regular"
                        "${CaptureSettings.resolutionLabel(mode.resolution)} ${mode.fps} FPS ${mode.encoderMime.substringAfterLast('/').uppercase(Locale.US)} ($session)"
                    }
                }
                if (summaries.isNotEmpty()) lines += "Câmera $cameraId: ${summaries.joinToString(" • ")}"
            }
        }
        matrix?.diagnostics?.takeLast(12)?.takeIf { it.isNotEmpty() }?.let { diagnostics ->
            lines += "Diagnóstico da análise"
            lines += diagnostics
        }
        lines += ""

        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { codecInfo ->
                codecInfo.isEncoder && !codecInfo.isSoftwareOnly
            }
            .flatMap { codecInfo ->
                codecInfo.supportedTypes.map { mime ->
                    "${codecInfo.name}: ${mime.lowercase(Locale.US)}"
                }
            }
            .distinct()
            .sorted()

        lines += "Encoders de hardware"
        lines += encoders.joinToString("\n").ifBlank { "Nenhum encoder de hardware encontrado" }

        return lines.joinToString("\n")
    }
}
