package com.steadyvault.camera.capture.service

import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.capture.recorder.RecordingBackend
import com.steadyvault.camera.capture.recorder.DirectMediaRecorderBackend
import com.steadyvault.camera.capture.timing.RecordingStabilizationPolicy
import com.steadyvault.camera.capture.timing.CaptureCadencePolicy
import com.steadyvault.camera.capture.timing.StrictCaptureModePolicy
import com.steadyvault.camera.capture.timing.SensorCadencePolicy

import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.camera.Camera3AStateStore
import com.steadyvault.camera.core.camera.CameraLensCatalog
import com.steadyvault.camera.core.camera.CameraZoom
import com.steadyvault.camera.core.camera.CameraResourceCoordinator
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.camera.WhiteBalanceCorrection
import com.steadyvault.camera.core.camera.CctWhiteBalanceController
import com.steadyvault.camera.core.capability.CaptureCapabilityMatrix
import com.steadyvault.camera.core.capability.CaptureModeCatalog
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.CameraProfileStore
import com.steadyvault.camera.core.state.CapturePhase
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.storage.RecordingStorageGuard
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.processing.auto.AutoGapRepairService
import com.steadyvault.camera.storage.vault.RecordingRecoveryRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.capture.CameraPreviewRegistry
import com.steadyvault.camera.ui.apps.VaultScreenCaptureService
import com.steadyvault.camera.widgets.WidgetRenderer

import com.steadyvault.camera.core.settings.VisualIdentityStore

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CaptureService : Service() {

    private data class CameraProfile(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val videoSize: Size,
        val targetFps: Int,
        val fpsRange: Range<Int>,
        val highSpeed: Boolean,
        val dynamicRangeProfile: Long,
        val previewStabilizationSupported: Boolean,
        val eisSupported: Boolean,
        val oisCapability: OpticalStabilizationCapability.Capability,
        val streamUseCaseSupported: Boolean,
        val sensorOrientation: Int
    ) {
        val oisSupported: Boolean
            get() = oisCapability.supported

        val hdrHlg10: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    dynamicRangeProfile == DynamicRangeProfiles.HLG10
    }

    private data class EncoderProfile(
        val codecName: String,
        val mime: String,
        val bitrate: Int,
        val hdrHlg10: Boolean
    )

    private data class CachedConfiguration(
        val signature: String,
        val camera: CameraProfile,
        val encoder: EncoderProfile
    )

    private data class ClaimedRecording(
        val recorder: RecordingBackend?,
        val output: File?,
        val raw: File?,
        val wasStarted: Boolean
    )

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var encoderPreparationExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val threadCounter = AtomicInteger(0)
    private val serviceActive = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val resourceLock = Any()
    private val cameraLeaseToken = Any()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var professionalRecorder: RecordingBackend? = null
    private var recorderSurface: Surface? = null
    private var finalOutputFile: File? = null
    private var rawOutputFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var userRequestedStop = false
    private var stopHapticAcknowledged = false

    @Volatile
    private var recorderStarted = false

    @Volatile
    private var recorderArmed = false

    @Volatile
    private var currentState = "Parado"

    @Volatile
    private var attemptToken = 0

    private var selectedCamera: CameraProfile? = null
    private var selectedEncoder: EncoderProfile? = null
    private var recordingStartedAtMs = 0L
    private var recordingStartedAtElapsedMs = 0L
    private var captureSessionId = ""
    private var recordingRequestedAtElapsedNs = 0L
    private var previewCaptureRequested = false
    private var headlessCaptureRequested = false
    private var deferredRawCleanup = false
    private var preferredCameraId: String? = null
    private var headlessSessionStartToken = -1
    private val oisCapabilities = mutableMapOf<String, OpticalStabilizationCapability.Capability>()
    private lateinit var recordingSettings: CaptureSettings.Snapshot

    @Volatile
    private var requestedTargetFps = CaptureModeStore.FPS_60

    private var fpsFallbackWarningLogged = false
    private var activeRecorderBackendName = ""
    private var activeRecorderMime: String? = null

    @Volatile
    private var foregroundNotificationStarted = false




    override fun onCreate() {
        super.onCreate()

        cameraExecutor = Executors.newSingleThreadExecutor(
            createThreadFactory("SteadyVault-Camera", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        )
        encoderPreparationExecutor = Executors.newSingleThreadExecutor(
            createThreadFactory("SteadyVault-EncoderPrepare", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        )

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording(intent)
            ACTION_STOP -> if (
                intent.getBooleanExtra(EXTRA_USER_REQUESTED_STOP, false)
            ) {
                stopHapticAcknowledged = stopHapticAcknowledged || intent.getBooleanExtra(EXTRA_STOP_HAPTIC_ACKNOWLEDGED, false)
                stopCurrentRecording()
            }
        }
        // Se o sistema precisar recriar o serviço, repete somente a intenção de
        // início originalmente acionada pelo usuário. stopSelf() continua sendo
        // definitivo após o botão Parar, mas uma pressão de memória não transforma
        // apagar/acender a tela em uma parada silenciosa.
        return START_REDELIVER_INTENT
    }

    private fun createThreadFactory(prefix: String, priority: Int): ThreadFactory =
        ThreadFactory { runnable ->
            Thread(
                {
                    try {
                        Process.setThreadPriority(priority)
                    } catch (_: Throwable) {
                    }
                    runnable.run()
                },
                "$prefix-${threadCounter.incrementAndGet()}"
            )
        }

    private fun beginRecording(intent: Intent?) {
        if (!serviceActive.compareAndSet(false, true)) {
            sendState(currentState)
            return
        }
        // A captura sempre vence qualquer transcodificação automática. O pedido de
        // cancelamento é síncrono no processo; não esperamos o reparo encerrar.
        AutoGapRepairService.pauseForCapture(this)
        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)
        VaultScreenCaptureService.yieldToCameraCapture(this)
        captureSessionId = "video-${System.currentTimeMillis()}-${SystemClock.elapsedRealtimeNanos()}"

        recordingRequestedAtElapsedNs = intent?.getLongExtra(EXTRA_REQUESTED_AT_ELAPSED_NS, 0L)?.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
        headlessCaptureRequested = intent?.getBooleanExtra(EXTRA_HEADLESS_CAPTURE, false) == true
        if (headlessCaptureRequested) {
            // Contrato encoder-only: widget, tela preta e atalhos headless nunca
            // compartilham Camera2/ISP com Surface visual. A Activity preta é apenas
            // uma janela preta; não recebe frames da câmera.
            CameraPreviewRegistry.clear()
            previewCaptureRequested = false
        }
        previewCaptureRequested = if (headlessCaptureRequested) {
            false
        } else {
            intent?.getBooleanExtra(EXTRA_FROM_PREVIEW, false) == true
        }
        preferredCameraId = intent?.getStringExtra(EXTRA_PREFERRED_CAMERA_ID)?.takeIf { it.isNotBlank() }
        val currentSettings = CaptureSettings.snapshot(this)
        val allCameraOptions = CameraLensCatalog.options(this)
        val storedOption = allCameraOptions.firstOrNull { it.id == (preferredCameraId ?: currentSettings.selectedCameraId) }
        val profileCameraId = CameraLensCatalog.resolveRecordingCameraId(
            this,
            preferredCameraId ?: currentSettings.selectedCameraId,
            currentSettings.resolution
        )
        val profileZoomRatio = if (storedOption?.isBack == true && !storedOption.logical && kotlin.math.abs(currentSettings.zoomRatio - 1f) < 0.05f) {
            CameraLensCatalog.shortcutRatio(storedOption)
        } else {
            currentSettings.zoomRatio
        }
        preferredCameraId = profileCameraId
        // A configuração atualmente salva é a fonte da verdade. Não recarregue um
        // perfil histórico da câmera no instante em que o usuário toca em Gravar.
        CameraProfileStore.setActiveMode(this, CameraProfileStore.FunctionMode.VIDEO)
        recordingSettings = currentSettings.copy(selectedCameraId = profileCameraId, zoomRatio = profileZoomRatio)
        requestedTargetFps = intent
            ?.getIntExtra(EXTRA_TARGET_FPS, recordingSettings.fps)
            ?.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: recordingSettings.fps
        activeRecorderBackendName = ""
        activeRecorderMime = null
        fpsFallbackWarningLogged = false
        val storedTargetResolution = CaptureSettings.resolutionForFps(this, requestedTargetFps)
        val resolvedTargetResolution = resolveCaptureResolutionForFps(
            cameraId = profileCameraId,
            targetFps = requestedTargetFps,
            storedResolution = storedTargetResolution
        )
        if (resolvedTargetResolution != storedTargetResolution) {
            CaptureSettings.saveResolutionForFps(this, requestedTargetFps, resolvedTargetResolution)
        }
        if (
            requestedTargetFps != recordingSettings.fps ||
            resolvedTargetResolution != recordingSettings.resolution
        ) {
            recordingSettings = recordingSettings.copy(
                fps = requestedTargetFps,
                resolution = resolvedTargetResolution
            )
        }
        userRequestedStop = false
        stopHapticAcknowledged = false
        stopping.set(false)
        recorderStarted = false
        recorderArmed = false
        recordingStartedAtMs = 0L
        recordingStartedAtElapsedMs = 0L
        fpsFallbackWarningLogged = false
        foregroundNotificationStarted = false
        synchronized(resourceLock) { headlessSessionStartToken = -1 }
        attemptToken++
        val token = attemptToken

        if (!hasRequiredPermissions()) {
            sendState("Falha: permissão de câmera é obrigatória")
            serviceActive.set(false)
            AutoGapRepairService.resumeAfterCapture(this)
            stopSelf()
            return
        }

        try {
            // Garante primeiro o prazo do FGS apenas com câmera. A promoção para
            // microfone acontece imediatamente antes do AudioRecord, evitando a
            // corrida de AppOps/FGS de microfone observada no Android 16.
            startForegroundCaptureFast("Preparando ${requestedProfileLabel()}…")
        } catch (t: Throwable) {
            sendState("Falha ao iniciar serviço: ${errorText(t)}")
            abortBeforeCaptureStart()
            return
        }

        acquireWakeLock()
        // Recuperação/limpeza pode copiar MP4s no mesmo armazenamento do muxer.
        // Sempre adie esse I/O até a captura terminar, inclusive com preview.
        deferredRawCleanup = true

        val preparationMessage = when {
            headlessCaptureRequested -> "Preparando ${requestedProfileLabel()} em modo dedicado, sem preview compartilhado…"
            previewCaptureRequested && preferredCameraId != null -> "Preparando ${requestedProfileLabel()} na mesma câmera do preview…"
            else -> "Validando ${requestedProfileLabel()} na câmera e no encoder…"
        }
        sendState(preparationMessage)
        updateNotification(preparationMessage)

        // Seleção de perfil e preparação do encoder não precisam interromper o preview.
        // Só liberamos a câmera quando a superfície do encoder já está pronta, reduzindo
        // o intervalo preto/piscar entre a sessão de preview e a sessão de gravação.
        cameraExecutor.execute { prepareInitialConfigurationBeforeCameraHandoff(token) }
    }

    private fun prepareInitialConfigurationBeforeCameraHandoff(token: Int) {
        if (!isAttemptValid(token)) return
        try {
            val (cameraProfile, encoderProfile) = selectExactCaptureConfiguration(
                targetFps = requestedTargetFps,
                allowHdr = recordingSettings.hdrHlg10
            )
            check(matchesRequestedResolution(cameraProfile) && cameraProfile.targetFps == requestedTargetFps) {
                "A configuração efetiva não preservou exatamente a resolução/FPS solicitados"
            }
            selectedCamera = cameraProfile
            selectedEncoder = encoderProfile
            if (!validateStorageForRecording(cameraProfile, encoderProfile)) return
            if (headlessCaptureRequested) {
                prepareHeadlessInParallel(
                    cameraProfile = cameraProfile,
                    encoderProfile = encoderProfile,
                    token = token,
                    requestCameraOwnership = true
                )
                return
            }
            try {
                prepareOutputAndRecorder(cameraProfile, encoderProfile)
            } catch (throwable: Throwable) {
                releaseRecordingResources(deleteOutput = true)
                failAndStopFromWorker("encoder recusou ${requestedProfileLabel()}: ${errorText(throwable)}")
                return
            }
            if (!isAttemptValid(token)) {
                releaseRecordingResources(deleteOutput = true)
                return
            }

            sendStateOnMain("Encoder pronto • assumindo a câmera para ${requestedProfileLabel()}…")
            updateNotificationOnMain("Encoder pronto • abrindo a câmera…")
            CameraResourceCoordinator.requestCapture(
                owner = CameraResourceCoordinator.Owner.VIDEO,
                token = cameraLeaseToken,
                onGranted = {
                    if (!isAttemptValid(token)) {
                        releaseRecordingResources(deleteOutput = true)
                        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
                    } else {
                        cameraExecutor.execute {
                            runCatching { openSelectedCamera(cameraProfile, token) }
                                .onFailure {
                                    failSelectedConfigurationFromWorker(
                                        token,
                                        "falha ao solicitar abertura da câmera: ${errorText(it)}"
                                    )
                                }
                        }
                    }
                },
                onDenied = { reason ->
                    cameraExecutor.execute {
                        releaseRecordingResources(deleteOutput = true)
                        failAndStopFromWorker("câmera indisponível: $reason")
                    }
                }
            )
        } catch (throwable: Throwable) {
            failAndStopFromWorker(errorText(throwable))
        }
    }

    private fun prepareHeadlessInParallel(
        cameraProfile: CameraProfile,
        encoderProfile: EncoderProfile,
        token: Int,
        requestCameraOwnership: Boolean
    ) {
        if (!isAttemptValid(token)) return
        synchronized(resourceLock) { headlessSessionStartToken = -1 }

        sendStateOnMain("Abrindo câmera e encoder em paralelo…")
        updateNotificationOnMain("Preparando captura rápida…")

        encoderPreparationExecutor.execute encoderPreparation@{
            try {
                prepareOutputAndRecorder(cameraProfile, encoderProfile)
            } catch (throwable: Throwable) {
                cameraExecutor.execute cameraFailure@{
                    if (!isAttemptValid(token)) return@cameraFailure
                        failSelectedConfigurationFromWorker(token, "encoder: ${errorText(throwable)}")
                }
                return@encoderPreparation
            }

            if (!isAttemptValid(token)) {
                releaseRecorderAndOutput(deleteOutput = true)
                return@encoderPreparation
            }

            cameraExecutor.execute {
                val camera = synchronized(resourceLock) { cameraDevice }
                if (camera != null) maybeCreateHeadlessSession(camera, cameraProfile, token)
            }
        }

        val openCamera = {
            if (!isAttemptValid(token)) {
                releaseRecordingResources(deleteOutput = true)
            } else {
                cameraExecutor.execute {
                    runCatching { openSelectedCamera(cameraProfile, token) }
                        .onFailure {
                            failSelectedConfigurationFromWorker(
                                token,
                                "falha ao solicitar abertura da câmera: ${errorText(it)}"
                            )
                        }
                }
            }
        }

        if (requestCameraOwnership) {
            CameraResourceCoordinator.requestCapture(
                owner = CameraResourceCoordinator.Owner.VIDEO,
                token = cameraLeaseToken,
                onGranted = openCamera,
                onDenied = { reason ->
                    cameraExecutor.execute {
                        releaseRecordingResources(deleteOutput = true)
                        failAndStopFromWorker("câmera indisponível: $reason")
                    }
                }
            )
        } else {
            openCamera()
        }
    }

    private fun maybeCreateHeadlessSession(
        camera: CameraDevice,
        profile: CameraProfile,
        token: Int
    ) {
        val canStart = synchronized(resourceLock) {
            if (
                !isAttemptValid(token) ||
                cameraDevice !== camera ||
                recorderSurface == null ||
                headlessSessionStartToken == token
            ) {
                false
            } else {
                headlessSessionStartToken = token
                true
            }
        }
        if (canStart) createRecordingSessionSafely(camera, profile, token)
    }

    private fun failSelectedConfigurationFromWorker(expectedToken: Int, reason: String) {
        if (!isAttemptValid(expectedToken)) return
        if (recorderStarted) {
            failAndStopFromWorker(reason)
            return
        }

        ++attemptToken
        releaseRecordingResources(deleteOutput = true)
        recorderStarted = false
        failAndStopFromWorker("${requestedProfileLabel()} não foi aceito: $reason")
    }

    private fun handleCameraInterruption(
        token: Int,
        reason: String
    ) {
        if (!isAttemptValid(token)) return
        if (recorderStarted) failAndStopFromWorker(reason)
        else failSelectedConfigurationFromWorker(token, reason)
    }

    /**
     * O widget pode ser usado antes de a análise completa de hardware existir.
     * Para 120/240, consulta diretamente os metadados constrained high-speed e
     * escolhe a maior resolução que publica a faixa fixa exata solicitada.
     */
    private fun resolveCaptureResolutionForFps(
        cameraId: String?,
        targetFps: Int,
        storedResolution: String
    ): String {
        if (cameraId.isNullOrBlank()) return storedResolution
        val cachedMatrix = CaptureCapabilityMatrix.cached(this)?.forCamera(cameraId)
        val catalogResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = storedResolution,
            matrix = cachedMatrix
        )
        if (targetFps < CaptureModeStore.FPS_120) return catalogResolution

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return catalogResolution
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return catalogResolution
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        val mime = recordingSettings.codecMimes(false).singleOrNull() ?: return catalogResolution
        val candidates = listOf(
            CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE,
            CaptureSettings.RESOLUTION_4K to CaptureSettings.UHD_SIZE,
            CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE,
            CaptureSettings.RESOLUTION_720P to CaptureSettings.HD_SIZE
        )
        val cameraCandidates = candidates.filter { (_, size) ->
            size in highSpeedSizes && runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)?.any { range ->
                    StrictCaptureModePolicy.acceptsFpsRange(targetFps, range.lower, range.upper)
                } == true
            }.getOrDefault(false)
        }
        if (cameraCandidates.isEmpty()) return catalogResolution

        // Não confie só no metadata da câmera: 240 pode existir no sensor e não na
        // combinação HEVC/resolução do encoder. Prioriza taxa confirmada pelo codec.
        cameraCandidates.firstOrNull { (_, size) ->
            highSpeedEncoderSupportLevel(size, targetFps, mime) >= 2
        }?.let { return it.first }

        val sizeSupported = cameraCandidates.filter { (_, size) ->
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
    }

    private fun highSpeedEncoderSupportLevel(size: Size, fps: Int, mime: String): Int {
        var sizeSupported = false
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in codecInfos) {
            if (!info.isEncoder || info.isSoftwareOnly) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val videoCaps = caps.videoCapabilities ?: continue
            if (!runCatching { videoCaps.isSizeSupported(size.width, size.height) }.getOrDefault(false)) continue
            sizeSupported = true
            if (runCatching {
                    videoCaps.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                }.getOrDefault(false)) {
                return 2
            }
        }
        // EncoderProfiles OEM com FPS exato também vale como confirmação forte.
        val oemExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                DirectMediaRecorderBackend.findExactSelection(
                    cameraId = preferredCameraId.orEmpty(),
                    width = size.width,
                    height = size.height,
                    fps = fps,
                    mime = mime,
                    hdrHlg10 = false,
                    requestedBitrate = configuredVideoBitrate()
                )
            }.getOrNull()
        } else null
        if (oemExact != null) return 2
        return if (sizeSupported) 1 else 0
    }

    private fun selectBestCaptureConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> {
        val signature = configurationSignature(targetFps, allowHdr)
        cachedConfiguration
            ?.takeIf { it.signature == signature }
            ?.takeIf { matchesRequestedMode(it.camera, targetFps) }
            ?.takeIf { it.camera.matchesRequestedFpsContract() }
            ?.takeIf { preferredCameraCompatible(it.camera) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.camera) }
            ?.let { return it.camera to it.encoder }

        loadRememberedConfiguration(signature, targetFps, allowHdr)
            ?.takeIf { preferredCameraCompatible(it.first) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.first) }
            ?.let { (camera, encoder) ->
                cacheConfiguration(signature, camera, encoder)
                return camera to encoder
            }

        selectPreferredPreviewConfiguration(targetFps, allowHdr)?.let { (camera, encoder) ->
            cacheConfiguration(signature, camera, encoder)
            return camera to encoder
        }

        val requestedLabel = "${CaptureSettings.resolutionLabel(recordingSettings.resolution)} $targetFps FPS"
        val cameraLabel = CameraLensCatalog.labelFor(this, preferredCameraId)
        throw IllegalStateException("$requestedLabel não pôde ser preparado para a câmera selecionada ($cameraLabel)")
    }

    private fun cacheConfiguration(
        signature: String,
        camera: CameraProfile,
        encoder: EncoderProfile
    ) {
        cachedConfiguration = CachedConfiguration(signature, camera, encoder)
        getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE).edit()
            .putString(CONFIG_SIGNATURE, signature)
            .putString(CONFIG_CAMERA_ID, camera.cameraId)
            .putInt(CONFIG_WIDTH, camera.videoSize.width)
            .putInt(CONFIG_HEIGHT, camera.videoSize.height)
            .putInt(CONFIG_FPS, camera.targetFps)
            .putBoolean(CONFIG_HIGH_SPEED, camera.highSpeed)
            .putLong(CONFIG_DYNAMIC_RANGE, camera.dynamicRangeProfile)
            .putString(CONFIG_MIME, encoder.mime)
            .apply()
    }

    private fun loadRememberedConfiguration(
        signature: String,
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile>? {
        val prefs = getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE)
        if (prefs.getString(CONFIG_SIGNATURE, null) != signature) return null
        val cameraId = prefs.getString(CONFIG_CAMERA_ID, null) ?: return null
        val width = prefs.getInt(CONFIG_WIDTH, 0)
        val height = prefs.getInt(CONFIG_HEIGHT, 0)
        val fps = prefs.getInt(CONFIG_FPS, 0)
        val highSpeed = prefs.getBoolean(CONFIG_HIGH_SPEED, false)
        val storedDynamicRange = prefs.getLong(CONFIG_DYNAMIC_RANGE, standardDynamicRangeProfile())
        val mime = prefs.getString(CONFIG_MIME, null) ?: return null
        if (width <= 0 || height <= 0 || fps <= 0 || mime !in recordingSettings.codecMimes(allowHdr)) return null
        val rememberedSize = Size(width, height)
        if (fps != targetFps || recordingSettings.exactPreferredSize()?.let { it != rememberedSize } == true) {
            getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE).edit().clear().apply()
            return null
        }

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return null
        val size = rememberedSize
        val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed) ?: return null
        val dynamicRange = when {
            allowHdr && supportsHlg10(characteristics) -> storedDynamicRange
            allowHdr -> return null
            else -> standardDynamicRangeProfile()
        }
        val profile = createCameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = size,
            targetFps = fps,
            fpsRange = fpsRange,
            highSpeed = highSpeed,
            dynamicRangeProfile = dynamicRange
        )
        if (mime !in recordingSettings.codecMimes(profile.hdrHlg10)) return null
        val encoder = selectDirectRecorderEncoder(profile) ?: return null
        return profile to encoder
    }

    private fun configurationSignature(targetFps: Int, allowHdr: Boolean): String = listOf(
        CAPTURE_PIPELINE_REVISION,
        recordingSettings.resolution,
        targetFps,
        recordingSettings.codec,
        recordingSettings.bitrateMbps,
        recordingSettings.autoFpsLowLight,
        allowHdr,
        recordingSettings.stabilization,
        recordingSettings.focusMode,
        preferredCameraId.orEmpty()
    ).joinToString("|")

    /**
     * A captura iniciada pelo preview já conhece a lente em uso. Validar primeiro essa
     * câmera evita percorrer todas as lentes novamente e reduz a pausa entre tocar no
     * obturador e o início do encoder. Se a combinação exata não for aceita, o fluxo
     * completo continua sendo usado; em pedidos de alta taxa, 60 FPS é o piso e
     * nunca existe downgrade automático para 30 FPS.
     */
    private fun selectPreferredPreviewConfiguration(
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

        // 30/60 FPS: não bloqueie o Start usando metadados públicos de cadence/minFrame.
        // Em aparelhos Samsung a HAL pode aceitar 4K60 mesmo quando esses metadados
        // são conservadores. Monte exatamente o modo pedido e deixe createCaptureSession()
        // ser a fonte de verdade. Se a HAL recusar, a tentativa falha de forma explícita.
        if (targetFps < CaptureModeStore.FPS_120) {
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

        val requestedSize = recordingSettings.exactPreferredSize() ?: return null

        // A API permite high-FPS em sessão regular quando a câmera publica a faixa
        // exata em CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. Preferimos esse caminho
        // quando também há cadence pública suficiente, pois ele evita o batching do
        // constrained high-speed observado no S25 (rajadas + buracos em 120 FPS).
        val regularRange = resolveRegularHighFpsRange(characteristics, requestedSize, targetFps)
        if (regularRange != null) {
            val regularProfile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = requestedSize,
                targetFps = targetFps,
                fpsRange = regularRange,
                highSpeed = false,
                dynamicRangeProfile = dynamicRange
            )
            if (matchesRequestedMode(regularProfile, targetFps)) {
                val encoder = selectDirectRecorderEncoder(regularProfile)
                if (encoder != null) return regularProfile to encoder
            }
        }

        // Se a câmera não confirma sessão regular, use constrained high-speed com
        // request mínimo e somente uma Surface de gravação.
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())

        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps || size !in highSpeedSizes) continue
            val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed = true) ?: continue
            val profile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = size,
                targetFps = fps,
                fpsRange = fpsRange,
                highSpeed = true,
                dynamicRangeProfile = dynamicRange
            )
            if (!matchesRequestedMode(profile, targetFps)) continue
            val encoder = selectDirectRecorderEncoder(profile) ?: continue
            if (profile.hasExactFpsRange()) return profile to encoder
        }
        return null
    }

    private fun resolveFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int,
        highSpeed: Boolean
    ): Range<Int>? {
        if (!highSpeed) {
            if (targetFps >= CaptureModeStore.FPS_120) {
                return resolveRegularHighFpsRange(characteristics, size, targetFps)
            }
            return resolveStandardFpsRange(characteristics, targetFps)
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return selectTargetFpsRange(ranges, targetFps)
    }

    /**
     * Equivalente Android do Auto FPS do iPhone: permanece desligado por padrão.
     * Quando habilitado, 30/60 usam apenas uma faixa variável que a própria HAL
     * publica e cujo teto é exatamente o FPS escolhido. Nunca se aplica a 120/240.
     */
    private fun resolveStandardFpsRange(
        characteristics: CameraCharacteristics,
        targetFps: Int
    ): Range<Int> {
        val exact = Range(targetFps, targetFps)
        if (!recordingSettings.autoFpsLowLight || targetFps !in setOf(CaptureModeStore.FPS_30, CaptureModeStore.FPS_60)) {
            return exact
        }
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == targetFps && it.lower < targetFps }
            .orEmpty()
        // Prefere a menor variação possível (ex.: 30-60 em vez de 15-60).
        return ranges.maxByOrNull { it.lower } ?: exact
    }

    private fun resolveRegularHighFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int
    ): Range<Int>? {
        if (targetFps < CaptureModeStore.FPS_120) return Range(targetFps, targetFps)
        val exact = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val minFrameNs = encoderSurfaceMinFrameDurationNs(map, size, preferMediaRecorder = true)
        val targetFrameNs = frameDurationNs(targetFps)
        if (minFrameNs > 0L && minFrameNs > targetFrameNs + HIGH_FPS_FRAME_TOLERANCE_NS) return null
        return exact
    }

    /**
     * FPS selecionado é contrato exato. Nenhuma faixa variável é aceita: 30 usa
     * [30,30], 60 usa [60,60], 120 usa [120,120] e 240 usa [240,240].
     */
    private fun selectTargetFpsRange(
        ranges: List<Range<Int>>,
        targetFps: Int
    ): Range<Int>? = ranges.firstOrNull {
        StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper)
    }

    private fun configurationRequestOrder(targetFps: Int): List<Pair<Size, Int>> {
        val requested = requireNotNull(recordingSettings.exactPreferredSize()) { "resolução manual inválida" }.toPolicyDimensions()
        return StrictCaptureModePolicy.requestOrder(requested, targetFps).map { request ->
            Size(request.dimensions.width, request.dimensions.height) to request.fps
        }
    }

    private fun matchesRequestedMode(camera: CameraProfile, targetFps: Int): Boolean {
        val requested = recordingSettings.exactPreferredSize()?.toPolicyDimensions() ?: return false
        return StrictCaptureModePolicy.matches(
            requestedSize = requested,
            targetFps = targetFps,
            actualSize = camera.videoSize.toPolicyDimensions(),
            actualFps = camera.targetFps
        )
    }

    private fun matchesRequestedResolution(camera: CameraProfile): Boolean =
        recordingSettings.exactPreferredSize() == camera.videoSize

    private fun selectExactCaptureConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> =
        selectBestCaptureConfiguration(
            targetFps = targetFps,
            allowHdr = allowHdr && targetFps < CaptureModeStore.FPS_120
        )

    private fun CameraProfile.hasExactFpsRange(): Boolean =
        fpsRange.lower == targetFps && fpsRange.upper == targetFps

    private fun CameraProfile.matchesRequestedFpsContract(): Boolean = when {
        highSpeed || targetFps >= CaptureModeStore.FPS_120 -> hasExactFpsRange()
        !recordingSettings.autoFpsLowLight -> hasExactFpsRange()
        else -> fpsRange.upper == targetFps && fpsRange.lower <= targetFps
    }

    private fun Size.toPolicyDimensions() = StrictCaptureModePolicy.Dimensions(width, height)

    /** A gravação usa somente Surface do MediaRecorder; não há MediaCodec/MediaMuxer manual. */
    private fun encoderSurfaceMinFrameDurationNs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        preferMediaRecorder: Boolean = true
    ): Long {
        val recorderDuration = runCatching {
            map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        }.getOrNull()?.takeIf { it > 0L }
        if (recorderDuration != null) return recorderDuration
        return runCatching {
            map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
        }.getOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private fun cadenceConfidence(profile: CameraProfile): CaptureCadencePolicy.Confidence {
        if (profile.highSpeed || profile.targetFps < CaptureModeStore.FPS_60) {
            return CaptureCadencePolicy.Confidence.CONFIRMED
        }
        val map = profile.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return CaptureCadencePolicy.Confidence.UNKNOWN
        val minFrameDurationNs = encoderSurfaceMinFrameDurationNs(
            map,
            profile.videoSize,
            preferMediaRecorder = true
        )
        return CaptureCadencePolicy.confidence(
            minFrameDurationNs = minFrameDurationNs,
            targetFps = profile.targetFps,
            toleranceNs = FRAME_DURATION_TOLERANCE_NS
        )
    }

    private fun createCameraProfile(
        cameraId: String,
        characteristics: CameraCharacteristics,
        videoSize: Size,
        targetFps: Int,
        fpsRange: Range<Int>,
        highSpeed: Boolean,
        dynamicRangeProfile: Long
    ): CameraProfile {
        val eisModes =
            characteristics.get(
                CameraCharacteristics
                    .CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            ) ?: intArrayOf()

        return CameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = videoSize,
            targetFps = targetFps,
            fpsRange = fpsRange,
            highSpeed = highSpeed,
            dynamicRangeProfile = dynamicRangeProfile,
            previewStabilizationSupported =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        eisModes.contains(
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                        ),
            eisSupported =
                eisModes.contains(
                    CameraMetadata
                        .CONTROL_VIDEO_STABILIZATION_MODE_ON
                ),
            oisCapability = oisCapability(cameraId, characteristics),
            streamUseCaseSupported = supportsVideoRecordStreamUseCase(characteristics),
            sensorOrientation =
                characteristics.get(
                    CameraCharacteristics
                        .SENSOR_ORIENTATION
                ) ?: 90
        )
    }

    private fun oisCapability(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): OpticalStabilizationCapability.Capability = synchronized(oisCapabilities) {
        oisCapabilities.getOrPut(cameraId) {
            OpticalStabilizationCapability.inspect(
                manager = getSystemService(CameraManager::class.java),
                cameraId = cameraId,
                characteristics = characteristics
            )
        }
    }

    private fun standardDynamicRangeProfile(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DynamicRangeProfiles.STANDARD
        } else {
            1L
        }

    private fun supportsHlg10(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false
        if (
            !capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
            )
        ) {
            return false
        }
        val profiles = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
        ) ?: return false
        if (!profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val colorSpaces = runCatching {
                characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
                )?.getSupportedColorSpacesForDynamicRange(
                    ImageFormat.PRIVATE,
                    DynamicRangeProfiles.HLG10
                )
            }.getOrNull()
            if (colorSpaces != null && !colorSpaces.contains(ColorSpace.Named.BT2020_HLG)) {
                return false
            }
        }
        return true
    }

    private fun supportsVideoRecordStreamUseCase(
        characteristics: CameraCharacteristics
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false
        if (
            !capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE
            )
        ) {
            return false
        }
        val useCases = characteristics.get(
            CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
        ) ?: return false
        return useCases.contains(
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        )
    }


    private fun explicitOisRequested(): Boolean = recordingSettings.stabilization == CaptureSettings.STABILIZATION_OIS

    private fun profileSatisfiesExplicitStabilization(profile: CameraProfile): Boolean {
        // Metadados de estabilização da câmera lógica podem ser incompletos em Samsung.
        // A escolha do perfil não é mais descartada aqui; configureCaptureRequest()
        // tenta exatamente o modo escolhido e a própria HAL decide se o request é válido.
        return profile.targetFps == requestedTargetFps
    }

    private fun preferredCameraCompatible(profile: CameraProfile): Boolean {
        val selected = preferredCameraId ?: return true
        return profile.cameraId == selected
    }


    private fun selectDirectRecorderEncoder(profile: CameraProfile): EncoderProfile? {
        val mime = recordingSettings.codecMimes(profile.hdrHlg10).singleOrNull() ?: return null
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DirectMediaRecorderBackend.findExactSelection(
                cameraId = profile.cameraId,
                width = profile.videoSize.width,
                height = profile.videoSize.height,
                fps = profile.targetFps,
                mime = mime,
                hdrHlg10 = profile.hdrHlg10,
                requestedBitrate = configuredVideoBitrate()
            )
        } else null
        if (profile.hdrHlg10 && exact == null) return null
        return EncoderProfile(
            codecName = if (exact != null) "MediaRecorder direto (perfil OEM compatível)" else "MediaRecorder direto",
            mime = mime,
            bitrate = configuredVideoBitrate(),
            hdrHlg10 = profile.hdrHlg10
        )
    }

    private fun configuredVideoBitrate(): Int =
        (recordingSettings.bitrateMbps * 1_000_000L)
            .coerceIn(4_000_000L, MAX_VIDEO_BITRATE.toLong())
            .toInt()

    private fun cleanupOldRawFilesAsync() {
        Thread(
            {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    RecordingRecoveryRepository.recoverStaleRecordings(
                        this,
                        STALE_RAW_FILE_MIN_AGE_MS
                    )
                }
            },
            "SteadyVault-RawCleanup"
        ).start()
    }

    private fun validateStorageForRecording(
        cameraProfile: CameraProfile,
        encoderProfile: EncoderProfile
    ): Boolean {
        val check = RecordingStorageGuard.check(
            context = this,
            videoBitrateBps = encoderProfile.bitrate.toLong(),
            audioBitrateBps = if (hasAudioPermission()) recordingSettings.audioBitrateKbps.toLong() * 1_000L else 0L
        )
        if (check.allowed) return true
        failAndStopFromWorker(check.message)
        return false
    }

    private fun prepareOutputAndRecorder(
        cameraProfile: CameraProfile,
        encoderProfile: EncoderProfile
    ) {
        val profileLabel = "${cameraProfile.videoSize.width}x${cameraProfile.videoSize.height}_${cameraProfile.targetFps}fps"
        val finalFile = VaultRepository.createRecordingFile(this, profileLabel)
        val integratedAudio = hasAudioPermission() && runCatching {
            startForegroundNow("Preparando gravação direta…", includeMicrophone = true)
            true
        }.getOrElse {
            Log.w(LOG_TAG, "Áudio indisponível nesta tentativa; gravando vídeo puro", it)
            false
        }

        val backend = DirectMediaRecorderBackend(
            context = this,
            outputFile = finalFile,
            cameraId = cameraProfile.cameraId,
            width = cameraProfile.videoSize.width,
            height = cameraProfile.videoSize.height,
            targetFps = cameraProfile.targetFps,
            videoMime = encoderProfile.mime,
            videoBitrate = encoderProfile.bitrate,
            hdrHlg10 = cameraProfile.hdrHlg10,
            orientationHint = calculateOrientationHint(cameraProfile.sensorOrientation),
            integratedAudio = integratedAudio,
            audioSampleRate = recordingSettings.audioSampleRate,
            audioBitrate = recordingSettings.audioBitrateKbps * 1_000,
            audioChannels = when (recordingSettings.audioChannels) {
                CaptureSettings.CHANNELS_MONO -> 1
                CaptureSettings.CHANNELS_STEREO -> 2
                else -> 2
            },
            onError = { throwable ->
                if (!stopping.get() && serviceActive.get()) failAndStop("MediaRecorder direto: ${errorText(throwable)}")
            }
        )

        try {
            val surface = backend.prepare()
            synchronized(resourceLock) {
                finalOutputFile = finalFile
                rawOutputFile = finalFile
                professionalRecorder = backend
                recorderSurface = surface
                activeRecorderBackendName = backend.backendName
                activeRecorderMime = encoderProfile.mime
            }
            val msg = "MediaRecorder direto pronto: ${backend.profileDescription}"
            Log.i(LOG_TAG, msg)
            AppLogRepository.info(this, "recording_backend", msg)
        } catch (t: Throwable) {
            runCatching { backend.release() }
            runCatching { finalFile.delete() }
            throw t
        }
    }

    private fun calculateOrientationHint(sensorOrientation: Int): Int =
        ((sensorOrientation % 360) + 360) % 360

    private fun openSelectedCamera(profile: CameraProfile, token: Int) {
        if (!isAttemptValid(token)) return

        sendStateOnMain(
            "Abrindo ${sizeName(profile.videoSize)} • " +
                    "${fpsModeName(profile)} • " +
                    "${encoderName()} • " +
                    stabilizationName(profile)
        )

        val manager = getSystemService(CameraManager::class.java)

        if (
            checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("permissão da câmera foi revogada")
        }

        try {
            manager.openCamera(
                profile.cameraId,
                cameraExecutor,
                object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isAttemptValid(token)) {
                        camera.close()
                        return
                    }
            
                    synchronized(resourceLock) {
                        cameraDevice = camera
                    }

                    if (headlessCaptureRequested) {
                        maybeCreateHeadlessSession(camera, profile, token)
                    } else {
                        createRecordingSessionSafely(camera, profile, token)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handleCameraInterruption(token, "a câmera foi desconectada")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    handleCameraInterruption(token, "erro da câmera: $error")
                }

                override fun onClosed(camera: CameraDevice) {
                    synchronized(resourceLock) {
                        if (cameraDevice === camera) cameraDevice = null
                    }
                }
                }
            )
        } catch (throwable: CameraAccessException) {
            throw throwable
        }
    }

    /**
     * Nenhuma exceção de configuração da HAL pode escapar de CameraDevice callbacks.
     * Alguns Samsung anunciam uma chave/modo e ainda assim recusam a combinação no
     * CaptureRequest real. Isso é uma falha da configuração selecionada, não motivo
     * para derrubar o processo do app.
     */
    private fun createRecordingSessionSafely(
        camera: CameraDevice,
        profile: CameraProfile,
        token: Int
    ) {
        runCatching {
            createRecordingSession(camera, profile, token)
        }.onFailure { throwable ->
            Log.e(LOG_TAG, "Falha controlada criando sessão de gravação", throwable)
            AppLogRepository.error(
                this,
                "recording_session",
                "${sizeName(profile.videoSize)} ${profile.targetFps} FPS: ${errorText(throwable)}"
            )
            failSelectedConfigurationFromWorker(
                token,
                "falha ao configurar câmera/estabilização: ${errorText(throwable)}"
            )
        }
    }

    private fun createRecordingSession(
        camera: CameraDevice,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) {
            return
        }

        val surface =
            synchronized(resourceLock) {
                recorderSurface
            } ?: return failAndStopFromWorker(
                "superfície do encoder indisponível"
            )

        // Toda gravação usa a mesma sessão encoder-only. A tela de captura pode manter
        // sua interface, mas nunca recebe uma segunda saída Camera2 durante o vídeo.
        // Isso reserva ISP, memória e largura de banda exclusivamente para o arquivo.
        val requestBuilder =
            createRecordRequestBuilder(camera).apply {
                addTarget(surface)
                configureCaptureRequest(this, profile)
                if (!profile.highSpeed) applyFinalWhiteBalance(this, profile)
            }

        val request =
            requestBuilder.build()

        val outputConfiguration = OutputConfiguration(surface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setDynamicRangeProfile(profile.dynamicRangeProfile)
                if (
                    !profile.highSpeed &&
                    profile.streamUseCaseSupported
                ) {
                    setStreamUseCase(
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
                    )
                }
            }
        }

        val outputConfigurations = listOf(outputConfiguration)

        val sessionType =
            if (profile.highSpeed) {
                SessionConfiguration
                    .SESSION_HIGH_SPEED
            } else {
                SessionConfiguration
                    .SESSION_REGULAR
            }

        val sessionConfiguration =
            SessionConfiguration(
                sessionType,
                outputConfigurations,
                cameraExecutor,
                object :
                    CameraCaptureSession
                    .StateCallback() {

                    override fun onConfigured(
                        session:
                        CameraCaptureSession
                    ) {
                        if (
                            !isAttemptValid(
                                token
                            )
                        ) {
                            session.close()
                            return
                        }

                        synchronized(
                            resourceLock
                        ) {
                            captureSession =
                                session
                        }

                        try {
                            if (
                                profile.highSpeed
                            ) {
                                val highSpeedSession =
                                    session as?
                                            CameraConstrainedHighSpeedCaptureSession
                                        ?: throw IllegalStateException(
                                            "sessão high-speed não retornada pela HAL"
                                        )

                                val requests = highSpeedSession.createHighSpeedRequestList(request)

                                startHighSpeedRecording(
                                    session = highSpeedSession,
                                    requests = requests,
                                    profile = profile,
                                    token = token
                                )
                            } else {
                                startStabilizedRecording(
                                    session = session,
                                    request = request,
                                    profile = profile,
                                    token = token
                                )
                            }
                        } catch (throwable: Throwable) {
                            failSelectedConfigurationFromWorker(
                                token,
                                "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(throwable)}"
                            )
                        }
                    }

                    override fun onConfigureFailed(
                        session:
                        CameraCaptureSession
                    ) {
                        session.close()
                        failSelectedConfigurationFromWorker(
                            token,
                            "a HAL recusou ${sizeName(profile.videoSize)} ${profile.targetFps} FPS"
                        )
                    }

                    override fun onClosed(
                        session:
                        CameraCaptureSession
                    ) {
                        synchronized(
                            resourceLock
                        ) {
                            if (
                                captureSession ===
                                session
                            ) {
                                captureSession =
                                    null
                            }
                        }
                    }
                }
            )

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !profile.highSpeed
        ) {
            val desiredColorSpace = if (profile.hdrHlg10) {
                ColorSpace.Named.BT2020_HLG
            } else {
                ColorSpace.Named.BT709
            }
            val supportedColorSpaces = runCatching {
                profile.characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
                )?.getSupportedColorSpacesForDynamicRange(
                    ImageFormat.PRIVATE,
                    profile.dynamicRangeProfile
                )
            }.getOrNull()
            if (supportedColorSpaces?.contains(desiredColorSpace) == true) {
                runCatching { sessionConfiguration.setColorSpace(desiredColorSpace) }
            }
        }

        if (!profile.highSpeed) {
            sessionConfiguration
                .setSessionParameters(
                    request
                )
        }

        try {
            camera.createCaptureSession(sessionConfiguration)
        } catch (throwable: Throwable) {
            failSelectedConfigurationFromWorker(
                token,
                "falha criando sessão ${sizeName(profile.videoSize)} ${profile.targetFps} FPS: ${errorText(throwable)}"
            )
        }
    }

    /**
     * High-speed sem segunda submissão do repeating request.
     *
     * O burst definitivo é instalado UMA única vez e permanece congelado durante
     * toda a captura. Não há callback de warm-up, aprovação de cadência nem troca
     * de request imediatamente antes de salvar. O encoder drena qualquer saída
     * anterior e só abre o arquivo depois que este burst foi aceito pela sessão.
     */
    private fun startHighSpeedRecording(
        session: CameraConstrainedHighSpeedCaptureSession,
        requests: List<CaptureRequest>,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        sendStateOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps…")
        updateNotificationOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps high-speed…")

        runCatching {
            armRecorderForFirstFrame(token)
            session.setRepeatingBurst(requests, null, mainHandler)
            commitRecorderStart(profile, token, highSpeed = true)
        }.onFailure {
            failSelectedConfigurationFromWorker(token, "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}")
        }
    }

    /**
     * Gravação regular com request congelado desde a primeira submissão.
     *
     * Não há callback Camera2 por frame e não existe uma segunda chamada para
     * substituir o repeating request antes do primeiro sample. O recorder é armado
     * antes, mas a época do arquivo só é confirmada depois que o request definitivo
     * entra na sessão; o primeiro IDR dessa época passa a ser o tempo zero do MP4.
     */
    private fun startStabilizedRecording(
        session: CameraCaptureSession,
        request: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        sendStateOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps…")
        updateNotificationOnMain("Iniciando ${sizeName(profile.videoSize)} ${profile.targetFps} fps…")

        runCatching {
            armRecorderForFirstFrame(token)
            val manualSensor = supportsManualSensor(profile)
            if (
                !recordingSettings.autoFpsLowLight &&
                profile.targetFps == CaptureModeStore.FPS_60 &&
                !profile.hdrHlg10 &&
                manualSensor
            ) {
                // Caminho AE60 CLEAN medido como o mais estável no S25 Ultra.
                // 30 FPS nunca entra aqui. Não existe warm-up especial do widget.
                val recent = Camera3AStateStore.recentExposure(profile.cameraId)
                val immediatePlan = recent?.let {
                    fixedCadencePlan(profile, it.exposureTimeNs, it.sensitivityIso)
                }
                if (immediatePlan != null) {
                    val fixedRequest = buildFixedCadenceRequest(profile, immediatePlan)
                        ?: throw IllegalStateException("câmera não disponível para request de cadência fixa")
                    session.setRepeatingRequest(fixedRequest, null, mainHandler)
                    commitRecorderStart(profile, token, highSpeed = false)
                } else {
                    startWithFixedSensorCadence(session, request, profile, token)
                }
            } else {
                // 30 FPS e Auto FPS seguem AE contínuo, como o comportamento do AVFoundation.
                session.setRepeatingRequest(request, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }
        }.onFailure {
            failSelectedConfigurationFromWorker(token, "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}")
        }
    }

    private fun supportsManualSensor(profile: CameraProfile): Boolean {
        val capabilities = profile.characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null &&
            profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null
    }

    /**
     * Captura headless precisa priorizar o instante do usuário. O AE recebe uma janela
     * curtíssima antes do MediaRecorder começar: até 3 resultados e nunca mais de 50 ms.
     * Se houver exposição/ISO válidos, a cadência é congelada antes do primeiro sample.
     * Se não houver, inicia imediatamente com o request CLEAN automático, sem esperar mais.
     */
    private fun startWithFixedSensorCadence(
        session: CameraCaptureSession,
        autoRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        val locked = AtomicBoolean(false)
        val completed = AtomicInteger(0)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || locked.get() || completed.incrementAndGet() < 3) return
                if (!locked.compareAndSet(false, true)) return

                val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val plan = if (exposureNs != null && sensitivityIso != null) {
                    fixedCadencePlan(profile, exposureNs, sensitivityIso)
                } else null

                val fixedRequest = plan?.let { buildFixedCadenceRequest(profile, it) }
                runCatching {
                    captureSession.setRepeatingRequest(fixedRequest ?: autoRequest, null, mainHandler)
                }
            }
        }

        session.setRepeatingRequest(autoRequest, callback, mainHandler)
        commitRecorderStart(profile, token, highSpeed = false)
    }

    private fun buildFixedCadenceRequest(
        profile: CameraProfile,
        plan: SensorCadencePolicy.Plan
    ): CaptureRequest? {
        val camera = synchronized(resourceLock) { cameraDevice } ?: return null
        val surface = synchronized(resourceLock) { recorderSurface } ?: return null
        return createRecordRequestBuilder(camera).apply {
            addTarget(surface)
            configureCaptureRequest(this, profile, plan)
            applyFinalWhiteBalance(this, profile)
        }.build()
    }

    private fun fixedCadencePlan(
        profile: CameraProfile,
        exposureTimeNs: Long,
        sensitivityIso: Int
    ): SensorCadencePolicy.Plan? {
        val exposureRange = profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val sensitivityRange = profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val maxFrameDuration = profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
        return SensorCadencePolicy.resolve(
            fps = profile.targetFps,
            observedExposureNs = exposureTimeNs,
            observedSensitivityIso = sensitivityIso,
            exposureMinNs = exposureRange.lower,
            exposureMaxNs = exposureRange.upper,
            sensitivityMinIso = sensitivityRange.lower,
            sensitivityMaxIso = sensitivityRange.upper,
            maxFrameDurationNs = maxFrameDuration,
            manualSensorSupported = supportsManualSensor(profile)
        )
    }

    private fun armRecorderForFirstFrame(token: Int) {
        if (!isAttemptValid(token)) return
        synchronized(resourceLock) {
            if (recorderArmed) return
            professionalRecorder?.arm()
                ?: throw IllegalStateException("encoder profissional indisponível")
            recorderArmed = true
        }
    }

    private fun commitRecorderStart(profile: CameraProfile, token: Int, highSpeed: Boolean) {
        if (!isAttemptValid(token)) return
        val backend = synchronized(resourceLock) {
            if (recorderStarted) return
            check(recorderArmed) { "encoder não foi armado antes da câmera" }
            professionalRecorder ?: throw IllegalStateException("encoder profissional indisponível")
        }

        val startFailure = runCatching {
            synchronized(resourceLock) {
                if (!isAttemptValid(token)) return
                if (professionalRecorder !== backend) return
                backend.commitStart()
                recorderStarted = true
                recordingStartedAtMs = System.currentTimeMillis()
                recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
            }
        }.exceptionOrNull()

        if (startFailure != null) throw startFailure

        logRecordingStartup(profile)
        logFpsFallbackIfNeeded(profile)
        CaptureStateStore.updateEffectiveMode(this, resolutionValue(profile.videoSize), sizeName(profile.videoSize), profile.targetFps)
        if (recordingSettings.vibrateStartStop) Haptics.start(this)

        val suffix = if (highSpeed) "high-speed • MediaRecorder direto" else "MediaRecorder direto • ${stabilizationName(profile)}"
        val fpsStatus = "${profile.targetFps} FPS • AE ${profile.fpsRange.lower}–${profile.fpsRange.upper}"
        val message = "Gravando ${sizeName(profile.videoSize)} • $fpsStatus • " +
            "${if (profile.hdrHlg10) "HLG10" else "SDR BT.709"} • ${encoderName()} • $suffix"
        sendStateOnMain(message)
        updateNotificationOnMain(message)
    }


    private fun createRecordRequestBuilder(
        camera: CameraDevice
    ): CaptureRequest.Builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

    private fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile,
        manualCadence: SensorCadencePolicy.Plan? = null
    ) {
        if (profile.highSpeed) {
            configureConstrainedHighSpeedRequest(builder, profile)
            return
        }
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualCadence == null) {
            setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
            setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        } else {
            setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            setSafely(builder, CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs)
            setSafely(builder, CaptureRequest.SENSOR_EXPOSURE_TIME, manualCadence.exposureTimeNs)
            setSafely(builder, CaptureRequest.SENSOR_SENSITIVITY, manualCadence.sensitivityIso)
        }
        setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val requestedAf = when {
            // Sem preview, a gravação começa assim que a sessão fica pronta. O foco contínuo
            // corrige a lente nos primeiros quadros sem atrasar o início, respeitando apenas
            // a escolha explícita de foco desligado.
            headlessCaptureRequested && recordingSettings.focusMode != CaptureSettings.FOCUS_OFF ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            // Sessões high-speed aceitam um conjunto bem menor de controles; o modo contínuo
            // de vídeo é o caminho mais estável quando o foco não foi desativado pelo usuário.
            profile.highSpeed && recordingSettings.focusMode != CaptureSettings.FOCUS_OFF ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            recordingSettings.focusMode == CaptureSettings.FOCUS_CONTINUOUS_PICTURE ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            recordingSettings.focusMode == CaptureSettings.FOCUS_AUTO ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            recordingSettings.focusMode == CaptureSettings.FOCUS_OFF ->
                CameraMetadata.CONTROL_AF_MODE_OFF
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        val fallbackAf = listOf(
            requestedAf,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_OFF
        ).distinct().firstOrNull { afModes.contains(it) }
        fallbackAf?.let { setSafely(builder, CaptureRequest.CONTROL_AF_MODE, it) }

        if (!profile.highSpeed) {
            setSafely(builder, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            if (fallbackAf == CameraMetadata.CONTROL_AF_MODE_OFF) {
                // AF desligado sem distância manual configurável significa foco fixo no infinito.
                setSafely(builder, CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
        }

        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val requestedAwb = requestedAwbMode(recordingSettings.whiteBalanceMode)
        val fallbackAwb = listOf(requestedAwb, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
            .firstOrNull { awbModes.contains(it) }
        fallbackAwb?.let { setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, it) }
        setSafely(
            builder,
            CaptureRequest.COLOR_CORRECTION_MODE,
            if (profile.targetFps <= CaptureModeStore.FPS_30) {
                CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY
            } else {
                CameraMetadata.COLOR_CORRECTION_MODE_FAST
            }
        )

        val antibandingModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
        val requestedAntibanding = when (recordingSettings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(requestedAntibanding)) setSafely(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAntibanding)

        if (manualCadence == null) {
            val exposureRange = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            exposureRange?.let {
                setSafely(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, recordingSettings.exposureCompensation.coerceIn(it.lower, it.upper))
            }
        }

        CameraZoom.apply(builder, profile.characteristics, recordingSettings.zoomRatio)

        applyStabilization(builder, profile)
        if (profile.highSpeed) return

        val faceModes = profile.characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
        if (faceModes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)) setSafely(builder, CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
        setSafely(builder, CaptureRequest.CONTROL_ENABLE_ZSL, false)
        setSafely(builder, CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        val noiseModes = profile.characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val requestedNoise = when {
            profile.targetFps >= CaptureModeStore.FPS_60 -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            recordingSettings.noiseReduction == CaptureSettings.PROCESSING_OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            recordingSettings.noiseReduction == CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        }
        val noiseFallbackOrder = if (profile.targetFps >= CaptureModeStore.FPS_60) {
            listOf(
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            )
        } else {
            listOf(
                requestedNoise,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF
            )
        }
        noiseFallbackOrder.firstOrNull { noiseModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.NOISE_REDUCTION_MODE, it)
        }

        val edgeModes = profile.characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val requestedEdge = when {
            profile.targetFps >= CaptureModeStore.FPS_60 -> CameraMetadata.EDGE_MODE_OFF
            recordingSettings.edgeMode == CaptureSettings.PROCESSING_OFF -> CameraMetadata.EDGE_MODE_OFF
            recordingSettings.edgeMode == CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            recordingSettings.edgeMode == CaptureSettings.PROCESSING_AUTO && profile.targetFps <= CaptureModeStore.FPS_30 ->
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            else -> CameraMetadata.EDGE_MODE_FAST
        }
        val edgeFallbackOrder = if (profile.targetFps >= CaptureModeStore.FPS_60) {
            listOf(CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
        } else {
            listOf(requestedEdge, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_OFF)
        }
        edgeFallbackOrder
            .firstOrNull { edgeModes.contains(it) }?.let { setSafely(builder, CaptureRequest.EDGE_MODE, it) }

        val aberrationModes = profile.characteristics.get(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
        ) ?: intArrayOf()
        val preferredAberration = if (profile.targetFps <= CaptureModeStore.FPS_30) {
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
        }
        listOf(
            preferredAberration,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
        ).firstOrNull { aberrationModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it)
        }
        val distortionModes = profile.characteristics.get(
            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES
        ) ?: intArrayOf()
        val preferredDistortion = if (profile.targetFps <= CaptureModeStore.FPS_30) {
            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
        }
        listOf(
            preferredDistortion,
            CameraMetadata.DISTORTION_CORRECTION_MODE_FAST,
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
        ).firstOrNull { distortionModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.DISTORTION_CORRECTION_MODE, it)
        }

        val hotPixelModes = profile.characteristics.get(
            CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES
        ) ?: intArrayOf()
        val preferredHotPixel = if (profile.targetFps <= CaptureModeStore.FPS_30) {
            CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.HOT_PIXEL_MODE_OFF
        }
        listOf(
            preferredHotPixel,
            CameraMetadata.HOT_PIXEL_MODE_FAST,
            CameraMetadata.HOT_PIXEL_MODE_OFF
        ).firstOrNull { hotPixelModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.HOT_PIXEL_MODE, it)
        }

        val shadingModes = profile.characteristics.get(
            CameraCharacteristics.SHADING_AVAILABLE_MODES
        ) ?: intArrayOf()
        val preferredShading = if (profile.targetFps <= CaptureModeStore.FPS_30) {
            CameraMetadata.SHADING_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.SHADING_MODE_OFF
        }
        listOf(
            preferredShading,
            CameraMetadata.SHADING_MODE_FAST,
            CameraMetadata.SHADING_MODE_OFF
        ).firstOrNull { shadingModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.SHADING_MODE, it)
        }

        applyColorProfile(builder, profile)
    }

    /**
     * Constrained high-speed possui um conjunto de controles intencionalmente
     * reduzido. Não reaproveite o request regular: estabilização e pós-processamento
     * extra podem fazer a HAL Samsung aceitar a sessão mas quebrar a cadência.
     */
    private fun configureConstrainedHighSpeedRequest(
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

    private fun requestedAwbMode(value: String): Int = when (value) {
        CaptureSettings.WHITE_BALANCE_INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        CaptureSettings.WHITE_BALANCE_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        CaptureSettings.WHITE_BALANCE_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        CaptureSettings.WHITE_BALANCE_CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        CaptureSettings.WHITE_BALANCE_TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        CaptureSettings.WHITE_BALANCE_SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    private fun applyFinalWhiteBalance(builder: CaptureRequest.Builder, profile: CameraProfile) {
        if (CctWhiteBalanceController.applyIfSupported(builder, profile.characteristics, recordingSettings.yellowReduction, recordingSettings.whiteBalanceMode)) return
        val availableModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val selectedMode = requestedAwbMode(recordingSettings.whiteBalanceMode)
        if (availableModes.contains(selectedMode)) setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, selectedMode)

        val measured = Camera3AStateStore.recentWhiteBalance(profile.cameraId)
        val strength = WhiteBalanceCorrection.strength(recordingSettings.yellowReduction, recordingSettings.whiteBalanceMode, measured?.gains)
        val capabilities = profile.characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualPost = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val canManualCorrect = strength > 0f && manualPost && measured != null && availableModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)

        if (canManualCorrect) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_TRANSFORM, measured.transform)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_GAINS, WhiteBalanceCorrection.adjustedGains(measured.gains, strength))
            return
        }

        if (
            WhiteBalanceCorrection.useIncandescentFallback(strength) &&
            recordingSettings.whiteBalanceMode == CaptureSettings.WHITE_BALANCE_AUTO &&
            availableModes.contains(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
        ) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
            return
        }

        val canLockAwb = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        val shouldLockAwb = recordingSettings.lockWhiteBalance &&
            recordingSettings.whiteBalanceMode == CaptureSettings.WHITE_BALANCE_AUTO &&
            canLockAwb && measured != null
        setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, shouldLockAwb)
    }

    private fun applyColorProfile(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        if (profile.hdrHlg10 || profile.highSpeed) return
        val modes = profile.characteristics.get(
            CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES
        ) ?: intArrayOf()

        val wantsCurve = recordingSettings.colorProfile == CaptureSettings.COLOR_SOFT ||
            recordingSettings.colorProfile == CaptureSettings.COLOR_FLAT
        if (wantsCurve && modes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            val points = if (recordingSettings.colorProfile == CaptureSettings.COLOR_FLAT) {
                floatArrayOf(
                    0f, 0f,
                    0.10f, 0.17f,
                    0.28f, 0.35f,
                    0.50f, 0.50f,
                    0.72f, 0.65f,
                    0.90f, 0.83f,
                    1f, 1f
                )
            } else {
                floatArrayOf(
                    0f, 0f,
                    0.14f, 0.19f,
                    0.34f, 0.39f,
                    0.50f, 0.50f,
                    0.66f, 0.61f,
                    0.86f, 0.81f,
                    1f, 1f
                )
            }
            setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            setSafely(builder, CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
            return
        }

        if (profile.targetFps >= CaptureModeStore.FPS_60) {
            if (modes.contains(CameraMetadata.TONEMAP_MODE_FAST)) {
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
            return
        }

        when {
            profile.targetFps <= CaptureModeStore.FPS_30 &&
                modes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) ->
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
            modes.contains(CameraMetadata.TONEMAP_MODE_FAST) ->
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            modes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) ->
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }
    }

    private fun applyStabilization(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        // Regra fixa: o SteadyVault não substituirá a estabilização escolhida; tenta o modo
        // exato no CaptureRequest e só reporta incompatibilidade se a HAL real recusar.
        val requested = requestedStabilizationMode(profile)

        fun setVideoModeRequired(mode: Int) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
            check(builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE) == mode) {
                "a HAL recusou ${stabilizationModeLabel(requested)}"
            }
        }

        fun setOisRequired(enabled: Boolean) {
            check(OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled)) {
                if (enabled) "a HAL recusou OIS" else "a HAL não permitiu desligar OIS"
            }
        }

        when (requested) {
            RecordingStabilizationPolicy.Mode.OFF -> {
                setVideoModeRequired(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                // Se a HAL não expõe OIS, não transforme a ausência da chave em erro:
                // CONTROL_VIDEO_STABILIZATION_MODE_OFF continua sendo aplicado exatamente.
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
            RecordingStabilizationPolicy.Mode.PREVIEW -> {
                setVideoModeRequired(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                // Preview stabilization tem precedência sobre OIS em Camera2; desligar OIS
                // explicitamente é desejável, mas a ausência dessa chave não bloqueia o modo.
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
            RecordingStabilizationPolicy.Mode.EIS -> {
                setVideoModeRequired(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
            RecordingStabilizationPolicy.Mode.OIS -> {
                setVideoModeRequired(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                setOisRequired(enabled = true)
            }
        }
    }

    private fun requestedStabilizationMode(profile: CameraProfile): RecordingStabilizationPolicy.Mode {
        if (recordingSettings.stabilization == CaptureSettings.STABILIZATION_AUTO) {
            if (profile.highSpeed || profile.targetFps > CaptureModeStore.FPS_60) {
                return RecordingStabilizationPolicy.Mode.OFF
            }
            return when {
                profile.previewStabilizationSupported -> RecordingStabilizationPolicy.Mode.PREVIEW
                profile.eisSupported -> RecordingStabilizationPolicy.Mode.EIS
                profile.oisSupported -> RecordingStabilizationPolicy.Mode.OIS
                else -> RecordingStabilizationPolicy.Mode.OFF
            }
        }
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> RecordingStabilizationPolicy.Mode.PREVIEW
            CaptureSettings.STABILIZATION_EIS -> RecordingStabilizationPolicy.Mode.EIS
            CaptureSettings.STABILIZATION_OIS -> RecordingStabilizationPolicy.Mode.OIS
            else -> RecordingStabilizationPolicy.Mode.OFF
        }
    }

    private fun stabilizationModeLabel(mode: RecordingStabilizationPolicy.Mode): String = when (mode) {
        RecordingStabilizationPolicy.Mode.PREVIEW -> "Preview stabilization"
        RecordingStabilizationPolicy.Mode.EIS -> "EIS"
        RecordingStabilizationPolicy.Mode.OIS -> "OIS"
        RecordingStabilizationPolicy.Mode.OFF -> "estabilização desligada"
    }

    private fun <T> setSafely(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T
    ) {
        runCatching { builder.set(key, value) }
    }

    private fun stopCurrentRecording() {
        if (!serviceActive.get()) {
            stopSelf()
            return
        }

        userRequestedStop = true
        if (!stopping.compareAndSet(false, true)) return
        ++attemptToken

        if (!recorderStarted) {
            if (!stopHapticAcknowledged && recordingSettings.vibrateStartStop) Haptics.stop(this)
            sendState("Captura cancelada antes do primeiro quadro")
            updateNotification("Captura cancelada")
            cameraExecutor.execute {
                releaseRecordingResources(deleteOutput = true)
                sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))
                finishServiceOnMain()
            }
            return
        }

        sendState("Parando gravação…")
        updateNotification("Parando gravação…")

        cameraExecutor.execute {
            val session = synchronized(resourceLock) { captureSession }
            if (recorderStarted && session != null) runCatching { session.stopRepeating() }
            stopRecorderAndFinalize()
        }
    }

    private fun stopRecorderAndFinalize() {
        val claimed = claimRecording()
        val finalFile = claimed.output
        val recorder = claimed.recorder
        val validOutput = claimed.wasStarted && runCatching { recorder?.stop() == true }.getOrDefault(false)

        releaseCameraOnly()
        runCatching { recorder?.release() }
        sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))

        if (!validOutput || finalFile == null || !finalFile.isFile || finalFile.length() <= 0L) {
            if (finalFile != null && finalFile.isFile && finalFile.length() > 0L) {
                finishWithRecoveredPartial(finalFile, finalFile, "MediaRecorder não conseguiu finalizar o MP4 direto")
            } else {
                runCatching { finalFile?.delete() }
                sendStateOnMain("Nenhum quadro foi produzido antes da parada")
                finishServiceOnMain()
            }
            return
        }

        com.steadyvault.camera.storage.vault.VaultMediaIndex.invalidate()
        if (!stopHapticAcknowledged && recordingSettings.vibrateStartStop) Haptics.stop(this)

        val profile = selectedCamera
        val size = profile?.videoSize ?: recordingSettings.preferredSizes().first()
        val fps = profile?.targetFps ?: requestedTargetFps
        val durationSeconds = if (recordingStartedAtElapsedMs > 0L) {
            ((SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) / 1000L).coerceAtLeast(0L)
        } else 0L
        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10" else "SDR BT.709"
        // O original já está finalizado e indexado antes de entrar na fila. O reparo
        // nunca recebe permissão para substituir este arquivo.
        AutoGapRepairService.enqueue(this, finalFile, fps)
        val message = "Vídeo salvo no cofre • ${sizeName(size)} • $fps FPS • $qualityLabel • ${formatDuration(durationSeconds)}"
        sendStateOnMain(message)
        updateNotificationOnMain(message)
        finishServiceOnMain()
    }

    private fun hasHlgVideoTrack(file: File): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/")) continue
                val standard = runCatching {
                    format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                }.getOrNull()
                val transfer = runCatching {
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                }.getOrNull()
                return standard == MediaFormat.COLOR_STANDARD_BT2020 &&
                        transfer == MediaFormat.COLOR_TRANSFER_HLG
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun finishWithRecoveredPartial(
        rawFile: File,
        finalFile: File,
        reason: String
    ) {
        if (rawFile.absolutePath != finalFile.absolutePath) runCatching { finalFile.delete() }
        val preserved = RecordingRecoveryRepository.preserveInterrupted(this, rawFile)
        when {
            preserved != null && RecordingRecoveryRepository.hasUsableVideo(preserved) -> {
                if (!stopHapticAcknowledged && recordingSettings.vibrateStartStop) Haptics.stop(this)
                sendStateOnMain("Trecho preservado em Vídeos com erro / recuperados • $reason")
            }
            preserved != null -> {
                if (recordingSettings.vibrateStartStop) Haptics.error(this)
                sendStateOnMain("Arquivo interrompido preservado em Vídeos com erro / recuperados • $reason")
            }
            rawFile.isFile && rawFile.length() > 0L -> {
                if (recordingSettings.vibrateStartStop) Haptics.error(this)
                sendStateOnMain("Falha: $reason • temporário reservado para nova tentativa de recuperação")
            }
            else -> {
                if (recordingSettings.vibrateStartStop) Haptics.error(this)
                sendStateOnMain("Nenhum quadro recuperável foi produzido antes da interrupção")
            }
        }
        finishServiceOnMain()
    }

    private fun releaseRecordingResources(deleteOutput: Boolean) {
        releaseCameraOnly()
        releaseRecorderAndOutput(deleteOutput)
    }

    private fun releaseCameraOnly() {
        synchronized(resourceLock) {
            runCatching { captureSession?.close() }
            captureSession = null

            runCatching { cameraDevice?.close() }
            cameraDevice = null
            headlessSessionStartToken = -1
        }
    }

    private fun releaseRecorderOnly() {
        synchronized(resourceLock) {
            recorderSurface = null
            runCatching { professionalRecorder?.release() }
            professionalRecorder = null
            recorderArmed = false
        }
    }

    private fun releaseRecorderAndOutput(
        deleteOutput: Boolean,
        deleteRaw: Boolean = true
    ) {
        val output: File?
        val rawFile: File?
        releaseRecorderOnly()
        synchronized(resourceLock) {
            output = finalOutputFile
            finalOutputFile = null
            rawFile = rawOutputFile
            rawOutputFile = null
        }
        if (deleteRaw) runCatching { rawFile?.delete() }
        if (deleteOutput) runCatching { output?.delete() }
    }

    private fun claimRecording(): ClaimedRecording = synchronized(resourceLock) {
        ClaimedRecording(
            recorder = professionalRecorder,
            output = finalOutputFile,
            raw = rawOutputFile,
            wasStarted = recorderStarted
        ).also {
            professionalRecorder = null
            recorderSurface = null
            finalOutputFile = null
            rawOutputFile = null
            recorderStarted = false
            recorderArmed = false
        }
    }

    /**
     * Fecha o muxer e publica os quadros já gravados quando o Android destrói o
     * serviço ou quando ocorre uma falha fatal fora do botão Parar. O arquivo
     * temporário nunca é descartado antes da tentativa de recuperação.
     */
    private fun preserveInterruptedRecording(): Boolean {
        releaseCameraOnly()
        val claimed = claimRecording()
        if (claimed.wasStarted) runCatching { claimed.recorder?.stop() }
        runCatching { claimed.recorder?.release() }
        val file = claimed.output ?: claimed.raw ?: return false
        if (!file.isFile || file.length() <= 0L) return false

        return if (RecordingRecoveryRepository.hasUsableVideo(file)) {
            com.steadyvault.camera.storage.vault.VaultMediaIndex.invalidate()
            true
        } else {
            RecordingRecoveryRepository.preserveInterrupted(this, file) != null
        }
    }

    private fun failAndStop(message: String) {
        if (!serviceActive.get()) return
        AppLogRepository.error(this, "recording", message)
        userRequestedStop = true
        stopping.set(true)

        cameraExecutor.execute {
            val preserved = if (recorderStarted) {
                preserveInterruptedRecording()
            } else {
                releaseRecordingResources(deleteOutput = true)
                false
            }
            val finalMessage = if (preserved) {
                "Falha: $message • trecho preservado no cofre"
            } else {
                "Falha: $message"
            }
            sendStateOnMain(finalMessage)
            updateNotificationOnMain(finalMessage)
            finishServiceOnMain()
        }
    }

    private fun failAndStopFromWorker(message: String) {
        if (!serviceActive.get()) return
        AppLogRepository.error(this, "recording", message)
        userRequestedStop = true
        stopping.set(true)
        val preserved = if (recorderStarted) {
            preserveInterruptedRecording()
        } else {
            releaseRecordingResources(deleteOutput = true)
            false
        }
        val finalMessage = if (preserved) {
            "Falha: $message • trecho preservado no cofre"
        } else {
            "Falha: $message"
        }
        sendStateOnMain(finalMessage)
        updateNotificationOnMain(finalMessage)
        finishServiceOnMain()
    }

    private fun finishServiceOnMain() {
        mainHandler.post { finishService() }
    }

    private fun finishService() {
        if (!serviceActive.compareAndSet(true, false)) return

        attemptToken++
        stopping.set(false)
        recorderStarted = false
        CaptureStateStore.completeSessionIfBusy(this, captureSessionId)
        WidgetRenderer.forceRecordingControls(this)
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        releaseWakeLock()
        AutoGapRepairService.resumeAfterCapture(this)
        if (deferredRawCleanup) {
            deferredRawCleanup = false
            cleanupOldRawFilesAsync()
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }

        foregroundNotificationStarted = false
        stopSelf()
    }

    private fun hasRequiredPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


    private fun isAttemptValid(token: Int): Boolean =
        token == attemptToken &&
                serviceActive.get() &&
                !userRequestedStop

    private fun startForegroundNow(text: String, includeMicrophone: Boolean = true) {
        val notification = buildNotification(text)
        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(NOTIFICATION_ID, notification, serviceType)
        foregroundNotificationStarted = true
    }

    private fun isForegroundPermissionRace(throwable: Throwable): Boolean =
        throwable is SecurityException &&
            (throwable.message?.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE, ignoreCase = true) == true ||
                throwable.message?.contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA, ignoreCase = true) == true ||
                throwable.message?.contains(Manifest.permission.RECORD_AUDIO, ignoreCase = true) == true)

    /**
     * Inicia imediatamente como FGS de câmera. O Android 16 pode manter o AppOp do
     * microfone indisponível por uma janela curta no primeiro start.
     */
    private fun startForegroundCaptureFast(text: String) {
        if (!hasAudioPermission()) {
            startForegroundCameraOnly(text)
            return
        }
        try {
            startForegroundNow(text, includeMicrophone = true)
        } catch (throwable: Throwable) {
            if (!isForegroundPermissionRace(throwable)) throw throwable
            startForegroundCameraOnly(text)
        }
    }

    private fun startForegroundCameraOnly(text: String) {
        startForegroundNow(text, includeMicrophone = false)
    }


    private fun abortBeforeCaptureStart() {
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
        AutoGapRepairService.resumeAfterCapture(this)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundNotificationStarted = false
        serviceActive.set(false)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        if (!foregroundNotificationStarted) {
            mainHandler.post {
                if (serviceActive.get()) {
                    runCatching { startForegroundNow(text) }
                }
            }
            return
        }

        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun updateNotificationOnMain(text: String) {
        mainHandler.post {
            if (serviceActive.get()) runCatching { updateNotification(text) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, CaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_USER_REQUESTED_STOP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val compactText = when {
            text.contains("falha", ignoreCase = true) -> "Falha na gravação"
            text.contains("final", ignoreCase = true) ||
                    text.contains("interval", ignoreCase = true) ||
                    text.contains("salv", ignoreCase = true) -> "Finalizando vídeo"
            text.contains("gravando", ignoreCase = true) -> "Atividade em andamento"
            else -> "Preparando atividade"
        }

        val identity = VisualIdentityStore.notificationIdentity(this)
        val builder = Notification.Builder(this, CHANNEL_ID)

        builder
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Gravação"))
            .setContentText(VisualIdentityStore.notificationText(this, compactText))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setLocalOnly(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, identity.cancelIcon),
                    VisualIdentityStore.actionLabel(this, "Parar e salvar", "Concluir"),
                    stopIntent
                ).build()
            )

        builder.setColorized(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_2)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gravação discreta",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificação silenciosa enquanto a câmera grava em segundo plano"
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = null
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        releaseWakeLock()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SteadyVault:Camera2"
            )
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }
        wakeLock = null
    }

    private fun fpsModeName(
        profile: CameraProfile
    ): String =
        if (
            profile.fpsRange.lower ==
            profile.targetFps &&
            profile.fpsRange.upper ==
            profile.targetFps
        ) {
            "${profile.targetFps}–${profile.targetFps} fps"
        } else {
            "${profile.fpsRange.lower}–" +
                    "${profile.fpsRange.upper} fps"
        }

    private fun stabilizationName(profile: CameraProfile): String {
        if (profile.highSpeed) return "sem estabilização (high-speed)"
        if (recordingSettings.stabilization == CaptureSettings.STABILIZATION_AUTO) {
            return "auto → ${stabilizationModeLabel(requestedStabilizationMode(profile))}"
        }
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
            CaptureSettings.STABILIZATION_EIS -> "EIS"
            CaptureSettings.STABILIZATION_OIS -> "OIS"
            CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
            else -> "estabilização inválida"
        }
    }

    private fun sizeName(
        size: Size
    ): String =
        when (size) {
            EIGHT_K_SIZE ->
                "8K UHD"

            UHD_SIZE ->
                "4K UHD"

            FHD_SIZE ->
                "1080p"

            HD_SIZE ->
                "720p"

            else ->
                "${size.width}×${size.height}"
        }

    private fun resolutionValue(size: Size): String = when (size) {
        EIGHT_K_SIZE -> CaptureSettings.RESOLUTION_8K
        UHD_SIZE -> CaptureSettings.RESOLUTION_4K
        FHD_SIZE -> CaptureSettings.RESOLUTION_1080P
        HD_SIZE -> CaptureSettings.RESOLUTION_720P
        else -> recordingSettings.resolution
    }

    private fun frameDurationNs(
        fps: Int
    ): Long =
        1_000_000_000L /
                fps.coerceAtLeast(1)

    private fun encoderName(): String =
        when {
            activeRecorderBackendName.startsWith("OEM MediaRecorder") && activeRecorderMime == MediaFormat.MIMETYPE_VIDEO_HEVC ->
                "OEM HEVC (EncoderProfiles)"
            activeRecorderBackendName.startsWith("OEM MediaRecorder") && activeRecorderMime == MediaFormat.MIMETYPE_VIDEO_AVC ->
                "OEM H.264 (EncoderProfiles)"
            activeRecorderBackendName.startsWith("OEM MediaRecorder") ->
                "OEM EncoderProfiles"
            selectedEncoder?.hdrHlg10 == true -> "HEVC Main10 HLG"
            selectedEncoder?.mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
            selectedEncoder?.mime == MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            else -> "encoder"
        }

    private fun sendState(message: String) {
        currentState = message
        val phase = capturePhaseFor(message)
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_PHASE, phase.name)
                .putExtra(EXTRA_SESSION_ID, captureSessionId)
                .putExtra(EXTRA_STATE_OWNER, "video")
                .putExtra(EXTRA_STARTED_AT_ELAPSED, recordingStartedAtElapsedMs)
        )
    }

    private fun capturePhaseFor(message: String): CapturePhase {
        val inferred = CaptureStateStore.phaseForMessage(message)
        return when {
            inferred == CapturePhase.IDLE || inferred == CapturePhase.FAILED -> inferred
            inferred == CapturePhase.FINALIZING || inferred == CapturePhase.RECOVERING -> inferred
            recorderStarted -> CapturePhase.RECORDING
            stopping.get() || userRequestedStop -> CapturePhase.FINALIZING
            serviceActive.get() -> CapturePhase.PREPARING
            else -> inferred
        }
    }

    private fun sendStateOnMain(message: String) {
        mainHandler.post { sendState(message) }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun errorText(throwable: Throwable): String =
        throwable.message
            ?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName

    override fun onDestroy() {
        val interruptedBySystem =
            serviceActive.get() &&
                recorderStarted &&
                !userRequestedStop
        val hasPendingRecording = !userRequestedStop && synchronized(resourceLock) {
            rawOutputFile?.let { it.isFile && it.length() > 0L } == true
        }

        serviceActive.set(false)
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        attemptToken++
        userRequestedStop = true
        stopping.set(true)
        val preserved = if (interruptedBySystem || hasPendingRecording) {
            preserveInterruptedRecording()
        } else {
            releaseRecordingResources(deleteOutput = recorderStarted)
            false
        }
        if (interruptedBySystem) {
            sendState(
                if (preserved) {
                    "Interrupção do sistema • trecho preservado no cofre • retomando gravação…"
                } else {
                    "Interrupção do sistema • retomando gravação…"
                }
            )
        }
        releaseWakeLock()

        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        if (::encoderPreparationExecutor.isInitialized && !encoderPreparationExecutor.isShutdown) {
            encoderPreparationExecutor.shutdownNow()
        }
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
        super.onDestroy()
    }

    private fun logRecordingStartup(profile: CameraProfile) {
        val requestedAt = recordingRequestedAtElapsedNs
        if (requestedAt <= 0L) return
        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - requestedAt) / 1_000_000L).coerceAtLeast(0L)
        val map = profile.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val minFrameDurationNs = map?.let {
            encoderSurfaceMinFrameDurationNs(it, profile.videoSize, preferMediaRecorder = true)
        } ?: 0L
        val cadence = cadenceConfidence(profile)
        val cadenceMessage = "Gravação ${sizeName(profile.videoSize)} ${profile.targetFps} FPS iniciada em ${elapsedMs} ms • " +
            "cadênciaHAL=$cadence • minFrame=${if (minFrameDurationNs > 0L) minFrameDurationNs / 1_000_000.0 else -1.0}ms • câmera=${profile.cameraId}"
        Log.i(LOG_TAG, cadenceMessage)
        AppLogRepository.info(this, "recording_fps", cadenceMessage)
    }

    private fun logFpsFallbackIfNeeded(profile: CameraProfile) {
        if (fpsFallbackWarningLogged) return
        val backend = synchronized(resourceLock) { professionalRecorder }
        val info = buildString {
            append("FPS exato ativo: solicitado=").append(requestedTargetFps)
            append(", efetivo=").append(profile.targetFps)
            append(", faixaAE=").append(profile.fpsRange.lower).append('-').append(profile.fpsRange.upper)
            append(", câmera=").append(profile.cameraId)
            append(", resolução=").append(profile.videoSize.width).append('x').append(profile.videoSize.height)
            append(", backend=MediaRecorder direto")
        }
        fpsFallbackWarningLogged = true
        Log.i(LOG_TAG, info)
        AppLogRepository.info(this, "recording_fps", info)
    }

    private fun requestedProfileLabel(): String =
        "${CaptureSettings.resolutionLabel(recordingSettings.resolution)} ${requestedTargetFps} FPS"

    companion object {
        const val ACTION_RECORDING_VISUAL_FINISHED =
            "com.steadyvault.camera.RECORDING_VISUAL_FINISHED"

        const val ACTION_START =
            "com.steadyvault.camera.START"
        const val ACTION_STOP =
            "com.steadyvault.camera.STOP"
        const val ACTION_STATE =
            "com.steadyvault.camera.STATE"
        const val EXTRA_MESSAGE =
            "message"
        const val EXTRA_PHASE =
            "phase"
        const val EXTRA_SESSION_ID =
            "session_id"
        const val EXTRA_STATE_OWNER =
            "state_owner"
        const val EXTRA_STARTED_AT_ELAPSED =
            "started_at_elapsed"
        const val EXTRA_TARGET_FPS =
            "target_fps"
        const val EXTRA_FROM_PREVIEW =
            "from_preview"
        const val EXTRA_HEADLESS_CAPTURE =
            "headless_capture"
        const val EXTRA_PREFERRED_CAMERA_ID =
            "preferred_camera_id"
        const val EXTRA_USER_REQUESTED_STOP =
            "user_requested_stop"
        const val EXTRA_STOP_HAPTIC_ACKNOWLEDGED =
            "stop_haptic_acknowledged"
        const val EXTRA_REQUESTED_AT_ELAPSED_NS =
            "requested_at_elapsed_ns"

        private const val LOG_TAG = "SteadyVaultCapture"
        private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-clean-1.8.257"
        private const val CONFIG_CACHE_PREFS = "steadyvault_capture_fast_start"
        private const val CONFIG_SIGNATURE = "signature"
        private const val CONFIG_CAMERA_ID = "camera_id"
        private const val CONFIG_WIDTH = "width"
        private const val CONFIG_HEIGHT = "height"
        private const val CONFIG_FPS = "fps"
        private const val CONFIG_HIGH_SPEED = "high_speed"
        private const val CONFIG_DYNAMIC_RANGE = "dynamic_range"
        private const val CONFIG_MIME = "mime"

        @Volatile
        private var cachedConfiguration: CachedConfiguration? = null

        private const val CHANNEL_ID =
            "steadyvault_recording_controls_v2"
        private const val LEGACY_CHANNEL_ID =
            "steadyvault_recording_silent"
        private const val LEGACY_CHANNEL_ID_2 =
            "steadyvault_camera_high_speed"
        private const val NOTIFICATION_ID =
            7

        private val EIGHT_K_SIZE =
            Size(7680, 4320)
        private val UHD_SIZE =
            Size(3840, 2160)
        private val FHD_SIZE =
            Size(1920, 1080)
        private val HD_SIZE =
            Size(1280, 720)

        private const val MAX_VIDEO_BITRATE =
            240_000_000
        private const val FRAME_DURATION_TOLERANCE_NS =
            50_000L

        private const val HIGH_FPS_FRAME_TOLERANCE_NS = 500_000L

        private const val RAW_FILE_PREFIX = "steadyvault_raw_"
        private const val STALE_RAW_FILE_MIN_AGE_MS = 60_000L

    }
}
