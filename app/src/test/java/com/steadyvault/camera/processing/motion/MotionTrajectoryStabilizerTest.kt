package com.steadyvault.camera.processing.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionTrajectoryStabilizerTest {
    @Test
    fun steadyPanDoesNotTriggerRepair() {
        val stabilizer = MotionTrajectoryStabilizer()
        repeat(10) {
            val correction = stabilizer.update(
                motionXUv = 0.010f,
                motionYUv = 0.001f,
                rotationRad = 0f,
                reliability = 0.9f,
                sceneChange = false,
                unstable = false,
                nearlyStatic = false
            )
            assertFalse(correction.jankDetected)
        }
    }

    @Test
    fun oneFrameSpikeIsDetectedAndBounded() {
        val stabilizer = MotionTrajectoryStabilizer()
        repeat(5) {
            stabilizer.update(0.010f, 0f, 0f, 0.9f, false, false, false)
        }

        val spike = stabilizer.update(0.026f, 0f, 0f, 0.9f, false, false, false)

        assertTrue(spike.jankDetected)
        assertTrue(spike.xUv < 0f)
        assertTrue(abs(spike.xUv) <= 0.0141f)
        assertTrue(spike.zoom in 1f..1.0451f)
    }

    @Test
    fun correctionDecaysAfterSpike() {
        val stabilizer = MotionTrajectoryStabilizer()
        repeat(5) {
            stabilizer.update(0.010f, 0f, 0f, 0.9f, false, false, false)
        }
        val spike = stabilizer.update(0.026f, 0f, 0f, 0.9f, false, false, false)
        val recovered = stabilizer.update(0.010f, 0f, 0f, 0.9f, false, false, false)

        assertTrue(abs(recovered.xUv) < abs(spike.xUv))
    }

    @Test
    fun sceneCutResetsTrajectory() {
        val stabilizer = MotionTrajectoryStabilizer()
        repeat(5) {
            stabilizer.update(0.010f, 0f, 0f, 0.9f, false, false, false)
        }
        stabilizer.update(0.030f, 0f, 0f, 0.9f, false, false, false)

        val reset = stabilizer.update(0.040f, 0.020f, 0.010f, 0.9f, true, false, false)

        assertTrue(reset.xUv == 0f)
        assertTrue(reset.yUv == 0f)
        assertTrue(reset.rotationRad == 0f)
    }

    @Test
    fun lowConfidenceNeverForcesNewJankCorrection() {
        val stabilizer = MotionTrajectoryStabilizer()
        repeat(5) {
            stabilizer.update(0.010f, 0f, 0f, 0.9f, false, false, false)
        }

        val lowConfidence = stabilizer.update(0.060f, 0f, 0f, 0.15f, false, false, false)

        assertFalse(lowConfidence.jankDetected)
    }
}
