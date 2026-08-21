package com.steadyvault.camera.storage.security

import android.content.Context

object ProtectedAppsStore {
    private const val PREFS = "steadyvault_protected_apps"
    private const val KEY_PACKAGES = "selected_launcher_packages"
    private const val KEY_BIOMETRIC = "biometric_unlock"

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet().orEmpty()

    fun setSelected(context: Context, packageName: String, selected: Boolean) {
        val packages = selectedPackages(context).toMutableSet()
        if (selected) packages += packageName else packages -= packageName
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    fun biometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
