package com.steadyvault.camera.ui.vault

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.os.Process
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultAreaId
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import java.io.File
import java.security.MessageDigest


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
    private const val STALE_RUNNING_MS = 6L * 60L * 60L * 1000L
    private const val CAPTURE_PRIORITY_POLL_MS = 500L
    private const val MAX_FAILURE_SAMPLES = 20

    enum class ProcessingOutcome { COMPLETED, CANCELLED, FAILED, PAUSED_BY_SYSTEM }

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
        if (running && updatedAt > 0L && System.currentTimeMillis() - updatedAt > STALE_RUNNING_MS && !VaultImportQueueStore.hasPending(app, area)) {
            clearRunning(app, area)
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

    fun start(context: Context, area: String, uris: List<Uri>, label: String) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        VaultImportQueueStore.createDocuments(app, area, uris, label)
        initialize(app, area, "Importando 0/${uris.size} $label…", uris.size)
        VaultImportService.start(app, area)
        AppLogRepository.info(app, "import", "Fila persistente criada para $area com ${uris.size} arquivo(s)")
    }

    fun startTree(context: Context, area: String, treeUri: Uri, label: String) {
        val app = context.applicationContext
        VaultImportQueueStore.createTree(app, area, treeUri, label)
        initialize(app, area, "Lendo pasta…", 0)
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
            if (running && updatedAt > 0L && System.currentTimeMillis() - updatedAt > STALE_RUNNING_MS) {
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
            val uris = runCatching {
                VaultImportUtils.mediaUrisFromTree(app, treeUri) { isCancellationRequested(app, area) || isSystemPauseRequested(app, area) }
            }.getOrElse { return failPending(app, area, it) }
            if (isSystemPauseRequested(app, area)) {
                markSystemPaused(app, area)
                onProgress()
                return ProcessingOutcome.PAUSED_BY_SYSTEM
            }
            if (isCancellationRequested(app, area)) {
                finish(app, area, 0, 0, 0, uris.size, emptyList(), cancelled = true)
                releasePermissions(app, job, uris)
                VaultImportQueueStore.clear(app, area)
                onProgress()
                return ProcessingOutcome.CANCELLED
            }
            if (uris.isEmpty()) {
                finishEmpty(app, area)
                releasePermissions(app, job, uris)
                VaultImportQueueStore.clear(app, area)
                onProgress()
                return ProcessingOutcome.COMPLETED
            }
            VaultImportQueueStore.prepareTreeQueue(app, area, uris)
            job = VaultImportQueueStore.job(app, area) ?: return ProcessingOutcome.FAILED
            saveRunning(app, area, "Importando 0/${uris.size} ${job.label}…", 0, uris.size, 0, 0, 0, preserveCancel = true)
            onProgress()
        }

        val uris = VaultImportQueueStore.readQueue(app, area)
        if (uris.isEmpty()) {
            finishEmpty(app, area)
            releasePermissions(app, job, uris)
            VaultImportQueueStore.clear(app, area)
            onProgress()
            return ProcessingOutcome.COMPLETED
        }

        val previous = snapshot(app, area)
        var imported = previous.imported.coerceAtLeast(0)
        var skipped = previous.skipped.coerceAtLeast(0)
        var done = job.nextIndex.coerceIn(0, uris.size)
        var failureCount = previous.failures.coerceAtLeast(0)
        val failures = mutableListOf<VaultImportSummary.Failure>()
        saveRunning(app, area, progressText(done, uris.size, imported, skipped, failureCount), done, uris.size, imported, skipped, failureCount, preserveCancel = true)
        onProgress()

        for (index in done until uris.size) {
            if (isCancellationRequested(app, area) || isSystemPauseRequested(app, area) || !awaitRecordingPriority(app, area)) break
            val uri = uris[index]
            val source = VaultImportUtils.sourceInfo(app, uri)
            runCatching {
                require(VaultMediaFormats.isSupported(source.displayName, source.mime)) { "Formato não compatível" }
                if (alreadyImported(app, area, source)) {
                    skipped++
                } else {
                    val item = importOne(app, area, uri)
                    rememberImported(app, area, source, item.file)
                    imported++
                }
            }.onFailure { error ->
                failureCount++
                if (failures.size < MAX_FAILURE_SAMPLES) failures += VaultImportSummary.Failure(source.displayName, error.message.orEmpty())
                AppLogRepository.error(app, "import", "Falha em ${source.displayName}", error)
            }
            done = index + 1
            VaultImportQueueStore.setNextIndex(app, area, done)
            saveRunning(app, area, progressText(done, uris.size, imported, skipped, failureCount), done, uris.size, imported, skipped, failureCount, preserveCancel = true)
            onProgress()
            if (isCancellationRequested(app, area) || isSystemPauseRequested(app, area)) break
        }

        if (isSystemPauseRequested(app, area)) {
            markSystemPaused(app, area)
            AppLogRepository.warn(app, "import", "Fila $area pausada pelo limite do Android em $done/${uris.size}")
            onProgress()
            return ProcessingOutcome.PAUSED_BY_SYSTEM
        }

        val cancelled = isCancellationRequested(app, area)
        finish(app, area, imported, skipped, done, uris.size, failures, cancelled, failureCount)
        releasePermissions(app, job, uris)
        VaultImportQueueStore.clear(app, area)
        AppLogRepository.info(app, "import", if (cancelled) "Importação cancelada em $done/${uris.size}" else "Importação concluída em $area")
        onProgress()
        return if (cancelled) ProcessingOutcome.CANCELLED else ProcessingOutcome.COMPLETED
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
            .apply()
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
            .apply()
    }

    private fun awaitRecordingPriority(context: Context, area: String): Boolean {
        while (VaultStartupCoordinator.isCapturePriorityActive(context)) {
            if (isCancellationRequested(context, area) || isSystemPauseRequested(context, area)) return false
            SystemClock.sleep(CAPTURE_PRIORITY_POLL_MS)
        }
        return !isCancellationRequested(context, area) && !isSystemPauseRequested(context, area)
    }

    private fun importOne(context: Context, area: String, uri: Uri): VaultRepository.MediaItem = when (area) {
        VaultAreaId.SECONDARY -> SecondaryVaultRepository.importFromUri(context, uri)
        VaultAreaId.TERTIARY -> TertiaryVaultRepository.importFromUri(context, uri)
        else -> VaultRepository.importFromUri(context, uri)
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
            .apply()
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
        val result = if (!cancelled && failures.isNotEmpty()) VaultImportSummary.popupMessage(imported, total, failures, skipped, failureCount) else ""
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(area, KEY_RUNNING), false)
            .putBoolean(key(area, KEY_CANCEL_REQUESTED), false)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), if (cancelled) "Importação cancelada • $done/$total processado(s) • $imported adicionado(s)" else finishedText(imported, skipped, failureCount))
            .putInt(key(area, KEY_DONE), done)
            .putInt(key(area, KEY_TOTAL), total)
            .putInt(key(area, KEY_IMPORTED), imported)
            .putInt(key(area, KEY_SKIPPED), skipped)
            .putInt(key(area, KEY_FAILURES), failureCount)
            .putBoolean(key(area, KEY_RESULT_PENDING), !cancelled && failureCount > 0)
            .putString(key(area, KEY_RESULT_MESSAGE), result.ifBlank { if (!cancelled && failureCount > 0) "$failureCount arquivo(s) falharam. Consulte os logs para detalhes." else "" })
            .apply()
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
            .apply()
    }

    private fun saveRunning(context: Context, area: String, message: String, done: Int, total: Int, imported: Int, skipped: Int, failures: Int, preserveCancel: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putBoolean(key(area, KEY_RUNNING), true)
            .putLong(key(area, KEY_UPDATED_AT), System.currentTimeMillis())
            .putString(key(area, KEY_MESSAGE), message)
            .putInt(key(area, KEY_DONE), done)
            .putInt(key(area, KEY_TOTAL), total)
            .putInt(key(area, KEY_IMPORTED), imported)
            .putInt(key(area, KEY_SKIPPED), skipped)
            .putInt(key(area, KEY_FAILURES), failures)
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

    private fun alreadyImported(context: Context, area: String, source: VaultImportUtils.SourceInfo): Boolean {
        val directory = directoryForArea(context, area)
        if (source.sizeBytes > 0L && VaultMediaFormats.hasEquivalentFile(directory, source.displayName, source.sizeBytes, source.mime)) return true
        return VaultImportDedupStore.containsValid(context, area, sourceKey(source), directory)
    }

    private fun rememberImported(context: Context, area: String, source: VaultImportUtils.SourceInfo, file: File) {
        VaultImportDedupStore.remember(context, area, sourceKey(source), file)
    }

    private fun releasePermissions(context: Context, job: VaultImportQueueStore.Job, uris: List<Uri>) {
        if (job.isTree) job.treeUri?.let { VaultImportUtils.releaseTreeReadPermission(context, it) }
        else VaultImportUtils.releaseDocumentReadPermissions(context, uris)
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

    private fun finishedText(imported: Int, skipped: Int, failures: Int): String = when {
        failures > 0 -> "Importação finalizada • OK $imported • repetidos $skipped • falhas $failures"
        skipped > 0 -> "Importação finalizada • $imported nova(s) • $skipped repetida(s) ignorada(s)"
        else -> "$imported mídia(s) adicionada(s)"
    }

    private fun key(area: String, name: String): String = "$area.$name"

}
