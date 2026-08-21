package com.steadyvault.camera.capture.timing

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStopPolicyTest {
    @Test
    fun preservesThreeFrameTailWithoutLongStopDelay() {
        assertEquals(100L, RecordingStopPolicy.tailDrainMs(30))
        assertEquals(50L, RecordingStopPolicy.tailDrainMs(60))
        assertEquals(25L, RecordingStopPolicy.tailDrainMs(120))
        assertEquals(20L, RecordingStopPolicy.tailDrainMs(240))
    }
}
