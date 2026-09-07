package com.steadyvault.camera.storage.vault

import android.content.Context
import android.os.SystemClock
import com.steadyvault.camera.core.state.CapturePhase
import com.steadyvault.camera.core.state.CaptureStateStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executa a manutenção inicial em série e com baixa prioridade.
 *
 * Isso evita três varreduras concorrentes disputando disco e CPU justamente
 * quando a câmera ou o player precisam iniciar rapidamente.
 */
object VaultStartupCoordinator {
    private val scheduled = AtomicBoolean(false)
    private val captureOwners = Collections.synchronizedSet(mutableSetOf<Any>())
    @Volatile private var lastCompletedElapsedMs = 0L
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SteadyVault-StartupMaintenance").apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    fun suspendForCapture(owner: Any) {
        captureOwners += owner
    }

    fun resumeAfterCapture(owner: Any) {
        captureOwners -= owner
        // Um load de miniatura que já estava em voo quando a câmera assumiu prioridade
        // pode ter devolvido o placeholder deliberado e a grade pode tê-lo colocado no
        // próprio LRU. Ao terminar a última captura, invalide somente a memória/warm-ups;
        // o cache de miniaturas em disco permanece intacto e a grade recarrega a imagem real.
        if (captureOwners.isEmpty()) {
            MediaThumbnailRepository.prepareForCapture()
        }
    }

    fun runAsync(context: Context) {
        val elapsed = SystemClock.elapsedRealtime()
        if (lastCompletedElapsedMs > 0L && elapsed - lastCompletedElapsedMs < MINIMUM_INTERVAL_MS) return
        if (!scheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching {
            executor.schedule(maintenance@{
                try {
                    if (isCapturePriorityActive(app)) return@maintenance
                    val recovery = RecordingRecoveryRepository.recoverStaleRecordings(app)
                    if (isCapturePriorityActive(app)) return@maintenance
                    VaultRepository.runStartupMaintenance(app)
                    if (isCapturePriorityActive(app)) return@maintenance
                    VaultCleanupRepository.runStartupCleanup(app)
                    val state = CaptureStateStore.sessionState(app)
                    if (state.owner == CaptureStateStore.OWNER_STARTUP_RECOVERY) {
                        val message = if (recovery.recoveredFiles > 0) {
                            "${recovery.recoveredFiles} trecho(s) preservado(s) em Vídeos com erro / recuperados"
                        } else {
                            "Pronto para gravar"
                        }
                        CaptureStateStore.update(app, message, CapturePhase.IDLE)
                    }
                    lastCompletedElapsedMs = SystemClock.elapsedRealtime()
                } finally {
                    scheduled.set(false)
                }
            }, STARTUP_DELAY_MS, TimeUnit.MILLISECONDS)
        }.onFailure {
            scheduled.set(false)
        }
    }

    fun isCapturePriorityActive(context: Context): Boolean {
        if (captureOwners.isNotEmpty()) return true
        val state = CaptureStateStore.sessionState(context)
        return state.phase.busy && state.owner != CaptureStateStore.OWNER_STARTUP_RECOVERY
    }

    private const val MINIMUM_INTERVAL_MS = 5L * 60L * 1_000L
    private const val STARTUP_DELAY_MS = 2_000L
}
