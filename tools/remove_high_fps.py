from pathlib import Path
import re


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def exact(text, old, new, label, count=1):
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{label}: expected {count}, found {found}")
    return text.replace(old, new, count)


def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{label}: start not found")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"{label}: end not found")
    return text[:a] + replacement + text[b:]


# Only 30/60 are valid recording rates.
mode_store = Path("app/src/main/java/com/steadyvault/camera/core/settings/CaptureModeStore.kt")
write(mode_store, '''package com.steadyvault.camera.core.settings

import android.content.Context

object CaptureModeStore {
    const val FPS_30 = 30
    const val FPS_60 = 60

    fun getTargetFps(context: Context): Int = CaptureSettings.snapshot(context).fps
}
''')

# Settings: migrate old 120/240 selections to 60 and never persist them again.
p = "app/src/main/java/com/steadyvault/camera/core/settings/CaptureSettings.kt"
t = read(p)
t = exact(t,
'''        val fps = prefs.getInt("fps", 60).takeIf { it in SUPPORTED_FPS } ?: 60
        val resolution = resolutionForFps(context, fps)
''',
'''        val storedFps = prefs.getInt("fps", 60)
        val fps = storedFps.takeIf { it in SUPPORTED_FPS } ?: 60
        val resolution = resolutionForFps(context, fps)
''', "settings stored fps")
t = exact(t,
'''        if (storedCodec != codec || storedStabilization != stabilization) {
            prefs.edit().putString("codec", codec).putString("stabilization", stabilization).apply()
        }
''',
'''        if (storedFps != fps || storedCodec != codec || storedStabilization != stabilization) {
            prefs.edit()
                .putInt("fps", fps)
                .putString("codec", codec)
                .putString("stabilization", stabilization)
                .apply()
        }
''', "settings migration")
t = exact(t,
'''        val normalized = snapshot.copy(
            resolution = snapshot.resolution.takeIf { it in SUPPORTED_RESOLUTIONS } ?: RESOLUTION_4K,
            codec = snapshot.codec.takeIf { it in SUPPORTED_CODECS } ?: CODEC_HEVC,
''',
'''        val normalized = snapshot.copy(
            resolution = snapshot.resolution.takeIf { it in SUPPORTED_RESOLUTIONS } ?: RESOLUTION_4K,
            fps = snapshot.fps.takeIf { it in SUPPORTED_FPS } ?: 60,
            codec = snapshot.codec.takeIf { it in SUPPORTED_CODECS } ?: CODEC_HEVC,
''', "settings save fps")
t = re.sub(
    r'''        val recommended = when \(resolution\) \{\n            RESOLUTION_8K -> when \(fps\) \{ 240 -> 220; 120 -> 220; 60 -> 180; else -> 100 \}\n            RESOLUTION_720P -> when \(fps\) \{ 240 -> 60; 120 -> 28; 60 -> 15; else -> 10 \}\n            RESOLUTION_1080P -> when \(fps\) \{ 240 -> 100; 120 -> 50; 60 -> 28; else -> 20 \}\n            RESOLUTION_4K -> when \(fps\) \{ 240 -> 170; 120 -> 135; 60 -> 60; else -> 48 \}\n            else -> when \(fps\) \{ 240 -> 170; 120 -> 135; 60 -> 60; else -> 48 \}\n        \}''',
    '''        val recommended = when (resolution) {
            RESOLUTION_8K -> if (fps == 60) 180 else 100
            RESOLUTION_720P -> if (fps == 60) 15 else 10
            RESOLUTION_1080P -> if (fps == 60) 28 else 20
            RESOLUTION_4K -> if (fps == 60) 60 else 48
            else -> if (fps == 60) 60 else 48
        }''',
    t,
    count=1
)
if "240 -> 220" in t:
    raise SystemExit("settings bitrate map was not simplified")
t = exact(t, "    private val SUPPORTED_FPS = linkedSetOf(30, 60, 120, 240)", "    private val SUPPORTED_FPS = linkedSetOf(30, 60)", "supported fps")
write(p, t)

p = "app/src/main/java/com/steadyvault/camera/core/settings/CameraProfileStore.kt"
t = read(p)
t = t.replace(" * perfil próprio. Assim 30/60/120/240 FPS não se sobrescrevem entre si.", " * perfil próprio. Assim 30 e 60 FPS não se sobrescrevem entre si.")
write(p, t)

# Catalog exposes only supported regular modes.
p = "app/src/main/java/com/steadyvault/camera/core/capability/CaptureModeCatalog.kt"
write(p, '''package com.steadyvault.camera.core.capability

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
            Source.UNAVAILABLE -> "Indisponível\\n$fps FPS"
            Source.ANALYZING -> "Analisando\\n$fps FPS"
            else -> "$resolutionLabel\\n$fps FPS"
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
        fun summary(): String = profiles.joinToString("\\n") { profile ->
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
    private val fpsValues get() = CaptureSettings.supportedFpsValues

    fun resolve(context: Context, matrix: CaptureCapabilityMatrix.Matrix? = CaptureCapabilityMatrix.cached(), scanInProgress: Boolean = false): Catalog {
        val profiles = fpsValues.map { fps ->
            val validated = CaptureStateStore.effectiveModeForFps(context, fps)
                ?.takeIf { it.resolutionValue in CaptureSettings.supportedResolutionValues }
                ?.let { Profile(fps, it.resolutionValue, it.resolutionLabel, Source.VALIDATED) }
            val detected = matrix?.maximumMode(fps)?.toProfile(Source.DETECTED)
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
        return resolveSelection(context, fps, safeRequested, matrix, scanInProgress).resolutionValue ?: safeRequested
    }

    fun remember(context: Context, matrix: CaptureCapabilityMatrix.Matrix) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SCAN_TIME, System.currentTimeMillis())
        fpsValues.forEach { fps ->
            val mode = matrix.maximumMode(fps)
            if (mode == null) editor.remove(resolutionKey(fps)) else editor.putString(resolutionKey(fps), mode.resolution)
        }
        editor.remove("resolution_120").remove("resolution_240")
            .remove("high_speed_30").remove("high_speed_60").remove("high_speed_120").remove("high_speed_240")
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
''')

# Capability matrix: regular Camera2 sessions only, and discard any old persisted high-speed modes.
p = "app/src/main/java/com/steadyvault/camera/core/capability/CaptureCapabilityMatrix.kt"
t = read(p)
t = t.replace("for (fps in CaptureSettings.supportedFpsValues.filter { it < CaptureModeStore.FPS_120 })", "for (fps in CaptureSettings.supportedFpsValues)")
start = t.find("            val capabilities = characteristics.get(\n                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES\n            ) ?: intArrayOf()", t.find("for (fps in CaptureSettings.supportedFpsValues)"))
end = t.find("        val unique = modes", start)
if start < 0 or end < 0:
    raise SystemExit("capability high-speed block not found")
t = t[:start] + "        }\n\n" + t[end:]
old_best_start = "        private fun bestCandidate(candidates: List<Mode>, fps: Int): Mode? {"
old_best_end = "        fun featuresFor(cameraId: String?): CameraFeatures? {"
t = between(t, old_best_start, old_best_end, '''        private fun bestCandidate(candidates: List<Mode>, fps: Int): Mode? =
            candidates.maxWithOrNull(
                compareBy<Mode> { resolutionScore(it.resolution) }
                    .thenBy { if (it.encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) 1 else 0 }
            )

''', "capability best candidate")
t = t.replace("            .distinctBy { listOf(it.cameraId, it.resolution, it.fps, it.highSpeed, it.encoderMime) }", "            .distinctBy { listOf(it.cameraId, it.resolution, it.fps, it.encoderMime) }")
t = t.replace("                    .thenByDescending { resolutionScore(it.resolution) }\n                    .thenByDescending { it.highSpeed }", "                    .thenByDescending { resolutionScore(it.resolution) }")
t = exact(t,
'''        val modes = root.getJSONArray("modes").jsonObjects().map { item ->
            Mode(
                cameraId = item.getString("cameraId"),
                resolution = item.getString("resolution"),
                size = Size(item.getInt("width"), item.getInt("height")),
                fps = item.getInt("fps"),
                highSpeed = item.getBoolean("highSpeed"),
                encoderMime = item.getString("encoderMime")
            )
        }
''',
'''        val modes = root.getJSONArray("modes").jsonObjects().mapNotNull { item ->
            val fps = item.getInt("fps")
            val highSpeed = item.optBoolean("highSpeed", false)
            if (fps !in CaptureSettings.supportedFpsValues || highSpeed) return@mapNotNull null
            Mode(
                cameraId = item.getString("cameraId"),
                resolution = item.getString("resolution"),
                size = Size(item.getInt("width"), item.getInt("height")),
                fps = fps,
                highSpeed = false,
                encoderMime = item.getString("encoderMime")
            )
        }
''', "capability persisted modes")
write(p, t)

# Capability report no longer advertises HFR capture paths.
p = "app/src/main/java/com/steadyvault/camera/core/capability/CapabilityReport.kt"
t = read(p)
start = t.find("            val highSpeedSizes: Array<Size>")
end = t.find("            val stabilizationModes: IntArray", start)
if start >= 0 and end >= 0:
    t = t[:start] + t[end:]
t = t.replace('            lines += "High-speed: ${highSpeed.ifBlank { "não anunciado" }}"\n', '')
t = t.replace('''                        val session = if (mode.highSpeed) "high-speed" else "regular"
                        "${CaptureSettings.resolutionLabel(mode.resolution)} ${mode.fps} FPS ${mode.encoderMime.substringAfterLast('/').uppercase(Locale.US)} ($session)"
''', '''                        "${CaptureSettings.resolutionLabel(mode.resolution)} ${mode.fps} FPS ${mode.encoderMime.substringAfterLast('/').uppercase(Locale.US)}"
''')
write(p, t)

# Settings UI text and compatibility now only consider 30/60.
p = "app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt"
t = read(p)
t = t.replace("Fica desligado por padrão. Em 30/60 FPS, se a câmera publicar uma faixa variável compatível, permite reduzir temporariamente o FPS para ganhar exposição em pouca luz. 120/240 permanecem fixos.", "Fica desligado por padrão. Em 30/60 FPS, se a câmera publicar uma faixa variável compatível, permite reduzir temporariamente o FPS para ganhar exposição em pouca luz.")
t = t.replace("Com FPS automático desligado, 30/60 usam faixa fixa e 120/240 exigem suporte real do formato. Com FPS automático ligado, somente 30/60 podem usar uma faixa variável publicada pela câmera; o app nunca inventa quadros.", "Com FPS automático desligado, 30/60 usam faixa fixa. Com FPS automático ligado, podem usar uma faixa variável publicada pela câmera; o app nunca inventa quadros.")
t = exact(t,
'''    private fun enforceHdrCompatibility() {
        if (!hdr.isChecked) return
        val activeFps = selected(fps).toIntOrNull() ?: editingFps
        val incompatible = selected(codec) == CaptureSettings.CODEC_AVC ||
                activeFps >= 120 ||
                selectedCameraFeatures()?.hdrHlg10 == Support.UNSUPPORTED
        if (incompatible) {
            hdr.isChecked = false
            Toast.makeText(this, "HLG10 foi desativado porque esta combinação não é compatível", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDependentControls() {
        if (!::hdr.isInitialized) return
        val highSpeed = (selected(fps).toIntOrNull() ?: editingFps) >= 120
        val hdrSupport = selectedCameraFeatures()?.hdrHlg10 ?: Support.UNVERIFIED
        val hdrHardwareSelectable = HardwareSupportPolicy.isSelectable(hdrSupport)
        hdr.isEnabled = !highSpeed && selected(codec) != CaptureSettings.CODEC_AVC && hdrHardwareSelectable
        hdr.alpha = if (hdr.isEnabled) 1f else 0.45f
        // 60 FPS pode usar TONEMAP_CONTRAST_CURVE quando a câmera publicar a
        // chave. As opções individuais do spinner já carregam o suporte real.
        colorProfile.isEnabled = !hdr.isChecked && !highSpeed
        colorProfile.alpha = if (colorProfile.isEnabled) 1f else 0.45f
    }
''',
'''    private fun enforceHdrCompatibility() {
        if (!hdr.isChecked) return
        val incompatible = selected(codec) == CaptureSettings.CODEC_AVC ||
                selectedCameraFeatures()?.hdrHlg10 == Support.UNSUPPORTED
        if (incompatible) {
            hdr.isChecked = false
            Toast.makeText(this, "HLG10 foi desativado porque esta combinação não é compatível", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDependentControls() {
        if (!::hdr.isInitialized) return
        val hdrSupport = selectedCameraFeatures()?.hdrHlg10 ?: Support.UNVERIFIED
        val hdrHardwareSelectable = HardwareSupportPolicy.isSelectable(hdrSupport)
        hdr.isEnabled = selected(codec) != CaptureSettings.CODEC_AVC && hdrHardwareSelectable
        hdr.alpha = if (hdr.isEnabled) 1f else 0.45f
        colorProfile.isEnabled = !hdr.isChecked
        colorProfile.alpha = if (colorProfile.isEnabled) 1f else 0.45f
    }
''', "settings hdr controls")
t = t.replace("if (settings.hdrHlg10 || settings.fps >= CaptureModeStore.FPS_120) return Support.UNSUPPORTED", "if (settings.hdrHlg10) return Support.UNSUPPORTED")
write(p, t)

# Main capture UI: remove all 120/240 controls and high-FPS-only restrictions.
p = "app/src/main/java/com/steadyvault/camera/ui/capture/CaptureActivity.kt"
t = read(p)
for line in [
    "    private lateinit var fps120Button: TextView\n",
    "    private lateinit var fps240Button: TextView\n",
    "    private lateinit var previewFps120Button: TextView\n",
    "    private lateinit var previewFps240Button: TextView\n",
    "        fps120Button = findViewById(R.id.fps120Button)\n",
    "        fps240Button = findViewById(R.id.fps240Button)\n",
    "        previewFps120Button = findViewById(R.id.previewFps120Button)\n",
    "        previewFps240Button = findViewById(R.id.previewFps240Button)\n",
    "        previewFps120Button.setOnClickListener { selectPreviewRecordingMode(CaptureModeStore.FPS_120) }\n",
    "        previewFps240Button.setOnClickListener { selectPreviewRecordingMode(CaptureModeStore.FPS_240) }\n",
]:
    t = t.replace(line, "")
t = re.sub(r'''\n        fps120Button\.setOnClickListener \{.*?\n        \}\n\n        fps240Button\.setOnClickListener \{.*?\n        \}\n''', "\n", t, count=1, flags=re.S)
t = between(t, "    private fun currentPreviewBufferSize(): Pair<Int, Int> {", "    private fun currentPreviewDisplayAspect(): Float {", '''    private fun currentPreviewBufferSize(): Pair<Int, Int> {
        val settings = CaptureSettings.snapshot(this)
        val selectedCameraId = settings.selectedCameraId ?: idlePreview.currentCameraId()
        CameraLensCatalog.previewSize(
            context = this,
            cameraId = selectedCameraId,
            resolution = settings.resolution,
            photoMode = previewPhotoMode,
            highSpeed = false
        )?.let { size ->
            val width = size.width.coerceAtLeast(1)
            val height = size.height.coerceAtLeast(1)
            if (previewPhotoMode) return width to height
            val aspect = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
            if (aspect in 1.70f..1.90f) return width to height
        }
        return if (previewPhotoMode) {
            PHOTO_PREVIEW_BUFFER_WIDTH to PHOTO_PREVIEW_BUFFER_HEIGHT
        } else {
            STANDARD_VIDEO_PREVIEW_BUFFER_WIDTH to STANDARD_VIDEO_PREVIEW_BUFFER_HEIGHT
        }
    }

''', "activity preview size")
t = between(t, "    private fun focusSupport(", "    private fun processingSupport(", '''    private fun focusSupport(
        settings: CaptureSettings.Snapshot,
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String
    ): Support = features?.focusSupport(value) ?: Support.UNVERIFIED

''', "activity focus support")
t = between(t, "    private fun processingSupport(", "    private fun yellowReductionSupport(", '''    private fun processingSupport(
        settings: CaptureSettings.Snapshot,
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String,
        noise: Boolean
    ): Support = if (noise) {
        features?.noiseReductionSupport(value) ?: Support.UNVERIFIED
    } else {
        features?.edgeSupport(value) ?: Support.UNVERIFIED
    }

''', "activity processing support")
t = t.replace("val hdrAvailable = settings.fps < CaptureModeStore.FPS_120 &&\n            settings.codec != CaptureSettings.CODEC_AVC &&", "val hdrAvailable = settings.codec != CaptureSettings.CODEC_AVC &&")
t = t.replace("val hdrAllowed = settings.fps < CaptureModeStore.FPS_120 &&\n            featureSelectable(support)", "val hdrAllowed = featureSelectable(support)")
t = t.replace('''            message = if (!previewPhotoMode && settings.fps >= CaptureModeStore.FPS_120) {
                "Em sessão high-speed o processamento fica sob controle da HAL para preservar a cadência."
            } else if (settings.fps >= CaptureModeStore.FPS_60) {
''', '''            message = if (settings.fps >= CaptureModeStore.FPS_60) {
''')
t = re.sub(r'''\n        if \(normalized\.hdrHlg10 && normalized\.fps >= CaptureModeStore\.FPS_120\) \{\n            Toast\.makeText\(this, "HLG10 não está disponível neste modo de alta taxa\.", Toast\.LENGTH_LONG\)\.show\(\)\n            return\n        \}\n''', "\n", t, count=1)
t = t.replace("            settings.fps < CaptureModeStore.FPS_120 &&\n            settings.codec != CaptureSettings.CODEC_AVC &&", "            settings.codec != CaptureSettings.CODEC_AVC &&")
t = t.replace("            CaptureModeStore.FPS_120 to previewFps120Button,\n            CaptureModeStore.FPS_240 to previewFps240Button\n", "")
t = t.replace("            CaptureModeStore.FPS_60 to previewFps60Button,\n        )", "            CaptureModeStore.FPS_60 to previewFps60Button\n        )")
t = t.replace("        configureButton(fps120Button, CaptureModeStore.FPS_120)\n", "")
t = t.replace("        configureButton(fps240Button, CaptureModeStore.FPS_240)\n", "")
t = re.sub(r'''\n        \(fps120Button\.parent as\? View\)\?\.visibility = .*?\n''', "\n", t, count=1)
if "FPS_120" in t or "FPS_240" in t or "fps120Button" in t or "fps240Button" in t or "previewFps120Button" in t or "previewFps240Button" in t:
    raise SystemExit("CaptureActivity still contains removed FPS controls/references")
write(p, t)

# Capture layout: drop second FPS row and preview 120/240 chips.
p = "app/src/main/res/layout/activity_capture.xml"
t = read(p)
t = re.sub(r'''\n                    <LinearLayout android:layout_width="match_parent" android:layout_height="44dp" android:layout_marginTop="7dp" android:orientation="horizontal">\n                        <TextView android:id="@\+id/fps120Button".*?\n                    </LinearLayout>''', "", t, count=1, flags=re.S)
t = re.sub(r'''\n                <TextView android:id="@\+id/previewFps120Button"[^\n]*/>''', "", t, count=1)
t = re.sub(r'''\n                <TextView android:id="@\+id/previewFps240Button"[^\n]*/>''', "", t, count=1)
if "fps120Button" in t or "fps240Button" in t or "previewFps120Button" in t or "previewFps240Button" in t:
    raise SystemExit("capture layout still contains high FPS buttons")
write(p, t)

# Recording service: resolve only 30/60, reject any stale high-speed cache, keep working 60 path unchanged.
p = "app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt"
t = read(p)
start = t.find("    /**\n     * O widget pode ser usado antes de a análise completa de hardware existir.")
end = t.find("    private fun configurationSignature", start)
if start < 0 or end < 0:
    raise SystemExit("service resolution block not found")
t = t[:start] + '''    private fun resolveCaptureResolutionForFps(
        cameraId: String?,
        targetFps: Int,
        storedResolution: String
    ): String {
        if (cameraId.isNullOrBlank()) return storedResolution
        val cachedMatrix = CaptureCapabilityMatrix.cached(this)?.forCamera(cameraId)
        return CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = storedResolution,
            matrix = cachedMatrix
        )
    }

''' + t[end:]
t = t.replace("        val highSpeed = prefs.getBoolean(CONFIG_HIGH_SPEED, false)\n", "        val highSpeed = prefs.getBoolean(CONFIG_HIGH_SPEED, false)\n        if (highSpeed) return null\n")
start = t.find("    private fun selectPreferredPreviewConfiguration(")
end = t.find("    private fun resolveFpsRange(", start)
if start < 0 or end < 0:
    raise SystemExit("service preferred config block not found")
t = t[:start] + '''    private fun selectPreferredPreviewConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile>? {
        val cameraId = preferredCameraId ?: return null
        val manager = getSystemService(CameraManager::class.java)
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null
        val dynamicRange = when {
            allowHdr && supportsHlg10(characteristics) -> DynamicRangeProfiles.HLG10
            allowHdr -> return null
            else -> standardDynamicRangeProfile()
        }
        val size = recordingSettings.exactPreferredSize() ?: return null
        val profile = createCameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = size,
            targetFps = targetFps,
            fpsRange = resolveStandardFpsRange(characteristics, targetFps),
            highSpeed = false,
            dynamicRangeProfile = dynamicRange
        )
        if (!matchesRequestedMode(profile, targetFps)) return null
        if (!profileSatisfiesExplicitStabilization(profile)) return null
        val encoder = selectDirectRecorderEncoder(profile) ?: return null
        return profile to encoder
    }

''' + t[end:]
start = t.find("    private fun resolveFpsRange(")
end = t.find("    /**\n     * Equivalente Android do Auto FPS do iPhone", start)
if start < 0 or end < 0:
    raise SystemExit("service fps range block not found")
t = t[:start] + '''    private fun resolveFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int,
        highSpeed: Boolean
    ): Range<Int>? {
        if (highSpeed) return null
        return resolveStandardFpsRange(characteristics, targetFps)
    }

''' + t[end:]
t = t.replace("     * Quando habilitado, 30/60 usam apenas uma faixa variável que a própria HAL\n     * publica e cujo teto é exatamente o FPS escolhido. Nunca se aplica a 120/240.\n", "     * Quando habilitado, 30/60 usam apenas uma faixa variável que a própria HAL\n     * publica e cujo teto é exatamente o FPS escolhido.\n")
start = t.find("    private fun resolveRegularHighFpsRange(")
end = t.find("    /**\n     * FPS selecionado é contrato exato.", start)
if start >= 0 and end >= 0:
    t = t[:start] + t[end:]
t = t.replace("     * [30,30], 60 usa [60,60], 120 usa [120,120] e 240 usa [240,240].\n", "     * [30,30] e 60 usa [60,60].\n")
t = t.replace("            allowHdr = allowHdr && targetFps < CaptureModeStore.FPS_120", "            allowHdr = allowHdr")
t = exact(t,
'''    private fun CameraProfile.matchesRequestedFpsContract(): Boolean = when {
        highSpeed || targetFps >= CaptureModeStore.FPS_120 -> hasExactFpsRange()
        !recordingSettings.autoFpsLowLight -> hasExactFpsRange()
        else -> fpsRange.upper == targetFps && fpsRange.lower <= targetFps
    }
''',
'''    private fun CameraProfile.matchesRequestedFpsContract(): Boolean = when {
        highSpeed -> false
        !recordingSettings.autoFpsLowLight -> hasExactFpsRange()
        else -> fpsRange.upper == targetFps && fpsRange.lower <= targetFps
    }
''', "service fps contract")
t = re.sub(r'''\n        val finalizedFps = selectedCamera\?\.targetFps \?: requestedTargetFps\n        if \(finalizedFps == CaptureModeStore\.FPS_120\) \{.*?\n        \}\n''', "\n", t, count=1, flags=re.S)
if "FPS_120" in t or "FPS_240" in t or "120 FPS" in t or "240 FPS" in t:
    raise SystemExit("CaptureService still contains 120/240 FPS references")
write(p, t)

# OEM exact profiles are only needed for HLG10 now.
p = "app/src/main/java/com/steadyvault/camera/capture/recorder/DirectMediaRecorderBackend.kt"
t = read(p)
t = t.replace("get() = selectedProfile != null && (hdrHlg10 || targetFps >= 240)", "get() = selectedProfile != null && hdrHlg10")
t = re.sub(r'''\n                    // 240 FPS preserva.*?// explicitamente para que o muxer receba 120 FPS reais\.\n''', "\n", t, count=1, flags=re.S)
write(p, t)

# Zoom no longer has a special HFR exposure path.
p = "app/src/main/java/com/steadyvault/camera/core/camera/CameraZoom.kt"
t = read(p)
t = t.replace("import kotlin.math.roundToInt\n", "")
t = re.sub(r'''        // O request constrained high-speed.*?        applyHighSpeedExposureBias\(builder, characteristics\)\n\n''', "", t, count=1, flags=re.S)
start = t.find("    private fun applyHighSpeedExposureBias(")
if start >= 0:
    end = t.rfind("\n}")
    if end <= start:
        raise SystemExit("zoom high speed function end not found")
    t = t[:start] + t[end:]
write(p, t)

# Camera lens preview remains regular-only from its caller; remove explicit legacy labels.
p = "app/src/main/java/com/steadyvault/camera/core/camera/CameraLensCatalog.kt"
t = read(p).replace("      // 16:9 para 120/240 FPS", "      // 16:9 para modo de alta cadência legado")
write(p, t)

# Optimization UI no longer offers generation of 120 FPS files.
p = "app/src/main/java/com/steadyvault/camera/ui/vault/VideoOptimizationActivity.kt"
t = read(p)
t = t.replace('bind(fps, "Fluidez final (FPS)", listOf("Manter o FPS original", "24 FPS", "30 FPS", "60 FPS", "120 FPS"))', 'bind(fps, "Fluidez final (FPS)", listOf("Manter o FPS original", "24 FPS", "30 FPS", "60 FPS"))')
t = re.sub(r'''\n        label\.contains\("120 FPS"\) -> "Aumenta bastante a carga térmica e exige encoder compatível\."''', "", t, count=1)
write(p, t)

# Remove the short-lived 120 FPS timestamp normalizer entirely.
normalizer = Path("app/src/main/java/com/steadyvault/camera/capture/recorder/HighSpeedTimestampNormalizer.kt")
if normalizer.exists():
    normalizer.unlink()

print("30/60-only patch applied")
