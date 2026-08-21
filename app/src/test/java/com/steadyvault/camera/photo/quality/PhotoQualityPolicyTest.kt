package com.steadyvault.camera.photo.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQualityPolicyTest {
    @Test
    fun singlePhotoSelectsHighestFourByThreeResolutionInsideLimit() {
        val selected = PhotoQualityPolicy.selectHighestResolution(
            sizes = listOf(
                PhotoQualityPolicy.Dimensions(4000, 3000),
                PhotoQualityPolicy.Dimensions(8160, 6120),
                PhotoQualityPolicy.Dimensions(16320, 12240),
                PhotoQualityPolicy.Dimensions(7680, 4320)
            ),
            maxPixels = 64_000_000L
        )

        assertEquals(PhotoQualityPolicy.Dimensions(8160, 6120), selected)
    }

    @Test
    fun sequenceModeCapKeepsReliableTwelveMegapixelOutput() {
        val selected = PhotoQualityPolicy.selectHighestResolution(
            sizes = listOf(
                PhotoQualityPolicy.Dimensions(4000, 3000),
                PhotoQualityPolicy.Dimensions(8160, 6120)
            ),
            maxPixels = 16_000_000L
        )

        assertEquals(PhotoQualityPolicy.Dimensions(4000, 3000), selected)
    }

    @Test
    fun fallsBackToClosestAvailableSizeWhenAllOutputsExceedLimit() {
        val selected = PhotoQualityPolicy.selectHighestResolution(
            sizes = listOf(
                PhotoQualityPolicy.Dimensions(9000, 6750),
                PhotoQualityPolicy.Dimensions(12000, 9000)
            ),
            maxPixels = 50_000_000L
        )

        assertEquals(PhotoQualityPolicy.Dimensions(9000, 6750), selected)
        assertTrue(selected!!.pixels > 50_000_000L)
    }
}
