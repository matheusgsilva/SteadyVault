package com.steadyvault.camera.widgets

import android.content.Context
import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceStore

object WidgetAppearance {
    fun expandedAccentBackground(context: Context): Int = accentBackground(AppearanceStore.theme(context), lock = false)
    fun lockAccentBackground(context: Context): Int = lockAccentFor(context, AppearanceStore.theme(context))

    fun expandedDisabledBackground(context: Context): Int = disabledBackground(surfaceTheme(context), lock = false)
    fun lockDisabledBackground(context: Context): Int = lockDisabledFor(context, surfaceTheme(context))
    fun lockRecordingBackground(context: Context): Int = lockCorner(
        context,
        R.drawable.bg_widget_lock_button_recording_compact,
        R.drawable.bg_widget_lock_button_recording_balanced,
        R.drawable.bg_widget_lock_button_recording_soft
    )
    fun lockStopBackground(context: Context): Int = lockCorner(
        context,
        R.drawable.bg_widget_lock_button_red_compact,
        R.drawable.bg_widget_lock_button_red_balanced,
        R.drawable.bg_widget_lock_button_red_soft
    )

    fun expandedShellBackground(context: Context): Int = shellBackground(surfaceTheme(context), compact = false)
    fun compactShellBackground(context: Context): Int = shellBackground(surfaceTheme(context), compact = true)
    fun logoBackground(context: Context): Int = logoBackground(AppearanceStore.theme(context))
    fun dividerBackground(context: Context): Int = dividerBackground(surfaceTheme(context))

    private fun lockCorner(context: Context, compact: Int, balanced: Int, soft: Int): Int = when (AppearanceStore.corners(context)) {
        AppearanceStore.CORNERS_COMPACT -> compact
        AppearanceStore.CORNERS_SOFT -> soft
        else -> balanced
    }

    private fun lockAccentFor(context: Context, theme: String): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_ocean_compact, R.drawable.bg_widget_lock_accent_button_ocean_balanced, R.drawable.bg_widget_lock_accent_button_ocean_soft)
        AppearanceStore.THEME_VIOLET -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_violet_compact, R.drawable.bg_widget_lock_accent_button_violet_balanced, R.drawable.bg_widget_lock_accent_button_violet_soft)
        AppearanceStore.THEME_AMBER -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_amber_compact, R.drawable.bg_widget_lock_accent_button_amber_balanced, R.drawable.bg_widget_lock_accent_button_amber_soft)
        AppearanceStore.THEME_ICE -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_ice_compact, R.drawable.bg_widget_lock_accent_button_ice_balanced, R.drawable.bg_widget_lock_accent_button_ice_soft)
        AppearanceStore.THEME_AMOLED -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_amoled_compact, R.drawable.bg_widget_lock_accent_button_amoled_balanced, R.drawable.bg_widget_lock_accent_button_amoled_soft)
        AppearanceStore.THEME_GRAPHITE -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_graphite_compact, R.drawable.bg_widget_lock_accent_button_graphite_balanced, R.drawable.bg_widget_lock_accent_button_graphite_soft)
        AppearanceStore.THEME_CYAN -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_cyan_compact, R.drawable.bg_widget_lock_accent_button_cyan_balanced, R.drawable.bg_widget_lock_accent_button_cyan_soft)
        AppearanceStore.THEME_ROSE -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_rose_compact, R.drawable.bg_widget_lock_accent_button_rose_balanced, R.drawable.bg_widget_lock_accent_button_rose_soft)
        AppearanceStore.THEME_RUBY -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_ruby_compact, R.drawable.bg_widget_lock_accent_button_ruby_balanced, R.drawable.bg_widget_lock_accent_button_ruby_soft)
        AppearanceStore.THEME_MIDNIGHT -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_midnight_compact, R.drawable.bg_widget_lock_accent_button_midnight_balanced, R.drawable.bg_widget_lock_accent_button_midnight_soft)
        AppearanceStore.THEME_COPPER -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_copper_compact, R.drawable.bg_widget_lock_accent_button_copper_balanced, R.drawable.bg_widget_lock_accent_button_copper_soft)
        else -> lockCorner(context, R.drawable.bg_widget_lock_accent_button_emerald_compact, R.drawable.bg_widget_lock_accent_button_emerald_balanced, R.drawable.bg_widget_lock_accent_button_emerald_soft)
    }

    private fun lockDisabledFor(context: Context, theme: String): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_ocean_compact, R.drawable.bg_widget_lock_disabled_button_ocean_balanced, R.drawable.bg_widget_lock_disabled_button_ocean_soft)
        AppearanceStore.THEME_VIOLET -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_violet_compact, R.drawable.bg_widget_lock_disabled_button_violet_balanced, R.drawable.bg_widget_lock_disabled_button_violet_soft)
        AppearanceStore.THEME_AMBER -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_amber_compact, R.drawable.bg_widget_lock_disabled_button_amber_balanced, R.drawable.bg_widget_lock_disabled_button_amber_soft)
        AppearanceStore.THEME_ICE -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_ice_compact, R.drawable.bg_widget_lock_disabled_button_ice_balanced, R.drawable.bg_widget_lock_disabled_button_ice_soft)
        AppearanceStore.THEME_AMOLED -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_amoled_compact, R.drawable.bg_widget_lock_disabled_button_amoled_balanced, R.drawable.bg_widget_lock_disabled_button_amoled_soft)
        AppearanceStore.THEME_GRAPHITE -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_graphite_compact, R.drawable.bg_widget_lock_disabled_button_graphite_balanced, R.drawable.bg_widget_lock_disabled_button_graphite_soft)
        AppearanceStore.THEME_CYAN -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_cyan_compact, R.drawable.bg_widget_lock_disabled_button_cyan_balanced, R.drawable.bg_widget_lock_disabled_button_cyan_soft)
        AppearanceStore.THEME_ROSE -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_rose_compact, R.drawable.bg_widget_lock_disabled_button_rose_balanced, R.drawable.bg_widget_lock_disabled_button_rose_soft)
        AppearanceStore.THEME_RUBY -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_ruby_compact, R.drawable.bg_widget_lock_disabled_button_ruby_balanced, R.drawable.bg_widget_lock_disabled_button_ruby_soft)
        AppearanceStore.THEME_MIDNIGHT -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_midnight_compact, R.drawable.bg_widget_lock_disabled_button_midnight_balanced, R.drawable.bg_widget_lock_disabled_button_midnight_soft)
        AppearanceStore.THEME_COPPER -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_copper_compact, R.drawable.bg_widget_lock_disabled_button_copper_balanced, R.drawable.bg_widget_lock_disabled_button_copper_soft)
        else -> lockCorner(context, R.drawable.bg_widget_lock_disabled_button_emerald_compact, R.drawable.bg_widget_lock_disabled_button_emerald_balanced, R.drawable.bg_widget_lock_disabled_button_emerald_soft)
    }

    private fun surfaceTheme(context: Context): String = when (AppearanceStore.surfaceStyle(context)) {
        AppearanceStore.SURFACE_NEUTRAL -> AppearanceStore.THEME_GRAPHITE
        AppearanceStore.SURFACE_BLACK -> AppearanceStore.THEME_AMOLED
        else -> AppearanceStore.theme(context)
    }

    private fun accentBackground(theme: String, lock: Boolean): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> if (lock) R.drawable.bg_widget_lock_accent_button_ocean else R.drawable.bg_widget_accent_button_ocean
        AppearanceStore.THEME_VIOLET -> if (lock) R.drawable.bg_widget_lock_accent_button_violet else R.drawable.bg_widget_accent_button_violet
        AppearanceStore.THEME_AMBER -> if (lock) R.drawable.bg_widget_lock_accent_button_amber else R.drawable.bg_widget_accent_button_amber
        AppearanceStore.THEME_ICE -> if (lock) R.drawable.bg_widget_lock_accent_button_ice else R.drawable.bg_widget_accent_button_ice
        AppearanceStore.THEME_AMOLED -> if (lock) R.drawable.bg_widget_lock_accent_button_amoled else R.drawable.bg_widget_accent_button_amoled
        AppearanceStore.THEME_GRAPHITE -> if (lock) R.drawable.bg_widget_lock_accent_button_graphite else R.drawable.bg_widget_accent_button_graphite
        AppearanceStore.THEME_CYAN -> if (lock) R.drawable.bg_widget_lock_accent_button_cyan else R.drawable.bg_widget_accent_button_cyan
        AppearanceStore.THEME_ROSE -> if (lock) R.drawable.bg_widget_lock_accent_button_rose else R.drawable.bg_widget_accent_button_rose
        AppearanceStore.THEME_RUBY -> if (lock) R.drawable.bg_widget_lock_accent_button_ruby else R.drawable.bg_widget_accent_button_ruby
        AppearanceStore.THEME_MIDNIGHT -> if (lock) R.drawable.bg_widget_lock_accent_button_midnight else R.drawable.bg_widget_accent_button_midnight
        AppearanceStore.THEME_COPPER -> if (lock) R.drawable.bg_widget_lock_accent_button_copper else R.drawable.bg_widget_accent_button_copper
        else -> if (lock) R.drawable.bg_widget_lock_accent_button_emerald else R.drawable.bg_widget_accent_button_emerald
    }

    private fun disabledBackground(theme: String, lock: Boolean): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> if (lock) R.drawable.bg_widget_lock_disabled_button_ocean else R.drawable.bg_widget_disabled_button_ocean
        AppearanceStore.THEME_VIOLET -> if (lock) R.drawable.bg_widget_lock_disabled_button_violet else R.drawable.bg_widget_disabled_button_violet
        AppearanceStore.THEME_AMBER -> if (lock) R.drawable.bg_widget_lock_disabled_button_amber else R.drawable.bg_widget_disabled_button_amber
        AppearanceStore.THEME_ICE -> if (lock) R.drawable.bg_widget_lock_disabled_button_ice else R.drawable.bg_widget_disabled_button_ice
        AppearanceStore.THEME_AMOLED -> if (lock) R.drawable.bg_widget_lock_disabled_button_amoled else R.drawable.bg_widget_disabled_button_amoled
        AppearanceStore.THEME_GRAPHITE -> if (lock) R.drawable.bg_widget_lock_disabled_button_graphite else R.drawable.bg_widget_disabled_button_graphite
        AppearanceStore.THEME_CYAN -> if (lock) R.drawable.bg_widget_lock_disabled_button_cyan else R.drawable.bg_widget_disabled_button_cyan
        AppearanceStore.THEME_ROSE -> if (lock) R.drawable.bg_widget_lock_disabled_button_rose else R.drawable.bg_widget_disabled_button_rose
        AppearanceStore.THEME_RUBY -> if (lock) R.drawable.bg_widget_lock_disabled_button_ruby else R.drawable.bg_widget_disabled_button_ruby
        AppearanceStore.THEME_MIDNIGHT -> if (lock) R.drawable.bg_widget_lock_disabled_button_midnight else R.drawable.bg_widget_disabled_button_midnight
        AppearanceStore.THEME_COPPER -> if (lock) R.drawable.bg_widget_lock_disabled_button_copper else R.drawable.bg_widget_disabled_button_copper
        else -> if (lock) R.drawable.bg_widget_lock_disabled_button_emerald else R.drawable.bg_widget_disabled_button_emerald
    }

    private fun shellBackground(theme: String, compact: Boolean): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> if (compact) R.drawable.bg_widget_compact_shell_ocean else R.drawable.bg_widget_shell_ocean
        AppearanceStore.THEME_VIOLET -> if (compact) R.drawable.bg_widget_compact_shell_violet else R.drawable.bg_widget_shell_violet
        AppearanceStore.THEME_AMBER -> if (compact) R.drawable.bg_widget_compact_shell_amber else R.drawable.bg_widget_shell_amber
        AppearanceStore.THEME_ICE -> if (compact) R.drawable.bg_widget_compact_shell_ice else R.drawable.bg_widget_shell_ice
        AppearanceStore.THEME_AMOLED -> if (compact) R.drawable.bg_widget_compact_shell_amoled else R.drawable.bg_widget_shell_amoled
        AppearanceStore.THEME_GRAPHITE -> if (compact) R.drawable.bg_widget_compact_shell_graphite else R.drawable.bg_widget_shell_graphite
        AppearanceStore.THEME_CYAN -> if (compact) R.drawable.bg_widget_compact_shell_cyan else R.drawable.bg_widget_shell_cyan
        AppearanceStore.THEME_ROSE -> if (compact) R.drawable.bg_widget_compact_shell_rose else R.drawable.bg_widget_shell_rose
        AppearanceStore.THEME_RUBY -> if (compact) R.drawable.bg_widget_compact_shell_ruby else R.drawable.bg_widget_shell_ruby
        AppearanceStore.THEME_MIDNIGHT -> if (compact) R.drawable.bg_widget_compact_shell_midnight else R.drawable.bg_widget_shell_midnight
        AppearanceStore.THEME_COPPER -> if (compact) R.drawable.bg_widget_compact_shell_copper else R.drawable.bg_widget_shell_copper
        else -> if (compact) R.drawable.bg_widget_compact_shell_emerald else R.drawable.bg_widget_shell_emerald
    }

    private fun logoBackground(theme: String): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> R.drawable.bg_widget_logo_ocean
        AppearanceStore.THEME_VIOLET -> R.drawable.bg_widget_logo_violet
        AppearanceStore.THEME_AMBER -> R.drawable.bg_widget_logo_amber
        AppearanceStore.THEME_ICE -> R.drawable.bg_widget_logo_ice
        AppearanceStore.THEME_AMOLED -> R.drawable.bg_widget_logo_amoled
        AppearanceStore.THEME_GRAPHITE -> R.drawable.bg_widget_logo_graphite
        AppearanceStore.THEME_CYAN -> R.drawable.bg_widget_logo_cyan
        AppearanceStore.THEME_ROSE -> R.drawable.bg_widget_logo_rose
        AppearanceStore.THEME_RUBY -> R.drawable.bg_widget_logo_ruby
        AppearanceStore.THEME_MIDNIGHT -> R.drawable.bg_widget_logo_midnight
        AppearanceStore.THEME_COPPER -> R.drawable.bg_widget_logo_copper
        else -> R.drawable.bg_widget_logo_emerald
    }

    private fun dividerBackground(theme: String): Int = when (theme) {
        AppearanceStore.THEME_OCEAN -> R.drawable.bg_widget_divider_ocean
        AppearanceStore.THEME_VIOLET -> R.drawable.bg_widget_divider_violet
        AppearanceStore.THEME_AMBER -> R.drawable.bg_widget_divider_amber
        AppearanceStore.THEME_ICE -> R.drawable.bg_widget_divider_ice
        AppearanceStore.THEME_AMOLED -> R.drawable.bg_widget_divider_amoled
        AppearanceStore.THEME_GRAPHITE -> R.drawable.bg_widget_divider_graphite
        AppearanceStore.THEME_CYAN -> R.drawable.bg_widget_divider_cyan
        AppearanceStore.THEME_ROSE -> R.drawable.bg_widget_divider_rose
        AppearanceStore.THEME_RUBY -> R.drawable.bg_widget_divider_ruby
        AppearanceStore.THEME_MIDNIGHT -> R.drawable.bg_widget_divider_midnight
        AppearanceStore.THEME_COPPER -> R.drawable.bg_widget_divider_copper
        else -> R.drawable.bg_widget_divider_emerald
    }
}
