from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'bloco não encontrado em {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# ---------------------------------------------------------------------------
# CaptureService: captura tem prioridade sobre reparo, 240 usa melhor tamanho
# publicamente anunciado, e high-speed passa a usar controles permitidos.
# ---------------------------------------------------------------------------
cap = 'app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt'
replace_once(
    cap,
    'import com.steadyvault.camera.storage.vault.VaultRepository\n',
    'import com.steadyvault.camera.storage.vault.VaultRepository\nimport com.steadyvault.camera.processing.auto.AutoGapRepairService\n'
)
replace_once(
    cap,
    '''        if (!serviceActive.compareAndSet(false, true)) {
            sendState(currentState)
            return
        }
        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)
''',
    '''        if (!serviceActive.compareAndSet(false, true)) {
            sendState(currentState)
            return
        }
        // A captura sempre vence qualquer transcodificação automática. O pedido de
        // cancelamento é síncrono no processo; não esperamos o reparo encerrar.
        AutoGapRepairService.pauseForCapture(this)
        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)
'''
)
replace_once(
    cap,
    '''        if (!hasRequiredPermissions()) {
            sendState("Falha: permissão de câmera é obrigatória")
            serviceActive.set(false)
            stopSelf()
            return
        }
''',
    '''        if (!hasRequiredPermissions()) {
            sendState("Falha: permissão de câmera é obrigatória")
            serviceActive.set(false)
            AutoGapRepairService.resumeAfterCapture(this)
            stopSelf()
            return
        }
'''
)
replace_once(
    cap,
    '''        val sizeSupported = cameraCandidates.filter { (_, size) ->
            highSpeedEncoderSupportLevel(size, targetFps, mime) >= 1
        }
        if (sizeSupported.isNotEmpty()) {
            // Em 240, metadata de rate ausente é tratada de forma conservadora:
            // usa a menor resolução high-speed comum para maximizar chance de 240 real.
            return if (targetFps >= CaptureModeStore.FPS_240) {
                sizeSupported.last().first
            } else {
                sizeSupported.first().first
            }
        }
        return catalogResolution
''',
    '''        val sizeSupported = cameraCandidates.filter { (_, size) ->
            highSpeedEncoderSupportLevel(size, targetFps, mime) >= 1
        }
        if (sizeSupported.isNotEmpty()) {
            // MediaCodec em Samsung frequentemente omite o limite de FPS de encoders
            // proprietários. Se o tamanho é aceito pelo hardware, preserve a maior
            // resolução que a própria câmera anuncia para o FPS solicitado.
            return sizeSupported.first().first
        }
        // Não deixe metadata incompleta do encoder transformar um modo high-speed
        // Camera2 válido em "aguardando confirmação". MediaRecorder/HAL é a validação
        // final, exatamente como já fazemos no caminho CLEAN de 60 FPS.
        return cameraCandidates.first().first
'''
)
old_highspeed = '''    private fun configureConstrainedHighSpeedRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            setSafely(builder, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        setSafely(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)

        // High-speed prioriza cadence. EIS/Preview stabilization/OIS ficam fora do
        // request constrained e podem ser testados depois de 120/240 estabilizarem.
        setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
        CameraZoom.apply(builder, profile.characteristics, recordingSettings.zoomRatio)
    }
'''
new_highspeed = '''    private fun configureConstrainedHighSpeedRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        // Em CONSTRAINED_HIGH_SPEED a HAL força AE/AWB/AF e pós-processamento FAST.
        // Mantemos somente controles que a API pública permite influenciar nesse modo.
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val exposureRange = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureRange?.let { range ->
            setSafely(
                builder,
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                recordingSettings.exposureCompensation.coerceIn(range.lower, range.upper)
            )
        }

        val antibandingModes = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
        ) ?: intArrayOf()
        val requestedAntibanding = when (recordingSettings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(requestedAntibanding)) {
            setSafely(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAntibanding)
        }

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            setSafely(builder, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        setSafely(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)

        // O Camera2 documenta OIS como controle válido também no modo high-speed.
        // EIS/preview stabilization são best-effort: só são enviados quando a câmera
        // publica explicitamente o modo. OFF permanece o fallback mais previsível.
        val videoModes = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        ) ?: intArrayOf()
        val requestedStabilization = recordingSettings.stabilization
        val autoUseOis = requestedStabilization == CaptureSettings.STABILIZATION_AUTO && profile.oisSupported
        val explicitOis = requestedStabilization == CaptureSettings.STABILIZATION_OIS
        val explicitEis = requestedStabilization == CaptureSettings.STABILIZATION_EIS
        val explicitPreview = requestedStabilization == CaptureSettings.STABILIZATION_PREVIEW
        when {
            (autoUseOis || explicitOis) && profile.oisSupported -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = true)
            }
            explicitEis && videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> {
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            explicitPreview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) -> {
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            }
            else -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
        }
        CameraZoom.apply(builder, profile.characteristics, recordingSettings.zoomRatio)
    }
'''
replace_once(cap, old_highspeed, new_highspeed)
replace_once(
    cap,
    '''        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10" else "SDR BT.709"
        val message = "Vídeo salvo no cofre • ${sizeName(size)} • $fps FPS • $qualityLabel • ${formatDuration(durationSeconds)}"
''',
    '''        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10" else "SDR BT.709"
        // O original já está finalizado e indexado antes de entrar na fila. O reparo
        // nunca recebe permissão para substituir este arquivo.
        AutoGapRepairService.enqueue(this, finalFile, fps)
        val message = "Vídeo salvo no cofre • ${sizeName(size)} • $fps FPS • $qualityLabel • ${formatDuration(durationSeconds)}"
'''
)
replace_once(
    cap,
    '''        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        releaseWakeLock()
''',
    '''        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        releaseWakeLock()
        AutoGapRepairService.resumeAfterCapture(this)
'''
)
replace_once(
    cap,
    '''    private fun abortBeforeCaptureStart() {
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
''',
    '''    private fun abortBeforeCaptureStart() {
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
        AutoGapRepairService.resumeAfterCapture(this)
'''
)

# ---------------------------------------------------------------------------
# Player: 120/240 são gravação em tempo real; não aplicar slow motion automático.
# Ao descobrir HFR depois que o player abriu, reabre o Media3 com perfil HFR.
# ---------------------------------------------------------------------------
player_activity = 'app/src/main/java/com/steadyvault/camera/ui/vault/MediaPlayerActivity.kt'
replace_once(
    player_activity,
    '''    private fun applyAutomaticPlaybackSpeed(frameRate: Float) {
        if (userSelectedPlaybackSpeed) return
        val targetSpeed = if (frameRate in HIGH_FRAME_RATE_MIN..HIGH_FRAME_RATE_MAX) 0.5f else 1f
        val targetIndex = PLAYBACK_SPEEDS.indexOfFirst { it == targetSpeed }.takeIf { it >= 0 } ?: 2
''',
    '''    private fun applyAutomaticPlaybackSpeed(frameRate: Float) {
        if (userSelectedPlaybackSpeed) return
        // 120/240 gravados pelo SteadyVault são vídeo em tempo real, como 4K120 da
        // câmera Samsung. Slow motion só acontece se o usuário escolher outra velocidade.
        val targetSpeed = 1f
        val targetIndex = PLAYBACK_SPEEDS.indexOfFirst { it == targetSpeed }.takeIf { it >= 0 } ?: 2
'''
)

zoom = 'app/src/main/java/com/steadyvault/camera/ui/gesture/ZoomableVideoView.kt'
replace_once(
    zoom,
    '''            val metadata = readSourceMetadata(uri)
            post {
                if (generation != analysisGeneration || source != uri) return@post
                playbackProfile = quickPlaybackProfile(metadata)
                sourceFrameRate = metadata.frameRate
''',
    '''            val metadata = readSourceMetadata(uri)
            post {
                if (generation != analysisGeneration || source != uri) return@post
                val wasHighFrameRate = isHighFrameRatePlayback()
                playbackProfile = quickPlaybackProfile(metadata)
                sourceFrameRate = metadata.frameRate
'''
)
replace_once(
    zoom,
    '''                analysisListener?.invoke(playbackProfile)
                applySurfaceFrameRate()
                val delayMs = if (metadata.frameRate >= HIGH_FRAME_RATE_SOURCE_MIN) {
''',
    '''                analysisListener?.invoke(playbackProfile)
                applySurfaceFrameRate()
                val nowHighFrameRate = isHighFrameRatePlayback()
                if (!wasHighFrameRate && nowHighFrameRate && engine == PlaybackEngine.MEDIA3 && media3Player != null) {
                    // O player pode ter sido criado antes da leitura dos PTS. Reabrir
                    // preservando posição aplica thresholds/buffer específicos de 120/240.
                    captureCurrentState()
                    requestEngineOpen(preservePosition = true)
                }
                val delayMs = if (metadata.frameRate >= HIGH_FRAME_RATE_SOURCE_MIN) {
'''
)

# ---------------------------------------------------------------------------
# Settings: habilita perfil de cor em 60 quando a opção realmente existe e expõe
# controle persistente da fila de reparo automático.
# ---------------------------------------------------------------------------
settings = 'app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt'
replace_once(
    settings,
    'import com.steadyvault.camera.core.state.CaptureStateStore\n',
    'import com.steadyvault.camera.core.state.CaptureStateStore\nimport com.steadyvault.camera.processing.auto.AutoGapRepairQueueStore\nimport com.steadyvault.camera.processing.auto.AutoGapRepairService\nimport com.steadyvault.camera.processing.auto.AutoGapRepairSettings\nimport com.steadyvault.camera.processing.model.FrameRepairMode\n'
)
replace_once(
    settings,
    '''    private lateinit var mediaDetailsLoadingMode: Spinner
    private lateinit var cacheUsageText: TextView
''',
    '''    private lateinit var mediaDetailsLoadingMode: Spinner
    private lateinit var cacheUsageText: TextView
    private lateinit var autoGapRepairEnabled: Switch
    private lateinit var autoGapRepairMode: Spinner
    private lateinit var autoGapRepairMaxFrames: Spinner
    private lateinit var autoGapRepairAi: Switch
    private lateinit var autoGapRepairQueueInfo: TextView
'''
)
replace_once(
    settings,
    '''        addSection("Processamento de vídeo")
        addInfo("Nada é processado automaticamente depois da captura. O MP4 direto é o arquivo final; otimização continua disponível somente por ação manual no Cofre.")

        val playback = PlaybackSettings.snapshot(this)
''',
    '''        addSection("Processamento de vídeo")
        val autoRepair = AutoGapRepairSettings.snapshot(this)
        autoGapRepairEnabled = addSwitch(
            "Reparar gaps automaticamente após gravar",
            "Analisa os timestamps do MP4 original em segundo plano. Quando há lacunas, cria uma NOVA cópia reparada; o original nunca é substituído. Se outra gravação começar, o processamento é interrompido e volta para a fila.",
            autoRepair.enabled
        )
        autoGapRepairMode = addSpinner(
            "Método automático para completar lacunas",
            listOf(
                option(FrameRepairMode.ADAPTIVE_BLEND.name, "Mistura temporal por GPU (recomendado)", "Cria somente os quadros ausentes misturando os dois quadros reais vizinhos. Se o gap for grande demais, usa o quadro real mais próximo."),
                option(FrameRepairMode.FILL_MISSING_FRAMES.name, "Quadro real mais próximo", "Preenche posições CFR usando um quadro vizinho real; não inventa movimento, mas pode deixar um instante repetido."),
                option(FrameRepairMode.SMOOTH_TIMELINE.name, "Somente corrigir timestamps", "Não cria novos pixels. Regulariza a timeline e é o fallback seguro para HDR ou encoder incompatível.")
            ),
            autoRepair.mode.name
        )
        autoGapRepairMaxFrames = addSpinner(
            "Máximo de quadros sintetizados por gap",
            listOf(
                option("1", "1 quadro", "Mais conservador; mistura somente gaps curtos."),
                option("2", "2 quadros", "Conservador para movimento rápido."),
                option("4", "4 quadros (recomendado)", "Cobre os gaps curtos observados no 60 FPS sem processar trechos longos."),
                option("8", "8 quadros", "Aceita lacunas maiores antes de cair para quadro vizinho."),
                option("16", "16 quadros", "Mais agressivo; use somente se houver falhas longas.")
            ),
            autoRepair.maxInterpolatedFramesPerGap.toString()
        )
        autoGapRepairAi = addSwitch(
            "Análise visual local assistida",
            "Opcional. Usa o analisador local já existente para orientar filtros durante a cópia reparada. O preenchimento dos gaps continua determinístico por GPU; nenhum serviço externo recebe o vídeo.",
            autoRepair.aiAssisted
        )
        autoGapRepairQueueInfo = addInfo(AutoGapRepairQueueStore.summary(this).text())
        addSmallButton("Tentar novamente os reparos com erro") {
            AutoGapRepairService.retryFailed(this)
            refreshAutoGapRepairQueueCard()
            Toast.makeText(this, "Falhas reenfileiradas quando houver", Toast.LENGTH_SHORT).show()
        }
        addSmallButton("Retomar fila de reparo agora") {
            AutoGapRepairService.resumeIfEnabled(this)
            refreshAutoGapRepairQueueCard()
            Toast.makeText(this, "Fila de reparo retomada", Toast.LENGTH_SHORT).show()
        }
        addInfo("O reparo roda com prioridade baixa e nunca bloqueia a câmera. Cada tentativa começa novamente do MP4 original; temporários incompletos são descartados. A ferramenta Otimizar vídeo no Cofre continua disponível para reprocessar manualmente qualquer arquivo.")

        val playback = PlaybackSettings.snapshot(this)
'''
)
replace_once(
    settings,
    '''        listOf(
            hdr, thermal, audioAgc, audioNoise, audioLowCut, vibration, secureScreen,
            lockWhiteBalance, intelligentPlayback, dropLateFrames, prebuffer4k60, autoRecoverStalls,
            openVideosExternally, openPhotosExternally
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, _ ->
                if (building) return@setOnCheckedChangeListener
                if (control === hdr) enforceHdrCompatibility()
''',
    '''        listOf(
            hdr, thermal, audioAgc, audioNoise, audioLowCut, vibration, secureScreen,
            lockWhiteBalance, intelligentPlayback, dropLateFrames, prebuffer4k60, autoRecoverStalls,
            openVideosExternally, openPhotosExternally, autoGapRepairEnabled, autoGapRepairAi
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, checked ->
                if (building) return@setOnCheckedChangeListener
                if (control === autoGapRepairEnabled) {
                    AutoGapRepairSettings.setEnabled(this, checked)
                    if (checked) AutoGapRepairService.resumeIfEnabled(this) else AutoGapRepairService.pauseByUser(this)
                    refreshAutoGapRepairQueueCard()
                    return@setOnCheckedChangeListener
                }
                if (control === autoGapRepairAi) {
                    AutoGapRepairSettings.setAiAssisted(this, checked)
                    return@setOnCheckedChangeListener
                }
                if (control === hdr) enforceHdrCompatibility()
'''
)
replace_once(
    settings,
    '''    private fun onSpinnerChanged(spinner: Spinner) {
        if (building) return
        when (spinner) {
''',
    '''    private fun onSpinnerChanged(spinner: Spinner) {
        if (building) return
        if (::autoGapRepairMode.isInitialized && spinner === autoGapRepairMode) {
            AutoGapRepairSettings.setMode(this, FrameRepairMode.from(selected(spinner)))
            return
        }
        if (::autoGapRepairMaxFrames.isInitialized && spinner === autoGapRepairMaxFrames) {
            AutoGapRepairSettings.setMaxInterpolatedFramesPerGap(
                this,
                selected(spinner).toIntOrNull() ?: 4
            )
            return
        }
        when (spinner) {
'''
)
replace_once(
    settings,
    '''        val highFrameRate = selected(fps).toInt() >= 60
        val highSpeed = selected(fps).toInt() >= 120
''',
    '''        val highSpeed = selected(fps).toInt() >= 120
'''
)
replace_once(
    settings,
    '''        colorProfile.isEnabled = !hdr.isChecked && !highFrameRate
        colorProfile.alpha = if (colorProfile.isEnabled) 1f else 0.45f
    }

    private fun scheduleSave''',
    '''        // 60 FPS pode usar TONEMAP_CONTRAST_CURVE quando a câmera publicar a
        // chave. As opções individuais do spinner já carregam o suporte real.
        colorProfile.isEnabled = !hdr.isChecked && !highSpeed
        colorProfile.alpha = if (colorProfile.isEnabled) 1f else 0.45f
    }

    private fun refreshAutoGapRepairQueueCard() {
        if (::autoGapRepairQueueInfo.isInitialized) {
            autoGapRepairQueueInfo.text = AutoGapRepairQueueStore.summary(this).text()
        }
    }

    private fun scheduleSave'''
)
replace_once(
    settings,
    '''            CaptureSettings.restoreDefaults(this)
            PlaybackSettings.restoreDefaults(this)
''',
    '''            CaptureSettings.restoreDefaults(this)
            PlaybackSettings.restoreDefaults(this)
            AutoGapRepairSettings.setEnabled(this, false)
            AutoGapRepairService.pauseByUser(this)
'''
)

# ---------------------------------------------------------------------------
# Application + manifest: retoma jobs pendentes após recriação do processo.
# ---------------------------------------------------------------------------
app = 'app/src/main/java/com/steadyvault/camera/SteadyVaultApplication.kt'
replace_once(
    app,
    'import com.steadyvault.camera.core.state.CaptureStateStore\n',
    'import com.steadyvault.camera.core.state.CaptureStateStore\nimport com.steadyvault.camera.processing.auto.AutoGapRepairService\n'
)
replace_once(
    app,
    '''        CaptureStateStore.reconcileInterruptedRecording(this)
        VaultStartupCoordinator.runAsync(this)
''',
    '''        CaptureStateStore.reconcileInterruptedRecording(this)
        AutoGapRepairService.resumeIfEnabled(this)
        VaultStartupCoordinator.runAsync(this)
'''
)

manifest = 'app/src/main/AndroidManifest.xml'
replace_once(
    manifest,
    '''        <service
            android:name=".processing.service.VideoOptimizationService"
            android:exported="false"
            android:foregroundServiceType="mediaProcessing" />
''',
    '''        <service
            android:name=".processing.service.VideoOptimizationService"
            android:exported="false"
            android:foregroundServiceType="mediaProcessing" />
        <service
            android:name=".processing.auto.AutoGapRepairService"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="mediaProcessing" />
'''
)

# ---------------------------------------------------------------------------
# Version bump.
# ---------------------------------------------------------------------------
build = 'app/build.gradle.kts'
p = Path(build)
text = p.read_text(encoding='utf-8')
import re
new_text, count = re.subn(r'versionCode = \d+', 'versionCode = 1000144', text, count=1)
if count != 1:
    raise SystemExit('versionCode não encontrado')
p.write_text(new_text, encoding='utf-8')

print('ROBUST_CAPTURE_PROCESSING_PATCH_OK')
