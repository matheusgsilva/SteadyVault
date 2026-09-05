from pathlib import Path
import re

capture_path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
build_path = Path("app/build.gradle.kts")

capture = capture_path.read_text(encoding="utf-8")
pattern = re.compile(
    r"    private fun startStabilizedRecording\(.*?\n    }\n\n    private fun supportsManualSensor",
    re.S,
)
replacement = '''    private fun startStabilizedRecording(
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
            session.setRepeatingRequest(request, null, mainHandler)
            commitRecorderStart(profile, token, highSpeed = false)
        }.onFailure {
            failSelectedConfigurationFromWorker(token, "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}")
        }
    }

    private fun supportsManualSensor'''

capture, count = pattern.subn(replacement, capture, count=1)
if count != 1:
    raise SystemExit(f"startStabilizedRecording: esperado 1 trecho, encontrado {count}")
capture_path.write_text(capture, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if "versionCode = 1000133" not in build:
    raise SystemExit("versionCode 1000133 não encontrado")
build = build.replace("versionCode = 1000133", "versionCode = 1000134", 1)
build_path.write_text(build, encoding="utf-8")

capture = capture_path.read_text(encoding="utf-8")
section = capture.split("private fun startStabilizedRecording", 1)[1].split("private fun supportsManualSensor", 1)[0]
if "session.setRepeatingRequest(request, null, mainHandler)" not in section:
    raise SystemExit("request automático não foi instalado diretamente")
if "fixedCadencePlan" in section or "startWithFixedSensorCadence" in section or "CaptureExposureFpsTrace" in section:
    raise SystemExit("caminho manual/diagnóstico ainda está ativo no início regular")
if "setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)" not in capture:
    raise SystemExit("AE automático ausente")
if "setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)" not in capture:
    raise SystemExit("FPS range automático ausente")

print("AE60_CLEAN_PATCH_OK")
