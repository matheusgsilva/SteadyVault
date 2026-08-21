package com.steadyvault.camera.core.settings

import android.content.Context
import com.steadyvault.camera.core.camera.CameraLensCatalog
import kotlin.math.abs

/** Zoom persistente compartilhado por fotos e vídeos rápidos iniciados sem preview. */
object BackgroundRecordingZoom {
    data class CameraSelection(
        val cameraId: String,
        val requestZoomRatio: Float
    )

    val choices: List<Float> = listOf(0.6f, 1f, 3f, 5f)

    fun selected(context: Context): Float {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RATIO, DEFAULT_RATIO)
        return choices.minByOrNull { abs(it - stored) } ?: DEFAULT_RATIO
    }

    fun supported(context: Context): List<Float> {
        val options = CameraLensCatalog.options(context)
        return choices.filter { CameraLensCatalog.optionForShortcut(options, it) != null }
            .ifEmpty { listOf(DEFAULT_RATIO) }
    }

    fun set(context: Context, ratio: Float): Float {
        val normalized = choices.minByOrNull { abs(it - ratio) } ?: DEFAULT_RATIO
        val effective = normalized.takeIf { it in supported(context) } ?: DEFAULT_RATIO
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_RATIO, effective)
            .apply()
        return effective
    }

    fun cycle(context: Context): Float {
        val available = supported(context)
        val current = selected(context)
        val index = available.indexOfFirst { abs(it - current) < 0.05f }
        return set(context, available[(index + 1) % available.size])
    }

    fun cameraSelection(context: Context): CameraSelection? {
        val options = CameraLensCatalog.options(context)
        val desired = selected(context)
        val target = CameraLensCatalog.optionForShortcut(options, desired)
            ?: CameraLensCatalog.optionForShortcut(options, DEFAULT_RATIO)
            ?: return null
        val requestRatio = if (target.logical) {
            desired.coerceIn(target.minZoom, target.maxZoom)
        } else {
            1f
        }
        return CameraSelection(target.id, requestRatio)
    }

    fun label(ratio: Float): String = when {
        abs(ratio - 0.6f) < 0.05f -> "0,6x"
        abs(ratio - 1f) < 0.05f -> "1x"
        abs(ratio - 3f) < 0.05f -> "3x"
        abs(ratio - 5f) < 0.05f -> "5x"
        else -> "1x"
    }

    private const val PREFS = "steadyvault_background_recording_zoom"
    private const val KEY_RATIO = "ratio"
    private const val DEFAULT_RATIO = 1f
}
