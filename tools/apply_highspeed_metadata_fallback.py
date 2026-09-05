from pathlib import Path

path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
text = path.read_text(encoding="utf-8")

old = '''        val storedTargetResolution = CaptureSettings.resolutionForFps(this, requestedTargetFps)
        val resolvedTargetResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = requestedTargetFps,
            requestedResolution = storedTargetResolution,
            matrix = CaptureCapabilityMatrix.cached(this)?.forCamera(profileCameraId)
        )
'''
new = '''        val storedTargetResolution = CaptureSettings.resolutionForFps(this, requestedTargetFps)
        val resolvedTargetResolution = resolveCaptureResolutionForFps(
            cameraId = profileCameraId,
            targetFps = requestedTargetFps,
            storedResolution = storedTargetResolution
        )
'''
if text.count(old) != 1:
    raise SystemExit(f"resolução por catálogo esperada 1 vez, encontrada {text.count(old)}")
text = text.replace(old, new, 1)

anchor = '''    private fun selectBestCaptureConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> {
'''
helper = '''    /**
     * O widget pode ser usado antes de a análise completa de hardware existir.
     * Para 120/240, consulta diretamente os metadados constrained high-speed e
     * escolhe a maior resolução que publica a faixa fixa exata solicitada.
     */
    private fun resolveCaptureResolutionForFps(
        cameraId: String,
        targetFps: Int,
        storedResolution: String
    ): String {
        val cachedMatrix = CaptureCapabilityMatrix.cached(this)?.forCamera(cameraId)
        val catalogResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = storedResolution,
            matrix = cachedMatrix
        )
        if (targetFps < CaptureModeStore.FPS_120 || cachedMatrix != null) {
            return catalogResolution
        }

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return storedResolution
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return storedResolution
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        val candidates = listOf(
            CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE,
            CaptureSettings.RESOLUTION_4K to CaptureSettings.UHD_SIZE,
            CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE,
            CaptureSettings.RESOLUTION_720P to CaptureSettings.HD_SIZE
        )
        return candidates.firstOrNull { (_, size) ->
            size in highSpeedSizes && runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)?.any { range ->
                    StrictCaptureModePolicy.acceptsFpsRange(targetFps, range.lower, range.upper)
                } == true
            }.getOrDefault(false)
        }?.first ?: storedResolution
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("âncora selectBestCaptureConfiguration não encontrada")
text = text.replace(anchor, helper + anchor, 1)

for marker in [
    "resolveCaptureResolutionForFps(",
    "map.highSpeedVideoSizes",
    "getHighSpeedVideoFpsRangesFor(size)",
    "CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE",
]:
    if marker not in text:
        raise SystemExit(f"marcador ausente: {marker}")

path.write_text(text, encoding="utf-8")
print("HIGHSPEED_METADATA_FALLBACK_OK")
