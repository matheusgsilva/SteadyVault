from pathlib import Path

capture_path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
matrix_path = Path("app/src/main/java/com/steadyvault/camera/core/capability/CaptureCapabilityMatrix.kt")
catalog_path = Path("app/src/main/java/com/steadyvault/camera/core/capability/CaptureModeCatalog.kt")
settings_path = Path("app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt")
build_path = Path("app/build.gradle.kts")

# CaptureService: widget resolve a resolução própria do FPS e 120/240 nunca usam sessão regular.
capture = capture_path.read_text(encoding="utf-8")
import_anchor = "import com.steadyvault.camera.core.camera.CctWhiteBalanceController\nimport com.steadyvault.camera.core.settings.CaptureModeStore\n"
import_replacement = "import com.steadyvault.camera.core.camera.CctWhiteBalanceController\nimport com.steadyvault.camera.core.capability.CaptureCapabilityMatrix\nimport com.steadyvault.camera.core.capability.CaptureModeCatalog\nimport com.steadyvault.camera.core.settings.CaptureModeStore\n"
if capture.count(import_anchor) != 1:
    raise SystemExit("âncora de imports do CaptureService não encontrada")
capture = capture.replace(import_anchor, import_replacement, 1)

old_mode_switch = "        if (requestedTargetFps != recordingSettings.fps) recordingSettings = recordingSettings.copy(fps = requestedTargetFps)\n"
new_mode_switch = '''        val storedTargetResolution = CaptureSettings.resolutionForFps(this, requestedTargetFps)
        val resolvedTargetResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = requestedTargetFps,
            requestedResolution = storedTargetResolution,
            matrix = CaptureCapabilityMatrix.cached(this)?.forCamera(profileCameraId)
        )
        if (resolvedTargetResolution != storedTargetResolution) {
            CaptureSettings.saveResolutionForFps(this, requestedTargetFps, resolvedTargetResolution)
        }
        if (
            requestedTargetFps != recordingSettings.fps ||
            resolvedTargetResolution != recordingSettings.resolution
        ) {
            recordingSettings = recordingSettings.copy(
                fps = requestedTargetFps,
                resolution = resolvedTargetResolution
            )
        }
'''
if capture.count(old_mode_switch) != 1:
    raise SystemExit("troca simples de FPS não encontrada")
capture = capture.replace(old_mode_switch, new_mode_switch, 1)

old_highspeed_loop = '''        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps) continue
            for (highSpeed in listOf(true, false)) {
                if (highSpeed && size !in highSpeedSizes) continue
                if (!highSpeed && size !in regularSizes) continue
                val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed) ?: continue
                val profile = createCameraProfile(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    videoSize = size,
                    targetFps = fps,
                    fpsRange = fpsRange,
                    highSpeed = highSpeed,
                    dynamicRangeProfile = dynamicRange
                )
                if (!matchesRequestedMode(profile, targetFps)) continue
                if (!profileSatisfiesExplicitStabilization(profile)) continue
                val encoder = selectDirectRecorderEncoder(profile) ?: continue
                if (profile.hasExactFpsRange()) return profile to encoder
            }
        }
'''
new_highspeed_loop = '''        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps || size !in highSpeedSizes) continue
            // 120/240 são contrato de constrained high-speed. Nunca caia para sessão
            // regular, pois ela pode aceitar a Surface e ainda entregar apenas ~60 FPS.
            val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed = true) ?: continue
            val profile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = size,
                targetFps = fps,
                fpsRange = fpsRange,
                highSpeed = true,
                dynamicRangeProfile = dynamicRange
            )
            if (!matchesRequestedMode(profile, targetFps)) continue
            if (!profileSatisfiesExplicitStabilization(profile)) continue
            val encoder = selectDirectRecorderEncoder(profile) ?: continue
            if (profile.hasExactFpsRange()) return profile to encoder
        }
'''
if capture.count(old_highspeed_loop) != 1:
    raise SystemExit("loop high-speed antigo não encontrado")
capture = capture.replace(old_highspeed_loop, new_highspeed_loop, 1)

# Como 120/240 não usam mais regularSizes neste bloco, mantenha a coleta apenas para documentação/30-60 fora daqui.
# Remove a variável local regularSizes específica deste caminho se ficar sem uso.
regular_sizes_block = '''        val regularSizes = linkedSetOf<Size>().apply {
            runCatching { map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
            runCatching { map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
            runCatching { map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
        }
'''
if capture.count(regular_sizes_block) != 1:
    raise SystemExit("regularSizes do caminho 120/240 não encontrado")
capture = capture.replace(regular_sizes_block, "", 1)

capture_path.write_text(capture, encoding="utf-8")

# Capability matrix: 120/240 só são detectados pela lista constrained high-speed.
matrix = matrix_path.read_text(encoding="utf-8")
matrix_import_anchor = "import com.steadyvault.camera.core.settings.CaptureSettings\n"
if matrix.count(matrix_import_anchor) != 1:
    raise SystemExit("import CaptureSettings da matrix não encontrado")
matrix = matrix.replace(
    matrix_import_anchor,
    "import com.steadyvault.camera.core.settings.CaptureModeStore\nimport com.steadyvault.camera.core.settings.CaptureSettings\n",
    1
)
regular_fps_loop = "                for (fps in CaptureSettings.supportedFpsValues) {\n"
if matrix.count(regular_fps_loop) != 1:
    raise SystemExit(f"loop regular da matrix esperado 1 vez, encontrado {matrix.count(regular_fps_loop)}")
matrix = matrix.replace(
    regular_fps_loop,
    "                for (fps in CaptureSettings.supportedFpsValues.filter { it < CaptureModeStore.FPS_120 }) {\n",
    1
)
if "private const val CACHE_SCHEMA = 7" not in matrix:
    raise SystemExit("CACHE_SCHEMA 7 não encontrado")
matrix = matrix.replace("private const val CACHE_SCHEMA = 7", "private const val CACHE_SCHEMA = 8", 1)
matrix_path.write_text(matrix, encoding="utf-8")

# Catalog: se 240 está salvo em 4K mas o hardware detecta apenas FHD, escolha o melhor high-speed detectado.
catalog = catalog_path.read_text(encoding="utf-8")
old_preferred = '''        if (matrix?.bestMode(safeRequested, fps) != null) return safeRequested
        return resolveSelection(context, fps, safeRequested, matrix, scanInProgress).resolutionValue ?: safeRequested
'''
new_preferred = '''        if (matrix?.bestMode(safeRequested, fps) != null) return safeRequested
        if (fps >= CaptureModeStore.FPS_120) {
            matrix?.maximumMode(fps)?.let { detected -> return detected.resolution }
        }
        return resolveSelection(context, fps, safeRequested, matrix, scanInProgress).resolutionValue ?: safeRequested
'''
if catalog.count(old_preferred) != 1:
    raise SystemExit("preferredResolution antigo não encontrado")
catalog = catalog.replace(old_preferred, new_preferred, 1)
catalog_path.write_text(catalog, encoding="utf-8")

# Settings: cor customizada em 30/60 depende da capacidade real de TONEMAP_CONTRAST_CURVE.
settings = settings_path.read_text(encoding="utf-8")
settings_import_anchor = "import android.graphics.Typeface\nimport android.media.MediaFormat\n"
settings_import_replacement = '''import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaFormat
'''
if settings.count(settings_import_anchor) != 1:
    raise SystemExit("âncora de imports Camera2 em SettingsActivity não encontrada")
settings = settings.replace(settings_import_anchor, settings_import_replacement, 1)

settings_mode_import_anchor = "import com.steadyvault.camera.core.settings.CaptureSettings\n"
if settings.count(settings_mode_import_anchor) != 1:
    raise SystemExit("import CaptureSettings em SettingsActivity não encontrado")
settings = settings.replace(
    settings_mode_import_anchor,
    "import com.steadyvault.camera.core.settings.CaptureModeStore\nimport com.steadyvault.camera.core.settings.CaptureSettings\n",
    1
)

old_encoder_info = '        addInfo("Pipeline único do encoder: prioridade em tempo real, VBR quando suportado, B-frames desativados, baixa latência, taxa operacional exata e descarte de frames bloqueado.")\n'
new_encoder_info = '        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder. O bitrate escolhido é enviado como taxa-alvo; CBR/VBR e demais decisões de rate control ficam a cargo do encoder de hardware/OEM, sem MediaCodec manual.")\n'
if settings.count(old_encoder_info) != 1:
    raise SystemExit("texto antigo de VBR não encontrado")
settings = settings.replace(old_encoder_info, new_encoder_info, 1)

old_color = '''    private fun colorProfileOptions(settings: CaptureSettings.Snapshot): List<ChoiceSpinnerAdapter.Option> {
        val customSupported = !settings.hdrHlg10 &&
            settings.fps < 60
        return featureOptions(
            specs = listOf(
                FeatureOptionSpec(CaptureSettings.COLOR_NATURAL, "Natural", "BT.709 limitado e tons equilibrados."),
                FeatureOptionSpec(CaptureSettings.COLOR_SOFT, "Natural suave", "Curva tonal com contraste mais suave."),
                FeatureOptionSpec(CaptureSettings.COLOR_FLAT, "Baixo contraste", "Curva mais plana para editar depois.")
            ),
            currentValue = settings.colorProfile
        ) { value ->
            if (value == CaptureSettings.COLOR_NATURAL || customSupported) Support.SUPPORTED else Support.UNSUPPORTED
        }
    }
'''
new_color = '''    private fun colorProfileOptions(settings: CaptureSettings.Snapshot): List<ChoiceSpinnerAdapter.Option> {
        val customSupport = customColorProfileSupport(settings)
        return featureOptions(
            specs = listOf(
                FeatureOptionSpec(CaptureSettings.COLOR_NATURAL, "Natural", "BT.709 limitado e tons equilibrados."),
                FeatureOptionSpec(CaptureSettings.COLOR_SOFT, "Natural suave", "Curva tonal com contraste mais suave."),
                FeatureOptionSpec(CaptureSettings.COLOR_FLAT, "Baixo contraste", "Curva mais plana para editar depois.")
            ),
            currentValue = settings.colorProfile
        ) { value ->
            if (value == CaptureSettings.COLOR_NATURAL) Support.SUPPORTED else customSupport
        }
    }

    private fun customColorProfileSupport(settings: CaptureSettings.Snapshot): Support {
        if (settings.hdrHlg10 || settings.fps >= CaptureModeStore.FPS_120) return Support.UNSUPPORTED
        val cameraId = settings.selectedCameraId
            ?: CameraLensCatalog.resolveCameraId(this, null, settings.resolution)
        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return Support.UNVERIFIED
        val modes = runCatching {
            characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        }.getOrNull()
        val requestKeys = runCatching { characteristics.availableCaptureRequestKeys.toSet() }
            .getOrDefault(emptySet())
        return when {
            modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true &&
                CaptureRequest.TONEMAP_CURVE in requestKeys -> Support.SUPPORTED
            modes == null && CaptureRequest.TONEMAP_CURVE in requestKeys -> Support.UNVERIFIED
            else -> Support.UNSUPPORTED
        }
    }
'''
if settings.count(old_color) != 1:
    raise SystemExit("colorProfileOptions antigo não encontrado")
settings = settings.replace(old_color, new_color, 1)
settings_path.write_text(settings, encoding="utf-8")

# Versiona para invalidar também o cache de capacidades persistido da versão anterior.
build = build_path.read_text(encoding="utf-8")
if "versionCode = 1000139" not in build:
    raise SystemExit("versionCode 1000139 não encontrado")
build = build.replace("versionCode = 1000139", "versionCode = 1000140", 1)
build_path.write_text(build, encoding="utf-8")

# Sanidade final.
checks = {
    capture_path: [
        "matrix = CaptureCapabilityMatrix.cached(this)?.forCamera(profileCameraId)",
        "highSpeed = true",
        "Nunca caia para sessão",
    ],
    matrix_path: [
        "filter { it < CaptureModeStore.FPS_120 }",
        "CACHE_SCHEMA = 8",
    ],
    catalog_path: [
        "matrix?.maximumMode(fps)?.let { detected -> return detected.resolution }",
    ],
    settings_path: [
        "customColorProfileSupport(settings)",
        "TONEMAP_MODE_CONTRAST_CURVE",
        "CBR/VBR e demais decisões de rate control",
    ],
}
for path, markers in checks.items():
    content = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in content:
            raise SystemExit(f"marcador ausente em {path}: {marker}")

print("HIGHSPEED_COLOR_FIX_OK")
