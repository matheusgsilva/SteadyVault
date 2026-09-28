package com.steadyvault.camera.capture.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfrInterpolationPlannerTest {
    @Test
    fun oneMissingFrameAt60FpsCreatesOnlyHalfwaySyntheticFrame() {
        val interval = 1_000_000_000L / 60L
        val steps = CfrInterpolationPlanner.sourceSteps(interval * 2L, interval)
        val alphas = CfrInterpolationPlanner.interpolationAlphas(steps)

        assertEquals(2, steps)
        assertEquals(1, alphas.size)
        assertEquals(0.5f, alphas[0], 0.0001f)
    }

    @Test
    fun threeMissingFramesProduceDistinctSyntheticPositions() {
        val interval = 1_000_000_000L / 60L
        val steps = CfrInterpolationPlanner.sourceSteps(interval * 4L, interval)
        val alphas = CfrInterpolationPlanner.interpolationAlphas(steps)

        assertEquals(4, steps)
        assertEquals(listOf(0.25f, 0.5f, 0.75f), alphas.toList())
        assertTrue(alphas.all { it > 0f && it < 1f })
        assertEquals(alphas.size, alphas.toSet().size)
    }

    @Test
    fun stableCadenceDoesNotCreateSyntheticFrames() {
        val interval = 1_000_000_000L / 60L
        val steps = CfrInterpolationPlanner.sourceSteps(interval, interval)

        assertEquals(1, steps)
        assertTrue(CfrInterpolationPlanner.interpolationAlphas(steps).isEmpty())
    }

    @Test
    fun duplicateOrRegressiveSourceTimestampProducesNoOutputStep() {
        val interval = 1_000_000_000L / 60L

        assertEquals(0, CfrInterpolationPlanner.sourceSteps(0L, interval))
        assertEquals(0, CfrInterpolationPlanner.sourceSteps(-1L, interval))
    }

    @Test
    fun realtimeMotionIsUsedOnlyForShortReliableGaps() {
        assertTrue(CfrInterpolationPlanner.useRealtimeMotionInterpolation(2))
        assertTrue(CfrInterpolationPlanner.useRealtimeMotionInterpolation(4))
        assertTrue(!CfrInterpolationPlanner.useRealtimeMotionInterpolation(5))
        assertTrue(!CfrInterpolationPlanner.useRealtimeMotionInterpolation(1))
    }
}
