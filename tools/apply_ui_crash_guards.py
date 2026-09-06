from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"bloco nao encontrado em {path}: {old[:220]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

settings = "app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt"

replace_once(
    settings,
    "import com.steadyvault.camera.core.feedback.Haptics\n",
    "import com.steadyvault.camera.core.feedback.Haptics\nimport com.steadyvault.camera.core.diagnostics.AppLogRepository\n",
)

replace_once(
    settings,
    "    private var building = false\n    private var saveGeneration = 0\n",
    "    private var building = false\n    private var formReady = false\n    private var saveGeneration = 0\n",
)

old_oncreate = '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBarInsets.applyTop(findViewById<View>(R.id.settingsScreenRoot))
        BottomNavigation.bind(this, BottomNavigation.TAB_SETTINGS)
        settingsScroll = findViewById(R.id.settingsScroll)
        container = findViewById(R.id.settingsContainer)
        val requestedProfileMode = intent.getStringExtra(EXTRA_PROFILE_MODE)
            ?.let { runCatching { CameraProfileStore.FunctionMode.valueOf(it) }.getOrNull() }
            ?: CameraProfileStore.FunctionMode.VIDEO
        val current = CaptureSettings.snapshot(this)
        val activeSnapshot = current.selectedCameraId?.takeIf { it.isNotBlank() }?.let { cameraId ->
            CameraProfileStore.ensureProfiles(this, cameraId, current)
            CameraProfileStore.activate(this, cameraId, requestedProfileMode, current)
        } ?: current.also { CameraProfileStore.setActiveMode(this, requestedProfileMode) }
        capabilityMatrix = CaptureCapabilityMatrix.cached(this)
        capabilityScanCompleted = capabilityMatrix != null
        buildForm(activeSnapshot)
    }

    override fun onPause() {
        if (!building && ::autoSaveStatus.isInitialized) {
            saveGeneration++
            saveCurrent()
        }
        super.onPause()
    }
'''

new_oncreate = '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settingsScroll = findViewById(R.id.settingsScroll)
        container = findViewById(R.id.settingsContainer)
        runCatching { SystemBarInsets.applyTop(findViewById<View>(R.id.settingsScreenRoot)) }
            .onFailure { AppLogRepository.warn(this, "SETTINGS_UI", "Falha ao aplicar insets", it) }
        runCatching { BottomNavigation.bind(this, BottomNavigation.TAB_SETTINGS) }
            .onFailure { AppLogRepository.warn(this, "SETTINGS_UI", "Falha ao montar navegacao inferior", it) }
        rebuildSettingsSafely("abertura")
    }

    private fun activeSettingsSnapshot(): CaptureSettings.Snapshot {
        val requestedProfileMode = intent.getStringExtra(EXTRA_PROFILE_MODE)
            ?.let { runCatching { CameraProfileStore.FunctionMode.valueOf(it) }.getOrNull() }
            ?: CameraProfileStore.FunctionMode.VIDEO
        val current = CaptureSettings.snapshot(this)
        return current.selectedCameraId?.takeIf { it.isNotBlank() }?.let { cameraId ->
            CameraProfileStore.ensureProfiles(this, cameraId, current)
            CameraProfileStore.activate(this, cameraId, requestedProfileMode, current)
        } ?: current.also { CameraProfileStore.setActiveMode(this, requestedProfileMode) }
    }

    private fun rebuildSettingsSafely(reason: String) {
        if (isFinishing || isDestroyed) return
        formReady = false
        building = true
        runCatching {
            capabilityMatrix = CaptureCapabilityMatrix.cached(this)
            capabilityScanCompleted = capabilityMatrix != null
            buildForm(activeSettingsSnapshot())
        }.onFailure { error ->
            showSettingsRecovery(reason, error)
        }
    }

    private fun showSettingsRecovery(reason: String, error: Throwable) {
        formReady = false
        building = true
        saveGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        AppLogRepository.error(this, "SETTINGS_UI", "Falha ao montar Configuracoes ($reason)", error)
        helperBySpinner.clear()
        labelBySpinner.clear()
        resolutionSelections.clear()
        container.removeAllViews()
        settingsScroll.scrollTo(0, 0)

        addTitle(
            "Configurações",
            "Uma opção salva ou uma capacidade do aparelho não pôde ser carregada agora. O app permaneceu aberto e nenhum arquivo do cofre foi alterado."
        )
        addInfo("Você pode tentar novamente. Se uma preferência antiga estiver incompatível, restaure somente os ajustes de câmera; cofres, PINs e mídias não são apagados.")
        addSmallButton("Tentar abrir configurações novamente") {
            rebuildSettingsSafely("nova tentativa")
        }
        addSmallButton("Restaurar somente ajustes de câmera") {
            OneUiDialog.confirm(
                activity = this,
                title = "Restaurar ajustes de câmera?",
                message = "Restaura resolução, FPS, codec e controles da câmera. Cofres, PINs, mídias, navegador e apps protegidos permanecem intactos.",
                positiveLabel = "Restaurar",
                destructive = false
            ) {
                CaptureSettings.restoreDefaults(this)
                CaptureCapabilityMatrix.invalidate(this)
                CaptureStateStore.clearEffectiveMode(this)
                rebuildSettingsSafely("apos restaurar camera")
            }
        }
        Toast.makeText(this, "Configurações entraram em modo de recuperação em vez de fechar o app.", Toast.LENGTH_LONG).show()
    }

    private fun runUiAction(name: String, action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runCatching(action).onFailure { error ->
            AppLogRepository.error(this, "UI_ACTION", "Falha em $name", error)
            Haptics.error(this)
            Toast.makeText(this, error.message ?: "Não foi possível concluir esta ação agora.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        if (formReady && !building && ::autoSaveStatus.isInitialized) {
            saveGeneration++
            runCatching { saveCurrent() }
                .onFailure { AppLogRepository.error(this, "SETTINGS_SAVE", "Falha ao salvar ajustes no onPause", it) }
        }
        super.onPause()
    }
'''
replace_once(settings, old_oncreate, new_oncreate)

replace_once(
    settings,
    '''    private fun buildForm(snapshot: CaptureSettings.Snapshot) {
        building = true
''',
    '''    private fun buildForm(snapshot: CaptureSettings.Snapshot) {
        formReady = false
        building = true
''',
)

replace_once(
    settings,
    '''        mainHandler.post {
            building = false
            refreshCacheUsage()
        }
''',
    '''        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            formReady = true
            building = false
            runCatching { refreshCacheUsage() }
                .onFailure { AppLogRepository.warn(this, "SETTINGS_UI", "Falha ao atualizar cache", it) }
        }
''',
)

replace_once(
    settings,
    '''            setOnClickListener { action() }
''',
    '''            setOnClickListener { runUiAction("botao: $text", action) }
''',
)

replace_once(
    settings,
    '''                helper.text = item.description
                onSpinnerChanged(spinner)
''',
    '''                helper.text = item.description
                runUiAction("seletor: $label") { onSpinnerChanged(spinner) }
''',
)

replace_once(
    settings,
    '''        updateOptions(fps, fpsOptions(catalog), requestedFps.toString())
        var selectedFps = selected(fps).toInt()
        var capability = catalog.profile(selectedFps)
''',
    '''        updateOptions(fps, fpsOptions(catalog), requestedFps.toString())
        val selectedFps = selected(fps).toIntOrNull()
            ?.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: requestedFps.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: 60
        val capability = catalog.profile(selectedFps)
''',
)

replace_once(
    settings,
    '''        val incompatible = selected(codec) == CaptureSettings.CODEC_AVC ||
                selected(fps).toInt() >= 120 ||
                selectedCameraFeatures()?.hdrHlg10 == Support.UNSUPPORTED
''',
    '''        val activeFps = selected(fps).toIntOrNull() ?: editingFps
        val incompatible = selected(codec) == CaptureSettings.CODEC_AVC ||
                activeFps >= 120 ||
                selectedCameraFeatures()?.hdrHlg10 == Support.UNSUPPORTED
''',
)

replace_once(
    settings,
    '''        val highSpeed = selected(fps).toInt() >= 120
''',
    '''        val highSpeed = (selected(fps).toIntOrNull() ?: editingFps) >= 120
''',
)

replace_once(
    settings,
    '''        val action = Runnable {
            if (generation != saveGeneration || isDestroyed) return@Runnable
            saveCurrent()
        }
''',
    '''        val action = Runnable {
            if (generation != saveGeneration || isDestroyed || !formReady) return@Runnable
            runCatching { saveCurrent() }
                .onFailure { AppLogRepository.error(this, "SETTINGS_SAVE", "Falha no salvamento automatico", it) }
        }
''',
)

replace_once(
    settings,
    '''    private fun snapshotFromForm(
        base: CaptureSettings.Snapshot,
        fpsValue: Int = selected(fps).toInt(),
        resolutionValue: String = selected(resolution)
    ): CaptureSettings.Snapshot = base.copy(
''',
    '''    private fun snapshotFromForm(
        base: CaptureSettings.Snapshot,
        fpsValue: Int = selected(fps).toIntOrNull()
            ?.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: editingFps.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: base.fps,
        resolutionValue: String = selected(resolution).takeIf { it in CaptureSettings.supportedResolutionValues }
            ?: base.resolution
    ): CaptureSettings.Snapshot = base.copy(
''',
)

replace_once(
    settings,
    '''        iFrameIntervalSeconds = selected(iframe).toInt(),
''',
    '''        iFrameIntervalSeconds = selected(iframe).toIntOrNull()?.coerceIn(1, 10) ?: base.iFrameIntervalSeconds,
''',
)
replace_once(settings, '''        audioSampleRate = selected(audioSampleRate).toInt(),
''', '''        audioSampleRate = selected(audioSampleRate).toIntOrNull()?.takeIf { it == 44_100 || it == 48_000 } ?: base.audioSampleRate,
''')
replace_once(settings, '''        audioBitrateKbps = selected(audioBitrate).toInt(),
''', '''        audioBitrateKbps = selected(audioBitrate).toIntOrNull()?.coerceIn(96, 320) ?: base.audioBitrateKbps,
''')

replace_once(
    settings,
    '''    private fun saveCurrent() {
        if (building) return
''',
    '''    private fun saveCurrent() {
        if (building || !formReady) return
''',
)

replace_once(
    settings,
    '''    private fun selected(spinner: Spinner): String {
        val adapter = spinner.adapter as ChoiceSpinnerAdapter
        return adapter.valueAt(spinner.selectedItemPosition.coerceAtLeast(0))
    }
''',
    '''    private fun selected(spinner: Spinner): String {
        val adapter = spinner.adapter as? ChoiceSpinnerAdapter ?: return ""
        if (adapter.count <= 0) return ""
        val position = spinner.selectedItemPosition.coerceIn(0, adapter.count - 1)
        return runCatching { adapter.valueAt(position) }.getOrDefault("")
    }
''',
)

# Bottom navigation: never let a navigation callback or Activity launch terminate the app.
nav = "app/src/main/java/com/steadyvault/camera/ui/navigation/BottomNavigation.kt"
replace_once(nav, "import android.widget.TextView\n", "import android.widget.TextView\nimport android.widget.Toast\n")
replace_once(nav, "import com.steadyvault.camera.R\n", "import com.steadyvault.camera.R\nimport com.steadyvault.camera.core.diagnostics.AppLogRepository\n")

replace_once(
    nav,
    '''        val root = activity.findViewById<View>(R.id.bottomNavigationRoot)
        val record = activity.findViewById<TextView>(R.id.navRecord)
        val library = activity.findViewById<TextView>(R.id.navLibrary)
        val browser = activity.findViewById<TextView>(R.id.navBrowser)
        val apps = activity.findViewById<TextView>(R.id.navApps)
        val settings = activity.findViewById<TextView>(R.id.navSettings)
''',
    '''        val root = activity.findViewById<View>(R.id.bottomNavigationRoot) ?: return
        val record = activity.findViewById<TextView>(R.id.navRecord) ?: return
        val library = activity.findViewById<TextView>(R.id.navLibrary) ?: return
        val browser = activity.findViewById<TextView>(R.id.navBrowser) ?: return
        val apps = activity.findViewById<TextView>(R.id.navApps) ?: return
        val settings = activity.findViewById<TextView>(R.id.navSettings) ?: return
''',
)

replace_once(
    nav,
    '''        if (alreadyOpen) return
        onBeforeNavigate?.invoke()
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
        activity.startActivity(intent, options)
''',
    '''        if (alreadyOpen || activity.isFinishing || activity.isDestroyed) return
        runCatching { onBeforeNavigate?.invoke() }
            .onFailure { AppLogRepository.error(activity, "NAVIGATION", "Falha antes de navegar para ${target.simpleName}", it) }
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val result = runCatching {
            val options = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
            activity.startActivity(intent, options)
        }.recoverCatching {
            activity.startActivity(intent)
        }
        result.onFailure { error ->
            AppLogRepository.error(activity, "NAVIGATION", "Nao foi possivel abrir ${target.simpleName}", error)
            Toast.makeText(activity, "Não foi possível abrir esta tela agora.", Toast.LENGTH_LONG).show()
        }
''',
)

# New build so the device definitely installs the crash-safe variant.
build = "app/build.gradle.kts"
replace_once(build, 'versionCode = 1000147', 'versionCode = 1000148')

print("UI crash guards applied")
