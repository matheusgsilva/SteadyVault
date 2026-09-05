from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: esperado 1 trecho, encontrado {count}: {old!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt",
    "setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)\n            setSafely(builder, CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs)",
    "setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)\n            setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)\n            setSafely(builder, CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs)",
)

capture = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt").read_text(encoding="utf-8")
if "CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange" not in capture:
    raise SystemExit("CONTROL_AE_TARGET_FPS_RANGE não foi aplicado ao request manual")

print("FPS_MANUAL_60_RANGE_FIX_OK")
