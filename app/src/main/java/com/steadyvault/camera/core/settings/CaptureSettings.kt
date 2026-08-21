package com.steadyvault.camera.core.settings

import android.content.Context
import android.media.MediaFormat
import android.util.Size

object CaptureSettings {
    const val RESOLUTION_8K = "8K"
    const val RESOLUTION_4K = "4K"
    const val RESOLUTION_1080P = "1080P"
    const val RESOLUTION_720P = "720P"

    const val CODEC_HEVC = "HEVC"
    const val CODEC_AVC = "AVC"

    const val STABILIZATION_PREVIEW = "PREVIEW"
    const val STABILIZATION_EIS = "EIS"
    const val STABILIZATION_OIS = "OIS"
    const val STABILIZATION_OFF = "OFF"

    const val FOCUS_CONTINUOUS_VIDEO = "CONTINUOUS_VIDEO"
    const val FOCUS_CONTINUOUS_PICTURE = "CONTINUOUS_PICTURE"
    const val FOCUS_AUTO = "AUTO"
    const val FOCUS_OFF = "OFF"

    const val PROCESSING_AUTO = "AUTO"
    const val PROCESSING_OFF = "OFF"
    const val PROCESSING_FAST = "FAST"
    const val PROCESSING_HIGH_QUALITY = "HIGH_QUALITY"

    const val ANTIBANDING_AUTO = "AUTO"
    const val ANTIBANDING_50HZ = "50HZ"
    const val ANTIBANDING_60HZ = "60HZ"
    const val ANTIBANDING_OFF = "OFF"

    const val CHANNELS_AUTO = "AUTO"
    const val CHANNELS_MONO = "MONO"
    const val CHANNELS_STEREO = "STEREO"

    const val COLOR_NATURAL = "NATURAL"
    const val COLOR_SOFT = "SOFT"
    const val COLOR_FLAT = "FLAT"

    const val WHITE_BALANCE_AUTO = "AUTO"
    const val WHITE_BALANCE_INCANDESCENT = "INCANDESCENT"
    const val WHITE_BALANCE_FLUORESCENT = "FLUORESCENT"
    const val WHITE_BALANCE_WARM_FLUORESCENT = "WARM_FLUORESCENT"
    const val WHITE_BALANCE_DAYLIGHT = "DAYLIGHT"
    const val WHITE_BALANCE_CLOUDY = "CLOUDY"
    const val WHITE_BALANCE_TWILIGHT = "TWILIGHT"
    const val WHITE_BALANCE_SHADE = "SHADE"

    const val YELLOW_REDUCTION_OFF = "OFF"
    const val YELLOW_REDUCTION_AUTO = "AUTO"
    const val YELLOW_REDUCTION_LIGHT = "LIGHT"
    const val YELLOW_REDUCTION_MEDIUM = "MEDIUM"
    const val YELLOW_REDUCTION_STRONG = "STRONG"

    const val PREVIEW_OFF = "OFF"
    const val PREVIEW_FULL = "FULL"

    data class Snapshot(
        val resolution: String,
        val fps: Int,
        val codec: String,
        val bitrateMbps: Int,
        val iFrameIntervalSeconds: Int,
        val hdrHlg10: Boolean,
        val colorProfile: String,
        val stabilization: String,
        val focusMode: String,
        val noiseReduction: String,
        val edgeMode: String,
        val antibanding: String,
        val whiteBalanceMode: String,
        val yellowReduction: String,
        val lockWhiteBalance: Boolean,
        val previewMode: String,
        val exposureCompensation: Int,
        val selectedCameraId: String?,
        val zoomRatio: Float,
        val thermalProtection: Boolean,
        val audioSampleRate: Int,
        val audioBitrateKbps: Int,
        val audioChannels: String,
        val audioGainDb: Int,
        val audioAgc: Boolean,
        val audioNoiseSuppressor: Boolean,
        val audioLowCut: Boolean,
        val vibrateStartStop: Boolean,
        val secureScreen: Boolean
    ) {
        fun preferredSizes(): List<Size> = exactPreferredSize()?.let(::listOf).orEmpty()

        fun exactPreferredSize(): Size? = when (resolution) {
            RESOLUTION_8K -> EIGHT_K_SIZE
            RESOLUTION_4K -> UHD_SIZE
            RESOLUTION_1080P -> FHD_SIZE
            RESOLUTION_720P -> HD_SIZE
            else -> null
        }

        fun codecMimes(hdr: Boolean = hdrHlg10): List<String> = when (codec) {
            CODEC_HEVC -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC)
            CODEC_AVC -> if (hdr) emptyList() else listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
            else -> emptyList()
        }
    }

    private const val PREFS = "steadyvault_capture_settings"

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fps = prefs.getInt("fps", 60).takeIf { it in SUPPORTED_FPS } ?: 60
        val resolution = resolutionForFps(context, fps)
        val storedCodec = prefs.getString("codec", CODEC_HEVC)
        val codec = storedCodec?.takeIf { it in SUPPORTED_CODECS } ?: CODEC_HEVC
        val storedStabilization = prefs.getString("stabilization", STABILIZATION_OFF)
        val stabilization = storedStabilization?.takeIf { it in SUPPORTED_STABILIZATIONS } ?: STABILIZATION_OFF

        if (storedCodec != codec || storedStabilization != stabilization) {
            prefs.edit().putString("codec", codec).putString("stabilization", stabilization).apply()
        }

        val defaultBitrate = defaultBitrateMbps(resolution, fps, codec)
        val storedBitrate = prefs.getInt("bitrate_mbps", defaultBitrate).coerceIn(4, 240)
        val storedEdgeMode = prefs.getString("edge_mode", PROCESSING_FAST) ?: PROCESSING_FAST
        val edgeMode = if (!prefs.getBoolean("edge_mode_clarity_migrated_v163", false) && storedEdgeMode == PROCESSING_OFF) {
            prefs.edit().putString("edge_mode", PROCESSING_FAST).putBoolean("edge_mode_clarity_migrated_v163", true).apply()
            PROCESSING_FAST
        } else storedEdgeMode

        if (!prefs.getBoolean("minimal_audio_pipeline_migrated_v217", false)) {
            prefs.edit().putInt("audio_gain_db", 0).putBoolean("audio_low_cut", false).putBoolean("minimal_audio_pipeline_migrated_v217", true).apply()
        }

        return Snapshot(
            resolution = resolution,
            fps = fps,
            codec = codec,
            bitrateMbps = storedBitrate,
            iFrameIntervalSeconds = prefs.getInt("iframe_interval", 2).coerceIn(1, 10),
            hdrHlg10 = prefs.getBoolean("hdr_hlg10", false),
            colorProfile = prefs.getString("color_profile", COLOR_NATURAL) ?: COLOR_NATURAL,
            stabilization = stabilization,
            focusMode = prefs.getString("focus_mode", FOCUS_CONTINUOUS_VIDEO) ?: FOCUS_CONTINUOUS_VIDEO,
            noiseReduction = prefs.getString("noise_reduction", PROCESSING_FAST) ?: PROCESSING_FAST,
            edgeMode = edgeMode,
            antibanding = prefs.getString("antibanding", ANTIBANDING_AUTO) ?: ANTIBANDING_AUTO,
            whiteBalanceMode = prefs.getString("white_balance_mode", WHITE_BALANCE_AUTO)?.takeIf { it in supportedWhiteBalanceValues } ?: WHITE_BALANCE_AUTO,
            yellowReduction = prefs.getString("yellow_reduction", YELLOW_REDUCTION_AUTO)?.takeIf { it in supportedYellowReductionValues } ?: YELLOW_REDUCTION_AUTO,
            lockWhiteBalance = prefs.getBoolean("lock_white_balance", true),
            previewMode = prefs.getString("preview_mode", PREVIEW_OFF)?.takeIf { it in supportedPreviewValues } ?: PREVIEW_OFF,
            exposureCompensation = prefs.getInt("exposure_compensation", 0).coerceIn(-12, 12),
            selectedCameraId = prefs.getString("selected_camera_id", null)?.takeIf { it.isNotBlank() },
            zoomRatio = prefs.getFloat("zoom_ratio", 1f).takeIf { it.isFinite() }?.coerceIn(0.5f, 30f) ?: 1f,
            thermalProtection = prefs.getBoolean("thermal_protection", true),
            audioSampleRate = prefs.getInt("audio_sample_rate", 48_000).takeIf { it == 44_100 || it == 48_000 } ?: 48_000,
            audioBitrateKbps = prefs.getInt("audio_bitrate_kbps", 256).coerceIn(96, 320),
            audioChannels = prefs.getString("audio_channels", CHANNELS_AUTO) ?: CHANNELS_AUTO,
            audioGainDb = prefs.getInt("audio_gain_db", 0).coerceIn(0, 30),
            audioAgc = prefs.getBoolean("audio_agc", false),
            audioNoiseSuppressor = prefs.getBoolean("audio_noise_suppressor", true),
            audioLowCut = prefs.getBoolean("audio_low_cut", false),
            vibrateStartStop = prefs.getBoolean("vibrate_start_stop", true),
            secureScreen = prefs.getBoolean("secure_screen", true)
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        val normalized = snapshot.copy(
            resolution = snapshot.resolution.takeIf { it in SUPPORTED_RESOLUTIONS } ?: RESOLUTION_4K,
            codec = snapshot.codec.takeIf { it in SUPPORTED_CODECS } ?: CODEC_HEVC,
            stabilization = snapshot.stabilization.takeIf { it in SUPPORTED_STABILIZATIONS } ?: STABILIZATION_OFF
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("resolution", normalized.resolution)
            .putString(resolutionKey(normalized.fps), normalized.resolution)
            .putInt("fps", normalized.fps)
            .putString("codec", normalized.codec)
            .putInt("bitrate_mbps", normalized.bitrateMbps)
            .putInt("iframe_interval", normalized.iFrameIntervalSeconds)
            .putBoolean("hdr_hlg10", normalized.hdrHlg10)
            .putString("color_profile", normalized.colorProfile)
            .putString("stabilization", normalized.stabilization)
            .putString("focus_mode", normalized.focusMode)
            .putString("noise_reduction", normalized.noiseReduction)
            .putString("edge_mode", normalized.edgeMode)
            .putString("antibanding", normalized.antibanding)
            .putString("white_balance_mode", normalized.whiteBalanceMode)
            .putString("yellow_reduction", normalized.yellowReduction)
            .putBoolean("lock_white_balance", normalized.lockWhiteBalance)
            .putString("preview_mode", normalized.previewMode)
            .putInt("exposure_compensation", normalized.exposureCompensation)
            .putString("selected_camera_id", normalized.selectedCameraId)
            .putFloat("zoom_ratio", normalized.zoomRatio)
            .putBoolean("thermal_protection", normalized.thermalProtection)
            .putInt("audio_sample_rate", normalized.audioSampleRate)
            .putInt("audio_bitrate_kbps", normalized.audioBitrateKbps)
            .putString("audio_channels", normalized.audioChannels)
            .putInt("audio_gain_db", normalized.audioGainDb)
            .putBoolean("audio_agc", normalized.audioAgc)
            .putBoolean("audio_noise_suppressor", normalized.audioNoiseSuppressor)
            .putBoolean("audio_low_cut", normalized.audioLowCut)
            .putBoolean("vibrate_start_stop", normalized.vibrateStartStop)
            .putBoolean("secure_screen", normalized.secureScreen)
            .apply()
        CameraProfileStore.onSnapshotSaved(context, normalized)
    }

    fun restoreDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CameraProfileStore.clearAll(context)
    }

    fun resolutionForFps(context: Context, fps: Int): String {
        val safeFps = fps.takeIf { it in SUPPORTED_FPS } ?: 60
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(resolutionKey(safeFps), null)
        val legacyFps = prefs.getInt("fps", 60).takeIf { it in SUPPORTED_FPS } ?: 60
        val legacyResolution = prefs.getString("resolution", null)
        val resolved = when {
            stored in SUPPORTED_RESOLUTIONS -> stored!!
            safeFps == legacyFps && legacyResolution in SUPPORTED_RESOLUTIONS -> legacyResolution!!
            else -> RESOLUTION_4K
        }
        if (stored != resolved) prefs.edit().putString(resolutionKey(safeFps), resolved).apply()
        return resolved
    }

    fun saveResolutionForFps(context: Context, fps: Int, resolution: String) {
        val safeFps = fps.takeIf { it in SUPPORTED_FPS } ?: return
        val safeResolution = resolution.takeIf { it in SUPPORTED_RESOLUTIONS } ?: RESOLUTION_4K
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(resolutionKey(safeFps), safeResolution).apply()
    }

    fun updateResolutionAndFps(context: Context, resolution: String, fps: Int) {
        val current = snapshot(context)
        val safeFps = fps.takeIf { it in SUPPORTED_FPS } ?: 60
        val safeResolution = resolution.takeIf { it in SUPPORTED_RESOLUTIONS } ?: RESOLUTION_4K
        save(context, current.copy(resolution = safeResolution, fps = safeFps))
    }

    fun defaultBitrateMbps(resolution: String, fps: Int, codec: String): Int {
        val recommended = when (resolution) {
            RESOLUTION_8K -> when (fps) { 240 -> 220; 120 -> 220; 60 -> 180; else -> 100 }
            RESOLUTION_720P -> when (fps) { 240 -> 60; 120 -> 28; 60 -> 15; else -> 10 }
            RESOLUTION_1080P -> when (fps) { 240 -> 100; 120 -> 50; 60 -> 28; else -> 20 }
            RESOLUTION_4K -> when (fps) { 240 -> 170; 120 -> 135; 60 -> 60; else -> 48 }
            else -> when (fps) { 240 -> 170; 120 -> 135; 60 -> 60; else -> 48 }
        }
        return if (codec == CODEC_AVC) (recommended * 1.25).toInt().coerceAtMost(220) else recommended
    }

    fun resolutionLabel(value: String): String = when (value) {
        RESOLUTION_8K -> "8K UHD"
        RESOLUTION_4K -> "4K UHD"
        RESOLUTION_1080P -> "1080p"
        RESOLUTION_720P -> "720p"
        else -> "Desconhecida"
    }

    fun colorProfileLabel(value: String): String = when (value) {
        COLOR_SOFT -> "Natural suave"
        COLOR_FLAT -> "Baixo contraste"
        else -> "Natural"
    }

    val supportedResolutionValues: List<String> get() = SUPPORTED_RESOLUTIONS.toList()
    val supportedCodecValues: List<String> get() = SUPPORTED_CODECS.toList()
    val supportedStabilizationValues: List<String> get() = SUPPORTED_STABILIZATIONS.toList()
    val supportedFpsValues: List<Int> get() = SUPPORTED_FPS.toList()

    val supportedWhiteBalanceValues = linkedSetOf(
        WHITE_BALANCE_AUTO, WHITE_BALANCE_INCANDESCENT, WHITE_BALANCE_FLUORESCENT,
        WHITE_BALANCE_WARM_FLUORESCENT, WHITE_BALANCE_DAYLIGHT, WHITE_BALANCE_CLOUDY,
        WHITE_BALANCE_TWILIGHT, WHITE_BALANCE_SHADE
    )
    val supportedYellowReductionValues = linkedSetOf(
        YELLOW_REDUCTION_OFF, YELLOW_REDUCTION_AUTO, YELLOW_REDUCTION_LIGHT,
        YELLOW_REDUCTION_MEDIUM, YELLOW_REDUCTION_STRONG
    )
    val supportedPreviewValues = linkedSetOf(PREVIEW_OFF)

    val EIGHT_K_SIZE = Size(7680, 4320)
    val UHD_SIZE = Size(3840, 2160)
    val FHD_SIZE = Size(1920, 1080)
    val HD_SIZE = Size(1280, 720)

    private fun resolutionKey(fps: Int) = "resolution_$fps"
    private val SUPPORTED_RESOLUTIONS = linkedSetOf(RESOLUTION_8K, RESOLUTION_4K, RESOLUTION_1080P, RESOLUTION_720P)
    private val SUPPORTED_CODECS = linkedSetOf(CODEC_HEVC, CODEC_AVC)
    private val SUPPORTED_STABILIZATIONS = linkedSetOf(STABILIZATION_PREVIEW, STABILIZATION_EIS, STABILIZATION_OIS, STABILIZATION_OFF)
    private val SUPPORTED_FPS = linkedSetOf(30, 60, 120, 240)
}
