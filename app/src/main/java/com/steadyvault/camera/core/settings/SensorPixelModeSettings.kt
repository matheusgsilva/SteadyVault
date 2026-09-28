package com.steadyvault.camera.core.settings

import android.content.Context

/**
 * Experimental Camera2 sensor-pixel-mode selector for cadence testing.
 *
 * AUTO leaves the key unset so Samsung HAL chooses its normal video sensor path.
 * NORMAL and MAXIMUM_RESOLUTION map to CaptureRequest.SENSOR_PIXEL_MODE values
 * when the platform/camera exposes support.
 */
object SensorPixelModeSettings {
    const val AUTO = "AUTO"
    const val NORMAL = "NORMAL"
    const val MAXIMUM_RESOLUTION = "MAXIMUM_RESOLUTION"

    private const val PREFS = "steadyvault_sensor_pixel_mode"
    private const val KEY_MODE = "mode"
    private val supported = setOf(AUTO, NORMAL, MAXIMUM_RESOLUTION)

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AUTO)
            ?.takeIf { it in supported }
            ?: AUTO

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.takeIf { it in supported } ?: AUTO)
            .apply()
    }
}
