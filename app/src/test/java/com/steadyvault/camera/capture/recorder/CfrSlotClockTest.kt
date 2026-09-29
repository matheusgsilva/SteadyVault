package com.steadyvault.camera.capture.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfrSlotClockTest {
    private val interval = 1_000_000_000L / 60L

    @Test
    fun stableCadenceKeepsEveryFrameWithoutFill() {
        val clock = CfrSlotClock(interval)
        clock.start(0L)
        for (slot in 1L..10L) {
            val decision = clock.next(slot * interval)
            assertFalse(decision.drop)
            assertEquals(0, decision.missingSlots)
        }
    }

    @Test
    fun pairedJitterDoesNotCreateFalseFillOrDrift() {
        val clock = CfrSlotClock(interval)
        clock.start(0L)

        val first = clock.next((1.6 * interval).toLong())
        val second = clock.next((2.0 * interval).toLong())
        val third = clock.next((3.0 * interval).toLong())

        assertFalse(first.drop)
        assertEquals(0, first.missingSlots)
        assertFalse(second.drop)
        assertEquals(0, second.missingSlots)
        assertFalse(third.drop)
        assertEquals(0, third.missingSlots)
    }

    @Test
    fun realTwoSlotGapCreatesExactlyOneSyntheticSlot() {
        val clock = CfrSlotClock(interval)
        clock.start(0L)
        val decision = clock.next(interval * 2L)

        assertFalse(decision.drop)
        assertEquals(1, decision.missingSlots)
    }

    @Test
    fun sourceRunningAheadDropsExcessFrame() {
        val clock = CfrSlotClock(interval)
        clock.start(0L)

        val first = clock.next(interval / 2L)
        assertTrue(first.drop)
        assertEquals(0, first.missingSlots)
    }
}
