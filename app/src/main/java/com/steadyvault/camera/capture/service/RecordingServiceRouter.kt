package com.steadyvault.camera.capture.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.camera.CameraLensCatalog

object RecordingServiceRouter {
    fun startIntent(
        context: Context,
        targetFps: Int,
        fromPreview: Boolean = false,
        preferredCameraId: String? = null,
        headless: Boolean = false
    ): Intent {
        // O roteador apenas transporta a intenção. Em headless, a câmera salva nas
        // Configurações é enviada explicitamente; não existe seleção óptica paralela.
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
     * Entrada usada por widget, tela preta e atalhos.
     *
     * O flag headless controla somente a UI/origem. O CaptureService aplica a mesma
     * sequência real de câmera, 3A, encoder e áudio usada pelo botão interno.
     */
    fun startHeadless(context: Context, targetFps: Int, preferredCameraId: String? = null) {
        val settings = CaptureSettings.snapshot(context)
        val exactCameraId = CameraLensCatalog.resolveRecordingCameraId(
            context,
            preferredCameraId ?: settings.selectedCameraId,
            settings.resolution
        )
        context.startForegroundService(
            startIntent(
                context = context,
                targetFps = targetFps,
                fromPreview = false,
                preferredCameraId = exactCameraId,
                headless = true
            )
        )
    }

    fun stop(context: Context, hapticAcknowledged: Boolean = false): Boolean = runCatching {
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP).putExtra(CaptureService.EXTRA_USER_REQUESTED_STOP, true).putExtra(CaptureService.EXTRA_STOP_HAPTIC_ACKNOWLEDGED, hapticAcknowledged)) != null
    }.getOrDefault(false)
}
