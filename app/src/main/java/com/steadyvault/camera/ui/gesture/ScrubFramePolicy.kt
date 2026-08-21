package com.steadyvault.camera.ui.gesture

import kotlin.math.roundToLong

/** Converte a posição contínua da barra no instante do quadro de vídeo mais próximo. */
object ScrubFramePolicy {
    private const val MICROSECONDS_PER_MILLISECOND = 1_000L
    private const val MICROSECONDS_PER_SECOND = 1_000_000.0
    private const val MIN_FRAME_RATE = 1f
    private const val MAX_FRAME_RATE = 1_000f

    fun snapPositionMs(positionMs: Int, durationMs: Int, frameRate: Float): Int {
        val positivePosition = positionMs.coerceAtLeast(0)
        val boundedPosition = if (durationMs > 0) positivePosition.coerceAtMost(durationMs) else positivePosition
        val frameIntervalUs = frameIntervalUs(frameRate) ?: return boundedPosition
        val requestedUs = boundedPosition.toLong() * MICROSECONDS_PER_MILLISECOND
        if (durationMs <= 0) return microsecondsToMilliseconds(nearestFrameUs(requestedUs, frameIntervalUs))
        if (durationMs == 1) return boundedPosition

        val lastDisplayableUs = (durationMs.toLong() - 1L) * MICROSECONDS_PER_MILLISECOND
        val lastFrameIndex = lastDisplayableUs / frameIntervalUs
        val requestedFrameIndex = frameIndexAt(requestedUs, frameIntervalUs)
            .coerceIn(0L, lastFrameIndex)
        val snappedUs = requestedFrameIndex * frameIntervalUs
        return microsecondsToMilliseconds(snappedUs)
            .coerceIn(0, durationMs)
    }

    private fun nearestFrameUs(positionUs: Long, frameIntervalUs: Long): Long =
        frameIndexAt(positionUs, frameIntervalUs) * frameIntervalUs

    private fun frameIndexAt(positionUs: Long, frameIntervalUs: Long): Long =
        (positionUs + frameIntervalUs / 2L) / frameIntervalUs

    private fun microsecondsToMilliseconds(positionUs: Long): Int =
        ((positionUs + MICROSECONDS_PER_MILLISECOND / 2L) / MICROSECONDS_PER_MILLISECOND)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    internal fun frameIntervalUs(frameRate: Float): Long? {
        if (!frameRate.isFinite() || frameRate !in MIN_FRAME_RATE..MAX_FRAME_RATE) return null
        return (MICROSECONDS_PER_SECOND / frameRate.toDouble()).roundToLong().coerceAtLeast(1L)
    }
}
