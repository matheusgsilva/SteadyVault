from pathlib import Path

path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
text = path.read_text()

old = '''    private fun resolveRegularHighFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int
    ): Range<Int>? {
        if (targetFps < CaptureModeStore.FPS_120) return Range(targetFps, targetFps)
        val exact = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val minFrameNs = encoderSurfaceMinFrameDurationNs(map, size, preferMediaRecorder = true)
        val targetFrameNs = frameDurationNs(targetFps)
        if (minFrameNs > 0L && minFrameNs > targetFrameNs + HIGH_FPS_FRAME_TOLERANCE_NS) return null
        return exact
    }
'''
new = '''    private fun resolveRegularHighFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int
    ): Range<Int>? {
        if (targetFps < CaptureModeStore.FPS_120) return Range(targetFps, targetFps)
        val exact = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
            ?: return null

        // No S25 Ultra, 120 FPS constrained chega ao MediaRecorder em lotes de
        // 4 quadros (~4/4/4/21 ms). Se a própria câmera publica [120,120] para uma
        // sessão normal, deixe a HAL validar a combinação real em vez de descartá-la
        // pelo minFrameDuration público, que é conservador em alguns Samsung.
        if (targetFps == CaptureModeStore.FPS_120) return exact

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val minFrameNs = encoderSurfaceMinFrameDurationNs(map, size, preferMediaRecorder = true)
        val targetFrameNs = frameDurationNs(targetFps)
        if (minFrameNs > 0L && minFrameNs > targetFrameNs + HIGH_FPS_FRAME_TOLERANCE_NS) return null
        return exact
    }
'''
if text.count(old) != 1:
    raise SystemExit(f"resolveRegularHighFpsRange: expected 1 match, found {text.count(old)}")
text = text.replace(old, new, 1)

old_rev = 'private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-auto60-1.8.260"'
new_rev = 'private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-auto60-1.8.261-regular120"'
if text.count(old_rev) != 1:
    raise SystemExit(f"pipeline revision: expected 1 match, found {text.count(old_rev)}")
text = text.replace(old_rev, new_rev, 1)

path.write_text(text)
