package com.steadyvault.camera.capture.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupVideoGateTest {
    @Test
    fun firstCommittedKeyFrameStartsImmediatelyWithoutBlindFrameDiscard() {
        val gate = gate()

        val decision = gate.onSample(
            isKeyFrame = true,
            clearlyBeforeCommit = false,
            sourcePtsUs = 1_000_000L,
            bufferedBytes = 0L,
            hasBufferedKeyFrame = false
        )

        assertEquals(StartupVideoGate.Action.START_WITH_CURRENT_KEY_FRAME, decision.action)
        assertFalse(decision.requestSyncFrame)
    }

    @Test
    fun preCommitGopIsHeldAndNewCommittedIdrReplacesIt() {
        val gate = gate()

        assertEquals(
            StartupVideoGate.Action.HOLD,
            gate.onSample(true, true, 900_000L, 2_366L, true).action
        )
        assertEquals(
            StartupVideoGate.Action.HOLD,
            gate.onSample(false, false, 916_667L, 2_621L, true).action
        )

        val accepted = gate.onSample(true, false, 933_334L, 2_621L, true)
        assertEquals(StartupVideoGate.Action.START_WITH_CURRENT_KEY_FRAME, accepted.action)
    }

    @Test
    fun boundedFallbackPublishesValidBufferedGopInsteadOfCancelling() {
        val gate = StartupVideoGate(
            maxFramesBeforeFallback = 3,
            maxWaitBeforeFallbackUs = 250_000L,
            maxBufferedBytes = 1_000_000L
        )

        gate.onSample(true, true, 900_000L, 2_366L, true)
        gate.onSample(false, false, 916_667L, 2_621L, true)
        val fallback = gate.onSample(false, false, 933_334L, 2_876L, true)

        assertEquals(StartupVideoGate.Action.START_WITH_BUFFERED_GOP, fallback.action)
        assertFalse(fallback.requestSyncFrame)
        assertEquals(
            StartupVideoGate.Action.WRITE_CURRENT,
            gate.onSample(false, false, 950_001L, 0L, false).action
        )
    }

    @Test
    fun missingKeyFrameRetriesSyncRequestWithoutOpeningOnPFrame() {
        val gate = StartupVideoGate(
            maxFramesBeforeFallback = 2,
            maxWaitBeforeFallbackUs = 250_000L,
            maxBufferedBytes = 1_000_000L
        )

        gate.onSample(false, false, 1_000_000L, 0L, false)
        val retry = gate.onSample(false, false, 1_016_667L, 0L, false)

        assertEquals(StartupVideoGate.Action.HOLD, retry.action)
        assertTrue(retry.requestSyncFrame)
    }

    @Test
    fun stopUsesBufferedIdrFallbackButNeverInventsOne() {
        val withIdr = gate().forceFallback(hasBufferedKeyFrame = true)
        assertEquals(StartupVideoGate.Action.START_WITH_BUFFERED_GOP, withIdr.action)

        val withoutIdr = gate().forceFallback(hasBufferedKeyFrame = false)
        assertEquals(StartupVideoGate.Action.HOLD, withoutIdr.action)
        assertTrue(withoutIdr.requestSyncFrame)
    }

    private fun gate() = StartupVideoGate(
        maxFramesBeforeFallback = 15,
        maxWaitBeforeFallbackUs = 250_000L,
        maxBufferedBytes = 24L * 1024L * 1024L
    )
}
