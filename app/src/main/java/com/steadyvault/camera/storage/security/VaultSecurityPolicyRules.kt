package com.steadyvault.camera.storage.security

object VaultSecurityPolicyRules {
    const val TIMEOUT_IMMEDIATE = 0L
    const val TIMEOUT_NEVER = -1L

    fun shouldLock(timeoutMs: Long, backgroundAt: Long, now: Long): Boolean {
        if (timeoutMs == TIMEOUT_NEVER || backgroundAt <= 0L) return false
        if (timeoutMs == TIMEOUT_IMMEDIATE) return true
        return now - backgroundAt >= timeoutMs
    }
}
