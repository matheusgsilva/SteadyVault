package com.steadyvault.camera.capture.timing

object RecordingStopPolicy {
    fun tailDrainMs(fps: Int): Long {
        val safeFps = fps.coerceAtLeast(1)
        return maxOf(20L, 3_000L / safeFps.toLong())
    }
}
