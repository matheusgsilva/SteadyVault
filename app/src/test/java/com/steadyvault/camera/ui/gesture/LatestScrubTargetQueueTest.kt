package com.steadyvault.camera.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestScrubTargetQueueTest {
    @Test
    fun continuousOffersKeepOnlyLatestTargetAndOneSchedule() {
        val queue = LatestScrubTargetQueue()

        assertTrue(queue.offer(1_000L))
        assertTrue(queue.hasPending())
        assertFalse(queue.offer(1_033L))
        assertFalse(queue.offer(1_067L))
        assertEquals(1_067L, queue.consume())
        assertFalse(queue.hasPending())
    }

    @Test
    fun consumeAllowsNextDisplayFrameAndClearCancelsPendingTarget() {
        val queue = LatestScrubTargetQueue()

        assertTrue(queue.offer(2_000L))
        assertEquals(2_000L, queue.consume())
        assertTrue(queue.offer(2_033L))
        queue.clear()
        assertFalse(queue.hasPending())
        assertEquals(LatestScrubTargetQueue.NO_TARGET, queue.consume())
    }
}
