package com.steadyvault.camera.ui.vault

import android.content.Context
import android.os.Process
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Completa duração/resolução e deixa a miniatura pronta sem bloquear a cópia da fila.
 * O mesmo pipeline é usado nos três cofres.
 */
internal object VaultImportPostProcessor {
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val executor = Executors.newScheduledThreadPool(2) { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-ImportPostProcess").apply { isDaemon = true }
    }

    fun enqueue(context: Context, item: VaultRepository.MediaItem) {
        val app = context.applicationContext
        val key = "${item.file.absolutePath}|${item.file.length()}|${item.file.lastModified()}"
        if (!pending.add(key)) return
        schedule(app, item, key, 0)
    }

    private fun schedule(context: Context, item: VaultRepository.MediaItem, key: String, attempt: Int) {
        runCatching {
            executor.schedule(task@{
                if (!item.file.isFile) {
                    pending.remove(key)
                    return@task
                }
                if (VaultStartupCoordinator.isCapturePriorityActive(context)) {
                    if (attempt < MAX_CAPTURE_RETRIES) schedule(context, item, key, attempt + 1) else pending.remove(key)
                    return@task
                }
                try {
                    val detailed = runCatching { VaultRepository.loadMediaDetails(context, item) }.getOrElse { item }
                    if (!VaultStartupCoordinator.isCapturePriorityActive(context)) {
                        runCatching {
                            val thumb = MediaThumbnailRepository.load(context, detailed.file, detailed.video, 384)
                            if (!thumb.isRecycled) thumb.recycle()
                        }
                    }
                } catch (error: Throwable) {
                    AppLogRepository.warn(context, "import", "Pós-processamento de ${item.name} falhou sem afetar a importação: ${error.message.orEmpty()}")
                } finally {
                    pending.remove(key)
                }
            }, if (attempt == 0) 0L else CAPTURE_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
        }.onFailure { pending.remove(key) }
    }

    private const val MAX_CAPTURE_RETRIES = 20
    private const val CAPTURE_RETRY_DELAY_SECONDS = 3L
}
