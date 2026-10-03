import com.steadyvault.camera.capture.timing.*
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
    expect(VaultAreaId.ALL == listOf("primary", "secondary", "tertiary"), "IDs canônicos dos cofres")
    expect(VaultAreaId.isValid("primary") && VaultAreaId.isValid("secondary") && VaultAreaId.isValid("tertiary"), "validação dos IDs dos cofres")
    expect(!VaultAreaId.isValid("main") && !VaultAreaId.isValid("decoy") && !VaultAreaId.isValid("real"), "IDs legados dos cofres rejeitados")
    expect(
        CaptureCadencePolicy.confidence(16_666_666L, 60, 250_000L) == CaptureCadencePolicy.Confidence.CONFIRMED,
        "4K60 confirmado pela duração mínima deve ter prioridade"
    )
    expect(
        CaptureCadencePolicy.confidence(33_333_333L, 60, 250_000L) == CaptureCadencePolicy.Confidence.TOO_SLOW,
        "rota pública de 30 FPS não pode ganhar de uma rota 60 FPS confirmada"
    )
    expect(
        CaptureCadencePolicy.priorityScore(CaptureCadencePolicy.Confidence.CONFIRMED, 60) >
            CaptureCadencePolicy.priorityScore(CaptureCadencePolicy.Confidence.UNKNOWN, 60),
        "cadência confirmada deve vencer heurísticas de câmera"
    )
    expect(
        !CaptureCadencePolicy.measuredCadenceAcceptable(59.68, 60, 0),
        "59,68 FPS reais não podem ser aceitos como 60 FPS profissional"
    )
    expect(
        CaptureCadencePolicy.measuredCadenceAcceptable(59.90, 60, 0),
        "59,90 FPS sem lacunas deve ficar dentro da margem de relógio"
    )
    expect(
        !CaptureCadencePolicy.measuredCadenceAcceptable(60.00, 60, 1),
        "uma lacuna longa no warm-up deve rejeitar a rota mesmo com média 60"
    )

    val fixed60 = SensorCadencePolicy.resolve(
        fps = 60,
        observedExposureNs = 16_000_000L,
        observedSensitivityIso = 100,
        exposureMinNs = 100_000L,
        exposureMaxNs = 30_000_000L,
        sensitivityMinIso = 50,
        sensitivityMaxIso = 3200,
        maxFrameDurationNs = 100_000_000L,
        manualSensorSupported = true
    )
    expect(fixed60 != null && fixed60.frameDurationNs in 16_666_666L..16_666_667L, "60 FPS deve fixar duração de quadro")
    expect(fixed60 != null && fixed60.exposureTimeNs <= 8_333_334L, "60 FPS deve limitar shutter a aproximadamente 1/120")
    expect(fixed60 != null && fixed60.sensitivityIso >= 190, "ISO deve compensar o shutter mais curto")
    expect(
        SensorCadencePolicy.resolve(60, 16_000_000L, 100, 100_000L, 30_000_000L, 50, 3200, 100_000_000L, false) == null,
        "sem MANUAL_SENSOR a captura deve permanecer no AE da HAL"
    )

    val lowLight60 = SensorCadencePolicy.resolve(
        60, 16_000_000L, 2500, 100_000L, 30_000_000L, 50, 3200, 100_000_000L, true
    )
    expect(lowLight60 != null && lowLight60.exposureTimeNs > 8_333_334L, "baixa luz pode alongar shutter sem ultrapassar o período de 60 FPS")
    expect(lowLight60 != null && lowLight60.sensitivityIso <= 3200, "baixa luz não pode ultrapassar ISO máximo")

    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.PREVIEW, 60, false, true, true, true
        ) == RecordingStabilizationPolicy.Mode.PREVIEW,
        "preview stabilization em 60 FPS deve permanecer Preview quando suportada"
    )
    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.EIS, 120, true, false, true, true
        ) == RecordingStabilizationPolicy.Mode.EIS,
        "EIS em 120 FPS deve permanecer EIS quando suportada"
    )
    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.PREVIEW, 60, false, true, true, false
        ) == RecordingStabilizationPolicy.Mode.PREVIEW,
        "sem OIS, Preview em 60 FPS continua Preview quando a HAL suporta"
    )
    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.PREVIEW, 30, false, true, true, true
        ) == RecordingStabilizationPolicy.Mode.PREVIEW,
        "30 FPS pode manter Preview Stabilization"
    )
    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.OIS, 60, false, false, false, false
        ) == RecordingStabilizationPolicy.Mode.OIS,
        "OIS escolhido não pode ser substituído só porque os metadados da câmera lógica são incompletos"
    )
    expect(
        RecordingStabilizationPolicy.resolve(
            RecordingStabilizationPolicy.Mode.EIS, 60, false, false, false, true
        ) == RecordingStabilizationPolicy.Mode.EIS,
        "EIS escolhido não pode virar Off por pré-validação de metadados"
    )

    val exact4k60 = StrictCaptureModePolicy.Dimensions(3840, 2160)
    val order = StrictCaptureModePolicy.requestOrder(exact4k60, 120)
    expect(order == listOf(StrictCaptureModePolicy.Request(exact4k60, 120)), "modo manual não cria resolução alternativa")
    val exactOrder = StrictCaptureModePolicy.requestOrder(exact4k60, 60)
    expect(exactOrder == listOf(StrictCaptureModePolicy.Request(exact4k60, 60)), "4K60 explícito não pode reduzir resolução ou FPS")
    expect(StrictCaptureModePolicy.matches(exact4k60, 60, exact4k60, 60), "modo exato deve ser aceito")
    expect(!StrictCaptureModePolicy.matches(exact4k60, 60, StrictCaptureModePolicy.Dimensions(1920, 1080), 60), "4K explícito não pode virar 1080p")
    expect(!StrictCaptureModePolicy.matches(exact4k60, 60, exact4k60, 30), "60 FPS explícito não pode virar 30 FPS")
    expect(StrictCaptureModePolicy.acceptsFpsRange(30, 30, 30), "30 FPS exige [30,30]")
    expect(StrictCaptureModePolicy.acceptsFpsRange(60, 60, 60), "60 FPS exige [60,60]")
    expect(StrictCaptureModePolicy.acceptsFpsRange(120, 120, 120), "120 FPS exige [120,120]")
    expect(StrictCaptureModePolicy.acceptsFpsRange(240, 240, 240), "240 FPS exige [240,240]")
    expect(!StrictCaptureModePolicy.acceptsFpsRange(60, 30, 60), "60 FPS nunca pode aceitar [30,60]")
    expect(!StrictCaptureModePolicy.acceptsFpsRange(30, 15, 30), "30 FPS nunca pode aceitar [15,30]")
    expect(!StrictCaptureModePolicy.acceptsFpsRange(120, 30, 120), "120 FPS nunca pode aceitar [30,120]")
    expect(!StrictCaptureModePolicy.acceptsFpsRange(240, 60, 240), "240 FPS nunca pode aceitar [60,240]")
    expect(StrictCaptureModePolicy.matches(exact4k60, 30, exact4k60, 30), "30 FPS explícito preserva a resolução e o FPS")

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
    expect(UiBehaviorRules.sanitizedPlaybackSpeed(0.13f) == 0.125f, "velocidade lenta 240 fps")
    expect(ScrubFramePolicy.snapPositionMs(60, 10_000, 30f) == 67, "snap do quadro a 30 FPS")
    expect(
        ScrubSeekPolicy.chooseCommitMode(1_000, 20_000, resumePlayback = true) == ScrubSeekPolicy.Mode.FAST_SYNC,
        "salto distante deve retomar pelo quadro sincronizado"
    )
    expect(
        ScrubSeekPolicy.chooseCommitMode(1_000, 20_000, resumePlayback = false) == ScrubSeekPolicy.Mode.FAST_SYNC,
        "scrub longo pausado deve continuar responsivo"
    )
    expect(
        ScrubSeekPolicy.chooseCommitMode(1_000, 20_000, resumePlayback = false, precisionRequired = true) == ScrubSeekPolicy.Mode.EXACT,
        "corte deve preservar o quadro exato"
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
