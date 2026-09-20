package com.steadyvault.camera.widgets

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.steadyvault.camera.capture.service.RecordingServiceRouter
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.state.VideoProcessingStateStore
import com.steadyvault.camera.core.state.PhotoCaptureStateStore
import com.steadyvault.camera.processing.service.VideoProcessingService
import com.steadyvault.camera.core.storage.RecordingStorageGuard
import com.steadyvault.camera.ui.capture.CaptureActivity

class WidgetStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START) return

        if (
            CaptureStateStore.isBusy(context) ||
            PhotoCaptureStateStore.isBusy(context)
        ) {
            WidgetRenderer.updateRecordingControls(context)
            return
        }

        val cameraGranted =
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted || !audioGranted) {
            CaptureStateStore.update(
                context,
                "Libere câmera e microfone para gravar com áudio"
            )
            WidgetRenderer.updateRecordingControls(context)
            runCatching {
                context.startActivity(
                    Intent(context, CaptureActivity::class.java)
                        .setAction(CaptureActivity.ACTION_WIDGET_REQUEST_PERMISSIONS)
                        .putExtra(
                            CaptureActivity.EXTRA_WIDGET_PERMISSION_MODE,
                            CaptureActivity.WIDGET_PERMISSION_VIDEO
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            return
        }

        val fps = intent.getIntExtra(
            EXTRA_TARGET_FPS,
            CaptureModeStore.getTargetFps(context)
        )
        val settings = CaptureSettings.snapshot(context)
        val effectiveSettings = settings.copy(fps = fps)
        if (VideoProcessingStateStore.snapshot(context).running) {
            VideoProcessingService.cancel(context)
            CaptureStateStore.update(context, "Cancelando otimização para gravar sem dividir recursos…")
        }
        val spaceCheck = RecordingStorageGuard.checkProfile(context, effectiveSettings)
        if (!spaceCheck.allowed) {
            CaptureStateStore.update(context, spaceCheck.message)
            WidgetRenderer.updateRecordingControls(context)
            Haptics.error(context)
            Toast.makeText(context, spaceCheck.message, Toast.LENGTH_LONG).show()
            return
        }
        val preparing = "Preparando gravação dedicada • ${CaptureSettings.resolutionLabel(settings.resolution)} • $fps FPS…"

        CaptureStateStore.update(context, preparing)

        runCatching {
            RecordingServiceRouter.startHeadless(context, fps)
            WidgetRenderer.updateRecordingControls(context)
        }.onFailure { throwable ->
            CaptureStateStore.update(context, "Pronto para gravar")
            WidgetRenderer.updateRecordingControls(context)
            Haptics.error(context)
            Toast.makeText(
                context,
                throwable.message?.takeIf { it.isNotBlank() }
                    ?: "Não foi possível iniciar a gravação.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_START = "com.steadyvault.camera.WIDGET_START"
        const val EXTRA_TARGET_FPS = "target_fps"
    }
}
