package com.steadyvault.camera.core.state

enum class CapturePhase(val busy: Boolean, val finalizing: Boolean) {
    IDLE(false, false),
    REQUESTED(true, false),
    WAITING_CAMERA(true, false),
    PREPARING(true, false),
    RECORDING(true, false),
    RECOVERING(true, false),
    FINALIZING(true, true),
    FAILED(false, false);

    companion object {
        fun from(value: String?): CapturePhase? = entries.firstOrNull { it.name == value }
    }
}

data class CaptureSessionState(
    val phase: CapturePhase,
    val message: String,
    val sessionId: String,
    val owner: String,
    val startedAtElapsedMs: Long,
    val heartbeatElapsedMs: Long
)
