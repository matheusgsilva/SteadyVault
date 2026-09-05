from pathlib import Path

root = Path('.')
settings_path = root / 'app/src/main/java/com/steadyvault/camera/core/settings/CaptureSettings.kt'
service_path = root / 'app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt'
ui_path = root / 'app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt'
sensor_path = root / 'app/src/main/java/com/steadyvault/camera/capture/timing/SensorCadencePolicy.kt'
build_path = root / 'app/build.gradle.kts'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'âncora não encontrada: {label}')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# CaptureSettings: Auto FPS de baixa luz + estabilização automática.
# ---------------------------------------------------------------------------
settings = settings_path.read_text(encoding='utf-8')
settings = replace_once(
    settings,
    '    const val STABILIZATION_PREVIEW = "PREVIEW"\n',
    '    const val STABILIZATION_AUTO = "AUTO"\n    const val STABILIZATION_PREVIEW = "PREVIEW"\n',
    'constante STABILIZATION_AUTO'
)
settings = replace_once(
    settings,
    '        val fps: Int,\n        val codec: String,\n',
    '        val fps: Int,\n        val autoFpsLowLight: Boolean,\n        val codec: String,\n',
    'Snapshot.autoFpsLowLight'
)
settings = replace_once(
    settings,
    '            fps = fps,\n            codec = codec,\n',
    '            fps = fps,\n            autoFpsLowLight = prefs.getBoolean("auto_fps_low_light", false),\n            codec = codec,\n',
    'snapshot auto fps'
)
settings = replace_once(
    settings,
    '            .putInt("fps", normalized.fps)\n            .putString("codec", normalized.codec)\n',
    '            .putInt("fps", normalized.fps)\n            .putBoolean("auto_fps_low_light", normalized.autoFpsLowLight)\n            .putString("codec", normalized.codec)\n',
    'persist auto fps'
)
settings = replace_once(
    settings,
    '    private val SUPPORTED_STABILIZATIONS = linkedSetOf(STABILIZATION_PREVIEW, STABILIZATION_EIS, STABILIZATION_OIS, STABILIZATION_OFF)\n',
    '    private val SUPPORTED_STABILIZATIONS = linkedSetOf(STABILIZATION_AUTO, STABILIZATION_PREVIEW, STABILIZATION_EIS, STABILIZATION_OIS, STABILIZATION_OFF)\n',
    'supported stabilizations'
)
settings_path.write_text(settings, encoding='utf-8')

# ---------------------------------------------------------------------------
# SensorCadencePolicy: restaura exatamente a política 60-only da AE CLEAN.
# 30 FPS volta a usar AE automático; 120/240 nunca entram nesse lock manual.
# ---------------------------------------------------------------------------
sensor = sensor_path.read_text(encoding='utf-8')
sensor = sensor.replace('    private val SUPPORTED_FPS = setOf(30, 60, 120, 240)\n\n', '', 1)
sensor = replace_once(
    sensor,
    '        if (!manualSensorSupported || fps !in SUPPORTED_FPS || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null\n',
    '        if (!manualSensorSupported || fps != 60 || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null\n',
    'sensor cadence 60 only'
)
sensor_path.write_text(sensor, encoding='utf-8')

# ---------------------------------------------------------------------------
# CaptureService: restaura 30/60 AE CLEAN, Auto FPS opcional, estabilização AUTO,
# cor customizada a 60 e mantém 120/240 isolados.
# ---------------------------------------------------------------------------
service = service_path.read_text(encoding='utf-8')

# A configuração em cache deve distinguir Auto FPS ligado/desligado.
service = replace_once(
    service,
    '        recordingSettings.bitrateMbps,\n        allowHdr,\n',
    '        recordingSettings.bitrateMbps,\n        recordingSettings.autoFpsLowLight,\n        allowHdr,\n',
    'signature auto fps'
)

# Cache: faixa variável só é válida quando Auto FPS está explicitamente ligado.
service = replace_once(
    service,
    '            ?.takeIf { it.camera.hasExactFpsRange() }\n',
    '            ?.takeIf { it.camera.matchesRequestedFpsContract() }\n',
    'cache fps contract'
)

# 30/60: ao montar profile, usar a faixa exata por padrão ou uma faixa variável
# publicada pela HAL apenas quando Auto FPS estiver habilitado.
service = replace_once(
    service,
    '                fpsRange = Range(targetFps, targetFps),\n                highSpeed = false,\n',
    '                fpsRange = resolveStandardFpsRange(characteristics, targetFps),\n                highSpeed = false,\n',
    'preview standard fps range'
)

old_resolve = '''        if (!highSpeed) {
            if (targetFps >= CaptureModeStore.FPS_120) {
                return resolveRegularHighFpsRange(characteristics, size, targetFps)
            }
            // 30/60 mantêm exatamente o comportamento CLEAN já validado.
            return Range(targetFps, targetFps)
        }
'''
new_resolve = '''        if (!highSpeed) {
            if (targetFps >= CaptureModeStore.FPS_120) {
                return resolveRegularHighFpsRange(characteristics, size, targetFps)
            }
            return resolveStandardFpsRange(characteristics, targetFps)
        }
'''
service = replace_once(service, old_resolve, new_resolve, 'resolveFpsRange 30/60')

helper_anchor = '''    private fun resolveRegularHighFpsRange(
'''
helper = '''    /**
     * Equivalente Android do Auto FPS do iPhone: permanece desligado por padrão.
     * Quando habilitado, 30/60 usam apenas uma faixa variável que a própria HAL
     * publica e cujo teto é exatamente o FPS escolhido. Nunca se aplica a 120/240.
     */
    private fun resolveStandardFpsRange(
        characteristics: CameraCharacteristics,
        targetFps: Int
    ): Range<Int> {
        val exact = Range(targetFps, targetFps)
        if (!recordingSettings.autoFpsLowLight || targetFps !in setOf(CaptureModeStore.FPS_30, CaptureModeStore.FPS_60)) {
            return exact
        }
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == targetFps && it.lower < targetFps }
            .orEmpty()
        // Prefere a menor variação possível (ex.: 30-60 em vez de 15-60).
        return ranges.maxByOrNull { it.lower } ?: exact
    }

'''
service = replace_once(service, helper_anchor, helper + helper_anchor, 'helper standard fps range')

# Contrato de FPS em cache/seleção.
contract_anchor = '''    private fun CameraProfile.hasExactFpsRange(): Boolean =
        fpsRange.lower == targetFps && fpsRange.upper == targetFps
'''
contract_new = '''    private fun CameraProfile.hasExactFpsRange(): Boolean =
        fpsRange.lower == targetFps && fpsRange.upper == targetFps

    private fun CameraProfile.matchesRequestedFpsContract(): Boolean = when {
        highSpeed || targetFps >= CaptureModeStore.FPS_120 -> hasExactFpsRange()
        !recordingSettings.autoFpsLowLight -> hasExactFpsRange()
        else -> fpsRange.upper == targetFps && fpsRange.lower <= targetFps
    }
'''
service = replace_once(service, contract_anchor, contract_new, 'fps contract helper')

# Restaura o start regular da AE CLEAN. Auto FPS precisa manter AE contínuo e não
# pode coexistir com SENSOR_FRAME_DURATION fixo.
start_begin = service.index('    private fun startStabilizedRecording(')
start_end = service.index('    private fun supportsManualSensor(', start_begin)
old_start_block = service[start_begin:start_end]
new_start_block = '''    private fun startStabilizedRecording(
        session: CameraCaptureSession,
        request: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        sendStateOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps…")
        updateNotificationOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps…")

        runCatching {
            armRecorderForFirstFrame(token)
            val manualSensor = supportsManualSensor(profile)
            if (
                !recordingSettings.autoFpsLowLight &&
                profile.targetFps == CaptureModeStore.FPS_60 &&
                !profile.hdrHlg10 &&
                manualSensor
            ) {
                // Caminho AE60 CLEAN medido como o mais estável no S25 Ultra.
                // 30 FPS nunca entra aqui. Não existe warm-up especial do widget.
                val recent = Camera3AStateStore.recentExposure(profile.cameraId)
                val immediatePlan = recent?.let {
                    fixedCadencePlan(profile, it.exposureTimeNs, it.sensitivityIso)
                }
                if (immediatePlan != null) {
                    val fixedRequest = buildFixedCadenceRequest(profile, immediatePlan)
                        ?: throw IllegalStateException("câmera não disponível para request de cadência fixa")
                    session.setRepeatingRequest(fixedRequest, null, mainHandler)
                    commitRecorderStart(profile, token, highSpeed = false)
                } else {
                    startWithFixedSensorCadence(session, request, profile, token)
                }
            } else {
                // 30 FPS e Auto FPS seguem AE contínuo, como o comportamento do AVFoundation.
                session.setRepeatingRequest(request, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }
        }.onFailure {
            failSelectedConfigurationFromWorker(token, "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}")
        }
    }

'''
service = service[:start_begin] + new_start_block + service[start_end:]

# Remove warm-up headless que causou divergência da AE CLEAN.
warmup_marker = '    private fun startHeadlessFastExposureWarmup('
if warmup_marker in service:
    warmup_begin = service.index(warmup_marker)
    warmup_end = service.index('    private fun startWithFixedSensorCadence(', warmup_begin)
    service = service[:warmup_begin] + service[warmup_end:]

# Cor: Soft/Flat pode ser aplicada também a 60 se TONEMAP_CONTRAST_CURVE existir.
old_color_gate = '''        if (profile.targetFps >= CaptureModeStore.FPS_60) {
            if (modes.contains(CameraMetadata.TONEMAP_MODE_FAST)) {
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
            return
        }

        val wantsCurve = recordingSettings.colorProfile == CaptureSettings.COLOR_SOFT ||
            recordingSettings.colorProfile == CaptureSettings.COLOR_FLAT
'''
new_color_gate = '''        val wantsCurve = recordingSettings.colorProfile == CaptureSettings.COLOR_SOFT ||
            recordingSettings.colorProfile == CaptureSettings.COLOR_FLAT
'''
service = replace_once(service, old_color_gate, new_color_gate, 'color gate 60')
color_curve_end = '''            setSafely(builder, CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
            return
        }

        when {
'''
color_curve_new = '''            setSafely(builder, CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
            return
        }

        if (profile.targetFps >= CaptureModeStore.FPS_60) {
            if (modes.contains(CameraMetadata.TONEMAP_MODE_FAST)) {
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
            return
        }

        when {
'''
service = replace_once(service, color_curve_end, color_curve_new, 'color natural 60 fast')

# Estabilização AUTO inspirada no AVFoundation: decide por capacidade do profile,
# sem alterar a escolha explícita do usuário. Acima de 60, AUTO prioriza cadência.
service = replace_once(
    service,
    '        val requested = requestedStabilizationMode()\n',
    '        val requested = requestedStabilizationMode(profile)\n',
    'apply stabilization auto'
)
old_req_stab = '''    private fun requestedStabilizationMode(): RecordingStabilizationPolicy.Mode = when (recordingSettings.stabilization) {
        CaptureSettings.STABILIZATION_PREVIEW -> RecordingStabilizationPolicy.Mode.PREVIEW
        CaptureSettings.STABILIZATION_EIS -> RecordingStabilizationPolicy.Mode.EIS
        CaptureSettings.STABILIZATION_OIS -> RecordingStabilizationPolicy.Mode.OIS
        else -> RecordingStabilizationPolicy.Mode.OFF
    }
'''
new_req_stab = '''    private fun requestedStabilizationMode(profile: CameraProfile): RecordingStabilizationPolicy.Mode {
        if (recordingSettings.stabilization == CaptureSettings.STABILIZATION_AUTO) {
            if (profile.highSpeed || profile.targetFps > CaptureModeStore.FPS_60) {
                return RecordingStabilizationPolicy.Mode.OFF
            }
            return when {
                profile.previewStabilizationSupported -> RecordingStabilizationPolicy.Mode.PREVIEW
                profile.eisSupported -> RecordingStabilizationPolicy.Mode.EIS
                profile.oisSupported -> RecordingStabilizationPolicy.Mode.OIS
                else -> RecordingStabilizationPolicy.Mode.OFF
            }
        }
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> RecordingStabilizationPolicy.Mode.PREVIEW
            CaptureSettings.STABILIZATION_EIS -> RecordingStabilizationPolicy.Mode.EIS
            CaptureSettings.STABILIZATION_OIS -> RecordingStabilizationPolicy.Mode.OIS
            else -> RecordingStabilizationPolicy.Mode.OFF
        }
    }
'''
service = replace_once(service, old_req_stab, new_req_stab, 'requested stabilization auto')

old_stab_name = '''    private fun stabilizationName(profile: CameraProfile): String {
        if (profile.highSpeed) return "sem estabilização (high-speed)"
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
            CaptureSettings.STABILIZATION_EIS -> "EIS"
            CaptureSettings.STABILIZATION_OIS -> "OIS"
            CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
            else -> "estabilização inválida"
        }
    }
'''
new_stab_name = '''    private fun stabilizationName(profile: CameraProfile): String {
        if (profile.highSpeed) return "sem estabilização (high-speed)"
        if (recordingSettings.stabilization == CaptureSettings.STABILIZATION_AUTO) {
            return "auto → ${stabilizationModeLabel(requestedStabilizationMode(profile))}"
        }
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
            CaptureSettings.STABILIZATION_EIS -> "EIS"
            CaptureSettings.STABILIZATION_OIS -> "OIS"
            CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
            else -> "estabilização inválida"
        }
    }
'''
service = replace_once(service, old_stab_name, new_stab_name, 'stabilization name auto')

# Remove constantes do warm-up headless, mantendo high-FPS tolerance.
service = service.replace('        private const val HEADLESS_AE_WARMUP_MAX_MS = 50L\n', '', 1)
service = service.replace('        private const val HEADLESS_AE_WARMUP_MAX_FRAMES = 3\n', '', 1)

# Revisão para invalidar cache de configurações antigas.
import re
revision_match = re.search(r'private const val CAPTURE_PIPELINE_REVISION = "[^"]+"', service)
if not revision_match:
    raise SystemExit('CAPTURE_PIPELINE_REVISION não encontrada')
service = service[:revision_match.start()] + 'private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-clean-1.8.257"' + service[revision_match.end():]

service_path.write_text(service, encoding='utf-8')

# ---------------------------------------------------------------------------
# Settings UI: expõe Auto FPS e Auto stabilization; corrige texto de bitrate.
# ---------------------------------------------------------------------------
ui = ui_path.read_text(encoding='utf-8')
ui = replace_once(
    ui,
    '    private lateinit var fps: Spinner\n    private lateinit var codec: Spinner\n',
    '    private lateinit var fps: Spinner\n    private lateinit var autoFpsLowLight: Switch\n    private lateinit var codec: Spinner\n',
    'ui auto fps property'
)

fps_form_anchor = '''        fps = addSpinner(
            "Taxa de quadros da gravação (FPS)",
            fpsOptions(),
            snapshot.fps.toString()
        )
        modeCapabilitiesText = addCapabilitiesCard()
'''
fps_form_new = '''        fps = addSpinner(
            "Taxa de quadros da gravação (FPS)",
            fpsOptions(),
            snapshot.fps.toString()
        )
        autoFpsLowLight = addSwitch(
            "FPS automático em pouca luz",
            "Equivalente ao Auto FPS do iPhone. Fica desligado por padrão. Em 30/60 FPS, se a câmera publicar uma faixa variável compatível, permite reduzir temporariamente o FPS para ganhar exposição em pouca luz. 120/240 permanecem fixos.",
            snapshot.autoFpsLowLight
        )
        modeCapabilitiesText = addCapabilitiesCard()
'''
ui = replace_once(ui, fps_form_anchor, fps_form_new, 'ui auto fps switch')

ui = replace_once(
    ui,
    '        addInfo("O bitrate escolhido fica salvo mesmo ao mudar FPS, resolução, codec ou estabilização. Ele é enviado diretamente ao encoder e só é limitado se ultrapassar o intervalo que o próprio codec de hardware publica como suportado; o app não reduz o valor por perfil automático.")\n',
    '        addInfo("O bitrate funciona como a taxa média-alvo do AVFoundation: o valor escolhido fica salvo e é enviado diretamente ao encoder. No MediaRecorder o fabricante controla internamente CBR/VBR; o SteadyVault não troca o bitrate escolhido silenciosamente.")\n',
    'bitrate iOS text'
)
ui = replace_once(
    ui,
    '        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder. O bitrate escolhido é enviado como taxa-alvo; CBR/VBR e demais decisões de rate control ficam a cargo do encoder de hardware/OEM, sem MediaCodec manual.")\n',
    '        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder, equivalente ao caminho de captura simples do AVFoundation: uma única saída de vídeo, sem interpolação ou callbacks por quadro. HEVC/H.264 e bitrate-alvo são configurados quando suportados pelo hardware.")\n',
    'pipeline iOS text'
)

# Estabilização automática.
ui = replace_once(
    ui,
    '        val specs = listOf(\n            FeatureOptionSpec(CaptureSettings.STABILIZATION_PREVIEW, "Preview stabilization", "Estabilização avançada da câmera."),\n',
    '        val specs = listOf(\n            FeatureOptionSpec(CaptureSettings.STABILIZATION_AUTO, "Automática (estilo iPhone)", "Escolhe Preview stabilization, EIS, OIS ou Off conforme o formato e as capacidades. Acima de 60 FPS prioriza cadência."),\n            FeatureOptionSpec(CaptureSettings.STABILIZATION_PREVIEW, "Preview stabilization", "Estabilização avançada da câmera."),\n',
    'stabilization auto option'
)
ui = replace_once(
    ui,
    '        return featureOptions(specs, currentValue) { value -> if (value == CaptureSettings.STABILIZATION_OFF) Support.SUPPORTED else directSupport(value) }\n',
    '        return featureOptions(specs, currentValue) { value ->\n            if (value == CaptureSettings.STABILIZATION_OFF || value == CaptureSettings.STABILIZATION_AUTO) Support.SUPPORTED else directSupport(value)\n        }\n',
    'stabilization auto support'
)

# Salva Auto FPS no snapshot da UI.
ui = replace_once(
    ui,
    '        fps = fpsValue,\n        codec = selected(codec),\n',
    '        fps = fpsValue,\n        autoFpsLowLight = autoFpsLowLight.isChecked,\n        codec = selected(codec),\n',
    'ui snapshot auto fps'
)

# Texto da faixa de FPS deixa claro fixed vs Auto.
ui = replace_once(
    ui,
    '        addInfo("A faixa fixa é sempre priorizada e o perfil VIDEO_RECORD é aplicado automaticamente quando a câmera o publica.")\n',
    '        addInfo("Com FPS automático desligado, 30/60 usam faixa fixa e 120/240 exigem suporte real do formato. Com FPS automático ligado, somente 30/60 podem usar uma faixa variável publicada pela câmera; o app nunca inventa quadros.")\n',
    'fps info text'
)
ui_path.write_text(ui, encoding='utf-8')

# ---------------------------------------------------------------------------
# Versão.
# ---------------------------------------------------------------------------
build = build_path.read_text(encoding='utf-8')
build = replace_once(build, '        versionCode = 1000142\n', '        versionCode = 1000143\n', 'versionCode')
build_path.write_text(build, encoding='utf-8')

# Sanidade estática.
checks = {
    settings_path: [
        'STABILIZATION_AUTO = "AUTO"',
        'val autoFpsLowLight: Boolean',
        'auto_fps_low_light'
    ],
    sensor_path: ['fps != 60'],
    service_path: [
        'profile.targetFps == CaptureModeStore.FPS_60',
        'resolveStandardFpsRange',
        'matchesRequestedFpsContract',
        'CaptureSettings.STABILIZATION_AUTO',
        'ios-like-ae-clean-1.8.257'
    ],
    ui_path: [
        'FPS automático em pouca luz',
        'Automática (estilo iPhone)',
        'autoFpsLowLight = autoFpsLowLight.isChecked'
    ],
    build_path: ['versionCode = 1000143'],
}
for path, markers in checks.items():
    text = path.read_text(encoding='utf-8')
    for marker in markers:
        if marker not in text:
            raise SystemExit(f'marcador ausente em {path}: {marker}')

if 'startHeadlessFastExposureWarmup' in service_path.read_text(encoding='utf-8'):
    raise SystemExit('warm-up headless antigo ainda presente')
if 'SUPPORTED_FPS = setOf(30, 60, 120, 240)' in sensor_path.read_text(encoding='utf-8'):
    raise SystemExit('SensorCadencePolicy ainda aplica lock a todos FPS')

print('IOS_LIKE_CAPTURE_PATCH_OK')
