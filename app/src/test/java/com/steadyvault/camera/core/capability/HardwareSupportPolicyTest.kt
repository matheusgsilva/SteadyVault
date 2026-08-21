package com.steadyvault.camera.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareSupportPolicyTest {
    @Test
    fun advertisedModeIsSupported() {
        assertEquals(
            HardwareSupportPolicy.Support.SUPPORTED,
            HardwareSupportPolicy.modeSupport(setOf(0, 1), true, 1, true)
        )
    }

    @Test
    fun missingMetadataWithRequestKeyIsNotAFalseNegative() {
        val support = HardwareSupportPolicy.modeSupport(emptySet(), false, 1, true)
        assertEquals(HardwareSupportPolicy.Support.UNVERIFIED, support)
        assertTrue(HardwareSupportPolicy.shouldExpose(support, scanCompleted = true))
        assertTrue(HardwareSupportPolicy.isSelectable(support, scanCompleted = true, hasSnapshot = true))
    }

    @Test
    fun explicitNegativeIsHiddenAfterScan() {
        val support = HardwareSupportPolicy.modeSupport(setOf(0), true, 1, true)
        assertEquals(HardwareSupportPolicy.Support.UNSUPPORTED, support)
        assertFalse(HardwareSupportPolicy.shouldExpose(support, scanCompleted = true))
    }
}
