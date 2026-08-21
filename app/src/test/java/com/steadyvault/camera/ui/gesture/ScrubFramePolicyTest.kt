package com.steadyvault.camera.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubFramePolicyTest {
    @Test
    fun invalidFrameRateKeepsRequestedPosition() {
        assertEquals(1_234, ScrubFramePolicy.snapPositionMs(1_234, 10_000, 0f))
        assertNull(ScrubFramePolicy.frameIntervalUs(Float.NaN))
    }

    @Test
    fun snapsToNearestThirtyFpsFrame() {
        assertEquals(67, ScrubFramePolicy.snapPositionMs(60, 10_000, 30f))
        assertEquals(100, ScrubFramePolicy.snapPositionMs(110, 10_000, 30f))
    }

    @Test
    fun snapsToNearestHighFrameRateFrame() {
        assertEquals(25, ScrubFramePolicy.snapPositionMs(24, 10_000, 240f))
        assertEquals(29, ScrubFramePolicy.snapPositionMs(30, 10_000, 240f))
    }

    @Test
    fun endOfVideoUsesLastDisplayableFrame() {
        assertEquals(967, ScrubFramePolicy.snapPositionMs(1_000, 1_000, 30f))
    }

    @Test
    fun unknownDurationStillSnapsWithoutClamping() {
        assertEquals(1_233, ScrubFramePolicy.snapPositionMs(1_234, 0, 30f))
    }
}
