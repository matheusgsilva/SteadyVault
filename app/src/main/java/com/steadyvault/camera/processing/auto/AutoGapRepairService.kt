package com.steadyvault.camera.processing.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.engine.ProcessingPolicy
import com.steadyvault.camera.processing.engine.VideoOptimizer
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.model.VideoFilterConfig
import com.steadyvault.camera.processing.validation.VideoValidator
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.ui.settings.SettingsActivity
import com.steadyvault.camera.ui.theme.AppearanceStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Reparo automático de gaps com prioridade inferior à captura.
 *
 * Regras de segurança:
 * - o arquivo original nunca é substituído nem apagado;
 * - cada tentativa usa um temporário e só publica uma cópia após validação;
 * - uma nova gravação cancela imediatamente o trabalho atual e reenfileira o original;
 * - a fila é persistente e sobrevive a encerramento do processo;
 * - falhas ficam registradas para reprocessamento manual.
 */
class AutoGapRepairService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runnable.run()
            },
            "SteadyVault-AutoGapRepair"
        )
    }
    private val workerRunning = AtomicBoolean(false)
    private val cancelCurrent = AtomicBoolean(false)
    @Volatile private var currentJobId: String? = null
    @Volatile private var currentProgress = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AutoGapRepairQueueStore.recoverInterrupted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val path = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)
                File(path).takeIf { it.isFile }?.let {
                    AutoGapRepairQueueStore.enqueue(this, it, fps)
                }
                kickWorker()
            }
            ACTION_RESUME -> kickWorker()
            ACTION_RETRY_FAILED -> {
                AutoGapRepairQueueStore.retryFailed(this)
                kickWorker()
            }
            ACTION_PAUSE_CAPTURE, ACTION_PAUSE_USER -> {
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Pausando reparo para priorizar a gravação…")
            }
        }
        return START_NOT_STICKY
    }

    private fun kickWorker() {
        if (!AutoGapRepairSettings.snapshot(this).enabled || capturePriorityRequested || CaptureStateStore.isBusy(this)) {
            cancelCurrent.set(true)
            if (!workerRunning.get()) stopSelf()
            return
        }
        AutoGapRepairQueueStore.removeMissingSources(this)
        if (!AutoGapRepairQueueStore.hasPending(this)) {
            if (!workerRunning.get()) stopSelf()
            return
        }
        if (!workerRunning.compareAndSet(false, true)) return
        cancelCurrent.set(false)
        startForegroundProcessing("Preparando reparo automático…")
        acquireWakeLock()
        executor.execute { processQueue() }
    }

    private fun processQueue() {
        try {
            while (shouldContinueQueue()) {
                val job = AutoGapRepairQueueStore.nextPending(this) ?: break
                currentJobId = job.id
                currentProgress = 0
                cancelCurrent.set(false)
                val continueQueue = processJob(job)
                currentJobId = null
                if (!continueQueue) break
            }
        } finally {
            currentJobId = null
            workerRunning.set(false)
            releaseWakeLock()
            clearNotification()
            stopSelf()
            if (shouldContinueQueue() && AutoGapRepairQueueStore.hasPending(this)) {
                // Um enqueue pode ter chegado exatamente enquanto o worker finalizava.
                startSelf(this, ACTION_RESUME)
            }
        }
    }

    private fun processJob(job: AutoGapRepairQueueStore.Job): Boolean {
        val source = File(job.sourcePath)
        if (!source.isFile || !VaultRepository.isInsideVault(this, source)) {
            AutoGapRepairQueueStore.markError(this, job.id, "O arquivo original não está mais disponível no cofre")
            return true
        }

        AutoGapRepairQueueStore.markRunning(this, job.id, "Analisando timestamps do original")
        publish(1, "Analisando ${source.name}…")

        var sourceLocked = false
        var workFile: File? = null
        try {
            sourceLocked = VaultRepository.acquireForProcessing(source)
            if (!sourceLocked) {
                AutoGapRepairQueueStore.markError(this, job.id, "O vídeo já está sendo processado por outra operação")
                return true
            }
            if (cancelledForCaptureOrUser()) return pauseJob(job, "Reparo adiado para priorizar nova gravação")

            val analysis = VideoAnalysis.read(source)
            if (!analysis.hasCadenceProblems || analysis.estimatedMissingFrames <= 0) {
                AutoGapRepairQueueStore.markSkipped(
                    this,
                    job.id,
                    "Cadência analisada: nenhum quadro ausente precisa ser preenchido"
                )
                publish(100, "Sem gaps para reparar em ${source.name}")
                return true
            }

            val settings = AutoGapRepairSettings.snapshot(this)
            val requestedMode = if (analysis.hdrHlg10) {
                // O transcoder atual não faz tone mapping HDR. Corrigir somente timestamps
                // preserva os pixels HLG10 sem transformar o original em SDR.
                FrameRepairMode.SMOOTH_TIMELINE
            } else {
                settings.mode
            }
            val modes = buildList {
                add(requestedMode)
                if (!analysis.hdrHlg10 && requestedMode != FrameRepairMode.FILL_MISSING_FRAMES) {
                    add(FrameRepairMode.FILL_MISSING_FRAMES)
                }
                if (requestedMode != FrameRepairMode.SMOOTH_TIMELINE) add(FrameRepairMode.SMOOTH_TIMELINE)
            }.distinct()

            var lastFailure: Throwable? = null
            for ((index, mode) in modes.withIndex()) {
                if (cancelledForCaptureOrUser()) return pauseJob(job, "Reparo pausado; original preservado na fila")
                workFile?.takeIf { it.exists() }?.delete()
                workFile = VaultRepository.createOptimizationWorkFile(this, source)
                val modeLabel = when (mode) {
                    FrameRepairMode.ADAPTIVE_BLEND -> "mistura temporal por GPU"
                    FrameRepairMode.FILL_MISSING_FRAMES -> "preenchimento por quadro vizinho"
                    FrameRepairMode.SMOOTH_TIMELINE -> "correção segura de timestamps"
                    else -> "reparo de timeline"
                }
                publish(
                    (4 + index * 3).coerceAtMost(12),
                    "Reparando ${analysis.estimatedMissingFrames} quadro(s) ausente(s) com $modeLabel"
                )

                val config = OptimizationConfig(
                    preset = OptimizationPreset.REPAIR_ONLY,
                    frameRepair = mode,
                    codec = OutputCodec.SOURCE,
                    rateMode = OptimizationRateMode.AUTO,
                    targetFps = job.targetFps.takeIf { it > 0 } ?: analysis.estimatedFps,
                    targetWidth = 0,
                    targetHeight = 0,
                    bitrateMbps = analysis.sourceBitrateMbps.roundToInt().coerceIn(4, 240),
                    keepAudio = true,
                    replaceOriginal = false,
                    filters = VideoFilterConfig(),
                    maxInterpolatedFramesPerGap = settings.maxInterpolatedFramesPerGap,
                    thermalProtection = true,
                    smartAutoTune = false,
                    aiAssisted = settings.aiAssisted && !analysis.hdrHlg10 && mode != FrameRepairMode.SMOOTH_TIMELINE
                ).normalized()

                val attempt = runCatching {
                    val policy = ProcessingPolicy(this)
                    policy.preflight(source, config)
                    policy.awaitSafeTemperature(true, ::cancelledForCaptureOrUser) { message ->
                        publish(currentProgress, message)
                    }
                    val result = VideoOptimizer().optimize(
                        input = source,
                        output = requireNotNull(workFile),
                        requestedConfig = config,
                        progress = { percent, message ->
                            currentProgress = percent.coerceIn(0, 97)
                            VaultRepository.heartbeatProcessing(source)
                            publish(currentProgress, message)
                        },
                        cancelled = ::cancelledForCaptureOrUser
                    )
                    if (cancelledForCaptureOrUser()) throw InterruptedException("Reparo pausado para captura")
                    require(result.output.isFile && result.output.length() > 0L) { "O reparo não produziu arquivo válido" }
                    publish(98, "Validando a cópia reparada…")
                    VideoValidator.validate(result.output, result.analysis)
                    val finalFile = VaultRepository.commitOptimizedFile(
                        this,
                        result.output,
                        source,
                        "auto_repaired"
                    )
                    workFile = null
                    finalFile to result
                }

                val success = attempt.getOrNull()
                if (success != null) {
                    val (finalFile, result) = success
                    AutoGapRepairQueueStore.markSuccess(
                        this,
                        job.id,
                        finalFile,
                        buildString {
                            append("Cópia reparada criada; original preservado")
                            if (result.repairedGaps > 0) append(" • ").append(result.repairedGaps).append(" gap(s) tratado(s)")
                            if (result.blendedFrames > 0) append(" • ").append(result.blendedFrames).append(" quadro(s) misturado(s)")
                            val repeated = (result.createdFrames - result.blendedFrames).coerceAtLeast(0)
                            if (repeated > 0) append(" • ").append(repeated).append(" posição(ões) CFR preenchida(s)")
                        }
                    )
                    publish(100, "Reparo concluído • original preservado")
                    return true
                }

                val failure = attempt.exceptionOrNull()
                if (failure is InterruptedException || cancelledForCaptureOrUser()) {
                    workFile?.takeIf { it.exists() }?.delete()
                    workFile = null
                    return pauseJob(job, "Reparo pausado; será retomado do original")
                }
                lastFailure = failure
                workFile?.takeIf { it.exists() }?.delete()
                workFile = null
            }

            AutoGapRepairQueueStore.markError(
                this,
                job.id,
                lastFailure?.message ?: "Nenhuma estratégia de reparo foi aceita pelo aparelho"
            )
            publish(0, "Falha no reparo de ${source.name}; disponível para tentar novamente")
            return true
        } catch (interrupted: InterruptedException) {
            workFile?.takeIf { it.exists() }?.delete()
            return pauseJob(job, "Reparo interrompido para priorizar a gravação")
        } catch (throwable: Throwable) {
            workFile?.takeIf { it.exists() }?.delete()
            AutoGapRepairQueueStore.markError(
                this,
                job.id,
                throwable.message ?: "Falha inesperada no reparo automático"
            )
            return true
        } finally {
            if (sourceLocked) VaultRepository.releaseFromProcessing(source)
        }
    }

    private fun pauseJob(job: AutoGapRepairQueueStore.Job, message: String): Boolean {
        AutoGapRepairQueueStore.markPending(this, job.id, message)
        return false
    }

    private fun shouldContinueQueue(): Boolean =
        AutoGapRepairSettings.snapshot(this).enabled &&
            !capturePriorityRequested &&
            !CaptureStateStore.isBusy(this) &&
            !cancelCurrent.get()

    private fun cancelledForCaptureOrUser(): Boolean =
        cancelCurrent.get() ||
            capturePriorityRequested ||
            CaptureStateStore.isBusy(this) ||
            !AutoGapRepairSettings.snapshot(this).enabled ||
            Thread.currentThread().isInterrupted

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:auto_gap_repair")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundProcessing(message: String) {
        val notification = buildNotification(0, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publish(progress: Int, message: String) {
        currentProgress = progress.coerceIn(0, 100)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(currentProgress, message))
    }

    private fun clearNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reparo automático de vídeo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                description = "Corrige gaps em cópias dos vídeos gravados, preservando sempre o original"
            }
        )
    }

    private fun buildNotification(progress: Int, message: String): Notification {
        val identity = VisualIdentityStore.notificationIdentity(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoGapRepairService::class.java).setAction(ACTION_PAUSE_USER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Reparo de vídeo $progress%"))
            .setContentText(VisualIdentityStore.notificationText(this, message))
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOngoing(progress in 0..99)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, identity.cancelIcon),
                    VisualIdentityStore.actionLabel(this, "Pausar", "Pausar reparo"),
                    pause
                ).build()
            )
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelCurrent.set(true)
        currentJobId?.let { id ->
            AutoGapRepairQueueStore.markPending(
                this,
                id,
                "O Android pausou o reparo pelo limite de processamento; será retomado depois"
            )
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        cancelCurrent.set(true)
        currentJobId?.let { id ->
            AutoGapRepairQueueStore.markPending(
                this,
                id,
                "Reparo interrompido; será retomado do original"
            )
        }
        releaseWakeLock()
        clearNotification()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_ENQUEUE = "com.steadyvault.camera.AUTO_GAP_REPAIR_ENQUEUE"
        private const val ACTION_RESUME = "com.steadyvault.camera.AUTO_GAP_REPAIR_RESUME"
        private const val ACTION_RETRY_FAILED = "com.steadyvault.camera.AUTO_GAP_REPAIR_RETRY_FAILED"
        private const val ACTION_PAUSE_CAPTURE = "com.steadyvault.camera.AUTO_GAP_REPAIR_PAUSE_CAPTURE"
        private const val ACTION_PAUSE_USER = "com.steadyvault.camera.AUTO_GAP_REPAIR_PAUSE_USER"
        private const val EXTRA_SOURCE_PATH = "source_path"
        private const val EXTRA_TARGET_FPS = "target_fps"
        private const val CHANNEL_ID = "steadyvault_auto_gap_repair"
        private const val NOTIFICATION_ID = 4011
        private const val MAX_WAKE_LOCK_MS = 5L * 60L * 60L * 1_000L

        @Volatile private var capturePriorityRequested = false

        fun enqueue(context: Context, source: File, targetFps: Int) {
            if (!AutoGapRepairSettings.snapshot(context).enabled || !source.isFile) return
            AutoGapRepairQueueStore.enqueue(context, source, targetFps)
            startSelf(context, ACTION_ENQUEUE) {
                putExtra(EXTRA_SOURCE_PATH, source.absolutePath)
                putExtra(EXTRA_TARGET_FPS, targetFps)
            }
        }

        fun pauseForCapture(context: Context) {
            // O flag é atualizado no mesmo processo antes de qualquer IPC: o transcoder
            // enxerga o cancelamento imediatamente, sem fazer a câmera esperar.
            capturePriorityRequested = true
            runCatching {
                context.startService(
                    Intent(context, AutoGapRepairService::class.java).setAction(ACTION_PAUSE_CAPTURE)
                )
            }
        }

        fun resumeAfterCapture(context: Context) {
            capturePriorityRequested = false
            resumeIfEnabled(context)
        }

        fun pauseByUser(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AutoGapRepairService::class.java).setAction(ACTION_PAUSE_USER)
                )
            }
        }

        fun resumeIfEnabled(context: Context) {
            if (!AutoGapRepairSettings.snapshot(context).enabled) return
            AutoGapRepairQueueStore.recoverInterrupted(context)
            if (!AutoGapRepairQueueStore.hasPending(context)) return
            startSelf(context, ACTION_RESUME)
        }

        fun retryFailed(context: Context) {
            AutoGapRepairQueueStore.retryFailed(context)
            if (AutoGapRepairSettings.snapshot(context).enabled) {
                startSelf(context, ACTION_RETRY_FAILED)
            }
        }

        private fun startSelf(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, AutoGapRepairService::class.java).setAction(action).apply(configure)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
