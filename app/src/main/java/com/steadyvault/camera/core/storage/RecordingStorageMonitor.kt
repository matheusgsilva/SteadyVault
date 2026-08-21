package com.steadyvault.camera.core.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifica o espaço durante uma gravação sem executar I/O na thread da câmera
 * ou no monitor de cadência. Cada início recebe uma geração própria, impedindo
 * que uma verificação antiga finalize uma gravação posterior.
 */
class RecordingStorageMonitor(
    context: Context,
    threadName: String,
    private val isRecording: () -> Boolean,
    private val onCritical: (RecordingStorageGuard.Result) -> Unit
) : Closeable {
    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(
                {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    task.run()
                },
                threadName
            )
        }

    fun start(videoBitrateBps: Long, audioBitrateBps: Long) {
        val token = generation.incrementAndGet()
        schedule(token, videoBitrateBps, audioBitrateBps)
    }

    fun stop() {
        generation.incrementAndGet()
    }

    private fun schedule(token: Int, videoBitrateBps: Long, audioBitrateBps: Long) {
        runCatching {
            executor.schedule(
                monitor@{
                    if (token != generation.get()) return@monitor
                    if (!isRecording()) {
                        schedule(token, videoBitrateBps, audioBitrateBps)
                        return@monitor
                    }
                    val result = RecordingStorageGuard.checkOngoing(
                        context = app,
                        videoBitrateBps = videoBitrateBps,
                        audioBitrateBps = audioBitrateBps
                    )
                    if (!result.allowed) {
                        mainHandler.post {
                            if (token == generation.get() && isRecording()) onCritical(result)
                        }
                    } else {
                        schedule(token, videoBitrateBps, audioBitrateBps)
                    }
                },
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    companion object {
        private const val CHECK_INTERVAL_SECONDS = 30L
    }
}
