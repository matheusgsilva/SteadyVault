package com.steadyvault.camera.ui.vault

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.os.Process
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.PrivateVaultMediaIndex
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultAreaId
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.storage.vault.VaultMediaIndex
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


internal object VaultBulkImportRunner {
    private const val PREFS = "vault_import_tasks"
    private const val KEY_RUNNING = "running"
    private const val KEY_MESSAGE = "message"
    private const val KEY_TOTAL = "total"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_SKIPPED = "skipped"
    private const val KEY_DONE = "done"
    private const val KEY_FAILURES = "failures"
    private const val KEY_RESULT_PENDING = "resultPending"
    private const val KEY_RESULT_MESSAGE = "resultMessage"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_CANCEL_REQUESTED = "cancelRequested"
    private const val KEY_SYSTEM_PAUSE_REQUESTED = "systemPauseRequested"
    private const val RUNNING_HEARTBEAT_STALE_MS = 90_000L
    private const val ITEM_WATCHDOG_POLL_MS = 500L
    private const val VERIFY_STALL_TIMEOUT_MS = 15_000L
    private const val COPY_STALL_TIMEOUT_MS = 45_000L
    private const val FINALIZE_STALL_TIMEOUT_MS = 25_000L
    private const val CAPTURE_PRIORITY_POLL_MS = 500L
    private const val MAX_FAILURE_SAMPLES = 20
    private const val CAPTURE_HEARTBEAT_FRESH_MS = 90_000L
    private const val CAPTURE_START_GRACE_MS = 15_000L
    private const val WAIT_STATUS_REFRESH_MS = 1_000L
    private val queueLifecycleLock = Any()

    enum class ProcessingOutcome { COMPLETED, CANCELLED, FAILED, PAUSED_BY_SYSTEM }

    private enum class ItemStage(val label: String, val stallTimeoutMs: Long) {
        VERIFYING("verificação", VERIFY_STALL_TIMEOUT_MS),
        COPYING("cópia", COPY_STALL_TIMEOUT_MS),
        FINALIZING("finalização", FINALIZE_STALL_TIMEOUT_MS)
    }

    private enum class ItemStatus { IMPORTED, SKIPPED, FAILED, CANCELLED, PAUSED_FOR_CAPTURE }

    private data class ItemResult(
        val status: ItemStatus,
        val displayName: String,
        val errorMessage: String = ""
    )

    data class Snapshot(
        val running: Boolean,
        val message: String,
        val done: Int,
        val total: Int,
        val imported: Int,
        val skipped: Int,
        val failures: Int,
        val cancelRequested: Boolean,
        val resultPending: Boolean,
        val resultMessage: String
    )

    fun snapshot(context: Context, area: String): Snapshot {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(key(area, KEY_RUNNING), false)
        val updatedAt = prefs.getLong(key(area, KEY_UPDATED_AT), 0L)
        if (running && updatedAt > 0L && System.currentTimeMillis() - updatedAt > RUNNING_HEARTBEAT_STALE_MS) {
            if (VaultImportQueueStore.hasPending(app, area)) {
                prefs.edit()
                    .putBoolean(key(area, KEY_RUNNING), false)
                    .putString(key(area, KEY_MESSAGE), "Fila interrompida • pronta para retomar")
                    .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
                    .commit()
            } else {
                clearRunning(app, area)
            }
        }
        return Snapshot(
            running = prefs.getBoolean(key(area, KEY_RUNNING), false),
            message = prefs.getString(key(area, KEY_MESSAGE), "Processando…").orEmpty(),
            done = prefs.getInt(key(area, KEY_DONE), 0),
            total = prefs.getInt(key(area, KEY_TOTAL), 0),
            imported = prefs.getInt(key(area, KEY_IMPORTED), 0),
            skipped = prefs.getInt(key(area, KEY_SKIPPED), 0),
            failures = prefs.getInt(key(area, KEY_FAILURES), 0),
            cancelRequested = prefs.getBoolean(key(area, KEY_CANCEL_REQUESTED), false),
            resultPending = prefs.getBoolean(key(area, KEY_RESULT_PENDING), false),
            resultMessage = prefs.getString(key(area, KEY_RESULT_MESSAGE), "").orEmpty()
        )
    }

    fun start(context: Context, area: String, uris: List<Uri>, label: String, keepDuplicates: Boolean = false) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        val effectiveKeepDuplicates = VaultImportQueueStore.job(app, area)?.keepDuplicates ?: keepDuplicates
        val enqueue = synchronized(queueLifecycleLock) {
            val result = VaultImportQueueStore.enqueueDocuments(app, area, uris, label, effectiveKeepDuplicates)
            if (result.createdNewJob) {
                initialize(app, area, "Importando 0/${result.total} $label…", result.total)
            } else {
                val state = snapshot(app, area)
                val total = maxOf(state.total, result.total, state.done)
                val message = if (result.waitingForTreeScan) {
                    "Lendo pasta… • ${uris.size} arquivo(s) adicional(is) na fila"
                } else {
                    progressText(state.done, total, state.imported, state.skipped, state.failures)
                }
                saveRunning(app, area, message, state.done, total, state.imported, state.skipped, state.failures, preserveCancel = true)
            }
            result
        }
        VaultImportService.start(app, area)
        AppLogRepository.info(app, "import", if (enqueue.createdNewJob) "Fila persistente criada para $area com ${uris.size} arquivo(s) • repetidos=${if (effectiveKeepDuplicates) "manter" else "ignorar"}" else "${uris.size} arquivo(s) acrescentado(s) à fila de $area • total ${enqueue.total}")
    }

    fun startTree(context: Context, area: String, treeUri: Uri, label: String, keepDuplicates: Boolean = false) {
        val app = context.applicationContext
        synchronized(queueLifecycleLock) {
            if (VaultImportQueueStore.hasPending(app, area)) {
                AppLogRepository.warn(app, "import", "Nova pasta não substituiu a fila existente de $area; retomando a importação pendente")
                VaultImportService.start(app, area)
                return
            }
            VaultImportQueueStore.createTree(app, area, treeUri, label, keepDuplicates)
            initialize(app, area, "Lendo pasta…", 0)
        }
        VaultImportService.start(app, area)
        AppLogRepository.info(app, "import", "Importação de pasta iniciada em $area")
    }

    fun cancel(context: Context, area: String) {
        requestCancellation(context, area)
        runCatching { VaultImportService.cancel(context.applicationContext, area) }
    }

    fun requestCancellation(context: Context, area: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), true)
            .putString(key(area, KEY_MESSAGE), "Cancelando importação com segurança…")
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .apply()
        AppLogRepository.warn(context, "import", "Cancelamento solicitado para $area")
    }

    fun isCancellationRequested(context: Context, area: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(area, KEY_CANCEL_REQUESTED), false)

    private fun isSystemPauseRequested(context: Context, area: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(area, KEY_SYSTEM_PAUSE_REQUESTED), false)

    fun resumeIfPending(context: Context, area: String) {
        val snapshot = snapshot(context, area)
        if (VaultImportQueueStore.hasPending(context, area) && !snapshot.running && !snapshot.cancelRequested) {
            runCatching { VaultImportService.resumePending(context.applicationContext, area) }
                .onFailure { AppLogRepository.error(context, "import", "Não foi possível retomar a fila $area", it) }
        }
    }

    fun consumeResult(context: Context, area: String): String? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(key(area, KEY_RESULT_PENDING), false)) return null
        val message = prefs.getString(key(area, KEY_RESULT_MESSAGE), "").orEmpty().takeIf { it.isNotBlank() }
        prefs.edit().putBoolean(key(area, KEY_RESULT_PENDING), false).remove(key(area, KEY_RESULT_MESSAGE)).apply()
        return message
    }

    fun cleanupDirtyState(context: Context): Int {
        val app = context.applicationContext
        var cleaned = 0
        val statePrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        VaultAreaId.ALL.forEach { area ->
            val running = statePrefs.getBoolean(key(area, KEY_RUNNING), false)
            val updatedAt = statePrefs.getLong(key(area, KEY_UPDATED_AT), 0L)
            if (running && updatedAt > 0L && System.currentTimeMillis() - updatedAt > RUNNING_HEARTBEAT_STALE_MS) {
                if (VaultImportQueueStore.hasPending(app, area)) {
                    statePrefs.edit()
                        .putBoolean(key(area, KEY_RUNNING), false)
                        .putString(key(area, KEY_MESSAGE), "Fila preservada • abra o cofre para retomar")
                        .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
                        .apply()
                } else {
                    clearRunning(app, area)
                }
                cleaned++
            }
        }

        VaultAreaId.ALL.forEach { area ->
            cleaned += VaultImportDedupStore.cleanupArea(app, area, directoryForArea(app, area))
        }
        return cleaned
    }

    fun processPending(context: Context, area: String, onProgress: () -> Unit): ProcessingOutcome {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val app = context.applicationContext
        var job = VaultImportQueueStore.job(app, area) ?: return ProcessingOutcome.COMPLETED
        if (job.isTree && !job.queueReady) {
            val treeUri = job.treeUri ?: return failPending(app, area, IllegalStateException("Pasta de origem ausente"))
            saveRunning(app, area, "Lendo pasta…", 0, 0, 0, 0, 0, preserveCancel = true)
            onProgress()
            var lastTreeProgressElapsed = 0L
            val treeUris = runCatching {
                VaultImportUtils.mediaUrisFromTree(
                    context = app,
                    treeUri = treeUri,
                    onProgress = { found ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTreeProgressElapsed >= WAIT_STATUS_REFRESH_MS) {
                            lastTreeProgressElapsed = now
                            saveRunning(app, area, "Lendo pasta… • $found mídia(s) encontrada(s)", 0, maxOf(found, snapshot(app, area).total), 0, 0, 0, preserveCancel = true)
                            onProgress()
                        }
                    },
                    shouldCancel = { isCancellationRequested(app, area) || isSystemPauseRequested(app, area) }
                )
            }.getOrElse { return failPending(app, area, it) }
            if (isSystemPauseRequested(app, area)) {
                markSystemPaused(app, area)
                onProgress()
                return ProcessingOutcome.PAUSED_BY_SYSTEM
            }
            if (isCancellationRequested(app, area)) {
                synchronized(queueLifecycleLock) {
                    val queued = runCatching { VaultImportQueueStore.readQueue(app, area) }.getOrDefault(emptyList())
                    finish(app, area, 0, 0, 0, treeUris.size + queued.size, emptyList(), cancelled = true)
                    VaultImportQueueStore.clear(app, area)
                    releasePermissionsAsync(app, job, treeUris + queued)
                }
                onProgress()
                return ProcessingOutcome.CANCELLED
            }
            VaultImportQueueStore.prepareTreeQueue(app, area, treeUris)
            job = VaultImportQueueStore.job(app, area) ?: return ProcessingOutcome.FAILED
            val total = VaultImportQueueStore.currentQueueCount(app, area)
            if (total <= 0) {
                synchronized(queueLifecycleLock) {
                    finishEmpty(app, area)
                    VaultImportQueueStore.clear(app, area)
                    releasePermissionsAsync(app, job, emptyList())
                }
                onProgress()
                return ProcessingOutcome.COMPLETED
            }
            saveRunning(app, area, "Importando 0/$total ${job.label}…", 0, total, 0, 0, 0, preserveCancel = true)
            onProgress()
        }

        val previous = snapshot(app, area)
        var imported = previous.imported.coerceAtLeast(0)
        var skipped = previous.skipped.coerceAtLeast(0)
        var failureCount = previous.failures.coerceAtLeast(0)
        var done = maxOf(job.nextIndex, previous.done).coerceAtLeast(0)
        if (done != job.nextIndex) VaultImportQueueStore.setNextIndex(app, area, done)
        val failures = mutableListOf<VaultImportSummary.Failure>()
        val seenUris = linkedSetOf<String>()
        runCatching { VaultImportQueueStore.readQueue(app, area).take(done).forEach { seenUris += it.toString() } }

        importLoop@ while (true) {
            if (isSystemPauseRequested(app, area)) {
                markSystemPaused(app, area)
                AppLogRepository.warn(app, "import", "Fila $area pausada pelo limite do Android em $done item(ns)")
                onProgress()
                return ProcessingOutcome.PAUSED_BY_SYSTEM
            }
            if (isCancellationRequested(app, area)) {
                synchronized(queueLifecycleLock) {
                    val currentJob = VaultImportQueueStore.job(app, area) ?: job
                    val queued = runCatching { VaultImportQueueStore.readQueue(app, area) }.getOrDefault(emptyList())
                    val total = maxOf(queued.size, done)
                    finish(app, area, imported, skipped, done, total, failures, cancelled = true, failureCount = failureCount)
                    VaultImportQueueStore.clear(app, area)
                    releasePermissionsAsync(app, currentJob, queued)
                }
                AppLogRepository.info(app, "import", "Importação cancelada em $done item(ns)")
                onProgress()
                return ProcessingOutcome.CANCELLED
            }

            job = VaultImportQueueStore.job(app, area) ?: return ProcessingOutcome.COMPLETED
            val uris = runCatching { VaultImportQueueStore.readQueue(app, area) }.getOrElse { return failPending(app, area, it) }
            if (done > uris.size) return failPending(app, area, IllegalStateException("Checkpoint da importação inválido: $done/${uris.size}"))

            if (done >= uris.size) {
                var completed = false
                synchronized(queueLifecycleLock) {
                    val latestJob = VaultImportQueueStore.job(app, area)
                    val latestUris = runCatching { VaultImportQueueStore.readQueue(app, area) }.getOrDefault(emptyList())
                    if (latestJob == null) {
                        completed = true
                    } else if (done >= latestUris.size) {
                        val total = maxOf(latestUris.size, done)
                        finish(app, area, imported, skipped, done, total, failures, cancelled = false, failureCount = failureCount)
                        VaultImportQueueStore.clear(app, area)
                        releasePermissionsAsync(app, latestJob, latestUris)
                        completed = true
                    }
                }
                if (completed) {
                    AppLogRepository.info(app, "import", "Importação concluída em $area • $done item(ns) processado(s)")
                    onProgress()
                    return ProcessingOutcome.COMPLETED
                }
                continue
            }

            val total = maxOf(uris.size, snapshot(app, area).total, done + 1)
            if (!awaitRecordingPriority(app, area, done, total, imported, skipped, failureCount, onProgress)) continue

            val index = done
            val uri = uris[index]
            val itemNumber = index + 1
            saveRunning(app, area, "Verificando $itemNumber/$total • OK $imported • repetidos $skipped • falhas $failureCount", done, total, imported, skipped, failureCount, preserveCancel = true)
            onProgress()

            val uriKey = uri.toString()
            if (!job.keepDuplicates && !seenUris.add(uriKey)) {
                skipped++
            } else {
                if (job.keepDuplicates) seenUris.add(uriKey)
                var lastProgressElapsed = 0L
                val result = processItemWithWatchdog(
                    context = app,
                    area = area,
                    uri = uri,
                    itemNumber = itemNumber,
                    total = total,
                    imported = imported,
                    skipped = skipped,
                    failureCount = failureCount,
                    done = done,
                    keepDuplicates = job.keepDuplicates,
                    onCopyProgress = { copied, expected ->
                        val now = SystemClock.elapsedRealtime()
                        if (copied >= expected && expected > 0L || now - lastProgressElapsed >= 450L) {
                            lastProgressElapsed = now
                            val percent = if (expected > 0L) ((copied * 100L) / expected).coerceIn(0L, 100L).toInt() else -1
                            val detail = if (percent >= 0) "$percent%" else formatTransferredBytes(copied)
                            val liveTotal = maxOf(total, snapshot(app, area).total, itemNumber)
                            saveRunning(app, area, "Copiando $itemNumber/$liveTotal • $detail • OK $imported • repetidos $skipped • falhas $failureCount", done, liveTotal, imported, skipped, failureCount, preserveCancel = true)
                            onProgress()
                        }
                    }
                )
                when (result.status) {
                    ItemStatus.IMPORTED -> imported++
                    ItemStatus.SKIPPED -> skipped++
                    ItemStatus.FAILED -> {
                        failureCount++
                        if (failures.size < MAX_FAILURE_SAMPLES) failures += VaultImportSummary.Failure(result.displayName, result.errorMessage)
                        AppLogRepository.warn(app, "import", "Item $itemNumber/$total encerrado com falha em ${result.displayName}: ${result.errorMessage}")
                    }
                    ItemStatus.CANCELLED -> {
                        if (isCancellationRequested(app, area) || isSystemPauseRequested(app, area)) continue@importLoop
                        return failPending(app, area, InterruptedException("Processamento do item $itemNumber/$total foi interrompido; checkpoint preservado"))
                    }
                    ItemStatus.PAUSED_FOR_CAPTURE -> {
                        // O item não terminou: permita que o mesmo URI seja processado de novo
                        // depois que a gravação liberar a prioridade.
                        seenUris.remove(uriKey)
                        saveRunning(app, area, "Importação pausada para priorizar a gravação • $done/$total processado(s)", done, total, imported, skipped, failureCount, preserveCancel = true)
                        onProgress()
                        continue@importLoop
                    }
                }
            }

            done = itemNumber
            val displayTotal = maxOf(total, snapshot(app, area).total, done)
            commitProcessedState(app, area, progressText(done, displayTotal, imported, skipped, failureCount), done, displayTotal, imported, skipped, failureCount)
            VaultImportQueueStore.setNextIndex(app, area, done)
            onProgress()
        }
    }

    fun failPending(context: Context, area: String, error: Throwable): ProcessingOutcome {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putString(key(area, KEY_MESSAGE), "Importação pausada por erro • fila preservada")
            .putBoolean(key(area, KEY_RESULT_PENDING), true)
            .putString(key(area, KEY_RESULT_MESSAGE), "A fila foi preservada para nova tentativa. Erro: ${error.message ?: error.javaClass.simpleName}")
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
        return ProcessingOutcome.FAILED
    }

    fun pauseForSystemLimit(context: Context, area: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_SYSTEM_PAUSE_REQUESTED), true)
            .putString(key(area, KEY_MESSAGE), "Pausando pelo limite do Android • fila será preservada")
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .apply()
    }

    private fun markSystemPaused(context: Context, area: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putBoolean(key(area, KEY_SYSTEM_PAUSE_REQUESTED), false)
            .putString(key(area, KEY_MESSAGE), "Importação pausada pelo Android • fila preservada para retomar")
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .commit()
    }

    private fun awaitRecordingPriority(
        context: Context,
        area: String,
        done: Int,
        total: Int,
        imported: Int,
        skipped: Int,
        failures: Int,
        onProgress: () -> Unit
    ): Boolean {
        var lastStatusElapsed = 0L
        while (shouldYieldToActiveCapture(context)) {
            if (isCancellationRequested(context, area) || isSystemPauseRequested(context, area)) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatusElapsed >= WAIT_STATUS_REFRESH_MS) {
                lastStatusElapsed = now
                saveRunning(context, area, "Importação aguardando a gravação • $done/$total processado(s) • retoma automaticamente", done, total, imported, skipped, failures, preserveCancel = true)
                onProgress()
            }
            SystemClock.sleep(CAPTURE_PRIORITY_POLL_MS)
        }
        return !isCancellationRequested(context, area) && !isSystemPauseRequested(context, area)
    }

    private fun shouldYieldToActiveCapture(context: Context): Boolean {
        if (!VaultStartupCoordinator.isCapturePriorityActive(context)) return false
        val state = CaptureStateStore.sessionState(context)
        // suspendForCapture() é acionado antes de abrir a câmera. Nessa pequena janela o
        // estado ainda pode aparecer IDLE; mesmo assim a importação deve liberar disco/CPU
        // imediatamente para o pipeline de 60 FPS.
        if (!state.phase.busy) return true
        val now = SystemClock.elapsedRealtime()
        val recentHeartbeat = state.heartbeatElapsedMs in 1L..now && now - state.heartbeatElapsedMs <= CAPTURE_HEARTBEAT_FRESH_MS
        val recentlyStarted = state.startedAtElapsedMs in 1L..now && now - state.startedAtElapsedMs <= CAPTURE_START_GRACE_MS
        return recentHeartbeat || recentlyStarted
    }

    private fun importOne(context: Context, area: String, uri: Uri, source: VaultImportUtils.SourceInfo, shouldCancel: () -> Boolean, onProgress: (Long, Long) -> Unit): VaultRepository.ImportedMedia = when (area) {
        VaultAreaId.SECONDARY -> SecondaryVaultRepository.importFromUriVerified(context, uri, source.displayName, source.mime, onProgress, shouldCancel)
        VaultAreaId.TERTIARY -> TertiaryVaultRepository.importFromUriVerified(context, uri, source.displayName, source.mime, onProgress, shouldCancel)
        else -> VaultRepository.importFromUriVerified(context, uri, source.displayName, source.mime, onProgress, shouldCancel)
    }

    private fun processItemWithWatchdog(
        context: Context,
        area: String,
        uri: Uri,
        itemNumber: Int,
        total: Int,
        imported: Int,
        skipped: Int,
        failureCount: Int,
        done: Int,
        keepDuplicates: Boolean,
        onCopyProgress: (Long, Long) -> Unit
    ): ItemResult {
        val fallbackName = VaultImportUtils.fallbackDisplayName(uri)
        val cancelToken = AtomicBoolean(false)
        val heartbeat = AtomicLong(SystemClock.elapsedRealtime())
        val stage = AtomicReference(ItemStage.VERIFYING)
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SteadyVault-ImportItem-$area-$itemNumber").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        val future = worker.submit<ItemResult> {
            heartbeat.set(SystemClock.elapsedRealtime())
            ensureItemActive(cancelToken)
            val source = VaultImportUtils.sourceInfo(context, uri)
            heartbeat.set(SystemClock.elapsedRealtime())
            ensureItemActive(cancelToken)
            require(VaultMediaFormats.isSupported(source.displayName, source.mime)) { "Formato não compatível" }
            // Não decide duplicidade por nome, tamanho, URI ou histórico da origem.
            // Em "Ignorar repetidos", somente o SHA-256 calculado durante a cópia
            // pode confirmar que o conteúdo já existe no cofre.
            stage.set(ItemStage.COPYING)
            heartbeat.set(SystemClock.elapsedRealtime())
            val importedMedia = importOne(context, area, uri, source, {
                cancelToken.get() || Thread.currentThread().isInterrupted || shouldYieldToActiveCapture(context)
            }) { copied, expected ->
                if (expected > 0L && copied >= expected) stage.set(ItemStage.FINALIZING)
                heartbeat.set(SystemClock.elapsedRealtime())
                onCopyProgress(copied, expected)
            }
            stage.set(ItemStage.FINALIZING)
            heartbeat.set(SystemClock.elapsedRealtime())
            ensureItemActive(cancelToken)
            val item = importedMedia.item
            if (!keepDuplicates) {
                val duplicate = VaultImportDedupStore.findContentDuplicate(context, area, importedMedia.contentSha256, item.file.length(), directoryForArea(context, area), item.file) { heartbeat.set(SystemClock.elapsedRealtime()) }
                if (duplicate != null) {
                    discardImported(context, area, item)
                    rememberImported(context, area, source, duplicate, importedMedia.contentSha256)
                    heartbeat.set(SystemClock.elapsedRealtime())
                    return@submit ItemResult(ItemStatus.SKIPPED, source.displayName)
                }
            }
            rememberImported(context, area, source, item.file, importedMedia.contentSha256)
            VaultImportPostProcessor.enqueue(context, item)
            heartbeat.set(SystemClock.elapsedRealtime())
            ItemResult(ItemStatus.IMPORTED, source.displayName)
        }
        try {
            while (true) {
                if (isCancellationRequested(context, area) || isSystemPauseRequested(context, area)) {
                    cancelToken.set(true)
                    future.cancel(true)
                    return ItemResult(ItemStatus.CANCELLED, fallbackName)
                }
                if (shouldYieldToActiveCapture(context)) {
                    cancelToken.set(true)
                    future.cancel(true)
                    AppLogRepository.info(context, "import", "$area $itemNumber/$total pausado para liberar I/O e CPU para a gravação")
                    return ItemResult(ItemStatus.PAUSED_FOR_CAPTURE, fallbackName)
                }
                try {
                    return future.get(ITEM_WATCHDOG_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val currentStage = stage.get()
                    val idleMs = SystemClock.elapsedRealtime() - heartbeat.get()
                    if (idleMs >= currentStage.stallTimeoutMs) {
                        cancelToken.set(true)
                        future.cancel(true)
                        val seconds = currentStage.stallTimeoutMs / 1000L
                        val message = "Sem progresso na ${currentStage.label} por ${seconds}s; item ignorado para a fila continuar"
                        AppLogRepository.warn(context, "import", "$area $itemNumber/$total • $fallbackName • $message")
                        saveRunning(context, area, "Ignorando $itemNumber/$total após travamento na ${currentStage.label}…", done, total, imported, skipped, failureCount, preserveCancel = true)
                        return ItemResult(ItemStatus.FAILED, fallbackName, message)
                    }
                } catch (error: ExecutionException) {
                    val cause = error.cause ?: error
                    if (shouldYieldToActiveCapture(context)) return ItemResult(ItemStatus.PAUSED_FOR_CAPTURE, fallbackName)
                    if (cancelToken.get() || isCancellationRequested(context, area) || isSystemPauseRequested(context, area)) return ItemResult(ItemStatus.CANCELLED, fallbackName)
                    return ItemResult(ItemStatus.FAILED, fallbackName, cause.message.orEmpty().ifBlank { cause.javaClass.simpleName })
                } catch (_: CancellationException) {
                    return when {
                        shouldYieldToActiveCapture(context) -> ItemResult(ItemStatus.PAUSED_FOR_CAPTURE, fallbackName)
                        cancelToken.get() -> ItemResult(ItemStatus.CANCELLED, fallbackName)
                        else -> ItemResult(ItemStatus.FAILED, fallbackName, "Processamento cancelado inesperadamente")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelToken.set(true)
                    future.cancel(true)
                    return ItemResult(ItemStatus.CANCELLED, fallbackName)
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    private fun ensureItemActive(cancelToken: AtomicBoolean) {
        if (cancelToken.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Importação cancelada")
    }

    private fun releasePermissionsAsync(context: Context, job: VaultImportQueueStore.Job, uris: List<Uri>) {
        Thread({ runCatching { releasePermissions(context.applicationContext, job, uris) }
            .onFailure { AppLogRepository.warn(context, "import", "Não foi possível liberar todas as permissões da importação: ${it.message.orEmpty()}") } },
            "SteadyVault-ImportPermissionCleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun initialize(context: Context, area: String, message: String, total: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), true)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), message)
            .putInt(key(area, KEY_DONE), 0)
            .putInt(key(area, KEY_TOTAL), total)
            .putInt(key(area, KEY_IMPORTED), 0)
            .putInt(key(area, KEY_SKIPPED), 0)
            .putInt(key(area, KEY_FAILURES), 0)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putBoolean(key(area, KEY_SYSTEM_PAUSE_REQUESTED), false)
            .putBoolean(key(area, KEY_RESULT_PENDING), false)
            .remove(key(area, KEY_RESULT_MESSAGE))
            .commit()
    }

    private fun finish(
        context: Context,
        area: String,
        imported: Int,
        skipped: Int,
        done: Int,
        total: Int,
        failures: List<VaultImportSummary.Failure>,
        cancelled: Boolean,
        failureCount: Int = failures.size
    ) {
        val finalTotal = maxOf(total, done)
        val result = if (!cancelled) {
            buildString {
                append("Processados: ").append(done).append('/').append(finalTotal).append(".\n")
                append("Importados: ").append(imported).append(".\n")
                append("Repetidos ignorados: ").append(skipped).append(".\n")
                append("Falhas: ").append(failureCount).append('.')
                if (failures.isNotEmpty()) append("\n\n").append(VaultImportSummary.popupMessage(imported, finalTotal, failures, skipped, failureCount))
            }
        } else ""
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), if (cancelled) "Importação cancelada • $done/$finalTotal processado(s) • $imported adicionado(s)" else "Importação concluída • $done/$finalTotal • OK $imported • repetidos $skipped • falhas $failureCount")
            .putInt(key(area, KEY_DONE), done)
            .putInt(key(area, KEY_TOTAL), finalTotal)
            .putInt(key(area, KEY_IMPORTED), imported)
            .putInt(key(area, KEY_SKIPPED), skipped)
            .putInt(key(area, KEY_FAILURES), failureCount)
            .putBoolean(key(area, KEY_RESULT_PENDING), !cancelled)
            .putString(key(area, KEY_RESULT_MESSAGE), result)
            .commit()
    }

    private fun finishEmpty(context: Context, area: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), "Nenhuma foto ou vídeo compatível encontrado")
            .putInt(key(area, KEY_DONE), 0)
            .putInt(key(area, KEY_TOTAL), 0)
            .putInt(key(area, KEY_IMPORTED), 0)
            .putInt(key(area, KEY_SKIPPED), 0)
            .putInt(key(area, KEY_FAILURES), 0)
            .putBoolean(key(area, KEY_RESULT_PENDING), true)
            .putString(key(area, KEY_RESULT_MESSAGE), "Nenhuma foto ou vídeo compatível foi encontrada na seleção.")
            .commit()
    }


    private fun commitProcessedState(context: Context, area: String, message: String, done: Int, total: Int, imported: Int, skipped: Int, failures: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stableDone = done.coerceAtLeast(prefs.getInt(key(area, KEY_DONE), 0))
        val stableTotal = maxOf(total, stableDone, prefs.getInt(key(area, KEY_TOTAL), 0))
        prefs.edit()
            .putBoolean(key(area, KEY_RUNNING), true)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), message)
            .putInt(key(area, KEY_DONE), stableDone)
            .putInt(key(area, KEY_TOTAL), stableTotal)
            .putInt(key(area, KEY_IMPORTED), maxOf(imported, prefs.getInt(key(area, KEY_IMPORTED), 0)))
            .putInt(key(area, KEY_SKIPPED), maxOf(skipped, prefs.getInt(key(area, KEY_SKIPPED), 0)))
            .putInt(key(area, KEY_FAILURES), maxOf(failures, prefs.getInt(key(area, KEY_FAILURES), 0)))
            .commit()
    }

    private fun saveRunning(context: Context, area: String, message: String, done: Int, total: Int, imported: Int, skipped: Int, failures: Int, preserveCancel: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stableDone = done.coerceAtLeast(prefs.getInt(key(area, KEY_DONE), 0))
        val stableTotal = maxOf(total, stableDone, prefs.getInt(key(area, KEY_TOTAL), 0))
        val editor = prefs.edit()
            .putBoolean(key(area, KEY_RUNNING), true)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), message)
            .putInt(key(area, KEY_DONE), stableDone)
            .putInt(key(area, KEY_TOTAL), stableTotal)
            .putInt(key(area, KEY_IMPORTED), maxOf(imported, prefs.getInt(key(area, KEY_IMPORTED), 0)))
            .putInt(key(area, KEY_SKIPPED), maxOf(skipped, prefs.getInt(key(area, KEY_SKIPPED), 0)))
            .putInt(key(area, KEY_FAILURES), maxOf(failures, prefs.getInt(key(area, KEY_FAILURES), 0)))
        if (!preserveCancel) editor.putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
        editor.apply()
    }

    private fun clearRunning(context: Context, area: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), "")
            .putInt(key(area, KEY_DONE), 0)
            .putInt(key(area, KEY_TOTAL), 0)
            .putInt(key(area, KEY_IMPORTED), 0)
            .putInt(key(area, KEY_SKIPPED), 0)
            .putInt(key(area, KEY_FAILURES), 0)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putBoolean(key(area, KEY_SYSTEM_PAUSE_REQUESTED), false)
            .apply()
    }

    private fun rememberImported(context: Context, area: String, source: VaultImportUtils.SourceInfo, file: File, contentSha256: String = "") {
        VaultImportDedupStore.remember(context, area, sourceKey(source), file, contentSha256)
    }

    private fun discardImported(context: Context, area: String, item: VaultRepository.MediaItem) {
        check(!item.file.exists() || item.file.delete()) { "Duplicado detectado, mas a cópia temporária não pôde ser removida" }
        when (area) {
            VaultAreaId.SECONDARY, VaultAreaId.TERTIARY -> PrivateVaultMediaIndex.remove(context, area, item.file)
            else -> VaultMediaIndex.remove(context, item.file)
        }
    }

    private fun releasePermissions(context: Context, job: VaultImportQueueStore.Job, uris: List<Uri>) {
        if (job.isTree) job.treeUri?.let { VaultImportUtils.releaseTreeReadPermission(context, it) }
        VaultImportUtils.releaseDocumentReadPermissions(context, uris)
    }

    private fun directoryForArea(context: Context, area: String): File = when (area) {
        VaultAreaId.SECONDARY -> SecondaryVaultRepository.directory(context)
        VaultAreaId.TERTIARY -> TertiaryVaultRepository.directory(context)
        else -> VaultRepository.primaryDirectory(context)
    }

    private fun sourceKey(source: VaultImportUtils.SourceInfo): String {
        val raw = buildString {
            append(source.uri.toString()).append('\n')
            append(source.displayName).append('\n')
            append(source.sizeBytes).append('\n')
            append(source.mime)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun progressText(done: Int, total: Int, imported: Int, skipped: Int, failures: Int): String =
        "Importando $done/$total • OK $imported • repetidos $skipped • falhas $failures"

    private fun formatTransferredBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun key(area: String, name: String): String = "$area.$name"

}
