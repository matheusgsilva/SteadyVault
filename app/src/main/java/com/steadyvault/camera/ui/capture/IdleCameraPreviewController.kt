package com.steadyvault.camera.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import com.steadyvault.camera.capture.timing.StrictCaptureModePolicy
import com.steadyvault.camera.core.camera.Camera3AStateStore
import com.steadyvault.camera.core.camera.CameraLensCatalog
import com.steadyvault.camera.core.camera.CameraZoom
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.camera.WhiteBalanceCorrection
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.vault.VaultRepository
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sessão de configuração visual. Ela usa somente a superfície do preview enquanto o app está
 * ocioso. Ao iniciar foto ou vídeo, o coordenador fecha esta câmera e o serviço de captura pode
 * reutilizar a mesma Surface na sua própria sessão quando a ação partiu do preview.
 */
class IdleCameraPreviewController(
    private val context: Context,
    private val onFailure: (String) -> Unit = {},
    private val onPreviewFrame: (photoMode: Boolean) -> Unit = {}
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread(
        "SteadyVault-LivePreview",
        android.os.Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }
    private val generation = AtomicInteger(0)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var activeSurface: Surface? = null
    private var activeSettings: CaptureSettings.Snapshot? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeOisCapability: OpticalStabilizationCapability.Capability? = null
    @Volatile
    private var activeCameraId: String? = null
    private var activeToken: Int = 0
    private var lockedCameraId: String? = null
    private var activePhotoMode = false
    private var photoReader: ImageReader? = null
    private var photoPrewarmRejected = false
    private var manualFocusToken = 0
    private var focusTimeoutRunnable: Runnable? = null
    private var focusRestoreRunnable: Runnable? = null
    private var lastFocusPoint = 0.5f to 0.5f
    private var photoFocusPrepared = false
    private var photoFocusPreparing = false
    private var photoFocusAttempts = 0
    private val photoThread = HandlerThread(
        "SteadyVault-LivePhotoWriter",
        android.os.Process.THREAD_PRIORITY_BACKGROUND
    ).apply { start() }
    private val photoHandler = Handler(photoThread.looper)
    private val photoLock = Any()
    private var pendingPhotoCapture: PendingPhotoCapture? = null
    @Volatile private var latestAwbGains: RggbChannelVector? = null
    @Volatile private var latestColorTransform: ColorSpaceTransform? = null

    data class SavedPhoto(
        val file: File,
        val index: Int,
        val total: Int
    )

    private data class PendingPhotoCapture(
        val total: Int,
        var requested: Int = 0,
        var saved: Int = 0,
        val onSaved: (SavedPhoto) -> Unit,
        val onComplete: () -> Unit,
        val onError: (String) -> Unit
    )

    fun start(surface: Surface, settings: CaptureSettings.Snapshot, photoMode: Boolean = false) {
        if (!surface.isValid || settings.previewMode == CaptureSettings.PREVIEW_OFF) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val token = generation.incrementAndGet()
        handler.post {
            closeResources(clearCameraLock = false)
            if (token != generation.get() || !surface.isValid) return@post
            val resolvedCameraId = CameraLensCatalog.resolveCameraId(
                context,
                settings.selectedCameraId,
                settings.resolution
            )
            val cameraId = lockedCameraId
                ?.takeIf { it == resolvedCameraId && isUsableCamera(it) }
                ?: resolvedCameraId?.takeIf(::isUsableCamera)
                ?: run {
                    fail("Nenhuma câmera disponível para o preview")
                    return@post
                }
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: run {
                    fail("Não foi possível consultar a câmera de preview")
                    return@post
                }
            activeSurface = surface
            activeSettings = settings
            activeCharacteristics = characteristics
            activeOisCapability = OpticalStabilizationCapability.inspect(
                manager = cameraManager,
                cameraId = cameraId,
                characteristics = characteristics
            )
            activeCameraId = cameraId
            lockedCameraId = cameraId
            activePhotoMode = photoMode
            photoPrewarmRejected = false
            activeToken = token
            runCatching {
                cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (token != generation.get() || !surface.isValid) {
                            device.close()
                            return
                        }
                        camera = device
                        createSession(device, surface, settings, characteristics, token, photoMode)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (camera === device) camera = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (camera === device) camera = null
                        fail("Preview indisponível (câmera $error)")
                    }
                })
            }.onFailure { fail(it.message ?: "Não foi possível abrir o preview") }
        }
    }

    fun update(surface: Surface, settings: CaptureSettings.Snapshot, photoMode: Boolean = false) {
        if (!surface.isValid || settings.previewMode == CaptureSettings.PREVIEW_OFF) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        handler.post {
            val desiredCameraId = CameraLensCatalog.resolveCameraId(
                context,
                settings.selectedCameraId,
                settings.resolution
            )
            if (desiredCameraId != null && desiredCameraId != activeCameraId) {
                mainHandler.post { start(surface, settings, photoMode) }
                return@post
            }

            val device = camera
            val currentSession = session
            val characteristics = activeCharacteristics
            val previous = activeSettings
            val token = activeToken
            val sameSurface = activeSurface === surface
            if (
                device == null || currentSession == null || characteristics == null || previous == null ||
                token != generation.get() || !sameSurface || !surface.isValid
            ) {
                mainHandler.post { start(surface, settings, photoMode) }
                return@post
            }

            if (requiresSessionRecreation(previous, settings, activePhotoMode, photoMode)) {
                activeSettings = settings
                activePhotoMode = photoMode
                runCatching {
                    session?.stopRepeating()
                    session?.abortCaptures()
                    session?.close()
                    session = null
                    closePhotoReader()
                    createSession(device, surface, settings, characteristics, token, photoMode)
                }.onFailure {
                    mainHandler.post { start(surface, settings, photoMode) }
                }
                return@post
            }

            val sessionSettings = previewSessionSettings(settings, photoMode)
            val highSpeedRange = highSpeedRange(characteristics, sessionSettings.fps)
            val useHighSpeed = !photoMode && sessionSettings.fps >= Int.MAX_VALUE && highSpeedRange != null
            activeSettings = settings
            activePhotoMode = photoMode
            runCatching {
                startRepeating(
                    device = device,
                    value = currentSession,
                    surface = surface,
                    settings = sessionSettings,
                    characteristics = characteristics,
                    fpsRange = highSpeedRange ?: regularFpsRange(characteristics, sessionSettings.fps),
                    highSpeed = useHighSpeed,
                    token = token,
                    photoMode = photoMode
                )
            }.onFailure {
                mainHandler.post { start(surface, settings, photoMode) }
            }
        }
    }

    private fun requiresSessionRecreation(
        previous: CaptureSettings.Snapshot,
        next: CaptureSettings.Snapshot,
        previousPhotoMode: Boolean,
        nextPhotoMode: Boolean
    ): Boolean {
        val previousEffective = previewSessionSettings(previous, previousPhotoMode)
        val nextEffective = previewSessionSettings(next, nextPhotoMode)
        val previousHighSpeed = previousEffective.fps >= Int.MAX_VALUE
        val nextHighSpeed = nextEffective.fps >= Int.MAX_VALUE
        val canReuseAcrossPhotoVideo =
            previousPhotoMode != nextPhotoMode &&
                photoReader?.surface?.isValid == true &&
                !previousEffective.hdrHlg10 &&
                !nextEffective.hdrHlg10 &&
                !previousHighSpeed &&
                !nextHighSpeed

        return (previousPhotoMode != nextPhotoMode && !canReuseAcrossPhotoVideo) ||
            previousEffective.hdrHlg10 != nextEffective.hdrHlg10 ||
            previousHighSpeed != nextHighSpeed ||
            (previousHighSpeed && previousEffective.fps != nextEffective.fps)
    }

    fun capturePhoto(
        burst: Boolean,
        onSaved: (SavedPhoto) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val total = if (burst) PREVIEW_BURST_COUNT else 1
        synchronized(photoLock) {
            if (pendingPhotoCapture != null) {
                mainHandler.post { onError("A câmera ainda está salvando a foto anterior") }
                return
            }
            pendingPhotoCapture = PendingPhotoCapture(
                total = total,
                onSaved = onSaved,
                onComplete = onComplete,
                onError = onError
            )
            photoFocusPrepared = false
            photoFocusPreparing = false
            photoFocusAttempts = 0
        }
        handler.post { captureQueuedPhoto(attempt = 0) }
    }

    private fun dispatchQueuedPhotoIfReady() {
        if (synchronized(photoLock) { pendingPhotoCapture != null }) {
            handler.post { captureQueuedPhoto(attempt = 0) }
        }
    }

    private fun captureQueuedPhoto(attempt: Int) {
        val pending = synchronized(photoLock) { pendingPhotoCapture } ?: return
        val device = camera
        val currentSession = session
        val reader = photoReader
        val settings = activeSettings
        val characteristics = activeCharacteristics
        if (
            !activePhotoMode || device == null || currentSession == null || reader == null ||
            settings == null || characteristics == null || !reader.surface.isValid
        ) {
            if (attempt < PHOTO_READY_RETRIES) {
                handler.postDelayed({ captureQueuedPhoto(attempt + 1) }, PHOTO_READY_RETRY_MS)
            } else {
                failPendingPhoto("O modo foto ainda não ficou pronto")
            }
            return
        }
        if (pending.requested > pending.saved) return
        if (pending.requested >= pending.total) return

        if (!photoFocusPrepared && pending.requested == 0) {
            if (!photoFocusPreparing) {
                photoFocusPreparing = true
                val point = lastFocusPoint
                triggerManualFocus(point.first, point.second, holdFocus = true) { focused ->
                    photoFocusPreparing = false
                    if (focused || photoFocusAttempts >= MAX_PHOTO_FOCUS_RETRIES) {
                        photoFocusPrepared = true
                        captureQueuedPhoto(attempt = 0)
                    } else {
                        photoFocusAttempts++
                        handler.postDelayed({ captureQueuedPhoto(attempt = 0) }, PHOTO_FOCUS_RETRY_DELAY_MS)
                    }
                }
            }
            return
        }

        val requestIndex = pending.requested + 1
        val sessionSettings = previewSessionSettings(settings, photoMode = true)
        val request = runCatching {
            createRequestBuilder(device, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // O disparo usa somente a saída JPEG. A superfície visual continua na sessão,
                // mas não divide o processamento do quadro final com o preview.
                addTarget(reader.surface)
                configureRequest(
                    builder = this,
                    settings = sessionSettings,
                    characteristics = characteristics,
                    fpsRange = null,
                    measuredGains = latestAwbGains,
                    measuredTransform = latestColorTransform,
                    yellowStrength = WhiteBalanceCorrection.strength(settings.yellowReduction, settings.whiteBalanceMode, latestAwbGains)
                )
                configureStillPhotoQuality(this, characteristics)
                set(this, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                // Evita salvar um quadro antigo do buffer ZSL antes do foco terminar.
                set(this, CaptureRequest.CONTROL_ENABLE_ZSL, false)
                set(this, CaptureRequest.JPEG_QUALITY, PREVIEW_JPEG_QUALITY.toByte())
                set(this, CaptureRequest.JPEG_THUMBNAIL_QUALITY, PREVIEW_JPEG_QUALITY.toByte())
                set(this, CaptureRequest.JPEG_ORIENTATION, calculateJpegOrientation(characteristics))
                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (photoFocusPrepared && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                    set(this, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    applyMeteringRegions(this, characteristics, lastFocusPoint.first, lastFocusPoint.second)
                    set(this, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                } else {
                    when {
                        afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                            set(this, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                            set(this, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    }
                }
            }.build()
        }.getOrElse {
            failPendingPhoto(it.message ?: "Não foi possível preparar a foto")
            return
        }

        pending.requested = requestIndex
        runCatching {
            currentSession.captureSingleRequest(
                request,
                executor,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        failPendingPhoto("A câmera não conseguiu capturar a foto")
                    }
                }
            )
        }.onFailure {
            failPendingPhoto(it.message ?: "Falha ao disparar a foto")
        }
    }

    private fun configureStillPhotoQuality(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        setPreferredMode(
            builder,
            CaptureRequest.EDGE_MODE,
            characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CameraMetadata.EDGE_MODE_HIGH_QUALITY,
            CameraMetadata.EDGE_MODE_FAST,
            CameraMetadata.EDGE_MODE_OFF
        )
        setPreferredMode(
            builder,
            CaptureRequest.NOISE_REDUCTION_MODE,
            characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        )
        setPreferredMode(
            builder,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST
        )
        setPreferredMode(
            builder,
            CaptureRequest.HOT_PIXEL_MODE,
            characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY,
            CameraMetadata.HOT_PIXEL_MODE_FAST
        )
        setPreferredMode(
            builder,
            CaptureRequest.SHADING_MODE,
            characteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
            CameraMetadata.SHADING_MODE_HIGH_QUALITY,
            CameraMetadata.SHADING_MODE_FAST
        )
        setPreferredMode(
            builder,
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
        )

        val videoModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        if (videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            set(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        activeOisCapability?.takeIf { it.supported }?.let { capability ->
            OpticalStabilizationCapability.apply(builder, capability, enabled = true)
        }
    }

    private fun setPreferredMode(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<Int>,
        available: IntArray?,
        vararg preferred: Int
    ) {
        preferred.firstOrNull { available?.contains(it) == true }?.let { set(builder, key, it) }
    }

    private fun createPhotoReader(characteristics: CameraCharacteristics): ImageReader? {
        closePhotoReader()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val selected = sizes
            .filter { area(it) <= MAX_PREVIEW_PHOTO_PIXELS }
            .sortedWith(
                compareByDescending<Size> { if (isNearFourByThree(it)) 1 else 0 }
                    .thenByDescending { area(it) }
            )
            .firstOrNull()
            ?: sizes.minByOrNull { kotlin.math.abs(area(it) - MAX_PREVIEW_PHOTO_PIXELS) }
            ?: return null

        return ImageReader.newInstance(
            selected.width,
            selected.height,
            ImageFormat.JPEG,
            PREVIEW_PHOTO_READER_IMAGES
        ).also { reader ->
            photoReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val bytes = runCatching {
                    val buffer = image.planes.first().buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }.getOrElse {
                    runCatching { image.close() }
                    failPendingPhoto(it.message ?: "Não foi possível ler a foto")
                    return@setOnImageAvailableListener
                }
                runCatching { image.close() }
                savePreviewPhoto(bytes)
            }, photoHandler)
        }
    }

    private fun savePreviewPhoto(bytes: ByteArray) {
        val pending = synchronized(photoLock) { pendingPhotoCapture } ?: return
        val index = pending.saved + 1
        val target = runCatching {
            VaultRepository.createPhotoFile(
                context = context,
                sequenceIndex = index.takeIf { pending.total > 1 }
            ).also { file ->
                file.outputStream().buffered().use { output ->
                    output.write(bytes)
                    output.flush()
                }
                require(file.isFile && file.length() > 0L) { "A foto criada ficou vazia" }
            }
        }.getOrElse {
            failPendingPhoto(it.message ?: "Não foi possível salvar a foto")
            return
        }

        val completed: Boolean
        synchronized(photoLock) {
            val current = pendingPhotoCapture ?: return
            current.saved++
            completed = current.saved >= current.total
        }
        mainHandler.post { pending.onSaved(SavedPhoto(target, index, pending.total)) }
        if (completed) {
            synchronized(photoLock) { pendingPhotoCapture = null }
            handler.post { resetPhotoFocusAndRestore() }
            mainHandler.post { pending.onComplete() }
        } else {
            handler.postDelayed({ captureQueuedPhoto(attempt = 0) }, PREVIEW_BURST_INTERVAL_MS)
        }
    }

    private fun failPendingPhoto(message: String) {
        val callback = synchronized(photoLock) {
            val pending = pendingPhotoCapture
            pendingPhotoCapture = null
            pending?.onError
        }
        handler.post { resetPhotoFocusAndRestore() }
        callback?.let { mainHandler.post { it(message) } }
    }

    private fun currentDisplayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(0)
            ?.rotation
            ?: Surface.ROTATION_0

    private fun calculateJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val rotation = currentDisplayRotation()
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun isUsableCamera(cameraId: String): Boolean = runCatching {
        cameraId in cameraManager.cameraIdList
    }.getOrDefault(false)


    fun focusAt(
        normalizedX: Float,
        normalizedY: Float,
        onResult: (Boolean) -> Unit = {}
    ) {
        handler.post {
            triggerManualFocus(normalizedX, normalizedY, holdFocus = false) { focused ->
                mainHandler.post { onResult(focused) }
            }
        }
    }

    fun lockFocusAndExposureAt(
        normalizedX: Float,
        normalizedY: Float,
        onResult: (Boolean) -> Unit = {}
    ) {
        handler.post {
            triggerManualFocus(normalizedX, normalizedY, holdFocus = true) { focused ->
                mainHandler.post { onResult(focused) }
            }
        }
    }

    fun unlockFocusAndExposure() {
        handler.post {
            manualFocusToken++
            restoreRepeatingAfterFocus()
        }
    }

    private fun triggerManualFocus(
        normalizedX: Float,
        normalizedY: Float,
        holdFocus: Boolean,
        onReady: (Boolean) -> Unit
    ) {
        val device = camera
        val currentSession = session
        val surface = activeSurface
        val settings = activeSettings
        val characteristics = activeCharacteristics
        val token = activeToken
        if (
            device == null || currentSession == null || surface == null || settings == null ||
            characteristics == null || token != generation.get() || !surface.isValid ||
            currentSession is CameraConstrainedHighSpeedCaptureSession
        ) {
            onReady(false)
            return
        }

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val supportsAutoFocus = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) &&
            (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        if (!supportsAutoFocus) {
            onReady(true)
            return
        }

        val x = normalizedX.coerceIn(0f, 1f)
        val y = normalizedY.coerceIn(0f, 1f)
        lastFocusPoint = x to y
        clearFocusCallbacks()
        val focusToken = ++manualFocusToken
        val sessionSettings = previewSessionSettings(settings, activePhotoMode)
        val fpsRange = regularFpsRange(characteristics, sessionSettings.fps)

        fun buildFocusRequest(trigger: Int): CaptureRequest =
            createRequestBuilder(device, CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                configureRequest(this, sessionSettings, characteristics, fpsRange, null, null, 0f)
                set(this, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                applyMeteringRegions(this, characteristics, x, y)
                set(this, CaptureRequest.CONTROL_AF_TRIGGER, trigger)
                if (trigger == CameraMetadata.CONTROL_AF_TRIGGER_START) {
                    set(this, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                } else {
                    set(this, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                }
            }.build()

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                callbackSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (focusToken != manualFocusToken || token != generation.get()) return
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val lensState = result.get(CaptureResult.LENS_STATE)
                val exposureReady = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                val lensReady = lensState == null || lensState == CaptureResult.LENS_STATE_STATIONARY
                when {
                    exposureReady && lensReady && (
                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    ) -> finishManualFocus(focusToken, token, true, holdFocus, onReady)
                    exposureReady && lensReady && afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                        finishManualFocus(focusToken, token, false, holdFocus, onReady)
                }
            }
        }

        runCatching {
            currentSession.setSingleRepeatingRequest(
                buildFocusRequest(CameraMetadata.CONTROL_AF_TRIGGER_IDLE),
                executor,
                callback
            )
            currentSession.captureSingleRequest(
                buildFocusRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL),
                executor,
                callback
            )
            currentSession.captureSingleRequest(
                buildFocusRequest(CameraMetadata.CONTROL_AF_TRIGGER_START),
                executor,
                callback
            )
        }.onFailure {
            finishManualFocus(focusToken, token, false, holdFocus, onReady)
            return
        }

        focusTimeoutRunnable = Runnable {
            finishManualFocus(focusToken, token, false, holdFocus, onReady)
        }.also { handler.postDelayed(it, MANUAL_FOCUS_TIMEOUT_MS) }
    }

    private fun finishManualFocus(
        focusToken: Int,
        sessionToken: Int,
        focused: Boolean,
        holdFocus: Boolean,
        onReady: (Boolean) -> Unit
    ) {
        if (focusToken != manualFocusToken || sessionToken != generation.get()) return
        focusTimeoutRunnable?.let(handler::removeCallbacks)
        focusTimeoutRunnable = null
        val restoreToken = ++manualFocusToken
        onReady(focused)
        if (holdFocus) {
            keepFocusAndExposureLocked(sessionToken)
            return
        }
        focusRestoreRunnable = Runnable {
            if (restoreToken == manualFocusToken && sessionToken == generation.get()) restoreRepeatingAfterFocus()
        }.also { handler.postDelayed(it, MANUAL_FOCUS_HOLD_MS) }
    }

    private fun keepFocusAndExposureLocked(sessionToken: Int) {
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = activeSurface?.takeIf { it.isValid } ?: return
        val settings = activeSettings ?: return
        val characteristics = activeCharacteristics ?: return
        if (sessionToken != generation.get() || currentSession is CameraConstrainedHighSpeedCaptureSession) return
        val sessionSettings = previewSessionSettings(settings, activePhotoMode)
        val fpsRange = regularFpsRange(characteristics, sessionSettings.fps)
        val (x, y) = lastFocusPoint
        val request = runCatching {
            createRequestBuilder(device, CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                configureRequest(this, sessionSettings, characteristics, fpsRange, null, null, 0f)
                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                    set(this, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                }
                applyMeteringRegions(this, characteristics, x, y)
                set(this, CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                if (characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                    set(this, CaptureRequest.CONTROL_AE_LOCK, true)
                }
                if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                    set(this, CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }.build()
        }.getOrNull() ?: return
        runCatching {
            currentSession.setSingleRepeatingRequest(
                request,
                executor,
                object : CameraCaptureSession.CaptureCallback() {}
            )
        }
    }

    private fun restoreRepeatingAfterFocus() {
        clearFocusCallbacks()
        val device = camera ?: return
        val currentSession = session ?: return
        val surface = activeSurface?.takeIf { it.isValid } ?: return
        val settings = activeSettings ?: return
        val characteristics = activeCharacteristics ?: return
        val token = activeToken
        if (token != generation.get() || currentSession is CameraConstrainedHighSpeedCaptureSession) return
        val sessionSettings = previewSessionSettings(settings, activePhotoMode)
        runCatching {
            startRepeating(
                device = device,
                value = currentSession,
                surface = surface,
                settings = sessionSettings,
                characteristics = characteristics,
                fpsRange = regularFpsRange(characteristics, sessionSettings.fps),
                highSpeed = false,
                token = token,
                photoMode = activePhotoMode
            )
        }
    }

    private fun resetPhotoFocusAndRestore() {
        photoFocusPrepared = false
        photoFocusPreparing = false
        photoFocusAttempts = 0
        manualFocusToken++
        restoreRepeatingAfterFocus()
    }

    private fun clearFocusCallbacks() {
        focusTimeoutRunnable?.let(handler::removeCallbacks)
        focusRestoreRunnable?.let(handler::removeCallbacks)
        focusTimeoutRunnable = null
        focusRestoreRunnable = null
    }

    private fun applyMeteringRegions(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        normalizedX: Float,
        normalizedY: Float
    ) {
        val region = meteringRegion(characteristics, normalizedX, normalizedY) ?: return
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            set(builder, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            set(builder, CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun meteringRegion(
        characteristics: CameraCharacteristics,
        normalizedX: Float,
        normalizedY: Float
    ): MeteringRectangle? {
        val activeArray = CameraZoom.sensorRegion(characteristics, activeSettings?.zoomRatio ?: 1f)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = currentDisplayRotation()
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relativeRotation = (sensorOrientation - deviceDegrees + 360) % 360
        val viewX = normalizedX.coerceIn(0f, 1f)
        val viewY = normalizedY.coerceIn(0f, 1f)
        val (sensorX, sensorY) = when (relativeRotation) {
            90 -> viewY to (1f - viewX)
            180 -> (1f - viewX) to (1f - viewY)
            270 -> (1f - viewY) to viewX
            else -> viewX to viewY
        }
        val centerX = activeArray.left + (sensorX * activeArray.width()).toInt()
        val centerY = activeArray.top + (sensorY * activeArray.height()).toInt()
        val regionWidth = (activeArray.width() * FOCUS_REGION_FRACTION).toInt().coerceAtLeast(1)
        val regionHeight = (activeArray.height() * FOCUS_REGION_FRACTION).toInt().coerceAtLeast(1)
        val left = (centerX - regionWidth / 2).coerceIn(activeArray.left, activeArray.right - regionWidth)
        val top = (centerY - regionHeight / 2).coerceIn(activeArray.top, activeArray.bottom - regionHeight)
        return MeteringRectangle(
            left,
            top,
            regionWidth,
            regionHeight,
            MeteringRectangle.METERING_WEIGHT_MAX
        )
    }

    fun currentCameraId(): String? = activeCameraId

    fun stop() {
        generation.incrementAndGet()
        handler.post { closeResources(clearCameraLock = true) }
    }

    fun stopAndThen(onStopped: () -> Unit) {
        generation.incrementAndGet()
        handler.post {
            closeResources(clearCameraLock = true)
            mainHandler.post { onStopped() }
        }
    }

    fun release() {
        generation.incrementAndGet()
        handler.post {
            closeResources(clearCameraLock = true)
            thread.quitSafely()
            photoThread.quitSafely()
        }
    }

    private fun createSession(
        device: CameraDevice,
        surface: Surface,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics,
        token: Int,
        photoMode: Boolean
    ) {
        val sessionSettings = previewSessionSettings(settings, photoMode)
        val highSpeedRange = highSpeedRange(characteristics, sessionSettings.fps)
        val useHighSpeed = !photoMode && sessionSettings.fps >= Int.MAX_VALUE && highSpeedRange != null
        val previewOutput = OutputConfiguration(surface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (sessionSettings.hdrHlg10) {
                    val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    if (profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true) {
                        runCatching { setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                    }
                }
                if (!useHighSpeed) {
                    val useCases = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                        ?: longArrayOf()
                    val previewUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
                    if (useCases.contains(previewUseCase)) runCatching { setStreamUseCase(previewUseCase) }
                }
            }
        }
        // O preview de vídeo usa somente a superfície visual. Manter um ImageReader JPEG
        // pré-aquecido nesta sessão aumenta a carga da HAL e causa engasgos principalmente
        // em 4K60. A saída de foto é criada apenas ao entrar no modo FOTO.
        val prewarmPhotoOutput = false
        val includePhotoOutput = photoMode
        val outputs = mutableListOf(previewOutput)
        if (includePhotoOutput) {
            createPhotoReader(characteristics)?.let { reader ->
                outputs += OutputConfiguration(reader.surface).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !useHighSpeed) {
                        val useCases = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                            ?: longArrayOf()
                        val stillUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()
                        if (useCases.contains(stillUseCase)) runCatching { setStreamUseCase(stillUseCase) }
                    }
                }
            }
        }
        val configuration = SessionConfiguration(
            if (useHighSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (token != generation.get() || camera !== device || !surface.isValid) {
                        value.close()
                        return
                    }
                    session = value
                    activeSettings = settings
                    activePhotoMode = photoMode
                    runCatching {
                        startRepeating(
                            device = device,
                            value = value,
                            surface = surface,
                            settings = sessionSettings,
                            characteristics = characteristics,
                            fpsRange = highSpeedRange ?: regularFpsRange(characteristics, sessionSettings.fps),
                            highSpeed = useHighSpeed,
                            token = token,
                            photoMode = photoMode
                        )
                        dispatchQueuedPhotoIfReady()
                    }.onFailure {
                        value.close()
                        if (session === value) session = null
                        fail(it.message ?: "Falha ao iniciar o preview")
                    }
                }

                override fun onConfigureFailed(value: CameraCaptureSession) {
                    value.close()
                    if (session === value) session = null
                    closePhotoReader()
                    if (prewarmPhotoOutput && !photoPrewarmRejected && token == generation.get()) {
                        photoPrewarmRejected = true
                        runCatching {
                            createSession(device, surface, settings, characteristics, token, photoMode = false)
                        }.onFailure {
                            fail(it.message ?: "A câmera recusou a superfície de preview")
                        }
                        return
                    }
                    fail(
                        if (sessionSettings.fps >= Int.MAX_VALUE) {
                            "A câmera não aceitou o preview em ${sessionSettings.fps} FPS; a gravação ainda será testada normalmente"
                        } else if (photoMode) {
                            "A câmera não aceitou foto e preview na mesma sessão"
                        } else {
                            "A câmera recusou a superfície de preview"
                        }
                    )
                }
            }
        )
        device.createCaptureSession(configuration)
    }

    private fun previewSessionSettings(
        settings: CaptureSettings.Snapshot,
        photoMode: Boolean
    ): CaptureSettings.Snapshot = if (photoMode) {
        settings.copy(
            fps = CaptureModeStore.FPS_30,
            hdrHlg10 = false,
            focusMode = CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
            noiseReduction = CaptureSettings.PROCESSING_HIGH_QUALITY,
            edgeMode = CaptureSettings.PROCESSING_HIGH_QUALITY
        )
    } else {
        settings
    }

    private fun startRepeating(
        device: CameraDevice,
        value: CameraCaptureSession,
        surface: Surface,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics,
        fpsRange: Range<Int>?,
        highSpeed: Boolean,
        token: Int,
        photoMode: Boolean
    ) {
        val template = if (highSpeed || settings.fps >= CaptureModeStore.FPS_60) {
            CameraDevice.TEMPLATE_RECORD
        } else {
            CameraDevice.TEMPLATE_PREVIEW
        }
        val corrected = AtomicBoolean(false)
        val firstFrameDelivered = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        lateinit var callback: CameraCaptureSession.CaptureCallback
        callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (token != generation.get()) return
                if (firstFrameDelivered.compareAndSet(false, true)) {
                    mainHandler.post { onPreviewFrame(photoMode) }
                }
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { latestAwbGains = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { latestColorTransform = it }
                val measuredGains = latestAwbGains
                val measuredTransform = latestColorTransform
                val measuredCameraId = activeCameraId
                if (measuredGains != null && measuredTransform != null && measuredCameraId != null) {
                    Camera3AStateStore.updateWhiteBalance(measuredCameraId, measuredGains, measuredTransform)
                }
                if (measuredCameraId != null) {
                    val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    val frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
                    if (exposureTimeNs != null && sensitivityIso != null) {
                        Camera3AStateStore.updateExposure(measuredCameraId, exposureTimeNs, sensitivityIso, frameDurationNs)
                    }
                }
                if (highSpeed || corrected.get() || frames.incrementAndGet() < COLOR_MEASURE_FRAMES) return
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
                val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return
                val strength = WhiteBalanceCorrection.strength(settings.yellowReduction, settings.whiteBalanceMode, gains)
                if (strength <= 0f || !corrected.compareAndSet(false, true)) return
                runCatching {
                    val correctedRequest = createRequestBuilder(device, template).apply {
                        addTarget(surface)
                        configureRequest(this, settings, characteristics, fpsRange, gains, transform, strength)
                    }.build()
                    value.setSingleRepeatingRequest(correctedRequest, executor, callback)
                }
            }
        }

        val request = createRequestBuilder(device, template).apply {
            addTarget(surface)
            configureRequest(this, settings, characteristics, fpsRange, null, null, 0f)
        }.build()

        if (highSpeed) {
            val highSpeedSession = value as? CameraConstrainedHighSpeedCaptureSession
                ?: error("A HAL não retornou uma sessão high-speed")
            val requests = highSpeedSession.createHighSpeedRequestList(request)
            highSpeedSession.setRepeatingBurstRequests(requests, executor, callback)
        } else {
            value.setSingleRepeatingRequest(request, executor, callback)
        }
    }

    private fun configureRequest(
        builder: CaptureRequest.Builder,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics,
        fpsRange: Range<Int>?,
        measuredGains: RggbChannelVector?,
        measuredTransform: ColorSpaceTransform?,
        yellowStrength: Float
    ) {
        set(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(
            builder,
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            if (settings.fps >= CaptureModeStore.FPS_60) {
                CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            } else {
                CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW
            }
        )
        fpsRange?.let { set(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureRange?.let {
            set(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.exposureCompensation.coerceIn(it.lower, it.upper))
        }

        CameraZoom.apply(builder, characteristics, settings.zoomRatio)

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val requestedAf = when (settings.focusMode) {
            CaptureSettings.FOCUS_CONTINUOUS_PICTURE -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureSettings.FOCUS_AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO
            CaptureSettings.FOCUS_OFF -> CameraMetadata.CONTROL_AF_MODE_OFF
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        listOf(
            requestedAf,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_OFF
        ).firstOrNull { afModes.contains(it) }?.let { set(builder, CaptureRequest.CONTROL_AF_MODE, it) }

        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val requestedAwb = awbMode(settings.whiteBalanceMode)
        listOf(requestedAwb, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
            .firstOrNull { awbModes.contains(it) }?.let { set(builder, CaptureRequest.CONTROL_AWB_MODE, it) }
        if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            set(builder, CaptureRequest.CONTROL_AWB_LOCK, false)
        }

        val antibandingModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
        val anti = when (settings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(anti)) set(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, anti)

        applyStabilization(builder, settings, characteristics)
        applyProcessing(builder, settings, characteristics)
        applyColorProfile(builder, settings, characteristics)
        applyYellowCorrection(builder, settings, characteristics, awbModes, measuredGains, measuredTransform, yellowStrength)
    }

    private fun applyStabilization(
        builder: CaptureRequest.Builder,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics
    ) {
        val videoModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val hasEis = videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        val hasPreview = videoModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
        val oisCapability = activeOisCapability
        val hasOis = oisCapability?.supported == true

        fun off() {
            set(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            oisCapability?.let {
                OpticalStabilizationCapability.apply(builder, it, enabled = false)
            }
        }
        fun eis(mode: Int) {
            set(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
            oisCapability?.let {
                OpticalStabilizationCapability.apply(builder, it, enabled = false)
            }
        }
        fun ois() {
            set(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            oisCapability?.let {
                OpticalStabilizationCapability.apply(builder, it, enabled = true)
            }
        }

        // Em sessão constrained high-speed, EIS/OIS pode introduzir cadência irregular,
        // O preview usa somente a sessão regular da HAL.
        if (settings.fps >= Int.MAX_VALUE) {
            off()
            return
        }

        when (settings.stabilization) {
            CaptureSettings.STABILIZATION_OFF -> off()
            CaptureSettings.STABILIZATION_PREVIEW -> if (hasPreview) eis(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) else off()
            CaptureSettings.STABILIZATION_EIS -> if (hasEis) eis(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) else off()
            CaptureSettings.STABILIZATION_OIS -> if (hasOis) ois() else off()
            else -> off()
        }
    }

    private fun applyProcessing(
        builder: CaptureRequest.Builder,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics
    ) {
        val highSpeedProcessing = settings.fps >= Int.MAX_VALUE
        val noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val noise = when {
            highSpeedProcessing -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            settings.noiseReduction == CaptureSettings.PROCESSING_OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            settings.noiseReduction == CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        }
        listOf(noise, CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            .firstOrNull { noiseModes.contains(it) }?.let { set(builder, CaptureRequest.NOISE_REDUCTION_MODE, it) }

        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val edge = when {
            highSpeedProcessing -> CameraMetadata.EDGE_MODE_OFF
            settings.edgeMode == CaptureSettings.PROCESSING_HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            settings.edgeMode == CaptureSettings.PROCESSING_OFF -> CameraMetadata.EDGE_MODE_OFF
            else -> CameraMetadata.EDGE_MODE_FAST
        }
        listOf(edge, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_OFF)
            .firstOrNull { edgeModes.contains(it) }?.let { set(builder, CaptureRequest.EDGE_MODE, it) }
    }

    private fun applyColorProfile(
        builder: CaptureRequest.Builder,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics
    ) {
        if (settings.hdrHlg10 || settings.fps >= Int.MAX_VALUE) return
        val modes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
        val wantsCurve = settings.colorProfile == CaptureSettings.COLOR_SOFT || settings.colorProfile == CaptureSettings.COLOR_FLAT
        if (wantsCurve && modes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            val points = if (settings.colorProfile == CaptureSettings.COLOR_FLAT) {
                floatArrayOf(0f, 0f, 0.10f, 0.17f, 0.28f, 0.35f, 0.50f, 0.50f, 0.72f, 0.65f, 0.90f, 0.83f, 1f, 1f)
            } else {
                floatArrayOf(0f, 0f, 0.14f, 0.19f, 0.34f, 0.39f, 0.50f, 0.50f, 0.66f, 0.61f, 0.86f, 0.81f, 1f, 1f)
            }
            set(builder, CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            set(builder, CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
        }
    }

    private fun applyYellowCorrection(
        builder: CaptureRequest.Builder,
        settings: CaptureSettings.Snapshot,
        characteristics: CameraCharacteristics,
        awbModes: IntArray,
        gains: RggbChannelVector?,
        transform: ColorSpaceTransform?,
        strength: Float
    ) {
        if (strength <= 0f) return
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualPost = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        if (manualPost && gains != null && transform != null && awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)) {
            set(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            set(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(builder, CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            set(builder, CaptureRequest.COLOR_CORRECTION_GAINS, WhiteBalanceCorrection.adjustedGains(gains, strength))
        } else if (WhiteBalanceCorrection.useIncandescentFallback(strength) && awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)) {
            set(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
        }
    }

    private fun regularFpsRange(characteristics: CameraCharacteristics, targetFps: Int): Range<Int>? {
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return Range(targetFps, targetFps)
    }

    private fun highSpeedRange(characteristics: CameraCharacteristics, targetFps: Int): Range<Int>? {
        if (targetFps < Int.MAX_VALUE) return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val size = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        val sizes = runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList())
        if (size !in sizes) return null
        val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())

        // Caminho legado mantido inacessível; os modos ativos são somente 30/60.
        return ranges.firstOrNull { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
    }


    private fun awbMode(value: String): Int = when (value) {
        CaptureSettings.WHITE_BALANCE_INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        CaptureSettings.WHITE_BALANCE_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        CaptureSettings.WHITE_BALANCE_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        CaptureSettings.WHITE_BALANCE_CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        CaptureSettings.WHITE_BALANCE_TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        CaptureSettings.WHITE_BALANCE_SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    private fun createRequestBuilder(
        device: CameraDevice,
        template: Int
    ): CaptureRequest.Builder = device.createCaptureRequest(template)

    private fun <T> set(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        runCatching { builder.set(key, value) }
    }

    private fun fail(message: String) {
        mainHandler.post { onFailure(message) }
    }

    private fun closePhotoReader() {
        runCatching { photoReader?.close() }
        photoReader = null
        latestAwbGains = null
        latestColorTransform = null
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height.toLong()

    private fun isNearFourByThree(size: Size): Boolean {
        val ratio = size.width.toDouble() / size.height.toDouble().coerceAtLeast(1.0)
        return kotlin.math.abs(ratio - (4.0 / 3.0)) <= PHOTO_ASPECT_TOLERANCE
    }

    private fun closeResources(clearCameraLock: Boolean) {
        clearFocusCallbacks()
        manualFocusToken++
        photoFocusPrepared = false
        photoFocusPreparing = false
        photoFocusAttempts = 0
        failPendingPhoto("A sessão da câmera foi encerrada")
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        closePhotoReader()
        runCatching { camera?.close() }
        camera = null
        activeSurface = null
        activeSettings = null
        activeCharacteristics = null
        activeOisCapability = null
        activeCameraId = null
        activePhotoMode = false
        activeToken = 0
        if (clearCameraLock) lockedCameraId = null
    }

    private companion object {
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
        const val COLOR_MEASURE_FRAMES = 8
        const val MAX_PREVIEW_PHOTO_PIXELS = 50_000_000L
        const val PREVIEW_PHOTO_READER_IMAGES = 3
        const val PREVIEW_JPEG_QUALITY = 100
        const val PREVIEW_BURST_COUNT = 5
        const val PREVIEW_BURST_INTERVAL_MS = 260L
        const val PHOTO_READY_RETRIES = 12
        const val PHOTO_READY_RETRY_MS = 80L
        const val PHOTO_ASPECT_TOLERANCE = 0.08
        const val MANUAL_FOCUS_TIMEOUT_MS = 1_800L
        const val MANUAL_FOCUS_HOLD_MS = 2_200L
        const val FOCUS_REGION_FRACTION = 0.10f
        const val MAX_PHOTO_FOCUS_RETRIES = 1
        const val PHOTO_FOCUS_RETRY_DELAY_MS = 140L
    }
}
