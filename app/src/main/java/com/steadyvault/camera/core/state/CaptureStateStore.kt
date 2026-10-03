package com.steadyvault.camera.core.state

import android.content.Context
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.validation.UiBehaviorRules

object CaptureStateStore {

    data class EffectiveMode(
        val resolutionValue: String,
        val resolutionLabel: String,
        val fps: Int
    )

    private const val PREFS = "steadyvault_capture_state"
    private const val KEY_STATE = "state"
    private const val KEY_EFFECTIVE_RESOLUTION_VALUE = "effective_resolution_value"
    private const val KEY_EFFECTIVE_RESOLUTION = "effective_resolution"
    private const val KEY_EFFECTIVE_FPS = "effective_fps"
    private const val KEY_PHASE = "phase"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_OWNER = "owner"
    private const val KEY_STARTED_AT_ELAPSED = "started_at_elapsed"
    private const val KEY_HISTORY_SCHEMA = "effective_history_schema"
    private const val HISTORY_SCHEMA = 2

    fun update(
        context: Context,
        state: String,
        phase: CapturePhase = phaseForMessage(state),
        sessionId: String = "",
        owner: String = "",
        startedAtElapsedMs: Long = 0L
    ) {
        preferences(context).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_PHASE, phase.name)
            .apply {
                if (sessionId.isNotBlank()) putString(KEY_SESSION_ID, sessionId)
                if (owner.isNotBlank()) putString(KEY_OWNER, owner)
                if (startedAtElapsedMs > 0L) putLong(KEY_STARTED_AT_ELAPSED, startedAtElapsedMs)
                if (!phase.busy) {
                    remove(KEY_SESSION_ID)
                    remove(KEY_OWNER)
                    remove(KEY_STARTED_AT_ELAPSED)
                }
            }
            .apply()
        if (phase == CapturePhase.FAILED) AppLogRepository.error(context, "capture", state)
        else if (phase == CapturePhase.RECOVERING) AppLogRepository.warn(context, "capture", state)
        else if (phase == CapturePhase.RECORDING || !phase.busy) AppLogRepository.info(context, "capture", state)
    }

    fun currentState(context: Context): String =
        preferences(context).getString(KEY_STATE, "Pronto para gravar") ?: "Pronto para gravar"

    fun sessionState(context: Context): CaptureSessionState {
        val prefs = preferences(context)
        val message = prefs.getString(KEY_STATE, "Pronto para gravar") ?: "Pronto para gravar"
        return CaptureSessionState(
            phase = CapturePhase.from(prefs.getString(KEY_PHASE, null)) ?: phaseForMessage(message),
            message = message,
            sessionId = prefs.getString(KEY_SESSION_ID, "").orEmpty(),
            owner = prefs.getString(KEY_OWNER, "").orEmpty(),
            startedAtElapsedMs = prefs.getLong(KEY_STARTED_AT_ELAPSED, 0L)
        )
    }

    fun currentPhase(context: Context): CapturePhase = sessionState(context).phase

    fun updateEffectiveMode(context: Context, resolutionLabel: String, fps: Int) {
        updateEffectiveMode(context, resolutionValueFromLabel(resolutionLabel), resolutionLabel, fps)
    }

    fun updateEffectiveMode(
        context: Context,
        resolutionValue: String,
        resolutionLabel: String,
        fps: Int,
        rememberHistory: Boolean = true
    ) {
        if (resolutionLabel.isBlank() || fps <= 0) return
        val safeValue = resolutionValue.takeIf { it in CaptureSettings.supportedResolutionValues }
            ?: resolutionValueFromLabel(resolutionLabel)
        preferences(context).edit()
            .putString(KEY_EFFECTIVE_RESOLUTION_VALUE, safeValue)
            .putString(KEY_EFFECTIVE_RESOLUTION, resolutionLabel)
            .putInt(KEY_EFFECTIVE_FPS, fps)
            .apply {
                if (rememberHistory) {
                    putString(historyResolutionValueKey(fps), safeValue)
                    putString(historyResolutionLabelKey(fps), resolutionLabel)
                    putLong(historyUpdatedKey(fps), System.currentTimeMillis())
                }
            }
            .apply()
    }



    fun effectiveMode(context: Context): EffectiveMode? {
        val preferences = preferences(context)
        val resolution = preferences.getString(KEY_EFFECTIVE_RESOLUTION, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val fps = preferences.getInt(KEY_EFFECTIVE_FPS, 0)
        if (fps <= 0) return null
        val value = preferences.getString(KEY_EFFECTIVE_RESOLUTION_VALUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: resolutionValueFromLabel(resolution)
        return EffectiveMode(value, resolution, fps)
    }

    fun effectiveModeForFps(context: Context, fps: Int): EffectiveMode? {
        if (fps <= 0) return null
        migrateEffectiveHistoryIfNeeded(context)
        val preferences = preferences(context)
        val label = preferences.getString(historyResolutionLabelKey(fps), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val value = preferences.getString(historyResolutionValueKey(fps), null)
            ?.takeIf { it.isNotBlank() }
            ?: resolutionValueFromLabel(label)
        return EffectiveMode(value, label, fps)
    }

    fun clearEffectiveMode(context: Context) {
        preferences(context).edit()
            .remove(KEY_EFFECTIVE_RESOLUTION_VALUE)
            .remove(KEY_EFFECTIVE_RESOLUTION)
            .remove(KEY_EFFECTIVE_FPS)
            .apply()
    }

    fun isBusy(context: Context): Boolean = currentPhase(context).busy

    fun isBusyMessage(message: String): Boolean = UiBehaviorRules.isRecordingBusy(message)

    fun isFinalizing(context: Context): Boolean = currentPhase(context).finalizing

    @Synchronized
    fun completeSessionIfBusy(
        context: Context,
        sessionId: String,
        fallbackState: String = "Pronto para gravar"
    ): Boolean {
        val current = sessionState(context)
        if (!current.phase.busy) return false
        if (
            current.sessionId.isNotBlank() &&
            sessionId.isNotBlank() &&
            current.sessionId != sessionId
        ) {
            return false
        }
        update(context, fallbackState, CapturePhase.IDLE)
        return true
    }

    fun phaseForMessage(message: String): CapturePhase {
        val normalized = message.trim().lowercase()
        return when {
            UiBehaviorRules.isRecordingFinalizing(message) -> CapturePhase.FINALIZING
            normalized.startsWith("gravando") -> CapturePhase.RECORDING
            normalized.contains("recuper") || normalized.contains("retomando") -> CapturePhase.RECOVERING
            normalized.startsWith("falha") || normalized.startsWith("erro") -> CapturePhase.FAILED
            normalized.contains("aguardando") || normalized.contains("indisponível") -> CapturePhase.WAITING_CAMERA
            UiBehaviorRules.isRecordingBusy(message) -> CapturePhase.PREPARING
            else -> CapturePhase.IDLE
        }
    }

    /**
     * Chamado apenas no nascimento de um novo processo do app. Se o estado salvo
     * ainda dizia que havia uma captura em andamento, o processo anterior morreu
     * e o trecho precisa ser recuperado. Não existe polling/heartbeat durante vídeo.
     */
    fun reconcileInterruptedRecording(context: Context): Boolean {
        if (!isBusy(context)) return false
        update(
            context,
            "Interrupção do sistema • procurando trecho recuperável no cofre…",
            CapturePhase.RECOVERING,
            sessionId = OWNER_STARTUP_RECOVERY,
            owner = OWNER_STARTUP_RECOVERY
        )
        return true
    }

    private fun migrateEffectiveHistoryIfNeeded(context: Context) {
        val prefs = preferences(context)
        if (prefs.getInt(KEY_HISTORY_SCHEMA, 0) >= HISTORY_SCHEMA) return
        prefs.edit()
            .remove(historyResolutionValueKey(120))
            .remove(historyResolutionLabelKey(120))
            .remove(historyUpdatedKey(120))
            .remove(historyResolutionValueKey(240))
            .remove(historyResolutionLabelKey(240))
            .remove(historyUpdatedKey(240))
            .putInt(KEY_HISTORY_SCHEMA, HISTORY_SCHEMA)
            .apply()
    }

    private fun resolutionValueFromLabel(label: String): String = when {
        label.contains("8K", ignoreCase = true) -> CaptureSettings.RESOLUTION_8K
        label.contains("4K", ignoreCase = true) -> CaptureSettings.RESOLUTION_4K
        label.contains("1080", ignoreCase = true) -> CaptureSettings.RESOLUTION_1080P
        label.contains("720", ignoreCase = true) -> CaptureSettings.RESOLUTION_720P
        else -> CaptureSettings.RESOLUTION_4K
    }

    private fun historyResolutionValueKey(fps: Int) = "effective_resolution_value_$fps"
    private fun historyResolutionLabelKey(fps: Int) = "effective_resolution_label_$fps"
    private fun historyUpdatedKey(fps: Int) = "effective_updated_$fps"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val OWNER_STARTUP_RECOVERY = "startup_recovery"
}
