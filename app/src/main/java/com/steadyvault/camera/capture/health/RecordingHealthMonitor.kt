package com.steadyvault.camera.capture.health

import android.os.Handler

class RecordingHealthMonitor(
    private val handler: Handler,
    private val intervalMs: Long,
    private val shouldRun: () -> Boolean,
    private val onTick: () -> Unit
) {
    private var running = false
    private val runnable = object : Runnable {
        override fun run() {
            if (!running || !shouldRun()) return
            onTick()
            if (running && shouldRun()) handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, intervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }
}
