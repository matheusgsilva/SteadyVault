package com.steadyvault.camera.storage.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSecurityPolicyRulesTest {
    @Test fun immediateLocksAsSoonAsAppLeavesForeground() {
        assertTrue(VaultSecurityPolicyRules.shouldLock(0L, 1_000L, 1_001L))
    }

    @Test fun delayedPolicyRespectsConfiguredWindow() {
        assertFalse(VaultSecurityPolicyRules.shouldLock(60_000L, 1_000L, 60_999L))
        assertTrue(VaultSecurityPolicyRules.shouldLock(60_000L, 1_000L, 61_000L))
    }

    @Test fun neverAndMissingBackgroundMarkDoNotLock() {
        assertFalse(VaultSecurityPolicyRules.shouldLock(-1L, 1_000L, 100_000L))
        assertFalse(VaultSecurityPolicyRules.shouldLock(0L, 0L, 100_000L))
    }
}
