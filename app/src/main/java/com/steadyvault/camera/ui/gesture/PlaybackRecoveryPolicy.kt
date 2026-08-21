package com.steadyvault.camera.ui.gesture

internal class PlaybackRecoveryPolicy {
    enum class Stage {
        MEDIA3_PERFORMANCE,
        MEDIA3_COMPATIBILITY,
        VLC_HARDWARE,
        VLC_SOFTWARE,
        EXHAUSTED
    }

    var stage: Stage = Stage.MEDIA3_PERFORMANCE
        private set

    fun reset() {
        stage = Stage.MEDIA3_PERFORMANCE
    }

    fun startAtMedia3() {
        stage = Stage.MEDIA3_PERFORMANCE
    }

    fun advanceAfterFailure(skipVlcSoftware: Boolean = false): Stage {
        stage = when (stage) {
            Stage.MEDIA3_PERFORMANCE -> Stage.MEDIA3_COMPATIBILITY
            Stage.MEDIA3_COMPATIBILITY -> Stage.VLC_HARDWARE
            Stage.VLC_HARDWARE -> if (skipVlcSoftware) Stage.EXHAUSTED else Stage.VLC_SOFTWARE
            Stage.VLC_SOFTWARE,
            Stage.EXHAUSTED -> Stage.EXHAUSTED
        }
        return stage
    }
}
