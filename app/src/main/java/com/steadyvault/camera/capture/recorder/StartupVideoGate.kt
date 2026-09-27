package com.steadyvault.camera.capture.recorder

/**
 * Decide when a valid GOP can be published after the user actually committed
 * the recording. This keeps the first visible sample decodable without holding
 * MediaCodec output buffers.
 */
internal class StartupVideoGate(
    private val maxFramesBeforeFallback: Int,
    private val maxWaitBeforeFallbackUs: Long,
    private val maxBufferedBytes: Long
) {
    enum class Action {
        HOLD,
        START_WITH_CURRENT_KEY_FRAME,
        START_WITH_BUFFERED_GOP,
        WRITE_CURRENT
    }

    data class Decision(
        val action: Action,
        val requestSyncFrame: Boolean = false
    )

    private var opened = false
    private var observedAfterCommit = 0
    private var firstCommittedPtsUs = Long.MIN_VALUE

    fun onSample(
        isKeyFrame: Boolean,
        clearlyBeforeCommit: Boolean,
        sourcePtsUs: Long,
        bufferedBytes: Long,
        hasBufferedKeyFrame: Boolean
    ): Decision {
        if (opened) return Decision(Action.WRITE_CURRENT)
        if (clearlyBeforeCommit) return Decision(Action.HOLD)

        if (firstCommittedPtsUs == Long.MIN_VALUE) firstCommittedPtsUs = sourcePtsUs
        observedAfterCommit++

        if (isKeyFrame) {
            opened = true
            return Decision(Action.START_WITH_CURRENT_KEY_FRAME)
        }

        val waitedUs = (sourcePtsUs - firstCommittedPtsUs).coerceAtLeast(0L)
        val fallbackReached =
            observedAfterCommit >= maxFramesBeforeFallback.coerceAtLeast(1) ||
                waitedUs >= maxWaitBeforeFallbackUs.coerceAtLeast(0L) ||
                bufferedBytes >= maxBufferedBytes.coerceAtLeast(0L)

        if (!fallbackReached) return Decision(Action.HOLD)

        if (hasBufferedKeyFrame) {
            opened = true
            return Decision(Action.START_WITH_BUFFERED_GOP)
        }

        return Decision(Action.HOLD, requestSyncFrame = true)
    }

    fun forceFallback(hasBufferedKeyFrame: Boolean): Decision {
        if (opened) return Decision(Action.WRITE_CURRENT)
        if (hasBufferedKeyFrame) {
            opened = true
            return Decision(Action.START_WITH_BUFFERED_GOP)
        }
        return Decision(Action.HOLD, requestSyncFrame = true)
    }
}
