package com.steadyvault.camera.photo.service

import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.diagnostics.PhotoPerformanceTracker
import com.steadyvault.camera.photo.quality.PhotoQualityPolicy
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.camera.CameraZoom
import com.steadyvault.camera.core.camera.WhiteBalanceCorrection
import com.steadyvault.camera.core.camera.CameraResourceCoordinator
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.state.PhotoCaptureStateStore
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.CameraProfileStore
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.widgets.WidgetRenderer
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.capture.CameraPreviewRegistry

import com.steadyvault.camera.core.settings.VisualIdentityStore

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class PhotoService : Service() {

    private data class PhotoProfile(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val photoSize: Size,
        val previewSize: Size,
        val sensorOrientation: Int,
        val lensFacing: Int,
        val afMode: Int,
        val maximumResolutionPixelMode: Boolean,
        val oisCapability: OpticalStabilizationCapability.Capability
    )

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageThread: HandlerThread
    private lateinit var imageHandler: Handler

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val focusTriggered = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)
    private val previewFrames = AtomicInteger(0)
    private val stableThreeAFrames = AtomicInteger(0)
    private val focusAttempts = AtomicInteger(0)
    private val cameraLeaseToken = Any()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutRunnable: Runnable? = null
    private var focusTimeoutRunnable: Runnable? = null
    private var cameraOpenRetryCount = 0

    private var isSequenceMode = false
    private var previewCaptureRequested = false
    private var fromWidget = false
    private var preferredCameraId: String? = null
    private var previewSurfaceFallbackAttempted = false
    private var photosSaved = 0
    private var activeProfile: PhotoProfile? = null
    private var activeJpegSurface: Surface? = null
    private var activeLivePreviewSurface: Surface? = null
    private lateinit var captureSettings: CaptureSettings.Snapshot
    @Volatile private var latestAwbGains: RggbChannelVector? = null
    @Volatile private var latestColorTransform: ColorSpaceTransform? = null

    override fun onCreate() {
        super.onCreate()

        cameraExecutor = Executors.newSingleThreadExecutor()

        imageThread = HandlerThread(
            "SteadyVault-PhotoHighQuality"
        ).apply {
            start()
        }

        imageHandler = Handler(imageThread.looper)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action

        if (
            action != ACTION_CAPTURE &&
            action != ACTION_BURST
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (finished.get()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started.compareAndSet(false, true)) {
            WidgetRenderer.updateRecordingControls(this)
            return START_NOT_STICKY
        }

        PhotoPerformanceTracker.begin()
        isSequenceMode = action == ACTION_BURST
        previewCaptureRequested = intent.getBooleanExtra(EXTRA_FROM_PREVIEW, false)
        fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)
        preferredCameraId = intent.getStringExtra(EXTRA_PREFERRED_CAMERA_ID)
            ?.takeIf { it.isNotBlank() }
        val currentSettings = CaptureSettings.snapshot(this)
        val requestedZoomRatio = intent.getFloatExtra(EXTRA_ZOOM_RATIO, Float.NaN)
            .takeIf { it.isFinite() && it > 0f }
        val quickCaptureZoom = if (
            !previewCaptureRequested &&
            preferredCameraId == null
        ) {
            BackgroundRecordingZoom.cameraSelection(this)
        } else {
            null
        }
        val profileCameraId =
            preferredCameraId ?:
            quickCaptureZoom?.cameraId ?:
            currentSettings.selectedCameraId
        val activatedSettings = if (profileCameraId != null) {
            CameraProfileStore.activate(
                this,
                profileCameraId,
                CameraProfileStore.FunctionMode.PHOTO,
                currentSettings.copy(selectedCameraId = profileCameraId)
            )
        } else {
            CameraProfileStore.setActiveMode(this, CameraProfileStore.FunctionMode.PHOTO)
            currentSettings
        }
        captureSettings = (quickCaptureZoom?.let { selection ->
            activatedSettings.copy(
                selectedCameraId = selection.cameraId,
                zoomRatio = selection.requestZoomRatio
            )
        } ?: activatedSettings).let { settings ->
            requestedZoomRatio?.let { settings.copy(zoomRatio = it) } ?: settings
        }
        latestAwbGains = null
        latestColorTransform = null
        previewSurfaceFallbackAttempted = false
        cameraOpenRetryCount = 0
        photosSaved = 0
        PhotoCaptureStateStore.begin(this)
        WidgetRenderer.updateRecordingControls(this)
        sendPhotoState(
            when {
                isSequenceMode -> "Preparando sequência de fotos…"
                previewCaptureRequested -> "Capturando foto…"
                else -> "Preparando foto em qualidade máxima…"
            }
        )

        if (fromWidget) {
            Haptics.photo(this)
        }

        val foregroundFailure = runCatching {
            startForegroundNowWithPermissionRetry(
                when {
                    isSequenceMode -> "Preparando sequência…"
                    previewCaptureRequested -> "Capturando foto…"
                    else -> "Preparando foto em alta qualidade…"
                }
            )
        }.exceptionOrNull()

        if (foregroundFailure != null) {
            val message = "Foto não capturada: falha ao iniciar serviço: ${errorText(foregroundFailure)}"
            sendPhotoState(message)
            PhotoCaptureStateStore.finish(this, success = false, message = message)
            stopSelf()
            return START_NOT_STICKY
        }

        if (
            checkSelfPermission(
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            finishWithError(
                "Permissão da câmera não concedida"
            )
            return START_NOT_STICKY
        }

        if (CaptureStateStore.isBusy(this)) {
            finishWithError(
                "Pare a gravação para preservar o modo de vídeo selecionado"
            )
            return START_NOT_STICKY
        }

        acquireWakeLock()
        scheduleCaptureTimeout()

        CameraResourceCoordinator.requestCapture(
            owner = CameraResourceCoordinator.Owner.PHOTO,
            token = cameraLeaseToken,
            onGranted = {
                if (finished.get()) {
                    CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.PHOTO, cameraLeaseToken)
                } else {
                    cameraExecutor.execute {
                        val profile = runCatching { selectBestProfile() }
                            .getOrElse {
                                finishWithError(errorText(it))
                                return@execute
                            }
                        openCameraWithRetry(profile)
                    }
                }
            },
            onDenied = { reason -> finishWithError("câmera indisponível: $reason") }
        )

        return START_NOT_STICKY
    }

    private fun selectBestProfile(): PhotoProfile {
        val manager = getSystemService(CameraManager::class.java)
        val candidates = mutableListOf<Pair<Long, PhotoProfile>>()
        val maxPhotoPixels = if (isSequenceMode) MAX_SEQUENCE_PHOTO_PIXELS else MAX_SINGLE_PHOTO_PIXELS

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_EXTERNAL
            if (preferredCameraId != null && cameraId != preferredCameraId) continue
            if (preferredCameraId == null && lensFacing != CameraCharacteristics.LENS_FACING_BACK) continue

            val regularMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val requestKeys = runCatching { characteristics.availableCaptureRequestKeys }.getOrDefault(emptyList())
            val maximumMap = if (!isSequenceMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                requestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE)) {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            } else {
                null
            }
            val streamModes = buildList {
                add(regularMap to false)
                maximumMap?.let { add(it to true) }
            }
            val oisCapability = OpticalStabilizationCapability.inspect(manager, cameraId, characteristics)

            for ((streamMap, maximumResolutionPixelMode) in streamModes) {
                val regularJpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                val highResolutionJpegSizes = if (!isSequenceMode && !maximumResolutionPixelMode) {
                    runCatching {
                        streamMap.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val jpegSizes = (regularJpegSizes + highResolutionJpegSizes)
                    .distinctBy { it.width to it.height }
                val previewSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                if (jpegSizes.isEmpty() || previewSizes.isEmpty()) continue

                val selectedDimensions = PhotoQualityPolicy.selectHighestResolution(
                    jpegSizes.map { PhotoQualityPolicy.Dimensions(it.width, it.height) },
                    maxPhotoPixels
                ) ?: continue
                val photoSize = jpegSizes.firstOrNull {
                    it.width == selectedDimensions.width && it.height == selectedDimensions.height
                } ?: continue

                val previewSize = previewSizes
                    .filter { area(it) <= MAX_PREVIEW_PIXELS && isNearFourByThree(it) }
                    .minByOrNull { abs(it.width - PREVIEW_WIDTH) + abs(it.height - PREVIEW_HEIGHT) }
                    ?: previewSizes
                        .filter { area(it) <= MAX_PREVIEW_PIXELS }
                        .minByOrNull { abs(area(it) - TARGET_PREVIEW_PIXELS) }
                    ?: previewSizes.minByOrNull(::area)
                    ?: continue

                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val afMode = when {
                    !isSequenceMode && afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    !isSequenceMode && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                        CameraMetadata.CONTROL_AF_MODE_AUTO
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                        CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }

                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val activeArray = if (maximumResolutionPixelMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                        ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                } else {
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                }
                val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                val resolutionScore = (area(photoSize) * 120L).coerceAtMost(30_000_000_000L)
                val maximumResolutionScore = if (maximumResolutionPixelMode) 12_000_000_000L else 0L
                val focusScore = when {
                    afMode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> 2_000_000_000L
                    afMode == CameraMetadata.CONTROL_AF_MODE_AUTO -> 1_300_000_000L
                    else -> 0L
                } + if (minimumFocusDistance > 0f) 350_000_000L else 0L
                val lensScore = preferredMainLensScore(characteristics)
                val stabilizationScore = if (oisCapability.supported) 900_000_000L else 0L
                val logicalScore = if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                    650_000_000L
                } else {
                    0L
                }
                val hardwareScore = when (hardwareLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 550_000_000L
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 400_000_000L
                    else -> 0L
                }
                val sensorScore = activeArray?.let {
                    (it.width().toLong() * it.height().toLong() * 8L).coerceAtMost(2_000_000_000L)
                } ?: 0L

                val preferredCameraScore = if (previewCaptureRequested && preferredCameraId == cameraId) {
                    PREFERRED_PREVIEW_CAMERA_SCORE
                } else {
                    0L
                }

                candidates += resolutionScore + maximumResolutionScore + focusScore + lensScore + stabilizationScore +
                    logicalScore + hardwareScore + sensorScore + preferredCameraScore to PhotoProfile(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    photoSize = photoSize,
                    previewSize = previewSize,
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    lensFacing = lensFacing,
                    afMode = afMode,
                    maximumResolutionPixelMode = maximumResolutionPixelMode,
                    oisCapability = oisCapability
                )
            }
        }

        return candidates.maxByOrNull { it.first }?.second
            ?: throw IllegalStateException("Nenhuma câmera compatível foi encontrada")
    }

    private fun preferredMainLensScore(characteristics: CameraCharacteristics): Long {
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0L
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return 0L
        if (sensorSize.width <= 0f || focalLengths.isEmpty()) return 0L

        val closestEquivalent = focalLengths.minOf { focalLength ->
            abs((focalLength * FULL_FRAME_SENSOR_WIDTH_MM / sensorSize.width) - PREFERRED_EQUIVALENT_FOCAL_LENGTH_MM)
        }
        return (MAX_MAIN_LENS_SCORE - (closestEquivalent * MAIN_LENS_SCORE_PER_MM).toLong()).coerceAtLeast(0L)
    }

    private fun openCamera(
        profile: PhotoProfile
    ) {
        if (finished.get()) return

        activeProfile = profile

        val jpeg = ImageReader.newInstance(
            profile.photoSize.width,
            profile.photoSize.height,
            ImageFormat.JPEG,
            2
        )

        val preview = ImageReader.newInstance(
            profile.previewSize.width,
            profile.previewSize.height,
            ImageFormat.YUV_420_888,
            3
        )

        jpegReader = jpeg
        previewReader = preview
        activeJpegSurface = jpeg.surface

        jpeg.setOnImageAvailableListener(
            { source ->
                val image = runCatching {
                    source.acquireNextImage()
                }.getOrNull()
                    ?: return@setOnImageAvailableListener

                PhotoPerformanceTracker.markImageAvailable()
                saveImage(image, profile)
            },
            imageHandler
        )

        preview.setOnImageAvailableListener(
            { source ->
                runCatching {
                    source.acquireLatestImage()
                        ?.close()
                }
            },
            imageHandler
        )

        if (
            checkSelfPermission(
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Permissão da câmera foi revogada"
            )
        }

        getSystemService(
            CameraManager::class.java
        ).openCamera(
            profile.cameraId,
            cameraExecutor,
            object :
                CameraDevice.StateCallback() {

                override fun onOpened(
                    camera: CameraDevice
                ) {
                    if (finished.get()) {
                        camera.close()
                        return
                    }

                    cameraDevice = camera

                    runCatching {
                        val livePreviewSurface = if (previewCaptureRequested && !previewSurfaceFallbackAttempted && !profile.maximumResolutionPixelMode) {
                            CameraPreviewRegistry.snapshot()?.surface
                        } else {
                            null
                        }
                        createSession(
                            camera,
                            profile,
                            preview.surface,
                            jpeg.surface,
                            livePreviewSurface
                        )
                    }.onFailure {
                        finishWithError(
                            errorText(it)
                        )
                    }
                }

                override fun onDisconnected(
                    camera: CameraDevice
                ) {
                    camera.close()
                    cameraDevice = null
                    if (!scheduleCameraOpenRetry(profile, "a câmera foi desconectada durante a abertura")) {
                        finishWithError("A câmera foi desconectada")
                    }
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int
                ) {
                    camera.close()
                    cameraDevice = null
                    val message = "Falha ao abrir a câmera: $error"
                    if (!isTransientCameraOpenError(error) || !scheduleCameraOpenRetry(profile, message)) {
                        finishWithError(message)
                    }
                }
            }
        )
    }

    private fun openCameraWithRetry(profile: PhotoProfile) {
        runCatching { openCamera(profile) }
            .onFailure { throwable ->
                if (!scheduleCameraOpenRetry(profile, errorText(throwable))) {
                    finishWithError(errorText(throwable))
                }
            }
    }

    private fun isTransientCameraOpenError(error: Int): Boolean =
        error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
            error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
            error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ||
            error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE

    private fun scheduleCameraOpenRetry(profile: PhotoProfile, reason: String): Boolean {
        if (finished.get() || cameraOpenRetryCount >= CAMERA_OPEN_RETRY_LIMIT) return false
        cameraOpenRetryCount++
        releaseCameraObjectsForRetry()
        val delayMs = CAMERA_OPEN_RETRY_DELAY_MS * cameraOpenRetryCount.toLong()
        val message = "Aguardando a câmera ficar disponível… tentativa ${cameraOpenRetryCount + 1}/${CAMERA_OPEN_RETRY_LIMIT + 1}"
        sendPhotoState(message)
        updateNotification(message)
        mainHandler.postDelayed(
            {
                if (!finished.get()) {
                    cameraExecutor.execute { openCameraWithRetry(profile) }
                }
            },
            delayMs
        )
        return true
    }

    private fun releaseCameraObjectsForRetry() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { previewReader?.close() }
        previewReader = null
        activeJpegSurface = null
        activeLivePreviewSurface = null
    }

    private fun createSession(
        camera: CameraDevice,
        profile: PhotoProfile,
        previewSurface: Surface,
        jpegSurface: Surface,
        livePreviewSurface: Surface?
    ) {
        val outputs = buildList {
            add(OutputConfiguration(previewSurface))
            add(OutputConfiguration(jpegSurface))
            livePreviewSurface?.takeIf { it.isValid }?.let { add(OutputConfiguration(it)) }
        }
        val configuration =
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor,
                object :
                    CameraCaptureSession.StateCallback() {

                    override fun onConfigured(
                        session:
                            CameraCaptureSession
                    ) {
                        if (finished.get()) {
                            session.close()
                            return
                        }

                        captureSession = session

                        runCatching {
                            startPreviewAndFocus(
                                camera,
                                session,
                                profile,
                                previewSurface,
                                jpegSurface,
                                livePreviewSurface
                            )
                        }.onFailure {
                            finishWithError(
                                errorText(it)
                            )
                        }
                    }

                    override fun onConfigureFailed(
                        session:
                            CameraCaptureSession
                    ) {
                        session.close()
                        if (livePreviewSurface != null && !previewSurfaceFallbackAttempted) {
                            previewSurfaceFallbackAttempted = true
                            sendPhotoState("Preview foi pausado porque a câmera recusou a superfície extra; mantendo a foto em qualidade máxima…")
                            runCatching {
                                createSession(camera, profile, previewSurface, jpegSurface, null)
                            }.onFailure { finishWithError(errorText(it)) }
                        } else {
                            finishWithError("A câmera recusou a sessão de foto")
                        }
                    }
                }
            )

        camera.createCaptureSession(
            configuration
        )
    }

    private fun startPreviewAndFocus(
        camera: CameraDevice,
        session: CameraCaptureSession,
        profile: PhotoProfile,
        previewSurface: Surface,
        jpegSurface: Surface,
        livePreviewSurface: Surface?
    ) {
        activeLivePreviewSurface = livePreviewSurface?.takeIf { it.isValid }
        previewFrames.set(0)
        stableThreeAFrames.set(0)
        focusAttempts.set(0)
        focusTriggered.set(false)
        captureInFlight.set(false)

        val previewRequest =
            camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)
                livePreviewSurface?.takeIf { it.isValid }?.let(::addTarget)
                configureAutoControls(
                    this,
                    profile
                )
            }.build()

        updateNotification(
            "Ajustando foco, exposição e balanço de branco…"
        )

        session.setSingleRepeatingRequest(
            previewRequest,
            cameraExecutor,
            object :
                CameraCaptureSession
                    .CaptureCallback() {

                override fun onCaptureCompleted(
                    callbackSession:
                        CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (
                        finished.get() ||
                        captureInFlight.get()
                    ) {
                        return
                    }

                    result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { latestAwbGains = it }
                    result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { latestColorTransform = it }

                    val frames =
                        previewFrames.incrementAndGet()

                    if (
                        frames >= framesBeforeFocus() &&
                        photoHasActiveAutoFocus(profile) &&
                        focusTriggered.compareAndSet(false, true)
                    ) {
                        triggerThreeA(
                            camera,
                            session,
                            profile,
                            previewSurface,
                            activeLivePreviewSurface
                        )
                    }

                    val afState = result.get(
                        CaptureResult
                            .CONTROL_AF_STATE
                    )

                    val aeState = result.get(
                        CaptureResult
                            .CONTROL_AE_STATE
                    )

                    val awbState = result.get(
                        CaptureResult
                            .CONTROL_AWB_STATE
                    )

                    val lensState = result.get(CaptureResult.LENS_STATE)
                    val focusReady = when {
                        !photoHasActiveAutoFocus(profile) -> true
                        !isSequenceMode && effectivePhotoAfMode(profile) == CameraMetadata.CONTROL_AF_MODE_AUTO ->
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        else -> afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    }
                    val exposureReady = aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                    val whiteBalanceReady = awbState == null ||
                        awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                        awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
                    val lensReady = lensState == null || lensState == CaptureResult.LENS_STATE_STATIONARY
                    val stableFrames = if (focusReady && exposureReady && whiteBalanceReady && lensReady) {
                        stableThreeAFrames.incrementAndGet()
                    } else {
                        stableThreeAFrames.set(0)
                        0
                    }

                    if (
                        frames >= minimumPreviewFrames() &&
                        stableFrames >= requiredStableFrames()
                    ) {
                        captureStill(camera, session, profile, jpegSurface)
                    }
                }

                override fun onCaptureFailed(
                    callbackSession:
                        CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (
                        previewFrames
                            .incrementAndGet() >=
                        fallbackPreviewFrames()
                    ) {
                        captureStill(
                            camera,
                            session,
                            profile,
                            jpegSurface
                        )
                    }
                }
            }
        )

        scheduleFocusTimeout(
            camera,
            session,
            profile,
            jpegSurface
        )
    }

    private fun fastSingleCapture(): Boolean = fromWidget && !isSequenceMode

    private fun framesBeforeFocus(): Int =
        if (fastSingleCapture()) FAST_FRAMES_BEFORE_FOCUS else FRAMES_BEFORE_FOCUS

    private fun minimumPreviewFrames(): Int =
        if (fastSingleCapture()) FAST_MIN_PREVIEW_FRAMES else MIN_PREVIEW_FRAMES

    private fun fallbackPreviewFrames(): Int =
        if (fastSingleCapture()) FAST_FALLBACK_PREVIEW_FRAMES else FALLBACK_PREVIEW_FRAMES

    private fun requiredStableFrames(): Int = when {
        fastSingleCapture() -> FAST_REQUIRED_STABLE_THREE_A_FRAMES
        isSequenceMode -> REQUIRED_SEQUENCE_STABLE_THREE_A_FRAMES
        else -> REQUIRED_SINGLE_STABLE_THREE_A_FRAMES
    }

    private fun focusTimeoutMs(): Long =
        if (fastSingleCapture()) FAST_FOCUS_TIMEOUT_MS else FOCUS_TIMEOUT_MS

    private fun triggerThreeA(
        camera: CameraDevice,
        session: CameraCaptureSession,
        profile: PhotoProfile,
        previewSurface: Surface,
        livePreviewSurface: Surface? = activeLivePreviewSurface
    ) {
        val triggerRequest =
            camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)
                livePreviewSurface?.takeIf { it.isValid }?.let(::addTarget)
                configureAutoControls(
                    this,
                    profile
                )

                if (
                    effectivePhotoAfMode(profile) !=
                    CameraMetadata
                        .CONTROL_AF_MODE_OFF
                ) {
                    focusAttempts.incrementAndGet()
                    setSafely(
                        this,
                        CaptureRequest
                            .CONTROL_AF_TRIGGER,
                        CameraMetadata
                            .CONTROL_AF_TRIGGER_START
                    )
                }

                setSafely(
                    this,
                    CaptureRequest
                        .CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata
                        .CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
            }.build()

        runCatching {
            session.captureSingleRequest(
                triggerRequest,
                cameraExecutor,
                object :
                    CameraCaptureSession
                        .CaptureCallback() {}
            )
        }
    }

    private fun captureStill(
        camera: CameraDevice,
        session: CameraCaptureSession,
        profile: PhotoProfile,
        jpegSurface: Surface
    ) {
        if (finished.get()) return

        if (
            !captureInFlight.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        clearFocusTimeout()

        PhotoPerformanceTracker.markCaptureRequest()
        val request =
            camera.createCaptureRequest(
                CameraDevice
                    .TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(jpegSurface)

                configureAutoControls(
                    this,
                    profile
                )

                configureHighQualityProcessing(
                    this,
                    profile
                )

                setSafely(
                    this,
                    CaptureRequest
                        .CONTROL_CAPTURE_INTENT,
                    CameraMetadata
                        .CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                )

                setSafely(
                    this,
                    CaptureRequest.CONTROL_ENABLE_ZSL,
                    false
                )

                setSafely(
                    this,
                    CaptureRequest
                        .CONTROL_AF_TRIGGER,
                    CameraMetadata
                        .CONTROL_AF_TRIGGER_IDLE
                )

                setSafely(
                    this,
                    CaptureRequest
                        .CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata
                        .CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )

                if (
                    profile.characteristics.get(
                        CameraCharacteristics
                            .CONTROL_AE_LOCK_AVAILABLE
                    ) == true
                ) {
                    setSafely(
                        this,
                        CaptureRequest
                            .CONTROL_AE_LOCK,
                        true
                    )
                }

                if (
                    captureSettings.lockWhiteBalance &&
                    captureSettings.whiteBalanceMode == CaptureSettings.WHITE_BALANCE_AUTO &&
                    profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
                ) {
                    setSafely(this, CaptureRequest.CONTROL_AWB_LOCK, true)
                }

                applyFinalWhiteBalance(this, profile)
                applyPhotoColorProfile(this, profile)

                setSafely(
                    this,
                    CaptureRequest
                        .JPEG_QUALITY,
                    JPEG_QUALITY.toByte()
                )

                setSafely(
                    this,
                    CaptureRequest.JPEG_THUMBNAIL_QUALITY,
                    JPEG_QUALITY.toByte()
                )

                setSafely(
                    this,
                    CaptureRequest
                        .JPEG_ORIENTATION,
                    calculateJpegOrientation(
                        profile.sensorOrientation,
                        profile.lensFacing
                    )
                )
            }.build()

        if (isSequenceMode) {
            updateNotification(
                "Capturando foto ${photosSaved + 1} de $SEQUENCE_COUNT…"
            )
        } else {
            updateNotification(
                "Capturando ${profile.photoSize.width}×${profile.photoSize.height} • " +
                    "${photoMegapixels(profile.photoSize)} MP com foco preciso…"
            )
        }

        session.captureSingleRequest(
            request,
            cameraExecutor,
            object :
                CameraCaptureSession
                    .CaptureCallback() {

                override fun onCaptureFailed(
                    callbackSession:
                        CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    captureInFlight.set(false)

                    finishWithError(
                        "A câmera não conseguiu gerar o JPEG"
                    )
                }
            }
        )
    }

    private fun effectivePhotoAfMode(profile: PhotoProfile): Int {
        val available = profile.characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        ) ?: intArrayOf()
        val requested = when (captureSettings.focusMode) {
            CaptureSettings.FOCUS_AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO
            CaptureSettings.FOCUS_OFF -> CameraMetadata.CONTROL_AF_MODE_OFF
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        return listOf(
            requested,
            profile.afMode,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_OFF
        ).firstOrNull(available::contains) ?: CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun photoHasActiveAutoFocus(profile: PhotoProfile): Boolean {
        val minimumFocusDistance = profile.characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f
        return minimumFocusDistance > 0f &&
            effectivePhotoAfMode(profile) != CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun configureAutoControls(
        builder: CaptureRequest.Builder,
        profile: PhotoProfile
    ) {
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setSafely(
                builder,
                CaptureRequest.SENSOR_PIXEL_MODE,
                if (profile.maximumResolutionPixelMode) {
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                } else {
                    CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT
                }
            )
        }

        setSafely(builder, CaptureRequest.CONTROL_AF_MODE, effectivePhotoAfMode(profile))

        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val requestedAwb = when (captureSettings.whiteBalanceMode) {
            CaptureSettings.WHITE_BALANCE_INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            CaptureSettings.WHITE_BALANCE_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
            CaptureSettings.WHITE_BALANCE_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            CaptureSettings.WHITE_BALANCE_CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            CaptureSettings.WHITE_BALANCE_TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
            CaptureSettings.WHITE_BALANCE_SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
            else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        listOf(requestedAwb, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
            .firstOrNull { awbModes.contains(it) }?.let { setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, it) }

        val exposureRange = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureRange?.let {
            setSafely(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, captureSettings.exposureCompensation.coerceIn(it.lower, it.upper))
        }

        CameraZoom.apply(builder, profile.characteristics, captureSettings.zoomRatio)

        val antibandingModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
        val requestedAntibanding = when (captureSettings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(requestedAntibanding)) {
            setSafely(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAntibanding)
        }

        val videoModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val useEis = captureSettings.stabilization == CaptureSettings.STABILIZATION_EIS ||
            captureSettings.stabilization == CaptureSettings.STABILIZATION_PREVIEW
        when {
            captureSettings.stabilization == CaptureSettings.STABILIZATION_OFF -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
            useEis && videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
            }
            profile.oisCapability.supported -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = true)
            }
            else -> {
                setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
        }
    }

    private fun configureHighQualityProcessing(
        builder: CaptureRequest.Builder,
        profile: PhotoProfile
    ) {
        configureSelectedEdgeProcessing(builder, profile)
        configureSelectedNoiseReduction(builder, profile)

        setHighestAvailableMode(
            builder,
            CaptureRequest
                .COLOR_CORRECTION_ABERRATION_MODE,
            profile.characteristics.get(
                CameraCharacteristics
                    .COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
            ),
            CameraMetadata
                .COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            CameraMetadata
                .COLOR_CORRECTION_ABERRATION_MODE_FAST
        )

        setHighestAvailableMode(
            builder,
            CaptureRequest.HOT_PIXEL_MODE,
            profile.characteristics.get(
                CameraCharacteristics
                    .HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES
            ),
            CameraMetadata
                .HOT_PIXEL_MODE_HIGH_QUALITY,
            CameraMetadata
                .HOT_PIXEL_MODE_FAST
        )

        setHighestAvailableMode(
            builder,
            CaptureRequest.SHADING_MODE,
            profile.characteristics.get(
                CameraCharacteristics
                    .SHADING_AVAILABLE_MODES
            ),
            CameraMetadata
                .SHADING_MODE_HIGH_QUALITY,
            CameraMetadata.SHADING_MODE_FAST
        )

        setHighestAvailableMode(
            builder,
            CaptureRequest.TONEMAP_MODE,
            profile.characteristics.get(
                CameraCharacteristics
                    .TONEMAP_AVAILABLE_TONE_MAP_MODES
            ),
            CameraMetadata
                .TONEMAP_MODE_HIGH_QUALITY,
            CameraMetadata.TONEMAP_MODE_FAST
        )

        setHighestAvailableMode(
            builder,
            CaptureRequest
                .DISTORTION_CORRECTION_MODE,
            profile.characteristics.get(
                CameraCharacteristics
                    .DISTORTION_CORRECTION_AVAILABLE_MODES
            ),
            CameraMetadata
                .DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            CameraMetadata
                .DISTORTION_CORRECTION_MODE_FAST
        )
    }

    private fun applyFinalWhiteBalance(builder: CaptureRequest.Builder, profile: PhotoProfile) {
        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val gains = latestAwbGains
        val transform = latestColorTransform
        val strength = WhiteBalanceCorrection.strength(captureSettings.yellowReduction, captureSettings.whiteBalanceMode, gains)
        val capabilities = profile.characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualPost = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        if (strength != 0f && manualPost && gains != null && transform != null && awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            setSafely(builder, CaptureRequest.COLOR_CORRECTION_GAINS, WhiteBalanceCorrection.adjustedGains(gains, strength))
        } else if (WhiteBalanceCorrection.useIncandescentFallback(strength) && awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
        } else if (WhiteBalanceCorrection.useWarmFallback(strength)) {
            when {
                awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_SHADE) ->
                    setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_SHADE)
                awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) ->
                    setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
            }
        }
    }

    private fun applyPhotoColorProfile(builder: CaptureRequest.Builder, profile: PhotoProfile) {
        if (captureSettings.hdrHlg10 || captureSettings.colorProfile == CaptureSettings.COLOR_NATURAL) return
        val modes = profile.characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
        if (!modes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) return
        val points = if (captureSettings.colorProfile == CaptureSettings.COLOR_FLAT) {
            floatArrayOf(0f, 0f, 0.10f, 0.17f, 0.28f, 0.35f, 0.50f, 0.50f, 0.72f, 0.65f, 0.90f, 0.83f, 1f, 1f)
        } else {
            floatArrayOf(0f, 0f, 0.14f, 0.19f, 0.34f, 0.39f, 0.50f, 0.50f, 0.66f, 0.61f, 0.86f, 0.81f, 1f, 1f)
        }
        setSafely(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        setSafely(builder, CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
    }

    private fun configureSelectedNoiseReduction(
        builder: CaptureRequest.Builder,
        profile: PhotoProfile
    ) {
        val available = profile.characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        ) ?: intArrayOf()
        val requested = if (isSequenceMode) {
            CameraMetadata.NOISE_REDUCTION_MODE_FAST
        } else {
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        }
        listOf(
            requested,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF
        ).firstOrNull(available::contains)?.let {
            setSafely(builder, CaptureRequest.NOISE_REDUCTION_MODE, it)
        }
    }

    private fun configureSelectedEdgeProcessing(
        builder: CaptureRequest.Builder,
        profile: PhotoProfile
    ) {
        val available = profile.characteristics.get(
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES
        ) ?: intArrayOf()
        val requested = if (isSequenceMode) {
            CameraMetadata.EDGE_MODE_FAST
        } else {
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        }
        listOf(requested, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_OFF)
            .firstOrNull(available::contains)?.let {
                setSafely(builder, CaptureRequest.EDGE_MODE, it)
            }
    }

    private fun setHighestAvailableMode(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<Int>,
        availableModes: IntArray?,
        preferred: Int,
        fallback: Int
    ) {
        when {
            availableModes
                ?.contains(preferred) == true ->
                setSafely(
                    builder,
                    key,
                    preferred
                )

            availableModes
                ?.contains(fallback) == true ->
                setSafely(
                    builder,
                    key,
                    fallback
                )
        }
    }

    private fun saveImage(
        image: Image,
        profile: PhotoProfile
    ) {
        var outputFile: java.io.File? = null

        try {
            val index = photosSaved + 1
            val target = VaultRepository.createPhotoFile(
                context = this,
                sequenceIndex = index.takeIf { isSequenceMode }
            )
            outputFile = target

            val buffer = image.planes.first().buffer
            target.outputStream().buffered(WRITE_BUFFER_SIZE).use { output ->
                val chunk = ByteArray(WRITE_BUFFER_SIZE)
                while (buffer.hasRemaining()) {
                    val size = minOf(buffer.remaining(), chunk.size)
                    buffer.get(chunk, 0, size)
                    output.write(chunk, 0, size)
                }
                output.flush()
            }

            require(target.isFile && target.length() > 0L) {
                "A foto criada no cofre está vazia"
            }

            val jpegBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, jpegBounds)
            require(jpegBounds.outWidth > 0 && jpegBounds.outHeight > 0) {
                "O JPEG criado pela câmera não pôde ser decodificado"
            }

            PhotoPerformanceTracker.finish(this, profile.photoSize.width, profile.photoSize.height)
            photosSaved++
            captureInFlight.set(false)

            if (isSequenceMode && photosSaved < SEQUENCE_COUNT) {
                scheduleNextSequencePhoto()
            } else {
                finishWithSuccess(
                    if (isSequenceMode) {
                        "Sequência de $SEQUENCE_COUNT fotos salva no cofre"
                    } else {
                        "Foto salva • ${profile.photoSize.width}×${profile.photoSize.height} • " +
                            "${photoMegapixels(profile.photoSize)} MP"
                    }
                )
            }
        } catch (t: Throwable) {
            captureInFlight.set(false)
            runCatching { outputFile?.delete() }
            finishWithError(errorText(t))
        } finally {
            runCatching { image.close() }
        }
    }

    private fun scheduleNextSequencePhoto() {
        mainHandler.postDelayed(
            {
                cameraExecutor.execute {
                    val camera = cameraDevice
                    val session = captureSession
                    val profile = activeProfile
                    val jpegSurface =
                        activeJpegSurface

                    if (
                        camera == null ||
                        session == null ||
                        profile == null ||
                        jpegSurface == null ||
                        finished.get()
                    ) {
                        finishWithError(
                            "A sessão da sequência foi encerrada"
                        )
                        return@execute
                    }

                    runCatching {
                        captureStill(
                            camera,
                            session,
                            profile,
                            jpegSurface
                        )
                    }.onFailure {
                        finishWithError(
                            errorText(it)
                        )
                    }
                }
            },
            SEQUENCE_INTERVAL_MS
        )
    }

    private fun scheduleCaptureTimeout() {
        clearCaptureTimeout()

        val runnable = Runnable {
            finishWithError(
                "Tempo limite da câmera excedido"
            )
        }

        timeoutRunnable = runnable

        mainHandler.postDelayed(
            runnable,
            if (isSequenceMode) {
                SEQUENCE_TIMEOUT_MS
            } else {
                CAPTURE_TIMEOUT_MS
            }
        )
    }

    private fun scheduleFocusTimeout(
        camera: CameraDevice,
        session: CameraCaptureSession,
        profile: PhotoProfile,
        jpegSurface: Surface
    ) {
        clearFocusTimeout()

        val runnable = Runnable {
            cameraExecutor.execute {
                if (finished.get() || captureInFlight.get()) return@execute

                runCatching {
                    if (
                        !fastSingleCapture() &&
                        photoHasActiveAutoFocus(profile) &&
                        focusAttempts.get() < MAX_FOCUS_ATTEMPTS
                    ) {
                        stableThreeAFrames.set(0)
                        updateNotification("Refinando o foco antes da foto…")
                        val previewSurface = previewReader?.surface
                            ?: throw IllegalStateException("Preview de foco indisponível")
                        triggerThreeA(camera, session, profile, previewSurface)
                        scheduleFocusTimeout(camera, session, profile, jpegSurface)
                    } else {
                        captureStill(camera, session, profile, jpegSurface)
                    }
                }.onFailure {
                    finishWithError(errorText(it))
                }
            }
        }

        focusTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, focusTimeoutMs())
    }

    private fun clearCaptureTimeout() {
        timeoutRunnable?.let(
            mainHandler::removeCallbacks
        )
        timeoutRunnable = null
    }

    private fun clearFocusTimeout() {
        focusTimeoutRunnable?.let(
            mainHandler::removeCallbacks
        )
        focusTimeoutRunnable = null
    }

    private fun finishWithSuccess(
        message: String
    ) {
        if (
            !finished.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        clearCaptureTimeout()
        clearFocusTimeout()
        Haptics.success(this)
        sendPhotoState(message)
        PhotoCaptureStateStore.finish(this, success = true, message = message)
        WidgetRenderer.updateRecordingControls(this)
        if (!fromWidget) showToast(message)
        updateNotification(message)
        cleanup()
    }

    private fun finishWithError(
        message: String
    ) {
        if (
            !finished.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        clearCaptureTimeout()
        clearFocusTimeout()
        Haptics.error(this)

        val fullMessage =
            "Foto não capturada: $message"

        sendPhotoState(fullMessage)
        PhotoCaptureStateStore.finish(this, success = false, message = fullMessage)
        WidgetRenderer.updateRecordingControls(this)
        showToast(fullMessage)
        updateNotification(fullMessage)
        cleanup()
    }

    private fun cleanup() {
        runCatching {
            captureSession?.stopRepeating()
        }

        runCatching {
            captureSession?.close()
        }
        captureSession = null

        runCatching {
            cameraDevice?.close()
        }
        cameraDevice = null

        runCatching {
            jpegReader?.close()
        }
        jpegReader = null

        runCatching {
            previewReader?.close()
        }
        previewReader = null

        activeProfile = null
        activeJpegSurface = null
        activeLivePreviewSurface = null
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.PHOTO, cameraLeaseToken)
        releaseWakeLock()

        mainHandler.postDelayed(
            {
                runCatching {
                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )
                }

                stopSelf()
            },
            NOTIFICATION_FINISH_DELAY_MS
        )
    }

    private fun calculateJpegOrientation(
        sensorOrientation: Int,
        lensFacing: Int
    ): Int {
        val displayManager = getSystemService(DisplayManager::class.java)
        val rotation = displayManager
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: Surface.ROTATION_0

        val deviceDegrees = when (
            rotation
        ) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun photoMegapixels(size: Size): String =
        String.format(java.util.Locale.US, "%.1f", area(size) / 1_000_000.0)

    private fun isNearFourByThree(
        size: Size
    ): Boolean {
        val ratio =
            size.width.toDouble() /
                size.height.toDouble()

        return abs(
            ratio -
                FOUR_BY_THREE
        ) <= ASPECT_TOLERANCE
    }

    private fun area(
        size: Size
    ): Long =
        size.width.toLong() *
            size.height.toLong()

    private fun <T> setSafely(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T
    ) {
        runCatching {
            builder.set(
                key,
                value
            )
        }
    }

    private fun startForegroundNow(
        text: String
    ) {
        val notification = buildNotification(text)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }

    private fun isForegroundPermissionRace(throwable: Throwable): Boolean =
        throwable is SecurityException &&
            (throwable.message?.contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA, ignoreCase = true) == true ||
                throwable.message?.contains(Manifest.permission.CAMERA, ignoreCase = true) == true)

    private fun startForegroundNowWithPermissionRetry(text: String) {
        var lastFailure: Throwable? = null
        repeat(FOREGROUND_PERMISSION_START_ATTEMPTS) { attempt ->
            try {
                startForegroundNow(text)
                return
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (!isForegroundPermissionRace(throwable)) throw throwable
                if (attempt < FOREGROUND_PERMISSION_START_ATTEMPTS - 1) {
                    SystemClock.sleep(FOREGROUND_PERMISSION_RETRY_DELAY_MS * (attempt + 1L))
                }
            }
        }
        throw lastFailure ?: IllegalStateException("falha desconhecida ao iniciar serviço de foto em primeiro plano")
    }

    private fun updateNotification(
        text: String
    ) {
        runCatching {
            getSystemService(
                NotificationManager::class.java
            ).notify(
                NOTIFICATION_ID,
                buildNotification(text)
            )
        }
    }

    private fun buildNotification(
        text: String
    ): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                200,
                Intent(
                    this,
                    CaptureActivity::class.java
                ),
                PendingIntent
                    .FLAG_UPDATE_CURRENT or
                    PendingIntent
                        .FLAG_IMMUTABLE
            )

        val identity = VisualIdentityStore.notificationIdentity(this)
        val originalText = when {
            text.contains("salv", ignoreCase = true) -> "Mídia salva no cofre"
            text.contains("falha", ignoreCase = true) || text.contains("não capturada", ignoreCase = true) -> "Não foi possível concluir"
            else -> "Atividade em andamento"
        }
        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Foto"))
            .setContentText(VisualIdentityStore.notificationText(this, originalText))
            /* original status is intentionally reduced when a discreet identity is selected */
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished.get())
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setColorized(false)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel(LEGACY_CHANNEL_ID)
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Atividade discreta",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Atividades silenciosas do SteadyVault"
                    setSound(null, null)
                    enableVibration(false)
                    vibrationPattern = null
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun acquireWakeLock() {
        wakeLock =
            getSystemService(
                PowerManager::class.java
            ).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SteadyVault:PhotoCaptureHQ"
            ).apply {
                setReferenceCounted(false)

                acquire(
                    if (isSequenceMode) {
                        SEQUENCE_WAKE_LOCK_MS
                    } else {
                        PHOTO_WAKE_LOCK_MS
                    }
                )
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

    private fun sendPhotoState(
        message: String
    ) {
        sendBroadcast(
            Intent(ACTION_PHOTO_STATE)
                .setPackage(packageName)
                .putExtra(
                    EXTRA_MESSAGE,
                    message
                )
        )
    }

    private fun showToast(
        message: String
    ) {
        mainHandler.post {
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun errorText(
        throwable: Throwable
    ): String =
        throwable.message
            ?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass
                .simpleName

    override fun onDestroy() {
        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.PHOTO, cameraLeaseToken)
        PhotoCaptureStateStore.finish(this)
        WidgetRenderer.updateRecordingControls(this)
        clearCaptureTimeout()
        clearFocusTimeout()

        runCatching {
            captureSession?.close()
        }

        runCatching {
            cameraDevice?.close()
        }

        runCatching {
            jpegReader?.close()
        }

        runCatching {
            previewReader?.close()
        }
        activeLivePreviewSurface = null

        releaseWakeLock()
        if (
            ::cameraExecutor.isInitialized &&
            !cameraExecutor.isShutdown
        ) {
            cameraExecutor.shutdown()
        }

        if (
            ::imageThread.isInitialized
        ) {
            imageThread.quitSafely()
        }

        super.onDestroy()
    }

    companion object {
        const val ACTION_CAPTURE =
            "com.steadyvault.camera.CAPTURE_PHOTO"

        const val ACTION_BURST =
            "com.steadyvault.camera.CAPTURE_BURST"

        const val ACTION_PHOTO_STATE =
            "com.steadyvault.camera.PHOTO_STATE"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_FROM_WIDGET =
            "from_widget"
        const val EXTRA_FROM_PREVIEW =
            "from_preview"
        const val EXTRA_PREFERRED_CAMERA_ID =
            "preferred_camera_id"
        const val EXTRA_ZOOM_RATIO =
            "zoom_ratio"

        private const val CHANNEL_ID =
            "steadyvault_background_activity_v2"

        private const val LEGACY_CHANNEL_ID =
            "steadyvault_photo"

        private const val NOTIFICATION_ID = 8

        private const val MAX_SINGLE_PHOTO_PIXELS = 220_000_000L
        private const val MAX_SEQUENCE_PHOTO_PIXELS = 16_000_000L
        private const val PREFERRED_PREVIEW_CAMERA_SCORE = 50_000_000_000L
        private const val FULL_FRAME_SENSOR_WIDTH_MM = 36f
        private const val PREFERRED_EQUIVALENT_FOCAL_LENGTH_MM = 24f
        private const val MAX_MAIN_LENS_SCORE = 10_000_000_000L
        private const val MAIN_LENS_SCORE_PER_MM = 400_000_000f

        private const val MAX_PREVIEW_PIXELS =
            2_100_000L

        private const val TARGET_PREVIEW_PIXELS =
            1_228_800L

        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 960

        private const val FOUR_BY_THREE =
            4.0 / 3.0

        private const val ASPECT_TOLERANCE =
            0.06

        private const val FRAMES_BEFORE_FOCUS = 6
        private const val MIN_PREVIEW_FRAMES = 18
        private const val FALLBACK_PREVIEW_FRAMES = 26
        private const val FAST_MIN_PREVIEW_FRAMES = 3
        private const val FAST_FRAMES_BEFORE_FOCUS = 1
        private const val FAST_FALLBACK_PREVIEW_FRAMES = 5
        private const val FAST_REQUIRED_STABLE_THREE_A_FRAMES = 1
        private const val REQUIRED_SINGLE_STABLE_THREE_A_FRAMES = 8
        private const val REQUIRED_SEQUENCE_STABLE_THREE_A_FRAMES = 2
        private const val MAX_FOCUS_ATTEMPTS = 2

        private const val FOCUS_TIMEOUT_MS = 4_800L
        private const val FAST_FOCUS_TIMEOUT_MS = 900L
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val SEQUENCE_TIMEOUT_MS = 42_000L

        private const val SEQUENCE_INTERVAL_MS =
            420L

        private const val SEQUENCE_COUNT = 5

        private const val JPEG_QUALITY = 100

        private const val WRITE_BUFFER_SIZE =
            256 * 1024

        private const val PHOTO_WAKE_LOCK_MS =
            30_000L

        private const val SEQUENCE_WAKE_LOCK_MS =
            50_000L

        private const val NOTIFICATION_FINISH_DELAY_MS =
            800L
        private const val FOREGROUND_PERMISSION_START_ATTEMPTS = 6
        private const val FOREGROUND_PERMISSION_RETRY_DELAY_MS = 100L
        private const val CAMERA_OPEN_RETRY_LIMIT = 3
        private const val CAMERA_OPEN_RETRY_DELAY_MS = 350L
    }
}
