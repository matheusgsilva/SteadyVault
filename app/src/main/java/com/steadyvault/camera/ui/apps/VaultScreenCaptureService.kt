package com.steadyvault.camera.ui.apps

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.steadyvault.camera.R
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.RecordingRecoveryRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultStartupCoordinator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Controle flutuante para prints imediatos e gravações de tela salvos somente no cofre escolhido. */
class VaultScreenCaptureService : Service() {
    private data class RecordingProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-ScreenCaptureWriter")
    }
    private val finishing = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)
    private val savingScreenshot = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val recordingTransition = AtomicBoolean(false)
    private val finishWhenReady = AtomicBoolean(false)

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFiles: VaultRepository.PrivateCaptureFiles? = null
    private var overlayView: LinearLayout? = null
    private var cameraButton: ImageButton? = null
    private var recordButton: ImageButton? = null
    private var destination = DESTINATION_PRIMARY

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { requestCloseSession() }
        }
    }

    private val expireSession = Runnable {
        if (!recording.get() && !recordingTransition.get()) {
            finishSession("Controle de captura encerrado por inatividade.")
        }
    }

    private val captureTimeout = Runnable {
        if (captureRequested.compareAndSet(true, false)) {
            cleanupScreenshotSurface()
            setOverlayVisible(true)
            setOverlayButtonsEnabled(true)
            updateNotification("Controle flutuante pronto • ${destinationLabel()}")
            Toast.makeText(this, "Não foi possível obter o quadro. Tente novamente.", Toast.LENGTH_LONG).show()
            if (finishWhenReady.compareAndSet(true, false)) finishSession(null) else scheduleIdleTimeout()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeSession.set(true)
        windowManager = getSystemService(WindowManager::class.java)
        captureThread = HandlerThread("SteadyVault-ScreenCapture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_CANCEL -> if (
                intent.getBooleanExtra(EXTRA_USER_REQUESTED_CLOSE, false)
            ) {
                requestCloseSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val requestedDestination = intent.getStringExtra(EXTRA_DESTINATION)
            ?.takeIf { it in SUPPORTED_DESTINATIONS }
            ?: DESTINATION_PRIMARY
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Preparando controle flutuante…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (VaultStartupCoordinator.isCapturePriorityActive(this)) {
            finishSession("A câmera está gravando e tem prioridade sobre a captura de tela.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            finishSession("Autorize a exibição sobre outros apps para usar os botões flutuantes.")
            return
        }
        if (projection != null) {
            lockDestination(requestedDestination)
            updateNotification("Controle flutuante pronto • ${destinationLabel()}")
            Toast.makeText(this, "O controle já está ativo em ${destinationLabel()}.", Toast.LENGTH_SHORT).show()
            return
        }
        destination = requestedDestination

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = projectionData(intent)
        if (resultCode == 0 || resultData == null) {
            finishSession("Autorização de captura inválida.")
            return
        }

        runCatching {
            val directory = destinationDirectory()
            VaultRepository.cleanupStalePrivateCaptures(this, directory)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val activeProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("O Android não iniciou o compartilhamento de tela")
            projection = activeProjection
            activeProjection.registerCallback(projectionCallback, mainHandler)
            showOverlay()
            lockDestination()
        }.onSuccess {
            updateNotification("Controle flutuante pronto • ${destinationLabel()}")
            Toast.makeText(
                this,
                "Use os botões flutuantes para print ou gravação. Destino: ${destinationLabel()}.",
                Toast.LENGTH_LONG
            ).show()
            scheduleIdleTimeout()
        }.onFailure {
            finishSession(it.message ?: "Não foi possível preparar o controle de captura.")
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setBackgroundResource(R.drawable.bg_oneui_dialog)
            elevation = dp(10).toFloat()
            contentDescription = "Controles de captura do SteadyVault"
        }
        val handle = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            contentDescription = "Arrastar controles"
        }
        panel.addView(handle, LinearLayout.LayoutParams(dp(30), dp(48)))
        cameraButton = createOverlayButton(
            icon = R.drawable.ic_camera,
            description = "Salvar print agora",
            tint = AppearanceStore.palette(this).accent
        ).also { button ->
            button.setOnClickListener { captureScreenshotNow() }
            panel.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        recordButton = createOverlayButton(
            icon = R.drawable.ic_record,
            description = "Iniciar gravação da tela",
            tint = getColor(R.color.record_red)
        ).also { button ->
            button.setOnClickListener { toggleScreenRecording() }
            panel.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        }
        createOverlayButton(
            icon = R.drawable.ic_close,
            description = "Fechar controles de captura",
            tint = getColor(R.color.text_secondary)
        ).also { button ->
            button.setOnClickListener { requestCloseSession() }
            panel.addView(button, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginStart = dp(4) })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(92)
        }
        bindOverlayDrag(handle, panel, params)
        windowManager.addView(panel, params)
        overlayView = panel
    }

    private fun createOverlayButton(icon: Int, description: String, tint: Int): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            setBackgroundResource(R.drawable.bg_capture_utility)
            contentDescription = description
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

    private fun bindOverlayDrag(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + dx).coerceIn(0, (metrics.widthPixels - panel.width).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (metrics.heightPixels - panel.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(panel, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) Haptics.tap(this)
                    true
                }
                else -> false
            }
        }
    }

    private fun captureScreenshotNow() {
        if (recording.get() || recordingTransition.get()) {
            Toast.makeText(this, "Pare a gravação antes de tirar um print.", Toast.LENGTH_SHORT).show()
            return
        }
        if (savingScreenshot.get() || !captureRequested.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(expireSession)
        setOverlayButtonsEnabled(false)
        setOverlayVisible(false)
        Haptics.tap(this)
        mainHandler.postDelayed({
            if (finishing.get()) return@postDelayed
            runCatching {
                val (width, height) = captureSize()
                prepareImageReader(width, height)
                switchVirtualSurface(imageReader!!.surface, width, height)
                updateNotification("Salvando print em ${destinationLabel()}…")
                mainHandler.removeCallbacks(captureTimeout)
                mainHandler.postDelayed(captureTimeout, FRAME_TIMEOUT_MS)
            }.onFailure {
                captureRequested.set(false)
                cleanupScreenshotSurface()
                setOverlayVisible(true)
                setOverlayButtonsEnabled(true)
                Haptics.error(this)
                Toast.makeText(this, it.message ?: "Não foi possível capturar a tela.", Toast.LENGTH_LONG).show()
                if (finishWhenReady.compareAndSet(true, false)) finishSession(null) else scheduleIdleTimeout()
            }
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun prepareImageReader(width: Int, height: Int) {
        cleanupImageReader()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        if (!captureRequested.compareAndSet(true, false) || !savingScreenshot.compareAndSet(false, true)) {
            image.close()
            return
        }
        mainHandler.removeCallbacks(captureTimeout)
        runCatching { fileExecutor.execute { saveScreenshot(image) } }
            .onFailure {
                image.close()
                savingScreenshot.set(false)
                mainHandler.post { finishScreenshot(it.message ?: "Não foi possível salvar o print.", success = false) }
            }
    }

    private fun saveScreenshot(image: Image) {
        var paddedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var files: VaultRepository.PrivateCaptureFiles? = null
        var finalFile: File? = null
        val result = runCatching {
            val plane = image.planes.firstOrNull() ?: throw IllegalStateException("Tela sem pixels disponíveis")
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
            val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
            val buffer = plane.buffer.apply { rewind() }
            paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(buffer)
            }
            finalBitmap = if (paddedWidth == width) paddedBitmap
            else Bitmap.createBitmap(paddedBitmap!!, 0, 0, width, height)

            files = VaultRepository.createScreenshotFiles(this, destinationDirectory())
            files!!.working.outputStream().buffered().use { stream ->
                check(finalBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Não foi possível codificar a captura"
                }
            }
            finalFile = VaultRepository.commitPrivateCapture(files!!)
            check(VaultRepository.readItem(finalFile!!, fast = false) != null) { "O print gerado ficou inválido" }
        }
        if (result.isFailure) {
            finalFile?.delete()
            VaultRepository.discardPrivateCapture(files)
        }
        if (finalBitmap !== paddedBitmap) finalBitmap?.recycle()
        paddedBitmap?.recycle()
        image.close()
        savingScreenshot.set(false)
        mainHandler.post {
            finishScreenshot(result.exceptionOrNull()?.message, success = result.isSuccess)
        }
    }

    private fun finishScreenshot(message: String?, success: Boolean) {
        cleanupScreenshotSurface()
        setOverlayVisible(true)
        setOverlayButtonsEnabled(true)
        updateNotification("Controle flutuante pronto • ${destinationLabel()}")
        if (success) {
            Haptics.photo(this)
            Toast.makeText(this, "Print salvo somente em ${destinationLabel()}.", Toast.LENGTH_SHORT).show()
        } else {
            Haptics.error(this)
            Toast.makeText(this, message ?: "Não foi possível salvar o print.", Toast.LENGTH_LONG).show()
        }
        if (finishWhenReady.compareAndSet(true, false)) finishSession(null) else scheduleIdleTimeout()
    }

    private fun toggleScreenRecording() {
        if (recordingTransition.get()) return
        if (recording.get()) stopScreenRecording(finishAfter = false) else startScreenRecording()
    }

    private fun startScreenRecording() {
        if (savingScreenshot.get() || captureRequested.get()) {
            Toast.makeText(this, "Aguarde o print terminar.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!recordingTransition.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(expireSession)
        setOverlayButtonsEnabled(false)
        setOverlayVisible(false)
        runCatching {
            val profile = chooseRecordingProfile()
            val files = VaultRepository.createScreenRecordingFiles(this, destinationDirectory())
            val recorder = createMediaRecorder(files, profile)
            recordingFiles = files
            mediaRecorder = recorder
            switchVirtualSurface(recorder.surface, profile.width, profile.height)
            recorder.start()
            recording.set(true)
        }.onSuccess {
            recordingTransition.set(false)
            setOverlayVisible(true)
            updateOverlayForRecording(true)
            setOverlayButtonsEnabled(true)
            updateNotification("Gravando tela • destino: ${destinationLabel()}")
            Haptics.start(this)
            Toast.makeText(this, "Gravação iniciada. Toque no quadrado vermelho para salvar.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            recording.set(false)
            recordingTransition.set(false)
            runCatching { virtualDisplay?.surface = null }
            releaseRecorder()
            VaultRepository.discardPrivateCapture(recordingFiles)
            recordingFiles = null
            setOverlayVisible(true)
            updateOverlayForRecording(false)
            setOverlayButtonsEnabled(true)
            Haptics.error(this)
            Toast.makeText(this, it.message ?: "Não foi possível iniciar a gravação.", Toast.LENGTH_LONG).show()
            scheduleIdleTimeout()
        }
    }

    private fun createMediaRecorder(
        files: VaultRepository.PrivateCaptureFiles,
        profile: RecordingProfile
    ): MediaRecorder {
        val recorder = createMediaRecorderForCurrentApi()
        return recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(profile.width, profile.height)
            setVideoFrameRate(profile.fps)
            setVideoEncodingBitRate(profile.bitrate)
            setOutputFile(files.working.absolutePath)
            prepare()
        }
    }

    private fun createMediaRecorderForCurrentApi(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder::class.java.getDeclaredConstructor().newInstance()
        }

    private fun stopScreenRecording(finishAfter: Boolean) {
        if (finishAfter) finishWhenReady.set(true)
        if (!recording.get() || !recordingTransition.compareAndSet(false, true)) return
        recording.set(false)
        setOverlayButtonsEnabled(false)
        updateNotification("Finalizando vídeo em ${destinationLabel()}…")
        val recorder = mediaRecorder
        mediaRecorder = null
        val files = recordingFiles
        recordingFiles = null
        runCatching {
            fileExecutor.execute {
                var finalFile: File? = null
                val result = finalizeScreenRecording(recorder, files)
                    .onSuccess { saved ->
                        finalFile = saved
                    }
                    .onSuccess {
                    check(VaultRepository.readItem(finalFile!!, fast = false) != null) {
                        "O vídeo de tela gerado ficou inválido"
                    }
                }
                if (result.isFailure) {
                    finalFile?.delete()
                    if (
                        files == null ||
                        !RecordingRecoveryRepository.hasUsableVideo(files!!.working)
                    ) {
                        VaultRepository.discardPrivateCapture(files)
                    }
                }
                mainHandler.post { finishRecording(result.exceptionOrNull()?.message, result.isSuccess) }
            }
        }.onFailure {
            runCatching { recorder?.release() }
            val activeFiles = files
            val recovered = activeFiles != null &&
                RecordingRecoveryRepository.hasUsableVideo(activeFiles.working) &&
                runCatching {
                    VaultRepository.commitPrivateCapture(activeFiles)
                }.isSuccess
            if (!recovered) {
                VaultRepository.discardPrivateCapture(files)
            }
            finishRecording(it.message, success = recovered)
        }
    }

    private fun finalizeScreenRecording(
        recorder: MediaRecorder?,
        files: VaultRepository.PrivateCaptureFiles?
    ): Result<File> = runCatching {
        val activeRecorder = recorder
            ?: throw IllegalStateException("Gravador indisponível")
        val activeFiles = files
            ?: throw IllegalStateException("Arquivo temporário indisponível")
        val stopFailure = runCatching { activeRecorder.stop() }.exceptionOrNull()
        runCatching { activeRecorder.release() }
        if (
            stopFailure != null &&
            !RecordingRecoveryRepository.hasUsableVideo(activeFiles.working)
        ) {
            throw stopFailure
        }
        VaultRepository.commitPrivateCapture(activeFiles)
    }

    private fun finishRecording(message: String?, success: Boolean) {
        runCatching { virtualDisplay?.surface = null }
        recordingTransition.set(false)
        updateOverlayForRecording(false)
        setOverlayButtonsEnabled(true)
        if (success) {
            Haptics.stop(this)
            Toast.makeText(this, "Vídeo salvo somente em ${destinationLabel()}.", Toast.LENGTH_LONG).show()
        } else {
            Haptics.error(this)
            Toast.makeText(
                this,
                message ?: "Não foi possível finalizar o vídeo. Grave por pelo menos um segundo.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (finishWhenReady.compareAndSet(true, false)) {
            finishSession(null)
        } else {
            updateNotification("Controle flutuante pronto • ${destinationLabel()}")
            scheduleIdleTimeout()
        }
    }

    private fun chooseRecordingProfile(): RecordingProfile {
        val (screenWidth, screenHeight) = captureSize()
        val fullWidth = even(screenWidth)
        val fullHeight = even(screenHeight)
        val limited = if (fullWidth >= fullHeight) {
            fitWithin(fullWidth, fullHeight, 1920, 1080)
        } else {
            fitWithin(fullWidth, fullHeight, 1080, 1920)
        }
        val candidates = listOf(
            fullWidth to fullHeight to 60,
            fullWidth to fullHeight to 30,
            limited.first to limited.second to 60,
            limited.first to limited.second to 30
        ).map { nested ->
            val size = nested.first
            val fps = nested.second
            val pixelsPerSecond = size.first.toLong() * size.second.toLong() * fps.toLong()
            RecordingProfile(
                width = size.first,
                height = size.second,
                fps = fps,
                bitrate = (pixelsPerSecond * 0.07).toLong().coerceIn(8_000_000L, 48_000_000L).toInt()
            )
        }.distinctBy { Triple(it.width, it.height, it.fps) }

        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return candidates.firstOrNull { profile ->
            encoders.any { info ->
                if (!info.isEncoder || info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) {
                    return@any false
                }
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?.areSizeAndRateSupported(profile.width, profile.height, profile.fps.toDouble()) == true
                }.getOrDefault(false)
            }
        } ?: candidates.last()
    }

    private fun switchVirtualSurface(surface: Surface, width: Int, height: Int) {
        val activeProjection = projection ?: throw IllegalStateException("Compartilhamento de tela encerrado")
        val density = resources.configuration.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_LOW)
        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = activeProjection.createVirtualDisplay(
                "SteadyVaultScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                captureHandler
            )
        } else {
            display.resize(width, height, density)
            display.surface = surface
        }
    }

    private fun cleanupScreenshotSurface() {
        if (!recording.get()) runCatching { virtualDisplay?.surface = null }
        cleanupImageReader()
    }

    private fun cleanupImageReader() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
    }

    private fun releaseRecorder() {
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
    }

    private fun updateOverlayForRecording(active: Boolean) {
        cameraButton?.isEnabled = !active
        cameraButton?.alpha = if (active) 0.35f else 1f
        recordButton?.apply {
            setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_record)
            imageTintList = ColorStateList.valueOf(getColor(R.color.record_red))
            contentDescription = if (active) "Parar e salvar gravação da tela" else "Iniciar gravação da tela"
        }
    }

    private fun setOverlayButtonsEnabled(enabled: Boolean) {
        cameraButton?.isEnabled = enabled && !recording.get()
        recordButton?.isEnabled = enabled
        overlayView?.alpha = if (enabled) 1f else 0.62f
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun requestCloseSession() {
        when {
            recording.get() -> stopScreenRecording(finishAfter = true)
            recordingTransition.get() || captureRequested.get() || savingScreenshot.get() -> finishWhenReady.set(true)
            else -> finishSession(null)
        }
    }

    private fun finishSession(message: String?) {
        if (!finishing.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        captureRequested.set(false)
        cleanupScreenshotSurface()
        releaseRecorder()
        VaultRepository.discardPrivateCapture(recordingFiles)
        recordingFiles = null
        removeOverlay()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.unregisterCallback(projectionCallback) }
        runCatching { activeProjection?.stop() }
        lockDestination()
        if (!message.isNullOrBlank()) {
            Haptics.error(this)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        overlayView = null
        cameraButton = null
        recordButton = null
    }

    private fun destinationDirectory(): File = when (destination) {
        DESTINATION_SECONDARY -> SecondaryVaultRepository.directory(this)
        DESTINATION_TERTIARY -> TertiaryVaultRepository.directory(this)
        else -> VaultRepository.primaryDirectory(this)
    }

    private fun destinationLabel(): String = when (destination) {
        DESTINATION_SECONDARY -> "Cofre secundário"
        DESTINATION_TERTIARY -> "Cofre terciário"
        else -> "Cofre principal"
    }

    private fun lockDestination(value: String = destination) {
        when (value) {
            DESTINATION_SECONDARY -> SecondaryVaultLock.lock()
            DESTINATION_TERTIARY -> TertiaryVaultLock.lock()
            else -> PrimaryVaultLock.lock()
        }
    }

    private fun scheduleIdleTimeout() {
        mainHandler.removeCallbacks(expireSession)
        mainHandler.postDelayed(expireSession, SESSION_TIMEOUT_MS)
    }

    private fun captureSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= maxWidth && height <= maxHeight) return even(width) to even(height)
        val scale = minOf(maxWidth.toDouble() / width.toDouble(), maxHeight.toDouble() / height.toDouble())
        return even((width * scale).toInt()) to even((height * scale).toInt())
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2) and 1.inv()

    private fun projectionData(intent: Intent): Intent? =
        IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ProtectedAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, VaultScreenCaptureService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_USER_REQUESTED_CLOSE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val identity = VisualIdentityStore.notificationIdentity(this)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Captura de tela"))
            .setContentText(VisualIdentityStore.notificationText(this, text))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, identity.cancelIcon),
                    VisualIdentityStore.actionLabel(this, "Encerrar", "Concluir"),
                    cancel
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Captura privada de tela", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sessão silenciosa para prints e vídeos gravados diretamente nos cofres"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        activeSession.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        captureRequested.set(false)
        cleanupImageReader()
        if (recording.getAndSet(false)) {
            val recorder = mediaRecorder
            mediaRecorder = null
            val files = recordingFiles
            recordingFiles = null
            val saved = finalizeScreenRecording(recorder, files).isSuccess
            if (
                !saved &&
                (
                    files == null ||
                        !RecordingRecoveryRepository.hasUsableVideo(files.working)
                    )
            ) {
                VaultRepository.discardPrivateCapture(files)
            }
        } else {
            releaseRecorder()
            val files = recordingFiles
            if (
                files == null ||
                !RecordingRecoveryRepository.hasUsableVideo(files.working)
            ) {
                VaultRepository.discardPrivateCapture(files)
            }
        }
        recordingFiles = null
        removeOverlay()
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        virtualDisplay = null
        projection = null
        lockDestination()
        fileExecutor.shutdownNow()
        captureThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val DESTINATION_PRIMARY = VaultAreaId.PRIMARY
        const val DESTINATION_SECONDARY = VaultAreaId.SECONDARY
        const val DESTINATION_TERTIARY = VaultAreaId.TERTIARY
        private val SUPPORTED_DESTINATIONS = setOf(DESTINATION_PRIMARY, DESTINATION_SECONDARY, DESTINATION_TERTIARY)
        private const val CHANNEL_ID = "vault_screen_capture_v2"
        private const val NOTIFICATION_ID = 2207
        private const val ACTION_START = "com.steadyvault.camera.action.START_VAULT_SCREEN_CAPTURE"
        private const val ACTION_CANCEL = "com.steadyvault.camera.action.CANCEL_VAULT_SCREEN_CAPTURE"
        private const val EXTRA_USER_REQUESTED_CLOSE = "user_requested_close"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_DESTINATION = "destination"
        private const val OVERLAY_HIDE_DELAY_MS = 90L
        private const val FRAME_TIMEOUT_MS = 4_000L
        private const val SESSION_TIMEOUT_MS = 30L * 60L * 1000L

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            destination: String
        ): Intent = Intent(context, VaultScreenCaptureService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_DESTINATION, destination)

        fun yieldToCameraCapture(context: Context) {
            if (!activeSession.get()) return
            runCatching {
                context.startService(
                    Intent(context, VaultScreenCaptureService::class.java)
                        .setAction(ACTION_CANCEL)
                        .putExtra(EXTRA_USER_REQUESTED_CLOSE, true)
                )
            }
        }

        private val activeSession = AtomicBoolean(false)
    }
}
