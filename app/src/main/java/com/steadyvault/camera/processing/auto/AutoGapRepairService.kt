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
import android.os.SystemClock
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
    private val progressPublishLock = Any()
    @Volatile private var currentJobId: String? = null
    @Volatile private var currentSourcePath: String? = null
    @Volatile private var currentProgress = 0
    @Volatile private var currentMessage = ""
    @Volatile private var lastPublishedProgress = -1
    @Volatile private var lastPublishedMessage = ""
    @Volatile private var lastPublishedAtMs = 0L
    @Volatile private var foregroundStarted = false
    @Volatile private var discardCurrentRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AutoGapRepairQueueStore.recoverInterrupted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_ENQUEUE -> {
                // startForegroundService() exige promoção imediata, mesmo se a fila mudar
                // entre o envio do Intent e a execução deste callback.
                ensureForegroundStarted("Verificando fila de reparo…")
                val path = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)
                File(path).takeIf { it.isFile }?.let {
                    AutoGapRepairQueueStore.enqueue(this, it, fps)
                }
                kickWorker()
            }
            ACTION_RESUME -> {
                ensureForegroundStarted("Verificando fila de reparo…")
                kickWorker()
            }
            ACTION_MANUAL_REPAIR -> {
                ensureForegroundStarted("Preparando pós-processamento manual…")
                manualDrainRequested = true
                userPauseRequested = false
                val path = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 0)
                val source = File(path)
                if (source.isFile && VaultRepository.isInsideKnownVault(this, source)) {
                    AutoGapRepairSettings.setEnabled(this, true)
                    AutoGapRepairSettings.setMode(this, FrameRepairMode.MOTION_COMPENSATED)
                    AutoGapRepairQueueStore.enqueue(this, source, fps)
                    kickWorker()
                } else {
                    manualDrainRequested = false
                    publish(100, "Vídeo não está disponível para pós-processamento", force = true)
                    stopSelf(startId)
                }
            }
            ACTION_RETRY_FAILED -> {
                ensureForegroundStarted("Verificando fila de reparo…")
                AutoGapRepairQueueStore.retryFailed(this)
                kickWorker()
            }
            ACTION_PAUSE_CAPTURE -> {
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Pausando reparo para priorizar a gravação…", force = true)
            }
            ACTION_PAUSE_USER -> {
                userPauseRequested = true
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Reparo pausado pelo usuário…", force = true)
            }
            ACTION_DISCARD_SOURCE -> {
                val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
                    ?.let(::File)?.absoluteFile?.normalize()?.path.orEmpty()
                if (path.isNotBlank()) {
                    AutoGapRepairQueueStore.removeSource(this, File(path))
                    if (currentSourcePath == path && workerRunning.get()) {
                        discardCurrentRequested = true
                        cancelCurrent.set(true)
                        publish(currentProgress, "Cancelando reparo do vídeo excluído…", force = true)
                    } else {
                        AutoGapRepairQueueStore.removeMissingSources(this)
                        kickWorker()
                    }
                }
            }
        }
        return if (
            action == ACTION_ENQUEUE ||
            action == ACTION_RESUME ||
            action == ACTION_RETRY_FAILED ||
            action == ACTION_MANUAL_REPAIR
        ) {
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    private fun kickWorker() {
        if (
            !AutoGapRepairSettings.snapshot(this).enabled ||
            capturePriorityRequested ||
            interactiveBlocksProcessing() ||
            userPauseRequested ||
            CaptureStateStore.isBusy(this)
        ) {
            cancelCurrent.set(true)
            if (!workerRunning.get()) stopSelf()
            return
        }
        AutoGapRepairQueueStore.removeMissingSources(this)
        if (!AutoGapRepairQueueStore.hasPending(this)) {
            immediateWidgetDrainRequested = false
            if (!workerRunning.get()) stopSelf()
            return
        }
        if (!workerRunning.compareAndSet(false, true)) return
        processingActive = true
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
                currentSourcePath = job.sourcePath
                currentProgress = 0
                currentMessage = "Preparando reparo automático"
                synchronized(progressPublishLock) {
                    lastPublishedProgress = -1
                    lastPublishedMessage = ""
                    lastPublishedAtMs = 0L
                }
                cancelCurrent.set(false)
                val continueQueue = processJob(job)
                broadcastProgress(running = false)
                currentJobId = null
                currentSourcePath = null
                if (!continueQueue) break
            }
        } finally {
            currentJobId = null
            val discardedCurrent = discardCurrentRequested
            discardCurrentRequested = false
            if (discardedCurrent) cancelCurrent.set(false)
            workerRunning.set(false)
            processingActive = false
            releaseWakeLock()
            clearNotification()
            stopSelf()
            if (AutoGapRepairQueueStore.hasPending(this)) {
                if (shouldContinueQueue()) {
                    // Um enqueue pode ter chegado exatamente enquanto o worker finalizava.
                    startSelf(this, ACTION_RESUME)
                }
            } else {
                immediateWidgetDrainRequested = false
                manualDrainRequested = false
            }
        }
    }

    private fun processJob(job: AutoGapRepairQueueStore.Job): Boolean {
        val source = File(job.sourcePath)
        if (!source.isFile || !VaultRepository.isInsideKnownVault(this, source)) {
            AutoGapRepairQueueStore.markError(this, job.id, "O arquivo original não está mais disponível em um cofre conhecido")
            return true
        }

        AutoGapRepairQueueStore.markRunning(this, job.id, "Analisando timestamps do original")
        publish(1, "Analisando ${source.name}…", force = true)

        var sourceLocked = false
        var workFile: File? = null
        try {
            sourceLocked = VaultRepository.acquireForProcessing(source)
            if (!sourceLocked) {
                AutoGapRepairQueueStore.markError(this, job.id, "O vídeo já está sendo processado por outra operação")
                return true
            }
            if (cancelledForCaptureOrUser()) return pauseJob(job, pauseReason())

            val analysis = VideoAnalysis.read(source)
            if (!analysis.hasRepairableProblems) {
                AutoGapRepairQueueStore.markSkipped(
                    this,
                    job.id,
                    "Vídeo validado: cadência e sincronismo já estão dentro dos limites"
                )
                publish(100, "Nenhum problema temporal detectado em ${source.name}")
                return true
            }

            val settings = AutoGapRepairSettings.snapshot(this)
            val needsFrameSynthesis = analysis.estimatedMissingFrames > 0
            val requestedMode = if (analysis.hdrHlg10 || !needsFrameSynthesis) {
                // O transcoder atual não faz tone mapping HDR. Corrigir somente timestamps
                // preserva os pixels HLG10 sem transformar o original em SDR.
                FrameRepairMode.SMOOTH_TIMELINE
            } else {
                settings.mode
            }
            val modes = if (analysis.hdrHlg10) {
                // Arquivos HDR antigos não entram no transcoder SDR para evitar
                // alteração de gama/cor. O reparo fica restrito à timeline.
                listOf(FrameRepairMode.SMOOTH_TIMELINE)
            } else if (!needsFrameSynthesis) {
                // Primeiro tenta correção sem recodificar. Se a validação rígida ainda
                // detectar jitter ou A/V fora do limite, recodifica para uma grade CFR.
                listOf(
                    FrameRepairMode.SMOOTH_TIMELINE,
                    FrameRepairMode.FILL_MISSING_FRAMES
                )
            } else {
                // Em SDR com frame realmente ausente, respeite o método escolhido
                // e mantenha fallbacks seguros sem transformar um erro visual em falha da fila.
                when (requestedMode) {
                    FrameRepairMode.QUALITY_LOW_MOTION,
                    FrameRepairMode.QUALITY_MEDIUM_MOTION,
                    FrameRepairMode.QUALITY_HIGH_MOTION,
                    FrameRepairMode.MOTION_COMPENSATED -> listOf(
                        requestedMode,
                        FrameRepairMode.ADAPTIVE_BLEND,
                        FrameRepairMode.FILL_MISSING_FRAMES
                    )
                    FrameRepairMode.ADAPTIVE_BLEND -> listOf(
                        FrameRepairMode.ADAPTIVE_BLEND,
                        FrameRepairMode.FILL_MISSING_FRAMES
                    )
                    FrameRepairMode.FILL_MISSING_FRAMES -> listOf(FrameRepairMode.FILL_MISSING_FRAMES)
                    FrameRepairMode.SMOOTH_TIMELINE,
                    FrameRepairMode.NONE -> listOf(
                        FrameRepairMode.SMOOTH_TIMELINE,
                        FrameRepairMode.FILL_MISSING_FRAMES
                    )
                }.distinct()
            }

            var lastFailure: Throwable? = null
            for ((index, mode) in modes.withIndex()) {
                if (cancelledForCaptureOrUser()) return pauseJob(job, pauseReason())
                workFile?.takeIf { it.exists() }?.delete()
                workFile = VaultRepository.createOptimizationWorkFile(this, source)
                val modeLabel = when (mode) {
                    FrameRepairMode.MOTION_COMPENSATED -> "interpolação compensada por movimento"
                    FrameRepairMode.QUALITY_LOW_MOTION -> "qualidade para movimento baixo"
                    FrameRepairMode.QUALITY_MEDIUM_MOTION -> "qualidade para movimento médio"
                    FrameRepairMode.QUALITY_HIGH_MOTION -> "qualidade conservadora para movimento alto"
                    FrameRepairMode.ADAPTIVE_BLEND -> "mistura temporal por GPU"
                    FrameRepairMode.FILL_MISSING_FRAMES -> "preenchimento por quadro vizinho"
                    FrameRepairMode.SMOOTH_TIMELINE -> "correção segura de timestamps"
                    FrameRepairMode.NONE -> "reparo de timeline"
                }
                publish(
                    (4 + index * 3).coerceAtMost(12),
                    if (needsFrameSynthesis) {
                        "Reparando ${analysis.estimatedMissingFrames} quadro(s) ausente(s) com $modeLabel"
                    } else {
                        "Normalizando cadência, timestamps e sincronismo com $modeLabel"
                    }
                )

                val config = OptimizationConfig(
                    preset = OptimizationPreset.REPAIR_ONLY,
                    frameRepair = mode,
                    codec = OutputCodec.SOURCE,
                    rateMode = OptimizationRateMode.CBR,
                    targetFps = job.targetFps.takeIf { it > 0 } ?: analysis.estimatedFps,
                    targetWidth = 0,
                    targetHeight = 0,
                    // Reparo não é etapa de compressão. Use folga de bitrate para
                    // reduzir perda geracional durante a recodificação obrigatória.
                    bitrateMbps = (analysis.sourceBitrateMbps * 1.20)
                        .roundToInt()
                        .coerceIn(4, 240),
                    keepAudio = true,
                    replaceOriginal = false,
                    maxInterpolatedFramesPerGap = settings.maxInterpolatedFramesPerGap,
                    thermalProtection = true
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
                    val repairedAnalysis = VideoValidator.validateRepair(result.output, result.analysis)
                    require(repairedAnalysis.cadenceScore >= 90) {
                        "A saída ainda não atingiu a qualidade mínima de cadência"
                    }
                    if (
                        needsFrameSynthesis &&
                        (mode == FrameRepairMode.MOTION_COMPENSATED ||
                            mode == FrameRepairMode.QUALITY_LOW_MOTION ||
                            mode == FrameRepairMode.QUALITY_MEDIUM_MOTION ||
                            mode == FrameRepairMode.QUALITY_HIGH_MOTION ||
                            mode == FrameRepairMode.ADAPTIVE_BLEND)
                    ) {
                        require(result.blendedFrames > 0) {
                            "A tentativa não reconstruiu visualmente nenhum quadro ausente"
                        }
                    }
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
                            if (analysis.duplicateTimestampCount > 0) append(" • timestamps duplicados corrigidos")
                            if (analysis.shortIntervalCount > 0) append(" • intervalos curtos normalizados")
                            if (analysis.hasAvSyncProblem) append(" • sincronismo A/V normalizado")
                            if (result.blendedFrames > 0) append(" • ").append(result.blendedFrames).append(" quadro(s) reconstruído(s)")
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
                    return pauseJob(job, pauseReason())
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
            publish(0, "Falha no reparo de ${source.name}; disponível para tentar novamente", force = true)
            return true
        } catch (interrupted: InterruptedException) {
            workFile?.takeIf { it.exists() }?.delete()
            return pauseJob(job, pauseReason())
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
            !interactiveBlocksProcessing() &&
            !userPauseRequested &&
            !CaptureStateStore.isBusy(this) &&
            !cancelCurrent.get()

    private fun cancelledForCaptureOrUser(): Boolean =
        cancelCurrent.get() ||
            capturePriorityRequested ||
            interactiveBlocksProcessing() ||
            userPauseRequested ||
            CaptureStateStore.isBusy(this) ||
            !AutoGapRepairSettings.snapshot(this).enabled ||
            Thread.currentThread().isInterrupted

    private fun pauseReason(): String = when {
        capturePriorityRequested || CaptureStateStore.isBusy(this) ->
            "Reparo pausado para priorizar uma nova gravação"
        interactiveBlocksProcessing() ->
            "Reparo pausado enquanto o app está em uso; será retomado em segundo plano"
        userPauseRequested ->
            "Reparo pausado pelo usuário"
        else ->
            "Reparo interrompido; será retomado do original"
    }

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
        currentMessage = message
        ensureForegroundStarted(message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(currentProgress, message))
    }

    private fun ensureForegroundStarted(message: String) {
        if (foregroundStarted) return
        val notification = buildNotification(currentProgress, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun publish(progress: Int, message: String, force: Boolean = false) {
        val safeProgress = progress.coerceIn(0, 100)
        currentProgress = safeProgress
        currentMessage = message

        val now = SystemClock.elapsedRealtime()
        val shouldPublish = synchronized(progressPublishLock) {
            val changed = safeProgress != lastPublishedProgress || message != lastPublishedMessage
            val intervalElapsed = lastPublishedAtMs == 0L || now - lastPublishedAtMs >= PROGRESS_PUBLISH_INTERVAL_MS
            val publishNow = force || safeProgress >= 100 || changed && intervalElapsed
            if (publishNow) {
                lastPublishedProgress = safeProgress
                lastPublishedMessage = message
                lastPublishedAtMs = now
            }
            publishNow
        }
        if (!shouldPublish) return

        currentJobId?.let { AutoGapRepairQueueStore.updateProgress(this, it, safeProgress, message) }
        broadcastProgress(running = safeProgress < 100)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(safeProgress, message))
    }

    private fun broadcastProgress(running: Boolean) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_SOURCE_PATH, currentSourcePath.orEmpty())
            putExtra(EXTRA_PROGRESS, currentProgress)
            putExtra(EXTRA_MESSAGE, currentMessage)
            putExtra(EXTRA_RUNNING, running)
        })
    }

    private fun clearNotification() {
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
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
        const val ACTION_STATE = "com.steadyvault.camera.AUTO_GAP_REPAIR_STATE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RUNNING = "running"
        private const val ACTION_ENQUEUE = "com.steadyvault.camera.AUTO_GAP_REPAIR_ENQUEUE"
        private const val ACTION_RESUME = "com.steadyvault.camera.AUTO_GAP_REPAIR_RESUME"
        private const val ACTION_RETRY_FAILED = "com.steadyvault.camera.AUTO_GAP_REPAIR_RETRY_FAILED"
        private const val ACTION_MANUAL_REPAIR = "com.steadyvault.camera.AUTO_GAP_REPAIR_MANUAL"
        private const val ACTION_PAUSE_CAPTURE = "com.steadyvault.camera.AUTO_GAP_REPAIR_PAUSE_CAPTURE"
        private const val ACTION_PAUSE_USER = "com.steadyvault.camera.AUTO_GAP_REPAIR_PAUSE_USER"
        private const val ACTION_DISCARD_SOURCE = "com.steadyvault.camera.AUTO_GAP_REPAIR_DISCARD_SOURCE"
        const val EXTRA_SOURCE_PATH = "source_path"
        private const val EXTRA_TARGET_FPS = "target_fps"
        private const val CHANNEL_ID = "steadyvault_auto_gap_repair"
        private const val NOTIFICATION_ID = 4011
        private const val MAX_WAKE_LOCK_MS = 5L * 60L * 60L * 1_000L
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 300L

        @Volatile private var capturePriorityRequested = false
        @Volatile private var interactivePriorityRequested = false
        @Volatile private var userPauseRequested = false
        @Volatile private var processingActive = false
        @Volatile private var immediateWidgetDrainRequested = false
        @Volatile private var manualDrainRequested = false

        fun enqueue(context: Context, source: File, targetFps: Int) {
            if (!AutoGapRepairSettings.snapshot(context).enabled || !source.isFile) return
            AutoGapRepairQueueStore.enqueue(context, source, targetFps)
            if (
                capturePriorityRequested ||
                interactiveBlocksProcessing() ||
                userPauseRequested ||
                CaptureStateStore.isBusy(context)
            ) return
            startSelf(context, ACTION_RESUME)
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
            userPauseRequested = false
            AutoGapRepairQueueStore.recoverInterrupted(context)
            AutoGapRepairQueueStore.removeMissingSources(context)
            if (AutoGapRepairQueueStore.hasPending(context)) immediateWidgetDrainRequested = true
            resumeIfEnabled(context)
        }

        private fun interactiveBlocksProcessing(): Boolean =
            interactivePriorityRequested



        fun cancelAndForget(context: Context, source: File) {
            val normalized = source.absoluteFile.normalize()
            AutoGapRepairQueueStore.removeSource(context, normalized)
            runCatching {
                context.startService(
                    Intent(context, AutoGapRepairService::class.java)
                        .setAction(ACTION_DISCARD_SOURCE)
                        .putExtra(EXTRA_SOURCE_PATH, normalized.path)
                )
            }
        }



        fun resumeIfEnabled(context: Context) {
            if (!AutoGapRepairSettings.snapshot(context).enabled) return
            if (
                capturePriorityRequested ||
                interactiveBlocksProcessing() ||
                userPauseRequested ||
                CaptureStateStore.isBusy(context)
            ) return
            AutoGapRepairQueueStore.recoverInterrupted(context)
            AutoGapRepairQueueStore.removeMissingSources(context)
            if (!AutoGapRepairQueueStore.hasPending(context)) return
            startSelf(context, ACTION_RESUME)
        }

        fun retryFailed(context: Context) {
            userPauseRequested = false
            AutoGapRepairQueueStore.retryFailed(context)
            resumeIfEnabled(context)
        }


        private fun startSelf(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, AutoGapRepairService::class.java).setAction(action).apply(configure)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
