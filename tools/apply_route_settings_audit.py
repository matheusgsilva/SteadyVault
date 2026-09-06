from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: esperado 1 trecho, encontrado {count}')
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# RecordingServiceRouter: uma fonte da verdade para perfil efetivo e cancelamento
# de otimizacao manual antes de qualquer rota iniciar a camera.
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/steadyvault/camera/capture/service/RecordingServiceRouter.kt'
text = path.read_text()
text = replace_once(
    text,
    'import com.steadyvault.camera.core.camera.CameraLensCatalog\n',
    'import com.steadyvault.camera.core.camera.CameraLensCatalog\n'
    'import com.steadyvault.camera.core.state.OptimizationStateStore\n'
    'import com.steadyvault.camera.processing.service.VideoOptimizationService\n',
    'router imports'
)
text = replace_once(
    text,
    'object RecordingServiceRouter {\n',
    '''object RecordingServiceRouter {\n    /** Perfil real que qualquer origem de gravacao deve usar. */\n    fun effectiveSettings(context: Context, requestedFps: Int): CaptureSettings.Snapshot {\n        val base = CaptureSettings.snapshot(context)\n        val safeFps = requestedFps.takeIf { it in CaptureSettings.supportedFpsValues } ?: base.fps\n        val targetResolution = CaptureSettings.resolutionForFps(context, safeFps)\n        return base.copy(fps = safeFps, resolution = targetResolution)\n    }\n\n    private fun cancelManualOptimizationIfRunning(context: Context) {\n        if (OptimizationStateStore.snapshot(context).running) {\n            VideoOptimizationService.cancel(context)\n        }\n    }\n\n''',
    'router object'
)
text = replace_once(
    text,
    '''    fun start(context: Context, targetFps: Int, fromPreview: Boolean = false, preferredCameraId: String? = null, headless: Boolean = false) {\n        context.startForegroundService(startIntent(context, targetFps, fromPreview, preferredCameraId, headless))\n    }\n''',
    '''    fun start(context: Context, targetFps: Int, fromPreview: Boolean = false, preferredCameraId: String? = null, headless: Boolean = false) {\n        cancelManualOptimizationIfRunning(context)\n        val effective = effectiveSettings(context, targetFps)\n        context.startForegroundService(startIntent(context, effective.fps, fromPreview, preferredCameraId, headless))\n    }\n''',
    'router start'
)
text = replace_once(
    text,
    '''    fun startHeadless(context: Context, targetFps: Int, preferredCameraId: String? = null) {\n        val settings = CaptureSettings.snapshot(context)\n        val exactCameraId = CameraLensCatalog.resolveRecordingCameraId(\n            context,\n            preferredCameraId ?: settings.selectedCameraId,\n            settings.resolution\n        )\n        context.startForegroundService(\n            startIntent(\n                context = context,\n                targetFps = targetFps,\n                fromPreview = false,\n                preferredCameraId = exactCameraId,\n                headless = true\n            )\n        )\n    }\n''',
    '''    fun startHeadless(context: Context, targetFps: Int, preferredCameraId: String? = null) {\n        cancelManualOptimizationIfRunning(context)\n        val settings = effectiveSettings(context, targetFps)\n        val exactCameraId = CameraLensCatalog.resolveRecordingCameraId(\n            context,\n            preferredCameraId ?: settings.selectedCameraId,\n            settings.resolution\n        )\n        context.startForegroundService(\n            startIntent(\n                context = context,\n                targetFps = settings.fps,\n                fromPreview = false,\n                preferredCameraId = exactCameraId,\n                headless = true\n            )\n        )\n    }\n''',
    'router headless'
)
path.write_text(text)

# -----------------------------------------------------------------------------
# Widget sem tela preta: usa exatamente o mesmo perfil por FPS do roteador.
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/steadyvault/camera/widgets/WidgetStartReceiver.kt'
text = path.read_text()
text = text.replace('import com.steadyvault.camera.core.state.OptimizationStateStore\n', '')
text = text.replace('import com.steadyvault.camera.processing.service.VideoOptimizationService\n', '')
text = replace_once(
    text,
    '''        val settings = CaptureSettings.snapshot(context)\n        val effectiveSettings = settings.copy(fps = fps)\n        if (OptimizationStateStore.snapshot(context).running) {\n            VideoOptimizationService.cancel(context)\n            CaptureStateStore.update(context, "Cancelando otimização para gravar sem dividir recursos…")\n        }\n        val spaceCheck = RecordingStorageGuard.checkProfile(context, effectiveSettings)\n''',
    '''        val effectiveSettings = RecordingServiceRouter.effectiveSettings(context, fps)\n        val spaceCheck = RecordingStorageGuard.checkProfile(context, effectiveSettings)\n''',
    'widget effective profile'
)
text = replace_once(
    text,
    '        val preparing = "Preparando gravação dedicada • ${CaptureSettings.resolutionLabel(settings.resolution)} • $fps FPS…"\n',
    '        val preparing = "Preparando gravação • ${CaptureSettings.resolutionLabel(effectiveSettings.resolution)} • ${effectiveSettings.fps} FPS…"\n',
    'widget status'
)
text = replace_once(
    text,
    '            RecordingServiceRouter.startHeadless(context, fps)\n',
    '            RecordingServiceRouter.startHeadless(context, effectiveSettings.fps)\n',
    'widget start'
)
path.write_text(text)

# -----------------------------------------------------------------------------
# Widget com tela preta: mesma resolucao/FPS e mesma politica do roteador.
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/steadyvault/camera/ui/capture/DiscreetRecordingActivity.kt'
text = path.read_text()
old = '''            val started = runCatching {\n                val targetFps =\n                    CaptureModeStore\n                        .getTargetFps(this)\n\n                RecordingServiceRouter.startHeadless(this, targetFps)\n                true\n            }.getOrElse {\n                showFailure("Não foi possível iniciar a gravação", it)\n                false\n            }\n\n            if (!started) {\n                finish()\n                return\n            }\n\n            val settings = CaptureSettings.snapshot(this)\n            val preparing = "Preparando gravação dedicada • " +\n                "${CaptureSettings.resolutionLabel(settings.resolution)} ${settings.fps} FPS • " +\n                "${if (settings.hdrHlg10) "HLG10" else "SDR"}…"\n'''
new = '''            val targetFps = CaptureModeStore.getTargetFps(this)\n            val effectiveSettings = RecordingServiceRouter.effectiveSettings(this, targetFps)\n            val started = runCatching {\n                RecordingServiceRouter.startHeadless(this, effectiveSettings.fps)\n                true\n            }.getOrElse {\n                showFailure("Não foi possível iniciar a gravação", it)\n                false\n            }\n\n            if (!started) {\n                finish()\n                return\n            }\n\n            val preparing = "Preparando gravação • " +\n                "${CaptureSettings.resolutionLabel(effectiveSettings.resolution)} ${effectiveSettings.fps} FPS • " +\n                "${if (effectiveSettings.hdrHlg10) "HLG10" else "SDR"}…"\n'''
text = replace_once(text, old, new, 'discreet route')
path.write_text(text)

# -----------------------------------------------------------------------------
# SettingsActivity: remove controles sem efeito no pipeline atual e simplifica
# textos. Campos antigos continuam no Snapshot para compatibilidade de perfil,
# mas deixam de ser apresentados como opcoes funcionais.
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt'
text = path.read_text()
text = text.replace('import android.media.audiofx.AutomaticGainControl\n', '')
text = text.replace('import android.media.audiofx.NoiseSuppressor\n', '')
for decl in [
    '    private lateinit var iframe: Spinner\n',
    '    private lateinit var audioGain: Spinner\n',
    '    private lateinit var audioAgc: Switch\n',
    '    private lateinit var audioNoise: Switch\n',
    '    private lateinit var audioLowCut: Switch\n',
    '    private lateinit var previewMode: Spinner\n',
]:
    if decl not in text:
        raise SystemExit(f'declaracao ausente: {decl.strip()}')
    text = text.replace(decl, '', 1)

text = text.replace('"Formato de compressão do vídeo (codec)"', '"Formato do vídeo"')
text = text.replace('"Taxa de dados do vídeo (bitrate)"', '"Qualidade do vídeo (bitrate)"')
text = text.replace('"Processamento de nitidez e contornos"', '"Nitidez da imagem"')
text = text.replace('"Correção de cintilação da iluminação"', '"Reduzir faixas e piscadas da iluminação"')
text = text.replace('"Balanço de branco (temperatura de cor)"', '"Cor da iluminação (balanço de branco)"')
text = text.replace('"Correção de dominante amarela"', '"Reduzir tom amarelado"')
text = text.replace('"Reparar gaps automaticamente após gravar"', '"Corrigir falhas de fluidez após gravar"')
text = text.replace('"Método automático para completar lacunas"', '"Como preencher os quadros que faltaram"')
text = text.replace('"Máximo de quadros reconstruídos por gap"', '"Máximo de quadros criados por falha"')
text = text.replace('"Buffer do player em modo de compatibilidade (ms)"', '"Tempo de buffer do player (ms)"')

old_iframe = '''        iframe = addSpinner(\n            "Intervalo entre quadros-chave (I-frame)",\n            listOf(\n                option("1", "1 segundo", "Busca e edição mais rápidas, com leve aumento do tamanho."),\n                option("2", "2 segundos", "Equilíbrio recomendado para gravação normal."),\n                option("5", "5 segundos", "Compressão um pouco melhor."),\n                option("10", "10 segundos", "Menos quadros-chave; não recomendado para arquivos frágeis.")\n            ),\n            snapshot.iFrameIntervalSeconds.toString()\n        )\n        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder: uma única saída de vídeo durante a captura, sem interpolação ou callbacks por quadro. HEVC/H.264 e bitrate-alvo são configurados quando suportados pelo hardware.")\n'''
new_iframe = '''        addInfo("A gravação usa Camera2 diretamente no MediaRecorder. Resolução, FPS, formato e bitrate são enviados ao gravador; o intervalo de quadros-chave fica sob controle do encoder do aparelho porque o MediaRecorder não oferece um ajuste confiável para isso.")\n'''
text = replace_once(text, old_iframe, new_iframe, 'remove iframe fake option')

old_preview = '''        previewMode = addSpinner(\n            "Visualização da câmera antes de capturar",\n            listOf(\n                option(\n                    CaptureSettings.PREVIEW_OFF,\n                    "Somente pelo botão de conferência",\n                    "O preview abre por cima da tela apenas quando solicitado e é fechado antes de foto ou gravação."\n                )\n            ),\n            CaptureSettings.PREVIEW_OFF\n        )\n'''
new_preview = '''        addInfo("A visualização da câmera abre somente quando você toca no botão de preview. Widget e gravação discreta não criam preview escondido nem uma segunda sessão da câmera.")\n'''
text = replace_once(text, old_preview, new_preview, 'remove preview one-option spinner')

# Explica e esconde processamento que o caminho de 60+ deliberadamente fixa para
# proteger a cadencia validada.
needle = '''        edge = addSpinner(\n            "Nitidez da imagem",\n            processingOptions(noise = false, currentValue = snapshot.edgeMode),\n            snapshot.edgeMode\n        )\n'''
replacement = needle + '''        if (initialFps >= CaptureModeStore.FPS_60) {\n            noiseReduction.visibility = View.GONE\n            edge.visibility = View.GONE\n            addInfo("Em 60, 120 e 240 FPS o SteadyVault usa redução de ruído mínima e nitidez desligada para priorizar a cadência. Esses dois controles aparecem em 30 FPS, onde a escolha realmente é aplicada.")\n        }\n'''
text = replace_once(text, needle, replacement, 'cadence processing explanation')

old_channels = '''        audioChannels = addSpinner(\n            "Canais de áudio gravados",\n            listOf(\n                option(CaptureSettings.CHANNELS_AUTO, "Automático", "Tenta estéreo e usa mono se necessário."),\n                option(CaptureSettings.CHANNELS_MONO, "Mono", "Menor tamanho e maior compatibilidade."),\n                option(CaptureSettings.CHANNELS_STEREO, "Estéreo", "Preserva separação entre canais quando o aparelho oferece.")\n            ),\n            snapshot.audioChannels\n        )\n        audioGain = addSpinner(\n            "Limite de ganho do microfone (dB)",\n            audioGainOptions(snapshot.audioGainDb),\n            snapshot.audioGainDb.toString()\n        )\n        audioAgc = addSwitch("Controle automático de ganho do microfone (AGC)", "O Android ajusta o nível do áudio automaticamente; ajuda em fontes baixas, mas pode variar o volume e causar efeito de bombeamento.", snapshot.audioAgc)\n        audioNoise = addSwitch("Supressão de ruído do microfone", "Usa o processador de áudio do aparelho para reduzir ruído contínuo de fundo quando o hardware oferece esse recurso.", snapshot.audioNoiseSuppressor)\n        audioLowCut = addSwitch("Filtro de graves muito baixos e vento", "Atenua frequências abaixo de aproximadamente 75 Hz para reduzir vibração, manuseio e parte do ruído de vento.", snapshot.audioLowCut)\n        if (!runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)) {\n            audioAgc.isChecked = false\n            audioAgc.visibility = View.GONE\n        }\n        if (!runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {\n            audioNoise.isChecked = false\n            audioNoise.visibility = View.GONE\n        }\n'''
new_channels = '''        audioChannels = addSpinner(\n            "Canais de áudio gravados",\n            listOf(\n                option(CaptureSettings.CHANNELS_STEREO, "Estéreo", "Recomendado. Grava dois canais quando o MediaRecorder do aparelho aceita esse formato."),\n                option(CaptureSettings.CHANNELS_MONO, "Mono", "Usa um canal e oferece maior compatibilidade em aparelhos ou modos que recusam estéreo.")\n            ),\n            if (snapshot.audioChannels == CaptureSettings.CHANNELS_MONO) CaptureSettings.CHANNELS_MONO else CaptureSettings.CHANNELS_STEREO\n        )\n        addInfo("O áudio é gravado diretamente pelo MediaRecorder. Por isso são mostrados apenas sample rate, bitrate e número de canais, que o gravador realmente recebe. Controles antigos de ganho, AGC, supressão e filtro de graves foram removidos porque não tinham efeito confiável neste pipeline.")\n'''
text = replace_once(text, old_channels, new_channels, 'remove fake audio controls')

text = replace_once(
    text,
    '''        listOf(\n            hdr, thermal, audioAgc, audioNoise, audioLowCut, vibration, secureScreen,\n            lockWhiteBalance, intelligentPlayback, dropLateFrames, prebuffer4k60, autoRecoverStalls,\n''',
    '''        listOf(\n            hdr, thermal, vibration, secureScreen,\n            lockWhiteBalance, intelligentPlayback, dropLateFrames, prebuffer4k60, autoRecoverStalls,\n''',
    'autosave removed controls'
)

text = replace_once(
    text,
    '''        bitrateMbps = selected(bitrate).toIntOrNull()?.coerceIn(4, 240) ?: base.bitrateMbps,\n        iFrameIntervalSeconds = selected(iframe).toIntOrNull()?.coerceIn(1, 10) ?: base.iFrameIntervalSeconds,\n        hdrHlg10 = hdr.isChecked,\n''',
    '''        bitrateMbps = selected(bitrate).toIntOrNull()?.coerceIn(4, 240) ?: base.bitrateMbps,\n        hdrHlg10 = hdr.isChecked,\n''',
    'snapshot iframe'
)
text = replace_once(
    text,
    '''        focusMode = selected(focus),\n        noiseReduction = selected(noiseReduction),\n        edgeMode = selected(edge),\n''',
    '''        focusMode = selected(focus),\n        noiseReduction = if (fpsValue >= CaptureModeStore.FPS_60) base.noiseReduction else selected(noiseReduction),\n        edgeMode = if (fpsValue >= CaptureModeStore.FPS_60) base.edgeMode else selected(edge),\n''',
    'snapshot 60 processing'
)
text = replace_once(
    text,
    '''        audioChannels = selected(audioChannels),\n        audioGainDb = selected(audioGain).toIntOrNull()?.coerceIn(0, 30) ?: 0,\n        audioAgc = audioAgc.isChecked,\n        audioNoiseSuppressor = audioNoise.isChecked,\n        audioLowCut = audioLowCut.isChecked,\n        vibrateStartStop = vibration.isChecked,\n''',
    '''        audioChannels = selected(audioChannels).takeIf { it in setOf(CaptureSettings.CHANNELS_MONO, CaptureSettings.CHANNELS_STEREO) }\n            ?: CaptureSettings.CHANNELS_STEREO,\n        vibrateStartStop = vibration.isChecked,\n''',
    'snapshot audio legacy controls'
)

# Textos do reparo menos tecnicos, sem esconder o que cada opcao faz.
text = text.replace(
    '"Analisa os timestamps do MP4 original em segundo plano. Quando há lacunas, cria uma NOVA cópia reparada; o original nunca é substituído. Se outra gravação começar, o processamento é interrompido e volta para a fila."',
    '"Depois que o vídeo é salvo, procura falhas de tempo entre os quadros e cria outra cópia corrigida. O original fica intacto. Se você começar outra gravação, o reparo para e volta para a fila."'
)
text = text.replace(
    '"Calcula um campo de movimento entre os quadros reais, desloca cada lado até a posição intermediária e reconstrói somente as lacunas. Se não houver confiança suficiente, usa fallbacks seguros."',
    '"Analisa para onde a imagem se moveu e cria o quadro intermediário na posição esperada. Se o movimento não puder ser estimado com segurança, usa um método mais simples automaticamente."'
)
text = text.replace(
    '"Mistura os dois quadros reais vizinhos sem estimar deslocamento. Serve como fallback quando a interpolação por movimento não for aceita."',
    '"Mistura os dois quadros vizinhos. É mais simples, mas pode deixar sombra dupla em objetos que se moveram rápido."'
)
text = text.replace(
    '"Preenche posições CFR usando um quadro vizinho real; não inventa movimento, mas pode deixar um instante repetido."',
    '"Repete o quadro real mais próximo. É seguro, mas um movimento rápido pode continuar parecendo um pequeno salto."'
)
text = text.replace(
    '"Não cria novos pixels. Regulariza a timeline e é o fallback seguro para HDR ou encoder incompatível."',
    '"Ajusta apenas o tempo dos quadros, sem criar imagem nova. É o modo mais seguro para HDR ou quando o encoder não aceita os outros métodos."'
)

# Nao deixe funcoes auxiliares mortas exclusivas dos controles removidos.
start = text.find('    private fun audioGainOptions(')
if start >= 0:
    # a funcao termina antes da proxima funcao privada conhecida
    end = text.find('    private fun playbackCacheOptions(', start)
    if end < 0:
        raise SystemExit('fim de audioGainOptions nao encontrado')
    text = text[:start] + text[end:]

# As referencias abaixo nao podem mais existir na tela.
for dead in ['selected(iframe)', 'audioGain.', 'audioAgc.', 'audioNoise.', 'audioLowCut.', 'previewMode = addSpinner']:
    if dead in text:
        raise SystemExit(f'controle legado ainda presente: {dead}')

path.write_text(text)

# -----------------------------------------------------------------------------
# Versao
# -----------------------------------------------------------------------------
path = ROOT / 'app/build.gradle.kts'
text = path.read_text()
text = replace_once(text, 'versionCode = 1000148', 'versionCode = 1000149', 'versionCode')
path.write_text(text)

# -----------------------------------------------------------------------------
# Auditoria estatica final: as rotas devem convergir e opcoes removidas nao podem
# voltar a aparecer sem implementacao real.
# -----------------------------------------------------------------------------
router = (ROOT / 'app/src/main/java/com/steadyvault/camera/capture/service/RecordingServiceRouter.kt').read_text()
widget = (ROOT / 'app/src/main/java/com/steadyvault/camera/widgets/WidgetStartReceiver.kt').read_text()
discreet = (ROOT / 'app/src/main/java/com/steadyvault/camera/ui/capture/DiscreetRecordingActivity.kt').read_text()
capture = (ROOT / 'app/src/main/java/com/steadyvault/camera/ui/capture/CaptureActivity.kt').read_text()
service = (ROOT / 'app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt').read_text()
backend = (ROOT / 'app/src/main/java/com/steadyvault/camera/capture/recorder/DirectMediaRecorderBackend.kt').read_text()
settings = (ROOT / 'app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt').read_text()

checks = {
    'router usa resolucao por FPS': 'CaptureSettings.resolutionForFps(context, safeFps)' in router,
    'widget usa perfil efetivo': 'RecordingServiceRouter.effectiveSettings(context, fps)' in widget,
    'widget preto usa perfil efetivo': 'RecordingServiceRouter.effectiveSettings(this, targetFps)' in discreet,
    'tela usa roteador': 'RecordingServiceRouter.start(' in capture and 'RecordingServiceRouter.startHeadless(' in capture,
    'servico pausa reparo automatico': 'AutoGapRepairService.pauseForCapture(this)' in service,
    'servico retoma reparo automatico': 'AutoGapRepairService.resumeAfterCapture(this)' in service,
    'bitrate chega ao MediaRecorder': 'setVideoEncodingBitRate(videoBitrate)' in backend,
    'fps chega ao MediaRecorder': 'setVideoFrameRate(targetFps)' in backend,
    'audio real chega ao MediaRecorder': 'setAudioSamplingRate(audioSampleRate)' in backend and 'setAudioChannels(audioChannels.coerceIn(1, 2))' in backend,
    'iframe ficticio removido da UI': 'Intervalo entre quadros-chave (I-frame)' not in settings,
    'ganho ficticio removido da UI': 'Limite de ganho do microfone' not in settings,
    'AGC ficticio removido da UI': 'Controle automático de ganho do microfone' not in settings,
    'preview de uma opcao removido': 'Visualização da câmera antes de capturar' not in settings,
    'explicacao 60+ presente': 'Em 60, 120 e 240 FPS' in settings,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('AUDIT FAIL: ' + '; '.join(failed))
print('AUDIT OK')
for name in checks:
    print(' -', name)
