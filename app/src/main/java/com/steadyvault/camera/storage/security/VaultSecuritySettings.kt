package com.steadyvault.camera.storage.security

import android.content.Context
import androidx.biometric.BiometricManager
import com.steadyvault.camera.storage.vault.VaultAreaId

object VaultSecuritySettings {
    const val TIMEOUT_IMMEDIATE = VaultSecurityPolicyRules.TIMEOUT_IMMEDIATE
    const val TIMEOUT_15_SECONDS = 15_000L
    const val TIMEOUT_1_MINUTE = 60_000L
    const val TIMEOUT_5_MINUTES = 5 * 60_000L
    const val TIMEOUT_15_MINUTES = 15 * 60_000L
    const val TIMEOUT_NEVER = VaultSecurityPolicyRules.TIMEOUT_NEVER

    private const val PREFS = "steadyvault_vault_security_policy"
    const val BIOMETRIC_TARGET_ASK = "ask"
    const val BIOMETRIC_TARGET_PRIMARY = VaultAreaId.PRIMARY
    const val BIOMETRIC_TARGET_SECONDARY = VaultAreaId.SECONDARY
    const val BIOMETRIC_TARGET_TERTIARY = VaultAreaId.TERTIARY

    private const val KEY_BIOMETRIC = "biometric_unlock"
    private const val KEY_BIOMETRIC_TARGET = "biometric_vault_target"
    private const val KEY_TIMEOUT = "auto_lock_timeout_ms"
    private const val KEY_SCREEN_OFF = "lock_on_screen_off"
    private const val KEY_BACKGROUND_AT = "background_at"

    fun biometricEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled && canUseBiometrics(context)).apply()
    }

    fun biometricVaultTarget(context: Context): String = prefs(context)
        .getString(KEY_BIOMETRIC_TARGET, BIOMETRIC_TARGET_ASK)
        .takeIf { it in setOf(BIOMETRIC_TARGET_ASK, BIOMETRIC_TARGET_PRIMARY, BIOMETRIC_TARGET_SECONDARY, BIOMETRIC_TARGET_TERTIARY) }
        ?: BIOMETRIC_TARGET_ASK

    fun setBiometricVaultTarget(context: Context, target: String) {
        val safe = target.takeIf {
            it in setOf(BIOMETRIC_TARGET_ASK, BIOMETRIC_TARGET_PRIMARY, BIOMETRIC_TARGET_SECONDARY, BIOMETRIC_TARGET_TERTIARY)
        } ?: BIOMETRIC_TARGET_ASK
        prefs(context).edit().putString(KEY_BIOMETRIC_TARGET, safe).apply()
    }

    fun timeoutMs(context: Context): Long = prefs(context).getLong(KEY_TIMEOUT, TIMEOUT_IMMEDIATE)

    fun setTimeoutMs(context: Context, value: Long) {
        val safe = value.takeIf {
            it in setOf(
                TIMEOUT_IMMEDIATE,
                TIMEOUT_15_SECONDS,
                TIMEOUT_1_MINUTE,
                TIMEOUT_5_MINUTES,
                TIMEOUT_15_MINUTES,
                TIMEOUT_NEVER
            )
        } ?: TIMEOUT_IMMEDIATE
        prefs(context).edit().putLong(KEY_TIMEOUT, safe).apply()
    }

    fun lockOnScreenOff(context: Context): Boolean = prefs(context).getBoolean(KEY_SCREEN_OFF, true)

    fun setLockOnScreenOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_OFF, enabled).apply()
    }

    fun markBackground(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_BACKGROUND_AT, now).apply()
    }

    fun clearBackgroundMark(context: Context) {
        prefs(context).edit().remove(KEY_BACKGROUND_AT).apply()
    }

    fun shouldLockOnForeground(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        VaultSecurityPolicyRules.shouldLock(
            timeoutMs = timeoutMs(context),
            backgroundAt = prefs(context).getLong(KEY_BACKGROUND_AT, 0L),
            now = now
        )

    fun canUseBiometrics(context: Context): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
