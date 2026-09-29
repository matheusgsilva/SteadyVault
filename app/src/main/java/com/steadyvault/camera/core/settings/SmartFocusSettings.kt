package com.steadyvault.camera.core.settings

import android.content.Context

/**
 * Experimental main4 subject focus.
 * Uses Camera2 face metadata only, so it does not add a second analysis stream.
 */
object SmartFocusSettings {
    private const val PREFS = "steadyvault_smart_focus"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
