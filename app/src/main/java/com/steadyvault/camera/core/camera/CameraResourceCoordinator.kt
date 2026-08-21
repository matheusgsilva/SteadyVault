package com.steadyvault.camera.core.camera

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordena o único recurso físico de câmera entre preview, foto e vídeo.
 * O dono de captura é reservado antes de liberar o preview para impedir corridas
 * entre widgets, tela principal, preview e serviços em foreground.
 */
object CameraResourceCoordinator {
    enum class Owner { VIDEO, PHOTO }

    private data class CaptureLease(val owner: Owner, val token: Any)

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureLease: CaptureLease? = null
    private var previewToken: Any? = null
    private var previewReleaser: (((() -> Unit)) -> Unit)? = null

    fun registerPreview(token: Any, releaser: ((() -> Unit)) -> Unit): Boolean = synchronized(lock) {
        if (captureLease != null) return@synchronized false
        previewToken = token
        previewReleaser = releaser
        true
    }

    fun unregisterPreview(token: Any) {
        synchronized(lock) {
            if (previewToken === token) {
                previewToken = null
                previewReleaser = null
            }
        }
    }

    fun canStartPreview(): Boolean = synchronized(lock) { captureLease == null }

    fun requestCapture(
        owner: Owner,
        token: Any,
        onGranted: () -> Unit,
        onDenied: (String) -> Unit
    ) {
        val releaser: (((() -> Unit)) -> Unit)?
        val denial: String?
        synchronized(lock) {
            val current = captureLease
            denial = if (current != null && current.token !== token) {
                "a câmera já está sendo usada por ${ownerLabel(current.owner)}"
            } else {
                null
            }
            if (denial == null) {
                captureLease = CaptureLease(owner, token)
                releaser = previewReleaser
                previewToken = null
                previewReleaser = null
            } else {
                releaser = null
            }
        }
        if (denial != null) {
            onDenied(denial)
            return
        }

        if (releaser == null) {
            onGranted()
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val grant = {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                onGranted()
            }
        }
        timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                releaseCapture(owner, token)
                onDenied("o preview não liberou a câmera com segurança")
            }
        }
        mainHandler.postDelayed(timeout, PREVIEW_RELEASE_TIMEOUT_MS)
        runCatching { releaser(grant) }.onFailure {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                releaseCapture(owner, token)
                onDenied(it.message ?: "não foi possível liberar o preview")
            }
        }
    }

    fun releaseCapture(owner: Owner, token: Any) {
        synchronized(lock) {
            val current = captureLease
            if (current?.owner == owner && current.token === token) captureLease = null
        }
    }

    private fun ownerLabel(owner: Owner): String = when (owner) {
        Owner.VIDEO -> "uma gravação de vídeo"
        Owner.PHOTO -> "uma captura de foto"
    }

    private const val PREVIEW_RELEASE_TIMEOUT_MS = 2_500L
}
