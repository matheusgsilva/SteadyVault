package com.steadyvault.camera.ui.vault

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.playback.PlaybackSettings
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.state.OptimizationStateStore
import com.steadyvault.camera.processing.service.VideoOptimizationService
import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import com.steadyvault.camera.processing.model.OptimizationRateMode
import com.steadyvault.camera.processing.model.OutputCodec
import com.steadyvault.camera.processing.model.VideoFilterConfig
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.storage.vault.VideoFilmstripCache
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.gesture.ZoomableImageView
import com.steadyvault.camera.ui.gesture.ZoomableVideoView
import com.steadyvault.camera.ui.gesture.PrecisionSeekBar
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@UnstableApi
class MediaPlayerActivity : ComponentActivity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val filmstripExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vault-trim-filmstrip").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var item: VaultRepository.MediaItem? = null
    private var playbackLocked = false
    private var waitingForProcessing = false
    private var deletePending = false
    private var destroyed = false
    private var userSeeking = false
    private var resumeAfterUserSeek = false
    private var seekCommitPending = false
    private var scrubOriginalSpeed = 1f
    private var scrubTargetMs = 0
    private var scrubCommitGeneration = 0
    private var viewerControlsVisible = false
    private var dismissInProgress = false
    private var videoDurationMs = 0
    private var speedIndex = 2
    private var scrubPrecisionIndex = 0
    private var initialPlaybackSpeedApplied = false
    private var userSelectedPlaybackSpeed = false
    private var imageLoadGeneration = 0L
    private var secondaryMode = false
    private var tertiaryMode = false
    private var startTrimOnReady = false
    private val alternateVaultMode: Boolean get() = secondaryMode || tertiaryMode
    private var resumePlaybackOnResume = false
    private var videoSuspended = false
    private var originalPreferredDisplayModeId = 0
    private var originalPreferredRefreshRate = 0f
    private var refreshPreferenceCaptured = false
    private var trimMode = false
    private var trimActiveHandle: VideoTrimRangeView.Handle? = null
    private var trimStartMs = 0
    private var trimEndMs = 0
    private var trimFilmstripGeneration = 0L
    private var trimFilmstripLoading = false
    private var trimOpenPending = false
    private var actionButtonsEnabled = false
    private var preparedTrimFilmstrip: List<Bitmap> = emptyList()
    private var playbackCompleted = false
    private var pendingFileExport: File? = null
    private val saveMediaToFiles = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = pendingFileExport
        pendingFileExport = null
        if (uri == null || source == null) return@registerForActivityResult
        if (::exportButton.isInitialized) exportButton.text = "Salvando…"
        if (::exportButton.isInitialized) setActionButtonsEnabled(false)
        ioExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Não foi possível abrir o destino escolhido")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                exportButton.text = "Exportar"
                setActionButtonsEnabled(true)
                result.onSuccess {
                    Haptics.success(this)
                    Toast.makeText(this, "Cópia salva nos Arquivos do celular", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Falha ao salvar nos Arquivos", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private lateinit var videoView: ZoomableVideoView
    private lateinit var imageView: ZoomableImageView
    private lateinit var zoomIndicator: TextView
    private lateinit var zoomHelp: TextView
    private lateinit var details: TextView
    private lateinit var exportButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var optimizeButton: TextView
    private lateinit var trimMediaButton: TextView
    private lateinit var viewerHeader: View
    private lateinit var viewerActions: View
    private lateinit var viewerToolBar: View
    private lateinit var videoControls: View
    private lateinit var videoCenterControls: View
    private lateinit var playbackSeekRow: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var speedButton: TextView
    private lateinit var scrubPrecisionButton: TextView
    private lateinit var fitModeButton: TextView
    private lateinit var seekBar: PrecisionSeekBar
    private lateinit var scrubPrecisionLabel: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var trimControls: View
    private lateinit var trimModeLabel: TextView
    private lateinit var trimRangeLabel: TextView
    private lateinit var trimRangeView: VideoTrimRangeView
    private lateinit var trimRemoveAudioSwitch: Switch
    private lateinit var trimSaveButton: TextView
    private lateinit var trimCancelButton: TextView

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (destroyed || !::videoView.isInitialized) return
            if (!userSeeking && !seekCommitPending && videoView.isPrepared()) {
                val position = videoView.currentPosition.coerceAtLeast(0)
                val duration = videoView.duration.coerceAtLeast(videoDurationMs)
                videoDurationMs = duration
                seekBar.max = duration.coerceAtLeast(1)
                seekBar.progress = position.coerceAtMost(seekBar.max)
                updateVideoTime(position, duration)
                if (trimMode && ::trimRangeView.isInitialized && duration > 0) {
                    val end = trimEndMs.coerceAtLeast(trimStartMs + 1)
                    if (videoView.isPlaying() && position >= end) {
                        videoView.seekTo(trimStartMs)
                        seekBar.progress = trimStartMs
                        trimRangeView.setPlayhead(trimStartMs.toFloat() / duration)
                    } else {
                        trimRangeView.setPlayhead(position.coerceIn(trimStartMs, end).toFloat() / duration)
                    }
                }
            }
            mainHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    private val hideScrubPrecisionRunnable = Runnable {
        if (!userSeeking && ::scrubPrecisionLabel.isInitialized) scrubPrecisionLabel.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDisplayRefreshPreference()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (trimMode) exitTrimMode() else finishViewer()
            }
        })
        secondaryMode = intent.getBooleanExtra(EXTRA_SECONDARY, false)
        tertiaryMode = intent.getBooleanExtra(EXTRA_TERTIARY, false)
        startTrimOnReady = intent.getBooleanExtra(EXTRA_START_TRIM, false)
        val accessGranted = when {
            tertiaryMode -> TertiaryVaultLock.isUnlocked(this)
            secondaryMode -> SecondaryVaultLock.isUnlocked(this)
            else -> PrimaryVaultLock.isUnlocked(this)
        }
        if (!accessGranted) {
            finish()
            return
        }
        if (!alternateVaultMode && PrimaryVaultLock.isEnabled(this) && CaptureSettings.snapshot(this).secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_media_player)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        item = when {
            tertiaryMode -> TertiaryVaultRepository.findByPath(this, path)
            secondaryMode -> SecondaryVaultRepository.findByPath(this, path)
            else -> VaultRepository.findByPath(this, path)
        }
        val media = item
        if (media == null) {
            Toast.makeText(this, "Mídia não encontrada", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (shouldOpenWithSystemViewer(media) && openWithSystemViewer(media)) {
            finish()
            return
        }

        bindViews()
        SystemBarInsets.applyTop(viewerHeader)
        SystemBarInsets.applyBottom(viewerActions)
        bindHeader(requireNotNull(item))
        bindActions()
        bindViewerTools()
        setSystemBarsVisible(false)

        if (media.video) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            configureVideoView()
            updateViewerToolBarPosition(video = true)
            prepareVideoPlayback(media)
        } else {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            videoControls.visibility = View.GONE
            optimizeButton.visibility = View.GONE
            updateViewerToolBarPosition(video = false)
            loadImageSafely(media.file)
        }
        applyViewerControlsVisibility(animated = false)

        if (media.video) mainHandler.post(progressRunnable)
    }


    override fun onResume() {
        super.onResume()
        val accessGranted = when {
            tertiaryMode -> TertiaryVaultLock.isUnlocked(this)
            secondaryMode -> SecondaryVaultLock.isUnlocked(this)
            else -> PrimaryVaultLock.isUnlocked(this)
        }
        if (!accessGranted) {
            finish()
            return
        }
        if (videoSuspended && ::videoView.isInitialized && item?.video == true) {
            val shouldResume = resumePlaybackOnResume
            videoSuspended = false
            resumePlaybackOnResume = false
            videoView.resumeFromHost(shouldResume)
        }
    }

    override fun onPause() {
        if (::videoView.isInitialized && item?.video == true) {
            resumePlaybackOnResume = videoView.suspendForHost()
            videoSuspended = true
        }
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        imageLoadGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        restoreDisplayRefreshPreference()
        stopVideoPlayback()
        if (::trimRangeView.isInitialized) trimRangeView.clearFrames()
        filmstripExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun shouldOpenWithSystemViewer(media: VaultRepository.MediaItem): Boolean {
        val playback = PlaybackSettings.snapshot(this)
        return if (media.video) playback.openVideosExternally else playback.openPhotosExternally
    }

    private fun externalMimeFor(media: VaultRepository.MediaItem): String {
        val extension = media.file.extension.lowercase(Locale.US)
        return VaultMediaFormats.mimeFor(extension, media.video).takeUnless { it == "image/*" || it == "video/*" }
            ?: media.mime.ifBlank { if (media.video) "video/*" else "image/*" }
    }

    private fun openWithSystemViewer(media: VaultRepository.MediaItem): Boolean {
        val mime = externalMimeFor(media)
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", media.file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivity(viewIntent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app externo encontrado para abrir esta mídia", Toast.LENGTH_LONG).show()
            false
        } catch (throwable: Throwable) {
            Toast.makeText(this, throwable.message ?: "Não foi possível abrir no app do celular", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun bindViews() {
        videoView = findViewById(R.id.videoView)
        imageView = findViewById(R.id.imageView)
        zoomIndicator = findViewById(R.id.zoomIndicator)
        zoomHelp = findViewById(R.id.zoomHelp)
        details = findViewById(R.id.viewerDetails)
        exportButton = findViewById(R.id.exportMediaButton)
        deleteButton = findViewById(R.id.deleteMediaButton)
        optimizeButton = findViewById(R.id.optimizeMediaButton)
        trimMediaButton = findViewById(R.id.trimMediaButton)
        viewerHeader = findViewById(R.id.viewerHeader)
        viewerActions = findViewById(R.id.viewerActions)
        viewerToolBar = findViewById(R.id.viewerToolBar)
        videoControls = findViewById(R.id.videoControls)
        videoCenterControls = findViewById(R.id.videoCenterControls)
        playbackSeekRow = findViewById(R.id.playbackSeekRow)
        playPauseButton = findViewById(R.id.playPauseButton)
        speedButton = findViewById(R.id.speedButton)
        scrubPrecisionButton = findViewById(R.id.scrubPrecisionButton)
        fitModeButton = findViewById(R.id.fitModeButton)
        seekBar = findViewById(R.id.videoSeekBar)
        scrubPrecisionLabel = findViewById(R.id.scrubPrecisionLabel)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        trimControls = findViewById(R.id.trimControls)
        trimModeLabel = findViewById(R.id.trimModeLabel)
        trimRangeLabel = findViewById(R.id.trimRangeLabel)
        trimRangeView = findViewById(R.id.trimRangeView)
        trimRemoveAudioSwitch = findViewById(R.id.trimRemoveAudioSwitch)
        trimSaveButton = findViewById(R.id.trimSaveButton)
        trimCancelButton = findViewById(R.id.trimCancelButton)
        applyScrubPrecision(showStatus = false)
        viewerActions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateViewerToolBarPosition(item?.video == true)
        }
        videoControls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateViewerToolBarPosition(item?.video == true)
        }
    }

    private fun bindHeader(media: VaultRepository.MediaItem) {
        findViewById<TextView>(R.id.viewerTitle).text = media.name
        updateDetails(media)
        findViewById<ImageButton>(R.id.viewerBackButton).setOnClickListener { finishViewer() }
    }

    private fun updateDetails(media: VaultRepository.MediaItem) {
        details.text = if (media.video) {
            val dimension = if (media.width > 0 && media.height > 0) "${media.width}×${media.height} • " else ""
            val duration = if (media.durationMs > 0L) "${VaultRepository.formatDuration(media.durationMs)} • " else ""
            "$dimension$duration${VaultRepository.formatBytes(media.sizeBytes)}"
        } else {
            val dimension = if (media.width > 0 && media.height > 0) "${media.width}×${media.height} • " else ""
            "${dimension}Imagem • ${VaultRepository.formatBytes(media.sizeBytes)}"
        }
    }

    private fun bindActions() {
        exportButton.setOnClickListener { export() }
        deleteButton.setOnClickListener { confirmDelete() }
        optimizeButton.apply {
            visibility = if (item?.video == true) View.VISIBLE else View.GONE
            setOnClickListener {
                val media = item ?: return@setOnClickListener
                stopVideoPlayback()
                startActivity(
                    Intent(this@MediaPlayerActivity, VideoOptimizationActivity::class.java)
                        .putExtra(VideoOptimizationActivity.EXTRA_PATH, media.file.absolutePath)
                        .putExtra(VideoOptimizationActivity.EXTRA_SECONDARY, secondaryMode)
                        .putExtra(VideoOptimizationActivity.EXTRA_TERTIARY, tertiaryMode)
                )
                finish()
            }
        }
        trimMediaButton.apply {
            visibility = if (item?.video == true) View.VISIBLE else View.GONE
            setOnClickListener { enterTrimMode() }
        }
    }

    private fun bindViewerTools() {
        findViewById<TextView>(R.id.zoomOutButton).setOnClickListener {
            if (item?.video == true) videoView.zoomBy(-0.75f) else imageView.zoomBy(-0.75f)
            showZoomHelp("Zoom reduzido")
        }
        findViewById<TextView>(R.id.zoomInButton).setOnClickListener {
            if (item?.video == true) videoView.zoomBy(0.75f) else imageView.zoomBy(0.75f)
            showZoomHelp("Zoom ampliado")
        }
        zoomIndicator.setOnClickListener {
            if (item?.video == true) videoView.resetZoom() else imageView.resetZoom()
            showZoomHelp("Zoom redefinido para 1×")
        }
        fitModeButton.setOnClickListener { toggleFitMode() }

        val zoomChanged: (Float) -> Unit = { scale ->
            zoomIndicator.text = String.format(Locale.US, "%.1f×", scale)
        }
        videoView.setOnZoomChangedListener(zoomChanged)
        imageView.setOnZoomChangedListener(zoomChanged)
        imageView.setOnClickListener { toggleViewerControls() }
        imageView.setOnDismissListener { dismissToVault() }
        videoView.setOnDismissListener { dismissToVault() }

        playPauseButton.setOnClickListener {
            hideZoomHelpImmediately()
            if (playbackCompleted && !trimMode) {
                replayCurrentVideo()
                return@setOnClickListener
            }
            if (trimMode) {
                val current = videoView.currentPosition
                if (current < trimStartMs || current >= trimEndMs) {
                    videoView.seekTo(trimStartMs)
                    trimRangeView.setPlayhead(trimStartMs.toFloat() / currentVideoDuration())
                }
            }
            videoView.togglePlayback()
        }
        findViewById<TextView>(R.id.rewindButton).setOnClickListener { seekRelative(-SEEK_STEP_MS) }
        findViewById<TextView>(R.id.forwardButton).setOnClickListener { seekRelative(SEEK_STEP_MS) }
        speedButton.setOnClickListener { cyclePlaybackSpeed() }
        scrubPrecisionButton.setOnClickListener { cycleScrubPrecision() }
        trimRemoveAudioSwitch.setOnCheckedChangeListener { _, removeAudio ->
            trimSaveButton.text = if (removeAudio) "Salvar sem áudio" else "Salvar cópia"
        }
        trimSaveButton.setOnClickListener { confirmTrimFromPlayer() }
        trimCancelButton.setOnClickListener { exitTrimMode() }
        trimRangeView.listener = object : VideoTrimRangeView.Listener {
            override fun onTrackingStarted(handle: VideoTrimRangeView.Handle, fraction: Float) {
                trimActiveHandle = handle
                beginUserScrub(trimFractionToMs(fraction))
                showScrubPrecision(currentScrubPrecision())
                updateTrimLabels()
            }

            override fun onRangeChanged(
                startFraction: Float,
                endFraction: Float,
                activeHandle: VideoTrimRangeView.Handle
            ) {
                val duration = currentVideoDuration()
                trimStartMs = (duration * startFraction).roundToInt().coerceIn(0, duration)
                trimEndMs = (duration * endFraction).roundToInt().coerceIn(trimStartMs, duration)
                trimActiveHandle = activeHandle
                previewUserScrub(if (activeHandle == VideoTrimRangeView.Handle.START) trimStartMs else trimEndMs)
                updateTrimLabels()
            }

            override fun onPlayheadChanged(fraction: Float) {
                previewUserScrub(trimFractionToMs(fraction))
            }

            override fun onTrackingStopped(handle: VideoTrimRangeView.Handle, fraction: Float) {
                scrubPrecisionLabel.visibility = View.GONE
                finishUserScrub(trimFractionToMs(fraction))
                trimActiveHandle = null
                updateTrimLabels()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = videoView.snapPreviewPosition(
                    progress.coerceIn(0, seekBar.max.coerceAtLeast(0))
                )
                scrubTargetMs = target
                updateVideoTime(target, videoDurationMs.coerceAtLeast(videoView.duration))
                if (seekBar.isDragGesture()) {
                    if (!userSeeking) {
                        beginUserScrub(target)
                        showScrubPrecision(currentScrubPrecision())
                    } else {
                        requestScrubPreviewFrame(target)
                    }
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                if (!videoView.isPrepared()) return
                scrubTargetMs = (bar?.progress ?: videoView.currentPosition)
                    .coerceIn(0, videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(0))
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val target = (bar?.progress ?: scrubTargetMs)
                    .coerceIn(0, videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(0))
                scrubPrecisionLabel.visibility = View.GONE
                if (userSeeking) finishUserScrub(target) else seekTimelineTap(target)
            }
        })
    }

    private fun showScrubPrecision(precision: Float) {
        scrubPrecisionLabel.text = when (precision) {
            0.5f -> "Precisão do arraste • ½×"
            0.25f -> "Precisão do arraste • ¼×"
            0.125f -> "Precisão do arraste • ⅛×"
            else -> "Precisão do arraste • 1×"
        }
        scrubPrecisionLabel.removeCallbacks(hideScrubPrecisionRunnable)
        scrubPrecisionLabel.visibility = View.VISIBLE
        if (!userSeeking) scrubPrecisionLabel.postDelayed(hideScrubPrecisionRunnable, PRECISION_STATUS_MS)
    }

    private fun cycleScrubPrecision() {
        scrubPrecisionIndex = (scrubPrecisionIndex + 1) % SCRUB_PRECISIONS.size
        Haptics.tap(this)
        applyScrubPrecision(showStatus = true)
    }

    private fun applyScrubPrecision(showStatus: Boolean) {
        val precision = currentScrubPrecision()
        if (::seekBar.isInitialized) seekBar.setScrubPrecision(precision)
        if (::trimRangeView.isInitialized) trimRangeView.setScrubPrecision(precision)
        if (::scrubPrecisionButton.isInitialized) {
            val value = when (precision) {
                0.5f -> "½×"
                0.25f -> "¼×"
                0.125f -> "⅛×"
                else -> "1×"
            }
            val description = when (precision) {
                0.5f -> "Arraste ½×: duas vezes mais preciso"
                0.25f -> "Arraste ¼×: quatro vezes mais preciso"
                0.125f -> "Arraste ⅛×: oito vezes mais preciso"
                else -> "Arraste 1×: movimento normal da barra"
            }
            scrubPrecisionButton.text = "Arraste $value"
            scrubPrecisionButton.contentDescription = description
            scrubPrecisionButton.tooltipText = description
        }
        if (showStatus) showScrubPrecision(precision)
    }

    private fun currentScrubPrecision(): Float = SCRUB_PRECISIONS[scrubPrecisionIndex]

    private fun beginScrubPreview(targetMs: Int) {
        if (videoView.isPlaybackRequested()) videoView.pause()
        videoView.beginPreviewSeek()
        playPauseButton.setImageResource(R.drawable.ic_play)
        requestScrubPreviewFrame(targetMs)
    }

    private fun endScrubPreview(finalTargetMs: Int? = null, resumePlayback: Boolean = false) {
        videoView.endPreviewSeek(finalTargetMs, resumePlayback, precise = trimMode)
    }

    private fun requestScrubPreviewFrame(targetMs: Int) {
        if (!userSeeking || destroyed || !::videoView.isInitialized) return
        videoView.previewSeekTo(videoView.snapPreviewPosition(targetMs))
    }

    private fun beginUserScrub(targetMs: Int) {
        if (!videoView.isPrepared()) return
        val target = videoView.snapPreviewPosition(targetMs)
        userSeeking = true
        seekCommitPending = false
        resumeAfterUserSeek = videoView.isPlaybackRequested() && !trimMode
        scrubOriginalSpeed = PLAYBACK_SPEEDS.getOrElse(speedIndex) { 1f }
        scrubCommitGeneration++
        beginScrubPreview(target)
        previewUserScrub(target)
    }

    private fun previewUserScrub(targetMs: Int) {
        if (!userSeeking) return
        val duration = currentVideoDuration()
        val target = videoView.snapPreviewPosition(targetMs).coerceIn(0, duration)
        scrubTargetMs = target
        requestScrubPreviewFrame(target)
        seekBar.progress = target.coerceAtMost(seekBar.max)
        updateVideoTime(target, duration)
    }

    private fun finishUserScrub(targetMs: Int) {
        if (!userSeeking) return
        val target = videoView.snapPreviewPosition(targetMs)
        endScrubPreview(target, resumePlayback = resumeAfterUserSeek && !trimMode)
        commitSeekBarTarget(target)
    }

    private fun commitSeekBarTarget(targetMs: Int) {
        if (!::videoView.isInitialized || !videoView.isPrepared()) {
            userSeeking = false
            seekCommitPending = false
            resumeAfterUserSeek = false
            return
        }
        val duration = videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(seekBar.max).coerceAtLeast(0)
        val target = videoView.snapPreviewPosition(targetMs).coerceIn(0, duration)
        val shouldResume = resumeAfterUserSeek
        val generation = ++scrubCommitGeneration
        seekCommitPending = true
        videoView.setPlaybackSpeed(scrubOriginalSpeed)
        updateSpeedButton(scrubOriginalSpeed)
        seekBar.progress = target.coerceAtMost(seekBar.max)
        updateVideoTime(target, duration)
        if (shouldResume) videoView.start() else videoView.pause()
        playPauseButton.setImageResource(if (shouldResume) R.drawable.ic_pause else R.drawable.ic_play)
        mainHandler.postDelayed({
            if (destroyed || generation != scrubCommitGeneration || !::videoView.isInitialized) return@postDelayed
            seekBar.progress = target.coerceAtMost(seekBar.max)
            updateVideoTime(target, duration)
            userSeeking = false
            seekCommitPending = false
            resumeAfterUserSeek = false
        }, SCRUB_SETTLE_MS)
    }


    private fun seekTimelineTap(targetMs: Int) {
        if (!::videoView.isInitialized || !videoView.isPrepared()) return
        val duration = currentVideoDuration()
        val target = videoView.snapPreviewPosition(targetMs).coerceIn(0, duration)
        val shouldResume = videoView.isPlaybackRequested() && !trimMode
        scrubCommitGeneration++
        seekCommitPending = true
        playbackCompleted = false
        videoView.seekFromTimeline(target, shouldResume, precise = trimMode)
        seekBar.progress = target.coerceAtMost(seekBar.max)
        updateVideoTime(target, duration)
        playPauseButton.setImageResource(if (shouldResume) R.drawable.ic_pause else R.drawable.ic_play)
        val generation = scrubCommitGeneration
        mainHandler.postDelayed({
            if (destroyed || generation != scrubCommitGeneration || !::videoView.isInitialized) return@postDelayed
            seekCommitPending = false
            seekBar.progress = videoView.currentPosition.coerceIn(0, seekBar.max.coerceAtLeast(0))
            updateVideoTime(videoView.currentPosition.coerceAtLeast(0), duration)
        }, DIRECT_SEEK_SETTLE_MS)
    }

    private fun configureVideoView() {
        videoView.setOnPreparedListener { duration ->
            playbackCompleted = false
            videoDurationMs = duration
            seekBar.max = duration.coerceAtLeast(1)
            updateVideoTime(videoView.currentPosition.coerceAtLeast(0), duration)
            setActionButtonsEnabled(true)
            if (startTrimOnReady && item?.video == true) {
                startTrimOnReady = false
                mainHandler.post { if (!destroyed && videoView.isPrepared()) enterTrimMode() }
            }
        }
        videoView.setOnPlaybackChangedListener { playing ->
            if (playing) playbackCompleted = false
            updatePlayPauseButton(playing)
        }
        videoView.setOnPlaybackAnalysisListener { profile ->
            if (!userSelectedPlaybackSpeed && profile.fps > 0f) {
                applyAutomaticPlaybackSpeed(profile.fps)
            } else if (profile.fps > 0f) {
                applyPreferredPlaybackRefreshRate(profile.fps, PLAYBACK_SPEEDS[speedIndex])
            }
        }
        videoView.setOnPlaybackEngineChangedListener { /* recuperação silenciosa */ }
        videoView.setOnCompletionListener {
            playbackCompleted = true
            val duration = videoDurationMs.coerceAtLeast(videoView.duration)
            seekBar.progress = duration.coerceAtMost(seekBar.max)
            updateVideoTime(duration, duration)
            updatePlayPauseButton(playing = false)
            hideZoomHelpImmediately()
            viewerControlsVisible = true
            applyViewerControlsVisibility(animated = true)
        }
        videoView.setOnSingleTapListener { toggleViewerControls() }
        videoView.setOnErrorListener {
            playbackCompleted = false
            hideZoomHelpImmediately()
            updatePlayPauseButton(playing = false)
            setActionButtonsEnabled(true)
            Toast.makeText(this, "Não foi possível reproduzir este vídeo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePlayPauseButton(playing: Boolean = videoView.isPlaying()) {
        val icon = when {
            playbackCompleted -> R.drawable.ic_refresh
            playing -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        }
        playPauseButton.setImageResource(icon)
        playPauseButton.contentDescription = when {
            playbackCompleted -> "Reproduzir novamente"
            playing -> "Pausar"
            else -> "Reproduzir"
        }
    }

    private fun replayCurrentVideo() {
        if (destroyed || !::videoView.isInitialized) return
        playbackCompleted = false
        seekBar.progress = 0
        updateVideoTime(0, videoDurationMs.coerceAtLeast(videoView.duration))
        videoView.prepareReplay()
        mainHandler.postDelayed({
            if (!destroyed && !isFinishing && !isDestroyed) videoView.start()
        }, 40L)
        updatePlayPauseButton(playing = true)
    }

    private fun prepareVideoPlayback(media: VaultRepository.MediaItem) {
        if (alternateVaultMode) {
            startVideoPlayback(media)
            return
        }
        OptimizationStateStore.recoverStale(this)
        VaultRepository.releaseStaleProcessingLocks()
        if (isProcessing(media.file)) {
            waitingForProcessing = true
            showZoomHelp("Reproduzindo o original enquanto a correção termina em segundo plano…")
        }
        startVideoPlayback(media)
    }

    private fun startVideoPlayback(media: VaultRepository.MediaItem) {
        if (destroyed || !media.file.isFile) return
        if (!alternateVaultMode && !playbackLocked) {
            var acquired = VaultRepository.acquireForPlayback(media.file)
            if (!acquired) {
                VaultRepository.releaseStaleProcessingLocks()
                acquired = VaultRepository.acquireForPlayback(media.file)
            }
            if (!acquired) {
                hideZoomHelpImmediately()
                Toast.makeText(this, "O arquivo ainda não foi liberado para reprodução", Toast.LENGTH_LONG).show()
                return
            }
            playbackLocked = true
        }

        runCatching {
            videoView.setVideoRotation(media.rotationDegrees)
            videoView.setVideoUri(Uri.fromFile(media.file))
            applyInitialPlaybackSpeed()
            hideZoomHelpImmediately()
            videoView.start()
        }.onFailure { throwable ->
            stopVideoPlayback()
            hideZoomHelpImmediately()
            Toast.makeText(this, throwable.message ?: "Falha ao preparar o vídeo", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVideoPlayback() {
        val media = item
        resetTrimFilmstripPreparation()
        if (::videoView.isInitialized) videoView.release()
        if (playbackLocked && media != null) {
            VaultRepository.releaseFromPlayback(media.file)
            playbackLocked = false
        }
    }

    private fun loadImageSafely(file: File) {
        val generation = ++imageLoadGeneration
        showZoomHelp("Carregando imagem…", autoHide = false)
        ioExecutor.execute {
            val result = runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sourceWidth = info.size.width.coerceAtLeast(1)
                    val sourceHeight = info.size.height.coerceAtLeast(1)
                    val maximum = (resources.displayMetrics.widthPixels.coerceAtLeast(resources.displayMetrics.heightPixels) * 2)
                        .coerceIn(1440, 4096)
                    val scale = minOf(1.0, maximum.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble())
                    decoder.setTargetSize(
                        (sourceWidth * scale).toInt().coerceAtLeast(1),
                        (sourceHeight * scale).toInt().coerceAtLeast(1)
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed || generation != imageLoadGeneration) {
                    result.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                result.onSuccess { bitmap ->
                    imageView.setImageBitmap(bitmap)
                    hideZoomHelpImmediately()
                    maybeShowGestureHint()
                }.onFailure {
                    showZoomHelp("Não foi possível abrir esta imagem", autoHide = false)
                    Toast.makeText(this, "Não foi possível abrir esta imagem", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateViewerToolBarPosition(video: Boolean) {
        val actionsHeight = viewerActions.height.takeIf { it > 0 } ?: dp(76)
        val toolbarParams = viewerToolBar.layoutParams as FrameLayout.LayoutParams
        val controlsHeight = videoControls.height.takeIf { video && it > 0 } ?: if (video) dp(108) else 0
        toolbarParams.bottomMargin = actionsHeight + controlsHeight + dp(12)
        viewerToolBar.layoutParams = toolbarParams

        val videoParams = videoControls.layoutParams as FrameLayout.LayoutParams
        videoParams.bottomMargin = actionsHeight + dp(8)
        videoControls.layoutParams = videoParams
    }

    private fun toggleFitMode() {
        val fill = if (item?.video == true) !videoView.isFillMode() else !imageView.isFillMode()
        if (item?.video == true) videoView.setFillMode(fill) else imageView.setFillMode(fill)
        fitModeButton.text = if (fill) "Preencher" else "Ajustar"
        showZoomHelp(if (fill) "Preenchendo a tela; as bordas podem ser cortadas" else "Mostrando a mídia inteira")
    }

    private fun enterTrimMode() {
        val media = item ?: return
        if (!media.video || !videoView.isPrepared()) {
            Toast.makeText(this, "Abra um vídeo para cortar", Toast.LENGTH_SHORT).show()
            return
        }
        val duration = videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(1)
        if (preparedTrimFilmstrip.isEmpty()) {
            trimOpenPending = true
            prepareTrimFilmstrip(media.file, duration)
            return
        }
        showTrimMode(duration)
    }

    private fun showTrimMode(duration: Int) {
        trimOpenPending = false
        trimMode = true
        trimActiveHandle = null
        trimStartMs = 0
        trimEndMs = duration
        trimRemoveAudioSwitch.isChecked = false
        trimSaveButton.text = "Salvar cópia"
        resumeAfterUserSeek = false
        videoView.pause()
        playPauseButton.setImageResource(R.drawable.ic_play)
        viewerControlsVisible = true
        trimControls.visibility = View.VISIBLE
        playbackSeekRow.visibility = View.GONE
        scrubPrecisionLabel.visibility = View.GONE
        trimMediaButton.text = "Cortando"
        exportButton.isEnabled = false
        deleteButton.isEnabled = false
        optimizeButton.isEnabled = false
        seekBar.max = duration
        seekBar.progress = 0
        updateVideoTime(0, duration)
        trimRangeView.minimumSpanFraction = TRIM_MIN_SPAN_MS.toFloat() / duration.toFloat()
        trimRangeView.setRange(0f, 1f, 0f)
        trimRangeView.setFrames(preparedTrimFilmstrip)
        preparedTrimFilmstrip = emptyList()
        updateTrimLabels()
        applyViewerControlsVisibility(animated = true)
        hideZoomHelpImmediately()
    }

    private fun exitTrimMode() {
        trimMode = false
        trimActiveHandle = null
        trimControls.visibility = View.GONE
        playbackSeekRow.visibility = View.VISIBLE
        scrubPrecisionLabel.visibility = View.GONE
        trimMediaButton.text = "Cortar"
        setActionButtonsEnabled(actionButtonsEnabled)
        trimRangeView.clearFrames()
        endScrubPreview()
        updateTrimLabels()
        applyViewerControlsVisibility(animated = false)
    }

    private fun updateTrimLabels() {
        if (!::trimRangeLabel.isInitialized) return
        val duration = currentVideoDuration()
        val end = trimEndMs.takeIf { it > 0 } ?: duration
        trimModeLabel.text = when {
            !trimMode -> "Cortar vídeo"
            trimActiveHandle == VideoTrimRangeView.Handle.START -> "Ajustando início"
            trimActiveHandle == VideoTrimRangeView.Handle.END -> "Ajustando fim"
            trimActiveHandle == VideoTrimRangeView.Handle.PLAYHEAD -> "Prévia do trecho"
            else -> "Cortar vídeo"
        }
        trimRangeLabel.text = "${formatTimeDetailed(trimStartMs)}  —  ${formatTimeDetailed(end)}   •   ${formatTimeDetailed((end - trimStartMs).coerceAtLeast(0))} selecionados"
    }

    private fun currentVideoDuration(): Int =
        videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(seekBar.max).coerceAtLeast(1)

    private fun trimFractionToMs(fraction: Float): Int =
        (currentVideoDuration() * fraction.coerceIn(0f, 1f)).roundToInt()

    private fun prepareTrimFilmstrip(file: File, durationMs: Int) {
        if (destroyed || trimMode || durationMs <= 0 || !file.isFile) return
        if (preparedTrimFilmstrip.isNotEmpty()) {
            if (trimOpenPending) showTrimMode(durationMs)
            return
        }
        if (trimFilmstripLoading) return
        val generation = ++trimFilmstripGeneration
        trimFilmstripLoading = true
        trimMediaButton.text = "Preparando…"
        updateTrimButtonState()
        filmstripExecutor.execute {
            val frames = runCatching {
                VideoFilmstripCache.loadOrCreate(
                    context = this,
                    file = file,
                    durationMs = durationMs,
                    frameCount = TRIM_FILMSTRIP_FRAMES,
                    maximumSide = TRIM_FILMSTRIP_MAXIMUM_SIDE
                ) {
                    VideoThumbnailFactory.loadFilmstrip(
                        file = file,
                        durationMs = durationMs,
                        frameCount = TRIM_FILMSTRIP_FRAMES,
                        maximumSide = TRIM_FILMSTRIP_MAXIMUM_SIDE
                    )
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (destroyed || generation != trimFilmstripGeneration) {
                    frames.forEach { if (!it.isRecycled) it.recycle() }
                    return@runOnUiThread
                }
                trimFilmstripLoading = false
                recyclePreparedTrimFilmstrip()
                preparedTrimFilmstrip = frames
                trimMediaButton.text = "Cortar"
                updateTrimButtonState()
                if (trimOpenPending) {
                    if (frames.isNotEmpty()) {
                        showTrimMode(durationMs)
                    } else {
                        trimOpenPending = false
                        Toast.makeText(
                            this,
                            "Não foi possível carregar os frames deste vídeo. Tente novamente.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun resetTrimFilmstripPreparation() {
        trimFilmstripGeneration++
        trimFilmstripLoading = false
        trimOpenPending = false
        recyclePreparedTrimFilmstrip()
    }

    private fun recyclePreparedTrimFilmstrip() {
        preparedTrimFilmstrip.forEach { if (!it.isRecycled) it.recycle() }
        preparedTrimFilmstrip = emptyList()
    }

    private fun confirmTrimFromPlayer() {
        val media = item ?: return
        val duration = videoDurationMs.coerceAtLeast(videoView.duration).coerceAtLeast(1)
        val end = trimEndMs.coerceIn(1, duration)
        val start = trimStartMs.coerceIn(0, (end - TRIM_MIN_SPAN_MS).coerceAtLeast(0))
        val removeAudio = trimRemoveAudioSwitch.isChecked
        if (end - start < TRIM_MIN_SPAN_MS) {
            Toast.makeText(this, "Escolha um trecho maior para cortar", Toast.LENGTH_LONG).show()
            Haptics.error(this)
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Criar vídeo cortado?",
            message = buildString {
                append("Trecho: ${formatTimeDetailed(start)} até ${formatTimeDetailed(end)}. ")
                append(if (removeAudio) "O áudio será removido da cópia. " else "O áudio será mantido. ")
                append("O original será preservado.")
            },
            positiveLabel = "Cortar"
        ) {
            startTrimProcessing(media, start, end, keepAudio = !removeAudio)
        }
    }

    private fun startTrimProcessing(
        media: VaultRepository.MediaItem,
        startMs: Int,
        endMs: Int,
        keepAudio: Boolean
    ) {
        val config = OptimizationConfig(
            preset = OptimizationPreset.HIGH_QUALITY,
            frameRepair = FrameRepairMode.NONE,
            codec = OutputCodec.SOURCE,
            rateMode = OptimizationRateMode.AUTO,
            targetFps = 0,
            targetWidth = 0,
            targetHeight = 0,
            bitrateMbps = 0,
            keepAudio = keepAudio,
            replaceOriginal = false,
            filters = VideoFilterConfig(),
            thermalProtection = true,
            smartAutoTune = false,
            aiAssisted = false,
            trimStartMs = startMs.toLong(),
            trimEndMs = endMs.toLong()
        ).normalized()
        exitTrimMode()
        stopVideoPlayback()
        val started = runCatching { VideoOptimizationService.start(this, media.file, config) }.getOrElse { throwable ->
            Toast.makeText(this, throwable.message ?: "Não foi possível iniciar o corte", Toast.LENGTH_LONG).show()
            false
        }
        if (started) {
            Haptics.success(this)
            val message = if (keepAudio) {
                "Corte iniciado em segundo plano. O original foi preservado."
            } else {
                "Corte sem áudio iniciado em segundo plano. O original foi preservado."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finishViewer()
        } else if (media.file.isFile) {
            Haptics.error(this)
            Toast.makeText(this, "Já existe outro processamento em andamento", Toast.LENGTH_LONG).show()
            startVideoPlayback(media)
        }
    }

    private fun seekRelative(deltaMs: Int) {
        if (!videoView.isPrepared()) return
        val lower = if (trimMode) trimStartMs else 0
        val upper = if (trimMode) trimEndMs else videoView.duration.coerceAtLeast(0)
        val target = (videoView.currentPosition + deltaMs).coerceIn(lower, upper.coerceAtLeast(lower))
        val shouldResume = videoView.isPlaybackRequested() && !trimMode
        playbackCompleted = false
        videoView.seekFromTimeline(target, shouldResume, precise = trimMode)
        seekBar.progress = target
        if (trimMode) trimRangeView.setPlayhead(target.toFloat() / currentVideoDuration())
        updateVideoTime(target, videoDurationMs.coerceAtLeast(videoView.duration))
        updatePlayPauseButton(shouldResume)
    }

    private fun applyInitialPlaybackSpeed() {
        if (initialPlaybackSpeedApplied) {
            val value = PLAYBACK_SPEEDS[speedIndex]
            videoView.setPlaybackSpeed(value)
            updateSpeedButton(value)
            return
        }
        initialPlaybackSpeedApplied = true
        applyAutomaticPlaybackSpeed(videoView.detectedSourceFrameRate())
    }

    private fun applyAutomaticPlaybackSpeed(frameRate: Float) {
        if (userSelectedPlaybackSpeed) return
        // 120/240 gravados pelo SteadyVault são vídeo em tempo real, como 4K120 da
        // câmera Samsung. Slow motion só acontece se o usuário escolher outra velocidade.
        val targetSpeed = 1f
        val targetIndex = PLAYBACK_SPEEDS.indexOfFirst { it == targetSpeed }.takeIf { it >= 0 } ?: 2
        if (speedIndex == targetIndex && videoView.isPrepared()) return
        speedIndex = targetIndex
        videoView.setPlaybackSpeed(targetSpeed)
        updateSpeedButton(targetSpeed)
        applyPreferredPlaybackRefreshRate(frameRate, targetSpeed)
    }

    private fun cyclePlaybackSpeed() {
        userSelectedPlaybackSpeed = true
        speedIndex = (speedIndex + 1) % PLAYBACK_SPEEDS.size
        val value = PLAYBACK_SPEEDS[speedIndex]
        videoView.setPlaybackSpeed(value)
        updateSpeedButton(value)
        applyPreferredPlaybackRefreshRate(videoView.detectedSourceFrameRate(), value)
        Haptics.tap(this)
    }

    private fun toggleViewerControls() {
        if (dismissInProgress) return
        if (trimMode) {
            viewerControlsVisible = true
            applyViewerControlsVisibility(animated = true)
            return
        }
        viewerControlsVisible = !viewerControlsVisible
        applyViewerControlsVisibility(animated = true)
    }

    private fun applyViewerControlsVisibility(animated: Boolean) {
        val mediaIsVideo = item?.video == true
        setOverlayVisible(viewerHeader, viewerControlsVisible, animated)
        setOverlayVisible(viewerActions, viewerControlsVisible, animated)
        setOverlayVisible(viewerToolBar, viewerControlsVisible && !trimMode, animated)
        setOverlayVisible(videoControls, viewerControlsVisible && mediaIsVideo, animated)
        setOverlayVisible(videoCenterControls, viewerControlsVisible && mediaIsVideo && !trimMode, animated)
    }

    private fun setOverlayVisible(view: View, visible: Boolean, animated: Boolean) {
        view.animate().cancel()
        if (!animated) {
            view.alpha = if (visible) 1f else 0f
            view.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(CONTROLS_FADE_MS).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(CONTROLS_FADE_MS).withEndAction {
                if (!destroyed && view.alpha == 0f) view.visibility = View.GONE
            }.start()
        }
    }

    private fun dismissToVault() {
        if (dismissInProgress) return
        dismissInProgress = true
        viewerControlsVisible = false
        applyViewerControlsVisibility(animated = false)
        mainHandler.postDelayed({ if (!destroyed) finishViewer() }, DISMISS_FINISH_DELAY_MS)
    }

    private fun finishViewer() {
        if (isFinishing || isDestroyed) return
        setSystemBarsVisible(true)
        finish()
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun maybeShowGestureHint() {
        val preferences = getSharedPreferences(VIEWER_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_GESTURE_HINT_SHOWN, false)) return
        preferences.edit().putBoolean(KEY_GESTURE_HINT_SHOWN, true).apply()
        showZoomHelp("Pinça ou dê 2 toques para ampliar • deslize para baixo para voltar")
    }

    private fun showZoomHelp(message: String, autoHide: Boolean = true) {
        if (item?.video == true && autoHide) {
            hideZoomHelpImmediately()
            return
        }
        zoomHelp.animate().cancel()
        zoomHelp.alpha = 1f
        zoomHelp.visibility = View.VISIBLE
        zoomHelp.text = message
        if (autoHide) {
            mainHandler.removeCallbacks(hideHelpRunnable)
            mainHandler.postDelayed(hideHelpRunnable, HELP_VISIBLE_MS)
        }
    }

    private val hideHelpRunnable = Runnable { hideZoomHelp() }

    private fun hideZoomHelp() {
        if (destroyed || waitingForProcessing || zoomHelp.visibility != View.VISIBLE) return
        zoomHelp.animate().alpha(0f).setDuration(250L).withEndAction {
            if (!destroyed) zoomHelp.visibility = View.GONE
        }.start()
    }

    private fun hideZoomHelpImmediately() {
        mainHandler.removeCallbacks(hideHelpRunnable)
        zoomHelp.animate().cancel()
        zoomHelp.alpha = 0f
        zoomHelp.visibility = View.GONE
    }

    private fun export() {
        val media = item ?: return
        if (isProcessing(media.file)) {
            Toast.makeText(this, "Aguarde o processamento encerrar para exportar", Toast.LENGTH_LONG).show()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Exportar mídia",
            message = "O arquivo original é mantido sem conversão. Para enviar no chat, use Compartilhar ou salve em Arquivos.",
            choices = listOf(
                OneUiDialog.Choice("Galeria", "Criar uma cópia na galeria do celular."),
                OneUiDialog.Choice("Arquivos do celular", "Escolher uma pasta e salvar o arquivo original."),
                OneUiDialog.Choice("Compartilhar", "Enviar diretamente para outro app, inclusive apps de chat.")
            )
        ) { option ->
            when (option) {
                0 -> exportToGallery(media)
                1 -> saveMediaInFiles(media)
                2 -> shareMedia(media)
            }
        }
    }

    private fun exportToGallery(media: VaultRepository.MediaItem) {
        exportButton.text = "Exportando…"
        setActionButtonsEnabled(false)
        ioExecutor.execute {
            val result = runCatching {
                when {
                    tertiaryMode -> TertiaryVaultRepository.exportToGallery(this, media)
                    secondaryMode -> SecondaryVaultRepository.exportToGallery(this, media)
                    else -> VaultRepository.exportToGallery(this, media)
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                exportButton.text = "Exportar"
                setActionButtonsEnabled(true)
                result.onSuccess {
                    Haptics.success(this)
                    Toast.makeText(this, "Cópia exportada para a galeria", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Falha ao exportar", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveMediaInFiles(media: VaultRepository.MediaItem) {
        pendingFileExport = media.file
        saveMediaToFiles.launch(media.name)
    }

    private fun shareMedia(media: VaultRepository.MediaItem) {
        val uri = runCatching { FileProvider.getUriForFile(this, "${packageName}.fileprovider", media.file) }.getOrElse {
            Toast.makeText(this, it.message ?: "Não foi possível preparar o compartilhamento", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = externalMimeFor(media)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, media.name)
            clipData = android.content.ClipData.newRawUri("Mídia SteadyVault", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Compartilhar mídia")) }.onFailure {
            Toast.makeText(this, it.message ?: "Nenhum app disponível para compartilhar", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val media = item ?: return
        if (deletePending) return
        if (isProcessing(media.file)) {
            OneUiDialog.choices(
                activity = this,
                title = "Excluir mídia em processamento",
                message = "A otimização será encerrada antes de liberar o arquivo.",
                choices = listOf(
                    OneUiDialog.Choice("Cancelar e mover para a lixeira", "Permite restaurar depois.", destructive = true),
                    OneUiDialog.Choice("Cancelar e excluir direto", "Apaga permanentemente sem passar pela lixeira.", destructive = true)
                )
            ) { option -> confirmProcessingDelete(media, permanently = option == 1) }
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Excluir mídia",
            message = "Escolha se quer manter a possibilidade de restauração.",
            choices = listOf(
                OneUiDialog.Choice("Mover para a lixeira", "Pode ser restaurada para o cofre de origem.", destructive = true),
                OneUiDialog.Choice("Excluir direto", "Apaga permanentemente sem passar pela lixeira.", destructive = true)
            )
        ) { option -> if (option == 0) confirmMoveToTrash(media) else confirmPermanentDelete(media) }
    }

    private fun confirmMoveToTrash(media: VaultRepository.MediaItem) {
        OneUiDialog.confirm(
            activity = this,
            title = "Mover para a lixeira?",
            message = "A mídia poderá ser restaurada para o cofre de origem. Cópias exportadas permanecem.",
            positiveLabel = "Mover",
            destructive = true
        ) { deleteSafely(media, permanently = false) }
    }

    private fun confirmPermanentDelete(media: VaultRepository.MediaItem) {
        OneUiDialog.confirm(
            activity = this,
            title = "Excluir direto?",
            message = "A mídia será apagada permanentemente e não poderá ser restaurada.",
            positiveLabel = "Excluir direto",
            destructive = true
        ) { deleteSafely(media, permanently = true) }
    }

    private fun confirmProcessingDelete(media: VaultRepository.MediaItem, permanently: Boolean) {
        OneUiDialog.confirm(
            activity = this,
            title = if (permanently) "Cancelar e excluir direto?" else "Cancelar e mover para a lixeira?",
            message = if (permanently) {
                "A otimização será encerrada e a mídia será apagada permanentemente."
            } else {
                "A otimização será encerrada e a mídia poderá ser restaurada depois."
            },
            positiveLabel = if (permanently) "Excluir direto" else "Mover",
            destructive = true
        ) { cancelProcessingAndDelete(media, permanently) }
    }

    private fun cancelProcessingAndDelete(media: VaultRepository.MediaItem, permanently: Boolean) {
        deletePending = true
        val progress = OneUiDialog.progress(
            activity = this,
            title = "Encerrando processamento",
            message = "Aguardando o arquivo ser liberado com segurança…"
        )
        VideoOptimizationService.cancel(this)
        waitForFileRelease(
            media.file,
            onReleased = {
                progress.dismiss()
                deletePending = false
                deleteSafely(media, permanently)
            },
            onTimeout = {
                progress.dismiss()
                deletePending = false
                Toast.makeText(this, "O processamento ainda não liberou o arquivo", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun deleteSafely(media: VaultRepository.MediaItem, permanently: Boolean) {
        stopVideoPlayback()
        val deleted = if (permanently) {
            when {
                tertiaryMode -> TertiaryVaultRepository.deletePermanently(this, media)
                secondaryMode -> SecondaryVaultRepository.deletePermanently(this, media)
                else -> VaultRepository.delete(this, media)
            }
        } else {
            VaultTrashRepository.moveToTrash(this, media) != null
        }
        if (deleted) {
            Haptics.stop(this)
            finish()
        } else {
            val message = if (permanently) {
                "Não foi possível excluir direto. O arquivo pode ainda estar em uso."
            } else {
                "Não foi possível mover para a lixeira. O arquivo pode ainda estar em uso."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (media.video && media.file.isFile) startVideoPlayback(media)
        }
    }

    private fun waitForFileRelease(file: File, onReleased: () -> Unit, onTimeout: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + PROCESSING_RELEASE_TIMEOUT_MS
        fun check() {
            if (destroyed || isFinishing || isDestroyed) return
            OptimizationStateStore.recoverStale(this)
            VaultRepository.releaseStaleProcessingLocks()
            if (!isProcessing(file)) {
                onReleased()
                return
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                onTimeout()
                return
            }
            mainHandler.postDelayed({ check() }, PROCESSING_POLL_MS)
        }
        check()
    }

    private fun isProcessing(file: File): Boolean =
        OptimizationStateStore.snapshotFor(this, file)?.running == true || VaultRepository.isBeingProcessed(file)

    private fun setActionButtonsEnabled(enabled: Boolean) {
        actionButtonsEnabled = enabled
        exportButton.isEnabled = enabled
        optimizeButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
        exportButton.alpha = if (enabled) 1f else 0.45f
        optimizeButton.alpha = if (enabled) 1f else 0.45f
        deleteButton.alpha = if (enabled) 1f else 0.45f
        updateTrimButtonState()
    }

    private fun updateTrimButtonState() {
        val enabled = actionButtonsEnabled && (!trimFilmstripLoading || trimMode)
        trimMediaButton.isEnabled = enabled
        trimMediaButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateVideoTime(positionMs: Int, durationMs: Int) {
        val safeDuration = durationMs.coerceAtLeast(videoDurationMs).coerceAtLeast(0)
        val safePosition = positionMs.coerceAtLeast(0).coerceAtMost(safeDuration.coerceAtLeast(0))
        currentTimeText.text = formatTime(safePosition)
        totalTimeText.text = formatTime(safeDuration)
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun formatTimeDetailed(milliseconds: Int): String {
        val safe = milliseconds.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        val ms = safe % 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d.%03d".format(hours, minutes, seconds, ms)
        else "%d:%02d.%03d".format(minutes, seconds, ms)
    }


    private fun captureDisplayRefreshPreference() {
        if (refreshPreferenceCaptured) return
        val attributes = window.attributes
        originalPreferredDisplayModeId = attributes.preferredDisplayModeId
        originalPreferredRefreshRate = attributes.preferredRefreshRate
        refreshPreferenceCaptured = true
    }

    private fun applyPreferredPlaybackRefreshRate(sourceFps: Float, playbackSpeed: Float) {
        val outputRate = sourceFps.coerceAtLeast(0f) * playbackSpeed.coerceAtLeast(0.25f)
        if (outputRate < HIGH_REFRESH_OUTPUT_MIN) {
            restoreDisplayRefreshPreference()
            return
        }
        val targetRate = outputRate.coerceAtMost(MAX_PREFERRED_REFRESH_RATE)
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            getSystemService(DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        } ?: return
        val currentMode = currentDisplay.mode
        val compatibleModes = currentDisplay.supportedModes.filter { mode ->
            mode.physicalWidth == currentMode.physicalWidth && mode.physicalHeight == currentMode.physicalHeight
        }
        val selectedMode = compatibleModes.minByOrNull { mode ->
            kotlin.math.abs(mode.refreshRate - targetRate)
        } ?: return
        val attributes = window.attributes
        if (
            attributes.preferredDisplayModeId == selectedMode.modeId &&
            kotlin.math.abs(attributes.preferredRefreshRate - selectedMode.refreshRate) < 0.1f
        ) return
        attributes.preferredDisplayModeId = selectedMode.modeId
        attributes.preferredRefreshRate = selectedMode.refreshRate
        window.attributes = attributes
    }

    private fun restoreDisplayRefreshPreference() {
        if (!refreshPreferenceCaptured) return
        val attributes = window.attributes
        if (
            attributes.preferredDisplayModeId == originalPreferredDisplayModeId &&
            kotlin.math.abs(attributes.preferredRefreshRate - originalPreferredRefreshRate) < 0.1f
        ) return
        attributes.preferredDisplayModeId = originalPreferredDisplayModeId
        attributes.preferredRefreshRate = originalPreferredRefreshRate
        window.attributes = attributes
    }

    private fun formatSpeed(value: Float): String =
        if (value % 1f == 0f) "${value.toInt()}×" else "${value}×"

    private fun updateSpeedButton(value: Float) {
        val formatted = formatSpeed(value)
        speedButton.text = "Vel. $formatted"
        val description = "Velocidade de reprodução $formatted"
        speedButton.contentDescription = description
        speedButton.tooltipText = description
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PATH = "media_path"
        const val EXTRA_SECONDARY = "secondary_vault"
        const val EXTRA_TERTIARY = "tertiary_vault"
        const val EXTRA_START_TRIM = "start_trim"
        private const val PROCESSING_POLL_MS = 250L
        private const val PROCESSING_RELEASE_TIMEOUT_MS = 20_000L
        private const val PROGRESS_UPDATE_MS = 100L
        private const val HELP_VISIBLE_MS = 3_500L
        private const val CONTROLS_FADE_MS = 150L
        private const val DISMISS_FINISH_DELAY_MS = 90L
        private const val VIEWER_PREFERENCES = "steadyvault_viewer_preferences"
        private const val KEY_GESTURE_HINT_SHOWN = "gesture_hint_shown"
        private const val SEEK_STEP_MS = 10_000
        private const val DIRECT_SEEK_SETTLE_MS = 80L
        private const val TRIM_MIN_SPAN_MS = 500
        private const val SCRUB_SETTLE_MS = 45L
        private const val PRECISION_STATUS_MS = 1_500L
        private const val TRIM_FILMSTRIP_FRAMES = 9
        private const val TRIM_FILMSTRIP_MAXIMUM_SIDE = 240
        private const val HIGH_FRAME_RATE_MIN = 230f
        private const val HIGH_FRAME_RATE_MAX = 241.5f
        private const val HIGH_REFRESH_OUTPUT_MIN = 50f
        private const val MAX_PREFERRED_REFRESH_RATE = 120f
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val SCRUB_PRECISIONS = floatArrayOf(1f, 0.5f, 0.25f, 0.125f)
    }
}
