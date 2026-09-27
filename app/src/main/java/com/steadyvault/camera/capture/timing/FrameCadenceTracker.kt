package com.steadyvault.camera.capture.timing

import kotlin.math.roundToInt

class FrameCadenceTracker(
    targetFps: Int,
    private val summaryEveryIntervals: Int = targetFps.coerceAtLeast(1) * 2
) {
    data class Observation(
        val frameNumber: Long,
        val deltaNs: Long?,
        val gapDetected: Boolean,
        val estimatedMissingFrames: Int,
        val intervalCount: Long,
        val gapCount: Long,
        val measuredFps: Double?,
        val worstDeltaNs: Long,
        val summaryDue: Boolean,
        val nonMonotonic: Boolean
    )

    val nominalDeltaNs: Long = 1_000_000_000L / targetFps.coerceAtLeast(1)
    val gapThresholdNs: Long = nominalDeltaNs * 3L / 2L

    private var previousTimestampNs = Long.MIN_VALUE
    private var frameCount = 0L
    private var intervalCount = 0L
    private var gapCount = 0L
    private var totalDeltaNs = 0L
    private var worstDeltaNs = 0L

    fun observe(timestampNs: Long): Observation {
        frameCount++

        if (previousTimestampNs == Long.MIN_VALUE) {
            previousTimestampNs = timestampNs
            return snapshot(deltaNs = null, gapDetected = false, estimatedMissingFrames = 0, nonMonotonic = false)
        }

        val deltaNs = timestampNs - previousTimestampNs
        previousTimestampNs = timestampNs

        if (deltaNs <= 0L) {
            return snapshot(
                deltaNs = deltaNs,
                gapDetected = false,
                estimatedMissingFrames = 0,
                nonMonotonic = true
            )
        }

        intervalCount++
        totalDeltaNs += deltaNs
        if (deltaNs > worstDeltaNs) worstDeltaNs = deltaNs

        val gapDetected = deltaNs > gapThresholdNs
        val missing = if (gapDetected) {
            (deltaNs.toDouble() / nominalDeltaNs.toDouble()).roundToInt().minus(1).coerceAtLeast(1)
        } else {
            0
        }
        if (gapDetected) gapCount++

        return snapshot(
            deltaNs = deltaNs,
            gapDetected = gapDetected,
            estimatedMissingFrames = missing,
            nonMonotonic = false
        )
    }

    private fun snapshot(
        deltaNs: Long?,
        gapDetected: Boolean,
        estimatedMissingFrames: Int,
        nonMonotonic: Boolean
    ): Observation {
        val fps = if (intervalCount > 0L && totalDeltaNs > 0L) {
            intervalCount.toDouble() * 1_000_000_000.0 / totalDeltaNs.toDouble()
        } else {
            null
        }
        return Observation(
            frameNumber = frameCount,
            deltaNs = deltaNs,
            gapDetected = gapDetected,
            estimatedMissingFrames = estimatedMissingFrames,
            intervalCount = intervalCount,
            gapCount = gapCount,
            measuredFps = fps,
            worstDeltaNs = worstDeltaNs,
            summaryDue = intervalCount > 0L &&
                summaryEveryIntervals > 0 &&
                intervalCount % summaryEveryIntervals.toLong() == 0L,
            nonMonotonic = nonMonotonic
        )
    }
}
