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
import android.os.Process
import android.util.Log
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.ui.capture.QuickCaptureLauncher
import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.widgets.WidgetPreviewPublisher
import com.steadyvault.camera.widgets.WidgetRenderer
import androidx.core.content.ContextCompat
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SteadyVaultApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private var changingConfiguration = false
    private val delayedLock = object : Runnable {
        override fun run() {
            if (startedActivities != 0) return
            if (PrimaryVaultLock.isEnabled(this@SteadyVaultApplication)) PrimaryVaultLock.lock()
            if (SecondaryVaultLock.isEnabled(this@SteadyVaultApplication)) SecondaryVaultLock.lock()
            if (TertiaryVaultLock.isEnabled(this@SteadyVaultApplication)) TertiaryVaultLock.lock()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && VaultSecuritySettings.lockOnScreenOff(this@SteadyVaultApplication)) {
                PrimaryVaultLock.lock()
                SecondaryVaultLock.lock()
                TertiaryVaultLock.lock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        AppLogRepository.installCrashCapture(this)
        CaptureStateStore.reconcileInterruptedRecording(this)
        VaultStartupCoordinator.runAsync(this)
        QuickCaptureLauncher.applySavedConfiguration(this)
        WidgetRenderer.updateAll(this)
        WidgetPreviewPublisher.publishIfNeeded(this)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfiguration) {
            handler.removeCallbacks(delayedLock)
            if (VaultSecuritySettings.shouldLockOnForeground(this)) {
                PrimaryVaultLock.lock()
                SecondaryVaultLock.lock()
                TertiaryVaultLock.lock()
            }
            VaultSecuritySettings.clearBackgroundMark(this)
            VaultStartupCoordinator.runAsync(this)
        }
        changingConfiguration = false
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        changingConfiguration = activity.isChangingConfigurations
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !changingConfiguration) {
            VaultSecuritySettings.markBackground(this)
            handler.removeCallbacks(delayedLock)
            val timeout = VaultSecuritySettings.timeoutMs(this)
            when {
                timeout == VaultSecuritySettings.TIMEOUT_IMMEDIATE -> delayedLock.run()
                timeout > 0L -> handler.postDelayed(delayedLock, timeout)
            }
        }
    }

    override fun onTerminate() {
        runCatching { unregisterReceiver(screenReceiver) }
        unregisterActivityLifecycleCallbacks(this)
        super.onTerminate()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        AppearanceRuntime.apply(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        AppearanceRuntime.apply(activity)
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    companion object {
        private const val TAG = "SteadyVaultApplication"
        private val mediaEngineExecutor = Executors.newSingleThreadExecutor { task ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "SteadyVault-MediaEngine")
        }
        private val mediaEngineLock = Any()

        @Volatile private var mediaEngineFuture: Future<Throwable?>? = null
        @Volatile private var mediaEngineReady = false
        @Volatile private var mediaEngineUpdateChecked = false
        @Volatile private var mediaEngineCompatibilityRepairChecked = false

        fun ensureMediaEngine(application: Application, refreshExtractor: Boolean = false) {
            if (!mediaEngineReady) {
                val error = beginMediaEngineInitialization(application).get()
                if (error != null) throw IllegalStateException(error.message ?: "Motor de download indisponível", error)
                mediaEngineReady = true
            }
            if (refreshExtractor) refreshExtractorBestEffort(application)
        }

        fun repairMediaExtractorForCompatibility(application: Application): Boolean {
            if (mediaEngineCompatibilityRepairChecked) return false
            synchronized(mediaEngineLock) {
                if (mediaEngineCompatibilityRepairChecked) return false
                mediaEngineCompatibilityRepairChecked = true
                val preferences = application.getSharedPreferences(MEDIA_ENGINE_PREFERENCES, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val lastAttempt = preferences.getLong(KEY_LAST_COMPATIBILITY_REPAIR, 0L)
                if (now - lastAttempt < MEDIA_ENGINE_REPAIR_COOLDOWN_MS) return false
                preferences.edit().putLong(KEY_LAST_COMPATIBILITY_REPAIR, now).apply()
                return runCatching {
                    updateExtractorFromStableChannel(application)
                }.onFailure {
                    Log.w(TAG, "Não foi possível reparar o extrator de compatibilidade", it)
                }.isSuccess
            }
        }

        private fun refreshExtractorBestEffort(application: Application) {
            if (mediaEngineUpdateChecked) return
            synchronized(mediaEngineLock) {
                if (mediaEngineUpdateChecked) return
                mediaEngineUpdateChecked = true
                runCatching { updateExtractorFromStableChannel(application) }
                    .onFailure { Log.w(TAG, "Não foi possível atualizar o extrator; usando a versão incluída", it) }
            }
        }

        private fun updateExtractorFromStableChannel(application: Application) {
            val instance = YoutubeDL.getInstance()
            val methods = instance.javaClass.methods.filter { it.name == "updateYoutubeDL" }
            val withChannel = methods.firstOrNull { method ->
                method.parameterTypes.size == 2 && method.parameterTypes[0].isAssignableFrom(application.javaClass)
            }
            if (withChannel != null) {
                val stable = withChannel.parameterTypes[1].enumConstants
                    ?.firstOrNull { value -> value.toString().contains("STABLE", ignoreCase = true) }
                    ?: throw IllegalStateException("Canal estável do yt-dlp indisponível")
                withChannel.invoke(instance, application, stable)
                return
            }
            val legacy = methods.firstOrNull { method ->
                method.parameterTypes.size == 1 && method.parameterTypes[0].isAssignableFrom(application.javaClass)
            } ?: throw IllegalStateException("Atualização do yt-dlp indisponível")
            legacy.invoke(instance, application)
        }

        private fun beginMediaEngineInitialization(application: Application): Future<Throwable?> =
            mediaEngineFuture ?: synchronized(mediaEngineLock) {
                mediaEngineFuture ?: mediaEngineExecutor.submit<Throwable?> {
                    runCatching {
                        YoutubeDL.getInstance().init(application)
                        FFmpeg.getInstance().init(application)
                        Aria2c.getInstance().init(application)
                    }.exceptionOrNull()?.also { Log.e(TAG, "Falha ao inicializar motor de download", it) }
                }.also { mediaEngineFuture = it }
            }

        private const val MEDIA_ENGINE_PREFERENCES = "steadyvault_media_engine"
        private const val KEY_LAST_COMPATIBILITY_REPAIR = "last_compatibility_repair_v1"
        private const val MEDIA_ENGINE_REPAIR_COOLDOWN_MS = 6L * 60L * 60L * 1_000L
    }

}
