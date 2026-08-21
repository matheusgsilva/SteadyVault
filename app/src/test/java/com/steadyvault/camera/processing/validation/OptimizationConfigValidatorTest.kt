package com.steadyvault.camera.processing.validation

import com.steadyvault.camera.processing.model.FilterStrength
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.VideoFilterConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationConfigValidatorTest {
    @Test
    fun defaultSdrConfigurationIsValid() {
        val result = OptimizationConfigValidator.validate(
            config = OptimizationConfig(),
            sourceWidth = 3840,
            sourceHeight = 2160,
            sourceFps = 60,
            sourceHdrHlg10 = false
        )
        assertTrue(result.valid)
    }

    @Test
    fun visualFiltersAreBlockedForHlg10() {
        val result = OptimizationConfigValidator.validate(
            config = OptimizationConfig(
                filters = VideoFilterConfig(sharpen = FilterStrength.LIGHT)
            ),
            sourceWidth = 3840,
            sourceHeight = 2160,
            sourceFps = 60,
            sourceHdrHlg10 = true
        )
        assertFalse(result.valid)
    }

    @Test
    fun dangerouslyLowBitrateIsBlocked() {
        val result = OptimizationConfigValidator.validate(
            config = OptimizationConfig(bitrateMbps = 2),
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceFps = 60,
            sourceHdrHlg10 = false
        )
        assertFalse(result.valid)
    }
}
