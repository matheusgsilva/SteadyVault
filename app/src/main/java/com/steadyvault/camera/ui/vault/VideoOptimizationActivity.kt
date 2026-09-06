package com.steadyvault.camera.ui.vault

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.state.OptimizationStateStore
import com.steadyvault.camera.processing.analysis.OptimizationAdvisor
import com.steadyvault.camera.processing.analysis.OptimizationRecommendation
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.ai.AiVideoEnhancer
import com.steadyvault.camera.processing.model.FilterStrength
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.model.VideoFilterConfig
import com.steadyvault.camera.processing.service.VideoOptimizationService
import com.steadyvault.camera.processing.validation.OptimizationConfigValidator
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.ui.components.ChoiceSpinnerAdapter
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class VideoOptimizationActivity : Activity() {
    private lateinit var sourceInfo: TextView
    private lateinit var recommendationInfo: TextView
    private lateinit var preset: Spinner
    private lateinit var frameRepair: Spinner
    private lateinit var codec: Spinner
    private lateinit var rateMode: Spinner
    private lateinit var resolution: Spinner
    private lateinit var fps: Spinner
    private lateinit var denoise: Spinner
    private lateinit var deblock: Spinner
    private lateinit var sharpen: Spinner
    private lateinit var bitrate: EditText
    private lateinit var brightness: EditText
    private lateinit var contrast: EditText
    private lateinit var saturation: EditText
    private lateinit var temperature: EditText
    private lateinit var tint: EditText
    private lateinit var maxInterpolation: EditText
    private lateinit var aiAssisted: Switch
    private lateinit var keepAudio: Switch
    private lateinit var thermalProtection: Switch
    private lateinit var replaceOriginal: Switch
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var startButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var smartButton: TextView

    private var source: File? = null
    private var secondaryMode = false
    private var tertiaryMode = false
    private var currentAnalysis: VideoAnalysis? = null
    private var currentRecommendation: OptimizationRecommendation? = null
    private var receiverRegistered = false
    private var busy = false
    private var applyingPreset = false
    private var smartAutoTune = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val presetValues = listOf(
        OptimizationPreset.REPAIR_ONLY,
        OptimizationPreset.HIGH_QUALITY,
        OptimizationPreset.BALANCED,
        OptimizationPreset.SMALL_FILE,
        OptimizationPreset.VERY_FAST_1080P,
        OptimizationPreset.FAST_1080P,
        OptimizationPreset.HQ_1080P,
        OptimizationPreset.FAST_720P,
        OptimizationPreset.HQ_720P,
        OptimizationPreset.CREATOR_2160P_4K,
        OptimizationPreset.CREATOR_1080P,
        OptimizationPreset.SOCIAL_720P,
        OptimizationPreset.APPLE_2160P_4K_HEVC,
        OptimizationPreset.APPLE_1080P_SURROUND,
        OptimizationPreset.ANDROID_1080P,
        OptimizationPreset.ANDROID_720P,
        OptimizationPreset.WEB_1080P,
        OptimizationPreset.ARCHIVE_4K,
        OptimizationPreset.SMART,
        OptimizationPreset.CUSTOM
    )
    private val repairValues = listOf(
        FrameRepairMode.NONE,
        FrameRepairMode.SMOOTH_TIMELINE,
        FrameRepairMode.FILL_MISSING_FRAMES,
        FrameRepairMode.ADAPTIVE_BLEND,
        FrameRepairMode.MOTION_COMPENSATED
    )
    private val codecValues = listOf(OutputCodec.SOURCE, OutputCodec.HEVC, OutputCodec.AVC)
    private val rateValues = listOf(
        OptimizationRateMode.AUTO,
        OptimizationRateMode.CONSTANT_QUALITY,
        OptimizationRateMode.VBR,
        OptimizationRateMode.CBR
    )
    private val strengthValues = FilterStrength.entries

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(VideoOptimizationService.EXTRA_STATE) ?: return
            val message = intent.getStringExtra(VideoOptimizationService.EXTRA_MESSAGE).orEmpty()
            val percent = intent.getIntExtra(VideoOptimizationService.EXTRA_PROGRESS, 0)
            progress.progress = percent
            status.text = message
            if (state == VideoOptimizationService.STATE_PROGRESS) {
                setBusy(true)
                startButton.text = "Processando $percent%"
            }
            when (state) {
                VideoOptimizationService.STATE_SUCCESS -> {
                    setBusy(false)
                    startButton.text = "Criar outra versão otimizada"
                    Haptics.success(this@VideoOptimizationActivity)
                    intent.getStringExtra(VideoOptimizationService.EXTRA_OUTPUT_PATH)?.let { path ->
                        val output = File(path)
                        source = output
                        loadSourceInfo(output)
                    }
                    Toast.makeText(this@VideoOptimizationActivity, message, Toast.LENGTH_LONG).show()
                }
                VideoOptimizationService.STATE_ERROR, VideoOptimizationService.STATE_CANCELLED -> {
                    setBusy(false)
                    startButton.text = "Criar vídeo otimizado"
                    if (state == VideoOptimizationService.STATE_ERROR) {
                        Haptics.error(this@VideoOptimizationActivity)
                    } else {
                        Haptics.tap(this@VideoOptimizationActivity)
                    }
                    Toast.makeText(this@VideoOptimizationActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secondaryMode = intent.getBooleanExtra(EXTRA_SECONDARY, false)
        tertiaryMode = intent.getBooleanExtra(EXTRA_TERTIARY, false)
        if (!hasUnlockedSourceVault()) {
            finish()
            return
        }
        if (CaptureSettings.snapshot(this).secureScreen) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_video_optimization)
        SystemBarInsets.applyTopAndBottom(findViewById<View>(R.id.optimizerRoot))
        bindViews()
        bindOptions()
        bindActions()

        source = intent.getStringExtra(EXTRA_PATH)?.let(::File)?.takeIf { isAllowedSourceFile(it) }
        source?.let(::loadSourceInfo) ?: run {
            sourceInfo.text = "Abra esta tela por um vídeo do cofre."
            startButton.isEnabled = false
            smartButton.isEnabled = false
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(VideoOptimizationService.ACTION_STATE)
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        restoreOptimizationState()
    }

    override fun onResume() {
        super.onResume()
        if (!hasUnlockedSourceVault()) {
            finish()
            return
        }
        restoreOptimizationState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun restoreOptimizationState() {
        val file = source ?: return
        val snapshot = OptimizationStateStore.snapshotFor(this, file) ?: return
        progress.progress = snapshot.progress
        status.text = snapshot.message.ifBlank {
            if (snapshot.running) "Otimização em andamento" else "Pronto para otimizar"
        }
        if (snapshot.running) {
            setBusy(true)
            startButton.text = "Processando ${snapshot.progress}%"
            cancelButton.isEnabled = true
        } else {
            setBusy(false)
            startButton.text = if (snapshot.state == OptimizationStateStore.STATE_SUCCESS) {
                "Otimizar novamente"
            } else {
                "Iniciar otimização"
            }
            snapshot.outputPath?.let { path ->
                File(path).takeIf { it.isFile && VaultRepository.isInsideKnownVault(this, it) }?.let {
                    source = it
                }
            }
        }
    }

    private fun hasUnlockedSourceVault(): Boolean = when {
        tertiaryMode -> TertiaryVaultLock.isUnlocked(this)
        secondaryMode -> SecondaryVaultLock.isUnlocked(this)
        else -> PrimaryVaultLock.isUnlocked(this)
    }

    private fun isAllowedSourceFile(file: File): Boolean = file.isFile && when {
        tertiaryMode -> TertiaryVaultRepository.findByPath(this, file.absolutePath)?.video == true
        secondaryMode -> SecondaryVaultRepository.findByPath(this, file.absolutePath)?.video == true
        else -> VaultRepository.isInsideVault(this, file) && VaultRepository.readItem(file, fast = true)?.video == true
    }

    private fun bindViews() {
        sourceInfo = findViewById(R.id.optimizerSourceInfo)
        recommendationInfo = findViewById(R.id.optimizerRecommendation)
        preset = findViewById(R.id.optimizerPreset)
        frameRepair = findViewById(R.id.optimizerFrameRepair)
        codec = findViewById(R.id.optimizerCodec)
        rateMode = findViewById(R.id.optimizerRateMode)
        resolution = findViewById(R.id.optimizerResolution)
        fps = findViewById(R.id.optimizerFps)
        denoise = findViewById(R.id.optimizerDenoise)
        deblock = findViewById(R.id.optimizerDeblock)
        sharpen = findViewById(R.id.optimizerSharpen)
        bitrate = findViewById(R.id.optimizerBitrate)
        brightness = findViewById(R.id.optimizerBrightness)
        contrast = findViewById(R.id.optimizerContrast)
        saturation = findViewById(R.id.optimizerSaturation)
        temperature = findViewById(R.id.optimizerTemperature)
        tint = findViewById(R.id.optimizerTint)
        maxInterpolation = findViewById(R.id.optimizerMaxInterpolation)
        aiAssisted = findViewById(R.id.optimizerAiAssisted)
        keepAudio = findViewById(R.id.optimizerKeepAudio)
        thermalProtection = findViewById(R.id.optimizerThermalProtection)
        replaceOriginal = findViewById(R.id.optimizerReplaceOriginal)
        progress = findViewById(R.id.optimizerProgress)
        status = findViewById(R.id.optimizerStatus)
        startButton = findViewById(R.id.optimizerStartButton)
        cancelButton = findViewById(R.id.optimizerCancelButton)
        smartButton = findViewById(R.id.optimizerSmartButton)
    }

    private fun bindOptions() {
        bind(preset, "Objetivo da otimização", presetValues.map { it.displayName })
        bind(
            frameRepair,
            "Como corrigir falhas de fluidez",
            listOf(
                "Manter exatamente como gravado",
                "Corrigir o tempo dos quadros (recomendado)",
                "Preencher lacunas repetindo o quadro mais próximo",
                "Suavizar lacunas misturando quadros (pode criar rastro)",
                "Interpolar lacunas acompanhando o movimento (recomendado)"
            )
        )
        bind(codec, "Formato do novo arquivo", listOf("Manter o formato original quando possível", "HEVC / H.265: menor arquivo", "AVC / H.264: maior compatibilidade"))
        bind(rateMode, "Prioridade entre qualidade e tamanho", listOf("Automático (recomendado)", "Priorizar qualidade visual", "Equilibrar qualidade e tamanho (VBR)", "Manter fluxo de dados constante (CBR)"))
        bind(resolution, "Tamanho da imagem final", listOf("Manter o tamanho original", "Limitar a 4K UHD", "Limitar a 1080p", "Limitar a 720p"))
        bind(fps, "Fluidez final (FPS)", listOf("Manter o FPS original", "24 FPS", "30 FPS", "60 FPS", "120 FPS"))
        val strengths = listOf("Desligado", "Leve", "Médio", "Forte")
        bind(denoise, "Suavizar granulação / ruído", strengths)
        bind(deblock, "Disfarçar blocos de compressão", strengths)
        bind(sharpen, "Recuperar nitidez aparente", strengths)

        applyingPreset = true
        preset.setSelection(presetValues.indexOf(OptimizationPreset.BALANCED))
        frameRepair.setSelection(repairValues.indexOf(FrameRepairMode.SMOOTH_TIMELINE))
        codec.setSelection(codecValues.indexOf(OutputCodec.HEVC))
        rateMode.setSelection(rateValues.indexOf(OptimizationRateMode.AUTO))
        applyingPreset = false

        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (preset.adapter as? ChoiceSpinnerAdapter)?.selectedPosition = position
                if (!applyingPreset) applyPresetDefaults(presetValues[position.coerceIn(0, presetValues.lastIndex)])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindActions() {
        findViewById<TextView>(R.id.optimizerBackButton).setOnClickListener { finish() }
        smartButton.setOnClickListener {
            currentRecommendation?.let {
                applyRecommendation(it)
                Haptics.success(this)
            } ?: run {
                status.text = "A análise ainda está em andamento"
                Haptics.tap(this)
            }
        }
        findViewById<TextView>(R.id.optimizerAutoBitrateButton).setOnClickListener {
            calculateRecommendedBitrate()
            Haptics.tap(this)
        }
        findViewById<TextView>(R.id.optimizerResetFiltersButton).setOnClickListener {
            resetFilters()
            status.text = "Filtros restaurados para valores neutros"
            Haptics.tap(this)
        }
        startButton.setOnClickListener { if (!busy) startOptimization() }
        cancelButton.setOnClickListener {
            VideoOptimizationService.cancel(this)
            status.text = "Solicitando cancelamento…"
            cancelButton.isEnabled = false
            Haptics.tap(this)
        }
    }

    private fun loadSourceInfo(file: File) {
        currentAnalysis = null
        currentRecommendation = null
        smartButton.isEnabled = false
        sourceInfo.text = "Analisando ${file.name}…"
        recommendationInfo.text = "Lendo timestamps sem carregar o vídeo inteiro na memória…"
        analysisExecutor.execute {
            val result = runCatching {
                val analysis = VideoAnalysis.read(file)
                val aiReport = runCatching { AiVideoEnhancer.analyze(file, analysis) }.getOrNull()
                analysis to OptimizationAdvisor.recommend(analysis, aiReport)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (analysis, recommendation) ->
                    currentAnalysis = analysis
                    currentRecommendation = recommendation
                    smartButton.isEnabled = !analysis.hdrHlg10
                    keepAudio.isChecked = analysis.hasAudio
                    sourceInfo.text = buildString {
                        append(file.name).append('\n')
                        append(analysis.width).append('×').append(analysis.height)
                        append(" • ").append(String.format(Locale.getDefault(), "%.2f", analysis.exactFps)).append(" FPS real")
                        append(" • alvo ").append(analysis.estimatedFps).append(" FPS")
                        append(" • ").append(VaultRepository.formatDuration(analysis.durationUs / 1_000L))
                        append(" • ").append(VaultRepository.formatBytes(file.length()))
                        append(" • ").append(if (analysis.hdrHlg10) "HDR HLG10" else "SDR BT.709").append('\n')
                        append("Cadência ").append(analysis.cadenceScore).append("/100 (").append(analysis.cadenceLabel).append(')')
                        append(" • confiança nominal ").append(analysis.nominalFpsConfidence).append('%')
                        append(" • jitter ").append(analysis.frameJitterPercent).append('%')
                        append(" • ").append(analysis.largeGapCount).append(" gaps")
                        append(" • ~").append(analysis.estimatedMissingFrames).append(" quadros ausentes")
                        append(" • ").append(String.format(Locale.getDefault(), "%.1f", analysis.sourceBitrateMbps)).append(" Mbps")
                    }
                    recommendationInfo.text = if (analysis.hdrHlg10) {
                        "HLG10 detectado. Para não alterar contraste e cores, use Somente reparar + Regularizar timestamps. A recodificação HDR com filtros foi bloqueada nesta versão."
                    } else {
                        recommendation.summary()
                    }
                    if (analysis.hdrHlg10) {
                        applyingPreset = true
                        select(preset, presetValues, OptimizationPreset.REPAIR_ONLY)
                        select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                        select(codec, codecValues, OutputCodec.SOURCE)
                        select(rateMode, rateValues, OptimizationRateMode.AUTO)
                        resolution.setSelection(0)
                        fps.setSelection(0)
                        resetFilters()
                        aiAssisted.isChecked = false
                        applyingPreset = false
                    }
                }.onFailure {
                    sourceInfo.text = file.name
                    recommendationInfo.text = it.message ?: "Não foi possível analisar este vídeo"
                }
            }
        }
    }

    private fun applyPresetDefaults(value: OptimizationPreset) {
        if (value != OptimizationPreset.SMART) smartAutoTune = false
        if (value != OptimizationPreset.SMART && value != OptimizationPreset.CUSTOM) resetFilters()
        if (value != OptimizationPreset.SMART && value != OptimizationPreset.CUSTOM) {
            status.text = value.description
        }
        when (value) {
            OptimizationPreset.REPAIR_ONLY -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.SOURCE)
                select(rateMode, rateValues, OptimizationRateMode.AUTO)
                resolution.setSelection(0)
                fps.setSelection(0)
                bitrate.setText("0")
                resetFilters()
            }
            OptimizationPreset.HIGH_QUALITY -> {
                select(frameRepair, repairValues, FrameRepairMode.ADAPTIVE_BLEND)
                select(codec, codecValues, OutputCodec.HEVC)
                select(rateMode, rateValues, OptimizationRateMode.CONSTANT_QUALITY)
                resolution.setSelection(0)
                fps.setSelection(0)
                bitrate.setText("0")
                sharpen.setSelection(strengthValues.indexOf(FilterStrength.LIGHT))
                deblock.setSelection(strengthValues.indexOf(FilterStrength.LIGHT))
            }
            OptimizationPreset.BALANCED -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.HEVC)
                select(rateMode, rateValues, OptimizationRateMode.AUTO)
                resolution.setSelection(0)
                fps.setSelection(0)
                bitrate.setText("0")
                resetFilters()
            }
            OptimizationPreset.SMALL_FILE -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.HEVC)
                select(rateMode, rateValues, OptimizationRateMode.VBR)
                resolution.setSelection(2)
                fps.setSelection(0)
                bitrate.setText("0")
                deblock.setSelection(strengthValues.indexOf(FilterStrength.LIGHT))
            }
            OptimizationPreset.VERY_FAST_1080P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.VBR,
                resolutionIndex = 2
            )
            OptimizationPreset.FAST_1080P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.VBR,
                resolutionIndex = 2
            )
            OptimizationPreset.HQ_1080P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.CONSTANT_QUALITY,
                resolutionIndex = 2,
                sharpenValue = FilterStrength.LIGHT
            )
            OptimizationPreset.FAST_720P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.VBR,
                resolutionIndex = 3
            )
            OptimizationPreset.HQ_720P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.CONSTANT_QUALITY,
                resolutionIndex = 3,
                sharpenValue = FilterStrength.LIGHT
            )
            OptimizationPreset.CREATOR_2160P_4K -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.CONSTANT_QUALITY,
                resolutionIndex = 1,
                sharpenValue = FilterStrength.LIGHT
            )
            OptimizationPreset.CREATOR_1080P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.CONSTANT_QUALITY,
                resolutionIndex = 2,
                sharpenValue = FilterStrength.LIGHT
            )
            OptimizationPreset.SOCIAL_720P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.VBR,
                resolutionIndex = 3,
                deblockValue = FilterStrength.LIGHT
            )
            OptimizationPreset.APPLE_2160P_4K_HEVC -> applyStandardPreset(
                codecValue = OutputCodec.HEVC,
                rateValue = OptimizationRateMode.CONSTANT_QUALITY,
                resolutionIndex = 1,
                sharpenValue = FilterStrength.LIGHT
            )
            OptimizationPreset.APPLE_1080P_SURROUND -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.AVC)
                select(rateMode, rateValues, OptimizationRateMode.VBR)
                resolution.setSelection(2)
                fps.setSelection(0)
                bitrate.setText("0")
                keepAudio.isChecked = true
                resetFilters()
                status.text = "Apple 1080p aplicado • o áudio AAC multicanal é preservado quando já existir"
            }
            OptimizationPreset.ANDROID_1080P -> applyStandardPreset(
                codecValue = OutputCodec.AVC,
                rateValue = OptimizationRateMode.VBR,
                resolutionIndex = 2
            )
            OptimizationPreset.ANDROID_720P -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.AVC)
                select(rateMode, rateValues, OptimizationRateMode.VBR)
                resolution.setSelection(3)
                fps.setSelection(0)
                bitrate.setText("0")
                keepAudio.isChecked = true
                resetFilters()
            }
            OptimizationPreset.WEB_1080P -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.AVC)
                select(rateMode, rateValues, OptimizationRateMode.VBR)
                resolution.setSelection(2)
                fps.setSelection(0)
                bitrate.setText("0")
                keepAudio.isChecked = true
                resetFilters()
            }
            OptimizationPreset.ARCHIVE_4K -> {
                select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
                select(codec, codecValues, OutputCodec.HEVC)
                select(rateMode, rateValues, OptimizationRateMode.CONSTANT_QUALITY)
                resolution.setSelection(1)
                fps.setSelection(0)
                bitrate.setText("0")
                keepAudio.isChecked = true
                resetFilters()
                sharpen.setSelection(strengthValues.indexOf(FilterStrength.LIGHT))
            }
            OptimizationPreset.SMART -> {
                val recommendation = currentRecommendation
                if (recommendation != null) applyRecommendation(recommendation) else {
                    smartAutoTune = true
                    status.text = "A configuração automática será calculada ao iniciar"
                }
            }
            OptimizationPreset.CUSTOM -> Unit
        }
    }

    private fun applyStandardPreset(
        codecValue: OutputCodec,
        rateValue: OptimizationRateMode,
        resolutionIndex: Int,
        sharpenValue: FilterStrength = FilterStrength.OFF,
        deblockValue: FilterStrength = FilterStrength.OFF
    ) {
        select(frameRepair, repairValues, FrameRepairMode.SMOOTH_TIMELINE)
        select(codec, codecValues, codecValue)
        select(rateMode, rateValues, rateValue)
        resolution.setSelection(resolutionIndex)
        fps.setSelection(0)
        bitrate.setText("0")
        keepAudio.isChecked = true
        resetFilters()
        sharpen.setSelection(strengthValues.indexOf(sharpenValue))
        deblock.setSelection(strengthValues.indexOf(deblockValue))
    }

    private fun applyRecommendation(recommendation: OptimizationRecommendation) {
        applyingPreset = true
        smartAutoTune = false
        select(preset, presetValues, OptimizationPreset.SMART)
        select(frameRepair, repairValues, recommendation.config.frameRepair)
        select(codec, codecValues, recommendation.config.codec)
        select(rateMode, rateValues, recommendation.config.rateMode)
        resolution.setSelection(0)
        fps.setSelection(0)
        bitrate.setText(recommendation.estimatedOutputMbps.toString())
        keepAudio.isChecked = recommendation.config.keepAudio
        thermalProtection.isChecked = recommendation.config.thermalProtection
        aiAssisted.isChecked = recommendation.aiReport != null || recommendation.config.aiAssisted
        denoise.setSelection(strengthValues.indexOf(recommendation.config.filters.denoise))
        deblock.setSelection(strengthValues.indexOf(recommendation.config.filters.deblock))
        sharpen.setSelection(strengthValues.indexOf(recommendation.config.filters.sharpen))
        brightness.setText(recommendation.config.filters.brightness.toString())
        contrast.setText(recommendation.config.filters.contrast.toString())
        saturation.setText(recommendation.config.filters.saturation.toString())
        temperature.setText(recommendation.config.filters.temperature.toString())
        tint.setText(recommendation.config.filters.tint.toString())
        maxInterpolation.setText(recommendation.config.maxInterpolatedFramesPerGap.toString())
        applyingPreset = false
        status.text = "Configuração recomendada aplicada. Revise e crie o vídeo otimizado."
    }

    private fun calculateRecommendedBitrate() {
        val analysis = currentAnalysis ?: run {
            bitrate.setText("0")
            status.text = "Bitrate automático será calculado durante o processamento"
            return
        }
        val dimensions = selectedDimensions(analysis)
        val targetFps = selectedFps().takeIf { it > 0 } ?: analysis.estimatedFps
        val value = OptimizationAdvisor.recommendedBitrateMbps(
            analysis = analysis,
            codec = codecValues[codec.selectedItemPosition.coerceIn(0, codecValues.lastIndex)],
            preset = presetValues[preset.selectedItemPosition.coerceIn(0, presetValues.lastIndex)],
            targetWidth = dimensions.first,
            targetHeight = dimensions.second,
            targetFps = targetFps
        )
        bitrate.setText(value.toString())
        status.text = "Bitrate recomendado: $value Mbps"
    }

    private fun startOptimization() {
        val file = source ?: return
        if (!file.isFile || file.length() <= 0L) {
            status.text = "O vídeo original não está disponível ou está vazio"
            Haptics.error(this)
            return
        }

        val config = buildConfig()
        val analysis = currentAnalysis
        if (analysis == null) {
            status.text = "Aguarde a análise inicial terminar antes de processar"
            Haptics.tap(this)
            return
        }

        val validation = OptimizationConfigValidator.validate(
            config = config,
            sourceWidth = analysis.width,
            sourceHeight = analysis.height,
            sourceFps = analysis.estimatedFps,
            sourceHdrHlg10 = analysis.hdrHlg10
        )
        if (!validation.valid) {
            status.text = validation.error ?: "Configuração incompatível com este vídeo"
            Haptics.error(this)
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            return
        }

        if (validation.warnings.isNotEmpty()) {
            status.text = validation.warnings.joinToString("\n• ", prefix = "Atenção: • ")
        } else {
            status.text = "Preparando uma cópia segura para processamento local"
        }

        setBusy(true)
        startButton.text = "Processando no aparelho…"
        progress.isIndeterminate = false
        progress.progress = 0
        Haptics.tap(this)
        val started = runCatching {
            VideoOptimizationService.start(this, file, config)
        }.getOrElse { throwable ->
            setBusy(false)
            startButton.text = "Criar vídeo otimizado"
            status.text = throwable.message ?: "Não foi possível iniciar a otimização"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            false
        }
        if (!started) {
            setBusy(false)
            startButton.text = "Criar vídeo otimizado"
            if (!status.text.toString().startsWith("Não foi possível")) {
                status.text = "Já existe outra otimização em andamento"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildConfig(): OptimizationConfig {
        val dimensions = when (resolution.selectedItemPosition) {
            1 -> 3840 to 2160
            2 -> 1920 to 1080
            3 -> 1280 to 720
            else -> 0 to 0
        }
        return OptimizationConfig(
            preset = presetValues[preset.selectedItemPosition.coerceIn(0, presetValues.lastIndex)],
            frameRepair = repairValues[frameRepair.selectedItemPosition.coerceIn(0, repairValues.lastIndex)],
            codec = codecValues[codec.selectedItemPosition.coerceIn(0, codecValues.lastIndex)],
            rateMode = rateValues[rateMode.selectedItemPosition.coerceIn(0, rateValues.lastIndex)],
            targetFps = selectedFps(),
            targetWidth = dimensions.first,
            targetHeight = dimensions.second,
            bitrateMbps = number(bitrate, 0).coerceIn(0, 240),
            keepAudio = keepAudio.isChecked,
            trimStartMs = 0L,
            trimEndMs = 0L,
            replaceOriginal = replaceOriginal.isChecked,
            filters = VideoFilterConfig(
                denoise = strengthValues[denoise.selectedItemPosition.coerceIn(0, strengthValues.lastIndex)],
                deblock = strengthValues[deblock.selectedItemPosition.coerceIn(0, strengthValues.lastIndex)],
                sharpen = strengthValues[sharpen.selectedItemPosition.coerceIn(0, strengthValues.lastIndex)],
                brightness = number(brightness, 0),
                contrast = number(contrast, 100),
                saturation = number(saturation, 100),
                temperature = number(temperature, 0),
                tint = number(tint, 0)
            ),
            maxInterpolatedFramesPerGap = number(maxInterpolation, 8),
            thermalProtection = thermalProtection.isChecked,
            smartAutoTune = smartAutoTune,
            aiAssisted = aiAssisted.isChecked
        ).normalized()
    }

    private fun selectedDimensions(analysis: VideoAnalysis): Pair<Int, Int> = when (resolution.selectedItemPosition) {
        1 -> fitWithin(analysis.width, analysis.height, 3840, 2160)
        2 -> fitWithin(analysis.width, analysis.height, 1920, 1080)
        3 -> fitWithin(analysis.width, analysis.height, 1280, 720)
        else -> analysis.width to analysis.height
    }

    private fun selectedFps(): Int = when (fps.selectedItemPosition) {
        1 -> 24
        2 -> 30
        3 -> 60
        4 -> 120
        else -> 0
    }

    private fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= maxWidth && height <= maxHeight) return width to height
        val scale = minOf(maxWidth.toDouble() / width.toDouble(), maxHeight.toDouble() / height.toDouble())
        return ((width * scale).toInt() and 1.inv()) to ((height * scale).toInt() and 1.inv())
    }

    private fun resetFilters() {
        denoise.setSelection(strengthValues.indexOf(FilterStrength.OFF))
        deblock.setSelection(strengthValues.indexOf(FilterStrength.OFF))
        sharpen.setSelection(strengthValues.indexOf(FilterStrength.OFF))
        brightness.setText("0")
        contrast.setText("100")
        saturation.setText("100")
        temperature.setText("0")
        tint.setText("0")
        maxInterpolation.setText("8")
    }

    private fun setBusy(value: Boolean) {
        busy = value
        startButton.isEnabled = !value
        smartButton.isEnabled = !value && currentRecommendation != null
        cancelButton.visibility = if (value) View.VISIBLE else View.GONE
        cancelButton.isEnabled = value
    }

    private fun number(field: EditText, fallback: Int): Int = field.text.toString().trim().toIntOrNull() ?: fallback

    private fun <T> select(spinner: Spinner, values: List<T>, value: T) {
        spinner.setSelection(values.indexOf(value).coerceAtLeast(0))
    }

    private fun bind(spinner: Spinner, title: String, values: List<String>) {
        spinner.prompt = title
        spinner.contentDescription = title
        val options = values.mapIndexed { index, label ->
            ChoiceSpinnerAdapter.Option(
                value = index.toString(),
                label = label,
                description = optionDescription(label)
            )
        }
        val adapter = ChoiceSpinnerAdapter(this, options)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                adapter.selectedPosition = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun optionDescription(label: String): String = when {
        label.contains("Reparar fluidez") -> "Corrige pausas e irregularidades sem aplicar filtros visuais."
        label.contains("Priorizar qualidade") -> "Usa mais bitrate e processamento para preservar detalhes."
        label.contains("Equilibrar qualidade") -> "Mantém boa qualidade com tamanho e temperatura moderados."
        label.contains("Reduzir tamanho") -> "Comprime mais e pode limitar a resolução para economizar espaço."
        label.contains("Escolher automaticamente") -> "Analisa o vídeo no aparelho e escolhe ajustes conservadores."
        label.contains("Configuração manual") -> "Mantém todas as opções sob seu controle."
        label.contains("exatamente como gravado", true) -> "Não altera o tempo nem a quantidade dos quadros."
        label.contains("tempo dos quadros", true) -> "Regulariza timestamps e remove pausas sem inventar imagens."
        label.contains("quadro mais próximo", true) -> "Preenche posições ausentes repetindo o quadro mais próximo."
        label.contains("misturando quadros", true) -> "Suaviza falhas curtas, mas pode criar leve rastro em movimento rápido."
        label.contains("formato original", true) -> "Evita recodificação quando o arquivo e o encoder permitirem."
        label.contains("HEVC") -> "Gera arquivos menores, com maior custo de processamento."
        label.contains("AVC") -> "Tem maior compatibilidade com players e editores."
        label.contains("Automático") -> "O perfil escolhe o método mais seguro para o vídeo."
        label.contains("qualidade visual", true) -> "O bitrate varia para preservar a aparência das cenas complexas."
        label.contains("VBR") -> "Usa mais dados apenas quando a cena precisa."
        label.contains("CBR") -> "Mantém fluxo de dados mais constante e previsível."
        label.contains("tamanho original", true) -> "Mantém as dimensões do vídeo de origem."
        label.contains("Limitar a") -> "Só reduz quando o vídeo original ultrapassa esse tamanho."
        label.contains("FPS original", true) -> "Mantém a fluidez nominal detectada no arquivo."
        label.contains("120 FPS") -> "Aumenta bastante a carga térmica e exige encoder compatível."
        label.contains("Forte") -> "Aplicação intensa; revise o resultado antes de substituir o original."
        label.contains("Médio") -> "Aplicação moderada do filtro."
        label.contains("Leve") -> "Ajuste discreto para preservar detalhes."
        label.contains("Desligado") -> "Não aplica este filtro."
        else -> "Toque para escolher esta opção."
    }

    companion object {
        const val EXTRA_PATH = "media_path"
        const val EXTRA_SECONDARY = "secondary_vault"
        const val EXTRA_TERTIARY = "tertiary_vault"
    }
}
