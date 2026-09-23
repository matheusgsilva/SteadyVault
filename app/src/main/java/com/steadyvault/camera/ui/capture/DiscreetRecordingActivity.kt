package com.steadyvault.camera.ui.capture

import com.steadyvault.camera.capture.service.CaptureService
import com.steadyvault.camera.capture.service.RecordingServiceRouter
import com.steadyvault.camera.core.capability.PowerPolicy
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.state.CapturePhase
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.widgets.ExpandedControlWidget

import com.steadyvault.camera.R

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class DiscreetRecordingActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private var receiversRegistered = false
    private var widgetStartHandled = false
    private var stopRequested = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action ==
                    CaptureService.ACTION_RECORDING_VISUAL_FINISHED
            ) {
                finishAndRemoveTask()
                return
            }

            val message = intent
                ?.getStringExtra(CaptureService.EXTRA_MESSAGE)
                ?: return

            val widgetWasBusy = CaptureStateStore.isBusy(this@DiscreetRecordingActivity)
            val phase = CapturePhase.from(intent.getStringExtra(CaptureService.EXTRA_PHASE))
                ?: CaptureStateStore.phaseForMessage(message)
            CaptureStateStore.update(
                context = this@DiscreetRecordingActivity,
                state = message,
                phase = phase,
                sessionId = intent.getStringExtra(CaptureService.EXTRA_SESSION_ID).orEmpty()
            )
            if (widgetWasBusy != phase.busy) {
                ExpandedControlWidget.updateRecordingControls(
                    this@DiscreetRecordingActivity
                )
            }
            renderState(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureBlackWindow()
        val startsFromWidget = intent?.action == ACTION_START_FROM_WIDGET
        if (startsFromWidget) {
            // Dispare o FGS antes de inflar a tela preta e instalar gestos. A UI não
            // participa da sessão Camera2 e não deve atrasar o momento capturado.
            startFromWidget()
            if (isFinishing) return
        }

        setContentView(R.layout.activity_discreet_capture)
        installDoubleTapStop()
        if (!startsFromWidget) {
            renderState(CaptureStateStore.currentState(this))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.action == ACTION_START_FROM_WIDGET) {
            widgetStartHandled = false
            startFromWidget()
        }
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiver()
    }

    override fun onStop() {
        unregisterStateReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }


    private fun installDoubleTapStop() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                stopRecordingAndExit()
                return true
            }
        })
        findViewById<View>(R.id.blackRoot).setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun stopRecordingAndExit() {
        if (stopRequested) return
        stopRequested = true

        val hapticAcknowledged = CaptureSettings.snapshot(this).vibrateStartStop
        if (hapticAcknowledged) Haptics.stop(this)
        RecordingServiceRouter.stop(this, hapticAcknowledged = hapticAcknowledged)

        finishAndRemoveTask()
    }

    private fun startFromWidget() {
        if (widgetStartHandled) return
        widgetStartHandled = true

        if (!hasRecordingPermissions()) {
            Toast.makeText(
                this,
                "Conceda a Câmera no aplicativo. Sem Microfone, o vídeo será salvo sem áudio.",
                Toast.LENGTH_LONG
            ).show()

            runCatching {
                startActivity(Intent(this, CaptureActivity::class.java))
            }
            finish()
            return
        }

        if (!PowerPolicy.isIgnoring(this)) {
            Toast.makeText(
                this,
                "A gravação começou. Configure a bateria como Sem restrições depois.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (!CaptureStateStore.isBusy(this)) {
            val started = runCatching {
                val targetFps =
                    CaptureModeStore
                        .getTargetFps(this)

                RecordingServiceRouter.startHeadless(this, targetFps)
                true
            }.getOrElse {
                showFailure("Não foi possível iniciar a gravação", it)
                false
            }

            if (!started) {
                finish()
                return
            }

            val settings = CaptureSettings.snapshot(this)
            val preparing = "Preparando gravação dedicada • " +
                "${CaptureSettings.resolutionLabel(settings.resolution)} ${settings.fps} FPS • " +
                "${if (settings.hdrHlg10) "HLG10" else "SDR"}…"
            CaptureStateStore.update(this, preparing)
            ExpandedControlWidget.updateRecordingControls(this)
        }

        renderState(CaptureStateStore.currentState(this))
    }

    private fun configureBlackWindow() {
        // Não mantenha o painel acordado. A gravação vive no CaptureService com
        // PARTIAL_WAKE_LOCK; a Activity pode parar/voltar conforme a lockscreen.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = MINIMUM_SCREEN_BRIGHTNESS
            // A tela é totalmente preta e estática. Limitar a taxa do painel reduz
            // consumo e aquecimento sem alterar a cadência da câmera ou do encoder.
            preferredRefreshRate = BLACK_SCREEN_REFRESH_RATE
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun registerStateReceiver() {
        if (receiversRegistered) return

        val filter = IntentFilter(
            CaptureService.ACTION_STATE
        ).apply {
            addAction(
                CaptureService.ACTION_RECORDING_VISUAL_FINISHED
            )
        }

        receiversRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterStateReceiver() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(stateReceiver) }
        receiversRegistered = false
    }

    private fun renderState(message: String) {
        val finished = message.startsWith("Salvo") ||
            message.startsWith("Vídeo salvo") ||
            message.startsWith("Falha") ||
            message.startsWith("Gravação cancelada") ||
            message.startsWith("Gravação interrompida")

        if (finished) {
            if (message.startsWith("Salvo") || message.startsWith("Vídeo salvo")) {
                Haptics.success(this)
            } else {
                Haptics.error(this)
            }
            handler.postDelayed({ finish() }, FINISH_DELAY_MS)
        }
    }

    private fun hasRecordingPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showFailure(prefix: String, throwable: Throwable) {
        Haptics.error(this)

        val detail = throwable.message
            ?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName

        Toast.makeText(
            this,
            "$prefix: $detail",
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        const val ACTION_SHOW =
            "com.steadyvault.camera.SHOW_BLACK_RECORDING"
        const val ACTION_START_FROM_WIDGET =
            "com.steadyvault.camera.START_FROM_WIDGET"

        private const val MINIMUM_SCREEN_BRIGHTNESS = 0.01f
        private const val BLACK_SCREEN_REFRESH_RATE = 30f
        private const val FINISH_DELAY_MS = 1_200L
    }
}
