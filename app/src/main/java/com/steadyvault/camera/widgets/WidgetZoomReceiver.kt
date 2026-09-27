package com.steadyvault.camera.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.settings.CaptureSettings
import kotlin.math.abs
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
        val settings = CaptureSettings.snapshot(context)
        val choices = BackgroundRecordingZoom.choices
        val current = BackgroundRecordingZoom.selected(context)
        val currentIndex = choices.indices.minByOrNull { abs(choices[it] - current) } ?: 1
        val requested = choices[(currentIndex + 1) % choices.size]
        val effective = BackgroundRecordingZoom.set(context, requested)
        CaptureSettings.save(context, settings.copy(zoomRatio = effective))
        Haptics.tap(context)
        WidgetRenderer.updateZoomControl(context)
    }

    companion object {
        const val ACTION_CYCLE = "com.steadyvault.camera.WIDGET_CYCLE_BACKGROUND_ZOOM"
    }
}
