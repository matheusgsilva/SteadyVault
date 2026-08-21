package com.steadyvault.camera.core.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePhaseTest {
    @Test
    fun onlyTerminalPhasesReleaseCaptureControls() {
        assertTrue(CapturePhase.REQUESTED.busy)
        assertTrue(CapturePhase.WAITING_CAMERA.busy)
        assertTrue(CapturePhase.PREPARING.busy)
        assertTrue(CapturePhase.RECORDING.busy)
        assertTrue(CapturePhase.RECOVERING.busy)
        assertTrue(CapturePhase.FINALIZING.busy)
        assertFalse(CapturePhase.IDLE.busy)
        assertFalse(CapturePhase.FAILED.busy)
    }

    @Test
    fun finalizingFlagIsSpecific() {
        assertTrue(CapturePhase.FINALIZING.finalizing)
        assertFalse(CapturePhase.RECORDING.finalizing)
        assertFalse(CapturePhase.RECOVERING.finalizing)
    }
}
