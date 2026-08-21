package com.steadyvault.camera.core.settings

import android.content.Context
import android.util.Base64

/**
 * Perfis por câmera e por modo real de uso.
 *
 * Foto continua separada de vídeo e, dentro de vídeo, cada FPS mantém um
 * perfil próprio. Assim 30/60/120/240 FPS não se sobrescrevem entre si.
 */
object CameraProfileStore {
    enum class FunctionMode { PHOTO, VIDEO }

    data class Summary(
        val mode: FunctionMode,
        val resolution: String,
        val fps: Int,
        val codec: String,
        val hdr: Boolean,
        val whiteBalance: String,
        val exposureCompensation: Int,
        val zoomRatio: Float
    )

    private const val PREFS = "steadyvault_camera_profiles"
    private const val ACTIVE_MODE = "active_mode"
    private const val LAST_BACK_CAMERA = "last_back_camera"
    private const val LAST_FRONT_CAMERA = "last_front_camera"
    private const val PROFILE_EXISTS = "exists"

    private data class ProfileScope(val mode: FunctionMode, val suffix: String)

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun activeMode(context: Context): FunctionMode = runCatching {
        FunctionMode.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ACTIVE_MODE, FunctionMode.VIDEO.name)
                ?: FunctionMode.VIDEO.name
        )
    }.getOrDefault(FunctionMode.VIDEO)

    fun setActiveMode(context: Context, mode: FunctionMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_MODE, mode.name).apply()
    }

    fun ensureProfiles(context: Context, cameraId: String, snapshot: CaptureSettings.Snapshot) {
        ensureScope(context, cameraId, snapshot, scopeFor(FunctionMode.PHOTO, snapshot.copy(fps = 30)))
        CaptureSettings.supportedFpsValues.forEach { fps ->
            val resolution = CaptureSettings.resolutionForFps(context, fps)
            val codec = snapshot.codec
            val seeded = defaultForMode(
                snapshot.copy(
                    selectedCameraId = cameraId,
                    fps = fps,
                    resolution = resolution,
                    bitrateMbps = snapshot.bitrateMbps,
                    zoomRatio = 1f,
                    hdrHlg10 = if (fps >= 120) false else snapshot.hdrHlg10,
                    colorProfile = if (fps >= 120) CaptureSettings.COLOR_NATURAL else snapshot.colorProfile
                ),
                cameraId,
                FunctionMode.VIDEO
            )
            ensureScope(context, cameraId, seeded, scopeFor(FunctionMode.VIDEO, seeded))
        }
    }

    fun activate(
        context: Context,
        cameraId: String,
        mode: FunctionMode,
        fallback: CaptureSettings.Snapshot = CaptureSettings.snapshot(context)
    ): CaptureSettings.Snapshot {
        setActiveMode(context, mode)
        val scope = scopeFor(mode, fallback)
        val stored = if (hasScope(context, cameraId, scope)) {
            readProfile(context, cameraId, scope, fallback)
        } else {
            defaultForMode(fallback, cameraId, mode).also {
                writeProfile(context, cameraId, scope, it)
            }
        }
        // Bitrate é uma preferência persistente de gravação e não pertence ao perfil
        // de câmera/foto. Preservá-lo em ambos os modos evita que entrar em FOTO e
        // voltar para VÍDEO restaure silenciosamente um valor antigo.
        val activated = stored.copy(bitrateMbps = fallback.bitrateMbps)
        CaptureSettings.save(context, activated)
        return activated
    }

    fun onSnapshotSaved(context: Context, snapshot: CaptureSettings.Snapshot) {
        val cameraId = snapshot.selectedCameraId?.takeIf { it.isNotBlank() } ?: return
        writeProfile(context, cameraId, scopeFor(activeMode(context), snapshot), snapshot)
    }

    fun saveCurrent(context: Context, mode: FunctionMode = activeMode(context)) {
        val snapshot = CaptureSettings.snapshot(context)
        val cameraId = snapshot.selectedCameraId?.takeIf { it.isNotBlank() } ?: return
        writeProfile(context, cameraId, scopeFor(mode, snapshot), snapshot)
    }

    fun saveProfile(context: Context, cameraId: String, mode: FunctionMode, snapshot: CaptureSettings.Snapshot) {
        writeProfile(context, cameraId, scopeFor(mode, snapshot), snapshot.copy(selectedCameraId = cameraId))
    }


    fun summary(
        context: Context,
        cameraId: String,
        mode: FunctionMode,
        fallback: CaptureSettings.Snapshot = CaptureSettings.snapshot(context)
    ): Summary {
        val scope = scopeFor(mode, fallback)
        val value = if (hasScope(context, cameraId, scope)) readProfile(context, cameraId, scope, fallback)
        else fallback.copy(selectedCameraId = cameraId)
        return Summary(
            mode = mode,
            resolution = value.resolution,
            fps = value.fps,
            codec = value.codec,
            hdr = value.hdrHlg10,
            whiteBalance = value.whiteBalanceMode,
            exposureCompensation = value.exposureCompensation,
            zoomRatio = value.zoomRatio
        )
    }

    fun rememberCamera(context: Context, cameraId: String, front: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (front) LAST_FRONT_CAMERA else LAST_BACK_CAMERA, cameraId)
            .apply()
    }

    fun lastCamera(context: Context, front: Boolean): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(if (front) LAST_FRONT_CAMERA else LAST_BACK_CAMERA, null)
            ?.takeIf { it.isNotBlank() }

    fun resetProfile(
        context: Context,
        cameraId: String,
        mode: FunctionMode,
        fallback: CaptureSettings.Snapshot = CaptureSettings.snapshot(context)
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = scopePrefix(cameraId, scopeFor(mode, fallback))
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }


    private fun ensureScope(
        context: Context,
        cameraId: String,
        snapshot: CaptureSettings.Snapshot,
        scope: ProfileScope
    ) {
        if (!hasScope(context, cameraId, scope)) {
            writeProfile(context, cameraId, scope, snapshot)
        }
    }

    private fun defaultForMode(
        snapshot: CaptureSettings.Snapshot,
        cameraId: String,
        mode: FunctionMode
    ): CaptureSettings.Snapshot = snapshot.copy(
        selectedCameraId = cameraId,
        zoomRatio = snapshot.zoomRatio.takeIf { it.isFinite() }?.coerceIn(0.5f, 30f) ?: 1f,
        focusMode = if (mode == FunctionMode.PHOTO) {
            CaptureSettings.FOCUS_CONTINUOUS_PICTURE
        } else {
            CaptureSettings.FOCUS_CONTINUOUS_VIDEO
        },
        hdrHlg10 = if (mode == FunctionMode.VIDEO && snapshot.fps >= 120) false else snapshot.hdrHlg10,
        colorProfile = if (mode == FunctionMode.VIDEO && snapshot.fps >= 120) CaptureSettings.COLOR_NATURAL else snapshot.colorProfile
    )

    private fun writeProfile(
        context: Context,
        cameraId: String,
        scope: ProfileScope,
        value: CaptureSettings.Snapshot
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(cameraId, scope, PROFILE_EXISTS), true)
            .putString(key(cameraId, scope, "resolution"), value.resolution)
            .putInt(key(cameraId, scope, "fps"), value.fps)
            .putString(key(cameraId, scope, "codec"), value.codec)
            .putInt(key(cameraId, scope, "iframe_interval"), value.iFrameIntervalSeconds)
            .putBoolean(key(cameraId, scope, "hdr_hlg10"), value.hdrHlg10)
            .putString(key(cameraId, scope, "color_profile"), value.colorProfile)
            .putString(key(cameraId, scope, "stabilization"), value.stabilization)
            .putString(key(cameraId, scope, "focus_mode"), value.focusMode)
            .putString(key(cameraId, scope, "noise_reduction"), value.noiseReduction)
            .putString(key(cameraId, scope, "edge_mode"), value.edgeMode)
            .putString(key(cameraId, scope, "antibanding"), value.antibanding)
            .putString(key(cameraId, scope, "white_balance_mode"), value.whiteBalanceMode)
            .putString(key(cameraId, scope, "yellow_reduction"), value.yellowReduction)
            .putBoolean(key(cameraId, scope, "lock_white_balance"), value.lockWhiteBalance)
            .putInt(key(cameraId, scope, "exposure_compensation"), value.exposureCompensation)
            .putFloat(key(cameraId, scope, "zoom_ratio"), value.zoomRatio)
            .apply()
    }

    private fun readProfile(
        context: Context,
        cameraId: String,
        scope: ProfileScope,
        defaults: CaptureSettings.Snapshot
    ): CaptureSettings.Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val resolution = prefs.getString(key(cameraId, scope, "resolution"), defaults.resolution)
            ?.takeIf { it in CaptureSettings.supportedResolutionValues } ?: defaults.resolution
        val fps = prefs.getInt(key(cameraId, scope, "fps"), defaults.fps)
            .takeIf { it in CaptureSettings.supportedFpsValues } ?: defaults.fps
        val codec = prefs.getString(key(cameraId, scope, "codec"), defaults.codec)
            ?.takeIf { it in CaptureSettings.supportedCodecValues } ?: defaults.codec
        val stabilization = prefs.getString(key(cameraId, scope, "stabilization"), defaults.stabilization)
            ?.takeIf { it in CaptureSettings.supportedStabilizationValues } ?: defaults.stabilization
        return defaults.copy(
            resolution = resolution,
            fps = fps,
            codec = codec,
            bitrateMbps = defaults.bitrateMbps,
            iFrameIntervalSeconds = prefs.getInt(key(cameraId, scope, "iframe_interval"), defaults.iFrameIntervalSeconds).coerceIn(1, 10),
            hdrHlg10 = prefs.getBoolean(key(cameraId, scope, "hdr_hlg10"), defaults.hdrHlg10),
            colorProfile = prefs.getString(key(cameraId, scope, "color_profile"), defaults.colorProfile) ?: defaults.colorProfile,
            stabilization = stabilization,
            focusMode = prefs.getString(key(cameraId, scope, "focus_mode"), defaults.focusMode) ?: defaults.focusMode,
            noiseReduction = prefs.getString(key(cameraId, scope, "noise_reduction"), defaults.noiseReduction) ?: defaults.noiseReduction,
            edgeMode = prefs.getString(key(cameraId, scope, "edge_mode"), defaults.edgeMode) ?: defaults.edgeMode,
            antibanding = prefs.getString(key(cameraId, scope, "antibanding"), defaults.antibanding) ?: defaults.antibanding,
            whiteBalanceMode = prefs.getString(key(cameraId, scope, "white_balance_mode"), defaults.whiteBalanceMode)
                ?.takeIf { it in CaptureSettings.supportedWhiteBalanceValues } ?: defaults.whiteBalanceMode,
            yellowReduction = prefs.getString(key(cameraId, scope, "yellow_reduction"), defaults.yellowReduction)
                ?.takeIf { it in CaptureSettings.supportedYellowReductionValues } ?: defaults.yellowReduction,
            lockWhiteBalance = prefs.getBoolean(key(cameraId, scope, "lock_white_balance"), defaults.lockWhiteBalance),
            exposureCompensation = prefs.getInt(key(cameraId, scope, "exposure_compensation"), defaults.exposureCompensation).coerceIn(-12, 12),
            selectedCameraId = cameraId,
            zoomRatio = prefs.getFloat(key(cameraId, scope, "zoom_ratio"), defaults.zoomRatio)
                .takeIf { it.isFinite() }?.coerceIn(0.5f, 30f) ?: 1f
        )
    }

    private fun hasScope(context: Context, cameraId: String, scope: ProfileScope): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(cameraId, scope, PROFILE_EXISTS), false)

    private fun scopeFor(mode: FunctionMode, snapshot: CaptureSettings.Snapshot): ProfileScope = when (mode) {
        FunctionMode.PHOTO -> ProfileScope(mode, "photo")
        FunctionMode.VIDEO -> ProfileScope(mode, "video_${snapshot.fps.takeIf { it in CaptureSettings.supportedFpsValues } ?: 60}")
    }

    private fun key(cameraId: String, scope: ProfileScope, field: String): String =
        "${scopePrefix(cameraId, scope)}$field"

    private fun scopePrefix(cameraId: String, scope: ProfileScope): String =
        "profile_${encodedCameraId(cameraId)}_${scope.suffix}_"

    private fun encodedCameraId(cameraId: String): String = Base64.encodeToString(
        cameraId.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}
