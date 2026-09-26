package com.steadyvault.camera.capture.service

import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.capture.recorder.RecordingBackend
import com.steadyvault.camera.capture.recorder.DirectMediaRecorderBackend
import com.steadyvault.camera.capture.recorder.DirectMediaCodecBackend
import com.steadyvault.camera.capture.timing.RecordingStabilizationPolicy
import com.steadyvault.camera.capture.timing.CaptureCadencePolicy
import com.steadyvault.camera.capture.timing.StrictCaptureModePolicy
import com.steadyvault.camera.capture.timing.SensorCadencePolicy

import com.steadyvault.camera.core.feedback.Haptics
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
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import com.steadyvault.camera.processing.service.VideoProcessingService
import com.steadyvault.camera.storage.vault.RecordingRecoveryRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.capture.CameraPreviewRegistry
import com.steadyvault.camera.widgets.WidgetRenderer

import com.steadyvault.camera.core.settings.VisualIdentityStore

import android.Manifest
import android.app.ActivityManager
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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
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

    @Volatile
    private var cameraRecoveryAttempt = 0

    private var fpsFallbackWarningLogged = false
    private var activeRecorderBackendName = ""
    private var activeRecorderMime: String? = null

    @Volatile
    private var hybridAeBaselineExposureProduct = 0.0

    @Volatile
    private var hybridAeProbeInFlight = false

    @Volatile
    private var foregroundNotificationStarted = false

    // Diagnóstico temporário 60+ FPS: correlaciona gaps reais do sensor com
    // autofocus/lente sem alterar o comportamento da captura.
    @Volatile private var cadenceDiagPreviousSensorTsNs = 0L
    @Volatile private var cadenceDiagPreviousFrameNumber = -1L
    @Volatile private var cadenceDiagPreviousAfState: Int? = null
    @Volatile private var cadenceDiagPreviousLensState: Int? = null
    @Volatile private var cadenceDiagGapCount = 0
    @Volatile private var cadenceDiagAfScanTransitions = 0
    @Volatile private var cadenceDiagLensMovingFrames = 0
    @Volatile private var cadenceDiagCpuStartMs = 0L
    @Volatile private var cadenceDiagWallStartMs = 0L
    @Volatile private var vendorResultDumped = false




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
        // Camera/encoder always win over any background transcode. Both calls set
        // an in-process cancellation flag before the service IPC, so preparation of
        // the next recording naturally gives GPU/codec work time to unwind.
        AutoGapRepairService.pauseForCapture(this)
        VideoProcessingService.pauseForCapture(this)
        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)
        MediaThumbnailRepository.prepareForCapture()
        val repairReleased = AutoGapRepairService.awaitReleasedForCapture()
        Log.i(LOG_TAG, "CAPTURE_ISOLATION: repairReleased=$repairReleased processingPaused=true thumbnailsPaused=true startupPaused=true")
        captureSessionId = "video-${System.currentTimeMillis()}-${SystemClock.elapsedRealtimeNanos()}"
        resetCadenceFocusDiagnostics()

        recordingRequestedAtElapsedNs = intent?.getLongExtra(EXTRA_REQUESTED_AT_ELAPSED_NS, 0L)?.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
        cameraRecoveryAttempt = intent?.getIntExtra(EXTRA_CAMERA_RECOVERY_ATTEMPT, 0)?.coerceAtLeast(0) ?: 0
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

        if (recordingSettings.thermalProtection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalStatus = getSystemService(PowerManager::class.java).currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                failAndStop("temperatura do aparelho está muito alta para iniciar com proteção térmica ativa")
                return
            }
        }

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
        hybridAeBaselineExposureProduct = 0.0
        hybridAeProbeInFlight = false
        recordingStartedAtMs = 0L
        recordingStartedAtElapsedMs = 0L
        fpsFallbackWarningLogged = false
        foregroundNotificationStarted = false
        synchronized(resourceLock) { headlessSessionStartToken = -1 }
        attemptToken++
        val token = attemptToken

        if (!hasRequiredPermissions()) {
            sendState("Falha: permissões de câmera e microfone são obrigatórias para gravar com áudio")
            serviceActive.set(false)
            AutoGapRepairService.resumeAfterCapture(this)
            VideoProcessingService.resumeAfterCapture()
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
            // App, preview, widget e atalhos usam exatamente a mesma sequência:
            // 1) prepara encoder/áudio; 2) assume a câmera; 3) abre a sessão;
            // 4) faz o warm-up 3A; 5) instala o request final estático.
            // A origem só muda a UI, nunca a captura efetiva.
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
        if (!recorderStarted) {
            failSelectedConfigurationFromWorker(token, reason)
            return
        }
        recoverActiveRecordingFromCameraInterruption(reason)
    }

    /**
     * Alguns HALs OEM podem entregar onDisconnected/onError durante transições
     * repetidas de lockscreen. Não trate isso como um "Parar" do usuário.
     *
     * Fecha o segmento atual de modo recuperável e reinicia a mesma captura dentro
     * do FGS. O usuário pode acabar com mais de um segmento, mas não perde o trecho
     * já gravado e a sessão continua sem depender de Activity/tela ligada.
     */
    private fun recoverActiveRecordingFromCameraInterruption(reason: String) {
        if (!serviceActive.get() || userRequestedStop) return

        val nextAttempt = cameraRecoveryAttempt + 1
        if (nextAttempt > MAX_CAMERA_RECOVERY_ATTEMPTS) {
            failAndStopFromWorker(
                "$reason • limite de recuperação automática atingido"
            )
            return
        }

        stopping.set(true)
        val preserved = preserveInterruptedRecording()
        CameraResourceCoordinator.releaseCapture(
            CameraResourceCoordinator.Owner.VIDEO,
            cameraLeaseToken
        )
        attemptToken++

        val message = buildString {
            append("Câmera interrompida • ")
            if (preserved) append("trecho preservado • ")
            append("retomando gravação ")
            append(nextAttempt)
            append("/")
            append(MAX_CAMERA_RECOVERY_ATTEMPTS)
        }
        sendStateOnMain(message)
        updateNotificationOnMain(message)

        val restartIntent = Intent(this, CaptureService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_FPS, requestedTargetFps)
            .putExtra(EXTRA_FROM_PREVIEW, false)
            .putExtra(EXTRA_HEADLESS_CAPTURE, headlessCaptureRequested)
            .putExtra(EXTRA_CAMERA_RECOVERY_ATTEMPT, nextAttempt)
            .putExtra(EXTRA_REQUESTED_AT_ELAPSED_NS, SystemClock.elapsedRealtimeNanos())
            .apply {
                preferredCameraId?.takeIf { it.isNotBlank() }?.let {
                    putExtra(EXTRA_PREFERRED_CAMERA_ID, it)
                }
            }

        serviceActive.set(false)
        stopping.set(false)

        mainHandler.postDelayed(
            {
                if (userRequestedStop) return@postDelayed
                runCatching { startForegroundService(restartIntent) }
                    .onFailure { failure ->
                        val message =
                            "Falha: não foi possível retomar após interrupção da câmera: ${errorText(failure)}"
                        sendState(message)
                        updateNotification(message)
                        releaseWakeLock()
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        foregroundNotificationStarted = false
                        stopSelf()
                    }
            },
            CAMERA_RECOVERY_DELAY_MS
        )
    }

    private fun resolveCaptureResolutionForFps(
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
        val rememberedHighSpeed = prefs.getBoolean(CONFIG_HIGH_SPEED, false)
        val cameraId = prefs.getString(CONFIG_CAMERA_ID, null) ?: return null
        val width = prefs.getInt(CONFIG_WIDTH, 0)
        val height = prefs.getInt(CONFIG_HEIGHT, 0)
        val fps = prefs.getInt(CONFIG_FPS, 0)
        val storedDynamicRange = prefs.getLong(CONFIG_DYNAMIC_RANGE, standardDynamicRangeProfile())
        val mime = prefs.getString(CONFIG_MIME, null) ?: return null
        if (width <= 0 || height <= 0 || fps <= 0 || fps !in CaptureSettings.supportedFpsValues || mime !in recordingSettings.codecMimes(allowHdr)) return null
        val rememberedSize = Size(width, height)
        if (fps != targetFps || recordingSettings.exactPreferredSize()?.let { it != rememberedSize } == true) {
            getSharedPreferences(CONFIG_CACHE_PREFS, MODE_PRIVATE).edit().clear().apply()
            return null
        }

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return null
        val fpsRange =
            if (rememberedHighSpeed) {
                constrainedHighSpeedRange(characteristics, rememberedSize, fps)
            } else {
                resolveFpsRange(characteristics, rememberedSize, fps, false)
            } ?: return null
        val dynamicRange = when {
            allowHdr && supportsHlg10(characteristics) -> storedDynamicRange
            allowHdr -> return null
            else -> standardDynamicRangeProfile()
        }
        val profile = createCameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = rememberedSize,
            targetFps = fps,
            fpsRange = fpsRange,
            highSpeed = rememberedHighSpeed,
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
        allowHdr,
        recordingSettings.stabilization,
        recordingSettings.focusMode,
        recordingSettings.autoFpsLowLight,
        recordingSettings.lockAeAwbForCadence,
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
        val size = recordingSettings.exactPreferredSize() ?: return null
        val highSpeedRange =
            if (!allowHdr && targetFps >= CaptureModeStore.FPS_60) {
                constrainedHighSpeedRange(characteristics, size, targetFps)
            } else {
                null
            }
        val profile = createCameraProfile(
            cameraId = cameraId,
            characteristics = characteristics,
            videoSize = size,
            targetFps = targetFps,
            fpsRange = highSpeedRange ?: resolveStandardFpsRange(characteristics, targetFps),
            highSpeed = highSpeedRange != null,
            dynamicRangeProfile = dynamicRange
        )
        Log.i(
            LOG_TAG,
            "Modo de sessão selecionado: ${if (profile.highSpeed) "HIGH_SPEED" else "REGULAR"} " +
                "${size.width}x${size.height} ${profile.fpsRange.lower}-${profile.fpsRange.upper} FPS"
        )
        if (!matchesRequestedMode(profile, targetFps)) return null
        if (!profileSatisfiesExplicitStabilization(profile)) return null
        val encoder = selectDirectRecorderEncoder(profile) ?: return null
        return profile to encoder
    }

    private fun constrainedHighSpeedRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int
    ): Range<Int>? {
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null

        val sizes = runCatching {
            map.highSpeedVideoSizes?.toList().orEmpty()
        }.getOrDefault(emptyList())

        if (size !in sizes) {
            Log.i(
                LOG_TAG,
                "HIGH_SPEED_CAP: ${size.width}x${size.height} não anunciado; " +
                    "sizes=" + sizes.joinToString { "${it.width}x${it.height}" }
            )
            return null
        }

        val ranges = runCatching {
            map.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        Log.i(
            LOG_TAG,
            "HIGH_SPEED_CAP: ${size.width}x${size.height} ranges=" +
                ranges.joinToString { "${it.lower}-${it.upper}" }
        )

        return ranges.firstOrNull {
            it.lower == targetFps && it.upper == targetFps
        }
    }

    private fun resolveFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int,
        highSpeed: Boolean
    ): Range<Int>? {
        if (highSpeed) return null
        return resolveStandardFpsRange(characteristics, targetFps)
    }

    /**
     * Quando "qualidade em pouca luz" está ativa, mantém o FPS escolhido como teto
     * e entrega à HAL a faixa variável mais ampla anunciada pelo próprio aparelho.
     * Isso permite aumentar exposição em cenas escuras. O pós-processamento é quem
     * volta a saída para cadência regular depois.
     *
     * Limitamos o piso a 15 FPS para não aceitar exposições tão longas que criem
     * motion blur impossível de reconstruir de forma convincente.
     */
    /**
     * Quando o usuário deixa compensação em 0, aplicamos uma pequena proteção
     * automática de altas luzes. Em 60 FPS ela também empurra o AE para uma
     * exposição um pouco mais curta, ajudando a reduzir motion blur sem desligar
     * AE/ISO automáticos. Qualquer valor manual diferente de zero sempre prevalece.
     */
    private fun effectiveExposureCompensation(profile: CameraProfile): Int {
        if (recordingSettings.exposureCompensation != 0) {
            return recordingSettings.exposureCompensation
        }

        // AE/ISO continuam 100% automáticos. Quando o usuário deixa em 0, aplicamos
        // apenas um alvo de brilho levemente mais alto (+0,5 EV), mais próximo do
        // look da câmera Samsung sem congelar exposição ou sensibilidade.
        val step = profile.characteristics
            .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
            ?.takeIf { it > 0f }
            ?: return 0

        return kotlin.math.round(0.5f / step).toInt()
    }

    private fun resolveStandardFpsRange(
        characteristics: CameraCharacteristics,
        targetFps: Int
    ): Range<Int> {
        @Suppress("UNUSED_VARIABLE")
        val ignoredCharacteristics = characteristics
        return Range(targetFps, targetFps)
    }

    /**
     * FPS selecionado é contrato exato. Nenhuma faixa variável é aceita: 30 usa
     * [30,30] e 60 usa [60,60].
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
            allowHdr = allowHdr
        )

    private fun CameraProfile.hasExactFpsRange(): Boolean =
        fpsRange.lower == targetFps && fpsRange.upper == targetFps

    private fun CameraProfile.matchesRequestedFpsContract(): Boolean =
        hasExactFpsRange()


    private fun Size.toPolicyDimensions() = StrictCaptureModePolicy.Dimensions(width, height)

    /** A gravação usa somente Surface do MediaRecorder; não há MediaCodec/MediaMuxer manual. */
    private fun encoderSurfaceMinFrameDurationNs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        preferMediaRecorder: Boolean
    ): Long {
        fun recorderDuration() = runCatching {
            map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        }.getOrNull()?.takeIf { it > 0L }

        fun privateDuration() = runCatching {
            map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
        }.getOrNull()?.takeIf { it > 0L }

        return if (preferMediaRecorder) {
            recorderDuration() ?: privateDuration() ?: 0L
        } else {
            privateDuration() ?: recorderDuration() ?: 0L
        }
    }

    private fun cadenceConfidence(
        profile: CameraProfile,
        preferMediaRecorder: Boolean
    ): CaptureCadencePolicy.Confidence {
        if (profile.highSpeed || profile.targetFps < CaptureModeStore.FPS_60) {
            return CaptureCadencePolicy.Confidence.CONFIRMED
        }
        val map = profile.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return CaptureCadencePolicy.Confidence.UNKNOWN
        val minFrameDurationNs = encoderSurfaceMinFrameDurationNs(
            map,
            profile.videoSize,
            preferMediaRecorder
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
        val requestedBitrate = configuredVideoBitrate()
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DirectMediaRecorderBackend.findExactSelection(
                cameraId = profile.cameraId,
                width = profile.videoSize.width,
                height = profile.videoSize.height,
                fps = profile.targetFps,
                mime = mime,
                hdrHlg10 = profile.hdrHlg10,
                requestedBitrate = requestedBitrate
            )
        } else null

        // HLG10 exige perfil OEM. Em SDR, quando a Samsung publica um VideoProfile
        // exato para resolução/FPS/codec, use os parâmetros do fabricante em vez
        // de reconstruir manualmente o encoder.
        if (profile.hdrHlg10 && exact == null) return null
        val directCodecName = if (!profile.hdrHlg10) {
            MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { info ->
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                .filter { info ->
                    runCatching {
                        val videoCaps =
                            info.getCapabilitiesForType(mime).videoCapabilities
                        videoCaps != null &&
                            videoCaps.isSizeSupported(
                                profile.videoSize.width,
                                profile.videoSize.height
                            ) &&
                            videoCaps.areSizeAndRateSupported(
                                profile.videoSize.width,
                                profile.videoSize.height,
                                profile.targetFps.toDouble()
                            )
                    }.getOrDefault(false)
                }
                .sortedWith(
                    compareByDescending<MediaCodecInfo> {
                        runCatching { it.isHardwareAccelerated }.getOrDefault(false)
                    }.thenBy { it.name }
                )
                .firstOrNull()
                ?.name
        } else {
            null
        }

        return EncoderProfile(
            codecName = directCodecName
                ?: if (exact != null) "OEM EncoderProfiles" else "MediaRecorder direto",
            mime = mime,
            bitrate = requestedBitrate,
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
        val profileLabel =
            "${cameraProfile.videoSize.width}x${cameraProfile.videoSize.height}_${cameraProfile.targetFps}fps"
        val finalFile = VaultRepository.createRecordingFile(this, profileLabel)

        val requestedBitrate = configuredVideoBitrate()
        val exactOem4k60 =
            cameraProfile.videoSize == UHD_SIZE &&
                cameraProfile.targetFps == CaptureModeStore.FPS_60 &&
                !cameraProfile.hdrHlg10 &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                DirectMediaRecorderBackend.findExactSelection(
                    cameraId = cameraProfile.cameraId,
                    width = cameraProfile.videoSize.width,
                    height = cameraProfile.videoSize.height,
                    fps = cameraProfile.targetFps,
                    mime = encoderProfile.mime,
                    hdrHlg10 = false,
                    requestedBitrate = requestedBitrate
                ) != null

        log4k60PipelineCapabilities(cameraProfile, encoderProfile, exactOem4k60)

        val preferDirectCodec =
            cameraProfile.targetFps == CaptureModeStore.FPS_60 &&
                !cameraProfile.hdrHlg10 &&
                !exactOem4k60

        val backend: RecordingBackend =
            if (preferDirectCodec) {
                check(hasAudioPermission()) { "permissão de microfone necessária para gravar com áudio" }
                startForegroundNow(
                    "Preparando gravação direta com áudio…",
                    includeMicrophone = true
                )
                val integratedAudio = true

                DirectMediaCodecBackend(
                    outputFile = finalFile,
                    width = cameraProfile.videoSize.width,
                    height = cameraProfile.videoSize.height,
                    targetFps = cameraProfile.targetFps,
                    videoMime = encoderProfile.mime,
                    preferredCodecName = encoderProfile.codecName,
                    videoBitrate = encoderProfile.bitrate,
                    iFrameIntervalSeconds = recordingSettings.iFrameIntervalSeconds,
                    orientationHint = calculateOrientationHint(cameraProfile.sensorOrientation),
                    integratedAudio = integratedAudio,
                    audioSampleRate = recordingSettings.audioSampleRate,
                    audioBitrate = recordingSettings.audioBitrateKbps * 1_000,
                    requestedAudioChannels = when (recordingSettings.audioChannels) {
                        CaptureSettings.CHANNELS_MONO -> 1
                        CaptureSettings.CHANNELS_STEREO -> 2
                        else -> 0
                    },
                    audioGainDb = recordingSettings.audioGainDb,
                    audioAgc = recordingSettings.audioAgc,
                    audioNoiseSuppressor = recordingSettings.audioNoiseSuppressor,
                    audioLowCut = recordingSettings.audioLowCut,
                    onError = { throwable ->
                        if (!stopping.get() && serviceActive.get()) {
                            failAndStop("MediaCodec direto: ${errorText(throwable)}")
                        }
                    }
                )
            } else {
                check(hasAudioPermission()) { "permissão de microfone necessária para gravar com áudio" }
                startForegroundNow(
                    "Preparando gravação direta com áudio…",
                    includeMicrophone = true
                )
                val integratedAudio = true

                DirectMediaRecorderBackend(
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
                        if (!stopping.get() && serviceActive.get()) {
                            failAndStop("MediaRecorder direto: ${errorText(throwable)}")
                        }
                    }
                )
            }

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

            val description = when (backend) {
                is DirectMediaCodecBackend -> backend.profileDescription
                is DirectMediaRecorderBackend -> backend.profileDescription
                else -> backend.backendName
            }
            val msg = "${backend.backendName} pronto: $description"
            Log.i(LOG_TAG, msg)
        } catch (codecFailure: Throwable) {
            runCatching { backend.release() }
            runCatching { finalFile.delete() }

            if (!preferDirectCodec && exactOem4k60) {
                Log.w(
                    LOG_TAG,
                    "OEM MediaRecorder 4K60 recusado; voltando para MediaCodec direto",
                    codecFailure
                )

                val fallbackFile = VaultRepository.createRecordingFile(this, profileLabel)
                check(hasAudioPermission()) { "permissão de microfone necessária para gravar com áudio" }
                startForegroundNow(
                    "Preparando fallback MediaCodec 4K60…",
                    includeMicrophone = true
                )

                val fallback = DirectMediaCodecBackend(
                    outputFile = fallbackFile,
                    width = cameraProfile.videoSize.width,
                    height = cameraProfile.videoSize.height,
                    targetFps = cameraProfile.targetFps,
                    videoMime = encoderProfile.mime,
                    preferredCodecName = encoderProfile.codecName,
                    videoBitrate = encoderProfile.bitrate,
                    iFrameIntervalSeconds = recordingSettings.iFrameIntervalSeconds,
                    orientationHint = calculateOrientationHint(cameraProfile.sensorOrientation),
                    integratedAudio = true,
                    audioSampleRate = recordingSettings.audioSampleRate,
                    audioBitrate = recordingSettings.audioBitrateKbps * 1_000,
                    requestedAudioChannels = when (recordingSettings.audioChannels) {
                        CaptureSettings.CHANNELS_MONO -> 1
                        CaptureSettings.CHANNELS_STEREO -> 2
                        else -> 0
                    },
                    audioGainDb = recordingSettings.audioGainDb,
                    audioAgc = recordingSettings.audioAgc,
                    audioNoiseSuppressor = recordingSettings.audioNoiseSuppressor,
                    audioLowCut = recordingSettings.audioLowCut,
                    onError = { throwable ->
                        if (!stopping.get() && serviceActive.get()) {
                            failAndStop("MediaCodec fallback 4K60: ${errorText(throwable)}")
                        }
                    }
                )

                try {
                    val surface = fallback.prepare()
                    synchronized(resourceLock) {
                        finalOutputFile = fallbackFile
                        rawOutputFile = fallbackFile
                        professionalRecorder = fallback
                        recorderSurface = surface
                        activeRecorderBackendName = fallback.backendName
                        activeRecorderMime = encoderProfile.mime
                    }
                    Log.i(LOG_TAG, "Fallback 4K60 ativo: ${fallback.profileDescription}")
                    return
                } catch (fallbackFailure: Throwable) {
                    runCatching { fallback.release() }
                    runCatching { fallbackFile.delete() }
                    throw fallbackFailure
                }
            }

            if (!preferDirectCodec) throw codecFailure

            Log.w(
                LOG_TAG,
                "MediaCodec direto recusado; usando MediaRecorder como fallback",
                codecFailure
            )

            val fallbackFile = VaultRepository.createRecordingFile(this, profileLabel)
            val integratedAudio = hasAudioPermission() && runCatching {
                startForegroundNow(
                    "Preparando gravação direta…",
                    includeMicrophone = true
                )
                true
            }.getOrDefault(false)

            val fallback = DirectMediaRecorderBackend(
                context = this,
                outputFile = fallbackFile,
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
                    if (!stopping.get() && serviceActive.get()) {
                        failAndStop("MediaRecorder fallback: ${errorText(throwable)}")
                    }
                }
            )

            try {
                val surface = fallback.prepare()
                synchronized(resourceLock) {
                    finalOutputFile = fallbackFile
                    rawOutputFile = fallbackFile
                    professionalRecorder = fallback
                    recorderSurface = surface
                    activeRecorderBackendName = fallback.backendName
                    activeRecorderMime = encoderProfile.mime
                }
            } catch (fallbackFailure: Throwable) {
                runCatching { fallback.release() }
                runCatching { fallbackFile.delete() }
                throw fallbackFailure
            }
        }
    }

    private fun log4k60PipelineCapabilities(
        profile: CameraProfile,
        encoderProfile: EncoderProfile,
        exactOem4k60: Boolean
    ) {
        if (profile.videoSize != UHD_SIZE || profile.targetFps != CaptureModeStore.FPS_60) return

        logVendorMetadataInventory(profile.cameraId, profile.characteristics)

        val map = profile.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun durationMs(block: () -> Long): Double =
            runCatching { block() }
                .getOrNull()
                ?.takeIf { it > 0L }
                ?.div(1_000_000.0)
                ?: -1.0

        val privateMs = map?.let {
            durationMs { it.getOutputMinFrameDuration(ImageFormat.PRIVATE, profile.videoSize) }
        } ?: -1.0
        val mediaRecorderMs = map?.let {
            durationMs { it.getOutputMinFrameDuration(MediaRecorder::class.java, profile.videoSize) }
        } ?: -1.0
        val mediaCodecMs = map?.let {
            durationMs { it.getOutputMinFrameDuration(MediaCodec::class.java, profile.videoSize) }
        } ?: -1.0

        val oem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DirectMediaRecorderBackend.findExactSelection(
                cameraId = profile.cameraId,
                width = profile.videoSize.width,
                height = profile.videoSize.height,
                fps = profile.targetFps,
                mime = encoderProfile.mime,
                hdrHlg10 = profile.hdrHlg10,
                requestedBitrate = configuredVideoBitrate()
            )
        } else null

        Log.i(
            LOG_TAG,
            "4K60_PIPELINE_CAPS: camera=${profile.cameraId} " +
                "PRIVATE=${"%.3f".format(Locale.US, privateMs)}ms " +
                "MediaCodec=${"%.3f".format(Locale.US, mediaCodecMs)}ms " +
                "MediaRecorder=${"%.3f".format(Locale.US, mediaRecorderMs)}ms " +
                "oemExact=$exactOem4k60 " +
                "oemFps=${oem?.videoProfile?.frameRate ?: -1} " +
                "oemBitrate=${oem?.videoProfile?.bitrate ?: -1} " +
                "requestedBitrate=${configuredVideoBitrate()}"
        )
    }

    private fun logVendorMetadataInventory(
        logicalCameraId: String,
        logicalCharacteristics: CameraCharacteristics
    ) {
        val manager = getSystemService(CameraManager::class.java)
        val cameraIds = linkedSetOf(logicalCameraId).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(logicalCharacteristics.physicalCameraIds)
            }
        }

        Log.i(
            LOG_TAG,
            "VENDOR_TAG_SCAN: logical=$logicalCameraId physicalIds=" +
                cameraIds.filter { it != logicalCameraId }.joinToString(",").ifBlank { "none" }
        )

        cameraIds.forEach { cameraId ->
            val characteristics =
                if (cameraId == logicalCameraId) logicalCharacteristics
                else runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()

            if (characteristics == null) {
                Log.w(LOG_TAG, "VENDOR_TAG_SCAN camera=$cameraId characteristics=unavailable")
                return@forEach
            }

            val characteristicKeys = characteristics.keys
            val requestKeys = characteristics.availableCaptureRequestKeys
            val resultKeys = characteristics.availableCaptureResultKeys
            val sessionKeys =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.availableSessionKeys.orEmpty()
                } else {
                    emptyList()
                }
            val physicalRequestKeys =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.availablePhysicalCameraRequestKeys.orEmpty()
                } else {
                    emptyList()
                }

            Log.i(
                LOG_TAG,
                "VENDOR_TAG_COUNTS camera=$cameraId characteristics=${characteristicKeys.size} " +
                    "request=${requestKeys.size} result=${resultKeys.size} " +
                    "session=${sessionKeys.size} physicalRequest=${physicalRequestKeys.size}"
            )

            logVendorKeyNames(cameraId, "characteristics", characteristicKeys.map { it.name })
            logVendorKeyNames(cameraId, "request", requestKeys.map { it.name })
            logVendorKeyNames(cameraId, "result", resultKeys.map { it.name })
            logVendorKeyNames(cameraId, "session", sessionKeys.map { it.name })
            logVendorKeyNames(cameraId, "physicalRequest", physicalRequestKeys.map { it.name })

            @Suppress("UNCHECKED_CAST")
            characteristicKeys
                .filter { isVendorCameraKey(it.name) }
                .sortedBy { it.name }
                .forEach { key ->
                    val value = runCatching {
                        characteristics.get(key as CameraCharacteristics.Key<Any>)
                    }.getOrNull()
                    Log.i(
                        LOG_TAG,
                        "VENDOR_CHAR camera=$cameraId key=${key.name} value=${metadataValueForLog(value)}"
                    )
                }
        }
    }

    private fun logVendorKeyNames(cameraId: String, category: String, names: List<String>) {
        val vendorNames = names.filter(::isVendorCameraKey).distinct().sorted()
        if (vendorNames.isEmpty()) {
            Log.i(LOG_TAG, "VENDOR_KEYS camera=$cameraId category=$category none")
            return
        }
        vendorNames.chunked(6).forEachIndexed { index, chunk ->
            Log.i(
                LOG_TAG,
                "VENDOR_KEYS camera=$cameraId category=$category part=${index + 1} " +
                    chunk.joinToString(" | ")
            )
        }
    }

    private fun isVendorCameraKey(name: String): Boolean =
        !name.startsWith("android.", ignoreCase = true)

    private fun logVendorCaptureResult(result: TotalCaptureResult) {
        val vendorKeys = result.keys
            .filter { isVendorCameraKey(it.name) }
            .sortedBy { it.name }

        val resultCameraId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { result.cameraId }.getOrDefault("?")
            } else {
                "?"
            }

        Log.i(
            LOG_TAG,
            "VENDOR_RESULT_SCAN: camera=$resultCameraId frame=${result.frameNumber} vendorKeys=${vendorKeys.size}"
        )

        @Suppress("UNCHECKED_CAST")
        vendorKeys.forEach { key ->
            val value = runCatching {
                result.get(key as CaptureResult.Key<Any>)
            }.getOrNull()
            Log.i(
                LOG_TAG,
                "VENDOR_RESULT key=${key.name} value=${metadataValueForLog(value)}"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.physicalCameraResults.forEach { (cameraId, physicalResult) ->
                val physicalVendorKeys = physicalResult.keys
                    .filter { isVendorCameraKey(it.name) }
                    .sortedBy { it.name }
                Log.i(
                    LOG_TAG,
                    "VENDOR_PHYSICAL_RESULT camera=$cameraId frame=${physicalResult.frameNumber} " +
                        "vendorKeys=${physicalVendorKeys.size}"
                )
                @Suppress("UNCHECKED_CAST")
                physicalVendorKeys.forEach { key ->
                    val value = runCatching {
                        physicalResult.get(key as CaptureResult.Key<Any>)
                    }.getOrNull()
                    Log.i(
                        LOG_TAG,
                        "VENDOR_PHYSICAL_RESULT camera=$cameraId key=${key.name} " +
                            "value=${metadataValueForLog(value)}"
                    )
                }
            }
        }
    }

    private fun metadataValueForLog(value: Any?): String {
        val raw = when (value) {
            null -> "null"
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            is CharArray -> value.concatToString()
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { it.toString() }
            else -> value.toString()
        }
        return if (raw.length <= 220) raw else raw.take(217) + "..."
    }

    private fun applyDynamicFpsConfig4k60(
        profile: CameraProfile,
        builder: CaptureRequest.Builder
    ): Boolean {
        if (
            profile.videoSize != UHD_SIZE ||
            profile.targetFps != CaptureModeStore.FPS_60 ||
            profile.highSpeed
        ) {
            return false
        }

        val name = "org.codeaurora.qcamera3.sessionParameters.dynamicFPSConfig"
        val requestSupported = profile.characteristics.availableCaptureRequestKeys.any { it.name == name }
        val sessionSupported =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                profile.characteristics.availableSessionKeys.orEmpty().any { it.name == name }
            } else {
                false
            }

        if (!requestSupported || !sessionSupported) {
            Log.i(
                LOG_TAG,
                "4K60_DYNAMIC_FPS: aplicado=false requestSupported=$requestSupported " +
                    "sessionSupported=$sessionSupported"
            )
            return false
        }

        val key = CaptureRequest.Key<FloatArray>(name, FloatArray::class.java)
        val value = floatArrayOf(2.0f, 33.0f, 60.0f, 0.0f, 0.0f)

        return runCatching {
            builder.set(key, value)
            Log.i(
                LOG_TAG,
                "4K60_DYNAMIC_FPS: aplicado=true type=FloatArray value=" +
                    value.joinToString(prefix = "[", postfix = "]")
            )
            true
        }.onFailure { throwable ->
            Log.w(
                LOG_TAG,
                "4K60_DYNAMIC_FPS: aplicado=false erro=${errorText(throwable)}",
                throwable
            )
        }.getOrDefault(false)
    }

    private fun logSelectedVendorTemplateDefaults(
        profile: CameraProfile,
        builder: CaptureRequest.Builder
    ) {
        val targets = listOf(
            "org.codeaurora.qcamera3.sessionParameters.dynamicFPSConfig",
            "org.codeaurora.qcamera3.sessionParameters.EISMode",
            "org.codeaurora.qcamera3.sessionParameters.MultiCameraMode",
            "org.codeaurora.qcamera3.sessionParameters.HDRVideoMode",
            "org.codeaurora.qcamera3.sessionParameters.numPCRsBeforeStreamOn",
            "org.codeaurora.qcamera3.sessionParameters.enableMCTFwithReferenceFrame",
            "org.codeaurora.qcamera3.sessionParameters.overrideResourceCostValidation"
        )
        val available = profile.characteristics.availableCaptureRequestKeys
            .associateBy { it.name }

        NativeCameraMetadataProbe.inspectCharacteristics(
            profile.cameraId,
            targets.toTypedArray()
        ).forEach { row ->
            Log.i(LOG_TAG, "VENDOR_NDK_CHARACTERISTICS $row")
        }

        targets.forEach { name ->
            val key = available[name]
            if (key == null) {
                Log.i(LOG_TAG, "VENDOR_TEMPLATE_DEFAULT key=$name present=false")
                return@forEach
            }

            @Suppress("UNCHECKED_CAST")
            val value = runCatching {
                builder.get(key as CaptureRequest.Key<Any>)
            }.getOrNull()

            val type = when (value) {
                null -> "null"
                is ByteArray -> "ByteArray"
                is ShortArray -> "ShortArray"
                is IntArray -> "IntArray"
                is LongArray -> "LongArray"
                is FloatArray -> "FloatArray"
                is DoubleArray -> "DoubleArray"
                is BooleanArray -> "BooleanArray"
                is CharArray -> "CharArray"
                is Array<*> -> "Array<${value.firstOrNull()?.javaClass?.simpleName ?: "?"}>"
                else -> value.javaClass.name
            }

            val internalType = describeVendorKeyInternalType(key)
            Log.i(
                LOG_TAG,
                "VENDOR_TEMPLATE_DEFAULT key=$name present=true type=$type " +
                    "internal=$internalType value=${metadataValueForLog(value)}"
            )
        }
    }

    private fun describeVendorKeyInternalType(key: CaptureRequest.Key<*>): String {
        val parts = mutableListOf<String>()
        parts += "publicClass=${key.javaClass.name}"

        val fields = runCatching { key.javaClass.declaredFields.toList() }
            .getOrElse {
                parts += "fieldsError=${it.javaClass.simpleName}:${it.message}"
                return parts.joinToString(";")
            }

        fields.forEach { field ->
            val fieldValue = runCatching {
                field.isAccessible = true
                field.get(key)
            }.onFailure { error ->
                parts += "field:${field.name}:${field.type.name}=BLOCKED(${error.javaClass.simpleName})"
            }.getOrNull()

            if (fieldValue != null) {
                parts += "field:${field.name}:${field.type.name}=${metadataValueForLog(fieldValue)}"

                if (field.name.contains("key", ignoreCase = true)) {
                    parts += describeInternalMetadataKey(fieldValue)
                }
            }
        }

        return parts.joinToString(";")
    }

    private fun describeInternalMetadataKey(internalKey: Any): String {
        val details = mutableListOf<String>()
        details += "internalClass=${internalKey.javaClass.name}"

        val methodNames = listOf(
            "getName",
            "getVendorId",
            "getTypeReference",
            "getType",
            "getNativeType"
        )
        methodNames.forEach { methodName ->
            val method = internalKey.javaClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@forEach

            val value = runCatching {
                method.isAccessible = true
                method.invoke(internalKey)
            }.onFailure { error ->
                details += "$methodName=BLOCKED(${error.javaClass.simpleName})"
            }.getOrNull()
            if (value != null) {
                details += "$methodName=${metadataValueForLog(value)}"
            }
        }

        internalKey.javaClass.declaredFields
            .filter { field ->
                field.name.contains("type", ignoreCase = true) ||
                    field.name.contains("vendor", ignoreCase = true) ||
                    field.name.contains("name", ignoreCase = true)
            }
            .forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(internalKey)
                }.onFailure { error ->
                    details += "internalField:${field.name}=BLOCKED(${error.javaClass.simpleName})"
                }.getOrNull()
                if (value != null) {
                    details += "internalField:${field.name}:${field.type.name}=${metadataValueForLog(value)}"
                }
            }

        return details.joinToString(",")
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
        val requestBuilder = createRecordRequestBuilder(camera)

        if (
            profile.videoSize == UHD_SIZE &&
            profile.targetFps == CaptureModeStore.FPS_60
        ) {
            logSelectedVendorTemplateDefaults(profile, requestBuilder)
        }

        requestBuilder.apply {
            addTarget(surface)
            configureCaptureRequest(this, profile)
            if (!profile.highSpeed) applyFinalWhiteBalance(this, profile)
        }

        val dynamicFpsApplied = applyDynamicFpsConfig4k60(profile, requestBuilder)

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
                    if (
                        profile.videoSize == UHD_SIZE &&
                        profile.targetFps >= CaptureModeStore.FPS_60
                    ) {
                        Log.i(
                            LOG_TAG,
                            "TESTE_4K60: streamUseCase VIDEO_RECORD reativado para comparar pipeline HAL"
                        )
                    }
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
                                if (
                                    profile.targetFps <= CaptureModeStore.FPS_60 &&
                                    supportsManualSensor(profile)
                                ) {
                                    startWithFixedSensorCadence(
                                        session = session,
                                        autoRequest = request,
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
            if (dynamicFpsApplied) {
                Log.i(
                    LOG_TAG,
                    "4K60_DYNAMIC_FPS_SESSION: aplicado=true value=[2.0, 33.0, 60.0, 0.0, 0.0]"
                )
            }
        }

        if (
            profile.videoSize == UHD_SIZE &&
            profile.targetFps == CaptureModeStore.FPS_60 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            val supported = runCatching {
                camera.isSessionConfigurationSupported(sessionConfiguration)
            }.getOrNull()
            Log.i(
                LOG_TAG,
                "4K60_SESSION_SUPPORT: backend=${activeRecorderBackendName.ifBlank { "desconhecido" }} supported=$supported"
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

            val diagnosticCallback =
                if (profile.targetFps >= CaptureModeStore.FPS_60) {
                    createCadenceFocusDiagnosticCallback(profile, token)
                } else {
                    null
                }
            session.setRepeatingRequest(request, diagnosticCallback, mainHandler)
            commitRecorderStart(profile, token, highSpeed = false)
        }.onFailure {
            failSelectedConfigurationFromWorker(
                token,
                "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}"
            )
        }
    }

    private fun resetCadenceFocusDiagnostics() {
        cadenceDiagPreviousSensorTsNs = 0L
        cadenceDiagPreviousFrameNumber = -1L
        cadenceDiagPreviousAfState = null
        cadenceDiagPreviousLensState = null
        cadenceDiagGapCount = 0
        cadenceDiagAfScanTransitions = 0
        cadenceDiagLensMovingFrames = 0
        cadenceDiagCpuStartMs = Process.getElapsedCpuTime()
        cadenceDiagWallStartMs = SystemClock.elapsedRealtime()
        vendorResultDumped = false
    }

    private fun createCadenceFocusDiagnosticCallback(
        profile: CameraProfile,
        token: Int
    ): CameraCaptureSession.CaptureCallback {
        val nominalNs = frameDurationNs(profile.targetFps)
        val gapThresholdNs = nominalNs * 3L / 2L

        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || !recorderStarted) return
                if (
                    !vendorResultDumped &&
                    profile.videoSize == UHD_SIZE &&
                    profile.targetFps == CaptureModeStore.FPS_60
                ) {
                    vendorResultDumped = true
                    logVendorCaptureResult(result)
                }

                val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                val frameNumber = result.frameNumber
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val lensState = result.get(CaptureResult.LENS_STATE)
                val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                val rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                val activePhysicalId =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    } else {
                        null
                    }

                val previousTs = cadenceDiagPreviousSensorTsNs
                val previousFrame = cadenceDiagPreviousFrameNumber
                val previousAf = cadenceDiagPreviousAfState
                val previousLens = cadenceDiagPreviousLensState

                if (
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN &&
                    previousAf != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                ) {
                    cadenceDiagAfScanTransitions++
                    Log.d(
                        LOG_TAG,
                        "AF_SCAN início: frame=$frameNumber foco=${focusDistance ?: -1f} " +
                            "lens=${lensStateName(lensState)}"
                    )
                }
                if (lensState == CaptureResult.LENS_STATE_MOVING) {
                    cadenceDiagLensMovingFrames++
                }

                if (previousTs > 0L) {
                    val deltaNs = sensorTs - previousTs
                    if (deltaNs > gapThresholdNs) {
                        cadenceDiagGapCount++
                        val frameStep = if (previousFrame >= 0L) frameNumber - previousFrame else -1L
                        val pressure = capturePressureSnapshot()
                        Log.w(
                            LOG_TAG,
                            String.format(
                                Locale.US,
                                "GAP_SENSOR #%d delta=%.3fms nominal=%.3fms frame=%d prevFrame=%d frameStep=%d " +
                                    "frameDuration=%.3fms exposição=%.3fms ISO=%s AE=%s AWB=%s " +
                                    "rollingSkew=%.3fms physicalId=%s AF=%s prevAF=%s LENS=%s prevLENS=%s foco=%.3f " +
                                    "thermal=%d procCpu=%.1f%% heap=%dMB availMem=%dMB",
                                cadenceDiagGapCount,
                                deltaNs / 1_000_000.0,
                                nominalNs / 1_000_000.0,
                                frameNumber,
                                previousFrame,
                                frameStep,
                                (frameDurationNs ?: 0L) / 1_000_000.0,
                                (exposureNs ?: 0L) / 1_000_000.0,
                                iso?.toString() ?: "?",
                                aeStateName(aeState),
                                awbStateName(awbState),
                                (rollingShutterSkewNs ?: 0L) / 1_000_000.0,
                                activePhysicalId ?: "?",
                                afStateName(afState),
                                afStateName(previousAf),
                                lensStateName(lensState),
                                lensStateName(previousLens),
                                focusDistance ?: -1f,
                                pressure.thermalStatus,
                                pressure.processCpuPercent,
                                pressure.heapUsedMb,
                                pressure.availableMemoryMb
                            )
                        )
                    }
                }

                cadenceDiagPreviousSensorTsNs = sensorTs
                cadenceDiagPreviousFrameNumber = frameNumber
                cadenceDiagPreviousAfState = afState
                cadenceDiagPreviousLensState = lensState
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                Log.w(
                    LOG_TAG,
                    "CAPTURE_FAILED frame=${failure.frameNumber} reason=${failure.reason} " +
                        "sequence=${failure.sequenceId} captured=${failure.wasImageCaptured()}"
                )
            }

            override fun onCaptureBufferLost(
                session: CameraCaptureSession,
                request: CaptureRequest,
                target: Surface,
                frameNumber: Long
            ) {
                Log.w(LOG_TAG, "BUFFER_LOST frame=$frameNumber")
            }
        }
    }

    private data class CapturePressureSnapshot(
        val thermalStatus: Int,
        val processCpuPercent: Double,
        val heapUsedMb: Long,
        val availableMemoryMb: Long
    )

    private fun capturePressureSnapshot(): CapturePressureSnapshot {
        val wallDeltaMs = (SystemClock.elapsedRealtime() - cadenceDiagWallStartMs).coerceAtLeast(1L)
        val cpuDeltaMs = (Process.getElapsedCpuTime() - cadenceDiagCpuStartMs).coerceAtLeast(0L)
        val processCpuPercent = cpuDeltaMs.toDouble() * 100.0 / wallDeltaMs.toDouble()
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching {
            getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        }
        val availableMemoryMb = memoryInfo.availMem / (1024L * 1024L)
        val thermalStatus =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { getSystemService(PowerManager::class.java).currentThermalStatus }
                    .getOrDefault(-1)
            } else {
                -1
            }

        return CapturePressureSnapshot(
            thermalStatus = thermalStatus,
            processCpuPercent = processCpuPercent,
            heapUsedMb = heapUsedMb,
            availableMemoryMb = availableMemoryMb
        )
    }

    private fun aeStateName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        null -> "?"
        else -> value.toString()
    }

    private fun awbStateName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
        null -> "?"
        else -> value.toString()
    }

    private fun afStateName(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
        null -> "?"
        else -> value.toString()
    }

    private fun lensStateName(value: Int?): String = when (value) {
        CaptureResult.LENS_STATE_STATIONARY -> "STATIONARY"
        CaptureResult.LENS_STATE_MOVING -> "MOVING"
        null -> "?"
        else -> value.toString()
    }

    private fun logCadenceFocusDiagnosticSummary() {
        if (requestedTargetFps < CaptureModeStore.FPS_60) return
        Log.i(
            LOG_TAG,
            "Diagnóstico AF/cadência: gapsSensor=$cadenceDiagGapCount " +
                "afScanStarts=$cadenceDiagAfScanTransitions " +
                "lensMovingFrames=$cadenceDiagLensMovingFrames"
        )
    }

    private fun startLocked3ARecording(
        session: CameraCaptureSession,
        warmupRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        val completed = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        val consecutiveStableFrames = AtomicInteger(0)
        var lastExposureNs: Long? = null
        var lastSensitivityIso: Int? = null

        fun finish() {
            if (!completed.compareAndSet(false, true) || !isAttemptValid(token)) return

            lastExposureNs?.let { exposure ->
                lastSensitivityIso?.let { iso ->
                    hybridAeBaselineExposureProduct = exposure.toDouble() * iso.toDouble()
                }
            }

            val lockedRequest = buildLockedAuto3ARequest(profile) ?: warmupRequest

            runCatching {
                session.setRepeatingRequest(lockedRequest, null, mainHandler)
                armRecorderForFirstFrame(token)
                commitRecorderStart(profile, token, highSpeed = false)

                // O monitor híbrido só existe depois que o MP4 já começou.
                // Ele não cria superfície extra e não decodifica frames.
                scheduleHybridAeProbe(
                    session = session,
                    profile = profile,
                    lockedRequest = lockedRequest,
                    token = token
                )
            }.onFailure {
                failSelectedConfigurationFromWorker(
                    token,
                    "não foi possível estabilizar AE/AWB antes da gravação: ${errorText(it)}"
                )
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || completed.get()) return

                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastSensitivityIso = it }

                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                    Camera3AStateStore.updateFocus(profile.cameraId, it)
                }

                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)

                val aeReady = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                val awbReady = awbState == null ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
                val focusReady = when (recordingSettings.focusMode) {
                    CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                    CaptureSettings.FOCUS_CONTINUOUS_PICTURE -> {
                        afState == null ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                    }
                    CaptureSettings.FOCUS_LOCKED -> {
                        afState == null ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    }
                    else -> true
                }

                val stable = aeReady && awbReady && focusReady
                if (stable) {
                    consecutiveStableFrames.incrementAndGet()
                } else {
                    consecutiveStableFrames.set(0)
                }

                val seen = frames.incrementAndGet()
                if (
                    seen >= THREE_A_DETERMINISTIC_MIN_WARMUP_FRAMES &&
                    consecutiveStableFrames.get() >= THREE_A_DETERMINISTIC_STABLE_FRAMES
                ) {
                    finish()
                } else if (seen >= THREE_A_DETERMINISTIC_MAX_WARMUP_FRAMES) {
                    finish()
                }
            }
        }

        runCatching {
            session.setRepeatingRequest(warmupRequest, callback, mainHandler)
        }.onFailure {
            finish()
        }

        mainHandler.postDelayed(
            { if (!completed.get()) finish() },
            THREE_A_DETERMINISTIC_MAX_WARMUP_MS
        )
    }

    private fun buildHybridAeProbeRequest(profile: CameraProfile): CaptureRequest? {
        val camera = synchronized(resourceLock) { cameraDevice } ?: return null
        val surface = synchronized(resourceLock) { recorderSurface } ?: return null

        return runCatching {
            createRecordRequestBuilder(camera).apply {
                addTarget(surface)
                configureCaptureRequest(this, profile)
                setSafely(this, CaptureRequest.CONTROL_AE_LOCK, false)
                if (
                    profile.characteristics.get(
                        CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
                    ) == true
                ) {
                    setSafely(this, CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                setSafely(
                    this,
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    profile.fpsRange
                )
            }.build()
        }.getOrNull()
    }

    private fun scheduleHybridAeProbe(
        session: CameraCaptureSession,
        profile: CameraProfile,
        lockedRequest: CaptureRequest,
        token: Int
    ) {
        mainHandler.postDelayed(
            {
                if (
                    !isAttemptValid(token) ||
                    !recorderStarted ||
                    stopping.get() ||
                    hybridAeProbeInFlight
                ) {
                    return@postDelayed
                }
                runHybridAeProbe(session, profile, lockedRequest, token)
            },
            HYBRID_AE_PROBE_INTERVAL_MS
        )
    }

    private fun runHybridAeProbe(
        session: CameraCaptureSession,
        profile: CameraProfile,
        lockedRequest: CaptureRequest,
        token: Int
    ) {
        if (!isAttemptValid(token) || !recorderStarted || stopping.get()) return
        val probeRequest = buildHybridAeProbeRequest(profile) ?: run {
            scheduleHybridAeProbe(session, profile, lockedRequest, token)
            return
        }

        hybridAeProbeInFlight = true
        val samples = AtomicInteger(0)
        var newestProduct = 0.0

        fun finishProbe() {
            if (!hybridAeProbeInFlight) return
            hybridAeProbeInFlight = false
            if (!isAttemptValid(token) || !recorderStarted || stopping.get()) return

            val baseline = hybridAeBaselineExposureProduct
            val ratio = if (baseline > 0.0 && newestProduct > 0.0) {
                maxOf(newestProduct / baseline, baseline / newestProduct)
            } else {
                1.0
            }

            if (ratio >= HYBRID_AE_SIGNIFICANT_RATIO) {
                runHybridAeCorrectionWindow(
                    session = session,
                    profile = profile,
                    lockedRequest = lockedRequest,
                    token = token
                )
            } else {
                // O repeating request travado nunca foi removido; após o burst
                // de sonda a sessão volta automaticamente para ele.
                scheduleHybridAeProbe(session, profile, lockedRequest, token)
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || !hybridAeProbeInFlight) return

                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (exposure != null && iso != null) {
                    newestProduct = exposure.toDouble() * iso.toDouble()
                }

                if (samples.incrementAndGet() >= HYBRID_AE_PROBE_FRAMES) {
                    finishProbe()
                }
            }

            override fun onCaptureSequenceAborted(
                session: CameraCaptureSession,
                sequenceId: Int
            ) {
                finishProbe()
            }
        }

        runCatching {
            session.captureBurst(
                List(HYBRID_AE_PROBE_FRAMES) { probeRequest },
                callback,
                mainHandler
            )
        }.onFailure {
            hybridAeProbeInFlight = false
            scheduleHybridAeProbe(session, profile, lockedRequest, token)
        }

        mainHandler.postDelayed(
            {
                if (hybridAeProbeInFlight && isAttemptValid(token)) {
                    finishProbe()
                }
            },
            HYBRID_AE_PROBE_TIMEOUT_MS
        )
    }

    private fun runHybridAeCorrectionWindow(
        session: CameraCaptureSession,
        profile: CameraProfile,
        lockedRequest: CaptureRequest,
        token: Int
    ) {
        if (!isAttemptValid(token) || !recorderStarted || stopping.get()) return
        val adaptiveRequest = buildHybridAeProbeRequest(profile) ?: run {
            scheduleHybridAeProbe(session, profile, lockedRequest, token)
            return
        }

        val frames = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        var newestProduct = hybridAeBaselineExposureProduct

        fun relock() {
            if (!finished.compareAndSet(false, true)) return
            if (!isAttemptValid(token) || !recorderStarted || stopping.get()) return
            runCatching {
                session.setRepeatingRequest(lockedRequest, null, mainHandler)
            }
            if (newestProduct > 0.0) {
                hybridAeBaselineExposureProduct = newestProduct
            }
            scheduleHybridAeProbe(session, profile, lockedRequest, token)
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || finished.get()) return

                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (exposure != null && iso != null) {
                    newestProduct = exposure.toDouble() * iso.toDouble()
                }

                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val converged =
                    aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED

                val seen = frames.incrementAndGet()
                if (
                    seen >= HYBRID_AE_MIN_CORRECTION_FRAMES && converged ||
                    seen >= HYBRID_AE_MAX_CORRECTION_FRAMES
                ) {
                    relock()
                }
            }
        }

        runCatching {
            session.setRepeatingRequest(adaptiveRequest, callback, mainHandler)
        }.onFailure {
            relock()
        }

        mainHandler.postDelayed(
            { if (!finished.get() && isAttemptValid(token)) relock() },
            HYBRID_AE_CORRECTION_TIMEOUT_MS
        )
    }

    private fun buildLockedAuto3ARequest(
        profile: CameraProfile
    ): CaptureRequest? {
        val camera = synchronized(resourceLock) { cameraDevice } ?: return null
        val surface = synchronized(resourceLock) { recorderSurface } ?: return null

        return runCatching {
            createRecordRequestBuilder(camera).apply {
                addTarget(surface)
                configureCaptureRequest(this, profile)

                // Mantém o controle automático já convergido, mas congela exposição
                // e balanço de branco sem entrar em modo SENSOR manual.
                if (
                    profile.characteristics.get(
                        CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE
                    ) == true
                ) {
                    setSafely(this, CaptureRequest.CONTROL_AE_LOCK, true)
                }
                if (
                    profile.characteristics.get(
                        CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
                    ) == true
                ) {
                    setSafely(this, CaptureRequest.CONTROL_AWB_LOCK, true)
                }

                // Reafirma o contrato fixo de 60/30 FPS depois dos locks.
                setSafely(
                    this,
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    profile.fpsRange
                )
            }.build()
        }.getOrNull()
    }

    private fun buildLocked3ARequest(
        profile: CameraProfile,
        cadencePlan: SensorCadencePolicy.Plan?,
        gains: RggbChannelVector?,
        transform: ColorSpaceTransform?,
        focusDistanceDiopters: Float?
    ): CaptureRequest? {
        val camera = synchronized(resourceLock) { cameraDevice } ?: return null
        val surface = synchronized(resourceLock) { recorderSurface } ?: return null

        return runCatching {
            createRecordRequestBuilder(camera).apply {
                addTarget(surface)
                configureCaptureRequest(this, profile, cadencePlan)

                val awbModes = profile.characteristics.get(
                    CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
                ) ?: intArrayOf()
                val capabilities = profile.characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: intArrayOf()
                val manualPost = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
                )
                val canUseMeasuredWhiteBalance =
                    gains != null &&
                    transform != null &&
                    manualPost &&
                    awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)

                if (canUseMeasuredWhiteBalance) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    set(
                        CaptureRequest.COLOR_CORRECTION_MODE,
                        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                    )
                    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                    set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                } else if (
                    profile.characteristics.get(
                        CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
                    ) == true
                ) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }

                if (cadencePlan == null &&
                    profile.characteristics.get(
                        CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE
                    ) == true
                ) {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                }

                if (
                    recordingSettings.focusMode == CaptureSettings.FOCUS_LOCKED &&
                    focusDistanceDiopters != null
                ) {
                    val minimumFocusDistance = profile.characteristics.get(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                    ) ?: 0f
                    if (minimumFocusDistance > 0f) {
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                        set(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            focusDistanceDiopters.coerceIn(0f, minimumFocusDistance)
                        )
                    }
                }
            }.build()
        }.getOrNull()
    }

    private fun startLockedFocusRecording(
        session: CameraCaptureSession,
        warmupRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        val recentFocus = Camera3AStateStore.recentFocus(profile.cameraId)
        if (recentFocus != null) {
            val lockedRequest = buildLockedFocusRequest(
                profile,
                recentFocus.focusDistanceDiopters
            )
            if (lockedRequest != null) {
                startStabilizedRecording(session, lockedRequest, profile, token)
                return
            }
        }

        val completed = AtomicBoolean(false)
        val frames = AtomicInteger(0)

        fun finishWithFocus(distance: Float?) {
            if (!completed.compareAndSet(false, true) || !isAttemptValid(token)) return
            val lockedRequest = distance
                ?.takeIf { it.isFinite() && it >= 0f }
                ?.let { buildLockedFocusRequest(profile, it) }

            runCatching {
                armRecorderForFirstFrame(token)
                session.setRepeatingRequest(lockedRequest ?: warmupRequest, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }.onFailure {
                failSelectedConfigurationFromWorker(
                    token,
                    "não foi possível travar o foco antes da gravação: ${errorText(it)}"
                )
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || completed.get()) return

                val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (distance != null) {
                    Camera3AStateStore.updateFocus(profile.cameraId, distance)
                }

                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val focused =
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

                if (focused || frames.incrementAndGet() >= FOCUS_LOCK_MAX_WARMUP_FRAMES) {
                    finishWithFocus(distance)
                }
            }
        }

        runCatching {
            session.setRepeatingRequest(warmupRequest, callback, mainHandler)
        }.onFailure {
            finishWithFocus(null)
        }

        mainHandler.postDelayed(
            {
                if (!completed.get()) {
                    finishWithFocus(
                        Camera3AStateStore.recentFocus(profile.cameraId)
                            ?.focusDistanceDiopters
                    )
                }
            },
            FOCUS_LOCK_MAX_WARMUP_MS
        )
    }

    private fun buildLockedFocusRequest(
        profile: CameraProfile,
        focusDistanceDiopters: Float
    ): CaptureRequest? {
        val camera = synchronized(resourceLock) { cameraDevice } ?: return null
        val surface = synchronized(resourceLock) { recorderSurface } ?: return null
        val minimumFocusDistance = profile.characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: return null
        if (minimumFocusDistance <= 0f) return null

        val distance = focusDistanceDiopters.coerceIn(0f, minimumFocusDistance)

        return runCatching {
            createRecordRequestBuilder(camera).apply {
                addTarget(surface)
                configureCaptureRequest(this, profile)
                applyFinalWhiteBalance(this, profile)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }.build()
        }.getOrNull()
    }

    private fun supportsManualSensor(profile: CameraProfile): Boolean {
        val capabilities = profile.characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null &&
            profile.characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null
    }

    /**
     * Mantém a mesma política de cadência em todos os modos regulares até 60 FPS,
     * incluindo 30/24 FPS e resoluções até 720p. O AE recebe uma janela curta antes
     * do primeiro sample; depois frame duration/exposure/ISO são congelados com headroom.
     */
    private fun startWithFixedSensorCadence(
        session: CameraCaptureSession,
        autoRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        if (!isAttemptValid(token)) return

        armRecorderForFirstFrame(token)

        val locked = AtomicBoolean(false)
        val validResults = AtomicInteger(0)
        var latestExposureNs: Long? = null
        var latestSensitivityIso: Int? = null

        fun applyHeadroomOrFallback(reason: String) {
            if (!locked.compareAndSet(false, true) || !isAttemptValid(token)) return

            val exposureNs = latestExposureNs
            val sensitivityIso = latestSensitivityIso
            val plan = if (exposureNs != null && sensitivityIso != null) {
                fixedCadencePlan(profile, exposureNs, sensitivityIso)
            } else null
            val fixedRequest = plan?.let { buildFixedCadenceRequest(profile, it) }

            if (plan != null && exposureNs != null && sensitivityIso != null) {
                val requestedIso =
                    sensitivityIso.toDouble() * exposureNs.toDouble() /
                        plan.exposureTimeNs.toDouble()
                val exposureLossPercent =
                    if (requestedIso > plan.sensitivityIso) {
                        ((1.0 - plan.sensitivityIso / requestedIso) * 100.0)
                            .coerceAtLeast(0.0)
                    } else {
                        0.0
                    }

                Log.i(
                    LOG_TAG,
                    String.format(
                        Locale.US,
                        "SENSOR_HEADROOM: motivo=%s frameDuration=%.3fms exposure=%.3fms ISO=%d " +
                            "(AE observado %.3fms ISO=%d, perdaEstimativa=%.1f%%)",
                        reason,
                        plan.frameDurationNs / 1_000_000.0,
                        plan.exposureTimeNs / 1_000_000.0,
                        plan.sensitivityIso,
                        exposureNs / 1_000_000.0,
                        sensitivityIso,
                        exposureLossPercent
                    )
                )
            } else {
                Log.w(
                    LOG_TAG,
                    "SENSOR_HEADROOM indisponível ($reason); mantendo AE automático"
                )
            }

            runCatching {
                session.setRepeatingRequest(
                    fixedRequest ?: autoRequest,
                    createCadenceFocusDiagnosticCallback(profile, token),
                    mainHandler
                )
                commitRecorderStart(profile, token, highSpeed = false)
            }.onFailure {
                failSelectedConfigurationFromWorker(
                    token,
                    "não foi possível aplicar headroom de exposição 4K60: ${errorText(it)}"
                )
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || locked.get()) return

                val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (exposureNs == null || sensitivityIso == null) return

                latestExposureNs = exposureNs
                latestSensitivityIso = sensitivityIso
                val count = validResults.incrementAndGet()
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val aeReady =
                    aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

                if (aeReady || count >= 4) {
                    applyHeadroomOrFallback(
                        if (aeReady) "AE_CONVERGED" else "4_RESULTADOS_VALIDOS"
                    )
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                Log.w(
                    LOG_TAG,
                    "SENSOR_HEADROOM warmup falhou frame=${failure.frameNumber}"
                )
            }
        }

        session.setRepeatingRequest(autoRequest, callback, mainHandler)

        mainHandler.postDelayed(
            {
                applyHeadroomOrFallback("TIMEOUT_${FOUR_K60_HEADROOM_WARMUP_MS}MS")
            },
            FOUR_K60_HEADROOM_WARMUP_MS
        )
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

        val backendLabel = activeRecorderBackendName.ifBlank { "encoder direto" }
        val suffix = if (highSpeed) "high-speed • $backendLabel" else "$backendLabel • ${stabilizationName(profile)}"
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

        val afModes = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        ) ?: intArrayOf()
        val automaticAf = listOf(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO
        ).firstOrNull { afModes.contains(it) }
        automaticAf?.let { setSafely(builder, CaptureRequest.CONTROL_AF_MODE, it) }
        setSafely(builder, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)

        val awbModes = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        ) ?: intArrayOf()
        if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)

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
                val requestedCompensation = effectiveExposureCompensation(profile)
                setSafely(
                    builder,
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    requestedCompensation.coerceIn(it.lower, it.upper)
                )
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
        val requestedNoise = when (recordingSettings.noiseReduction) {
            CaptureSettings.PROCESSING_OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            CaptureSettings.PROCESSING_FAST -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else -> if (profile.targetFps >= CaptureModeStore.FPS_60) {
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            } else {
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            }
        }
        val noiseFallbackOrder = listOf(
            requestedNoise,
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF
        ).distinct()
        noiseFallbackOrder.firstOrNull { noiseModes.contains(it) }?.let {
            setSafely(builder, CaptureRequest.NOISE_REDUCTION_MODE, it)
        }

        val edgeModes = profile.characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val requestedEdge = when (recordingSettings.edgeMode) {
            CaptureSettings.PROCESSING_OFF -> CameraMetadata.EDGE_MODE_OFF
            CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            CaptureSettings.PROCESSING_FAST -> CameraMetadata.EDGE_MODE_FAST
            else -> if (profile.targetFps >= CaptureModeStore.FPS_60) {
                CameraMetadata.EDGE_MODE_OFF
            } else {
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            }
        }
        val edgeFallbackOrder = listOf(
            requestedEdge,
            CameraMetadata.EDGE_MODE_FAST,
            CameraMetadata.EDGE_MODE_OFF
        ).distinct()
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
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)

        // Constrained high-speed não herdava o anti-banding do request regular.
        // Em alta taxa isso deixa LEDs ligados em rede aparecerem como quadros/faixas
        // alternadamente escuras. Preserve a preferência existente (AUTO/50/60/OFF)
        // também na sessão high-speed para a HAL sincronizar a exposição quando puder.
        val antibandingModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
        val requestedAntibanding = when (recordingSettings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(requestedAntibanding)) {
            setSafely(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAntibanding)
        }
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
        // request constrained e podem ser testados depois de alta taxa estabilizarem.
        setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
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

    private fun applyFinalWhiteBalance(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        // Durante vídeo o balanço de branco fica sempre sob controle do HAL.
        // Evita AWB lock, ganhos manuais e fallback incandescente, que faziam a cor
        // ficar presa quando a iluminação mudava.
        val modes = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        ) ?: intArrayOf()
        if (modes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
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
        // Regra única de estabilização em alta cadência: Preview stabilization/EIS
        // sobrecarregam o ISP e, em 4K60 (e acima), viram gaps de frame reais no
        // arquivo. Essa regra vale sempre, mesmo se o usuário escolheu manualmente
        // EIS/Preview nos Ajustes — não existe um "caminho manual" que ignore isso.
        if (profile.highSpeed || profile.targetFps > CaptureModeStore.FPS_60) {
            return RecordingStabilizationPolicy.Mode.OFF
        }
        if (profile.targetFps >= CaptureModeStore.FPS_60) {
            return if (profile.oisSupported) {
                RecordingStabilizationPolicy.Mode.OIS
            } else {
                RecordingStabilizationPolicy.Mode.OFF
            }
        }

        if (recordingSettings.stabilization == CaptureSettings.STABILIZATION_AUTO) {
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
        logCadenceFocusDiagnosticSummary()

        releaseCameraOnly()
        runCatching { recorder?.release() }
        sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))

        if (!validOutput || finalFile == null || !finalFile.isFile || finalFile.length() <= 0L) {
            if (finalFile != null && finalFile.isFile && finalFile.length() > 0L) {
                finishWithRecoveredPartial(
                    finalFile,
                    finalFile,
                    "${activeRecorderBackendName.ifBlank { "encoder" }} não conseguiu finalizar o MP4"
                )
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
        // O original é finalizado e indexado antes de entrar no pós-processamento.
        // Gravações headless (widget/atalhos) já armam a fila para iniciar assim que
        // esta sessão liberar câmera/encoder. Se outra gravação começar, o worker
        // é cancelado e o job volta para PENDING.
        val cadenceAnalysis = runCatching { VideoAnalysis.read(finalFile) }
            .onSuccess { analysis ->
                Log.i(
                    LOG_TAG,
                    String.format(
                        Locale.US,
                        "Cadência MP4: fpsReal=%.3f nominal=%d frames=%d gaps=%d ausentes=%d curtos=%d deltaMin=%.3fms deltaMax=%.3fms jitter=%d%%",
                        analysis.exactFps,
                        analysis.estimatedFps,
                        analysis.frameCount,
                        analysis.largeGapCount,
                        analysis.estimatedMissingFrames,
                        analysis.shortIntervalCount,
                        analysis.minimumFrameDeltaUs / 1000.0,
                        analysis.maximumFrameDeltaUs / 1000.0,
                        analysis.frameJitterPercent
                    )
                )
            }
            .onFailure {
                Log.w(LOG_TAG, "Não foi possível analisar a cadência do MP4 final", it)
            }
            .getOrNull()

        if (cadenceAnalysis?.estimatedMissingFrames ?: 0 > 0) {
            Log.i(
                LOG_TAG,
                "Gap real detectado no MP4: iniciando reparo CFR automático de " +
                    "${cadenceAnalysis?.estimatedMissingFrames ?: 0} quadro(s)"
            )
            AutoGapRepairService.enqueueAfterRecording(
                context = this,
                source = finalFile,
                targetFps = fps,
                startImmediately = false
            )
        }
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
        VideoProcessingService.resumeAfterCapture()
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
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
        VideoProcessingService.resumeAfterCapture()
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
        val backend = synchronized(resourceLock) { professionalRecorder }
        val preferMediaRecorder = backend is DirectMediaRecorderBackend
        val map = profile.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val minFrameDurationNs = map?.let {
            encoderSurfaceMinFrameDurationNs(it, profile.videoSize, preferMediaRecorder)
        } ?: 0L
        val cadence = cadenceConfidence(profile, preferMediaRecorder)
        val cadenceMessage = "Gravação ${sizeName(profile.videoSize)} ${profile.targetFps} FPS iniciada em ${elapsedMs} ms • " +
            "cadênciaHAL=$cadence • minFrame=${if (minFrameDurationNs > 0L) minFrameDurationNs / 1_000_000.0 else -1.0}ms • " +
            "backend=${backend?.backendName ?: activeRecorderBackendName} • câmera=${profile.cameraId}"
        Log.i(LOG_TAG, cadenceMessage)
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
            append(", backend=").append(backend?.backendName ?: activeRecorderBackendName.ifBlank { "desconhecido" })
        }
        fpsFallbackWarningLogged = true
        Log.i(LOG_TAG, info)
    }

    private fun requestedProfileLabel(): String =
        "${CaptureSettings.resolutionLabel(recordingSettings.resolution)} ${requestedTargetFps} FPS"

    companion object {
        private const val FOCUS_LOCK_MAX_WARMUP_FRAMES = 8
        private const val FOCUS_LOCK_MAX_WARMUP_MS = 250L
        private const val THREE_A_LOCK_MIN_WARMUP_FRAMES = 4
        private const val THREE_A_LOCK_MAX_WARMUP_FRAMES = 10
        private const val THREE_A_LOCK_MAX_WARMUP_MS = 350L
        private const val THREE_A_DETERMINISTIC_MIN_WARMUP_FRAMES = 12
        private const val THREE_A_DETERMINISTIC_STABLE_FRAMES = 6
        private const val THREE_A_DETERMINISTIC_MAX_WARMUP_FRAMES = 30
        private const val THREE_A_DETERMINISTIC_MAX_WARMUP_MS = 900L
        private const val HYBRID_AE_PROBE_INTERVAL_MS = 2_000L
        private const val HYBRID_AE_PROBE_FRAMES = 3
        private const val HYBRID_AE_PROBE_TIMEOUT_MS = 350L
        private const val HYBRID_AE_SIGNIFICANT_RATIO = 1.65
        private const val HYBRID_AE_MIN_CORRECTION_FRAMES = 4
        private const val HYBRID_AE_MAX_CORRECTION_FRAMES = 6
        private const val HYBRID_AE_CORRECTION_TIMEOUT_MS = 250L
        private const val FOUR_K60_HEADROOM_WARMUP_MS = 500L

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
        const val EXTRA_CAMERA_RECOVERY_ATTEMPT =
            "camera_recovery_attempt"

        private const val LOG_TAG = "SteadyVaultCapture"
        private const val CAPTURE_PIPELINE_REVISION = "mediacodec-manual-headroom-all-regular-1.8.282"
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
        private const val AUTO_HIGHLIGHT_BIAS_EV_60 = -0.40f
        private const val AUTO_HIGHLIGHT_BIAS_EV_30 = -0.25f
        private const val MAX_CAMERA_RECOVERY_ATTEMPTS = 3
        private const val CAMERA_RECOVERY_DELAY_MS = 450L

    }
}