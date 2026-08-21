package com.steadyvault.camera.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubSeekPolicyTest {
    @Test
    fun smallFrameMovementIsExactAtBeginningAndMiddle() {
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseMode(1_000L, 1_100L, 16L, 30f)
        )
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseMode(31_000L, 31_100L, 16L, 30f)
        )
    }

    @Test
    fun largeRapidMovementUsesSynchronizedFrame() {
        assertEquals(
            ScrubSeekPolicy.Mode.FAST_SYNC,
            ScrubSeekPolicy.chooseMode(12_000L, 12_600L, 16L, 30f)
        )
    }

    @Test
    fun largeButDeliberateMovementKeepsExactFrame() {
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseMode(12_000L, 12_600L, 400L, 30f)
        )
    }

    @Test
    fun frictionReducedMovementKeepsExactFrame() {
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseMode(31_000L, 31_075L, 16L, 30f)
        )
    }

    @Test
    fun thresholdDoesNotDependOnTimelinePosition() {
        val beginning = ScrubSeekPolicy.chooseMode(1_000L, 1_600L, 16L, 30f)
        val middle = ScrubSeekPolicy.chooseMode(301_000L, 301_600L, 16L, 30f)
        assertEquals(beginning, middle)
        assertEquals(ScrubSeekPolicy.Mode.FAST_SYNC, middle)
    }

    @Test
    fun distantCommitUsesFastFrameOnlyWhenPlaybackWillResume() {
        assertEquals(
            ScrubSeekPolicy.Mode.FAST_SYNC,
            ScrubSeekPolicy.chooseCommitMode(1_000L, 20_000L, resumePlayback = true)
        )
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseCommitMode(1_000L, 20_000L, resumePlayback = false)
        )
        assertEquals(
            ScrubSeekPolicy.Mode.EXACT,
            ScrubSeekPolicy.chooseCommitMode(1_000L, 1_100L, resumePlayback = true)
        )
    }
}
