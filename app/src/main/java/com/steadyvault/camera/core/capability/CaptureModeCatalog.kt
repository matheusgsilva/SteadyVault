package com.steadyvault.camera.core.capability

import android.content.Context
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.state.CaptureStateStore

object CaptureModeCatalog {
    enum class Source { VALIDATED, DETECTED, CACHED, ANALYZING, UNVERIFIED, UNAVAILABLE }

    data class Profile(
        val fps: Int,
        val resolutionValue: String?,
        val resolutionLabel: String,
        val source: Source
    ) {
        val selectable: Boolean get() = source != Source.UNAVAILABLE
        val available: Boolean get() = source != Source.UNAVAILABLE
        val buttonText: String get() = when (source) {
            Source.UNAVAILABLE -> "Indisponível\n$fps FPS"
            Source.ANALYZING -> "Analisando\n$fps FPS"
            else -> "$resolutionLabel\n$fps FPS"
        }
        val inlineText: String get() = when (source) {
            Source.UNAVAILABLE -> "$fps FPS indisponível"
            Source.ANALYZING -> "$fps FPS em análise"
            Source.UNVERIFIED -> "$fps FPS ainda não confirmado"
            else -> "$resolutionLabel • $fps FPS"
        }
        val capabilityDescription: String get() = when (source) {
            Source.VALIDATED -> "Validado em uma gravação real neste aparelho."
            Source.DETECTED -> "Detectado pela câmera e pelo encoder; uma captura real ainda é a validação definitiva."
            Source.CACHED -> "Detectado na última análise deste aparelho."
            Source.ANALYZING -> "Ainda não confirmado neste aparelho; você pode selecionar e a sessão real fará a validação."
            Source.UNVERIFIED -> "Ainda não confirmado neste aparelho; você pode selecionar e a sessão real fará a validação."
            Source.UNAVAILABLE -> "A combinação escolhida foi identificada como incompatível."
        }
    }

    data class Catalog(val profiles: List<Profile>) {
        fun profile(fps: Int): Profile = profiles.firstOrNull { it.fps == fps } ?: unavailable(fps)
        fun summary(): String = profiles.joinToString("\n") { profile ->
            val suffix = when (profile.source) {
                Source.VALIDATED -> "validado na prática"
                Source.DETECTED -> "detectado"
                Source.CACHED -> "detectado anteriormente"
                Source.ANALYZING -> "ainda não confirmado"
                Source.UNVERIFIED -> "validação ao iniciar"
                Source.UNAVAILABLE -> "indisponível"
            }
            "${profile.inlineText} — $suffix"
        }
    }

    private const val PREFS = "steadyvault_mode_capabilities"
    private const val KEY_SCAN_TIME = "scan_time"
    private const val HIGH_FPS_MIN = 120
    private val fpsValues get() = CaptureSettings.supportedFpsValues

    fun resolve(context: Context, matrix: CaptureCapabilityMatrix.Matrix? = CaptureCapabilityMatrix.cached(), scanInProgress: Boolean = false): Catalog {
        val profiles = fpsValues.map { fps ->
            val validated = CaptureStateStore.effectiveModeForFps(context, fps)
                ?.takeIf { it.resolutionValue in CaptureSettings.supportedResolutionValues }
                ?.let { Profile(fps, it.resolutionValue, it.resolutionLabel, Source.VALIDATED) }
            val detected = matrix?.maximumMode(fps)?.toProfile(Source.DETECTED)
            // Taxas altas (120/240) só aparecem quando a análise confirmou o modo nesta câmera.
            if (matrix != null && detected == null && fps >= HIGH_FPS_MIN) return@map unavailable(fps)
            val cached = cachedProfile(context, fps)
            when {
                detected != null -> if (validated != null && validated.resolutionValue == detected.resolutionValue) validated else detected
                validated != null -> validated
                cached != null -> cached
                matrix != null -> Profile(fps, null, "Não confirmado", Source.UNVERIFIED)
                scanInProgress -> Profile(fps, null, "Analisando", Source.ANALYZING)
                else -> Profile(fps, null, "Não confirmado", Source.UNVERIFIED)
            }
        }
        return Catalog(profiles)
    }

    fun resolveSelection(
        context: Context,
        fps: Int,
        resolution: String = CaptureSettings.resolutionForFps(context, fps),
        matrix: CaptureCapabilityMatrix.Matrix? = CaptureCapabilityMatrix.cached(),
        scanInProgress: Boolean = false
    ): Profile {
        val safeResolution = resolution.takeIf { it in CaptureSettings.supportedResolutionValues } ?: CaptureSettings.RESOLUTION_4K
        val validated = CaptureStateStore.effectiveModeForFps(context, fps)?.takeIf { it.resolutionValue == safeResolution }
        if (matrix != null && fps >= HIGH_FPS_MIN && matrix.bestMode(safeResolution, fps) == null) return unavailable(fps)
        matrix?.bestMode(safeResolution, fps)?.let {
            return if (validated != null) Profile(fps, safeResolution, validated.resolutionLabel, Source.VALIDATED)
            else it.toProfile(Source.DETECTED)
        }
        if (validated != null) return Profile(fps, safeResolution, validated.resolutionLabel, Source.VALIDATED)
        cachedProfile(context, fps)?.takeIf { it.resolutionValue == safeResolution }?.let { return it }
        return Profile(
            fps,
            safeResolution,
            CaptureSettings.resolutionLabel(safeResolution),
            if (scanInProgress) Source.ANALYZING else Source.UNVERIFIED
        )
    }

    fun preferredResolution(
        context: Context,
        fps: Int,
        requestedResolution: String = CaptureSettings.resolutionForFps(context, fps),
        matrix: CaptureCapabilityMatrix.Matrix? = CaptureCapabilityMatrix.cached(),
        scanInProgress: Boolean = false
    ): String {
        val safeRequested = requestedResolution.takeIf { it in CaptureSettings.supportedResolutionValues }
            ?: CaptureSettings.RESOLUTION_4K
        if (matrix?.bestMode(safeRequested, fps) != null) return safeRequested
        // Taxa alta (ex.: 120/240) costuma existir só em resoluções menores: usa a maior confirmada
        // que não passe da pedida; se não houver, a maior disponível.
        val confirmed = matrix?.modes.orEmpty().filter { it.fps == fps }.map { it.resolution }.toSet()
        if (confirmed.isNotEmpty()) {
            val ordered = CaptureSettings.supportedResolutionValues
            val startIndex = ordered.indexOf(safeRequested).coerceAtLeast(0)
            // Sem nada confirmado igual ou abaixo do pedido, sobe só até a menor resolução confirmada
            // (nunca pula direto para a máxima: pedir 720p não pode virar 4K).
            return ordered.drop(startIndex).firstOrNull { it in confirmed }
                ?: ordered.take(startIndex).lastOrNull { it in confirmed }
                ?: safeRequested
        }
        return resolveSelection(context, fps, safeRequested, matrix, scanInProgress).resolutionValue ?: safeRequested
    }

    fun remember(context: Context, matrix: CaptureCapabilityMatrix.Matrix) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SCAN_TIME, System.currentTimeMillis())
        fpsValues.forEach { fps ->
            val mode = matrix.maximumMode(fps)
            if (mode == null) editor.remove(resolutionKey(fps)) else editor.putString(resolutionKey(fps), mode.resolution)
        }
        editor.remove("high_speed_30").remove("high_speed_60").remove("high_speed_120").remove("high_speed_240")
            .apply()
    }

    private fun cachedProfile(context: Context, fps: Int): Profile? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val resolution = prefs.getString(resolutionKey(fps), null)?.takeIf { it in CaptureSettings.supportedResolutionValues } ?: return null
        return Profile(fps, resolution, CaptureSettings.resolutionLabel(resolution), Source.CACHED)
    }

    private fun CaptureCapabilityMatrix.Mode.toProfile(source: Source) = Profile(fps, resolution, CaptureSettings.resolutionLabel(resolution), source)
    private fun unavailable(fps: Int) = Profile(fps, null, "Indisponível", Source.UNAVAILABLE)
    private fun resolutionKey(fps: Int) = "resolution_$fps"
}
