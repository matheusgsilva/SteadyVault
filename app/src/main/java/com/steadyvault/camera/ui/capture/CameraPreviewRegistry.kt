package com.steadyvault.camera.ui.capture

import android.view.Surface

/** Ponte temporária entre a tela de captura e a sessão Camera2 do serviço. */
object CameraPreviewRegistry {
    data class Target(val surface: Surface, val width: Int, val height: Int)
    private var target: Target? = null

    @Synchronized
    fun register(surface: Surface, width: Int, height: Int) {
        if (!surface.isValid) return
        target = Target(surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    @Synchronized
    fun clear(surface: Surface? = null) {
        if (surface == null || target?.surface === surface) target = null
    }

    @Synchronized
    fun snapshot(): Target? = target?.takeIf { it.surface.isValid }
}
