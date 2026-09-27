package com.steadyvault.camera.capture.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTrackerTest {

    @Test
    fun detectsIndividualGapAt60Fps() {
        val tracker = FrameCadenceTracker(targetFps = 60, summaryEveryIntervals = 10)

        tracker.observe(1_000_000_000L)
        val normal = tracker.observe(1_016_666_666L)
        val gap = tracker.observe(1_050_000_000L)

        assertFalse(normal.gapDetected)
        assertTrue(gap.gapDetected)
        assertEquals(1, gap.estimatedMissingFrames)
        assertEquals(1L, gap.gapCount)
    }

    @Test
    fun reportsMeasuredCadenceWithoutReplacingFrames() {
        val tracker = FrameCadenceTracker(targetFps = 60, summaryEveryIntervals = 3)
        var timestamp = 0L

        tracker.observe(timestamp)
        repeat(3) {
            timestamp += 16_666_667L
            tracker.observe(timestamp)
        }

        val observation = tracker.observe(timestamp + 16_666_667L)
        assertTrue((observation.measuredFps ?: 0.0) > 59.9)
        assertEquals(0L, observation.gapCount)
    }

    @Test
    fun flagsNonMonotonicTimestampSeparately() {
        val tracker = FrameCadenceTracker(targetFps = 60)

        tracker.observe(100L)
        val observation = tracker.observe(90L)

        assertTrue(observation.nonMonotonic)
        assertFalse(observation.gapDetected)
    }
}
