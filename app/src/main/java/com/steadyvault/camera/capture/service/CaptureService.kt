package com.steadyvault.camera.capture.service

import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.capture.recorder.HardwareRecorder
import com.steadyvault.camera.capture.timing.RecordingStopPolicy
import com.steadyvault.camera.capture.timing.StrictCaptureModePolicy
import com.steadyvault.camera.capture.health.RecordingHealthMonitor

import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.camera.Camera3AStateStore
import com.steadyvault.camera.core.camera.CameraZoom
import com.steadyvault.camera.core.capability.CaptureCapabilityMatrix
import com.steadyvault.camera.core.camera.CameraResourceCoordinator
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.camera.WhiteBalanceCorrection
import com.steadyvault.camera.core.camera.CctWhiteBalanceController
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.CameraProfileStore
import com.steadyvault.camera.core.state.CapturePhase
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.state.OptimizationStateStore
import com.steadyvault.camera.core.storage.RecordingStorageGuard
import com.steadyvault.camera.core.storage.RecordingStorageMonitor
import com.steadyvault.camera.processing.service.VideoOptimizationService
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.RecordingFilePublisher
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CaptureService : Service() {

    private enum class EffectiveStabilization {
        OFF,
        PREVIEW,
        EIS,
        OIS
    }

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
        val hdrHlg10: Boolean,
        val profile: Int?,
        val level: Int?
    )

    private data class CachedConfiguration(
        val signature: String,
        val camera: CameraProfile,
        val encoder: EncoderProfile
    )

    private data class ClaimedRecording(
        val recorder: HardwareRecorder?,
        val output: File?,
        val raw: File?,
        val wasStarted: Boolean
    )

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var encoderPreparationExecutor: ExecutorService
    private lateinit var runtimeStorageMonitor: RecordingStorageMonitor
    private lateinit var recordingHealthMonitor: RecordingHealthMonitor
    private val mainHandler = Handler(Looper.getMainLooper())
    private val threadCounter = AtomicInteger(0)
    private val serviceActive = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val cameraRecoveryScheduled = AtomicBoolean(false)
    private val initialCameraRetryScheduled = AtomicBoolean(false)
    private val segmentRecoveryInProgress = AtomicBoolean(false)
    private val resourceLock = Any()
    private val cameraLeaseToken = Any()
    private var screenReceiverRegistered = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraAvailabilityManager: CameraManager? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null
    private var professionalRecorder: HardwareRecorder? = null
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
    private var recordingWasEverStarted = false
    private var recoveredSegmentCount = 0
    private var cameraRecoveryAttempts = 0
    private var recordingRequestedAtElapsedNs = 0L
    private var lastServiceHeartbeatElapsedMs = 0L
    private var wakeLockAcquiredAtElapsedMs = 0L
    @Volatile private var initialCameraRetryNotBeforeElapsedMs = 0L
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

    @Volatile
    private var foregroundNotificationStarted = false

    @Volatile
    private var lastScreenTransition = "estado inicial"

    private val screenTransitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> handleScreenTransition(intent.action.orEmpty())
            }
        }
    }


    override fun onCreate() {
        super.onCreate()

        cameraExecutor = Executors.newSingleThreadExecutor(
            createThreadFactory("SteadyVault-Camera", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        )
        encoderPreparationExecutor = Executors.newSingleThreadExecutor(
            createThreadFactory("SteadyVault-EncoderPrepare", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        )
        recordingHealthMonitor = RecordingHealthMonitor(
            handler = mainHandler,
            intervalMs = RECORDING_HEALTH_CHECK_INTERVAL_MS,
            shouldRun = { serviceActive.get() && recorderStarted && !userRequestedStop && !stopping.get() },
            onTick = {
                refreshRecordingServiceHeartbeat()
                checkRecordingPipelineHealth("monitor contínuo")
            }
        )
        runtimeStorageMonitor = RecordingStorageMonitor(
            context = this,
            threadName = "SteadyVault-StorageMonitor",
            isRecording = {
                serviceActive.get() && recorderStarted && !userRequestedStop && !stopping.get()
            },
            onCritical = {
                sendState("Espaço crítico • finalizando e preservando o vídeo…")
                stopCurrentRecording()
            }
        )

        createNotificationChannel()
        registerScreenTransitionReceiver()
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
        // Todo ponto de entrada recebe a mesma prioridade. A tela normalmente já
        // aguarda o cancelamento, mas o serviço também protege widgets e intents diretos.
        if (OptimizationStateStore.snapshot(this).running) {
            runCatching { VideoOptimizationService.cancel(this) }
            sendState("Cancelando otimização para liberar o processador da gravação…")
        }
        previewCaptureRequested = if (headlessCaptureRequested) {
            false
        } else {
            intent?.getBooleanExtra(EXTRA_FROM_PREVIEW, false) == true
        }
        preferredCameraId = intent?.getStringExtra(EXTRA_PREFERRED_CAMERA_ID)?.takeIf { it.isNotBlank() }
        val currentSettings = CaptureSettings.snapshot(this)
        val backgroundZoom = if (headlessCaptureRequested && preferredCameraId == null) {
            BackgroundRecordingZoom.cameraSelection(this)
        } else {
            null
        }
        val profileCameraId = preferredCameraId ?: backgroundZoom?.cameraId ?: currentSettings.selectedCameraId
        recordingSettings = if (profileCameraId != null) {
            CameraProfileStore.activate(
                this,
                profileCameraId,
                CameraProfileStore.FunctionMode.VIDEO,
                currentSettings.copy(selectedCameraId = profileCameraId)
            )
        } else {
            CameraProfileStore.setActiveMode(this, CameraProfileStore.FunctionMode.VIDEO)
            currentSettings
        }
        requestedTargetFps = intent
            ?.getIntExtra(EXTRA_TARGET_FPS, recordingSettings.fps)
            ?.takeIf { it in CaptureSettings.supportedFpsValues }
            ?: recordingSettings.fps
        if (requestedTargetFps != recordingSettings.fps) recordingSettings = recordingSettings.copy(fps = requestedTargetFps)
        backgroundZoom?.let { selection ->
            recordingSettings = recordingSettings.copy(
                selectedCameraId = selection.cameraId,
                zoomRatio = selection.requestZoomRatio
            )
        }

        userRequestedStop = false
        stopHapticAcknowledged = false
        stopping.set(false)
        cameraRecoveryScheduled.set(false)
        cancelInitialCameraAvailabilityRetry()
        segmentRecoveryInProgress.set(false)
        cameraRecoveryAttempts = 0
        recorderStarted = false
        recorderArmed = false
        recordingStartedAtMs = 0L
        recordingStartedAtElapsedMs = 0L
        recordingWasEverStarted = false
        recoveredSegmentCount = 0
        fpsFallbackWarningLogged = false
        foregroundNotificationStarted = false
        synchronized(resourceLock) { headlessSessionStartToken = -1 }
        runtimeStorageMonitor.start(
            videoBitrateBps = recordingSettings.bitrateMbps.toLong() * 1_000_000L,
            audioBitrateBps = if (hasAudioPermission()) {
                recordingSettings.audioBitrateKbps.toLong() * 1_000L
            } else {
                0L
            }
        )
        attemptToken++
        val token = attemptToken

        if (!hasRequiredPermissions()) {
            sendState("Falha: permissão de câmera é obrigatória")
            serviceActive.set(false)
            stopSelf()
            return
        }

        try {
            // Garante primeiro o prazo do FGS apenas com câmera. A promoção para
            // microfone acontece imediatamente antes do AudioRecord, evitando a
            // corrida de AppOps/FGS de microfone observada no Android 16.
            startForegroundCameraOnly("Preparando ${requestedProfileLabel()}…")
        } catch (t: Throwable) {
            sendState("Falha ao iniciar serviço: ${errorText(t)}")
            abortBeforeCaptureStart()
            return
        }

        acquireWakeLock()
        lastServiceHeartbeatElapsedMs = 0L
        refreshRecordingServiceHeartbeat(force = true)
        // Recuperação/limpeza pode copiar MP4s no mesmo armazenamento do muxer.
        // Sempre adie esse I/O até a captura terminar, inclusive com preview.
        deferredRawCleanup = true

        val thermalStatus = getSystemService(PowerManager::class.java).currentThermalStatus
        if (recordingSettings.thermalProtection && thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            failAndStop("temperatura crítica; resfrie o aparelho antes de gravar ${requestedProfileLabel()}")
            return
        }

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
            val (cameraProfile, encoderProfile) = selectCaptureConfigurationWithFpsFallback(
                targetFps = requestedTargetFps,
                allowHdr = recordingSettings.hdrHlg10
            )
            check(matchesRequestedResolution(cameraProfile) && cameraProfile.targetFps <= requestedTargetFps) {
                "A configuração efetiva não preservou a resolução solicitada ou excedeu o FPS pedido"
            }
            selectedCamera = cameraProfile
            selectedEncoder = encoderProfile
            if (!validateStorageForRecording(encoderProfile)) return
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
        if (canStart) createRecordingSession(camera, profile, token)
    }

    private fun failSelectedConfigurationFromWorker(expectedToken: Int, reason: String) {
        if (!isAttemptValid(expectedToken)) return
        selectedCamera?.let { profile ->
            if (recorderStarted) {
                scheduleCameraRecovery(profile, reason)
                return
            }
        }

        ++attemptToken
        CaptureCapabilityMatrix.invalidate(this)
        releaseRecordingResources(deleteOutput = true)
        recorderStarted = false
        failAndStopFromWorker("${requestedProfileLabel()} não foi aceito: $reason")
    }

    private fun handleCameraInterruption(
        profile: CameraProfile,
        token: Int,
        reason: String,
        temporarilyBusy: Boolean = false
    ) {
        if (!isAttemptValid(token)) return
        if (!recorderStarted) {
            if (headlessCaptureRequested && temporarilyBusy) {
                scheduleInitialCameraAvailabilityRetry(profile, token, reason)
                return
            }
            failSelectedConfigurationFromWorker(token, reason)
            return
        }
        scheduleCameraRecovery(profile, reason)
    }

    /**
     * No bloqueio, o reconhecimento facial da One UI pode reservar a câmera por
     * alguns instantes. Isso não invalida resolução, FPS ou encoder. Conservamos
     * toda a preparação e repetimos somente openCamera assim que o HAL sinalizar
     * que a lente escolhida voltou a ficar disponível.
     */
    private fun scheduleInitialCameraAvailabilityRetry(
        profile: CameraProfile,
        expectedToken: Int,
        reason: String
    ) {
        if (
            !isAttemptValid(expectedToken) ||
            recorderStarted ||
            !initialCameraRetryScheduled.compareAndSet(false, true)
        ) {
            return
        }

        val manager = getSystemService(CameraManager::class.java)
        initialCameraRetryNotBeforeElapsedMs =
            SystemClock.elapsedRealtime() + INITIAL_CAMERA_BUSY_RETRY_MS
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                if (cameraId == profile.cameraId) {
                    retryInitialCameraOpen(profile, expectedToken, "câmera liberada pelo sistema")
                }
            }
        }
        cameraAvailabilityManager = manager
        cameraAvailabilityCallback = callback
        runCatching {
            manager.registerAvailabilityCallback(cameraExecutor, callback)
        }.onFailure {
            cameraAvailabilityManager = null
            cameraAvailabilityCallback = null
        }

        sendStateOnMain("Preparando gravação • aguardando a câmera do desbloqueio facial…")
        updateNotificationOnMain("Aguardando a câmera ficar disponível…")
        Log.i(LOG_TAG, "Aguardando disponibilidade inicial da câmera: $reason")

        mainHandler.postDelayed(
            {
                cameraExecutor.execute {
                    retryInitialCameraOpen(
                        profile,
                        expectedToken,
                        "verificação rápida de disponibilidade"
                    )
                }
            },
            INITIAL_CAMERA_BUSY_RETRY_MS
        )
    }

    private fun retryInitialCameraOpen(
        profile: CameraProfile,
        expectedToken: Int,
        reason: String
    ) {
        val remainingDelayMs =
            initialCameraRetryNotBeforeElapsedMs - SystemClock.elapsedRealtime()
        if (remainingDelayMs > 0L) {
            mainHandler.postDelayed(
                {
                    if (initialCameraRetryScheduled.get()) {
                        cameraExecutor.execute {
                            retryInitialCameraOpen(profile, expectedToken, reason)
                        }
                    }
                },
                remainingDelayMs
            )
            return
        }
        if (!initialCameraRetryScheduled.compareAndSet(true, false)) return
        unregisterInitialCameraAvailabilityCallback()
        if (!isAttemptValid(expectedToken) || recorderStarted) return

        val retryToken = ++attemptToken
        synchronized(resourceLock) {
            headlessSessionStartToken = -1
        }
        runCatching {
            openSelectedCamera(profile, retryToken)
        }.onFailure { throwable ->
            if (isTemporaryCameraBusy(throwable)) {
                scheduleInitialCameraAvailabilityRetry(
                    profile,
                    retryToken,
                    "$reason: ${errorText(throwable)}"
                )
            } else {
                failSelectedConfigurationFromWorker(
                    retryToken,
                    "$reason: ${errorText(throwable)}"
                )
            }
        }
    }

    private fun cancelInitialCameraAvailabilityRetry() {
        initialCameraRetryScheduled.set(false)
        initialCameraRetryNotBeforeElapsedMs = 0L
        unregisterInitialCameraAvailabilityCallback()
    }

    private fun unregisterInitialCameraAvailabilityCallback() {
        val manager = cameraAvailabilityManager
        val callback = cameraAvailabilityCallback
        cameraAvailabilityManager = null
        cameraAvailabilityCallback = null
        if (manager != null && callback != null) {
            runCatching { manager.unregisterAvailabilityCallback(callback) }
        }
    }

    private fun isTemporaryCameraBusy(throwable: Throwable): Boolean =
        throwable is CameraAccessException &&
            throwable.reason in setOf(
                CameraAccessException.CAMERA_IN_USE,
                CameraAccessException.MAX_CAMERAS_IN_USE
            )

    private fun scheduleCameraRecovery(
        profile: CameraProfile,
        reason: String
    ) {
        if (
            !serviceActive.get() ||
            userRequestedStop ||
            stopping.get() ||
            !recorderStarted ||
            !cameraRecoveryScheduled.compareAndSet(false, true)
        ) {
            return
        }

        val attempt = ++cameraRecoveryAttempts
        val delayMs = (
            CAMERA_RECOVERY_DELAY_MS * attempt.coerceAtMost(
                CAMERA_RECOVERY_MAX_DELAY_STEPS
            )
        ).coerceAtMost(CAMERA_RECOVERY_MAX_DELAY_MS)
        val message =
            "Gravando • câmera temporariamente ocupada; retomando automaticamente…"
        Log.w(LOG_TAG, "$message tentativa=$attempt motivo=$reason")
        sendStateOnMain(message)
        updateNotificationOnMain(message)

        mainHandler.postDelayed(
            {
                cameraRecoveryScheduled.set(false)
                cameraExecutor.execute {
                    if (
                        !serviceActive.get() ||
                        userRequestedStop ||
                        stopping.get() ||
                        !recorderStarted
                    ) {
                        return@execute
                    }

                    val recoveryToken = ++attemptToken
                    releaseCameraOnly()
                    runCatching {
                        openSelectedCamera(profile, recoveryToken)
                    }.onFailure {
                        scheduleCameraRecovery(
                            profile,
                            "nova tentativa: ${errorText(it)}"
                        )
                    }
                }
            },
            delayMs
        )
    }

    private fun registerScreenTransitionReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                screenTransitionReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterScreenTransitionReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenTransitionReceiver) }
        screenReceiverRegistered = false
    }

    private fun handleScreenTransition(action: String) {
        lastScreenTransition = when (action) {
            Intent.ACTION_SCREEN_OFF -> "tela apagada"
            Intent.ACTION_SCREEN_ON -> "tela acesa"
            Intent.ACTION_USER_PRESENT -> "aparelho desbloqueado"
            else -> "mudança da tela"
        }
        if (
            !serviceActive.get() ||
            !recorderStarted ||
            userRequestedStop ||
            stopping.get()
        ) {
            return
        }

        // Alguns firmwares Samsung suspendem a sessão Camera2 no ciclo
        // apagar/acender mesmo mantendo o foreground service vivo. O wake lock
        // protege o encoder e a verificação atrasada reabre somente a câmera se
        // os quadros realmente tiverem parado.
        if (wakeLock?.isHeld != true) acquireWakeLock()
        mainHandler.postDelayed(
            {
                if (
                    serviceActive.get() &&
                    recorderStarted &&
                    !userRequestedStop &&
                    !stopping.get()
                ) {
                    checkRecordingPipelineHealth(lastScreenTransition)
                }
            },
            SCREEN_TRANSITION_HEALTH_DELAY_MS
        )
    }

    private fun startRecordingHealthWatchdog() {
        recordingHealthMonitor.start()
    }

    private fun stopRecordingHealthWatchdog() {
        if (::recordingHealthMonitor.isInitialized) recordingHealthMonitor.stop()
    }

    private fun checkRecordingPipelineHealth(reason: String) {
        if (
            !serviceActive.get() ||
            !recorderStarted ||
            userRequestedStop ||
            stopping.get()
        ) {
            return
        }

        val recorder: HardwareRecorder?
        val profile: CameraProfile?
        synchronized(resourceLock) {
            recorder = professionalRecorder
            profile = selectedCamera
        }
        renewWakeLockIfNeeded()
        val activeRecorder = recorder ?: return
        val selectedProfile = profile ?: return
        // Escrita síncrona lenta significa pressão do muxer/armazenamento, não HAL
        // travada. Reabrir Camera2 nesse instante só ampliaria a lacuna do arquivo.
        if (activeRecorder.isVideoWriteInProgress()) return
        // Não reconfigura bitrate com MediaCodec.setParameters durante a captura.
        // Em alguns codecs Samsung/Qualcomm essa troca em voo cria pequenas pausas.
        // O teto seguro é escolhido uma vez antes de iniciar o encoder.
        val silenceMs = activeRecorder.videoSilenceDurationMs()
        val stallThresholdMs = if (activeRecorder.hasProducedVideoSample()) {
            VIDEO_FRAME_STALL_RECOVERY_MS
        } else {
            FIRST_VIDEO_FRAME_GRACE_MS
        }
        if (silenceMs < stallThresholdMs) return

        scheduleCameraRecovery(
            selectedProfile,
            "$reason sem quadros do encoder por ${silenceMs} ms"
        )
    }

    private fun renewWakeLockIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - wakeLockAcquiredAtElapsedMs >= WAKE_LOCK_RENEW_INTERVAL_MS) acquireWakeLock()
    }

    private fun selectBestCaptureConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> {
        val signature = configurationSignature(targetFps, allowHdr)
        cachedConfiguration
            ?.takeIf { it.signature == signature }
            ?.takeIf { matchesRequestedMode(it.camera, targetFps) }
            ?.takeIf { targetFps != CaptureModeStore.FPS_60 || it.camera.hasExactFpsRange() }
            ?.takeIf { preferredCameraCompatible(it.camera) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.camera) }
            ?.let {
                Log.d(LOG_TAG, "Reutilizando câmera e encoder já validados")
                return it.camera to it.encoder
            }

        loadRememberedConfiguration(signature, targetFps, allowHdr)
            ?.takeIf { preferredCameraCompatible(it.first) }
            ?.takeIf { profileSatisfiesExplicitStabilization(it.first) }
            ?.let { (camera, encoder) ->
                cacheConfiguration(signature, camera, encoder)
                Log.d(LOG_TAG, "Reutilizando configuração validada em uma gravação anterior")
                return camera to encoder
            }

        selectPreferredPreviewConfiguration(targetFps, allowHdr)?.let { (camera, encoder) ->
            cacheConfiguration(signature, camera, encoder)
            Log.d(LOG_TAG, "Usando diretamente a mesma câmera do preview")
            return camera to encoder
        }

        selectFromCapabilityMatrix(targetFps, allowHdr)?.let { (camera, encoder) ->
            cacheConfiguration(signature, camera, encoder)
            Log.d(LOG_TAG, "Usando capacidade já analisada pelo aplicativo")
            return camera to encoder
        }

        val diagnostics = mutableListOf<String>()
        val requestOrder = configurationRequestOrder(targetFps)
        val profileCache = mutableMapOf<Int, List<CameraProfile>>()

        for ((size, fps) in requestOrder) {
            val profiles = profileCache.getOrPut(fps) {
                val discovered = if (fps >= CaptureModeStore.FPS_60) {
                    findRegularCameraProfiles(fps, diagnostics, allowHdr) +
                        findHighSpeedCameraProfiles(fps, diagnostics)
                } else {
                    findRegularCameraProfiles(fps, diagnostics, allowHdr)
                }
                discovered.sortedByDescending { profilePriority(it) + preferredCameraScore(it) }
            }

            val matching = profiles
                .asSequence()
                .filter { it.videoSize == size }
                .filter(::profileSatisfiesExplicitStabilization)
                .sortedWith(
                    compareByDescending<CameraProfile> {
                        when {
                            allowHdr && recordingSettings.hdrHlg10 && it.hdrHlg10 -> 2
                            !it.hdrHlg10 -> 1
                            else -> 0
                        }
                    }.thenByDescending { profilePriority(it) + preferredCameraScore(it) }
                )

            for (profile in matching) {
                val encoder = selectBestHardwareEncoder(profile)
                if (encoder == null) {
                    diagnostics += "câmera ${profile.cameraId}: ${sizeName(size)} $fps FPS sem encoder compatível"
                    continue
                }
                cacheConfiguration(signature, profile, encoder)
                return profile to encoder
            }
        }

        val conciseDiagnostics = diagnostics
            .distinct()
            .takeLast(8)
            .joinToString(" | ")
        val requestedLabel = "${CaptureSettings.resolutionLabel(recordingSettings.resolution)} $targetFps FPS"
        throw IllegalStateException(
            "O modo solicitado $requestedLabel não foi aceito nesta tentativa" +
                if (conciseDiagnostics.isBlank()) "" else ": $conciseDiagnostics"
        )
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
        val encoder = findHardwareEncoder(
            mime = mime,
            desiredBitrate = configuredVideoBitrate(),
            size = size,
            fps = fps,
            requireMain10 = allowHdr
        ) ?: return null
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
        preferredCameraId.orEmpty()
    ).joinToString("|")

    /**
     * A captura iniciada pelo preview já conhece a lente em uso. Validar primeiro essa
     * câmera evita percorrer todas as lentes novamente e reduz a pausa entre tocar no
     * obturador e o início do encoder. Se a combinação exata não for aceita, o fluxo
     * completo continua sendo usado; a política final ainda pode preservar a
     * resolução e reduzir apenas o FPS para manter a gravação ativa.
     */
    private fun selectPreferredPreviewConfiguration(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile>? {
        val cameraId = preferredCameraId ?: return null
        val manager = getSystemService(CameraManager::class.java)
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val regularSizes = linkedSetOf<Size>().apply {
            runCatching { map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
            runCatching { map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
            runCatching { map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList()).let(::addAll)
        }
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        val dynamicRange = when {
            allowHdr && supportsHlg10(characteristics) -> DynamicRangeProfiles.HLG10
            allowHdr -> return null
            else -> standardDynamicRangeProfile()
        }

        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps) continue
            var coveringFallback: Pair<CameraProfile, EncoderProfile>? = null
            val highSpeedFirst = fps >= CaptureModeStore.FPS_120
            val sessionKinds = when {
                highSpeedFirst -> listOf(true, false)
                fps >= CaptureModeStore.FPS_60 -> listOf(false, true)
                else -> listOf(false)
            }
            for (highSpeed in sessionKinds) {
                if (highSpeed && size !in highSpeedSizes) continue
                if (!highSpeed && size !in regularSizes) continue
                val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed) ?: continue
                val profile = createCameraProfile(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    videoSize = size,
                    targetFps = fps,
                    fpsRange = fpsRange,
                    highSpeed = highSpeed,
                    dynamicRangeProfile = dynamicRange
                )
                if (!matchesRequestedMode(profile, targetFps)) continue
                if (!profileSatisfiesExplicitStabilization(profile)) continue
                val encoder = selectBestHardwareEncoder(profile) ?: continue
                if (profile.hasExactFpsRange()) return profile to encoder
                if (coveringFallback == null) coveringFallback = profile to encoder
            }
            coveringFallback?.let { return it }
        }
        return null
    }

    /**
     * The capture screen already scans the camera/encoder matrix. Reusing that
     * result avoids walking every lens, size and codec again on every press.
     * HDR still uses the full path because it needs ten-bit profile checks.
     */
    private fun selectFromCapabilityMatrix(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile>? {
        // A matriz descreve resolução/FPS/encoder, mas não conserva a origem lógica
        // ou física do OIS. Com OIS explícito, a varredura completa precisa avaliar
        // as lentes antes de aceitar uma configuração.
        if (allowHdr || explicitOisRequested()) return null
        val matrix = CaptureCapabilityMatrix.cached(this) ?: return null
        val manager = getSystemService(CameraManager::class.java)
        val allowedMimes = recordingSettings.codecMimes(hdr = false)

        for ((size, fps) in configurationRequestOrder(targetFps)) {
            var coveringFallback: Pair<CameraProfile, EncoderProfile>? = null
            val modes = matrix.modes.asSequence()
                .filter { it.size == size && it.fps == fps && it.encoderMime in allowedMimes }
                .filter { preferredCameraId == null || it.cameraId == preferredCameraId }
                .sortedWith(
                    compareByDescending<com.steadyvault.camera.core.capability.CaptureCapabilityMatrix.Mode> {
                        if (preferredCameraId != null && it.cameraId == preferredCameraId) 1 else 0
                    }.thenByDescending { mode ->
                        when {
                            fps >= CaptureModeStore.FPS_120 && mode.highSpeed -> 3
                            fps >= CaptureModeStore.FPS_120 -> 2
                            !mode.highSpeed -> 3
                            else -> 1
                        }
                    }
                )

            for (mode in modes) {
                val characteristics = runCatching {
                    manager.getCameraCharacteristics(mode.cameraId)
                }.getOrNull() ?: continue
                val fpsRange = resolveFpsRange(
                    characteristics = characteristics,
                    size = mode.size,
                    targetFps = mode.fps,
                    highSpeed = mode.highSpeed
                ) ?: continue
                val profile = createCameraProfile(
                    cameraId = mode.cameraId,
                    characteristics = characteristics,
                    videoSize = mode.size,
                    targetFps = mode.fps,
                    fpsRange = fpsRange,
                    highSpeed = mode.highSpeed,
                    dynamicRangeProfile = standardDynamicRangeProfile()
                )
                if (!profileSatisfiesExplicitStabilization(profile)) continue
                val bitrate = configuredVideoBitrate()
                val encoder = findHardwareEncoder(
                    mime = mode.encoderMime,
                    desiredBitrate = bitrate,
                    size = mode.size,
                    fps = mode.fps,
                    requireMain10 = false
                ) ?: continue
                if (
                    matchesRequestedMode(profile, targetFps)
                ) {
                    if (profile.hasExactFpsRange()) return profile to encoder
                    if (coveringFallback == null) coveringFallback = profile to encoder
                }
            }
            coveringFallback?.let { return it }
        }
        return null
    }

    private fun resolveFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int,
        highSpeed: Boolean
    ): Range<Int>? {
        val ranges: List<Range<Int>> = if (highSpeed) {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            runCatching { map.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty() }
                .getOrDefault(emptyList())
        } else {
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList().orEmpty()
        }
        return selectTargetFpsRange(ranges, targetFps)
    }

    /**
     * A faixa fixa exata é sempre a primeira escolha. Para 60 FPS, se a HAL não
     * publicar [60,60] naquele instante, ainda aceitamos uma faixa que alcance 60
     * para não transformar uma limitação transitória em falha de gravação.
     * Se nem isso existir, o fluxo superior tenta 30 FPS na mesma resolução.
     */
    private fun selectTargetFpsRange(
        ranges: List<Range<Int>>,
        targetFps: Int
    ): Range<Int>? {
        ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
        if (StrictCaptureModePolicy.requiresExactFpsRange(targetFps)) {
            return ranges
                .filter { it.lower <= targetFps && it.upper >= targetFps }
                .maxByOrNull { fpsRangeScore(it, targetFps) }
        }
        return ranges
            .filter { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
            .maxByOrNull { fpsRangeScore(it, targetFps) }
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

    private fun selectCaptureConfigurationWithFpsFallback(
        targetFps: Int,
        allowHdr: Boolean
    ): Pair<CameraProfile, EncoderProfile> {
        val fpsOrder = CaptureSettings.supportedFpsValues
            .filter { it <= targetFps }
            .sortedDescending()
            .let { values -> if (targetFps in values) values else listOf(targetFps) + values }
        var primaryError: Throwable? = null
        fpsOrder.forEach { candidateFps ->
            try {
                return selectBestCaptureConfiguration(candidateFps, allowHdr && candidateFps < CaptureModeStore.FPS_120)
            } catch (error: Throwable) {
                if (primaryError == null) primaryError = error else primaryError?.addSuppressed(error)
            }
        }
        throw primaryError ?: IllegalStateException("Nenhuma taxa de quadros pôde ser preparada")
    }

    private fun CameraProfile.hasExactFpsRange(): Boolean =
        fpsRange.lower == targetFps && fpsRange.upper == targetFps

    private fun Size.toPolicyDimensions() = StrictCaptureModePolicy.Dimensions(width, height)

    /**
     * O stream real da gravação é a Surface criada pelo MediaCodec. Usar o menor valor
     * entre PRIVATE/MediaCodec/MediaRecorder podia validar uma câmera cuja Surface do
     * encoder só garantia 30 FPS. Consulte primeiro a classe realmente configurada e
     * use os demais formatos apenas quando o HAL não publicar esse dado.
     */
    private fun encoderSurfaceMinFrameDurationNs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size
    ): Long {
        val encoderDuration = runCatching {
            map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        }.getOrNull()?.takeIf { it > 0L }
        if (encoderDuration != null) return encoderDuration

        val privateDuration = runCatching {
            map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
        }.getOrNull()?.takeIf { it > 0L }
        if (privateDuration != null) return privateDuration

        return runCatching {
            map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        }.getOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private fun findRegularCameraProfiles(
        targetFps: Int,
        diagnostics: MutableList<String>,
        allowHdr: Boolean
    ): List<CameraProfile> {
        val manager = getSystemService(CameraManager::class.java)
        val candidates = mutableListOf<Pair<Long, CameraProfile>>()

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (!isEligibleCamera(cameraId, characteristics)) continue

            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            if (map == null) {
                diagnostics += "câmera $cameraId: sem mapa de streams"
                continue
            }

            val outputSizes = linkedSetOf<Size>().apply {
                runCatching { map.getOutputSizes(ImageFormat.PRIVATE) }
                    .getOrNull()?.let(::addAll)
                runCatching { map.getOutputSizes(MediaCodec::class.java) }
                    .getOrNull()?.let(::addAll)
                runCatching { map.getOutputSizes(MediaRecorder::class.java) }
                    .getOrNull()?.let(::addAll)
            }
            val fpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: emptyArray()
            val selectedRange = selectTargetFpsRange(fpsRanges.toList(), targetFps)

            if (selectedRange == null) {
                diagnostics += if (StrictCaptureModePolicy.requiresExactFpsRange(targetFps)) {
                    "câmera $cameraId: nenhuma faixa alcança $targetFps fps; fallback inferior será tentado"
                } else {
                    "câmera $cameraId: sem faixa compatível com $targetFps fps"
                }
                continue
            }

            for (size in recordingSettings.preferredSizes()) {
                if (!outputSizes.contains(size)) continue

                val minFrameDuration = encoderSurfaceMinFrameDurationNs(map, size)
                val exactRange = selectedRange.lower == targetFps && selectedRange.upper == targetFps
                val timingIsValid = minFrameDuration <= 0L ||
                        minFrameDuration <= frameDurationNs(targetFps) + FRAME_DURATION_TOLERANCE_NS

                if (!timingIsValid) {
                    diagnostics += "câmera $cameraId: ${sizeName(size)} $targetFps FPS não foi confirmado pelo tempo mínimo público; a combinação será testada diretamente"
                }

                val timingScore = when {
                    timingIsValid && exactRange -> 220_000_000L
                    timingIsValid -> 120_000_000L
                    else -> 20_000_000L
                }
                val stabilityScore = sizePreferenceScore(size)
                val sdrProfile = createCameraProfile(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    videoSize = size,
                    targetFps = targetFps,
                    fpsRange = selectedRange,
                    highSpeed = false,
                    dynamicRangeProfile = standardDynamicRangeProfile()
                )
                candidates += cameraScore(sdrProfile) + timingScore + stabilityScore to sdrProfile

                if (allowHdr && supportsHlg10(characteristics)) {
                    val hdrProfile = createCameraProfile(
                        cameraId = cameraId,
                        characteristics = characteristics,
                        videoSize = size,
                        targetFps = targetFps,
                        fpsRange = selectedRange,
                        highSpeed = false,
                        dynamicRangeProfile = DynamicRangeProfiles.HLG10
                    )
                    candidates += cameraScore(hdrProfile) + timingScore + HDR_SCORE_BONUS to hdrProfile
                }
            }
        }

        return candidates.sortedByDescending { it.first }.map { it.second }
    }

    private fun findHighSpeedCameraProfiles(
        targetFps: Int,
        diagnostics: MutableList<String>
    ): List<CameraProfile> {
        val manager =
            getSystemService(
                CameraManager::class.java
            )

        val candidates =
            mutableListOf<Pair<Long, CameraProfile>>()

        for (cameraId in manager.cameraIdList) {
            val characteristics =
                manager.getCameraCharacteristics(
                    cameraId
                )

            if (!isEligibleCamera(cameraId, characteristics)) continue

            val capabilities =
                characteristics.get(
                    CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES
                ) ?: intArrayOf()

            if (
                !capabilities.contains(
                    CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
                )
            ) {
                diagnostics +=
                    "câmera $cameraId: sem capacidade high-speed"
                continue
            }

            val map =
                characteristics.get(
                    CameraCharacteristics
                        .SCALER_STREAM_CONFIGURATION_MAP
                )

            if (map == null) {
                diagnostics +=
                    "câmera $cameraId: sem mapa high-speed"
                continue
            }

            val highSpeedSizes =
                runCatching {
                    map.highSpeedVideoSizes
                        .toList()
                }.getOrDefault(
                    emptyList()
                )

            for (size in highSpeedSizes) {
                if (size !in recordingSettings.preferredSizes()) continue
                if (size.width * 9 != size.height * 16) continue

                val ranges =
                    runCatching {
                        map.getHighSpeedVideoFpsRangesFor(
                            size
                        )
                            .toList()
                    }.getOrDefault(
                        emptyList()
                    )

                val selectedRange = selectTargetFpsRange(ranges, targetFps) ?: continue

                val sdrProfile = createCameraProfile(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    videoSize = size,
                    targetFps = targetFps,
                    fpsRange = selectedRange,
                    highSpeed = true,
                    dynamicRangeProfile = standardDynamicRangeProfile()
                )
                val candidateScore = highSpeedSizeScore(size) + cameraScore(sdrProfile)
                candidates += candidateScore to sdrProfile

                diagnostics +=
                    "câmera $cameraId: " +
                            "${size.width}×${size.height} " +
                            "${selectedRange.lower}-${selectedRange.upper} fps"
            }
        }

        return candidates
            .sortedByDescending {
                it.first
            }
            .map {
                it.second
            }
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

    private fun isEligibleCamera(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): Boolean {
        val selected = preferredCameraId
        if (!selected.isNullOrBlank()) return cameraId == selected
        return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun explicitOisRequested(): Boolean = recordingSettings.stabilization == CaptureSettings.STABILIZATION_OIS

    private fun profileSatisfiesExplicitStabilization(profile: CameraProfile): Boolean = when (recordingSettings.stabilization) {
        CaptureSettings.STABILIZATION_OIS -> profile.oisSupported
        CaptureSettings.STABILIZATION_EIS -> profile.eisSupported
        CaptureSettings.STABILIZATION_PREVIEW -> !profile.highSpeed && profile.previewStabilizationSupported
        else -> true
    }

    private fun preferredCameraCompatible(profile: CameraProfile): Boolean {
        val selected = preferredCameraId ?: return true
        return profile.cameraId == selected
    }

    private fun preferredCameraScore(profile: CameraProfile): Long =
        if (preferredCameraId != null && profile.cameraId == preferredCameraId) PREFERRED_PREVIEW_CAMERA_SCORE else 0L

    private fun profilePriority(profile: CameraProfile): Long {
        // Em 30/60/120/240, uma faixa AE fixa publicada pela própria HAL vem antes
        // de heurísticas de lente/sessão. Se ela não existir, a faixa abrangente
        // compatível segue como fallback, sem alterar a escolha do usuário.
        val fixedCadenceScore = if (profile.hasExactFpsRange()) FIXED_FPS_RANGE_SCORE else 0L
        val sessionScore = when {
            profile.targetFps >= CaptureModeStore.FPS_120 && profile.highSpeed -> 4_000_000_000L
            profile.targetFps >= CaptureModeStore.FPS_120 -> 3_000_000_000L
            profile.highSpeed -> 1_500_000_000L
            else -> 2_000_000_000L
        }
        val stabilizationScore = when {
            explicitOisRequested() && profile.oisSupported -> REQUESTED_OIS_CAMERA_SCORE
            recordingSettings.stabilization == CaptureSettings.STABILIZATION_EIS && profile.eisSupported -> REQUESTED_EIS_CAMERA_SCORE
            recordingSettings.stabilization == CaptureSettings.STABILIZATION_PREVIEW && !profile.highSpeed && profile.previewStabilizationSupported -> REQUESTED_EIS_CAMERA_SCORE
            else -> 0L
        }
        return fixedCadenceScore + sessionScore + sizePreferenceScore(profile.videoSize) + cameraScore(profile) +
                stabilizationScore +
                if (profile.hdrHlg10) HDR_SCORE_BONUS else 0L
    }

    private fun sizePreferenceScore(size: Size): Long {
        val index = recordingSettings.preferredSizes().indexOf(size)
        if (index < 0) return Long.MIN_VALUE / 4
        return (recordingSettings.preferredSizes().size - index).toLong() * 2_000_000_000L +
                size.width.toLong() * size.height.toLong()
    }

    private fun cameraScore(
        profile: CameraProfile
    ): Long {
        val capabilities =
            profile.characteristics.get(
                CameraCharacteristics
                    .REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()

        val isLogical =
            capabilities.contains(
                CameraCharacteristics
                    .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )

        val hardwareLevel =
            profile.characteristics.get(
                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL
            ) ?: CameraCharacteristics
                .INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        val levelScore =
            when (hardwareLevel) {
                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_3 ->
                    400_000_000L

                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_FULL ->
                    300_000_000L

                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ->
                    200_000_000L

                else ->
                    0L
            }

        return (
                if (isLogical) {
                    1_000_000_000L
                } else {
                    0L
                }
                ) + levelScore
    }

    private fun highSpeedSizeScore(
        size: Size
    ): Long {
        val area =
            size.width.toLong() *
                    size.height.toLong()

        return when (size) {
            EIGHT_K_SIZE ->
                16_000_000_000L + area

            UHD_SIZE ->
                12_000_000_000L + area

            FHD_SIZE ->
                8_000_000_000L + area

            HD_SIZE ->
                4_000_000_000L + area

            else ->
                area
        }
    }

    private fun fpsRangeScore(
        range: Range<Int>,
        targetFps: Int
    ): Int {
        val exact =
            range.lower == targetFps &&
                    range.upper == targetFps

        val endsAtTarget =
            range.upper == targetFps

        return when {
            exact ->
                100_000

            endsAtTarget ->
                50_000 +
                        range.lower * 100

            else ->
                10_000 +
                        range.lower * 100 -
                        kotlin.math.abs(
                            range.upper -
                                    targetFps
                        )
        }
    }

    private fun selectBestHardwareEncoder(profile: CameraProfile): EncoderProfile? {
        val mime = recordingSettings.codecMimes(profile.hdrHlg10).singleOrNull() ?: return null
        return findHardwareEncoder(
            mime = mime,
            desiredBitrate = configuredVideoBitrate(),
            size = profile.videoSize,
            fps = profile.targetFps,
            requireMain10 = profile.hdrHlg10
        )
    }

    private fun configuredVideoBitrate(): Int =
        (recordingSettings.bitrateMbps * 1_000_000L)
            .coerceIn(4_000_000L, MAX_VIDEO_BITRATE.toLong())
            .toInt()

    private fun findHardwareEncoder(
        mime: String,
        desiredBitrate: Int,
        size: Size,
        fps: Int,
        requireMain10: Boolean
    ): EncoderProfile? {
        val targetPerformancePoint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint(size.width, size.height, fps)
        } else {
            null
        }
        var best: Pair<Long, EncoderProfile>? = null

        for (codecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codecInfo.isEncoder || codecInfo.isSoftwareOnly) continue
            if (!codecInfo.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val capabilities = runCatching { codecInfo.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val profileLevel = preferredEncoderProfileLevel(mime, capabilities, requireMain10)
            if (requireMain10 && profileLevel == null) continue
            val videoCapabilities = capabilities.videoCapabilities ?: continue
            if (!runCatching { videoCapabilities.isSizeSupported(size.width, size.height) }.getOrDefault(false)) continue

            val bitrateRange = videoCapabilities.bitrateRange
            val bitrate = desiredBitrate.coerceIn(videoCapabilities.bitrateRange.lower, videoCapabilities.bitrateRange.upper)
            val candidate = EncoderProfile(codecInfo.name, mime, bitrate, requireMain10, profileLevel?.first, profileLevel?.second)
            val exactRateSupported = runCatching {
                videoCapabilities.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
            }.getOrDefault(false)
            val performanceGuaranteed = targetPerformancePoint != null && runCatching {
                videoCapabilities.supportedPerformancePoints?.any { it.covers(targetPerformancePoint) } == true
            }.getOrDefault(false)
            val bitrateUnclamped = desiredBitrate in bitrateRange

            // A ordem do MediaCodecList não é uma garantia de desempenho. Para 4K60/HFR,
            // priorize o codec que o próprio fabricante garante por PerformancePoint;
            // depois considere suporte exato, taxa medida, vendor/hardware e bitrate.
            val score =
                (if (performanceGuaranteed) 1_000_000_000L else 0L) +
                (if (exactRateSupported) 500_000_000L else 0L) +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isVendor) 100_000_000L else 0L) +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isHardwareAccelerated) 50_000_000L else 0L) +
                (if (bitrateUnclamped) 10_000_000L else 0L) +
                bitrate.toLong().coerceAtMost(9_000_000L)

            if (best == null || score > best!!.first) best = score to candidate
        }
        return best?.second
    }

    private fun preferredEncoderProfileLevel(
        mime: String,
        capabilities: MediaCodecInfo.CodecCapabilities,
        requireMain10: Boolean
    ): Pair<Int, Int>? {
        val preferredProfiles = when {
            requireMain10 -> intArrayOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> intArrayOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            mime == MediaFormat.MIMETYPE_VIDEO_AVC -> intArrayOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            else -> intArrayOf()
        }
        preferredProfiles.forEach { profile ->
            val level = capabilities.profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level }
            if (level != null) return profile to level
        }
        return null
    }

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
        encoderProfile: EncoderProfile
    ): Boolean {
        val audioBitrateBps = if (hasAudioPermission()) {
            recordingSettings.audioBitrateKbps.toLong() * 1_000L
        } else {
            0L
        }
        val check = RecordingStorageGuard.check(
            context = this,
            videoBitrateBps = encoderProfile.bitrate.toLong(),
            audioBitrateBps = audioBitrateBps
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
        // O cofre e o cache interno usam o mesmo armazenamento privado. Evitar
        // externalCacheDir retira a camada FUSE do caminho crítico do muxer e reduz
        // picos de latência de escrita durante 4K60/120 FPS.
        val rawFile = File.createTempFile(
            RAW_FILE_PREFIX + cameraProfile.targetFps + "_",
            ".mp4",
            cacheDir
        )
        val recorder = HardwareRecorder(
            outputFile = rawFile,
            videoConfig = HardwareRecorder.VideoConfig(
                codecName = encoderProfile.codecName,
                mime = encoderProfile.mime,
                width = cameraProfile.videoSize.width,
                height = cameraProfile.videoSize.height,
                fps = cameraProfile.targetFps,
                bitrate = encoderProfile.bitrate,
                orientationHint = calculateOrientationHint(cameraProfile.sensorOrientation),
                hdrHlg10 = encoderProfile.hdrHlg10,
                profile = encoderProfile.profile,
                level = encoderProfile.level,
                iFrameIntervalSeconds = recordingSettings.iFrameIntervalSeconds
            ),
            audioConfig = HardwareRecorder.AudioConfig(
                enabled = hasAudioPermission(),
                sampleRate = recordingSettings.audioSampleRate,
                bitrate = recordingSettings.audioBitrateKbps * 1_000,
                channels = recordingSettings.audioChannels,
                gainDb = recordingSettings.audioGainDb,
                agcEnabled = recordingSettings.audioAgc,
                noiseSuppressorEnabled = recordingSettings.audioNoiseSuppressor,
                lowCutEnabled = recordingSettings.audioLowCut,
                microphoneDirection = when (cameraProfile.characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER
                    CameraCharacteristics.LENS_FACING_BACK -> MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER
                    else -> MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
                }
            ),
            onError = { throwable ->
                if (!stopping.get() && serviceActive.get()) {
                    failAndStop("encoder profissional: ${errorText(throwable)}")
                }
            }
        )

        try {
            val surface = recorder.prepare()
            synchronized(resourceLock) {
                finalOutputFile = finalFile
                rawOutputFile = rawFile
                professionalRecorder = recorder
                recorderSurface = surface
            }
        } catch (t: Throwable) {
            runCatching { recorder.release() }
            runCatching { rawFile.delete() }
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
                    cancelInitialCameraAvailabilityRetry()

                    synchronized(resourceLock) {
                        cameraDevice = camera
                    }

                    if (headlessCaptureRequested) {
                        maybeCreateHeadlessSession(camera, profile, token)
                    } else {
                        createRecordingSession(camera, profile, token)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handleCameraInterruption(
                        profile,
                        token,
                        "a câmera foi desconectada",
                        temporarilyBusy = !recorderStarted
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    handleCameraInterruption(
                        profile,
                        token,
                        "erro da câmera: $error",
                        temporarilyBusy =
                            error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                                error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                    )
                }

                override fun onClosed(camera: CameraDevice) {
                    synchronized(resourceLock) {
                        if (cameraDevice === camera) cameraDevice = null
                    }
                }
                }
            )
        } catch (throwable: CameraAccessException) {
            if (
                headlessCaptureRequested &&
                !recorderStarted &&
                isTemporaryCameraBusy(throwable)
            ) {
                scheduleInitialCameraAvailabilityRetry(
                    profile,
                    token,
                    errorText(throwable)
                )
            } else {
                throw throwable
            }
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
            session.setRepeatingBurst(requests, null, null)
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
            // Request definitivo: instalado uma vez e nunca substituído durante o vídeo.
            armRecorderForFirstFrame(token)
            session.setRepeatingRequest(request, null, null)
            commitRecorderStart(profile, token, highSpeed = false)
        }.onFailure {
            failSelectedConfigurationFromWorker(token, "não foi possível iniciar ${profile.targetFps} FPS: ${errorText(it)}")
        }
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

        // O encoder foi armado antes do primeiro repeating request, mas ainda
        // descartava qualquer saída antiga. Confirme a época somente depois que a
        // sessão aceitou o request definitivo e então peça o primeiro IDR do arquivo.
        val resumed = synchronized(resourceLock) {
            if (recorderStarted) {
                true
            } else {
                check(recorderArmed) { "encoder não foi armado antes da câmera" }
                professionalRecorder?.commitStart()
                    ?: throw IllegalStateException("encoder profissional indisponível")
                recorderStarted = true
                recordingWasEverStarted = true
                recordingStartedAtMs = System.currentTimeMillis()
                recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                false
            }
        }

        cameraRecoveryScheduled.set(false)
        cameraRecoveryAttempts = 0
        if (!resumed) {
            logRecordingStartup(profile)
            logFpsFallbackIfNeeded(profile)
            CaptureStateStore.updateEffectiveMode(this, resolutionValue(profile.videoSize), sizeName(profile.videoSize), profile.targetFps)
            if (recordingSettings.vibrateStartStop) Haptics.start(this)
            startMicrophoneWithoutBlockingVideo(token)
        }

        val suffix = if (highSpeed) "high-speed" else stabilizationName(profile)
        val fpsStatus = when {
            profile.targetFps < requestedTargetFps -> "⚠ ${profile.targetFps} FPS (pedido ${requestedTargetFps})"
            !profile.hasExactFpsRange() ->
                "⚠ ${profile.fpsRange.lower}–${profile.fpsRange.upper} FPS (pedido ${requestedTargetFps} fixo)"
            else -> "${profile.targetFps} FPS"
        }
        val message = "Gravando ${sizeName(profile.videoSize)} • $fpsStatus • " +
            "${if (profile.hdrHlg10) "HLG10" else "SDR BT.709"} • ${encoderName()} • $suffix"
        sendStateOnMain(message)
        updateNotificationOnMain(message)
        startRecordingHealthWatchdog()
    }

    /**
     * Android 16 pode levar algumas tentativas para aceitar o tipo FGS de microfone.
     * Essa espera roda fora da thread Camera2 e depois do vídeo já estar armado.
     * Se o microfone continuar indisponível, preserva a captura como vídeo-only.
     */
    private fun startMicrophoneWithoutBlockingVideo(token: Int) {
        if (!hasAudioPermission()) {
            synchronized(resourceLock) { professionalRecorder?.continueWithoutAudio() }
            return
        }
        encoderPreparationExecutor.execute {
            if (!isAttemptValid(token) || stopping.get() || userRequestedStop) return@execute

            val promoted = runCatching {
                promoteForegroundForMicrophoneWithRetry("Gravando ${requestedProfileLabel()}…")
                true
            }.getOrElse { throwable ->
                Log.w(LOG_TAG, "Microfone indisponível; mantendo a gravação de vídeo", throwable)
                AppLogRepository.warn(this, "recording", "Microfone indisponível; vídeo continua sem áudio: ${errorText(throwable)}")
                false
            }

            if (!isAttemptValid(token) || stopping.get() || userRequestedStop) return@execute

            val audioStarted = if (promoted) {
                synchronized(resourceLock) { professionalRecorder?.startAudioCapture() == true }
            } else {
                false
            }

            if (!audioStarted) {
                synchronized(resourceLock) { professionalRecorder?.continueWithoutAudio() }
                updateNotificationOnMain("Gravando vídeo • áudio indisponível")
            }
        }
    }

    private fun createRecordRequestBuilder(
        camera: CameraDevice
    ): CaptureRequest.Builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

    private fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
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

        val exposureRange = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureRange?.let {
            setSafely(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, recordingSettings.exposureCompensation.coerceIn(it.lower, it.upper))
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

        if (profile.targetFps >= CaptureModeStore.FPS_60) {
            if (modes.contains(CameraMetadata.TONEMAP_MODE_FAST)) {
                setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }
            return
        }

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
        fun setVideoMode(mode: Int) {
            runCatching { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode) }
        }
        fun eis(mode: Int) {
            setVideoMode(mode)
            OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
        }
        fun ois() {
            setVideoMode(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = true)
        }
        fun off() {
            setVideoMode(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
        }

        when (resolveStabilization(profile)) {
            EffectiveStabilization.OFF -> off()
            EffectiveStabilization.PREVIEW -> eis(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            EffectiveStabilization.EIS -> eis(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            EffectiveStabilization.OIS -> ois()
        }
    }

    private fun resolveStabilization(profile: CameraProfile): EffectiveStabilization = when (recordingSettings.stabilization) {
        CaptureSettings.STABILIZATION_OFF -> EffectiveStabilization.OFF
        CaptureSettings.STABILIZATION_PREVIEW -> if (!profile.highSpeed && profile.previewStabilizationSupported) EffectiveStabilization.PREVIEW else EffectiveStabilization.OFF
        CaptureSettings.STABILIZATION_EIS -> if (profile.eisSupported) EffectiveStabilization.EIS else EffectiveStabilization.OFF
        CaptureSettings.STABILIZATION_OIS -> if (profile.oisSupported) EffectiveStabilization.OIS else EffectiveStabilization.OFF
        else -> EffectiveStabilization.OFF
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
        cancelInitialCameraAvailabilityRetry()
        runtimeStorageMonitor.stop()
        stopRecordingHealthWatchdog()

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

        sendState("Finalizando ${requestedProfileLabel()}…")
        updateNotification("Finalizando ${requestedProfileLabel()}…")

        cameraExecutor.execute {
            val session = synchronized(resourceLock) { captureSession }
            val drainTail = recorderStarted && session != null
            if (drainTail) runCatching { session?.stopRepeating() }
            val delayMs = if (drainTail) {
                RecordingStopPolicy.tailDrainMs(selectedCamera?.targetFps ?: requestedTargetFps)
            } else {
                0L
            }
            if (delayMs > 0L) {
                mainHandler.postDelayed({ cameraExecutor.execute { stopRecorderAndFinalize() } }, delayMs)
            } else {
                stopRecorderAndFinalize()
            }
        }
    }

    private fun stopRecorderAndFinalize() {
        val claimed = claimRecording()
        val finalFile = claimed.output
        val rawFile = claimed.raw
        val recorder = claimed.recorder
        val validOutput = claimed.wasStarted &&
            runCatching { recorder?.stop() == true }.getOrDefault(false)
        val cadenceStats = recorder?.videoCadenceStats()

        releaseCameraOnly()
        runCatching { recorder?.release() }
        sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))

        val rawContainsData =
            rawFile?.isFile == true &&
                rawFile.length() > 0L
        if (!validOutput || finalFile == null || rawFile == null || !rawFile.isFile || rawFile.length() <= 0L) {
            if (
                finalFile != null &&
                rawFile != null &&
                (recordingStartedAtMs > 0L || rawContainsData)
            ) {
                finishWithRecoveredPartial(
                    rawFile,
                    finalFile,
                    "o encoder não conseguiu finalizar o arquivo"
                )
            } else {
                // Um clique na notificação pode chegar entre o primeiro byte do muxer
                // e a atualização do marcador de início. Nunca apaga um bruto que já
                // recebeu dados; ele fica reservado para a recuperação automática.
                if (!rawContainsData) runCatching { rawFile?.delete() }
                runCatching { finalFile?.delete() }
                sendStateOnMain(
                    when {
                        recoveredSegmentCount > 0 ->
                            "Vídeo salvo no cofre • trecho de segurança preservado"
                        rawContainsData ->
                            "Trecho temporário preservado • recuperação automática pendente"
                        else ->
                            "Nenhum quadro foi produzido antes da parada"
                    }
                )
                finishServiceOnMain()
            }
            return
        }

        val profile = selectedCamera
        val fps = profile?.targetFps ?: requestedTargetFps
        logMeasuredCadence(cadenceStats, fps, profile)

        sendStateOnMain("Salvando original no cofre…")
        updateNotificationOnMain("Salvando original no cofre…")
        val published = runCatching {
            RecordingFilePublisher.publish(rawFile, finalFile) { candidate ->
                RecordingRecoveryRepository.hasUsableVideo(candidate) &&
                    (profile?.hdrHlg10 != true || hasHlgVideoTrack(candidate))
            }
        }.isSuccess
        if (!published) {
            finishWithRecoveredPartial(
                rawFile,
                finalFile,
                "não foi possível publicar o arquivo original validado"
            )
            return
        }

        if (!stopHapticAcknowledged && recordingSettings.vibrateStartStop) Haptics.stop(this)

        val size = profile?.videoSize ?: recordingSettings.preferredSizes().first()
        val durationSeconds = if (recordingStartedAtElapsedMs > 0L) {
            ((SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) / 1000L).coerceAtLeast(0L)
        } else 0L
        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10 • ${encoderName()}" else "SDR BT.709 • ${encoderName()}"
        // O MP4 direto da câmera é o resultado final. Nenhuma leitura, thumbnail,
        // correção ou transcode é iniciado automaticamente depois de gravar.
        // As ferramentas manuais permanecem disponíveis no Cofre.
        val repairStatus = "Original salvo sem processamento automático"
        val message = buildString {
            append("Vídeo salvo no cofre\n")
            append(sizeName(size)).append(" • ").append(cadenceDisplay(cadenceStats, fps)).append(" • ")
            append(qualityLabel).append(" • ").append(formatDuration(durationSeconds))
            append('\n').append(repairStatus)
        }
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
        runCatching { finalFile.delete() }
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
        val safeOutput = claimed.output
        val safeRawFile = claimed.raw
        val preserved = if (
            safeOutput != null &&
            safeRawFile != null &&
            safeRawFile.isFile &&
            safeRawFile.length() > 0L
        ) {
            runCatching {
                RecordingFilePublisher.publish(
                    safeRawFile,
                    safeOutput,
                    RecordingRecoveryRepository::hasUsableVideo
                )
            }.isSuccess
        } else {
            false
        }

        if (preserved) runCatching { safeRawFile?.delete() }
        if (!preserved) runCatching { safeOutput?.delete() }
        return preserved
    }

    /**
     * Um erro irrecuperável do encoder encerra apenas o arquivo atual. O trecho
     * válido é publicado no cofre e uma nova parte começa automaticamente com o
     * mesmo perfil. Assim, falha de HAL/codec não vira um comando Parar oculto.
     */
    private fun recoverRecordingSegment(message: String) {
        if (
            !serviceActive.get() ||
            userRequestedStop ||
            !recorderStarted ||
            !segmentRecoveryInProgress.compareAndSet(false, true)
        ) {
            return
        }
        stopRecordingHealthWatchdog()
        cameraExecutor.execute {
            recoverRecordingSegmentFromWorker(message)
        }
    }

    private fun recoverRecordingSegmentFromWorker(message: String) {
        if (!serviceActive.get() || userRequestedStop) {
            segmentRecoveryInProgress.set(false)
            return
        }

        val preserved = preserveInterruptedRecording()
        if (preserved) recoveredSegmentCount++
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        cameraRecoveryScheduled.set(false)
        cameraRecoveryAttempts = 0
        recordingStartedAtMs = 0L
        recordingStartedAtElapsedMs = 0L
        val nextToken = ++attemptToken

        val status = if (preserved) {
            "Trecho salvo no cofre • continuando a gravação automaticamente…"
        } else {
            "Recuperando a gravação automaticamente…"
        }
        Log.w(LOG_TAG, "$status motivo=$message")
        AppLogRepository.warn(this, "recording", "$status motivo=$message")
        sendStateOnMain(status)
        updateNotificationOnMain(status)
        refreshRecordingServiceHeartbeat(force = true)

        mainHandler.postDelayed(
            {
                segmentRecoveryInProgress.set(false)
                if (
                    serviceActive.get() &&
                    !userRequestedStop &&
                    !stopping.get()
                ) {
                    cameraExecutor.execute {
                        prepareInitialConfigurationBeforeCameraHandoff(nextToken)
                    }
                }
            },
            SEGMENT_RESTART_DELAY_MS
        )
    }

    private fun retryRecordingSession(message: String) {
        if (
            !serviceActive.get() ||
            userRequestedStop ||
            !recordingWasEverStarted ||
            !segmentRecoveryInProgress.compareAndSet(false, true)
        ) {
            return
        }
        stopRecordingHealthWatchdog()
        cameraExecutor.execute {
            retryRecordingSessionFromWorker(message)
        }
    }

    private fun retryRecordingSessionFromWorker(message: String) {
        if (!serviceActive.get() || userRequestedStop) {
            segmentRecoveryInProgress.set(false)
            return
        }

        val nextToken = ++attemptToken
        releaseRecordingResources(deleteOutput = true)
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        selectedCamera = null
        selectedEncoder = null
        cameraRecoveryScheduled.set(false)
        cameraRecoveryAttempts = 0
        sendStateOnMain("Continuando a gravação após uma interrupção temporária…")
        updateNotificationOnMain("Retomando a gravação automaticamente…")
        Log.w(LOG_TAG, "Reiniciando segmento automaticamente: $message")
        refreshRecordingServiceHeartbeat(force = true)

        mainHandler.postDelayed(
            {
                segmentRecoveryInProgress.set(false)
                if (
                    serviceActive.get() &&
                    !userRequestedStop &&
                    !stopping.get()
                ) {
                    cameraExecutor.execute {
                        prepareInitialConfigurationBeforeCameraHandoff(nextToken)
                    }
                }
            },
            SEGMENT_RETRY_DELAY_MS
        )
    }

    private fun failAndStop(message: String) {
        if (!serviceActive.get()) return
        AppLogRepository.error(this, "recording", message)
        if (recorderStarted && !userRequestedStop) {
            recoverRecordingSegment(message)
            return
        }
        if (recordingWasEverStarted && !userRequestedStop) {
            retryRecordingSession(message)
            return
        }

        userRequestedStop = true
        stopping.set(true)
        stopRecordingHealthWatchdog()

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
        if (recorderStarted && !userRequestedStop) {
            if (segmentRecoveryInProgress.compareAndSet(false, true)) {
                stopRecordingHealthWatchdog()
                recoverRecordingSegmentFromWorker(message)
            }
            return
        }
        if (recordingWasEverStarted && !userRequestedStop) {
            if (segmentRecoveryInProgress.compareAndSet(false, true)) {
                stopRecordingHealthWatchdog()
                retryRecordingSessionFromWorker(message)
            }
            return
        }

        userRequestedStop = true
        stopping.set(true)
        stopRecordingHealthWatchdog()
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
        cameraRecoveryScheduled.set(false)
        cancelInitialCameraAvailabilityRetry()
        segmentRecoveryInProgress.set(false)
        cameraRecoveryAttempts = 0
        recorderStarted = false
        runtimeStorageMonitor.stop()
        stopRecordingHealthWatchdog()
        CaptureStateStore.clearRecordingServiceHeartbeat(this)
        CaptureStateStore.completeSessionIfBusy(this, captureSessionId)
        WidgetRenderer.forceRecordingControls(this)
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        releaseWakeLock()
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

    private fun refreshRecordingServiceHeartbeat(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (
            force ||
            now - lastServiceHeartbeatElapsedMs >= SERVICE_HEARTBEAT_INTERVAL_MS
        ) {
            lastServiceHeartbeatElapsedMs = now
            CaptureStateStore.markRecordingServiceAlive(this)
        }
    }

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
    private fun startForegroundCameraOnly(text: String) {
        startForegroundNow(text, includeMicrophone = false)
    }

    /**
     * Promove para câmera+microfone só quando o encoder já está pronto para gravar.
     * Se o AppOp ainda estiver em transição, repete apenas a promoção e mantém câmera,
     * sessão e encoder intactos.
     */
    private fun promoteForegroundForMicrophoneWithRetry(text: String) {
        var lastFailure: Throwable? = null
        repeat(FOREGROUND_PERMISSION_START_ATTEMPTS) { attempt ->
            try {
                startForegroundNow(text, includeMicrophone = true)
                return
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (!isForegroundPermissionRace(throwable)) throw throwable
                if (attempt < FOREGROUND_PERMISSION_START_ATTEMPTS - 1) {
                    SystemClock.sleep(FOREGROUND_PERMISSION_RETRY_DELAY_MS * (attempt + 1L))
                }
            }
        }
        throw lastFailure ?: IllegalStateException("falha desconhecida ao promover serviço para microfone")
    }

    private fun abortBeforeCaptureStart() {
        runtimeStorageMonitor.stop()
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
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
                acquire(MAX_WAKE_LOCK_MS)
            }
        wakeLockAcquiredAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }
        wakeLock = null
        wakeLockAcquiredAtElapsedMs = 0L
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

    private fun stabilizationName(profile: CameraProfile): String = when (recordingSettings.stabilization) {
        CaptureSettings.STABILIZATION_PREVIEW -> if (resolveStabilization(profile) == EffectiveStabilization.PREVIEW) "preview stabilization" else "preview indisponível"
        CaptureSettings.STABILIZATION_EIS -> if (resolveStabilization(profile) == EffectiveStabilization.EIS) "EIS" else "EIS indisponível"
        CaptureSettings.STABILIZATION_OIS -> if (resolveStabilization(profile) == EffectiveStabilization.OIS) "OIS" else "OIS indisponível"
        CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
        else -> "estabilização inválida"
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
        segmentRecoveryInProgress.set(false)
        cancelInitialCameraAvailabilityRetry()
        stopRecordingHealthWatchdog()
        unregisterScreenTransitionReceiver()
        CaptureStateStore.clearRecordingServiceHeartbeat(this)
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
        if (::runtimeStorageMonitor.isInitialized) runtimeStorageMonitor.close()
        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)
        super.onDestroy()
    }

    private fun cadenceDisplay(
        stats: HardwareRecorder.VideoCadenceStats?,
        targetFps: Int
    ): String {
        if (stats == null || stats.frames < 2L || stats.effectiveFps <= 0.0) return "$targetFps FPS"
        return if (stats.effectiveFps < targetFps * 0.99) {
            "⚠ ${String.format(Locale.US, "%.2f", stats.effectiveFps)} FPS reais (pedido $targetFps)"
        } else {
            "${String.format(Locale.US, "%.2f", stats.effectiveFps)} FPS reais"
        }
    }

    private fun logMeasuredCadence(
        stats: HardwareRecorder.VideoCadenceStats?,
        targetFps: Int,
        profile: CameraProfile?
    ) {
        if (stats == null || stats.frames < 2L || stats.effectiveFps <= 0.0) return
        val message =
            "Cadência medida: ${String.format(Locale.US, "%.3f", stats.effectiveFps)} FPS, " +
                "alvo=$targetFps, quadros=${stats.frames}, lacunasLongas=${stats.longGaps}, " +
                "maiorLacuna=${stats.maxGapUs / 1000.0}ms, filaMuxPico=${stats.muxQueuePeak}, " +
                "câmera=${profile?.cameraId ?: "?"}, resolução=${profile?.videoSize?.width ?: 0}x${profile?.videoSize?.height ?: 0}"
        if (stats.effectiveFps < targetFps * 0.99 || stats.longGaps > 0L) {
            AppLogRepository.warn(this, "recording_fps", message)
            Log.w(LOG_TAG, message)
        } else {
            AppLogRepository.info(this, "recording_fps", message)
            Log.i(LOG_TAG, message)
        }
    }

    private fun logRecordingStartup(profile: CameraProfile) {
        val requestedAt = recordingRequestedAtElapsedNs
        if (requestedAt <= 0L) return
        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - requestedAt) / 1_000_000L).coerceAtLeast(0L)
        Log.i(
            LOG_TAG,
            "Gravação ${sizeName(profile.videoSize)} ${profile.targetFps} FPS iniciada em ${elapsedMs} ms"
        )
    }

    private fun logFpsFallbackIfNeeded(profile: CameraProfile) {
        if (fpsFallbackWarningLogged) return
        val warning = when {
            profile.targetFps < requestedTargetFps ->
                "FPS reduzido para manter a gravação: solicitado=${requestedTargetFps}, efetivo=${profile.targetFps}, faixaAE=${profile.fpsRange.lower}-${profile.fpsRange.upper}, câmera=${profile.cameraId}, resolução=${profile.videoSize.width}x${profile.videoSize.height}"
            !profile.hasExactFpsRange() ->
                "FPS fixo não pôde ser garantido; gravação mantida: solicitado=${requestedTargetFps}-${requestedTargetFps}, efetivo=${profile.fpsRange.lower}-${profile.fpsRange.upper}, câmera=${profile.cameraId}, resolução=${profile.videoSize.width}x${profile.videoSize.height}"
            else -> null
        } ?: return
        fpsFallbackWarningLogged = true
        Log.w(LOG_TAG, warning)
        AppLogRepository.warn(this, "recording_fps", warning)
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
        private const val CAMERA_RECOVERY_DELAY_MS = 350L
        private const val CAMERA_RECOVERY_MAX_DELAY_STEPS = 8
        private const val CAMERA_RECOVERY_MAX_DELAY_MS = 2_800L
        private const val INITIAL_CAMERA_BUSY_RETRY_MS = 90L
        private const val RECORDING_HEALTH_CHECK_INTERVAL_MS = 1_000L
        private const val SCREEN_TRANSITION_HEALTH_DELAY_MS = 650L
        private const val VIDEO_FRAME_STALL_RECOVERY_MS = 1_500L
        private const val FIRST_VIDEO_FRAME_GRACE_MS = 3_500L
        private const val SEGMENT_RESTART_DELAY_MS = 500L
        private const val SEGMENT_RETRY_DELAY_MS = 1_000L

        private const val CAPTURE_PIPELINE_REVISION = "manual-direct-single-buffer-1.8.221"
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

        private const val PREFERRED_PREVIEW_CAMERA_SCORE = 20_000_000_000L
        private const val REQUESTED_OIS_CAMERA_SCORE = 30_000_000_000L
        private const val REQUESTED_EIS_CAMERA_SCORE = 8_000_000_000L
        private const val FIXED_FPS_RANGE_SCORE = 12_000_000_000L

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
        private const val HDR_SCORE_BONUS =
            50_000_000L
        private const val FRAME_DURATION_TOLERANCE_NS =
            250_000L

        private const val RAW_FILE_PREFIX = "steadyvault_raw_"
        private const val STALE_RAW_FILE_MIN_AGE_MS = 60_000L
        private const val SERVICE_HEARTBEAT_INTERVAL_MS = 15_000L
        private const val FOREGROUND_PERMISSION_START_ATTEMPTS = 6
        private const val FOREGROUND_PERMISSION_RETRY_DELAY_MS = 100L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 5L * 60L * 60L * 1_000L

        private const val MAX_WAKE_LOCK_MS =
            6 * 60 * 60 * 1000L

    }
}
