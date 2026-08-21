package com.steadyvault.camera.storage.vault

import android.content.Context

/** Preferências leves de cache das galerias, separadas dos perfis da câmera. */
object VaultMediaCacheSettings {
    const val DETAILS_ON_MENU_OPEN = "menu_open"
    const val DETAILS_ON_DEMAND = "details_tap"

    private const val PREFS = "steadyvault_media_cache_settings"
    private const val KEY_DETAILS_LOADING_MODE = "details_loading_mode"

    val detailsLoadingModes = listOf(DETAILS_ON_MENU_OPEN, DETAILS_ON_DEMAND)

    fun detailsLoadingMode(context: Context): String =
        preferences(context).getString(KEY_DETAILS_LOADING_MODE, DETAILS_ON_MENU_OPEN)
            ?.takeIf { it in detailsLoadingModes }
            ?: DETAILS_ON_MENU_OPEN

    fun setDetailsLoadingMode(context: Context, mode: String) {
        preferences(context).edit()
            .putString(KEY_DETAILS_LOADING_MODE, mode.takeIf { it in detailsLoadingModes } ?: DETAILS_ON_MENU_OPEN)
            .apply()
    }

    fun shouldPrefetchDetailsOnMenuOpen(context: Context): Boolean =
        detailsLoadingMode(context) == DETAILS_ON_MENU_OPEN

    fun restoreDefaults(context: Context) {
        preferences(context).edit().remove(KEY_DETAILS_LOADING_MODE).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
