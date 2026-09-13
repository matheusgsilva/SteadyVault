from pathlib import Path

p = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
t = p.read_text()

if "private fun selectBestCaptureConfiguration(" not in t:
    marker = "    private fun cacheConfiguration(\n"
    if marker not in t:
        raise SystemExit("cacheConfiguration marker not found")
    fn = '''    private fun selectBestCaptureConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> {
        val signature = configurationSignature(targetFps, allowHdr)
        cachedConfiguration
            ?.takeIf { it.signature == signature }
            ?.takeIf { matchesRequestedMode(it.camera, targetFps) }
            ?.takeIf { it.camera.matchesRequestedFpsContract() }
            ?.takeIf { preferredCameraCompatible(it.camera) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.camera) }
            ?.let { return it.camera to it.encoder }

        loadRememberedConfiguration(signature, targetFps, allowHdr)
            ?.takeIf { preferredCameraCompatible(it.first) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.first) }
            ?.let { (camera, encoder) ->
                cacheConfiguration(signature, camera, encoder)
                return camera to encoder
            }

        selectPreferredPreviewConfiguration(targetFps, allowHdr)?.let { (camera, encoder) ->
            cacheConfiguration(signature, camera, encoder)
            return camera to encoder
        }

        val requestedLabel = "${CaptureSettings.resolutionLabel(recordingSettings.resolution)} $targetFps FPS"
        val cameraLabel = CameraLensCatalog.labelFor(this, preferredCameraId)
        throw IllegalStateException("$requestedLabel não pôde ser preparado para a câmera selecionada ($cameraLabel)")
    }

'''
    t = t.replace(marker, fn + marker, 1)
    p.write_text(t)
    print("selectBestCaptureConfiguration restored")
else:
    print("selectBestCaptureConfiguration already present")
