package com.steadyvault.camera.core.settings

import android.content.Context

object RecordingDisplayPreferences {
    private const val PREFS = "steadyvault_recording_display"
    private const val KEY_APP = "black_screen_app_headless"
    private const val KEY_WIDGET = "black_screen_widget"
    private const val KEY_SHORTCUT = "black_screen_quick_shortcut"

    fun appHeadless(context: Context): Boolean = prefs(context).getBoolean(KEY_APP, true)
    fun widget(context: Context): Boolean = prefs(context).getBoolean(KEY_WIDGET, false)
    fun quickShortcut(context: Context): Boolean = prefs(context).getBoolean(KEY_SHORTCUT, true)

    fun setAppHeadless(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_APP, enabled).apply()
    fun setWidget(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_WIDGET, enabled).apply()
    fun setQuickShortcut(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_SHORTCUT, enabled).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
