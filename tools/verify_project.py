#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
errors = []

def require(condition, message):
    if not condition:
        errors.append(message)

build = (ROOT / "app/build.gradle.kts").read_text(errors="ignore")
capture_path = JAVA / "com/steadyvault/camera/capture/service/CaptureService.kt"
direct_path = JAVA / "com/steadyvault/camera/capture/recorder/DirectMediaRecorderBackend.kt"
backend_path = JAVA / "com/steadyvault/camera/capture/recorder/RecordingBackend.kt"

require('versionName = "1.8.266"' in build, "versionName não é 1.8.266")
require('versionCode = 466' in build, "versionCode não é 466")
require(capture_path.is_file(), "CaptureService.kt ausente")
require(direct_path.is_file(), "DirectMediaRecorderBackend.kt ausente")
require(backend_path.is_file(), "RecordingBackend.kt ausente")

for removed in (
    JAVA / "com/steadyvault/camera/capture/recorder/HardwareRecorder.kt",
    JAVA / "com/steadyvault/camera/capture/recorder/OemMediaRecorderBackend.kt",
    JAVA / "com/steadyvault/camera/capture/recorder/OemRecorderPolicy.kt",
    JAVA / "com/steadyvault/camera/processing/analysis/AutomaticCfrRepairPolicy.kt",
):
    require(not removed.exists(), f"arquivo antigo ainda existe: {removed.name}")

capture = capture_path.read_text(errors="ignore")
direct = direct_path.read_text(errors="ignore")

for forbidden in (
    "HardwareRecorder",
    "OemMediaRecorderBackend",
    "OemRecorderPolicy",
    "repairExactFpsIfNeeded",
    "AutomaticCfrRepairPolicy",
    "VideoOptimizer().optimize",
    "RecordingFilePublisher.publish",
    "FrameRepairMode.ADAPTIVE_BLEND",
):
    require(forbidden not in capture, f"pipeline antigo ainda referenciado no CaptureService: {forbidden}")

for required in (
    "DirectMediaRecorderBackend(",
    "outputFile = finalFile",
    "rawOutputFile = finalFile",
    "session.stopRepeating()",
    "stopRecorderAndFinalize()",
    'CAPTURE_PIPELINE_REVISION = "strict-config-direct-mediarecorder-1.8.255"',
    'backend=MediaRecorder direto',
):
    require(required in capture, f"pipeline direto incompleto: {required}")

for required in (
    "class DirectMediaRecorderBackend",
    "MediaRecorder.VideoSource.SURFACE",
    "setOutputFile(outputFile.absolutePath)",
    "setVideoSize(width, height)",
    "setVideoFrameRate(targetFps)",
    "setVideoEncodingBitRate(videoBitrate)",
    "override val videoBitrateBps: Long get() = videoBitrate.toLong()",
    "requestedBitrate: Int",
    "recorder?.start()",
    "recorder?.stop()",
    "findExactSelection(",
):
    require(required in direct, f"MediaRecorder direto/configuração estrita incompleto: {required}")

for forbidden in (
    "selectedProfile?.videoProfile?.bitrate",
    ".maxByOrNull { it.bitrate }",
):
    require(forbidden not in direct, f"perfil OEM ainda sobrescreve preferência do usuário: {forbidden}")

require("bitrate = configuredVideoBitrate()" in capture, "CaptureService não preserva bitrate configurado")
require("videoBitrateBps = encoderProfile.bitrate.toLong()" in capture, "storage guard não usa bitrate configurado")
require("CameraProfileStore.activate(" not in capture[:capture.find("private fun prepareInitialConfigurationBeforeCameraHandoff")],
        "início da gravação ainda reativa perfil histórico sobre a configuração atual")


router_path = JAVA / "com/steadyvault/camera/capture/service/RecordingServiceRouter.kt"
widget_start_path = JAVA / "com/steadyvault/camera/widgets/WidgetStartReceiver.kt"
router = router_path.read_text(errors="ignore")
widget_start = widget_start_path.read_text(errors="ignore")
require("CameraProfileStore.activate(" not in router, "router ainda troca configuração antes de iniciar")
require("CameraProfileStore.activate(" not in widget_start, "widget ainda troca configuração antes de iniciar")


# Runtime puro: nada de polling/watchdog/monitor de câmera durante a gravação.
for forbidden in (
    "RecordingHealthMonitor",
    "RecordingStorageMonitor",
    "registerAvailabilityCallback",
    "scheduleCameraRecovery",
    "scheduleInitialCameraAvailabilityRetry",
    "checkRecordingPipelineHealth",
    "refreshRecordingServiceHeartbeat",
    "ACTION_SCREEN_OFF",
    "ACTION_SCREEN_ON",
    "postDelayed(",
    "SystemClock.sleep(",
    "registerReceiver(",
):
    require(forbidden not in capture, f"runtime de gravação ainda contém monitoramento: {forbidden}")
require("val outputConfigurations = listOf(outputConfiguration)" in capture, "sessão não está limitada a uma única saída")
require("addTarget(surface)" in capture, "request não envia exclusivamente para a Surface do MediaRecorder")
require("REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR" in capture, "cadência fixa não valida MANUAL_SENSOR")
require("CaptureRequest.SENSOR_FRAME_DURATION" in capture, "cadência fixa não controla SENSOR_FRAME_DURATION")
require("CameraLensCatalog.resolveRecordingCameraId" in capture, "gravação não normaliza para câmera lógica")
require("CaptureCapabilityMatrix.scan(this" not in (JAVA / "com/steadyvault/camera/ui/capture/CaptureActivity.kt").read_text(errors="ignore"), "CaptureActivity ainda varre capabilities em background")
require("CaptureCapabilityMatrix" not in capture, "CaptureService ainda depende da matriz de capabilities")
settings_text = (JAVA / "com/steadyvault/camera/ui/settings/SettingsActivity.kt").read_text(errors="ignore")
require("if (capabilityMatrix == null) scanCapabilities(force = true)" not in settings_text, "SettingsActivity ainda inicia scan de câmera automaticamente")
require(not (JAVA / "com/steadyvault/camera/capture/health/RecordingHealthMonitor.kt").exists(), "watchdog antigo ainda existe")
require(not (JAVA / "com/steadyvault/camera/core/storage/RecordingStorageMonitor.kt").exists(), "monitor de armazenamento antigo ainda existe")

# Camera2: a thread cameraExecutor não possui Looper. Toda API Handler-based deve receber Handler explícito.
require("setRepeatingRequest(request, null, null)" not in capture, "repeating request ainda depende de Looper implícito")
require("setRepeatingRequest(autoRequest, callback, null)" not in capture, "callback de cadência ainda depende de Looper implícito")
require("setRepeatingBurst(requests, null, null)" not in capture, "high-speed ainda depende de Looper implícito")
require("setRepeatingRequest(request, null, mainHandler)" in capture, "request regular não usa Handler explícito")
require("setRepeatingRequest(autoRequest, callback, mainHandler)" in capture, "warm-up de 3 frames não usa Handler explícito")

# O código de captura não pode criar MediaMuxer nem instanciar MediaCodec para gravar.
require("MediaMuxer(" not in capture, "CaptureService instancia MediaMuxer")
require("MediaCodec.create" not in capture, "CaptureService instancia MediaCodec")

# Auditoria básica de chaves para detectar edições truncadas.
for path in (capture_path, direct_path, backend_path):
    text = path.read_text(errors="ignore")
    stripped = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    require(stripped.count('{') == stripped.count('}'), f"chaves desbalanceadas em {path.name}")
    require(stripped.count('(') == stripped.count(')'), f"parênteses desbalanceados em {path.name}")

if errors:
    print("STATIC_AUDIT_FAILED")
    for error in errors:
        print("-", error)
    sys.exit(1)

print("STATIC_AUDIT_OK")
