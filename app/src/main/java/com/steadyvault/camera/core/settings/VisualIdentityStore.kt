package com.steadyvault.camera.core.settings

import android.content.Context
import com.steadyvault.camera.R

object VisualIdentityStore {
    private const val PREFS = "steadyvault_visual_identity"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_LABEL = "custom_label"
    private const val KEY_NEUTRAL_ACTIONS = "neutral_actions"
    private const val KEY_NEUTRAL_WIDGET_ACTIONS = "neutral_widget_actions"

    const val MODE_ORIGINAL = "original"
    const val MODE_FILES = "files"
    const val MODE_UTILITY = "utility"
    const val MODE_NOTES = "notes"
    const val MODE_CAMERA = "camera"

    val modeValues = listOf(MODE_ORIGINAL, MODE_FILES, MODE_UTILITY, MODE_NOTES, MODE_CAMERA)

    data class NotificationIdentity(val smallIcon: Int, val cancelIcon: Int)

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_MODE, MODE_ORIGINAL).orEmpty().takeIf { it in modeValues } ?: MODE_ORIGINAL

    fun setMode(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, value.takeIf { it in modeValues } ?: MODE_ORIGINAL)
            .apply()
    }

    fun customLabel(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CUSTOM_LABEL, "").orEmpty().trim()

    fun setCustomLabel(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_LABEL, value.trim().replace(Regex("\\s+"), " ").take(32))
            .apply()
    }

    fun neutralActions(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_NEUTRAL_ACTIONS, false)

    fun setNeutralActions(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NEUTRAL_ACTIONS, value).apply()
    }

    fun neutralWidgetActions(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_NEUTRAL_WIDGET_ACTIONS, false)

    fun setNeutralWidgetActions(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NEUTRAL_WIDGET_ACTIONS, value).apply()
    }

    fun modeLabel(value: String): String = when (value) {
        MODE_FILES -> "Arquivos"
        MODE_UTILITY -> "Utilitário"
        MODE_NOTES -> "Notas"
        MODE_CAMERA -> "Câmera"
        else -> "SteadyVault"
    }

    fun notificationTitle(context: Context, activity: String): String {
        val custom = customLabel(context)
        if (custom.isNotBlank()) return custom
        return when (mode(context)) {
            MODE_FILES -> "Arquivos"
            MODE_UTILITY -> "Serviço"
            MODE_NOTES -> "Notas"
            MODE_CAMERA -> "Câmera"
            else -> if (activity.isBlank()) "SteadyVault" else "SteadyVault • ${activity.lowercase()}"
        }
    }

    fun notificationText(context: Context, original: String, neutral: String = "Atividade em andamento"): String =
        if (mode(context) == MODE_ORIGINAL) original else neutral

    fun actionLabel(context: Context, original: String, neutral: String): String =
        if (neutralActions(context)) neutral else original

    fun notificationIdentity(context: Context): NotificationIdentity = NotificationIdentity(
        smallIcon = when (mode(context)) {
            MODE_FILES -> R.drawable.ic_stat_files
            MODE_UTILITY -> R.drawable.ic_stat_utility
            MODE_NOTES -> R.drawable.ic_stat_notes
            MODE_CAMERA -> R.drawable.ic_stat_camera
            else -> R.drawable.ic_stat_vault
        },
        cancelIcon = R.drawable.ic_stat_cancel
    )

    fun widgetLogo(context: Context): Int = when (mode(context)) {
        MODE_FILES -> R.drawable.ic_identity_files
        MODE_UTILITY -> R.drawable.ic_stat_utility
        MODE_NOTES -> R.drawable.ic_stat_notes
        MODE_CAMERA -> R.drawable.ic_stat_camera
        else -> R.drawable.widget_logo
    }

    fun summary(context: Context): String {
        val parts = mutableListOf(customLabel(context).ifBlank { modeLabel(mode(context)) })
        if (neutralActions(context)) parts += "rótulos neutros"
        if (neutralWidgetActions(context)) parts += "ícones neutros"
        return parts.joinToString(" • ")
    }
}
