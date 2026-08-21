package com.steadyvault.camera.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun advancesFromNativePlayerToCompatibilityFallbacks() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_PERFORMANCE, policy.stage)
        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_COMPATIBILITY, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.VLC_HARDWARE, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.VLC_SOFTWARE, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.EXHAUSTED, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.EXHAUSTED, policy.advanceAfterFailure())
    }

    @Test
    fun skipsVlcSoftwareForDemandingFourKPlayback() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_COMPATIBILITY, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.VLC_HARDWARE, policy.advanceAfterFailure())
        assertEquals(
            PlaybackRecoveryPolicy.Stage.EXHAUSTED,
            policy.advanceAfterFailure(skipVlcSoftware = true)
        )
    }

    @Test
    fun media3PreferenceStillFallsBackToVlcHardware() {
        val policy = PlaybackRecoveryPolicy()
        policy.startAtMedia3()

        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_PERFORMANCE, policy.stage)
        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_COMPATIBILITY, policy.advanceAfterFailure())
        assertEquals(PlaybackRecoveryPolicy.Stage.VLC_HARDWARE, policy.advanceAfterFailure())
    }

    @Test
    fun resetReturnsReplayToMedia3Performance() {
        val policy = PlaybackRecoveryPolicy()
        repeat(4) { policy.advanceAfterFailure() }

        policy.reset()

        assertEquals(PlaybackRecoveryPolicy.Stage.MEDIA3_PERFORMANCE, policy.stage)
    }
}
