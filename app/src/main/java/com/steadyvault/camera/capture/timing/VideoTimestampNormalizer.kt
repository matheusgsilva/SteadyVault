package com.steadyvault.camera.capture.timing

import kotlin.math.roundToLong

/**
 * Preserves the source timeline. It only repairs timestamps that would be illegal
 * for an MP4 track because they are duplicated or regress.
 */
class VideoTimestampNormalizer(private val fps: Int) {
    private var lastPtsUs = Long.MIN_VALUE

    fun normalize(rawPtsUs: Long): Long {
        val normalized = if (lastPtsUs == Long.MIN_VALUE || rawPtsUs > lastPtsUs) {
            rawPtsUs
        } else {
            lastPtsUs + 1L
        }
        lastPtsUs = normalized
        return normalized
    }

    fun reset() {
        lastPtsUs = Long.MIN_VALUE
    }

    fun frameIntervalUs(): Long =
        (1_000_000.0 / fps.coerceAtLeast(1).toDouble()).roundToLong()
}
