import com.steadyvault.camera.capture.timing.*
import com.steadyvault.camera.capture.recorder.StartupVideoGate
import com.steadyvault.camera.core.camera.OisSupportPolicy
import com.steadyvault.camera.core.capability.HardwareSupportPolicy
import com.steadyvault.camera.core.validation.UiBehaviorRules
import com.steadyvault.camera.photo.quality.PhotoQualityPolicy
import com.steadyvault.camera.ui.gesture.*
import com.steadyvault.camera.ui.security.PinLayoutRules
import com.steadyvault.camera.ui.vault.VaultGridRules
import com.steadyvault.camera.ui.vault.VaultImportSession
import com.steadyvault.camera.ui.vault.VaultImportSummary
import com.steadyvault.camera.storage.vault.VaultAreaId

private fun expect(value: Boolean, message: String) { if (!value) error(message) }

fun main() {
    val startupGate = StartupVideoGate(
        maxFramesBeforeFallback = 3,
        maxWaitBeforeFallbackUs = 250_000L,
        maxBufferedBytes = 16L * 1024L * 1024L
    )
    expect(
        startupGate.onSample(
            isKeyFrame = true,
            clearlyBeforeCommit = true,
            sourcePtsUs = 900_000L,
            bufferedBytes = 2_366L,
            hasBufferedKeyFrame = true
        ).action == StartupVideoGate.Action.HOLD,
        "IDR anterior ao commit deve permanecer fora do MP4"
    )
    expect(
        startupGate.onSample(
            isKeyFrame = true,
            clearlyBeforeCommit = false,
            sourcePtsUs = 916_667L,
            bufferedBytes = 2_366L,
            hasBufferedKeyFrame = true
        ).action == StartupVideoGate.Action.START_WITH_CURRENT_KEY_FRAME,
        "primeiro IDR pós-commit deve abrir o MP4 sem descarte fixo"
    )

    expect(VaultAreaId.ALL == listOf("primary", "secondary", "tertiary"), "IDs canônicos dos cofres")
    expect(VaultAreaId.isValid("primary") && VaultAreaId.isValid("secondary") && VaultAreaId.isValid("tertiary"), "validação dos IDs dos cofres")
    expect(!VaultAreaId.isValid("main") && !VaultAreaId.isValid("decoy") && !VaultAreaId.isValid("real"), "IDs legados dos cofres rejeitados")
    mapOf(
        30 to longArrayOf(0L, 33_332L, 66_670L, 100_001L),
        60 to longArrayOf(0L, 16_540L, 33_470L, 49_930L),
        120 to longArrayOf(0L, 8_100L, 16_900L, 24_700L),
        240 to longArrayOf(0L, 4_166L, 8_334L, 12_501L)
    ).forEach { (fps, rawPts) ->
        val normalizer = VideoTimestampNormalizer(fps)
        expect(
            rawPts.map(normalizer::normalize) == rawPts.toList(),
            "PTS real deve permanecer intacto em $fps FPS"
        )
    }

    val subNominal60 = VideoTimestampNormalizer(60)
    val rawAtAbout57Fps = longArrayOf(0L, 17_544L, 35_088L, 52_632L, 70_175L, 87_719L)
    expect(
        rawAtAbout57Fps.map(subNominal60::normalize) == rawAtAbout57Fps.toList(),
        "fonte subnominal em modo 60 FPS não deve sofrer snap periódico"
    )

    val brokenClock = VideoTimestampNormalizer(120)
    val repaired = longArrayOf(10_000L, 20_000L, 20_000L, 19_500L, 35_000L)
        .map(brokenClock::normalize)
    expect(
        repaired == listOf(10_000L, 20_000L, 20_001L, 20_002L, 35_000L),
        "PTS duplicado ou regressivo deve avançar somente um microssegundo"
    )

    brokenClock.reset()
    expect(brokenClock.normalize(5_000L) == 5_000L, "reset deve iniciar uma linha temporal independente")



    expect(RecordingStopPolicy.tailDrainMs(30) == 100L, "cauda de parada em 30 FPS")
    expect(RecordingStopPolicy.tailDrainMs(60) == 50L, "cauda de parada em 60 FPS")
    expect(RecordingStopPolicy.tailDrainMs(120) == 25L, "cauda de parada em 120 FPS")
    expect(RecordingStopPolicy.tailDrainMs(240) == 20L, "cauda mínima em 240 FPS")
    val exact4k60 = StrictCaptureModePolicy.Dimensions(3840, 2160)
    val order = StrictCaptureModePolicy.requestOrder(exact4k60, 120)
    expect(order == listOf(StrictCaptureModePolicy.Request(exact4k60, 120)), "modo manual não cria resolução alternativa")
    val exactOrder = StrictCaptureModePolicy.requestOrder(exact4k60, 60)
    expect(exactOrder == listOf(StrictCaptureModePolicy.Request(exact4k60, 60)), "4K60 explícito não pode reduzir resolução ou FPS")
    expect(StrictCaptureModePolicy.matches(exact4k60, 60, exact4k60, 60), "modo exato deve ser aceito")
    expect(!StrictCaptureModePolicy.matches(exact4k60, 60, StrictCaptureModePolicy.Dimensions(1920, 1080), 60), "4K explícito não pode virar 1080p")
    expect(!StrictCaptureModePolicy.matches(exact4k60, 60, exact4k60, 30), "60 FPS explícito não pode virar 30 FPS")
    expect(StrictCaptureModePolicy.requiresExactFpsRange(60), "60 FPS deve exigir faixa AE fixa")
    expect(StrictCaptureModePolicy.acceptsFpsRange(60, 60, 60), "60 FPS deve aceitar somente [60,60]")
    expect(!StrictCaptureModePolicy.acceptsFpsRange(60, 30, 60), "60 FPS não pode aceitar [30,60]")
    expect(StrictCaptureModePolicy.acceptsFpsRange(30, 15, 30), "30 FPS preserva fallback abrangente")
    expect(StrictCaptureModePolicy.matches(exact4k60, 30, exact4k60, 30), "fallback de 30 FPS preserva a resolução")

    val logicalOis = OisSupportPolicy.resolve(
        logicalModes = intArrayOf(0, 1),
        physicalModes = emptyList(),
        logicalRequestAvailable = true,
        physicalOverrideAvailable = false,
        onMode = 1
    )
    expect(logicalOis.source == OisSupportPolicy.Source.LOGICAL_METADATA, "OIS lógico")
    val physicalOis = OisSupportPolicy.resolve(
        logicalModes = intArrayOf(0),
        physicalModes = listOf(intArrayOf(0, 1)),
        logicalRequestAvailable = true,
        physicalOverrideAvailable = true,
        onMode = 1
    )
    expect(physicalOis.source == OisSupportPolicy.Source.PHYSICAL_METADATA, "OIS da lente física")
    val missingOisMetadata = OisSupportPolicy.resolve(
        logicalModes = null,
        physicalModes = emptyList(),
        logicalRequestAvailable = true,
        physicalOverrideAvailable = false,
        onMode = 1
    )
    expect(
        missingOisMetadata.source == OisSupportPolicy.Source.REQUEST_KEY_FALLBACK,
        "metadado OIS opcional ausente"
    )
    val contradictoryOisMetadata = OisSupportPolicy.resolve(
        logicalModes = intArrayOf(0),
        physicalModes = emptyList(),
        logicalRequestAvailable = true,
        physicalOverrideAvailable = false,
        onMode = 1
    )
    expect(
        contradictoryOisMetadata.source == OisSupportPolicy.Source.REQUEST_KEY_FALLBACK,
        "HAL contraditória deve permitir teste real de OIS"
    )

    expect(
        HardwareSupportPolicy.modeSupport(setOf(0, 1), true, 1, true) ==
            HardwareSupportPolicy.Support.SUPPORTED,
        "modo anunciado deve aparecer"
    )
    expect(
        HardwareSupportPolicy.modeSupport(emptySet(), false, 1, true) ==
            HardwareSupportPolicy.Support.UNVERIFIED,
        "metadado ausente com request deve permanecer não verificado"
    )
    expect(
        HardwareSupportPolicy.modeSupport(setOf(0), true, 1, true) ==
            HardwareSupportPolicy.Support.UNSUPPORTED,
        "negativa explícita deve ocultar a opção"
    )
    expect(
        !HardwareSupportPolicy.shouldExpose(HardwareSupportPolicy.Support.UNSUPPORTED),
        "opção incompatível não deve permanecer na interface"
    )
    expect(
        !HardwareSupportPolicy.isSelectable(HardwareSupportPolicy.Support.UNVERIFIED),
        "opção inconclusiva não deve iniciar uma gravação como teste"
    )
    expect(
        HardwareSupportPolicy.shouldExpose(HardwareSupportPolicy.Support.SUPPORTED) &&
            HardwareSupportPolicy.isSelectable(HardwareSupportPolicy.Support.SUPPORTED),
        "opção confirmada deve ficar visível e selecionável"
    )

    val recovery = PlaybackRecoveryPolicy()
    expect(recovery.advanceAfterFailure() == PlaybackRecoveryPolicy.Stage.MEDIA3_COMPATIBILITY, "Media3 compatibilidade")
    expect(recovery.advanceAfterFailure() == PlaybackRecoveryPolicy.Stage.VLC_HARDWARE, "VLC hardware")
    expect(recovery.advanceAfterFailure() == PlaybackRecoveryPolicy.Stage.VLC_SOFTWARE, "VLC software")
    expect(recovery.advanceAfterFailure() == PlaybackRecoveryPolicy.Stage.EXHAUSTED, "fallback finito")

    expect(PlaybackHealthPolicy.classify(1_000, 1_000, 1f) == PlaybackHealthPolicy.State.HEALTHY, "player saudável")
    expect(PinLayoutRules.keySizePx(1_080, 3f) in 174..246, "PIN dentro dos limites")
    expect(VaultGridRules.afterScale(3, 1.2f) == 2, "grade ampliada")
    val importSession = VaultImportSession(gracePeriodMs = 1_000L)
    importSession.begin(nowElapsedMs = 10_000L)
    expect(importSession.isTrusted(nowElapsedMs = 10_999L), "retorno do seletor deve preservar o cofre")
    expect(importSession.consume(nowElapsedMs = 11_000L), "limite da sessão de importação")
    expect(!importSession.isTrusted(nowElapsedMs = 11_001L), "sessão consumida não pode ser reutilizada")
    importSession.begin(nowElapsedMs = 20_000L)
    expect(!importSession.consume(nowElapsedMs = 21_001L), "sessão de importação expirada")
    val importFailureSamples = List(20) { index -> VaultImportSummary.Failure("video_$index.mp4", "read failed: EIO (I/O error)") }
    val importSummary = VaultImportSummary.popupMessage(imported = 1_642, total = 2_941, failures = importFailureSamples, skipped = 0, failureCount = 1_299)
    expect("Falhas: 1299." in importSummary, "resumo deve mostrar a quantidade real de falhas, não o limite de amostras")
    expect("+1289 falha(s)" in importSummary, "resumo deve indicar falhas adicionais preservadas nos diagnósticos")
    expect("Falharam 50" !in importSummary, "resumo não pode mascarar falhas pelo limite visual")
    expect(UiBehaviorRules.isRecordingBusy("Salvando original no cofre…"), "publicação do original deve continuar ocupada")
    expect(UiBehaviorRules.isRecordingFinalizing("Salvando original no cofre…"), "publicação do original deve ser finalização")
    expect(UiBehaviorRules.sanitizedPlaybackSpeed(1.48f) == 1.5f, "velocidade sanitizada")
    expect(ScrubFramePolicy.snapPositionMs(60, 10_000, 30f) == 67, "snap do quadro a 30 FPS")
    expect(
        ScrubSeekPolicy.chooseCommitMode(1_000, 20_000, resumePlayback = true) == ScrubSeekPolicy.Mode.FAST_SYNC,
        "salto distante deve retomar pelo quadro sincronizado"
    )
    expect(
        ScrubSeekPolicy.chooseCommitMode(1_000, 20_000, resumePlayback = false) == ScrubSeekPolicy.Mode.EXACT,
        "vídeo pausado e corte devem preservar o quadro exato"
    )
    val scrubQueue = LatestScrubTargetQueue()
    expect(scrubQueue.offer(1_000), "primeiro destino agenda o frame da tela")
    expect(!scrubQueue.offer(1_033), "movimento contínuo não duplica agendamento")
    expect(scrubQueue.consume() == 1_033L, "somente o destino mais recente deve ser renderizado")

    val selected = PhotoQualityPolicy.selectHighestResolution(
        listOf(
            PhotoQualityPolicy.Dimensions(4_000, 3_000),
            PhotoQualityPolicy.Dimensions(7_680, 4_320),
            PhotoQualityPolicy.Dimensions(1_920, 1_080)
        ), 20_000_000L
    )
    expect(selected == PhotoQualityPolicy.Dimensions(4_000, 3_000), "seleção de foto 4:3")
    val maximumPhoto = PhotoQualityPolicy.selectHighestResolution(
        listOf(
            PhotoQualityPolicy.Dimensions(4_080, 3_060),
            PhotoQualityPolicy.Dimensions(8_160, 6_120),
            PhotoQualityPolicy.Dimensions(16_320, 12_240)
        ), 220_000_000L
    )
    expect(maximumPhoto == PhotoQualityPolicy.Dimensions(16_320, 12_240), "foto deve preservar a maior resolução disponível")
    println("PURE_LOGIC_TESTS_OK")
}
