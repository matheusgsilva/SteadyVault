package com.steadyvault.camera.processing.service

import com.steadyvault.camera.ui.theme.AppearanceStore
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.core.state.OptimizationStateStore
import com.steadyvault.camera.processing.engine.ProcessingPolicy
import com.steadyvault.camera.processing.engine.VideoOptimizer
import com.steadyvault.camera.processing.model.FilterStrength
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.model.VideoFilterConfig
import com.steadyvault.camera.processing.validation.VideoValidator
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.ui.vault.VideoOptimizationActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class VideoOptimizationService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            },
            "SteadyVault-VideoOptimization"
        )
    }
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val progressLock = Any()

    @Volatile private var currentSourcePath: String? = null
    @Volatile private var currentProgress = 0
    @Volatile private var lastPublishedProgress = -1
    @Volatile private var lastPublishedMessage = ""
    @Volatile private var lastPublishedAtMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (running.get()) {
                    cancelled.set(true)
                    publishProgress(currentProgress, "Cancelando otimização…", forceNotification = true)
                } else {
                    val snapshot = OptimizationStateStore.snapshot(this)
                    snapshot.sourcePath.takeIf { it.isNotBlank() }?.let { path ->
                        val source = File(path)
                        VaultRepository.releaseFromProcessing(source)
                        OptimizationStateStore.finish(
                            this,
                            source,
                            OptimizationStateStore.STATE_CANCELLED,
                            snapshot.progress,
                            "Otimização cancelada"
                        )
                    }
                    clearProcessingNotification()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (running.compareAndSet(false, true)) {
                    startOptimization(intent)
                } else {
                    publishProgress(currentProgress, "Já existe uma otimização em andamento", forceNotification = true)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startOptimization(intent: Intent) {
        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        val source = File(sourcePath)
        currentSourcePath = source.absolutePath
        currentProgress = 0
        lastPublishedProgress = -1
        lastPublishedMessage = ""
        lastPublishedAtMs = 0L

        val config = OptimizationConfig(
            preset = OptimizationPreset.from(intent.getStringExtra(EXTRA_PRESET)),
            frameRepair = FrameRepairMode.from(intent.getStringExtra(EXTRA_FRAME_REPAIR)),
            codec = OutputCodec.from(intent.getStringExtra(EXTRA_CODEC)),
            rateMode = OptimizationRateMode.from(intent.getStringExtra(EXTRA_RATE_MODE)),
            targetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 0),
            targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 0),
            targetHeight = intent.getIntExtra(EXTRA_TARGET_HEIGHT, 0),
            bitrateMbps = intent.getIntExtra(EXTRA_BITRATE_MBPS, 0),
            keepAudio = intent.getBooleanExtra(EXTRA_KEEP_AUDIO, true),
            replaceOriginal = intent.getBooleanExtra(EXTRA_REPLACE_ORIGINAL, false),
            filters = VideoFilterConfig(
                denoise = FilterStrength.from(intent.getStringExtra(EXTRA_DENOISE)),
                sharpen = FilterStrength.from(intent.getStringExtra(EXTRA_SHARPEN)),
                deblock = FilterStrength.from(intent.getStringExtra(EXTRA_DEBLOCK)),
                brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, 0),
                contrast = intent.getIntExtra(EXTRA_CONTRAST, 100),
                saturation = intent.getIntExtra(EXTRA_SATURATION, 100),
                temperature = intent.getIntExtra(EXTRA_TEMPERATURE, 0),
                tint = intent.getIntExtra(EXTRA_TINT, 0)
            ),
            maxInterpolatedFramesPerGap = intent.getIntExtra(EXTRA_MAX_INTERPOLATED_FRAMES, 8),
            thermalProtection = intent.getBooleanExtra(EXTRA_THERMAL_PROTECTION, true),
            smartAutoTune = intent.getBooleanExtra(EXTRA_SMART_AUTO_TUNE, false),
            aiAssisted = intent.getBooleanExtra(EXTRA_AI_ASSISTED, false),
            trimStartMs = intent.getLongExtra(EXTRA_TRIM_START_MS, 0L),
            trimEndMs = intent.getLongExtra(EXTRA_TRIM_END_MS, 0L)
        ).normalized()

        cancelled.set(false)
        OptimizationStateStore.begin(this, source)
        acquireWakeLock()
        val initialNotification = buildNotification(0, "Preparando vídeo…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        executor.execute {
            var output: File? = null
            var sourceLocked = false
            try {
                require(sourcePath.isNotBlank() && source.isFile) { "Vídeo de origem inválido" }
                require(VaultRepository.isInsideVault(this, source)) { "O vídeo precisa estar no cofre" }
                sourceLocked = VaultRepository.acquireForProcessing(source)
                require(sourceLocked) { "Este vídeo já está sendo processado" }
                VaultRepository.heartbeatProcessing(source)
                publishProgress(1, "Verificando vídeo e espaço disponível", forceNotification = true)

                val policy = ProcessingPolicy(this)
                val preflight = policy.preflight(source, config)
                if (preflight.warnings.isNotEmpty()) {
                    publishProgress(currentProgress, preflight.warnings.joinToString(" • "))
                }
                policy.awaitSafeTemperature(config.thermalProtection, cancelled::get) { message ->
                    publishProgress(currentProgress, message)
                }

                output = VaultRepository.createOptimizationWorkFile(this, source)
                var lastThermalCheckMs = 0L
                val result = VideoOptimizer().optimize(
                    input = source,
                    output = output,
                    requestedConfig = config,
                    progress = { percent, message ->
                        VaultRepository.heartbeatProcessing(source)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastThermalCheckMs >= THERMAL_CHECK_INTERVAL_MS) {
                            lastThermalCheckMs = now
                            policy.awaitSafeTemperature(config.thermalProtection, cancelled::get) { thermalMessage ->
                                publishProgress(max(currentProgress, percent), thermalMessage)
                            }
                        }
                        publishProgress(percent, message)
                    },
                    cancelled = cancelled::get
                )

                require(result.output.isFile && result.output.length() > 0L) {
                    "O processador não gerou um arquivo válido"
                }
                if (cancelled.get()) throw InterruptedException("Otimização cancelada")

                publishProgress(98, "Validando integridade, duração, orientação e áudio", forceNotification = true)
                val validation = VideoValidator.validate(
                    result.output,
                    result.analysis,
                    expectedDurationUs = if (config.hasTrim()) config.trimmedDurationUs(result.analysis.durationUs) else null
                )
                if (cancelled.get()) throw InterruptedException("Otimização cancelada")

                var replacementDeferred = false
                val finalFile = if (config.replaceOriginal) {
                    val replacement = VaultRepository.replaceOriginalOrDefer(this, source, result.output)
                    replacementDeferred = replacement.deferred
                    replacement.file
                } else {
                    VaultRepository.commitOptimizedFile(
                        this,
                        result.output,
                        source,
                        config.preset.name.lowercase()
                    )
                }
                output = null

                val saved = result.inputBytes - result.outputBytes
                val message = buildString {
                    append(if (replacementDeferred) "Vídeo corrigido e validado" else "Vídeo otimizado e validado")
                    append(" • ").append(validation.width).append('×').append(validation.height)
                    append(" • cadência ").append(result.analysis.cadenceScore).append("/100")
                    result.aiReport?.let { append(" • análise local ").append(it.confidence).append('%') }
                    if (result.repairedGaps > 0) append(" • ${result.repairedGaps} irregularidades tratadas")
                    if (result.blendedFrames > 0) append(" • ${result.blendedFrames} transições misturadas")
                    val repeatedFrames = (result.createdFrames - result.blendedFrames).coerceAtLeast(0)
                    if (repeatedFrames > 0) append(" • $repeatedFrames posições CFR preenchidas")
                    if (saved > 0) append(" • ${VaultRepository.formatBytes(saved)} economizados")
                    if (saved < 0) append(" • ${VaultRepository.formatBytes(-saved)} adicionais")
                    if (replacementDeferred) append(" • troca pendente será aplicada ao reabrir o app ou fechar o player")
                }
                publishTerminal(
                    state = OptimizationStateStore.STATE_SUCCESS,
                    broadcastState = STATE_SUCCESS,
                    progress = 100,
                    message = message,
                    source = source,
                    outputPath = finalFile.absolutePath
                )
            } catch (throwable: Throwable) {
                output?.takeIf { it.exists() }?.delete()
                val wasCancelled = cancelled.get() || throwable is InterruptedException
                val message = if (wasCancelled) {
                    "Otimização cancelada"
                } else {
                    throwable.message ?: "Falha ao otimizar vídeo"
                }
                publishTerminal(
                    state = if (wasCancelled) OptimizationStateStore.STATE_CANCELLED else OptimizationStateStore.STATE_ERROR,
                    broadcastState = if (wasCancelled) STATE_CANCELLED else STATE_ERROR,
                    progress = currentProgress,
                    message = message,
                    source = source,
                    outputPath = null
                )
            } finally {
                if (sourceLocked) VaultRepository.releaseFromProcessing(source)
                running.set(false)
                releaseWakeLock()
                clearProcessingNotification()
                stopSelf()
            }
        }
    }

    private fun publishProgress(
        progress: Int,
        message: String,
        forceNotification: Boolean = false
    ) {
        val source = currentSourcePath?.let(::File) ?: return
        val safeProgress: Int
        val shouldPublish: Boolean
        synchronized(progressLock) {
            safeProgress = max(currentProgress, progress.coerceIn(0, 99))
            currentProgress = safeProgress
            val now = SystemClock.elapsedRealtime()
            val progressChanged = safeProgress != lastPublishedProgress
            val messageChanged = message != lastPublishedMessage
            shouldPublish = forceNotification ||
                progressChanged ||
                messageChanged && now - lastPublishedAtMs >= MESSAGE_UPDATE_INTERVAL_MS ||
                now - lastPublishedAtMs >= NOTIFICATION_FORCE_INTERVAL_MS
            if (shouldPublish) {
                lastPublishedProgress = safeProgress
                lastPublishedMessage = message
                lastPublishedAtMs = now
            }
        }
        if (!shouldPublish) return

        OptimizationStateStore.update(this, source, safeProgress, message)
        VaultRepository.heartbeatProcessing(source)
        broadcast(STATE_PROGRESS, message, safeProgress, null)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(safeProgress, message))
    }

    private fun publishTerminal(
        state: String,
        broadcastState: String,
        progress: Int,
        message: String,
        source: File,
        outputPath: String?
    ) {
        currentProgress = progress.coerceIn(0, 100)
        OptimizationStateStore.finish(this, source, state, currentProgress, message, outputPath)
        clearProcessingNotification()
        broadcast(broadcastState, message, currentProgress, outputPath)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:video_processing")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun clearProcessingNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun broadcast(state: String, message: String, progress: Int, outputPath: String?) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_SOURCE_PATH, currentSourcePath.orEmpty())
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PROGRESS, progress)
            outputPath?.let { putExtra(EXTRA_OUTPUT_PATH, it) }
        })
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Otimização de vídeo", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                description = "Processamento local dos vídeos do cofre"
            }
        )
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VideoOptimizationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { currentSourcePath?.let { putExtra(VideoOptimizationActivity.EXTRA_PATH, it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, VideoOptimizationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val safeProgress = progress.coerceIn(0, 100)
        val identity = VisualIdentityStore.notificationIdentity(this)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Otimização $safeProgress%"))
            .setContentText(VisualIdentityStore.notificationText(this, text))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText("$safeProgress%")
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, safeProgress, safeProgress <= 0)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, identity.cancelIcon),
                    VisualIdentityStore.actionLabel(this, "Cancelar", "Interromper"),
                    cancel
                ).build()
            )
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        currentSourcePath?.let(::File)?.let { source ->
            publishTerminal(
                OptimizationStateStore.STATE_ERROR,
                STATE_ERROR,
                currentProgress,
                "O Android encerrou a otimização por limite de processamento",
                source,
                null
            )
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (running.get()) cancelled.set(true)
        releaseWakeLock()
        clearProcessingNotification()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATE = "com.steadyvault.camera.OPTIMIZATION_STATE"
        const val STATE_PROGRESS = "progress"
        const val STATE_SUCCESS = "success"
        const val STATE_ERROR = "error"
        const val STATE_CANCELLED = "cancelled"
        const val EXTRA_SOURCE_PATH = "source_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_FRAME_REPAIR = "frame_repair"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_RATE_MODE = "rate_mode"
        const val EXTRA_TARGET_FPS = "target_fps"
        const val EXTRA_TARGET_WIDTH = "target_width"
        const val EXTRA_TARGET_HEIGHT = "target_height"
        const val EXTRA_BITRATE_MBPS = "bitrate_mbps"
        const val EXTRA_KEEP_AUDIO = "keep_audio"
        const val EXTRA_REPLACE_ORIGINAL = "replace_original"
        const val EXTRA_DENOISE = "filter_denoise"
        const val EXTRA_SHARPEN = "filter_sharpen"
        const val EXTRA_DEBLOCK = "filter_deblock"
        const val EXTRA_BRIGHTNESS = "filter_brightness"
        const val EXTRA_CONTRAST = "filter_contrast"
        const val EXTRA_SATURATION = "filter_saturation"
        const val EXTRA_TEMPERATURE = "filter_temperature"
        const val EXTRA_TINT = "filter_tint"
        const val EXTRA_MAX_INTERPOLATED_FRAMES = "max_interpolated_frames"
        const val EXTRA_THERMAL_PROTECTION = "thermal_protection"
        const val EXTRA_SMART_AUTO_TUNE = "smart_auto_tune"
        const val EXTRA_AI_ASSISTED = "ai_assisted"
        const val EXTRA_TRIM_START_MS = "trim_start_ms"
        const val EXTRA_TRIM_END_MS = "trim_end_ms"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"
        private const val ACTION_START = "com.steadyvault.camera.OPTIMIZE_START"
        private const val ACTION_CANCEL = "com.steadyvault.camera.OPTIMIZE_CANCEL"
        private const val CHANNEL_ID = "steadyvault_video_optimization"
        private const val NOTIFICATION_ID = 4007
        private const val MAX_WAKE_LOCK_MS = 5L * 60L * 60L * 1_000L
        private const val THERMAL_CHECK_INTERVAL_MS = 2_000L
        private const val MESSAGE_UPDATE_INTERVAL_MS = 350L
        private const val NOTIFICATION_FORCE_INTERVAL_MS = 1_000L

        fun cancel(context: Context) {
            val intent = Intent(context, VideoOptimizationService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }

        fun start(context: Context, source: File, requestedConfig: OptimizationConfig): Boolean {
            val active = OptimizationStateStore.snapshot(context)
            if (active.running) return false
            val config = requestedConfig.normalized()
            OptimizationStateStore.begin(context, source)
            val intent = Intent(context, VideoOptimizationService::class.java).setAction(ACTION_START).apply {
                putExtra(EXTRA_SOURCE_PATH, source.absolutePath)
                putExtra(EXTRA_PRESET, config.preset.name)
                putExtra(EXTRA_FRAME_REPAIR, config.frameRepair.name)
                putExtra(EXTRA_CODEC, config.codec.name)
                putExtra(EXTRA_RATE_MODE, config.rateMode.name)
                putExtra(EXTRA_TARGET_FPS, config.targetFps)
                putExtra(EXTRA_TARGET_WIDTH, config.targetWidth)
                putExtra(EXTRA_TARGET_HEIGHT, config.targetHeight)
                putExtra(EXTRA_BITRATE_MBPS, config.bitrateMbps)
                putExtra(EXTRA_KEEP_AUDIO, config.keepAudio)
                putExtra(EXTRA_REPLACE_ORIGINAL, config.replaceOriginal)
                putExtra(EXTRA_DENOISE, config.filters.denoise.name)
                putExtra(EXTRA_SHARPEN, config.filters.sharpen.name)
                putExtra(EXTRA_DEBLOCK, config.filters.deblock.name)
                putExtra(EXTRA_BRIGHTNESS, config.filters.brightness)
                putExtra(EXTRA_CONTRAST, config.filters.contrast)
                putExtra(EXTRA_SATURATION, config.filters.saturation)
                putExtra(EXTRA_TEMPERATURE, config.filters.temperature)
                putExtra(EXTRA_TINT, config.filters.tint)
                putExtra(EXTRA_MAX_INTERPOLATED_FRAMES, config.maxInterpolatedFramesPerGap)
                putExtra(EXTRA_THERMAL_PROTECTION, config.thermalProtection)
                putExtra(EXTRA_SMART_AUTO_TUNE, config.smartAutoTune)
                putExtra(EXTRA_AI_ASSISTED, config.aiAssisted)
                putExtra(EXTRA_TRIM_START_MS, config.trimStartMs)
                putExtra(EXTRA_TRIM_END_MS, config.trimEndMs)
            }
            try {
                context.startForegroundService(intent)
            } catch (throwable: Throwable) {
                VaultRepository.releaseFromProcessing(source)
                OptimizationStateStore.finish(
                    context,
                    source,
                    OptimizationStateStore.STATE_ERROR,
                    0,
                    throwable.message ?: "Não foi possível iniciar a otimização"
                )
                throw throwable
            }
            return true
        }
    }
}
