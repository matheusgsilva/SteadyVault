from pathlib import Path

capture_path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
policy_path = Path("app/src/main/java/com/steadyvault/camera/capture/timing/SensorCadencePolicy.kt")
build_path = Path("app/build.gradle.kts")

capture = capture_path.read_text(encoding="utf-8")
old_condition = "if (profile.targetFps == CaptureModeStore.FPS_60 && !profile.hdrHlg10 && manualSensor) {"
new_condition = "if (!profile.hdrHlg10 && manualSensor) {"
if capture.count(old_condition) != 1:
    raise SystemExit(f"condição especial de 60 FPS: esperado 1 trecho, encontrado {capture.count(old_condition)}")
capture = capture.replace(old_condition, new_condition, 1)

# Mantém os dois caminhos limpos já existentes:
# - sessão regular: mesma trava de cadência usada pelo melhor teste de 60 FPS;
# - sessão constrained high-speed: burst fixo, pois a HAL força AE e não permite sensor manual.
required_capture = [
    "startWithFixedSensorCadence(session, request, profile, token)",
    "session.setRepeatingRequest(fixedRequest, null, mainHandler)",
    "session.setRepeatingBurst(requests, null, mainHandler)",
    "CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF",
    "CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs",
]
for marker in required_capture:
    if marker not in capture:
        raise SystemExit(f"marcador obrigatório ausente em CaptureService: {marker}")

for forbidden in [
    "applyHybridAe60ExposurePriority",
    "HYBRID_AE60_EXPOSURE_NS",
    "frameGapCaptureCallback",
    "setSingleRepeatingRequest",
]:
    if forbidden in capture:
        raise SystemExit(f"trecho indesejado presente em CaptureService: {forbidden}")

capture_path.write_text(capture, encoding="utf-8")

policy = policy_path.read_text(encoding="utf-8")
old_guard = "if (!manualSensorSupported || fps != 60 || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null"
new_guard = "if (!manualSensorSupported || fps !in SUPPORTED_FPS || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null"
if policy.count(old_guard) != 1:
    raise SystemExit(f"guard de SensorCadencePolicy: esperado 1 trecho, encontrado {policy.count(old_guard)}")
policy = policy.replace(old_guard, new_guard, 1)

anchor = "object SensorCadencePolicy {\n"
insert = "object SensorCadencePolicy {\n    private val SUPPORTED_FPS = setOf(30, 60, 120, 240)\n\n"
if policy.count(anchor) != 1:
    raise SystemExit("âncora SensorCadencePolicy não encontrada")
policy = policy.replace(anchor, insert, 1)
policy_path.write_text(policy, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if "versionCode = 1000133" not in build:
    raise SystemExit("versionCode 1000133 não encontrado na branch base")
build = build.replace("versionCode = 1000133", "versionCode = 1000138", 1)
build_path.write_text(build, encoding="utf-8")

print("CLEAN_ALL_FPS_OK")
