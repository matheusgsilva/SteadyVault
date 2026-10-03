package com.steadyvault.camera.processing.auto

import android.content.Context
import com.steadyvault.camera.processing.model.FrameRepairMode

/** Configuração global do reparo automático pós-gravação. */
object AutoGapRepairSettings {
    data class Snapshot(
        val enabled: Boolean,
        val mode: FrameRepairMode,
        val maxInterpolatedFramesPerGap: Int
    )

    private const val PREFS = "steadyvault_auto_gap_repair"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_MAX_FRAMES = "max_frames"
    private const val KEY_SCHEMA = "schema"
    private const val SCHEMA = 7

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = FrameRepairMode.from(
            prefs.getString(KEY_MODE, FrameRepairMode.MOTION_COMPENSATED.name)
        )
        val previousSchema = prefs.getInt(KEY_SCHEMA, 0)
        val migrated = when {
            previousSchema < 4 &&
                (stored == FrameRepairMode.FILL_MISSING_FRAMES ||
                    stored == FrameRepairMode.SMOOTH_TIMELINE ||
                    stored == FrameRepairMode.ADAPTIVE_BLEND) ->
                FrameRepairMode.MOTION_COMPENSATED
            else -> stored
        }
        val safeMode = migrated.takeIf {
            it == FrameRepairMode.MOTION_COMPENSATED ||
                it == FrameRepairMode.QUALITY_LOW_MOTION ||
                it == FrameRepairMode.QUALITY_MEDIUM_MOTION ||
                it == FrameRepairMode.QUALITY_HIGH_MOTION ||
                it == FrameRepairMode.ADAPTIVE_BLEND ||
                it == FrameRepairMode.FILL_MISSING_FRAMES ||
                it == FrameRepairMode.SMOOTH_TIMELINE
        } ?: FrameRepairMode.MOTION_COMPENSATED

        // Schema 3 aumenta o limite padrão para cobrir gaps de 120/240 FPS sem cair
        // prematuramente em repetição de quadro. Preserve valores que o usuário já alterou.
        val storedMaxFrames = if (prefs.contains(KEY_MAX_FRAMES)) {
            prefs.getInt(KEY_MAX_FRAMES, 16)
        } else 16

        if (previousSchema < SCHEMA || safeMode != stored) {
            prefs.edit()
                .putInt(KEY_SCHEMA, SCHEMA)
                .putString(KEY_MODE, safeMode.name)
                .apply()
        }
        // O pós-processamento faz parte do fluxo padrão de gravação. Corrige também
        // instalações antigas que tenham deixado a preferência desativada.
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        }
        return Snapshot(
            enabled = true,
            mode = safeMode,
            maxInterpolatedFramesPerGap = storedMaxFrames.coerceIn(1, 30)
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        // Mantido por compatibilidade. O reparo automático não pode ser desligado:
        // novas gravações entram na fila e a captura sempre tem prioridade.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, true).apply()
    }

    fun setMode(context: Context, mode: FrameRepairMode) {
        val safe = mode.takeIf {
            it == FrameRepairMode.MOTION_COMPENSATED ||
                it == FrameRepairMode.QUALITY_LOW_MOTION ||
                it == FrameRepairMode.QUALITY_MEDIUM_MOTION ||
                it == FrameRepairMode.QUALITY_HIGH_MOTION ||
                it == FrameRepairMode.ADAPTIVE_BLEND ||
                it == FrameRepairMode.FILL_MISSING_FRAMES ||
                it == FrameRepairMode.SMOOTH_TIMELINE
        } ?: FrameRepairMode.MOTION_COMPENSATED
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, safe.name).apply()
    }


}
