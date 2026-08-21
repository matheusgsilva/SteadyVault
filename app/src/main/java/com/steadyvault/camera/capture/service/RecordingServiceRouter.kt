package com.steadyvault.camera.capture.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.CameraProfileStore

object RecordingServiceRouter {
    fun startIntent(
        context: Context,
        targetFps: Int,
        fromPreview: Boolean = false,
        preferredCameraId: String? = null,
        headless: Boolean = false
    ): Intent {
        val current = CaptureSettings.snapshot(context)
        val cameraId = preferredCameraId?.takeIf { it.isNotBlank() } ?: current.selectedCameraId
        if (cameraId != null) {
            CameraProfileStore.activate(context, cameraId, CameraProfileStore.FunctionMode.VIDEO, current.copy(selectedCameraId = cameraId))
        } else {
            CameraProfileStore.setActiveMode(context, CameraProfileStore.FunctionMode.VIDEO)
        }
        return Intent(context, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_TARGET_FPS, targetFps)
            .putExtra(CaptureService.EXTRA_FROM_PREVIEW, fromPreview && !headless)
            .putExtra(CaptureService.EXTRA_HEADLESS_CAPTURE, headless)
            .putExtra(CaptureService.EXTRA_REQUESTED_AT_ELAPSED_NS, SystemClock.elapsedRealtimeNanos())
            .apply { preferredCameraId?.takeIf { it.isNotBlank() }?.let { putExtra(CaptureService.EXTRA_PREFERRED_CAMERA_ID, it) } }
    }

    fun start(context: Context, targetFps: Int, fromPreview: Boolean = false, preferredCameraId: String? = null, headless: Boolean = false) {
        context.startForegroundService(startIntent(context, targetFps, fromPreview, preferredCameraId, headless))
    }

    /**
     * Caminho dedicado para widget, tela preta e atalhos sem preview.
     *
     * Este método existe para impedir regressão acidental: a origem nunca é marcada
     * como preview e o CaptureService recebe explicitamente o contrato encoder-only.
     */
    fun startHeadless(context: Context, targetFps: Int, preferredCameraId: String? = null) {
        context.startForegroundService(
            startIntent(
                context = context,
                targetFps = targetFps,
                fromPreview = false,
                preferredCameraId = preferredCameraId,
                headless = true
            )
        )
    }

    fun stop(context: Context, hapticAcknowledged: Boolean = false): Boolean = runCatching {
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP).putExtra(CaptureService.EXTRA_USER_REQUESTED_STOP, true).putExtra(CaptureService.EXTRA_STOP_HAPTIC_ACKNOWLEDGED, hapticAcknowledged)) != null
    }.getOrDefault(false)
}
