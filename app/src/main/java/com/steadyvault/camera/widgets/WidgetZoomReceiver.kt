package com.steadyvault.camera.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.state.PhotoCaptureStateStore

/** Alterna o zoom da próxima gravação sem abrir Activity, câmera ou preview. */
class WidgetZoomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CYCLE) return
        if (CaptureStateStore.isBusy(context) || PhotoCaptureStateStore.isBusy(context)) {
            WidgetRenderer.updateRecordingControls(context)
            return
        }
        BackgroundRecordingZoom.cycle(context)
        Haptics.tap(context)
        WidgetRenderer.updateZoomControl(context)
    }

    companion object {
        const val ACTION_CYCLE = "com.steadyvault.camera.WIDGET_CYCLE_BACKGROUND_ZOOM"
    }
}
