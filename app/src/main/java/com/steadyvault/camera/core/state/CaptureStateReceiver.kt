package com.steadyvault.camera.core.state

import com.steadyvault.camera.capture.service.CaptureService
import com.steadyvault.camera.widgets.WidgetRenderer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CaptureStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CaptureService.ACTION_STATE) {
            val message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE)
            if (message != null) {
                val phase = CapturePhase.from(intent.getStringExtra(CaptureService.EXTRA_PHASE))
                    ?: CaptureStateStore.phaseForMessage(message)
                CaptureStateStore.update(
                    context = context,
                    state = message,
                    phase = phase,
                    sessionId = intent.getStringExtra(CaptureService.EXTRA_SESSION_ID).orEmpty(),
                    owner = intent.getStringExtra(CaptureService.EXTRA_STATE_OWNER).orEmpty(),
                    startedAtElapsedMs = intent.getLongExtra(
                        CaptureService.EXTRA_STARTED_AT_ELAPSED,
                        0L
                    )
                )
                // O serviço persiste o estado antes do broadcast. Comparar o valor
                // anterior aqui fazia a transição final parecer inalterada e deixava
                // o RemoteViews preso na aparência de gravação.
                WidgetRenderer.updateRecordingControls(context)
            }
        }
    }
}
