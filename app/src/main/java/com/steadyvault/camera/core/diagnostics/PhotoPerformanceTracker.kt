package com.steadyvault.camera.core.diagnostics

import android.content.Context
import android.os.SystemClock

/** Cronometragem leve para localizar a origem da lentidão de foto sem alterar a captura. */
object PhotoPerformanceTracker {
    private const val PREFS = "steadyvault_photo_performance"
    private val lock = Any()
    private var startedAt = 0L
    private var captureRequestedAt = 0L
    private var imageAvailableAt = 0L

    data class Report(
        val measuredAtMillis: Long,
        val prepareMs: Long,
        val sensorToJpegMs: Long,
        val writeMs: Long,
        val totalMs: Long,
        val width: Int,
        val height: Int
    ) {
        fun displayText(): String = buildString {
            append(width).append('×').append(height).append(" • total ").append(totalMs).append(" ms")
            append('\n').append("Preparação: ").append(prepareMs).append(" ms")
            append(" • câmera/JPEG: ").append(sensorToJpegMs).append(" ms")
            append(" • gravação: ").append(writeMs).append(" ms")
            val bottleneck = maxOf(prepareMs, sensorToJpegMs, writeMs)
            append('\n').append("Maior etapa: ").append(
                when (bottleneck) {
                    prepareMs -> "preparação/foco"
                    sensorToJpegMs -> "sensor/processamento JPEG"
                    else -> "gravação no cofre"
                }
            )
        }
    }

    fun begin() = synchronized(lock) {
        startedAt = SystemClock.elapsedRealtime()
        captureRequestedAt = 0L
        imageAvailableAt = 0L
    }

    fun markCaptureRequest() = synchronized(lock) {
        if (startedAt > 0L && captureRequestedAt == 0L) captureRequestedAt = SystemClock.elapsedRealtime()
    }

    fun markImageAvailable() = synchronized(lock) {
        if (startedAt > 0L && imageAvailableAt == 0L) imageAvailableAt = SystemClock.elapsedRealtime()
    }

    fun finish(context: Context, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        val report = synchronized(lock) {
            if (startedAt <= 0L) return
            val requestAt = captureRequestedAt.takeIf { it >= startedAt } ?: startedAt
            val imageAt = imageAvailableAt.takeIf { it >= requestAt } ?: now
            Report(
                measuredAtMillis = System.currentTimeMillis(),
                prepareMs = (requestAt - startedAt).coerceAtLeast(0L),
                sensorToJpegMs = (imageAt - requestAt).coerceAtLeast(0L),
                writeMs = (now - imageAt).coerceAtLeast(0L),
                totalMs = (now - startedAt).coerceAtLeast(0L),
                width = width,
                height = height
            ).also {
                startedAt = 0L
                captureRequestedAt = 0L
                imageAvailableAt = 0L
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("measured_at", report.measuredAtMillis)
            .putLong("prepare_ms", report.prepareMs)
            .putLong("camera_ms", report.sensorToJpegMs)
            .putLong("write_ms", report.writeMs)
            .putLong("total_ms", report.totalMs)
            .putInt("width", report.width)
            .putInt("height", report.height)
            .apply()
    }

    fun lastReport(context: Context): Report? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val measured = prefs.getLong("measured_at", 0L)
        if (measured <= 0L) return null
        return Report(
            measuredAtMillis = measured,
            prepareMs = prefs.getLong("prepare_ms", 0L),
            sensorToJpegMs = prefs.getLong("camera_ms", 0L),
            writeMs = prefs.getLong("write_ms", 0L),
            totalMs = prefs.getLong("total_ms", 0L),
            width = prefs.getInt("width", 0),
            height = prefs.getInt("height", 0)
        )
    }
}
