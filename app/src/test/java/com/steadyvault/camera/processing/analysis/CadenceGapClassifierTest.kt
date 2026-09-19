package com.steadyvault.camera.processing.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceGapClassifierTest {
    private val frame60Us = 16_667L

    @Test
    fun compensatedLongShortPairIsJitterNotMissingFrame() {
        val result = CadenceGapClassifier.classify(
            listOf(16_700L, 27_000L, 6_300L, 16_650L),
            frame60Us
        )

        assertEquals(0, result.largeGapCount)
        assertEquals(0, result.estimatedMissingFrames)
        assertEquals(1, result.compensatedJitterCount)
    }

    @Test
    fun realThirtyThreeMillisecondGapStillCountsOneMissingFrame() {
        val result = CadenceGapClassifier.classify(
            listOf(16_600L, 33_400L, 16_700L),
            frame60Us
        )

        assertEquals(1, result.largeGapCount)
        assertEquals(1, result.estimatedMissingFrames)
    }

    @Test
    fun multiFrameGapKeepsRealLoss() {
        val result = CadenceGapClassifier.classify(
            listOf(16_700L, 50_100L, 16_600L),
            frame60Us
        )

        assertEquals(1, result.largeGapCount)
        assertEquals(2, result.estimatedMissingFrames)
    }
}
