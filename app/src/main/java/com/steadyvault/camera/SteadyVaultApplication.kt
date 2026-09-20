package com.steadyvault.camera

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.processing.auto.AutoGapRepairService
import com.steadyvault.camera.ui.capture.QuickCaptureLauncher
import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.widgets.WidgetPreviewPublisher
import com.steadyvault.camera.widgets.WidgetRenderer
import androidx.core.content.ContextCompat

class SteadyVaultApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private var changingConfiguration = false
    private val delayedLock = object : Runnable {
        override fun run() {
            if (startedActivities != 0) return
            protect("APP_LIFECYCLE", "bloqueio atrasado dos cofres") {
                if (PrimaryVaultLock.isEnabled(this@SteadyVaultApplication)) PrimaryVaultLock.lock()
                if (SecondaryVaultLock.isEnabled(this@SteadyVaultApplication)) SecondaryVaultLock.lock()
                if (TertiaryVaultLock.isEnabled(this@SteadyVaultApplication)) TertiaryVaultLock.lock()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            protect("APP_LIFECYCLE", "bloqueio ao apagar a tela") {
                if (VaultSecuritySettings.lockOnScreenOff(this@SteadyVaultApplication)) {
                    PrimaryVaultLock.lock()
                    SecondaryVaultLock.lock()
                    TertiaryVaultLock.lock()
                }
            }
            // Screen-off is the ideal time for automatic repair: no player/UI is
            // competing for decoder/GPU and the foreground service owns a wake lock.
            protect("APP_LIFECYCLE", "retomar reparo com tela apagada") {
                AutoGapRepairService.resumeForBackground(this@SteadyVaultApplication)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        // Remove resíduos de versões antigas que armazenavam logs persistentes.
        runCatching { java.io.File(filesDir, "diagnostics").deleteRecursively() }
        runCatching { getSharedPreferences("steadyvault_diagnostics", MODE_PRIVATE).edit().clear().apply() }

        protect("APP_STARTUP", "reconciliar gravacao interrompida") {
            CaptureStateStore.reconcileInterruptedRecording(this)
        }
        protect("APP_STARTUP", "inicializar cofres") {
            VaultStartupCoordinator.runAsync(this)
        }
        protect("APP_STARTUP", "aplicar atalhos de captura") {
            QuickCaptureLauncher.applySavedConfiguration(this)
        }
        protect("APP_STARTUP", "atualizar widgets") {
            WidgetRenderer.updateAll(this)
        }
        protect("APP_STARTUP", "publicar preview de widget") {
            WidgetPreviewPublisher.publishIfNeeded(this)
        }
        protect("APP_STARTUP", "registrar observador de tela") {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfiguration) {
            // Automatic transcode yields while the user is navigating, opening the
            // vault/settings or playing the original. The job stays persisted.
            AutoGapRepairService.pauseForInteractiveUse()
            handler.removeCallbacks(delayedLock)
            protect("APP_LIFECYCLE", "retorno ao primeiro plano") {
                if (VaultSecuritySettings.shouldLockOnForeground(this)) {
                    PrimaryVaultLock.lock()
                    SecondaryVaultLock.lock()
                    TertiaryVaultLock.lock()
                }
                VaultSecuritySettings.clearBackgroundMark(this)
            }
            protect("APP_LIFECYCLE", "coordenar cofres no primeiro plano") {
                VaultStartupCoordinator.runAsync(this)
            }
        }
        changingConfiguration = false
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        changingConfiguration = activity.isChangingConfigurations
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !changingConfiguration) {
            protect("APP_LIFECYCLE", "entrada em segundo plano") {
                VaultSecuritySettings.markBackground(this)
                handler.removeCallbacks(delayedLock)
                val timeout = VaultSecuritySettings.timeoutMs(this)
                when {
                    timeout == VaultSecuritySettings.TIMEOUT_IMMEDIATE -> delayedLock.run()
                    timeout > 0L -> handler.postDelayed(delayedLock, timeout)
                }
            }
            protect("APP_LIFECYCLE", "retomar reparo em segundo plano") {
                AutoGapRepairService.resumeForBackground(this)
            }
        }
    }

    override fun onTerminate() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterActivityLifecycleCallbacks(this) }
        super.onTerminate()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        protect("APP_APPEARANCE", "aplicar aparencia em ${activity.javaClass.simpleName}") {
            AppearanceRuntime.apply(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        AutoGapRepairService.pauseForInteractiveUse()
        protect("APP_APPEARANCE", "reaplicar aparencia em ${activity.javaClass.simpleName}") {
            AppearanceRuntime.apply(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private inline fun protect(category: String, operation: String, block: () -> Unit) {
        runCatching(block)
    }
}
