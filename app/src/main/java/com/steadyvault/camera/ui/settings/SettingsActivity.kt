package com.steadyvault.camera.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaFormat
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.steadyvault.camera.R
import com.steadyvault.camera.core.capability.CapabilityReport
import com.steadyvault.camera.core.capability.CaptureCapabilityMatrix
import com.steadyvault.camera.core.capability.CaptureModeCatalog
import com.steadyvault.camera.core.capability.HardwareSupportPolicy
import com.steadyvault.camera.core.capability.HardwareSupportPolicy.Support
import com.steadyvault.camera.core.capability.PowerPolicy
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.playback.PlaybackSettings
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.CameraProfileStore
import com.steadyvault.camera.core.settings.RecordingDisplayPreferences
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.core.camera.CameraLensCatalog
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.storage.security.AppVaultLock
import com.steadyvault.camera.storage.security.ProtectedAppsStore
import com.steadyvault.camera.storage.vault.VaultCleanupRepository
import com.steadyvault.camera.storage.vault.VaultMediaCacheSettings
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.ui.components.ChoiceSpinnerAdapter
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.components.OneUiSpinner
import com.steadyvault.camera.ui.components.ScrollSafeSwitch
import com.steadyvault.camera.ui.components.ScrollSafeTextView
import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.widgets.WidgetPreviewPublisher
import com.steadyvault.camera.widgets.WidgetRenderer
import com.steadyvault.camera.ui.browser.PrivateBrowserStore
import com.steadyvault.camera.ui.capture.QuickCaptureLauncher
import com.steadyvault.camera.ui.apps.ProtectedAppsActivity
import com.steadyvault.camera.ui.apps.VaultScreenCaptureService
import com.steadyvault.camera.ui.vault.VaultBulkImportRunner
import com.steadyvault.camera.ui.vault.RecoveryActivity
import com.steadyvault.camera.ui.navigation.BottomNavigation
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.security.PinPadDialog
import java.util.concurrent.Executors

class SettingsActivity : FragmentActivity() {
    private lateinit var container: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var autoSaveStatus: TextView
    private lateinit var modeCapabilitiesText: TextView
    private lateinit var resolution: Spinner
    private lateinit var fps: Spinner
    private lateinit var codec: Spinner
    private lateinit var bitrate: Spinner
    private lateinit var iframe: Spinner
    private lateinit var hdr: Switch
    private lateinit var colorProfile: Spinner
    private lateinit var stabilization: Spinner
    private lateinit var focus: Spinner
    private lateinit var noiseReduction: Spinner
    private lateinit var edge: Spinner
    private lateinit var antibanding: Spinner
    private lateinit var exposure: Spinner
    private lateinit var thermal: Switch
    private lateinit var audioSampleRate: Spinner
    private lateinit var audioBitrate: Spinner
    private lateinit var audioChannels: Spinner
    private lateinit var audioGain: Spinner
    private lateinit var audioAgc: Switch
    private lateinit var audioNoise: Switch
    private lateinit var audioLowCut: Switch
    private lateinit var vibration: Switch
    private lateinit var secureScreen: Switch
    private lateinit var biometricUnlock: Switch
    private lateinit var autoLockTimeout: Spinner
    private lateinit var lockOnScreenOff: Switch
    private lateinit var trashRetention: Spinner
    private lateinit var whiteBalance: Spinner
    private lateinit var yellowReduction: Spinner
    private lateinit var lockWhiteBalance: Switch
    private lateinit var previewMode: Spinner
    private lateinit var intelligentPlayback: Switch
    private lateinit var dropLateFrames: Switch
    private lateinit var prebuffer4k60: Switch
    private lateinit var playbackCache: Spinner
    private lateinit var autoRecoverStalls: Switch
    private lateinit var openVideosExternally: Switch
    private lateinit var openPhotosExternally: Switch
    private lateinit var mediaDetailsLoadingMode: Spinner
    private lateinit var cacheUsageText: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val cacheExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-CacheSettings")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val helperBySpinner = mutableMapOf<Spinner, TextView>()
    private val labelBySpinner = mutableMapOf<Spinner, TextView>()
    private val resolutionSelections = mutableMapOf<Int, String>()
    private var capabilityMatrix: CaptureCapabilityMatrix.Matrix? = null
    private var capabilityScanCompleted = false
    private var capabilityScanInProgress = false
    private var editingFps = 60
    private var building = false
    private var saveGeneration = 0
    private var cacheUsageRefreshGeneration = 0
    private var protectedCaptureFlowPending = false
    private var pendingProtectedCaptureDestination =
        VaultScreenCaptureService.DESTINATION_PRIMARY

    private val protectedProjectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                cancelProtectedCaptureFlow()
                Toast.makeText(this, "Captura de tela cancelada.", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    VaultScreenCaptureService.startIntent(
                        this,
                        result.resultCode,
                        data,
                        pendingProtectedCaptureDestination
                    )
                )
            }.onSuccess {
                protectedCaptureFlowPending = false
                Haptics.success(this)
                Toast.makeText(
                    this,
                    "Controle protegido ativado. Abra um app e use foto ou gravação.",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Haptics.error(this)
                cancelProtectedCaptureFlow()
                Toast.makeText(
                    this,
                    it.message ?: "Não foi possível iniciar a captura.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val protectedOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestProtectedNotificationThenProjection()
            } else {
                cancelProtectedCaptureFlow()
                Toast.makeText(
                    this,
                    "Autorize 'Exibir sobre outros apps' para usar os botões de captura.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val protectedNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestProtectedProjectionPermission()
            } else {
                cancelProtectedCaptureFlow()
                Toast.makeText(
                    this,
                    "Ative as notificações para manter a captura protegida em execução.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) {
                    "Microfone liberado. As próximas gravações incluirão áudio."
                } else {
                    "O vídeo continuará sendo gravado normalmente sem áudio."
                },
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        if (capabilityMatrix == null) scanCapabilities(force = true)
    }

    override fun onPause() {
        if (!building && ::autoSaveStatus.isInitialized) {
            saveGeneration++
            saveCurrent()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (protectedCaptureFlowPending) cancelProtectedCaptureFlow()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        cacheExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildForm(snapshot: CaptureSettings.Snapshot) {
        building = true
        helperBySpinner.clear()
        labelBySpinner.clear()
        container.removeAllViews()
        val activeCamera = CameraLensCatalog.labelFor(this, snapshot.selectedCameraId)
        val activeFunction = if (CameraProfileStore.activeMode(this) == CameraProfileStore.FunctionMode.PHOTO) "Foto" else "Vídeo"
        addTitle(
            "Ajustes profissionais",
            "Perfil ativo: $activeCamera • $activeFunction. As alterações são salvas somente para esta câmera e função; segurança, cofre, áudio e otimização continuam globais."
        )
        autoSaveStatus = TextView(this).apply {
            text = "✓ Alterações salvas automaticamente"
            setTextColor(AppearanceStore.palette(this@SettingsActivity).accent)
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(42)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setBackgroundResource(R.drawable.bg_card)
        }
        container.addView(autoSaveStatus, matchWrap(bottom = 10))

        CaptureSettings.supportedFpsValues.forEach { value ->
            resolutionSelections[value] = CaptureSettings.resolutionForFps(this, value)
        }
        resolutionSelections[snapshot.fps] = snapshot.resolution
        editingFps = snapshot.fps

        addSection("Vídeo")
        val initialFps = snapshot.fps
        editingFps = initialFps
        resolution = addSpinner(
            "Resolução do arquivo de vídeo",
            resolutionOptions(initialFps),
            resolutionSelections[initialFps] ?: snapshot.resolution
        )
        fps = addSpinner(
            "Taxa de quadros da gravação (FPS)",
            fpsOptions(),
            snapshot.fps.toString()
        )
        modeCapabilitiesText = addCapabilitiesCard()
        codec = addSpinner(
            "Formato de compressão do vídeo (codec)",
            codecOptions(snapshot),
            snapshot.codec
        )
        bitrate = addSpinner(
            "Taxa de dados do vídeo (bitrate)",
            bitrateOptions(snapshot.bitrateMbps),
            snapshot.bitrateMbps.toString()
        )
        addInfo("O bitrate escolhido fica salvo mesmo ao mudar FPS, resolução, codec ou estabilização. Ele é enviado diretamente ao encoder e só é limitado se ultrapassar o intervalo que o próprio codec de hardware publica como suportado; o app não reduz o valor por perfil automático.")
        addSmallButton("Recalcular bitrate recomendado") {
            setSelection(
                bitrate,
                CaptureSettings.defaultBitrateMbps(
                    selected(resolution),
                    selected(fps).toInt(),
                    selected(codec)
                ).toString()
            )
            scheduleSave()
        }
        iframe = addSpinner(
            "Intervalo entre quadros-chave (I-frame)",
            listOf(
                option("1", "1 segundo", "Busca e edição mais rápidas, com leve aumento do tamanho."),
                option("2", "2 segundos", "Equilíbrio recomendado para gravação normal."),
                option("5", "5 segundos", "Compressão um pouco melhor."),
                option("10", "10 segundos", "Menos quadros-chave; não recomendado para arquivos frágeis.")
            ),
            snapshot.iFrameIntervalSeconds.toString()
        )
        addInfo("Pipeline único do encoder: prioridade em tempo real, VBR quando suportado, B-frames desativados, baixa latência, taxa operacional exata e descarte de frames bloqueado.")
        hdr = addSwitch(
            "HDR HLG10",
            "Usa HEVC Main10, BT.2020 HLG e faixa limitada. Combinações incompatíveis são informadas em vez de serem trocadas silenciosamente.",
            snapshot.hdrHlg10
        )
        colorProfile = addSpinner(
            "Perfil de cor da gravação",
            colorProfileOptions(snapshot),
            snapshot.colorProfile
        )
        addInfo("A prioridade de fluidez fica sempre ativa: o app usa uma única Surface do encoder e preserva os timestamps reais sem interpolar, repetir ou remapear quadros.")

        addSection("Câmera e estabilização")
        stabilization = addSpinner(
            "Estabilização da imagem",
            stabilizationOptions(snapshot.stabilization),
            snapshot.stabilization
        )
        addInfo("A estabilização aplicada é exatamente a escolhida aqui. O app não troca sozinho entre OIS, EIS, Preview stabilization e Off.")
        focus = addSpinner(
            "Modo de foco da câmera",
            focusOptions(snapshot.focusMode),
            snapshot.focusMode
        )
        noiseReduction = addSpinner(
            "Redução de ruído da imagem",
            processingOptions(noise = true, currentValue = snapshot.noiseReduction),
            snapshot.noiseReduction
        )
        edge = addSpinner(
            "Processamento de nitidez e contornos",
            processingOptions(noise = false, currentValue = snapshot.edgeMode),
            snapshot.edgeMode
        )
        antibanding = addSpinner(
            "Correção de cintilação da iluminação",
            antibandingOptions(snapshot.antibanding),
            snapshot.antibanding
        )
        whiteBalance = addSpinner(
            "Balanço de branco (temperatura de cor)",
            whiteBalanceOptions(snapshot.whiteBalanceMode),
            snapshot.whiteBalanceMode
        )
        yellowReduction = addSpinner(
            "Correção de dominante amarela",
            yellowReductionOptions(snapshot.yellowReduction),
            snapshot.yellowReduction
        )
        lockWhiteBalance = addSwitch(
            "Travar cor ao começar a gravar",
            "Depois que a câmera estabiliza, impede mudanças de amarelo para azul no meio do vídeo.",
            snapshot.lockWhiteBalance
        )
        previewMode = addSpinner(
            "Visualização da câmera antes de capturar",
            listOf(
                option(
                    CaptureSettings.PREVIEW_OFF,
                    "Somente pelo botão de conferência",
                    "O preview abre por cima da tela apenas quando solicitado e é fechado antes de foto ou gravação."
                )
            ),
            CaptureSettings.PREVIEW_OFF
        )
        exposure = addSpinner(
            "Compensação de exposição (brilho)",
            exposureOptions(snapshot.exposureCompensation),
            snapshot.exposureCompensation.toString()
        )
        addInfo("A faixa fixa é sempre priorizada e o perfil VIDEO_RECORD é aplicado automaticamente quando a câmera o publica.")
        thermal = addSwitch(
            "Proteção contra temperatura crítica",
            "Antes de iniciar, verifica a condição térmica do aparelho para evitar começar uma captura quando o sistema já está em estado crítico.",
            snapshot.thermalProtection
        )

        addSection("Gravação discreta")
        addSwitch(
            "Tela preta ao gravar pelo app",
            "Vale somente para gravação sem preview. Dois toques rápidos na tela preta param a gravação, confirmam com vibração quando habilitada e saem da tela discreta.",
            RecordingDisplayPreferences.appHeadless(this)
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) RecordingDisplayPreferences.setAppHeadless(this, checked)
        }
        addSwitch(
            "Tela preta ao gravar por widget",
            "Quando o widget inicia uma gravação sem preview, abre a tela discreta. Desligado mantém o início totalmente em segundo plano.",
            RecordingDisplayPreferences.widget(this)
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) RecordingDisplayPreferences.setWidget(this, checked)
        }
        addSwitch(
            "Tela preta nos atalhos rápidos",
            "Aplica aos ícones rápidos de gravação. Na tela preta, dois toques rápidos param a gravação e saem imediatamente.",
            RecordingDisplayPreferences.quickShortcut(this)
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) RecordingDisplayPreferences.setQuickShortcut(this, checked)
        }
        addInfo("A tela preta não cria preview nem uma segunda sessão de câmera. Gravações iniciadas com preview continuam usando somente o preview solicitado e não entram neste modo discreto.")

        addSection("Áudio")
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            addInfo("O microfone é opcional e nunca bloqueia o início do vídeo. Libere-o aqui para incluir som nas próximas gravações.")
            addSmallButton("Liberar microfone") {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            addInfo("Microfone liberado. Se ele falhar ou demorar, o vídeo continua sem interrupção.")
        }
        audioSampleRate = addSpinner(
            "Taxa de amostragem do áudio",
            listOf(
                option("48000", "48 kHz", "Padrão de vídeo e opção recomendada."),
                option("44100", "44,1 kHz", "Compatível com música, mas exige conversão em alguns fluxos.")
            ),
            snapshot.audioSampleRate.toString()
        )
        audioBitrate = addSpinner(
            "Qualidade do áudio AAC (bitrate)",
            listOf(
                option("128", "128 kbps", "Economiza espaço e mantém voz e som ambiente com boa inteligibilidade."),
                option("192", "192 kbps", "Aumenta a fidelidade para música e ambientes complexos sem crescer demais o arquivo."),
                option("256", "256 kbps", "Equilíbrio recomendado entre fidelidade, dinâmica e tamanho do áudio."),
                option("320", "320 kbps", "Maior bitrate disponível; prioriza detalhes do áudio e gera uma faixa um pouco maior.")
            ),
            snapshot.audioBitrateKbps.toString()
        )
        audioChannels = addSpinner(
            "Canais de áudio gravados",
            listOf(
                option(CaptureSettings.CHANNELS_AUTO, "Automático", "Tenta estéreo e usa mono se necessário."),
                option(CaptureSettings.CHANNELS_MONO, "Mono", "Menor tamanho e maior compatibilidade."),
                option(CaptureSettings.CHANNELS_STEREO, "Estéreo", "Preserva separação entre canais quando o aparelho oferece.")
            ),
            snapshot.audioChannels
        )
        audioGain = addSpinner(
            "Limite de ganho do microfone (dB)",
            audioGainOptions(snapshot.audioGainDb),
            snapshot.audioGainDb.toString()
        )
        audioAgc = addSwitch("Controle automático de ganho do microfone (AGC)", "O Android ajusta o nível do áudio automaticamente; ajuda em fontes baixas, mas pode variar o volume e causar efeito de bombeamento.", snapshot.audioAgc)
        audioNoise = addSwitch("Supressão de ruído do microfone", "Usa o processador de áudio do aparelho para reduzir ruído contínuo de fundo quando o hardware oferece esse recurso.", snapshot.audioNoiseSuppressor)
        audioLowCut = addSwitch("Filtro de graves muito baixos e vento", "Atenua frequências abaixo de aproximadamente 75 Hz para reduzir vibração, manuseio e parte do ruído de vento.", snapshot.audioLowCut)
        if (!runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)) {
            audioAgc.isChecked = false
            audioAgc.visibility = View.GONE
        }
        if (!runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
            audioNoise.isChecked = false
            audioNoise.visibility = View.GONE
        }

        addSection("Processamento de vídeo")
        addInfo("Nada é processado automaticamente depois da captura. O MP4 direto é o arquivo final; otimização continua disponível somente por ação manual no Cofre.")

        val playback = PlaybackSettings.snapshot(this)
        addSection("Reprodução")
        intelligentPlayback = addSwitch("Ajustar a reprodução automaticamente", "Analisa FPS, bitrate, resolução e timestamps do arquivo para escolher a estratégia de reprodução mais estável sem alterar o vídeo original.", playback.intelligentPlayback)
        dropLateFrames = addSwitch("Descartar quadros atrasados na reprodução", "Quando o aparelho não acompanha um vídeo pesado, prioriza manter o tempo do vídeo avançando em vez de acumular quadros atrasados e engasgar.", playback.dropLateFrames)
        prebuffer4k60 = addSwitch("Usar pré-buffer extra em vídeos 4K60", "Carrega mais dados antes e durante a reprodução de 4K60 com bitrate alto para reduzir pausas causadas por leitura ou decodificação irregular.", playback.prebuffer4k60)
        autoRecoverStalls = addSwitch("Recuperar travamentos do player automaticamente", "Se a reprodução parar de avançar, tenta outro perfil de decodificação e retoma no mesmo ponto sem modificar o arquivo.", playback.autoRecoverStalls)
        openVideosExternally = addSwitch("Usar o player do celular para vídeos do cofre", "Ao abrir um vídeo, envia acesso temporário somente de leitura ao player padrão do Android/Samsung em vez de usar o player interno.", playback.openVideosExternally)
        openPhotosExternally = addSwitch("Usar a galeria do celular para fotos do cofre", "Ao abrir uma foto, envia acesso temporário somente de leitura ao visualizador padrão do Android/Samsung em vez de usar o visualizador interno.", playback.openPhotosExternally)
        playbackCache = addSpinner("Buffer do player em modo de compatibilidade (ms)", playbackCacheOptions(playback.fileCacheMs), playback.fileCacheMs.toString())
        addInfo("O Media3 nativo é usado primeiro por fluidez. Se ele falhar, o player tenta perfis de compatibilidade e o VLC automaticamente. Falhas gravadas nos timestamps ainda podem exigir a ferramenta Otimizar vídeo. Ao abrir fora do app, o arquivo é compartilhado somente com permissão temporária de leitura.")

        addSection("Privacidade e cofres")
        vibration = addSwitch("Vibrar quando a gravação realmente iniciar e terminar", "Emite uma confirmação tátil após o início efetivo da captura e outra quando o arquivo termina de ser salvo.", snapshot.vibrateStartStop)
        secureScreen = addSwitch("Impedir screenshots e prévia dos cofres", "Com PIN ativo, bloqueia capturas de tela e impede que o conteúdo do cofre apareça na miniatura de aplicativos recentes.", snapshot.secureScreen)
        val biometricAvailable = VaultSecuritySettings.canUseBiometrics(this)
        biometricUnlock = addSwitch(
            "Desbloqueio biométrico",
            when {
                !PrimaryVaultLock.isEnabled(this) -> "Crie primeiro um PIN do cofre principal para ativar a biometria."
                !biometricAvailable -> "Nenhuma biometria cadastrada ou disponível no aparelho."
                else -> "Confirma sua identidade e depois permite escolher qual cofre configurado será aberto."
            },
            PrimaryVaultLock.isEnabled(this) && biometricAvailable && VaultSecuritySettings.biometricEnabled(this)
        ).apply {
            isEnabled = PrimaryVaultLock.isEnabled(this@SettingsActivity) && biometricAvailable
            alpha = if (isEnabled) 1f else 0.45f
            visibility = if (biometricAvailable) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, checked ->
                if (!building) VaultSecuritySettings.setBiometricEnabled(this@SettingsActivity, checked)
            }
        }
        if (PrimaryVaultLock.isEnabled(this) && biometricAvailable && VaultSecuritySettings.biometricEnabled(this)) {
            addSmallButton("Biometria: ${biometricTargetLabel()}") { showBiometricTargetSettings() }
        }
        autoLockTimeout = addSpinner(
            "Tempo para bloquear os cofres ao sair do app",
            listOf(
                option(VaultSecuritySettings.TIMEOUT_IMMEDIATE.toString(), "Imediatamente", "Bloqueia assim que o SteadyVault deixa de estar em primeiro plano."),
                option(VaultSecuritySettings.TIMEOUT_15_SECONDS.toString(), "Após 15 segundos", "Útil para alternações rápidas entre aplicativos."),
                option(VaultSecuritySettings.TIMEOUT_1_MINUTE.toString(), "Após 1 minuto", "Mantém uma sessão curta antes de exigir autenticação."),
                option(VaultSecuritySettings.TIMEOUT_5_MINUTES.toString(), "Após 5 minutos", "Equilíbrio entre praticidade e privacidade."),
                option(VaultSecuritySettings.TIMEOUT_15_MINUTES.toString(), "Após 15 minutos", "Menos solicitações de autenticação."),
                option(VaultSecuritySettings.TIMEOUT_NEVER.toString(), "Somente manualmente", "O cofre continua desbloqueado até bloquear, apagar a tela ou reiniciar o app.")
            ),
            VaultSecuritySettings.timeoutMs(this).toString()
        )
        lockOnScreenOff = addSwitch(
            "Bloquear ao apagar a tela",
            "Bloqueia imediatamente ao desligar a tela, independentemente do tempo acima.",
            VaultSecuritySettings.lockOnScreenOff(this)
        ).apply {
            setOnCheckedChangeListener { _, checked ->
                if (!building) VaultSecuritySettings.setLockOnScreenOff(this@SettingsActivity, checked)
            }
        }
        trashRetention = addSpinner(
            "Prazo para apagar itens da lixeira privada",
            listOf(
                option(VaultTrashRepository.RETENTION_7_DAYS.toString(), "Após 7 dias", "Libera espaço rapidamente."),
                option(VaultTrashRepository.RETENTION_15_DAYS.toString(), "Após 15 dias", "Tempo intermediário para recuperação."),
                option(VaultTrashRepository.RETENTION_30_DAYS.toString(), "Após 30 dias", "Padrão recomendado."),
                option(VaultTrashRepository.RETENTION_NEVER.toString(), "Nunca automaticamente", "Você deverá esvaziar a lixeira manualmente.")
            ),
            VaultTrashRepository.retentionDays(this).toString()
        )
        addSmallButton(if (PrimaryVaultLock.isEnabled(this)) "Gerenciar PIN do cofre principal" else "Criar PIN do cofre principal") { managePin() }
        addSmallButton(if (SecondaryVaultLock.isEnabled(this)) "Gerenciar cofre secundário" else "Configurar cofre secundário") { manageSecondaryVault() }
        addSmallButton(if (TertiaryVaultLock.isEnabled(this)) "Gerenciar cofre terciário" else "Configurar cofre terciário") { manageTertiaryVault() }
        addSmallButton("Central de recuperação dos cofres") {
            startActivity(Intent(this, RecoveryActivity::class.java))
        }
        addInfo("Cofre principal, Cofre secundário e Cofre terciário continuam separados e usam PINs exclusivos. A tela bloqueada não revela quantos cofres existem; essa escolha aparece somente após autenticar ou pode ser associada à biometria nos ajustes.")
        addInfo("Ao excluir, você pode usar a lixeira privada para restaurar depois ou apagar direto sem recuperação. Álbuns são opcionais e apenas organizam o cofre principal, sem alterar o cofre secundário.")
        addInfo("As mídias ficam privadas até serem exportadas. Desinstalar o aplicativo pode apagar o cofre; exporte cópias importantes.")

        addSection("Apps protegidos")
        addSwitch(
            "Biometria para a aba Apps",
            when {
                !AppVaultLock.isEnabled(this) -> "Crie primeiro um PIN exclusivo para os apps."
                !biometricAvailable -> "Nenhuma biometria cadastrada ou disponível no aparelho."
                else -> "Protege a lista de atalhos separadamente dos cofres de mídia."
            },
            AppVaultLock.isEnabled(this) && biometricAvailable && ProtectedAppsStore.biometricEnabled(this)
        ).apply {
            isEnabled = AppVaultLock.isEnabled(this@SettingsActivity) && biometricAvailable
            alpha = if (isEnabled) 1f else 0.45f
            setOnCheckedChangeListener { _, checked ->
                if (!building) ProtectedAppsStore.setBiometricEnabled(this@SettingsActivity, checked && isEnabled)
            }
        }
        addSmallButton(if (AppVaultLock.isEnabled(this)) "Gerenciar PIN dos apps" else "Criar PIN dos apps") {
            manageAppsPin()
        }
        addSmallButton("Abrir aba Apps protegidos") {
            startActivity(Intent(this, ProtectedAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        addInfo("Os ícones extras de foto e vídeo são controlados diretamente na seção Atalhos e disfarces abaixo. O app não abre mais uma tela genérica da One UI que não consegue alterar esses componentes.")
        addSmallButton("Ativar controles protegidos de print e gravação de tela") {
            Haptics.tap(this)
            chooseProtectedCaptureDestination()
        }
        addInfo("A aba Apps fica somente com os atalhos e o botão Adicionar/remover. PIN, biometria, ocultação de ícones e autorização de captura são configurados aqui. A lista usa apenas aplicativos lançáveis do perfil normal e não lê Pasta Segura nem perfil de trabalho.")


        addSection("Execução em segundo plano")
        addInfo(
            if (PowerPolicy.isIgnoring(this))
                "O SteadyVault está fora da otimização de bateria. Gravação e importação ainda obedecem aos limites obrigatórios do Android, mas o sistema não deve aplicar a otimização comum de bateria ao app."
            else
                "A otimização de bateria pode suspender trabalhos longos. Para gravações e importações extensas, permita execução sem otimização quando o aparelho oferecer essa opção."
        )
        addSmallButton(if (PowerPolicy.isIgnoring(this)) "Bateria: sem otimização" else "Configurar bateria sem restrições") {
            Haptics.tap(this)
            if (!PowerPolicy.openSettings(this)) Toast.makeText(this, "O aparelho não expôs uma tela compatível de otimização de bateria", Toast.LENGTH_LONG).show()
        }
        addInfo("Importações usam um serviço em primeiro plano com notificação cancelável e fila persistente. No Android 15+ o limite de tempo de dataSync é imposto pelo sistema; ao atingir o teto, a fila é pausada e preservada em vez de ser perdida.")

        addSection("Cache e desempenho")
        mediaDetailsLoadingMode = addSpinner(
            "Pré-carregamento dos detalhes da mídia",
            listOf(
                option(
                    VaultMediaCacheSettings.DETAILS_ON_MENU_OPEN,
                    "Ao abrir os três pontos (recomendado)",
                    "Prepara somente a mídia selecionada em segundo plano; ao tocar em Detalhes, normalmente abre na hora."
                ),
                option(
                    VaultMediaCacheSettings.DETAILS_ON_DEMAND,
                    "Somente ao tocar em Detalhes",
                    "Evita a leitura antecipada, mas pode exibir uma espera curta antes de mostrar as informações."
                )
            ),
            VaultMediaCacheSettings.detailsLoadingMode(this)
        )
        cacheUsageText = addInfo("USO DOS CACHES\nCalculando miniaturas, detalhes e temporários…")
        addSmallButton("Atualizar uso dos caches") { refreshCacheUsage() }
        addSmallButton("Limpar caches de mídia") { confirmCleanCacheAndDirtyData() }
        addSmallButton("Diagnóstico, logs e armazenamento") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        addInfo("A limpeza não apaga fotos, vídeos, lixeira, álbuns, favoritos do navegador nem configurações da câmera. Miniaturas e detalhes necessários são recriados automaticamente.")

        addSection("Navegador e downloads")
        addSmallButton("Destino dos downloads: ${PrivateBrowserStore.destinationLabel(PrivateBrowserStore.downloadDestination(this))}") { chooseBrowserDownloadDestination() }
        addSmallButton("Qualidade preferida dos downloads: ${PrivateBrowserStore.mediaPreferenceSummary(this)}") { chooseBrowserMediaQuality() }
        addSmallButton("Opções avançadas do downloader: ${PrivateBrowserStore.advancedSummary(this)}") { showAdvancedDownloaderSettings() }
        addSmallButton(if (PrivateBrowserStore.thirdPartyCookiesEnabled(this)) "Cookies de terceiros: permitidos" else "Cookies de terceiros: bloqueados") { toggleBrowserCookies() }
        addInfo("O downloader usa o link original quando possível e análise de formatos somente quando necessário. Pode baixar vídeo, áudio e imagens para o cofre escolhido sem expor uma marca ou módulo separado no aplicativo.")
        addInfo("O navegador abre em modo privado: não salva histórico local e mantém apenas os favoritos. O bloqueador remove anúncios, rastreadores e pop-ups comuns.")

        addSection("Aparência")
        addInfo("A tela de ajustes usa proteção contra toques durante a rolagem. Seletores só alteram o valor após tocar em Aplicar, e chaves ignoram gestos de arrastar.")
        addSmallButton("Tema visual do app: ${AppearanceStore.themeLabel(AppearanceStore.theme(this))}") { chooseAppearanceTheme() }
        addSmallButton("Estilo do fundo: ${AppearanceStore.surfaceLabel(AppearanceStore.surfaceStyle(this))}") { chooseAppearanceSurface() }
        addSmallButton("Contraste da interface: ${AppearanceStore.contrastLabel(AppearanceStore.contrast(this))}") { chooseAppearanceContrast() }
        addSmallButton("Fonte da interface: ${AppearanceStore.fontLabel(AppearanceStore.font(this))}") { chooseAppearanceFont() }
        addSmallButton("Arredondamento dos cantos: ${AppearanceStore.cornerLabel(AppearanceStore.corners(this))}") { chooseAppearanceCorners() }
        addSmallButton("Contorno dos controles: ${AppearanceStore.borderLabel(AppearanceStore.borders(this))}") { chooseAppearanceBorders() }
        addSmallButton("Tamanho e espaçamento dos controles: ${AppearanceStore.densityLabel(AppearanceStore.density(this))}") { chooseAppearanceDensity() }
        addSmallButton("Identidade visual dos widgets e notificações: ${VisualIdentityStore.summary(this)}") { chooseVisualIdentity() }
        addSmallButton("Nome discreto exibido: ${VisualIdentityStore.customLabel(this).ifBlank { "usar nome do perfil" }}") { editVisualIdentityLabel() }
        addSwitch(
            "Ações com texto neutro",
            "Troca rótulos como ‘Parar e salvar’/‘Cancelar importação’ por termos neutros, preservando exatamente a mesma função.",
            VisualIdentityStore.neutralActions(this)
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) {
                VisualIdentityStore.setNeutralActions(this, checked)
                WidgetRenderer.updateAll(this)
                WidgetPreviewPublisher.publishIfNeeded(this, force = true)
            }
        }
        addSwitch(
            "Ícones neutros nas ações dos widgets",
            "Troca câmera/gravação por símbolos genéricos de grade, adicionar, reproduzir e parar. Posições e funções permanecem iguais.",
            VisualIdentityStore.neutralWidgetActions(this)
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) {
                VisualIdentityStore.setNeutralWidgetActions(this, checked)
                WidgetRenderer.updateAll(this)
                WidgetPreviewPublisher.publishIfNeeded(this, force = true)
            }
        }
        addInfo("A identidade discreta altera somente ícones e rótulos do SteadyVault. Notificações obrigatórias do Android continuam visíveis e acionáveis; o app não imita aplicativos do sistema.")
        addInfo("A personalização é aplicada globalmente ao SteadyVault, inclusive aos widgets. Em Android 15 ou superior o seletor recebe uma prévia gerada com o mesmo layout, tema, ícones e estado ocioso do widget real; versões anteriores usam o próprio layout real como preview estático.")

        addLauncherShortcutSettings()

        addSection("Diagnóstico")
        addSmallButton("Ver e apagar os dados salvos pelo app") { startActivity(Intent(this, StorageManagementActivity::class.java)) }
        addSmallButton("Ver capacidades reais da câmera e dos encoders") { showCapabilities() }
        addSmallButton("Reanalisar capacidades do hardware") { reanalyzeHardware() }
        addSmallButton("Restaurar padrões estáveis") { confirmRestore() }

        bindAutoSaveListeners()
        refreshCompatibilityOptions()
        refreshHardwareFeatureOptions()
        refreshDependentControls()
        mainHandler.post {
            building = false
            refreshCacheUsage()
        }
    }


    private fun addLauncherShortcutSettings() {
        val state = QuickCaptureLauncher.snapshot(this)
        addSection("Atalhos e disfarces")

        lateinit var photoProfileButton: TextView
        photoProfileButton = addSmallButton("Foto — nome e ícone: ${state.photoProfile.label}") {
            chooseLauncherProfile(QuickCaptureLauncher.Kind.PHOTO) { profile ->
                photoProfileButton.text = "Foto — nome e ícone: ${profile.label}"
            }
        }
        lateinit var videoProfileButton: TextView
        videoProfileButton = addSmallButton("Vídeo — nome e ícone: ${state.videoProfile.label}") {
            chooseLauncherProfile(QuickCaptureLauncher.Kind.VIDEO) { profile ->
                videoProfileButton.text = "Vídeo — nome e ícone: ${profile.label}"
            }
        }

        addSwitch(
            "Atalhos ao segurar o ícone principal",
            "Mostra as ações de foto e vídeo no menu do launcher, com os nomes e ícones escolhidos acima.",
            state.longPressEnabled
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) applyLauncherSetting {
                QuickCaptureLauncher.setLongPressEnabled(this, checked)
            }
        }

        addSwitch(
            "Ícone de foto na gaveta de apps",
            "Cria um segundo ícone opcional que abre e tira a foto; o SteadyVault original continua igual.",
            state.photoDrawerEnabled
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) applyLauncherSetting {
                QuickCaptureLauncher.setDrawerEnabled(this, QuickCaptureLauncher.Kind.PHOTO, checked)
            }
        }

        addSwitch(
            "Ícone de vídeo na gaveta de apps",
            "Cria outro ícone opcional que abre e inicia o vídeo; não altera a tela Gravar.",
            state.videoDrawerEnabled
        ).setOnCheckedChangeListener { _, checked ->
            if (!building) applyLauncherSetting {
                QuickCaptureLauncher.setDrawerEnabled(this, QuickCaptureLauncher.Kind.VIDEO, checked)
            }
        }

        addSmallButton("Adicionar atalho de foto à tela inicial") {
            requestPinnedLauncherShortcut(QuickCaptureLauncher.Kind.PHOTO)
        }
        addSmallButton("Adicionar atalho de vídeo à tela inicial") {
            requestPinnedLauncherShortcut(QuickCaptureLauncher.Kind.VIDEO)
        }
        addInfo("São três opções independentes: menu ao segurar, atalho fixado na tela inicial e ícone semelhante a um app na gaveta. Os ícones adicionais podem ser desligados aqui sem remover nem renomear o SteadyVault principal.")
    }

    private fun chooseLauncherProfile(
        kind: QuickCaptureLauncher.Kind,
        onChanged: (QuickCaptureLauncher.Profile) -> Unit
    ) {
        val profiles = QuickCaptureLauncher.profiles(kind)
        val current = QuickCaptureLauncher.snapshot(this).profile(kind)
        val actionLabel = if (kind == QuickCaptureLauncher.Kind.PHOTO) "foto" else "vídeo"
        OneUiDialog.choices(
            activity = this,
            title = "Nome e ícone de $actionLabel",
            message = "A escolha vale para os três formatos de atalho. O ícone principal do SteadyVault não muda.",
            choices = profiles.map { profile ->
                OneUiDialog.Choice(profile.label, profile.description)
            },
            selectedIndex = profiles.indexOfFirst { it.id == current.id },
            cancelLabel = "Cancelar",
            confirmLabel = "OK"
        ) { index ->
            val selected = profiles[index]
            val applied = QuickCaptureLauncher.setProfile(this, kind, selected.id)
            onChanged(selected)
            Haptics.tap(this)
            Toast.makeText(
                this,
                if (applied) "Atalhos atualizados: ${selected.label}" else "A escolha foi salva e será aplicada pelo launcher",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyLauncherSetting(action: () -> Boolean) {
        if (!action()) {
            Toast.makeText(this, "A opção foi salva e será aplicada na próxima abertura", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPinnedLauncherShortcut(kind: QuickCaptureLauncher.Kind) {
        val label = QuickCaptureLauncher.snapshot(this).profile(kind).label
        QuickCaptureLauncher.requestPinnedShortcut(this, kind) { result ->
            if (isFinishing || isDestroyed) return@requestPinnedShortcut
            when (result) {
                QuickCaptureLauncher.PinResult.REQUESTED -> Toast.makeText(
                    this,
                    "Confirme no launcher para adicionar $label",
                    Toast.LENGTH_LONG
                ).show()
                QuickCaptureLauncher.PinResult.UNSUPPORTED -> Toast.makeText(
                    this,
                    "Este launcher não permite adicionar atalhos automaticamente",
                    Toast.LENGTH_LONG
                ).show()
                QuickCaptureLauncher.PinResult.FAILED -> Toast.makeText(
                    this,
                    "Não foi possível solicitar o atalho agora",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    private fun chooseAppearanceTheme() {
        val values = AppearanceStore.themeValues
        OneUiDialog.choices(
            activity = this,
            title = "Tema do SteadyVault",
            message = "Escolha a paleta principal. A seleção só é aplicada depois de confirmar.",
            choices = values.map { OneUiDialog.Choice(AppearanceStore.themeLabel(it), AppearanceStore.themeSubtitle(it)) },
            selectedIndex = values.indexOf(AppearanceStore.theme(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setTheme(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceSurface() {
        val values = AppearanceStore.surfaceValues
        OneUiDialog.choices(
            activity = this,
            title = "Fundo e superfícies",
            message = "Mantém a cor de destaque do tema e muda somente o fundo dos cartões e da interface.",
            choices = listOf(
                OneUiDialog.Choice("Cor do tema", "Usa o fundo desenhado especificamente para a paleta selecionada."),
                OneUiDialog.Choice("Neutro", "Remove a tonalidade do fundo e mantém apenas a cor de destaque."),
                OneUiDialog.Choice("Preto puro", "Fundo OLED preto com a cor de destaque do tema atual.")
            ),
            selectedIndex = values.indexOf(AppearanceStore.surfaceStyle(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setSurfaceStyle(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceContrast() {
        val values = AppearanceStore.contrastValues
        OneUiDialog.choices(
            activity = this,
            title = "Contraste da interface",
            message = "Aumenta a separação entre cartões, contornos e estados ligado/desligado das chaves.",
            choices = listOf(
                OneUiDialog.Choice("Padrão", "Visual mais discreto, mantendo contraste seguro."),
                OneUiDialog.Choice("Forte", "Recomendado: chaves e contornos ficam mais fáceis de distinguir."),
                OneUiDialog.Choice("Máximo", "Maior separação visual possível para controles e superfícies.")
            ),
            selectedIndex = values.indexOf(AppearanceStore.contrast(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setContrast(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceFont() {
        val values = AppearanceStore.fontValues
        OneUiDialog.choices(
            activity = this,
            title = "Fonte do aplicativo",
            message = "A fonte é aplicada a títulos, botões, menus e textos do SteadyVault sem alterar o conteúdo dos arquivos.",
            choices = values.map { OneUiDialog.Choice(AppearanceStore.fontLabel(it)) },
            selectedIndex = values.indexOf(AppearanceStore.font(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setFont(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceCorners() {
        val values = AppearanceStore.cornerValues
        OneUiDialog.choices(
            activity = this,
            title = "Arredondamento",
            message = "Ajusta cartões e controles retangulares sem deformar os botões circulares de captura.",
            choices = values.map { OneUiDialog.Choice(AppearanceStore.cornerLabel(it)) },
            selectedIndex = values.indexOf(AppearanceStore.corners(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setCorners(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceBorders() {
        val values = AppearanceStore.borderValues
        OneUiDialog.choices(
            activity = this,
            title = "Contornos",
            message = "Controla a força das bordas de cartões e botões compatíveis com o tema.",
            choices = values.map { OneUiDialog.Choice(AppearanceStore.borderLabel(it)) },
            selectedIndex = values.indexOf(AppearanceStore.borders(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setBorders(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseAppearanceDensity() {
        val values = AppearanceStore.densityValues
        OneUiDialog.choices(
            activity = this,
            title = "Densidade dos controles",
            message = "Muda a altura e o espaçamento dos controles na tela de ajustes sem reduzir a área segura de toque.",
            choices = listOf(
                OneUiDialog.Choice("Compacta", "Mostra mais opções na tela com espaçamento reduzido."),
                OneUiDialog.Choice("Confortável", "Equilíbrio entre quantidade de opções e facilidade de toque."),
                OneUiDialog.Choice("Espaçosa", "Controles maiores e mais separados para evitar toques errados.")
            ),
            selectedIndex = values.indexOf(AppearanceStore.density(this)),
            confirmLabel = "Aplicar"
        ) { index ->
            saveCurrent()
            AppearanceStore.setDensity(this, values[index])
            applyAppearanceChange()
        }
    }

    private fun chooseVisualIdentity() {
        val values = VisualIdentityStore.modeValues
        OneUiDialog.choices(
            activity = this,
            title = "Identidade discreta",
            message = "Escolha o símbolo genérico usado nos widgets e nas notificações de atividades em andamento.",
            choices = values.map { value -> OneUiDialog.Choice(VisualIdentityStore.modeLabel(value)) },
            selectedIndex = values.indexOf(VisualIdentityStore.mode(this))
        ) { index ->
            VisualIdentityStore.setMode(this, values[index])
            WidgetRenderer.updateAll(this)
            WidgetPreviewPublisher.publishIfNeeded(this, force = true)
            rebuildSettingsForm()
        }
    }

    private fun editVisualIdentityLabel() {
        val current = VisualIdentityStore.customLabel(this)
        val values = (listOf("", "Arquivos", "Documentos", "Notas", "Galeria", "Utilitário") + current.takeIf { it.isNotBlank() }.orEmpty())
            .distinct()
        OneUiDialog.choices(
            activity = this,
            title = "Nome discreto",
            message = "Escolha um rótulo pronto. Não há campo de texto nem teclado nesta configuração.",
            choices = values.map { value -> OneUiDialog.Choice(value.ifBlank { "Usar nome do perfil" }) },
            selectedIndex = values.indexOf(current).coerceAtLeast(0)
        ) { index ->
            VisualIdentityStore.setCustomLabel(this, values[index])
            WidgetRenderer.updateAll(this)
            WidgetPreviewPublisher.publishIfNeeded(this, force = true)
            rebuildSettingsForm()
        }
    }

    private fun applyAppearanceChange() {
        WidgetRenderer.updateAll(this)
        WidgetPreviewPublisher.publishIfNeeded(this, force = true)
        rebuildSettingsForm()
        AppearanceRuntime.apply(this)
    }

    private fun chooseBrowserDownloadDestination() {
        val values = PrivateBrowserStore.destinationValues
        val current = PrivateBrowserStore.downloadDestination(this)
        OneUiDialog.choices(
            activity = this,
            title = "Destino padrão dos downloads",
            message = "No navegador, cada download ainda permite escolher outro destino na hora.",
            choices = values.map { OneUiDialog.Choice(PrivateBrowserStore.destinationLabel(it), PrivateBrowserStore.destinationSubtitle(it)) },
            selectedIndex = values.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setDownloadDestination(this, values[index])
            Toast.makeText(this, "Downloads: ${PrivateBrowserStore.destinationLabel(values[index])}", Toast.LENGTH_SHORT).show()
            rebuildSettingsForm()
        }
    }

    private fun chooseBrowserMediaQuality() {
        val current = PrivateBrowserStore.mediaQuality(this)
        OneUiDialog.choices(
            activity = this,
            title = "Qualidade de mídia",
            message = "Preferência para sites que oferecem versões de mídia. Links diretos baixam o arquivo original.",
            choices = PrivateBrowserStore.qualityValues.map { OneUiDialog.Choice(PrivateBrowserStore.qualityLabel(it)) },
            selectedIndex = PrivateBrowserStore.qualityValues.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setMediaQuality(this, PrivateBrowserStore.qualityValues[index])
            chooseBrowserMediaResolution()
        }
    }

    private fun chooseBrowserMediaResolution() {
        val current = PrivateBrowserStore.mediaResolution(this)
        OneUiDialog.choices(
            activity = this,
            title = "Resolução preferida",
            message = "Original preserva a qualidade máxima do arquivo recebido. Outras opções só se aplicam quando o site expõe versões separadas.",
            choices = PrivateBrowserStore.resolutionValues.map { OneUiDialog.Choice(PrivateBrowserStore.resolutionLabel(it)) },
            selectedIndex = PrivateBrowserStore.resolutionValues.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setMediaResolution(this, PrivateBrowserStore.resolutionValues[index])
            Toast.makeText(this, "Mídia: ${PrivateBrowserStore.mediaPreferenceSummary(this)}", Toast.LENGTH_SHORT).show()
            rebuildSettingsForm()
        }
    }

    private fun toggleBrowserCookies() {
        val enabled = !PrivateBrowserStore.thirdPartyCookiesEnabled(this)
        PrivateBrowserStore.setThirdPartyCookiesEnabled(this, enabled)
        Toast.makeText(this, if (enabled) "Cookies completos ligados" else "Cookies completos desligados", Toast.LENGTH_SHORT).show()
        rebuildSettingsForm()
    }

    private fun showAdvancedDownloaderSettings() {
        val profile = PrivateBrowserStore.performanceProfile(this)
        OneUiDialog.choices(
            activity = this,
            title = "Downloader avançado",
            message = "Escolha o que deseja configurar. Todas as opções abaixo alteram o downloader de verdade; recursos incompatíveis não são apresentados durante uma operação.",
            choices = listOf(
                OneUiDialog.Choice("Desempenho: ${PrivateBrowserStore.performanceLabel(profile)}", "Rápido prioriza conexões paralelas; Compatível usa o caminho mais simples."),
                OneUiDialog.Choice(if (PrivateBrowserStore.wifiOnly(this)) "Somente Wi‑Fi: ligado" else "Somente Wi‑Fi: desligado", "Bloqueia novos downloads em rede móvel quando ativado."),
                OneUiDialog.Choice(if (PrivateBrowserStore.aria2Enabled(this)) "Downloads paralelos: ligados" else "Downloads paralelos: desligados", "Usa aria2 quando o perfil e o formato suportam; em Compatível ele é ignorado."),
                OneUiDialog.Choice(if (PrivateBrowserStore.embedMetadata(this)) "Metadados: preservar" else "Metadados: mínimos", "Inclui título, capa e metadados quando a ferramenta e o site fornecerem esses dados."),
                OneUiDialog.Choice(if (PrivateBrowserStore.verifyFreeSpace(this)) "Espaço livre: verificar" else "Espaço livre: não verificar", "Evita começar uma operação que provavelmente não cabe no armazenamento.")
            )
        ) { index ->
            when (index) {
                0 -> chooseDownloaderPerformance()
                1 -> {
                    PrivateBrowserStore.setWifiOnly(this, !PrivateBrowserStore.wifiOnly(this))
                    rebuildSettingsForm()
                }
                2 -> {
                    PrivateBrowserStore.setAria2Enabled(this, !PrivateBrowserStore.aria2Enabled(this))
                    rebuildSettingsForm()
                }
                3 -> {
                    PrivateBrowserStore.setEmbedMetadata(this, !PrivateBrowserStore.embedMetadata(this))
                    rebuildSettingsForm()
                }
                4 -> {
                    PrivateBrowserStore.setVerifyFreeSpace(this, !PrivateBrowserStore.verifyFreeSpace(this))
                    rebuildSettingsForm()
                }
            }
        }
    }

    private fun chooseDownloaderPerformance() {
        val values = PrivateBrowserStore.performanceProfiles
        val current = PrivateBrowserStore.performanceProfile(this)
        OneUiDialog.choices(
            activity = this,
            title = "Perfil do downloader",
            message = "O perfil muda tentativas, timeouts e paralelismo. Ele não reduz a qualidade escolhida.",
            choices = listOf(
                OneUiDialog.Choice("Rápido", "Mais paralelismo quando disponível; indicado para Wi‑Fi estável."),
                OneUiDialog.Choice("Equilibrado", "Boa velocidade com tolerância a servidores instáveis."),
                OneUiDialog.Choice("Compatível", "Menos otimizações e caminho mais simples para sites problemáticos.")
            ),
            selectedIndex = values.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setPerformanceProfile(this, values[index])
            rebuildSettingsForm()
        }
    }

    private fun rebuildSettingsForm() {
        if (building) return
        val scrollY = if (::settingsScroll.isInitialized) settingsScroll.scrollY else 0
        saveCurrent()
        buildForm(CaptureSettings.snapshot(this))
        restoreScrollPosition(scrollY)
    }

    private fun buildFormPreservingScroll(snapshot: CaptureSettings.Snapshot) {
        val scrollY = if (::settingsScroll.isInitialized) settingsScroll.scrollY else 0
        buildForm(snapshot)
        restoreScrollPosition(scrollY)
    }

    private fun restoreScrollPosition(scrollY: Int) {
        if (!::settingsScroll.isInitialized) return
        settingsScroll.post {
            val maxScroll = (settingsScroll.getChildAt(0)?.height ?: 0) - settingsScroll.height
            settingsScroll.scrollTo(0, scrollY.coerceIn(0, maxScroll.coerceAtLeast(0)))
        }
    }


    private fun confirmCleanCacheAndDirtyData() {
        if (CaptureStateStore.isBusy(this)) {
            OneUiDialog.message(
                activity = this,
                title = "Gravação em andamento",
                message = "Finalize a gravação antes de limpar os arquivos temporários. Assim o vídeo que está sendo criado permanece protegido.",
                positiveLabel = "OK"
            )
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Limpar caches de mídia?",
            message = "Isso libera miniaturas da memória e do armazenamento, detalhes já preparados, arquivos temporários e registros órfãos. Nenhuma foto ou vídeo dos cofres e da lixeira será apagado.",
            positiveLabel = "Limpar",
            destructive = false
        ) { cleanCacheAndDirtyData() }
    }

    private fun cleanCacheAndDirtyData() {
        val progress = OneUiDialog.progress(
            activity = this,
            title = "Limpando cache",
            message = "Removendo miniaturas, detalhes e temporários sem apagar mídias…",
            cancelable = false
        )
        cacheExecutor.execute {
            val result = runCatching {
                val report = VaultCleanupRepository.cleanCacheAndDirtyData(this)
                val importRecords = VaultBulkImportRunner.cleanupDirtyState(this)
                report to importRecords
            }
            runOnUiThread {
                progress.dismiss()
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (report, importRecords) ->
                    Haptics.success(this)
                    refreshCacheUsage()
                    OneUiDialog.message(
                        activity = this,
                        title = "Limpeza concluída",
                        message = report.summary() + "\nRegistros de importação órfãos: $importRecords\n\nAs miniaturas e informações necessárias serão recriadas conforme você usar os cofres.",
                        positiveLabel = "OK"
                    )
                }.onFailure { error ->
                    Haptics.error(this)
                    OneUiDialog.message(
                        activity = this,
                        title = "Falha na limpeza",
                        message = error.message ?: "Não foi possível concluir a limpeza.",
                        positiveLabel = "OK"
                    )
                }
            }
        }
    }

    private fun refreshCacheUsage() {
        if (!::cacheUsageText.isInitialized || cacheExecutor.isShutdown) return
        val target = cacheUsageText
        val generation = ++cacheUsageRefreshGeneration
        target.text = "USO DOS CACHES\nCalculando miniaturas, detalhes e temporários…"
        val queued = runCatching {
            cacheExecutor.execute {
                val result = runCatching { VaultCleanupRepository.cacheSnapshot(this) }
                runOnUiThread {
                    if (isDestroyed || generation != cacheUsageRefreshGeneration || cacheUsageText !== target) return@runOnUiThread
                    target.text = result.fold(
                        onSuccess = { it.summary() },
                        onFailure = { "USO DOS CACHES\nNão foi possível calcular agora. Toque em Atualizar para tentar novamente." }
                    )
                }
            }
        }.isSuccess
        if (!queued && cacheUsageText === target) {
            target.text = "USO DOS CACHES\nNão foi possível calcular agora."
        }
    }

    private fun scanCapabilities(force: Boolean = false) {
        if (capabilityScanInProgress) return
        if (!force) {
            CaptureCapabilityMatrix.cached(this)?.let { cached ->
                capabilityMatrix = cached
                capabilityScanCompleted = true
                autoSaveStatus.text = "✓ Usando capacidades salvas deste aparelho"
                refreshCompatibilityOptions()
                refreshHardwareFeatureOptions()
                refreshDependentControls()
                return
            }
        }
        if (CaptureStateStore.isBusy(this)) {
            autoSaveStatus.text = if (capabilityMatrix != null) {
                "✓ Usando capacidades salvas; nova análise adiada até a gravação terminar"
            } else {
                "Gravação em prioridade; capacidades serão analisadas ao reabrir os ajustes"
            }
            refreshCompatibilityOptions()
            refreshHardwareFeatureOptions()
            return
        }
        capabilityScanInProgress = true
        if (capabilityMatrix == null) capabilityScanCompleted = false
        autoSaveStatus.text = "Analisando câmera, lentes físicas, FPS, HDR, estabilização e encoder…"
        refreshCompatibilityOptions()
        refreshHardwareFeatureOptions()
        executor.execute {
            val result = runCatching {
                CaptureCapabilityMatrix.scan(this, force = force || capabilityMatrix == null)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                capabilityScanInProgress = false
                capabilityScanCompleted = true
                result.onSuccess { matrix ->
                    capabilityMatrix = matrix
                    val selected = selectedCapabilityMatrix()
                    if (selected != null && selected.modes.isNotEmpty()) CaptureModeCatalog.remember(this, selected)
                    autoSaveStatus.text = "✓ Interface configurada para a câmera e os encoders deste aparelho"
                }.onFailure {
                    autoSaveStatus.text = "Metadados inconclusivos; opções incertas serão validadas ao abrir a sessão"
                }
                // Uma nova leitura de capacidades nunca muda silenciosamente o perfil
                // escolhido. Opções incompatíveis ficam indisponíveis e a gravação explica
                // o motivo, mas o valor salvo só muda por ação explícita do usuário.
                refreshCompatibilityOptions()
                refreshHardwareFeatureOptions()
                refreshDependentControls()
            }
        }
    }

    private fun reanalyzeHardware() {
        if (CaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Finalize a gravação antes de reanalisar o hardware.", Toast.LENGTH_LONG).show()
            return
        }
        CaptureCapabilityMatrix.invalidate(this)
        capabilityMatrix = null
        capabilityScanCompleted = false
        scanCapabilities(force = true)
    }

    private fun bindAutoSaveListeners() {
        listOf(
            hdr, thermal, audioAgc, audioNoise, audioLowCut, vibration, secureScreen,
            lockWhiteBalance, intelligentPlayback, dropLateFrames, prebuffer4k60, autoRecoverStalls,
            openVideosExternally, openPhotosExternally
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, _ ->
                if (building) return@setOnCheckedChangeListener
                if (control === hdr) enforceHdrCompatibility()
                if (control === hdr) refreshHardwareFeatureOptions()
                refreshDependentControls()
                scheduleSave()
            }
        }
    }

    private fun onSpinnerChanged(spinner: Spinner) {
        if (building) return
        when (spinner) {
            autoLockTimeout -> VaultSecuritySettings.setTimeoutMs(this, selected(spinner).toLongOrNull() ?: VaultSecuritySettings.TIMEOUT_IMMEDIATE)
            trashRetention -> VaultTrashRepository.setRetentionDays(this, selected(spinner).toIntOrNull() ?: VaultTrashRepository.RETENTION_30_DAYS)
            mediaDetailsLoadingMode -> VaultMediaCacheSettings.setDetailsLoadingMode(this, selected(spinner))
            fps -> {
                val targetFps = selected(fps).toIntOrNull() ?: editingFps
                if (targetFps != editingFps && CameraProfileStore.activeMode(this) == CameraProfileStore.FunctionMode.VIDEO) {
                    switchVideoFpsProfile(targetFps)
                    return
                }
                resolutionSelections[editingFps] = selected(resolution)
                refreshCompatibilityOptions()
            }
            resolution -> {
                resolutionSelections[editingFps] = selected(resolution)
                refreshCompatibilityOptions()
            }
            codec -> enforceHdrCompatibility()
        }
        if (
            spinner === fps || spinner === resolution || spinner === codec
        ) {
            refreshHardwareFeatureOptions()
        }
        refreshDependentControls()
        scheduleSave()
    }

    private fun refreshCompatibilityOptions() {
        if (!::resolution.isInitialized || !::fps.isInitialized) return
        val catalog = modeCatalog()
        val beforeFps = editingFps
        val beforeResolution = selected(resolution)
        resolutionSelections[beforeFps] = beforeResolution
        val requestedFps = selected(fps).toIntOrNull() ?: CaptureSettings.snapshot(this).fps

        updateOptions(fps, fpsOptions(catalog), requestedFps.toString())
        var selectedFps = selected(fps).toInt()
        var capability = catalog.profile(selectedFps)

        editingFps = selectedFps
        val requestedResolution = resolutionSelections[selectedFps]
            ?: CaptureSettings.resolutionForFps(this, selectedFps)
        updateOptions(
            resolution,
            resolutionOptions(selectedFps),
            requestedResolution
        )
        val selectedResolution = selected(resolution)
        resolutionSelections[selectedFps] = selectedResolution
        resolution.isEnabled = capability.selectable
        resolution.alpha = if (resolution.isEnabled) 1f else 0.45f
        updateCapabilitiesCard(catalog)

    }

    private fun modeCatalog(): CaptureModeCatalog.Catalog = CaptureModeCatalog.resolve(
        context = this,
        matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
        scanInProgress = capabilityMatrix == null && capabilityScanInProgress
    )

    private fun enforceHdrCompatibility() {
        if (!hdr.isChecked) return
        val incompatible = selected(codec) == CaptureSettings.CODEC_AVC ||
                selected(fps).toInt() >= 120 ||
                selectedCameraFeatures()?.hdrHlg10 == Support.UNSUPPORTED
        if (incompatible) {
            hdr.isChecked = false
            Toast.makeText(this, "HLG10 foi desativado porque esta combinação não é compatível", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDependentControls() {
        if (!::hdr.isInitialized) return
        val highFrameRate = selected(fps).toInt() >= 60
        val highSpeed = selected(fps).toInt() >= 120
        val hdrSupport = selectedCameraFeatures()?.hdrHlg10 ?: Support.UNVERIFIED
        val hdrHardwareSelectable = HardwareSupportPolicy.isSelectable(hdrSupport)
        hdr.isEnabled = !highSpeed && selected(codec) != CaptureSettings.CODEC_AVC && hdrHardwareSelectable
        hdr.alpha = if (hdr.isEnabled) 1f else 0.45f
        colorProfile.isEnabled = !hdr.isChecked && !highFrameRate
        colorProfile.alpha = if (colorProfile.isEnabled) 1f else 0.45f
    }

    private fun scheduleSave(immediate: Boolean = false) {
        if (building) return
        val generation = ++saveGeneration
        val action = Runnable {
            if (generation != saveGeneration || isDestroyed) return@Runnable
            saveCurrent()
        }
        if (immediate) action.run() else mainHandler.postDelayed(action, AUTO_SAVE_DELAY_MS)
    }

    private fun snapshotFromForm(
        base: CaptureSettings.Snapshot,
        fpsValue: Int = selected(fps).toInt(),
        resolutionValue: String = selected(resolution)
    ): CaptureSettings.Snapshot = base.copy(
        resolution = resolutionValue,
        fps = fpsValue,
        codec = selected(codec),
        bitrateMbps = selected(bitrate).toIntOrNull()?.coerceIn(4, 240) ?: base.bitrateMbps,
        iFrameIntervalSeconds = selected(iframe).toInt(),
        hdrHlg10 = hdr.isChecked,
        colorProfile = selected(colorProfile),
        stabilization = selected(stabilization),
        focusMode = selected(focus),
        noiseReduction = selected(noiseReduction),
        edgeMode = selected(edge),
        antibanding = selected(antibanding),
        whiteBalanceMode = selected(whiteBalance),
        yellowReduction = selected(yellowReduction),
        lockWhiteBalance = lockWhiteBalance.isChecked,
        previewMode = CaptureSettings.PREVIEW_OFF,
        exposureCompensation = selected(exposure).toIntOrNull()?.coerceIn(-12, 12) ?: 0,
        thermalProtection = thermal.isChecked,
        audioSampleRate = selected(audioSampleRate).toInt(),
        audioBitrateKbps = selected(audioBitrate).toInt(),
        audioChannels = selected(audioChannels),
        audioGainDb = selected(audioGain).toIntOrNull()?.coerceIn(0, 30) ?: 0,
        audioAgc = audioAgc.isChecked,
        audioNoiseSuppressor = audioNoise.isChecked,
        audioLowCut = audioLowCut.isChecked,
        vibrateStartStop = vibration.isChecked,
        secureScreen = secureScreen.isChecked
    )

    private fun switchVideoFpsProfile(targetFps: Int) {
        if (building || targetFps == editingFps) return
        saveGeneration++
        val oldGlobal = CaptureSettings.snapshot(this)
        val previousFps = editingFps
        val previousResolution = resolutionSelections[previousFps] ?: selected(resolution)
        val previousForm = snapshotFromForm(oldGlobal, previousFps, previousResolution)
        CaptureSettings.saveResolutionForFps(this, previousFps, previousResolution)

        val cameraId = previousForm.selectedCameraId?.takeIf { it.isNotBlank() }
        if (cameraId != null) {
            CameraProfileStore.saveProfile(this, cameraId, CameraProfileStore.FunctionMode.VIDEO, previousForm)
        } else {
            CaptureSettings.save(this, previousForm)
        }

        val targetResolution = resolutionSelections[targetFps] ?: CaptureSettings.resolutionForFps(this, targetFps)
        val fallback = previousForm.copy(fps = targetFps, resolution = targetResolution)
        val activated = if (cameraId != null) {
            CameraProfileStore.ensureProfiles(this, cameraId, fallback)
            CameraProfileStore.activate(this, cameraId, CameraProfileStore.FunctionMode.VIDEO, fallback)
        } else {
            CaptureSettings.save(this, fallback)
            fallback
        }
        editingFps = targetFps
        CaptureStateStore.clearEffectiveMode(this)
        buildFormPreservingScroll(activated)
    }

    private fun saveCurrent() {
        if (building) return
        resolutionSelections[editingFps] = selected(resolution)
        val old = CaptureSettings.snapshot(this)
        val value = snapshotFromForm(old)
        if (old.resolution != value.resolution || old.fps != value.fps) {
            CaptureStateStore.clearEffectiveMode(this)
        }
        resolutionSelections.forEach { (fpsValue, resolutionValue) ->
            CaptureSettings.saveResolutionForFps(this, fpsValue, resolutionValue)
        }
        if (old != value) CaptureSettings.save(this, value)
        val oldPlayback = PlaybackSettings.snapshot(this)
        val newPlayback = oldPlayback.copy(
            intelligentPlayback = intelligentPlayback.isChecked,
            dropLateFrames = dropLateFrames.isChecked,
            prebuffer4k60 = prebuffer4k60.isChecked,
            autoRecoverStalls = autoRecoverStalls.isChecked,
            openVideosExternally = openVideosExternally.isChecked,
            openPhotosExternally = openPhotosExternally.isChecked,
            fileCacheMs = selected(playbackCache).toIntOrNull()?.coerceIn(250, 5_000) ?: oldPlayback.fileCacheMs
        )
        if (oldPlayback != newPlayback) PlaybackSettings.save(this, newPlayback)
    }

    private fun confirmRestore() {
        OneUiDialog.confirm(
            activity = this,
            title = "Restaurar padrões estáveis?",
            message = "Volta para 4K, 60 FPS, SDR BT.709 natural, HEVC e estabilização desativada.",
            positiveLabel = "Restaurar",
            destructive = true
        ) {
            CaptureSettings.restoreDefaults(this)
            PlaybackSettings.restoreDefaults(this)
            VaultMediaCacheSettings.restoreDefaults(this)
            CaptureStateStore.clearEffectiveMode(this)
            buildFormPreservingScroll(CaptureSettings.snapshot(this))
            Haptics.success(this)
        }
    }

    private fun showBiometricTargetSettings() {
        val targets = buildList {
            add(VaultSecuritySettings.BIOMETRIC_TARGET_ASK to "Perguntar sempre")
            add(VaultSecuritySettings.BIOMETRIC_TARGET_PRIMARY to "Cofre principal")
            if (SecondaryVaultLock.isEnabled(this@SettingsActivity)) {
                add(VaultSecuritySettings.BIOMETRIC_TARGET_SECONDARY to "Cofre secundário")
            }
            if (TertiaryVaultLock.isEnabled(this@SettingsActivity)) {
                add(VaultSecuritySettings.BIOMETRIC_TARGET_TERTIARY to "Cofre terciário")
            }
        }
        val current = VaultSecuritySettings.biometricVaultTarget(this)
        OneUiDialog.choices(
            activity = this,
            title = "Destino da biometria",
            message = "Escolha se a biometria deve perguntar sempre ou abrir diretamente um cofre.",
            choices = targets.map { (value, label) ->
                OneUiDialog.Choice(
                    label,
                    if (value == VaultSecuritySettings.BIOMETRIC_TARGET_ASK) {
                        "Mostra a escolha somente depois que a biometria for confirmada."
                    } else {
                        "Abre automaticamente após a autenticação."
                    }
                )
            },
            selectedIndex = targets.indexOfFirst { it.first == current }.coerceAtLeast(0)
        ) { position ->
            VaultSecuritySettings.setBiometricVaultTarget(this, targets[position].first)
            Toast.makeText(this, "Destino biométrico atualizado.", Toast.LENGTH_SHORT).show()
            buildFormPreservingScroll(CaptureSettings.snapshot(this))
        }
    }

    private fun biometricTargetLabel(): String = when (VaultSecuritySettings.biometricVaultTarget(this)) {
        VaultSecuritySettings.BIOMETRIC_TARGET_PRIMARY -> "cofre principal"
        VaultSecuritySettings.BIOMETRIC_TARGET_SECONDARY -> "cofre secundário"
        VaultSecuritySettings.BIOMETRIC_TARGET_TERTIARY -> "cofre terciário"
        else -> "perguntar sempre"
    }

    private fun managePin() {
        if (!PrimaryVaultLock.isEnabled(this)) {
            createPin()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Segurança do cofre principal",
            choices = listOf(
                OneUiDialog.Choice("Alterar PIN", "Confirme o PIN atual e escolha um novo."),
                OneUiDialog.Choice("Remover PIN", "O cofre continuará privado pelo armazenamento do Android.", destructive = true)
            )
        ) { which ->
            when (which) {
                0 -> verifyPin { createPin() }
                1 -> verifyPin {
                    PrimaryVaultLock.clear(this)
                    VaultSecuritySettings.setBiometricEnabled(this, false)
                    SecondaryVaultLock.clear(this)
                    TertiaryVaultLock.clear(this)
                    Toast.makeText(this, "PIN do cofre principal removido; cofres secundário e terciário desativados", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        }
    }

    private fun manageAppsPin() {
        if (!AppVaultLock.isEnabled(this)) {
            createAppsPin()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Segurança dos apps",
            message = "Este PIN protege somente a lista de atalhos da aba Apps.",
            choices = listOf(
                OneUiDialog.Choice("Alterar PIN dos apps", "Confirme o PIN atual e escolha um novo."),
                OneUiDialog.Choice("Remover PIN dos apps", "A lista permanece salva, mas fica indisponível até criar outro PIN.", destructive = true)
            )
        ) { choice ->
            when (choice) {
                0 -> verifyAppsPin { createAppsPin() }
                1 -> verifyAppsPin {
                    AppVaultLock.clear(this)
                    ProtectedAppsStore.setBiometricEnabled(this, false)
                    Toast.makeText(this, "Proteção dos apps removida", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        }
    }

    private fun verifyAppsPin(onVerified: () -> Unit) {
        PinPadDialog.showVerify(
            activity = this,
            title = "Confirmar PIN dos apps",
            subtitle = "Digite o PIN usado para abrir a aba Apps.",
            verify = { AppVaultLock.verify(this, it) },
            onVerified = onVerified
        )
    }

    private fun createAppsPin() {
        PinPadDialog.showCreate(
            activity = this,
            title = "Criar PIN dos apps",
            onCreated = { pin ->
                AppVaultLock.setPin(this, pin)
                Toast.makeText(this, "PIN dos apps salvo", Toast.LENGTH_SHORT).show()
                buildFormPreservingScroll(CaptureSettings.snapshot(this))
            }
        )
    }


    private fun chooseProtectedCaptureDestination() {
        val secondaryEnabled = SecondaryVaultLock.isEnabled(this)
        val tertiaryEnabled = TertiaryVaultLock.isEnabled(this)
        OneUiDialog.choices(
            activity = this,
            title = "Salvar capturas em",
            message = "Prints e vídeos ficarão somente no cofre escolhido.",
            choices = listOf(
                OneUiDialog.Choice("Cofre principal", "Protegido pelo PIN do cofre principal."),
                OneUiDialog.Choice(
                    "Cofre secundário",
                    if (secondaryEnabled) {
                        "Protegido pelo PIN do cofre secundário."
                    } else {
                        "Configure o cofre secundário primeiro."
                    },
                    enabled = secondaryEnabled
                ),
                OneUiDialog.Choice(
                    "Cofre terciário",
                    if (tertiaryEnabled) {
                        "Protegido pelo PIN do cofre terciário."
                    } else {
                        "Configure o cofre terciário primeiro."
                    },
                    enabled = tertiaryEnabled
                )
            )
        ) { index ->
            pendingProtectedCaptureDestination = when (index) {
                1 -> VaultScreenCaptureService.DESTINATION_SECONDARY
                2 -> VaultScreenCaptureService.DESTINATION_TERTIARY
                else -> VaultScreenCaptureService.DESTINATION_PRIMARY
            }
            protectedCaptureFlowPending = true
            authorizeProtectedCaptureDestination()
        }
    }

    private fun authorizeProtectedCaptureDestination() {
        if (isProtectedCaptureDestinationUnlocked()) {
            requestProtectedOverlayThenProjection()
            return
        }
        PinPadDialog.showVerify(
            activity = this,
            title = "Autorizar ${protectedCaptureDestinationLabel()}",
            subtitle = "Digite o PIN desse cofre. Nenhuma cópia será criada na galeria do aparelho.",
            verify = { pin -> verifyProtectedCaptureDestination(pin) },
            onVerified = { requestProtectedOverlayThenProjection() },
            onCancel = { cancelProtectedCaptureFlow() }
        )
    }

    private fun requestProtectedOverlayThenProjection() {
        if (Settings.canDrawOverlays(this)) {
            requestProtectedNotificationThenProjection()
            return
        }
        runCatching {
            protectedOverlayPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            cancelProtectedCaptureFlow()
            Toast.makeText(
                this,
                it.message ?: "Não foi possível pedir a permissão do botão flutuante.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestProtectedNotificationThenProjection() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                protectedNotificationPermission.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }.onFailure {
                cancelProtectedCaptureFlow()
                Toast.makeText(
                    this,
                    it.message ?: "Não foi possível pedir a permissão de notificação.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            requestProtectedProjectionPermission()
        }
    }

    private fun requestProtectedProjectionPermission() {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            protectedProjectionPermission.launch(manager.createScreenCaptureIntent())
        }.onFailure {
            cancelProtectedCaptureFlow()
            Haptics.error(this)
            Toast.makeText(
                this,
                it.message ?: "Não foi possível pedir a autorização de captura.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isProtectedCaptureDestinationUnlocked(): Boolean =
        when (pendingProtectedCaptureDestination) {
            VaultScreenCaptureService.DESTINATION_SECONDARY ->
                SecondaryVaultLock.isUnlocked(this)
            VaultScreenCaptureService.DESTINATION_TERTIARY ->
                TertiaryVaultLock.isUnlocked(this)
            else ->
                PrimaryVaultLock.isUnlocked(this)
        }

    private fun verifyProtectedCaptureDestination(pin: String): Boolean =
        when (pendingProtectedCaptureDestination) {
            VaultScreenCaptureService.DESTINATION_SECONDARY ->
                SecondaryVaultLock.verify(this, pin)
            VaultScreenCaptureService.DESTINATION_TERTIARY ->
                TertiaryVaultLock.verify(this, pin)
            else ->
                PrimaryVaultLock.verify(this, pin)
        }

    private fun protectedCaptureDestinationLabel(): String =
        when (pendingProtectedCaptureDestination) {
            VaultScreenCaptureService.DESTINATION_SECONDARY -> "cofre secundário"
            VaultScreenCaptureService.DESTINATION_TERTIARY -> "cofre terciário"
            else -> "cofre principal"
        }

    private fun cancelProtectedCaptureFlow() {
        when (pendingProtectedCaptureDestination) {
            VaultScreenCaptureService.DESTINATION_SECONDARY ->
                SecondaryVaultLock.lock()
            VaultScreenCaptureService.DESTINATION_TERTIARY ->
                TertiaryVaultLock.lock()
            else ->
                PrimaryVaultLock.lock()
        }
        protectedCaptureFlowPending = false
    }

    private fun verifyPin(onVerified: () -> Unit) {
        PinPadDialog.showVerify(
            activity = this,
            title = "Confirmar PIN",
            subtitle = "Digite o PIN atual para continuar.",
            verify = { PrimaryVaultLock.verify(this, it) },
            onVerified = onVerified
        )
    }

    private fun createPin() {
        PinPadDialog.showCreate(
            activity = this,
            onCreated = { pin ->
                if ((SecondaryVaultLock.isEnabled(this) && SecondaryVaultLock.matches(this, pin)) ||
                    (TertiaryVaultLock.isEnabled(this) && TertiaryVaultLock.matches(this, pin))) {
                    Haptics.error(this)
                    Toast.makeText(this, "Use um PIN diferente dos cofres secundário e terciário", Toast.LENGTH_LONG).show()
                } else {
                    PrimaryVaultLock.setPin(this, pin)
                    Toast.makeText(this, "PIN do cofre principal salvo", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        )
    }

    private fun manageSecondaryVault() {
        if (!PrimaryVaultLock.isEnabled(this)) {
            OneUiDialog.message(
                activity = this,
                title = "Crie o PIN do cofre principal primeiro",
                message = "O cofre secundário precisa de um PIN do cofre principal ativo para funcionar com segurança.",
                positiveLabel = "Entendi"
            )
            return
        }
        if (!SecondaryVaultLock.isEnabled(this)) {
            createSecondaryPin()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Cofre secundário",
            message = "O PIN do cofre secundário abre um cofre separado e não desbloqueia o cofre principal.",
            choices = listOf(
                OneUiDialog.Choice("Alterar PIN do cofre secundário", "Confirme o PIN atual e escolha outro."),
                OneUiDialog.Choice("Remover PIN do cofre secundário", "Os arquivos separados permanecem no aparelho.", destructive = true)
            )
        ) { which ->
            when (which) {
                0 -> verifySecondaryPin { createSecondaryPin() }
                1 -> verifySecondaryPin {
                    SecondaryVaultLock.clear(this)
                    Toast.makeText(this, "Cofre secundário desativado", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        }
    }

    private fun verifySecondaryPin(onVerified: () -> Unit) {
        PinPadDialog.showVerify(
            activity = this,
            title = "Confirmar PIN do cofre secundário",
            subtitle = "Digite o PIN usado para abrir o cofre secundário.",
            verify = { SecondaryVaultLock.verify(this, it) },
            onVerified = onVerified
        )
    }

    private fun createSecondaryPin() {
        PinPadDialog.showCreate(
            activity = this,
            title = "Criar PIN do cofre secundário",
            onCreated = { pin ->
                if (PrimaryVaultLock.matches(this, pin) || (TertiaryVaultLock.isEnabled(this) && TertiaryVaultLock.matches(this, pin))) {
                    Haptics.error(this)
                    Toast.makeText(this, "Use um PIN diferente dos outros cofres", Toast.LENGTH_LONG).show()
                } else {
                    SecondaryVaultLock.setPin(this, pin)
                    SecondaryVaultLock.lock()
                    Toast.makeText(this, "Cofre secundário configurado", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        )
    }

    private fun manageTertiaryVault() {
        if (!PrimaryVaultLock.isEnabled(this)) {
            OneUiDialog.message(
                activity = this,
                title = "Crie o PIN do cofre principal primeiro",
                message = "O cofre terciário precisa de um PIN do cofre principal ativo para funcionar com segurança.",
                positiveLabel = "Entendi"
            )
            return
        }
        if (!TertiaryVaultLock.isEnabled(this)) {
            createTertiaryPin()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Cofre terciário",
            message = "Este PIN abre um terceiro armazenamento privado separado do cofre principal e do cofre secundário.",
            choices = listOf(
                OneUiDialog.Choice("Alterar PIN do cofre terciário", "Confirme o PIN atual e escolha outro."),
                OneUiDialog.Choice("Remover PIN do cofre terciário", "Os arquivos separados permanecem no aparelho.", destructive = true)
            )
        ) { which ->
            when (which) {
                0 -> verifyTertiaryPin { createTertiaryPin() }
                1 -> verifyTertiaryPin {
                    TertiaryVaultLock.clear(this)
                    Toast.makeText(this, "Cofre terciário desativado", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        }
    }

    private fun verifyTertiaryPin(onVerified: () -> Unit) {
        PinPadDialog.showVerify(
            activity = this,
            title = "Confirmar PIN do cofre terciário",
            subtitle = "Digite o PIN usado para abrir o cofre terciário.",
            verify = { TertiaryVaultLock.verify(this, it) },
            onVerified = onVerified
        )
    }

    private fun createTertiaryPin() {
        PinPadDialog.showCreate(
            activity = this,
            title = "Criar PIN do cofre terciário",
            onCreated = { pin ->
                if (PrimaryVaultLock.matches(this, pin) || (SecondaryVaultLock.isEnabled(this) && SecondaryVaultLock.matches(this, pin))) {
                    Haptics.error(this)
                    Toast.makeText(this, "Use um PIN diferente dos outros cofres", Toast.LENGTH_LONG).show()
                } else {
                    TertiaryVaultLock.setPin(this, pin)
                    TertiaryVaultLock.lock()
                    Toast.makeText(this, "Cofre terciário configurado", Toast.LENGTH_SHORT).show()
                    buildFormPreservingScroll(CaptureSettings.snapshot(this))
                }
            }
        )
    }

    private fun showCapabilities() {
        val progress = OneUiDialog.progress(
            activity = this,
            title = "Analisando o aparelho…",
            message = "Consultando Camera2 e MediaCodec.",
            cancelable = false
        )
        executor.execute {
            val report = runCatching { CapabilityReport.build(this) }
                .getOrElse { "Falha ao consultar capacidades: ${it.message}" }
            runOnUiThread {
                progress.dismiss()
                OneUiDialog.message(
                    activity = this,
                    title = "Capacidades detectadas",
                    message = report,
                    positiveLabel = "Fechar"
                )
            }
        }
    }

    private fun addTitle(title: String, summary: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = summary
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })
    }

    private fun addSection(title: String): TextView = TextView(this).apply {
            text = title.uppercase()
            setTextColor(AppearanceStore.palette(this@SettingsActivity).accent)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val topPadding = when (AppearanceStore.density(this@SettingsActivity)) {
                AppearanceStore.DENSITY_COMPACT -> 14
                AppearanceStore.DENSITY_SPACIOUS -> 22
                else -> 18
            }
            setPadding(0, dp(topPadding), 0, dp(7))
        }.also(container::addView)

    private fun addSpinner(
        label: String,
        options: List<ChoiceSpinnerAdapter.Option>,
        selectedValue: String
    ): Spinner {
        val labelView = labelView(label)
        container.addView(labelView)
        val spinner = OneUiSpinner(this).apply {
            prompt = label
            contentDescription = label
            setBackgroundResource(R.drawable.bg_oneui_field)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val adapter = ChoiceSpinnerAdapter(this, options)
        spinner.adapter = adapter
        val initial = adapter.positionOf(selectedValue).takeIf { it >= 0 }
            ?: options.indexOfFirst { it.enabled }.takeIf { it >= 0 }
            ?: 0
        adapter.selectedPosition = initial
        spinner.setSelection(initial, false)

        val helper = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(dp(8), dp(5), dp(8), dp(9))
            text = options.getOrNull(initial)?.description.orEmpty()
        }
        helperBySpinner[spinner] = helper
        labelBySpinner[spinner] = labelView
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val item = adapter.getItem(position)
                if (!item.enabled) {
                    spinner.setSelection(adapter.selectedPosition, false)
                    return
                }
                adapter.selectedPosition = position
                helper.text = item.description
                onSpinnerChanged(spinner)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        container.addView(spinner, matchHeight(AppearanceStore.controlHeightDp(this), bottom = AppearanceStore.controlSpacingDp(this) / 2))
        return spinner
    }

    private fun addSwitch(title: String, summary: String, checked: Boolean): Switch =
        ScrollSafeSwitch(this).apply {
            text = "$title\n$summary"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            isChecked = checked
            thumbTintList = getColorStateList(R.color.switch_thumb_tint)
            trackTintList = getColorStateList(R.color.switch_track_tint)
            gravity = Gravity.CENTER_VERTICAL
            val verticalPadding = AppearanceStore.controlVerticalPaddingDp(this@SettingsActivity)
            setPadding(dp(14), dp(verticalPadding), dp(12), dp(verticalPadding))
            setBackgroundResource(R.drawable.bg_oneui_field)
            container.addView(this, matchWrap(bottom = AppearanceStore.controlSpacingDp(this@SettingsActivity)))
        }

    private fun addSmallButton(text: String, action: () -> Unit): TextView {
        val button = ScrollSafeTextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(AppearanceStore.buttonHeightDp(this@SettingsActivity))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setBackgroundResource(R.drawable.bg_oneui_button_secondary)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        container.addView(button, matchWrap(bottom = AppearanceStore.controlSpacingDp(this)))
        return button
    }

    private fun addCapabilitiesCard(): TextView = TextView(this).apply {
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setLineSpacing(0f, 1.12f)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundResource(R.drawable.bg_card)
        text = capabilityCardText(modeCatalog())
        container.addView(this, matchWrap(bottom = 9))
    }

    private fun updateCapabilitiesCard(catalog: CaptureModeCatalog.Catalog = modeCatalog()) {
        if (!::modeCapabilitiesText.isInitialized) return
        modeCapabilitiesText.text = capabilityCardText(catalog)
    }

    private fun capabilityCardText(catalog: CaptureModeCatalog.Catalog): String {
        val settings = CaptureSettings.snapshot(this)
        val camera = CameraLensCatalog.labelFor(this, settings.selectedCameraId)
        val features = selectedCameraFeatures()
        val stabilization = features?.stabilization
            ?.filterValues { it != Support.UNSUPPORTED }
            ?.keys
            ?.joinToString { value ->
                when (value) {
                    CaptureSettings.STABILIZATION_PREVIEW -> "Preview"
                    CaptureSettings.STABILIZATION_EIS -> "EIS"
                    CaptureSettings.STABILIZATION_OIS -> "OIS"
                    CaptureSettings.STABILIZATION_OFF -> "Off"
                    else -> "Inválida"
                }
            }
            .orEmpty()
        val hdr = when (features?.hdrHlg10) {
            Support.SUPPORTED -> "HLG10 confirmado"
            Support.UNVERIFIED -> "HLG10 validado ao usar"
            Support.UNSUPPORTED -> "somente SDR"
            null -> "HDR em análise"
        }
        val featureLine = if (stabilization.isBlank()) hdr else "$stabilization • $hdr"
        return "CAPACIDADES DE $camera\n${catalog.summary()}\n$featureLine"
    }

    private fun addInfo(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundResource(R.drawable.bg_card)
    }.also { container.addView(it, matchWrap(bottom = 9)) }

    private fun updateOptions(spinner: Spinner, options: List<ChoiceSpinnerAdapter.Option>, selectedValue: String) {
        val adapter = spinner.adapter as ChoiceSpinnerAdapter
        val previousBuilding = building
        building = true
        adapter.replace(options, selectedValue)
        spinner.setSelection(adapter.selectedPosition, false)
        helperBySpinner[spinner]?.text = adapter.selectedOption().description
        building = previousBuilding
    }

    private fun setSelection(spinner: Spinner, value: String) {
        val adapter = spinner.adapter as ChoiceSpinnerAdapter
        val position = adapter.positionOf(value)
        if (position >= 0) {
            adapter.selectedPosition = position
            spinner.setSelection(position, false)
            helperBySpinner[spinner]?.text = adapter.getItem(position).description
        }
    }

    private fun selected(spinner: Spinner): String {
        val adapter = spinner.adapter as ChoiceSpinnerAdapter
        return adapter.valueAt(spinner.selectedItemPosition.coerceAtLeast(0))
    }

    private data class FeatureOptionSpec(
        val value: String,
        val label: String,
        val description: String
    )

    private fun selectedCapabilityMatrix(): CaptureCapabilityMatrix.Matrix? {
        val matrix = capabilityMatrix ?: return null
        val settings = CaptureSettings.snapshot(this)
        val cameraId = settings.selectedCameraId
            ?: CameraLensCatalog.resolveCameraId(this, null, settings.resolution)
        return matrix.forCamera(cameraId)
    }

    private fun selectedCameraFeatures(): CaptureCapabilityMatrix.CameraFeatures? {
        val settings = CaptureSettings.snapshot(this)
        return selectedCapabilityMatrix()?.featuresFor(settings.selectedCameraId)
    }

    private fun featureOptions(
        specs: List<FeatureOptionSpec>,
        currentValue: String,
        supportOf: (String) -> Support
    ): List<ChoiceSpinnerAdapter.Option> {
        val options = specs.mapNotNull { spec ->
            val support = supportOf(spec.value)
            if (!HardwareSupportPolicy.shouldExpose(support)) return@mapNotNull null
            val enabled = HardwareSupportPolicy.isSelectable(support)
            val description = when (support) {
                Support.SUPPORTED -> spec.description
                Support.UNVERIFIED -> "${spec.description} Validação real ao abrir a sessão."
                Support.UNSUPPORTED -> "Não suportado pela câmera selecionada."
            }
            option(spec.value, spec.label, description, enabled, description)
        }
        return options.ifEmpty {
            val fallback = specs.firstOrNull { it.value == currentValue } ?: specs.first()
            listOf(option(fallback.value, fallback.label, "Aguardando a análise do aparelho.", false, "Aguardando a análise do aparelho."))
        }
    }

    private fun stabilizationOptions(currentValue: String): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val activeFps = if (::fps.isInitialized) selected(fps).toIntOrNull() ?: CaptureSettings.snapshot(this).fps else CaptureSettings.snapshot(this).fps
        val specs = listOf(
            FeatureOptionSpec(CaptureSettings.STABILIZATION_PREVIEW, "Preview stabilization", "Estabilização avançada da câmera."),
            FeatureOptionSpec(CaptureSettings.STABILIZATION_EIS, "EIS eletrônica", "Recorta a imagem e usa processamento eletrônico."),
            FeatureOptionSpec(CaptureSettings.STABILIZATION_OIS, "OIS óptica", "Usa o movimento físico da lente."),
            FeatureOptionSpec(CaptureSettings.STABILIZATION_OFF, "Desativada", "Menor processamento e enquadramento integral.")
        )
        fun directSupport(value: String): Support = if (value == CaptureSettings.STABILIZATION_PREVIEW && activeFps >= 120) Support.UNSUPPORTED else features?.stabilizationSupport(value) ?: Support.UNVERIFIED
        return featureOptions(specs, currentValue) { value -> if (value == CaptureSettings.STABILIZATION_OFF) Support.SUPPORTED else directSupport(value) }
    }

    private fun focusOptions(currentValue: String): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val activeFps = if (::fps.isInitialized) selected(fps).toIntOrNull() ?: 60 else CaptureSettings.snapshot(this).fps
        val specs = listOf(
            FeatureOptionSpec(CaptureSettings.FOCUS_CONTINUOUS_VIDEO, "Contínuo para vídeo", "Mantém o foco acompanhando a cena."),
            FeatureOptionSpec(CaptureSettings.FOCUS_CONTINUOUS_PICTURE, "Contínuo para foto", "Reage rapidamente a mudanças de assunto."),
            FeatureOptionSpec(CaptureSettings.FOCUS_AUTO, "Automático", "Foco pontual controlado pela câmera."),
            FeatureOptionSpec(CaptureSettings.FOCUS_OFF, "Fixo", "Evita caça de foco; depende da distância atual da lente.")
        )
        return featureOptions(specs, currentValue) { value ->
            if (
                activeFps >= 120 &&
                value != CaptureSettings.FOCUS_CONTINUOUS_VIDEO &&
                value != CaptureSettings.FOCUS_OFF
            ) {
                Support.UNSUPPORTED
            } else {
                features?.focusSupport(value) ?: Support.UNVERIFIED
            }
        }
    }

    private fun processingOptions(
        noise: Boolean,
        currentValue: String
    ): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val activeFps = if (::fps.isInitialized) selected(fps).toIntOrNull() ?: 60 else CaptureSettings.snapshot(this).fps
        val specs = if (noise) {
            listOf(
                FeatureOptionSpec(CaptureSettings.PROCESSING_AUTO, "Automática", "A câmera decide a redução de ruído conforme luz e cena, buscando equilíbrio entre limpeza e detalhes."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_OFF, "Desativada", "Não aplica redução de ruído da câmera; preserva textura fina, mas deixa granulação mais visível em pouca luz."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_FAST, "Rápida", "Usa redução de ruído de baixa latência, com processamento mais leve e menor suavização dos detalhes."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_HIGH_QUALITY, "Alta qualidade", "Prioriza limpeza de ruído com processamento mais forte; pode suavizar detalhes finos e exigir mais da câmera.")
            )
        } else {
            listOf(
                FeatureOptionSpec(CaptureSettings.PROCESSING_AUTO, "Automática", "A câmera escolhe a intensidade de nitidez conforme a cena para equilibrar detalhe e contornos naturais."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_OFF, "Desativada", "Desliga o realce eletrônico de bordas; produz contornos mais naturais e reduz a chance de halos."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_FAST, "Rápida", "Aplica realce leve com baixa latência, mantendo o processamento de contornos mais simples."),
                FeatureOptionSpec(CaptureSettings.PROCESSING_HIGH_QUALITY, "Alta qualidade", "Usa processamento de nitidez mais elaborado; pode destacar detalhes, mas também tornar halos mais perceptíveis.")
            )
        }
        return featureOptions(specs, currentValue) { value ->
            if (activeFps >= 120) {
                if (value == CaptureSettings.PROCESSING_AUTO) Support.SUPPORTED else Support.UNSUPPORTED
            } else if (noise) {
                features?.noiseReductionSupport(value) ?: Support.UNVERIFIED
            } else {
                features?.edgeSupport(value) ?: Support.UNVERIFIED
            }
        }
    }

    private fun antibandingOptions(currentValue: String): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val specs = listOf(
            FeatureOptionSpec(CaptureSettings.ANTIBANDING_AUTO, "Automático", "Detecta a frequência da iluminação."),
            FeatureOptionSpec(CaptureSettings.ANTIBANDING_50HZ, "50 Hz", "Para redes e luzes de 50 Hz."),
            FeatureOptionSpec(CaptureSettings.ANTIBANDING_60HZ, "60 Hz", "Recomendado no Brasil para muitas fontes elétricas."),
            FeatureOptionSpec(CaptureSettings.ANTIBANDING_OFF, "Desativado", "Pode causar faixas em luz artificial.")
        )
        return featureOptions(specs, currentValue) { value ->
            features?.antibandingSupport(value) ?: Support.UNVERIFIED
        }
    }

    private fun whiteBalanceOptions(currentValue: String): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val specs = listOf(
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_AUTO, "Automático estabilizado", "Usa a medição de cor do aparelho."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_INCANDESCENT, "Luz quente / tungstênio", "Reduz o amarelo de lâmpadas quentes."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_FLUORESCENT, "Fluorescente", "Compensa iluminação fluorescente."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT, "Fluorescente quente", "Para fluorescente ou LED muito quente."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_DAYLIGHT, "Luz do dia", "Mantém cores naturais sob sol direto."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_CLOUDY, "Nublado", "Aquece levemente cenas externas frias."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_TWILIGHT, "Entardecer", "Compensa a luz azul do fim do dia."),
            FeatureOptionSpec(CaptureSettings.WHITE_BALANCE_SHADE, "Sombra", "Aquece cenas sob sombra forte.")
        )
        return featureOptions(specs, currentValue) { value ->
            features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
        }
    }

    private fun yellowReductionOptions(currentValue: String): List<ChoiceSpinnerAdapter.Option> {
        val features = selectedCameraFeatures()
        val manual = features?.manualPostProcessing ?: Support.UNVERIFIED
        val incandescent = features?.whiteBalanceSupport(CaptureSettings.WHITE_BALANCE_INCANDESCENT)
            ?: Support.UNVERIFIED
        val strongFallback = when {
            manual == Support.SUPPORTED || incandescent == Support.SUPPORTED -> Support.SUPPORTED
            manual == Support.UNVERIFIED || incandescent == Support.UNVERIFIED -> Support.UNVERIFIED
            else -> Support.UNSUPPORTED
        }
        return featureOptions(
            specs = listOf(
                FeatureOptionSpec(CaptureSettings.YELLOW_REDUCTION_OFF, "Desativado", "Mantém exatamente a resposta de cor da câmera."),
                FeatureOptionSpec(CaptureSettings.YELLOW_REDUCTION_AUTO, "Automático conservador", "Corrige usando os ganhos medidos pela câmera."),
                FeatureOptionSpec(CaptureSettings.YELLOW_REDUCTION_LIGHT, "Leve", "Ajuste manual sutil dos canais de cor."),
                FeatureOptionSpec(CaptureSettings.YELLOW_REDUCTION_MEDIUM, "Médio", "Correção visível para luz quente."),
                FeatureOptionSpec(CaptureSettings.YELLOW_REDUCTION_STRONG, "Forte", "Correção para ambientes muito amarelados.")
            ),
            currentValue = currentValue
        ) { value ->
            when (value) {
                CaptureSettings.YELLOW_REDUCTION_OFF -> Support.SUPPORTED
                CaptureSettings.YELLOW_REDUCTION_AUTO,
                CaptureSettings.YELLOW_REDUCTION_LIGHT -> manual
                else -> strongFallback
            }
        }
    }

    private fun codecOptions(settings: CaptureSettings.Snapshot): List<ChoiceSpinnerAdapter.Option> {
        val matrix = selectedCapabilityMatrix()
        fun support(value: String): Support = when (value) {
            CaptureSettings.CODEC_HEVC -> matrix?.encoderSupport(
                settings.selectedCameraId,
                settings.resolution,
                settings.fps,
                MediaFormat.MIMETYPE_VIDEO_HEVC
            ) ?: Support.UNVERIFIED
            CaptureSettings.CODEC_AVC -> matrix?.encoderSupport(
                settings.selectedCameraId,
                settings.resolution,
                settings.fps,
                MediaFormat.MIMETYPE_VIDEO_AVC
            ) ?: Support.UNVERIFIED
            else -> Support.SUPPORTED
        }
        return featureOptions(
            specs = listOf(
                FeatureOptionSpec(CaptureSettings.CODEC_HEVC, "HEVC / H.265", "Melhor qualidade por tamanho e necessário para HLG10."),
                FeatureOptionSpec(CaptureSettings.CODEC_AVC, "AVC / H.264", "Maior compatibilidade, com arquivos geralmente maiores.")
            ),
            currentValue = settings.codec,
            supportOf = ::support
        )
    }

    private fun colorProfileOptions(settings: CaptureSettings.Snapshot): List<ChoiceSpinnerAdapter.Option> {
        val customSupported = !settings.hdrHlg10 &&
            settings.fps < 60
        return featureOptions(
            specs = listOf(
                FeatureOptionSpec(CaptureSettings.COLOR_NATURAL, "Natural", "BT.709 limitado e tons equilibrados."),
                FeatureOptionSpec(CaptureSettings.COLOR_SOFT, "Natural suave", "Curva tonal com contraste mais suave."),
                FeatureOptionSpec(CaptureSettings.COLOR_FLAT, "Baixo contraste", "Curva mais plana para editar depois.")
            ),
            currentValue = settings.colorProfile
        ) { value ->
            if (value == CaptureSettings.COLOR_NATURAL || customSupported) Support.SUPPORTED else Support.UNSUPPORTED
        }
    }

    private fun refreshHardwareFeatureOptions() {
        if (!::stabilization.isInitialized || !::codec.isInitialized) return
        val stored = CaptureSettings.snapshot(this)
        val fpsValue = if (::fps.isInitialized) selected(fps).toIntOrNull() ?: stored.fps else stored.fps
        val resolutionValue = if (::resolution.isInitialized) selected(resolution) else stored.resolution
        val current = stored.copy(
            fps = fpsValue,
            resolution = resolutionValue,
            codec = selected(codec),
            hdrHlg10 = hdr.isChecked,
            colorProfile = selected(colorProfile)
        )

        updateOptions(codec, codecOptions(current), current.codec)
        updateOptions(stabilization, stabilizationOptions(selected(stabilization)), selected(stabilization))
        updateOptions(focus, focusOptions(selected(focus)), selected(focus))
        updateOptions(
            noiseReduction,
            processingOptions(noise = true, currentValue = selected(noiseReduction)),
            selected(noiseReduction)
        )
        updateOptions(
            edge,
            processingOptions(noise = false, currentValue = selected(edge)),
            selected(edge)
        )
        updateOptions(antibanding, antibandingOptions(selected(antibanding)), selected(antibanding))
        updateOptions(whiteBalance, whiteBalanceOptions(selected(whiteBalance)), selected(whiteBalance))
        updateOptions(yellowReduction, yellowReductionOptions(selected(yellowReduction)), selected(yellowReduction))
        updateOptions(colorProfile, colorProfileOptions(current), current.colorProfile)

        listOf(
            stabilization, focus, noiseReduction, edge, antibanding, whiteBalance,
            yellowReduction, colorProfile, previewMode
        )
            .forEach(::updateSpinnerVisibility)

        val features = selectedCameraFeatures()
        val hdrSupport = features?.hdrHlg10 ?: Support.UNVERIFIED
        if (hdrSupport == Support.UNSUPPORTED && hdr.isChecked) hdr.isChecked = false
        hdr.visibility = if (
            HardwareSupportPolicy.shouldExpose(hdrSupport)
        ) View.VISIBLE else View.GONE

        val exposureVisible = features?.exposureCompensationSupported != false
        exposure.visibility = if (exposureVisible) View.VISIBLE else View.GONE
        labelBySpinner[exposure]?.visibility = exposure.visibility
        if (!exposureVisible) setSelection(exposure, "0")

        updateSwitchAvailability(lockWhiteBalance, features?.awbLock ?: Support.UNVERIFIED)

    }

    private fun updateSpinnerVisibility(spinner: Spinner) {
        val count = (spinner.adapter as ChoiceSpinnerAdapter).count
        val visible = count > 1 || !capabilityScanCompleted
        spinner.visibility = if (visible) View.VISIBLE else View.GONE
        labelBySpinner[spinner]?.visibility = spinner.visibility
        helperBySpinner[spinner]?.visibility = spinner.visibility
    }

    private fun updateSwitchAvailability(control: Switch, support: Support) {
        val visible = HardwareSupportPolicy.shouldExpose(support)
        control.visibility = if (visible) View.VISIBLE else View.GONE
        control.isEnabled = HardwareSupportPolicy.isSelectable(support)
        control.alpha = if (control.isEnabled) 1f else 0.45f
        if (!visible && control.isChecked) control.isChecked = false
    }

    private fun resolutionOptions(selectedFps: Int): List<ChoiceSpinnerAdapter.Option> {
        val fpsCapability = modeCatalog().profile(selectedFps)
        return listOf(
            CaptureSettings.RESOLUTION_8K,
            CaptureSettings.RESOLUTION_4K,
            CaptureSettings.RESOLUTION_1080P,
            CaptureSettings.RESOLUTION_720P
        ).map { value ->
            val profile = configuredProfile(selectedFps, value)
            option(
                value = value,
                label = CaptureSettings.resolutionLabel(value),
                description = profile.capabilityDescription,
                enabled = profile.selectable && fpsCapability.source != CaptureModeCatalog.Source.UNAVAILABLE,
                disabledReason = profile.capabilityDescription
            )
        }
    }

    private fun configuredProfile(
        fpsValue: Int,
        resolutionValue: String = resolutionSelections[fpsValue]
            ?: CaptureSettings.resolutionForFps(this, fpsValue)
    ): CaptureModeCatalog.Profile = CaptureModeCatalog.resolveSelection(
        context = this,
        fps = fpsValue,
        resolution = resolutionValue,
        matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
        scanInProgress = capabilityMatrix == null && capabilityScanInProgress
    )

    private fun fpsOptions(catalog: CaptureModeCatalog.Catalog = modeCatalog()): List<ChoiceSpinnerAdapter.Option> =
        CaptureSettings.supportedFpsValues.mapNotNull { value ->
            val capability = catalog.profile(value)
            if (capability.source == CaptureModeCatalog.Source.UNAVAILABLE) return@mapNotNull null
            val configured = configuredProfile(value)
            val label = when (capability.source) {
                CaptureModeCatalog.Source.UNAVAILABLE -> "$value FPS • indisponível"
                CaptureModeCatalog.Source.ANALYZING -> "$value FPS • analisando"
                else -> "$value FPS • ${configured.resolutionLabel}"
            }
            option(
                value = value.toString(),
                label = label,
                description = configured.capabilityDescription,
                enabled = capability.selectable,
                disabledReason = capability.capabilityDescription
            )
        }.ifEmpty {
            listOf(option(CaptureSettings.snapshot(this).fps.toString(), "Analisando FPS", "Aguardando a câmera e o encoder.", false, "Aguardando a câmera e o encoder."))
        }


    private fun bitrateOptions(current: Int): List<ChoiceSpinnerAdapter.Option> =
        (listOf(4, 6, 8, 10, 12, 15, 20, 24, 28, 32, 40, 44, 48, 50, 60, 64, 80, 100, 120, 135, 150, 180, 200, 220, 240) + current)
            .distinct().sorted().map { value ->
                val profile = when {
                    value <= 10 -> "prioriza arquivos menores; pode perder detalhes em 4K ou cenas com muito movimento"
                    value <= 28 -> "equilibra tamanho e qualidade em cenas comuns, especialmente em resoluções menores"
                    value <= 64 -> "prioriza qualidade e preservação de detalhes em movimento com tamanho de arquivo maior"
                    value <= 120 -> "usa taxa alta para cenas complexas e alta resolução, aumentando bastante o espaço ocupado"
                    else -> "usa taxa muito alta, indicada apenas quando o encoder e o armazenamento sustentam esse volume de dados"
                }
                option(value.toString(), "$value Mbps", "Envia $value Mbps ao encoder: $profile.")
            }

    private fun exposureOptions(current: Int): List<ChoiceSpinnerAdapter.Option> =
        ((-12..12).toList() + current).distinct().sorted().map { value ->
            val effect = when {
                value <= -6 -> "escurecimento forte"
                value < 0 -> "escurecimento leve a moderado"
                value == 0 -> "sem compensação; mantém a exposição calculada pela câmera"
                value < 6 -> "clareamento leve a moderado"
                else -> "clareamento forte"
            }
            option(value.toString(), if (value > 0) "+$value" else value.toString(), "Compensação $value: $effect. O efeito exato depende do passo de exposição publicado pela câmera.")
        }

    private fun audioGainOptions(current: Int): List<ChoiceSpinnerAdapter.Option> =
        ((0..30).toList() + current).distinct().sorted().map { value ->
            val effect = when {
                value == 0 -> "não adiciona ganho manual ao áudio"
                value <= 6 -> "aumento leve, útil quando o microfone já capta em bom nível"
                value <= 12 -> "aumento moderado, com maior chance de elevar ruído de fundo"
                value <= 20 -> "aumento forte, indicado apenas para fontes realmente baixas"
                else -> "aumento muito forte; pode amplificar ruído e aproximar picos da saturação"
            }
            option(value.toString(), "$value dB", "Limite de ganho em $value dB: $effect.")
        }

    private fun playbackCacheOptions(current: Int): List<ChoiceSpinnerAdapter.Option> =
        (listOf(250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000) + current.coerceIn(250, 5000))
            .distinct().sorted().map { value ->
                val effect = when {
                    value <= 500 -> "resposta mais imediata, com menos margem para arquivos pesados"
                    value <= 1000 -> "equilíbrio entre início rápido e tolerância a variações de leitura"
                    value <= 2000 -> "mais margem para 4K e bitrates altos, com pequena espera adicional"
                    else -> "buffer amplo para arquivos difíceis, com maior demora antes de iniciar ou retomar"
                }
                option(value.toString(), "$value ms", "No player de compatibilidade, mantém até $value ms de conteúdo em buffer: $effect.")
            }
    private fun option(
        value: String,
        label: String,
        description: String,
        enabled: Boolean = true,
        disabledReason: String = ""
    ) = ChoiceSpinnerAdapter.Option(value, label, description, enabled, disabledReason)

    private fun labelView(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(7), 0, dp(6))
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun matchHeight(height: Int, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height)
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 550L
        const val EXTRA_PROFILE_MODE = "profile_mode"
    }

}
