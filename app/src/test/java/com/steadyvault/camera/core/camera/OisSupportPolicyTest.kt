package com.steadyvault.camera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OisSupportPolicyTest {
    @Test
    fun acceptsLogicalCameraThatAdvertisesOis() {
        val decision = OisSupportPolicy.resolve(
            logicalModes = intArrayOf(0, 1),
            physicalModes = emptyList(),
            logicalRequestAvailable = true,
            physicalOverrideAvailable = false,
            onMode = 1
        )

        assertTrue(decision.supported)
        assertEquals(OisSupportPolicy.Source.LOGICAL_METADATA, decision.source)
    }

    @Test
    fun recoversOisFromPhysicalLensBehindLogicalCamera() {
        val decision = OisSupportPolicy.resolve(
            logicalModes = intArrayOf(0),
            physicalModes = listOf(intArrayOf(0), intArrayOf(0, 1)),
            logicalRequestAvailable = true,
            physicalOverrideAvailable = true,
            onMode = 1
        )

        assertTrue(decision.supported)
        assertEquals(OisSupportPolicy.Source.PHYSICAL_METADATA, decision.source)
    }

    @Test
    fun keepsRequestKeyFallbackTestableWhenMetadataIsMissingOrContradictory() {
        val missing = OisSupportPolicy.resolve(
            logicalModes = null,
            physicalModes = emptyList(),
            logicalRequestAvailable = true,
            physicalOverrideAvailable = false,
            onMode = 1
        )
        val explicitOff = OisSupportPolicy.resolve(
            logicalModes = intArrayOf(0),
            physicalModes = emptyList(),
            logicalRequestAvailable = true,
            physicalOverrideAvailable = false,
            onMode = 1
        )

        assertEquals(OisSupportPolicy.Source.REQUEST_KEY_FALLBACK, missing.source)
        assertTrue(explicitOff.supported)
        assertEquals(OisSupportPolicy.Source.REQUEST_KEY_FALLBACK, explicitOff.source)
    }

}
