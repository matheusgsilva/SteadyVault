package com.steadyvault.camera.storage.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Separate PIN/session for the tertiary private vault. */
object TertiaryVaultLock {
    private const val PREFS = "steadyvault_tertiary_vault_security"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    @Volatile private var sessionUnlocked = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("hash")

    fun isUnlocked(context: Context): Boolean = isEnabled(context) && sessionUnlocked

    fun lock() { sessionUnlocked = false }
    fun unlockSession() { sessionUnlocked = true }

    fun setPin(context: Context, pin: String) {
        require(pin.length in 4..12 && pin.all(Char::isDigit)) { "Use de 4 a 12 números" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        sessionUnlocked = true
    }

    fun matches(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val actual = derive(pin, salt)
        var diff = expected.size xor actual.size
        for (i in 0 until minOf(expected.size, actual.size)) {
            diff = diff or (expected[i].toInt() xor actual[i].toInt())
        }
        return diff == 0
    }

    fun verify(context: Context, pin: String): Boolean {
        if (!PinAttemptLimiter.canAttempt(context, ATTEMPT_SCOPE)) return false
        val ok = matches(context, pin)
        PinAttemptLimiter.record(context, ATTEMPT_SCOPE, ok)
        if (ok) sessionUnlocked = true
        return ok
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        sessionUnlocked = false
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val ATTEMPT_SCOPE = "tertiary_vault"
}
