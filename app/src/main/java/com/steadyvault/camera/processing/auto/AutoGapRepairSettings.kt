package com.steadyvault.camera.processing.auto

import android.content.Context
import com.steadyvault.camera.processing.model.FrameRepairMode

/** Configuração global do reparo automático pós-gravação. */
object AutoGapRepairSettings {
    data class Snapshot(
        val enabled: Boolean,
        val mode: FrameRepairMode,
        val maxInterpolatedFramesPerGap: Int,
        val aiAssisted: Boolean
    )

    private const val PREFS = "steadyvault_auto_gap_repair"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_MAX_FRAMES = "max_frames"
    private const val KEY_AI_ASSISTED = "ai_assisted"

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            mode = FrameRepairMode.from(
                prefs.getString(KEY_MODE, FrameRepairMode.ADAPTIVE_BLEND.name)
            ).takeIf {
                it == FrameRepairMode.ADAPTIVE_BLEND ||
                    it == FrameRepairMode.FILL_MISSING_FRAMES ||
                    it == FrameRepairMode.SMOOTH_TIMELINE
            } ?: FrameRepairMode.ADAPTIVE_BLEND,
            maxInterpolatedFramesPerGap = prefs.getInt(KEY_MAX_FRAMES, 4).coerceIn(1, 16),
            aiAssisted = prefs.getBoolean(KEY_AI_ASSISTED, false)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setMode(context: Context, mode: FrameRepairMode) {
        val safe = mode.takeIf {
            it == FrameRepairMode.ADAPTIVE_BLEND ||
                it == FrameRepairMode.FILL_MISSING_FRAMES ||
                it == FrameRepairMode.SMOOTH_TIMELINE
        } ?: FrameRepairMode.ADAPTIVE_BLEND
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, safe.name).apply()
    }

    fun setMaxInterpolatedFramesPerGap(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_FRAMES, value.coerceIn(1, 16)).apply()
    }

    fun setAiAssisted(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AI_ASSISTED, enabled).apply()
    }
}
