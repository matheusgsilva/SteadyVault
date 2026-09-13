from pathlib import Path

p = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
t = p.read_text()

if "private fun selectBestCaptureConfiguration(" not in t:
    marker = "    private fun configurationSignature("
    if marker not in t:
        raise SystemExit("configurationSignature marker not found")
    block = '''    private fun selectBestCaptureConfiguration(
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

    private fun cacheConfiguration(
        signature: String,
        camera: CameraProfile,
        encoder: EncoderProfile
    ) {
        cachedConfiguration = CachedConfiguration(signature, camera, encoder)
        getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE).edit()
            .putString(CONFIG_SIGNATURE, signature)
            .putString(CONFIG_CAMERA_ID, camera.cameraId)
            .putInt(CONFIG_WIDTH, camera.videoSize.width)
            .putInt(CONFIG_HEIGHT, camera.videoSize.height)
            .putInt(CONFIG_FPS, camera.targetFps)
            .remove(CONFIG_HIGH_SPEED)
            .putLong(CONFIG_DYNAMIC_RANGE, camera.dynamicRangeProfile)
            .putString(CONFIG_MIME, encoder.mime)
            .apply()
    }

    private fun loadRememberedConfiguration(
        signature: String,
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile>? {
        val prefs = getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE)
        if (prefs.getString(CONFIG_SIGNATURE, null) != signature) return null
        if (prefs.getBoolean(CONFIG_HIGH_SPEED, false)) return null
        val cameraId = prefs.getString(CONFIG_CAMERA_ID, null) ?: return null
        val width = prefs.getInt(CONFIG_WIDTH, 0)
        val height = prefs.getInt(CONFIG_HEIGHT, 0)
        val fps = prefs.getInt(CONFIG_FPS, 0)
        val storedDynamicRange = prefs.getLong(CONFIG_DYNAMIC_RANGE, standardDynamicRangeProfile())
        val mime = prefs.getString(CONFIG_MIME, null) ?: return null
        if (width <= 0 || height <= 0 || fps <= 0 || fps !in CaptureSettings.supportedFpsValues || mime !in recordingSettings.codecMimes(allowHdr)) return null
        val rememberedSize = Size(width, height)
        if (fps != targetFps || recordingSettings.exactPreferredSize()?.let { it != rememberedSize } == true) {
            getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE).edit().clear().apply()
            return null
        }

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return null
        val fpsRange = resolveFpsRange(characteristics, rememberedSize, fps, false) ?: return null
        val dynamicRange = when {
            allowHdr && supportsHlg10(characteristics) -> storedDynamicRange
            allowHdr -> return null
            else -> standardDynamicRangeProfile()
        }
        val profile = createCameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = rememberedSize,
            targetFps = fps,
            fpsRange = fpsRange,
            highSpeed = false,
            dynamicRangeProfile = dynamicRange
        )
        if (mime !in recordingSettings.codecMimes(profile.hdrHlg10)) return null
        val encoder = selectDirectRecorderEncoder(profile) ?: return null
        return profile to encoder
    }

'''
    t = t.replace(marker, block + marker, 1)
    p.write_text(t)
    print("regular 30/60 configuration restored")
else:
    print("regular configuration already present")
