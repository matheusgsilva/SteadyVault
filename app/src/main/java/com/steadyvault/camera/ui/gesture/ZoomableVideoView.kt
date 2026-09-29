package com.steadyvault.camera.ui.gesture

import android.content.Context
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.steadyvault.camera.core.playback.PlaybackSettings
import com.steadyvault.camera.core.validation.UiBehaviorRules
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VlcMedia
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Player local híbrido.
 *
 * O Media3 usa primeiro o decodificador nativo do aparelho para manter cadência
 * estável em 30 FPS e em câmera lenta de 240 FPS. O VLC permanece disponível como
 * fallback de compatibilidade, preservando posição, velocidade e intenção de reprodução.
 */
@UnstableApi
class ZoomableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), MediaController.MediaPlayerControl {

    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        keepScreenOn = true
        isClickable = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var vlcVideoLayout = newVlcVideoLayout()
    private val contentLayer = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val gestureOverlay = View(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var media3Player: ExoPlayer? = null
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var source: Uri? = null
    private val playbackSettings = PlaybackSettings.snapshot(context.applicationContext)
    private var playbackProfile = PlaybackProfile(0f, 0, 0, 0.0, 100, 0, 0, false, false, 700)
    private var engine = PlaybackEngine.MEDIA3
    private var media3Profile = Media3Profile.PERFORMANCE
    private var prepared = false
    private var playWhenReady = false
    private var pendingSeekMs = 0L
    private var pendingSeekFast = false
    private var sourceFrameRate = 0f
    private var sourceVideoWidth = 0
    private var sourceVideoHeight = 0
    private var externalRotationDegrees = 0
    private var fillMode = false
    private var speed = 1f
    private val recoveryPolicy = PlaybackRecoveryPolicy()
    private var recoveryTransitionPending = false
    private var playbackEnded = false
    private var terminalFailure = false
    private var bufferingGeneration = 0L
    private var bufferingStartedAtMs = 0L
    private var bufferingRecoveryScheduled = false
    private var vlcBuffering = false
    private var vlcBufferPercentage = 0
    private var vlcSessionId = 0L
    private var engineOpenGeneration = 0L
    private var surfaceWaitGeneration = 0L
    private var firstFrameGeneration = 0L
    private var firstFrameRendered = false
    private var vlcVideoOutputReady = false
    private var vlcMediaAssigned = false
    private var surfaceRecoveryPending = false
    private var sameEngineSurfaceRecoveryAttempts = 0
    private var hostSuspended = false
    private var previewFrameRequested = false
    private var previewSeekActive = false
    private var previewSeekGeneration = 0L
    private var previewSeekOriginMs = -1L
    private var lastPreviewSeekMs = -1L
    private var lastPreviewRequestRealtimeMs = 0L
    private val previewRenderQueue = LatestScrubTargetQueue()
    private var analysisGeneration = 0L

    private var errorListener: ((Throwable?) -> Unit)? = null
    private var engineListener: ((String) -> Unit)? = null
    private var zoomListener: ((Float) -> Unit)? = null
    private var preparedListener: ((Int) -> Unit)? = null
    private var completionListener: (() -> Unit)? = null
    private var playbackListener: ((Boolean) -> Unit)? = null
    private var singleTapListener: (() -> Unit)? = null
    private var dismissListener: (() -> Unit)? = null
    private var analysisListener: ((PlaybackProfile) -> Unit)? = null
    private var healthLastPositionMs = 0L
    private var healthLastCheckMs = 0L
    private var healthStallSamples = 0
    private var healthMonitorRunning = false

    private val zoomController = ZoomGestureController(
        context = context,
        target = contentLayer,
        maxScale = MAX_ZOOM,
        onSingleTap = { singleTapListener?.invoke() },
        onScaleChanged = { scale -> zoomListener?.invoke(scale) },
        onDismiss = { dismissListener?.invoke() }
    )

    private val media3Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    cancelBufferingRecovery()
                    prepared = true
                    playbackEnded = false
                    terminalFailure = false
                    preparedListener?.invoke(duration)
                    applySurfaceFrameRate()
                    armFirstFrameWatchdog()
                }

                Player.STATE_BUFFERING -> scheduleBufferingRecovery()

                Player.STATE_ENDED -> {
                    cancelBufferingRecovery()
                    prepared = true
                    playbackEnded = true
                    playWhenReady = false
                    pendingSeekMs = 0L
                    playbackListener?.invoke(false)
                    completionListener?.invoke()
                }

                Player.STATE_IDLE -> {
                    cancelBufferingRecovery()
                    prepared = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val playbackActive = playWhenReady && (isPlaying || media3Player?.playbackState == Player.STATE_BUFFERING)
            playbackListener?.invoke(playbackActive)
            if (isPlaying) {
                cancelBufferingRecovery()
                applySurfaceFrameRate()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            prepared = false
            playbackListener?.invoke(false)
            recoverFromMedia3Failure(error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                sourceVideoWidth = videoSize.width
                sourceVideoHeight = videoSize.height
            }
            applySurfaceFrameRate()
        }

        override fun onRenderedFirstFrame() {
            markFirstFrameReady()
            applySurfaceFrameRate()
        }

    }

    private fun handleVlcEvent(eventType: Int, buffering: Float = 0f, voutCount: Int = 0, timeChangedMs: Long = -1L) {
        if (source == null || engine != PlaybackEngine.VLC) return
        when (eventType) {
            VlcMediaPlayer.Event.Opening -> {
                prepared = false
                vlcBuffering = true
                playbackListener?.invoke(playWhenReady)
                scheduleBufferingRecovery()
            }

            VlcMediaPlayer.Event.Buffering -> {
                vlcBuffering = buffering < 100f
                vlcBufferPercentage = buffering.toInt().coerceIn(0, 100)
                playbackListener?.invoke(playWhenReady)
                if (vlcBuffering) scheduleBufferingRecovery() else cancelBufferingRecovery()
            }

            VlcMediaPlayer.Event.Vout -> {
                vlcVideoOutputReady = voutCount > 0
                if (vlcVideoOutputReady) {
                    val sessionId = vlcSessionId
                    postDelayed({
                        if (sessionId == vlcSessionId && vlcVideoOutputReady && isVlcSurfaceValid()) {
                            markFirstFrameReady()
                        }
                    }, VLC_FIRST_FRAME_CONFIRM_DELAY_MS)
                } else if (playWhenReady || previewFrameRequested) {
                    firstFrameRendered = false
                    armFirstFrameWatchdog()
                }
            }

            VlcMediaPlayer.Event.TimeChanged -> {
                if (timeChangedMs >= 0L && vlcVideoOutputReady && isVlcSurfaceValid()) markFirstFrameReady()
            }

            VlcMediaPlayer.Event.Playing -> {
                cancelBufferingRecovery()
                vlcBuffering = false
                vlcBufferPercentage = 100
                prepared = true
                playbackEnded = false
                terminalFailure = false
                applyVlcPlaybackSpeed()
                applyVlcVideoLayout()
                if (pendingSeekMs > 0L) {
                    runCatching { vlcPlayer?.setTime(pendingSeekMs, pendingSeekFast) }
                    pendingSeekMs = 0L
                    pendingSeekFast = false
                }
                if (!playWhenReady && !previewFrameRequested) {
                    runCatching { vlcPlayer?.pause() }
                    playbackListener?.invoke(false)
                } else {
                    playbackListener?.invoke(playWhenReady)
                    armFirstFrameWatchdog()
                }
                preparedListener?.invoke(duration)
                applySurfaceFrameRate()
            }

            VlcMediaPlayer.Event.Paused -> {
                cancelBufferingRecovery()
                vlcBuffering = false
                prepared = true
                playbackListener?.invoke(false)
            }

            VlcMediaPlayer.Event.Stopped -> {
                cancelBufferingRecovery()
                vlcBuffering = false
                playbackListener?.invoke(false)
            }

            VlcMediaPlayer.Event.EndReached -> {
                cancelBufferingRecovery()
                cancelFirstFrameWatchdog()
                vlcBuffering = false
                prepared = true
                playbackEnded = true
                playWhenReady = false
                previewFrameRequested = false
                pendingSeekMs = 0L
                playbackListener?.invoke(false)
                completionListener?.invoke()
            }

            VlcMediaPlayer.Event.EncounteredError -> {
                cancelBufferingRecovery()
                cancelFirstFrameWatchdog()
                vlcBuffering = false
                prepared = false
                playbackListener?.invoke(false)
                recoverFromVlcFailure(null)
            }
        }
    }

    private val playbackHealthMonitor = object : Runnable {
        override fun run() {
            if (!healthMonitorRunning || source == null) return
            val now = SystemClock.uptimeMillis()
            val position = currentPosition.toLong()
            val durationMs = duration.toLong()
            val nearEnd = durationMs > 0L && durationMs - position < HEALTH_END_GUARD_MS
            if (playWhenReady && prepared && !isActiveSurfaceValid() && !surfaceRecoveryPending) {
                firstFrameRendered = false
                recoverMissingVideoOutput(IllegalStateException("A superfície de vídeo foi perdida durante a reprodução"))
                postDelayed(this, HEALTH_CHECK_MS)
                return
            }
            val buffering = when (engine) {
                PlaybackEngine.VLC -> vlcBuffering
                PlaybackEngine.MEDIA3 -> media3Player?.playbackState == Player.STATE_BUFFERING
            }
            val shouldMeasure = playWhenReady && prepared && !playbackEnded && !nearEnd && !buffering
            if (shouldMeasure && healthLastCheckMs > 0L) {
                val elapsed = (now - healthLastCheckMs).coerceAtLeast(1L)
                val advanced = (position - healthLastPositionMs).coerceAtLeast(0L)
                val health = PlaybackHealthPolicy.classify(elapsed, advanced, speed)
                val stalled = health == PlaybackHealthPolicy.State.STALLED
                healthStallSamples = if (stalled) healthStallSamples + 1 else 0
                val veryHighFrameRateSlowMotion = isVeryHighFrameRateSlowMotion()
                // Pequenas oscilações do relógio não justificam desmontar o player.
                // Só trocamos de decodificador quando o vídeo realmente deixa de avançar.
                val unhealthy = healthStallSamples >= if (veryHighFrameRateSlowMotion) {
                    HEALTH_HFR_STALL_SAMPLES
                } else {
                    HEALTH_STALL_SAMPLES
                }
                if (unhealthy && playbackSettings.intelligentPlayback && playbackSettings.autoRecoverStalls &&
                    !recoveryTransitionPending) {
                    healthStallSamples = 0
                    when (engine) {
                        PlaybackEngine.VLC -> recoverFromVlcFailure(
                            IllegalStateException("Reprodução sem avanço detectada")
                        )
                        PlaybackEngine.MEDIA3 -> recoverFromMedia3Failure(
                            IllegalStateException("Reprodução sem avanço detectada")
                        )
                    }
                }
            } else {
                healthStallSamples = 0
            }
            healthLastCheckMs = now
            healthLastPositionMs = position
            postDelayed(this, HEALTH_CHECK_MS)
        }
    }

    private val previewRenderRunnable = Runnable {
        val target = previewRenderQueue.consume()
        if (previewSeekActive && target >= 0L) renderPreviewSeek(target)
    }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        contentLayer.addView(playerView)
        contentLayer.addView(vlcVideoLayout)
        addView(contentLayer)
        addView(gestureOverlay)
        gestureOverlay.setOnTouchListener(zoomController)
    }

    private fun newVlcVideoLayout(): VLCVideoLayout = VLCVideoLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        visibility = View.GONE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVlcVideoLayout() }
    }

    private fun removeVlcVideoLayoutFromHost() {
        if (vlcVideoLayout.parent !== contentLayer) return
        runCatching {
            if (contentLayer.isInLayout) contentLayer.removeViewInLayout(vlcVideoLayout)
            else contentLayer.removeView(vlcVideoLayout)
        }
    }

    private fun replaceVlcVideoLayout() {
        removeVlcVideoLayoutFromHost()
        vlcVideoLayout = newVlcVideoLayout()
        contentLayer.addView(vlcVideoLayout, minOf(1, contentLayer.childCount))
    }

    private fun requestEngineOpen(preservePosition: Boolean) {
        val generation = ++engineOpenGeneration
        post { openEngineWhenReady(generation, preservePosition, attempt = 0) }
    }

    private fun openEngineWhenReady(generation: Long, preservePosition: Boolean, attempt: Int) {
        if (generation != engineOpenGeneration || source == null || hostSuspended) return
        if (!isAttachedToWindow || width <= 0 || height <= 0) {
            if (attempt < ENGINE_OPEN_WAIT_ATTEMPTS) {
                postDelayed(
                    { openEngineWhenReady(generation, preservePosition, attempt + 1) },
                    ENGINE_OPEN_WAIT_STEP_MS
                )
            } else {
                terminalFailure = true
                errorListener?.invoke(IllegalStateException("A tela do player não ficou disponível"))
            }
            return
        }
        when (engine) {
            PlaybackEngine.VLC -> openVlcPlayer(preservePosition)
            PlaybackEngine.MEDIA3 -> openMedia3Player(preservePosition)
        }
    }

    private fun markFirstFrameReady() {
        if (firstFrameRendered) return
        firstFrameRendered = true
        firstFrameGeneration++
        sameEngineSurfaceRecoveryAttempts = 0
        surfaceRecoveryPending = false
        if (previewFrameRequested && !playWhenReady) {
            postDelayed({
                if (!previewFrameRequested || playWhenReady) return@postDelayed
                previewFrameRequested = false
                when (engine) {
                    PlaybackEngine.MEDIA3 -> runCatching { media3Player?.pause() }
                    PlaybackEngine.VLC -> runCatching { vlcPlayer?.pause() }
                }
                playbackListener?.invoke(false)
            }, PREVIEW_FRAME_PAUSE_DELAY_MS)
        }
    }

    private fun armFirstFrameWatchdog() {
        if (firstFrameRendered || (!playWhenReady && !previewFrameRequested) || surfaceRecoveryPending) return
        val generation = ++firstFrameGeneration
        val sessionId = vlcSessionId
        val delay = if (playbackProfile.demandingDecode) FIRST_FRAME_TIMEOUT_DEMANDING_MS else FIRST_FRAME_TIMEOUT_MS
        postDelayed({
            if (generation != firstFrameGeneration || firstFrameRendered || source == null || hostSuspended) return@postDelayed
            if (engine == PlaybackEngine.VLC && sessionId != vlcSessionId) return@postDelayed
            recoverMissingVideoOutput(IllegalStateException("O decodificador não entregou o primeiro quadro"))
        }, delay)
    }

    private fun cancelFirstFrameWatchdog() {
        firstFrameGeneration++
    }

    private fun recoverMissingVideoOutput(cause: Throwable) {
        if (surfaceRecoveryPending || source == null || hostSuspended) return
        surfaceRecoveryPending = true
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        captureCurrentState()
        if (sameEngineSurfaceRecoveryAttempts < MAX_SAME_ENGINE_SURFACE_RECOVERIES) {
            sameEngineSurfaceRecoveryAttempts++
            when (engine) {
                PlaybackEngine.MEDIA3 -> releaseMedia3Player(keepPosition = false)
                PlaybackEngine.VLC -> releaseVlcPlayer(keepPosition = false, releaseCore = false)
            }
            prepared = false
            postDelayed({
                surfaceRecoveryPending = false
                requestEngineOpen(preservePosition = true)
            }, SURFACE_RECONNECT_DELAY_MS)
            return
        }
        surfaceRecoveryPending = false
        when (engine) {
            PlaybackEngine.MEDIA3 -> recoverFromMedia3Failure(cause)
            PlaybackEngine.VLC -> recoverFromVlcFailure(cause)
        }
    }

    private fun isVlcSurfaceValid(): Boolean =
        vlcVideoLayout.parent === contentLayer &&
            findSurfaceView(vlcVideoLayout)?.holder?.surface?.isValid == true

    private fun isActiveSurfaceValid(): Boolean {
        val root: View = if (engine == PlaybackEngine.MEDIA3) playerView else vlcVideoLayout
        return findSurfaceView(root)?.holder?.surface?.isValid == true
    }

    fun suspendForHost(): Boolean {
        val shouldResume = playWhenReady || isPlaying
        captureCurrentState()
        hostSuspended = true
        playWhenReady = false
        previewFrameRequested = false
        stopHealthMonitor()
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        engineOpenGeneration++
        releaseMedia3Player(keepPosition = false)
        releaseVlcPlayer(keepPosition = false, releaseCore = false)
        prepared = false
        playbackListener?.invoke(false)
        return shouldResume
    }

    fun resumeFromHost(shouldPlay: Boolean) {
        if (source == null) return
        hostSuspended = false
        playWhenReady = shouldPlay
        previewFrameRequested = !shouldPlay
        firstFrameRendered = false
        vlcVideoOutputReady = false
        if (shouldPlay) startHealthMonitor()
        requestEngineOpen(preservePosition = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!hostSuspended && source != null && media3Player == null && vlcPlayer == null) {
            requestEngineOpen(preservePosition = true)
        }
    }

    override fun onDetachedFromWindow() {
        val shouldResume = playWhenReady || isPlaying
        if (source != null && !hostSuspended) {
            captureCurrentState()
            cancelBufferingRecovery()
            cancelFirstFrameWatchdog()
            engineOpenGeneration++
            releaseMedia3Player(keepPosition = false)
            releaseVlcPlayer(keepPosition = false, releaseCore = false)
            playWhenReady = shouldResume
            prepared = false
        }
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setVideoUri(uri: Uri) {
        releasePlayers(clearSource = false, keepPosition = false, releaseVlcCore = false)
        source = uri
        zoomController.resetImmediately()
        prepared = false
        playWhenReady = false
        pendingSeekMs = 0L
        pendingSeekFast = false
        engine = PlaybackEngine.VLC
        media3Profile = Media3Profile.PERFORMANCE
        recoveryPolicy.reset()
        playbackEnded = false
        terminalFailure = false
        recoveryTransitionPending = false
        surfaceRecoveryPending = false
        sameEngineSurfaceRecoveryAttempts = 0
        firstFrameRendered = false
        vlcVideoOutputReady = false
        vlcMediaAssigned = false
        previewFrameRequested = false
        vlcBufferPercentage = 0
        analysisGeneration++
        val metadata = SourceMetadata(0f, 0, 0)
        playbackProfile = quickPlaybackProfile(metadata)
        sourceFrameRate = metadata.frameRate
        sourceVideoWidth = metadata.width
        sourceVideoHeight = metadata.height
        analysisListener?.invoke(playbackProfile)
        selectInitialEngine()
        notifyEngineChanged()
        requestEngineOpen(preservePosition = false)
        startSourceMetadataAnalysis(uri)
    }

    fun setOnErrorListener(listener: ((Throwable?) -> Unit)?) { errorListener = listener }

    fun setOnPlaybackEngineChangedListener(listener: ((String) -> Unit)?) {
        engineListener = listener
        if (source != null) listener?.invoke(engineDescription())
    }

    fun setOnZoomChangedListener(listener: ((Float) -> Unit)?) {
        zoomListener = listener
        listener?.invoke(currentZoom())
    }

    fun setOnPreparedListener(listener: ((Int) -> Unit)?) {
        preparedListener = listener
        if (prepared) listener?.invoke(duration)
    }

    fun setOnCompletionListener(listener: (() -> Unit)?) { completionListener = listener }

    fun setOnPlaybackChangedListener(listener: ((Boolean) -> Unit)?) {
        playbackListener = listener
        listener?.invoke(isPlaying || playWhenReady)
    }

    fun setOnSingleTapListener(listener: (() -> Unit)?) { singleTapListener = listener }

    fun setOnDismissListener(listener: (() -> Unit)?) { dismissListener = listener }

    fun setOnPlaybackAnalysisListener(listener: ((PlaybackProfile) -> Unit)?) {
        analysisListener = listener
        if (source != null) listener?.invoke(playbackProfile)
    }

    fun resetZoom() = zoomController.reset()
    fun zoomBy(delta: Float) = zoomController.zoomBy(delta)
    fun zoomTo(scale: Float) = zoomController.zoomTo(scale)
    fun currentZoom(): Float = zoomController.currentScale()

    fun setFillMode(enabled: Boolean) {
        if (fillMode == enabled) return
        fillMode = enabled
        zoomController.resetImmediately()
        val resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.resizeMode = resizeMode
        applyVlcVideoLayout()
    }

    fun isFillMode(): Boolean = fillMode

    fun setVideoRotation(degrees: Int) {
        externalRotationDegrees = ((degrees % 360) + 360) % 360
    }

    fun togglePlayback() {
        if (isPlaying || playWhenReady) pause() else start()
    }

    fun prepareReplay() {
        playWhenReady = false
        pendingSeekMs = 0L
        stopHealthMonitor()
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        terminalFailure = false
        when (engine) {
            PlaybackEngine.MEDIA3 -> {
                previewFrameRequested = false
                media3Player?.let { player ->
                    runCatching {
                        player.pause()
                        player.playWhenReady = false
                        player.seekTo(0L)
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        playbackEnded = false
                        prepared = true
                    }
                }
            }
            PlaybackEngine.VLC -> {
                // O VLC precisa decodificar novamente o primeiro quadro após EndReached.
                // Faz isso em silêncio e pausa assim que o quadro estiver visível.
                previewFrameRequested = true
                playbackEnded = false
                prepared = false
                if (!restartCurrentVlcMedia(0L)) requestEngineOpen(preservePosition = false)
            }
        }
        playbackListener?.invoke(false)
    }

    fun setPlaybackSpeed(requested: Float) {
        speed = UiBehaviorRules.sanitizedPlaybackSpeed(requested)
        media3Player?.playbackParameters = PlaybackParameters(speed)
        applyVlcPlaybackSpeed()
        applySurfaceFrameRate()
    }

    fun detectedSourceFrameRate(): Float = sourceFrameRate
    fun isPrepared(): Boolean = prepared
    fun isPlaybackRequested(): Boolean = playWhenReady || isPlaying

    fun beginPreviewSeek() {
        if (source == null) return
        cancelPreviewRender()
        previewSeekActive = true
        previewSeekGeneration++
        previewFrameRequested = true
        previewSeekOriginMs = currentPosition.toLong().coerceAtLeast(0L)
        lastPreviewSeekMs = previewSeekOriginMs
        lastPreviewRequestRealtimeMs = SystemClock.uptimeMillis()
        stopHealthMonitor()
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        media3Player?.let { player ->
            runCatching {
                player.setSeekParameters(SeekParameters.EXACT)
                player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
                player.setScrubbingModeEnabled(true)
            }
        }
    }

    fun previewSeekTo(position: Int) {
        val target = snapPreviewPosition(position).toLong()
        pendingSeekMs = target
        pendingSeekFast = false
        previewFrameRequested = true
        playbackEnded = false
        terminalFailure = false
        if (!previewSeekActive) return
        if (!previewRenderQueue.hasPending() && target == lastPreviewSeekMs) return
        if (previewRenderQueue.offer(target)) postOnAnimation(previewRenderRunnable)
    }

    private fun renderPreviewSeek(target: Long) {
        val now = SystemClock.uptimeMillis()
        val previousTarget = lastPreviewSeekMs
        val elapsedSincePreviousRequest = now - lastPreviewRequestRealtimeMs
        lastPreviewRequestRealtimeMs = now
        if (!previewSeekActive || target == previousTarget) return

        val seekMode = ScrubSeekPolicy.chooseMode(
            previousPositionMs = previousTarget,
            targetPositionMs = target,
            elapsedRealtimeMs = elapsedSincePreviousRequest,
            frameRate = sourceFrameRate
        )
        lastPreviewSeekMs = target
        pendingSeekFast = seekMode == ScrubSeekPolicy.Mode.FAST_SYNC
        when (engine) {
            PlaybackEngine.MEDIA3 -> previewSeekMedia3(
                target,
                if (seekMode == ScrubSeekPolicy.Mode.FAST_SYNC) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
            )
            PlaybackEngine.VLC -> previewSeekVlc(target, fast = seekMode == ScrubSeekPolicy.Mode.FAST_SYNC)
        }
    }

    fun snapPreviewPosition(position: Int): Int =
        ScrubFramePolicy.snapPositionMs(position, duration, sourceFrameRate)

    fun endPreviewSeek(finalPosition: Int? = null, resumePlayback: Boolean = false, precise: Boolean = false) {
        val pendingRenderTarget = previewRenderQueue.consume().takeIf { it >= 0L }
        cancelPreviewRender()
        val finalTarget = finalPosition?.let { snapPreviewPosition(it).toLong() }
            ?: pendingRenderTarget
            ?: lastPreviewSeekMs.takeIf { it >= 0L }
        media3Player?.let { player ->
            runCatching { player.setScrubbingModeEnabled(false) }
        }
        if (finalTarget != null) {
            val commitMode = ScrubSeekPolicy.chooseCommitMode(
                startPositionMs = previewSeekOriginMs,
                targetPositionMs = finalTarget,
                resumePlayback = resumePlayback,
                precisionRequired = precise
            )
            val fast = commitMode == ScrubSeekPolicy.Mode.FAST_SYNC
            pendingSeekMs = finalTarget
            pendingSeekFast = fast
            lastPreviewSeekMs = finalTarget
            when (engine) {
                PlaybackEngine.MEDIA3 -> previewSeekMedia3(
                    finalTarget,
                    if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
                )
                PlaybackEngine.VLC -> previewSeekVlc(finalTarget, fast = fast)
            }
        }
        previewSeekActive = false
        previewSeekGeneration++
        previewFrameRequested = false
        previewSeekOriginMs = -1L
        lastPreviewSeekMs = -1L
        lastPreviewRequestRealtimeMs = 0L
        media3Player?.let { player ->
            runCatching {
                player.setScrubbingModeEnabled(false)
                if (!playWhenReady && !resumePlayback) {
                    player.pause()
                    player.playWhenReady = false
                }
            }
        }
    }

    private fun cancelPreviewRender() {
        removeCallbacks(previewRenderRunnable)
        previewRenderQueue.clear()
    }

    private fun previewSeekMedia3(target: Long, seekParameters: SeekParameters) {
        val player = media3Player
        if (player == null) {
            requestEngineOpen(preservePosition = true)
            return
        }
        runCatching {
            player.setSeekParameters(seekParameters)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.seekTo(target)
            player.setSeekParameters(SeekParameters.EXACT)
        }.onFailure { recoverFromMedia3Failure(it) }
    }

    private fun previewSeekVlc(target: Long, fast: Boolean) {
        val player = vlcPlayer
        if (player == null) {
            requestEngineOpen(preservePosition = true)
            return
        }
        runCatching {
            player.setTime(target, fast)
            if (!playWhenReady && !player.isPlaying) {
                previewFrameRequested = true
                requestVlcPlay(player)
                val generation = ++previewSeekGeneration
                postDelayed({
                    if (previewSeekActive && generation == previewSeekGeneration && !playWhenReady) {
                        runCatching { player.pause() }
                        playbackListener?.invoke(false)
                    }
                }, VLC_PREVIEW_SEEK_RENDER_MS)
            }
        }.onFailure { recoverFromVlcFailure(it) }
    }


    private fun openMedia3Player(preservePosition: Boolean = true) {
        val uri = source ?: return
        if (hostSuspended || !isAttachedToWindow) return
        releaseMedia3Player(keepPosition = preservePosition)
        releaseVlcPlayer(keepPosition = preservePosition, releaseCore = true)
        engine = PlaybackEngine.MEDIA3
        showActiveEngine()

        try {
            val compatibility = media3Profile == Media3Profile.COMPATIBILITY
            val smoothMotionPlayback = sourceFrameRate >= SMOOTH_MOTION_SOURCE_MIN
            val highFrameRatePlayback = isHighFrameRatePlayback()
            val veryHighFrameRateSlowMotion = sourceFrameRate >= VERY_HIGH_FRAME_RATE_SOURCE_MIN && speed <= 0.75f
            val demandingPlayback = playbackProfile.demandingDecode ||
                (sourceFrameRate >= 50f && sourceVideoWidth.toLong() * sourceVideoHeight.toLong() >= 3_840L * 2_160L)
            val lateDropThresholdUs = when {
                compatibility -> COMPATIBILITY_LATE_DROP_US
                veryHighFrameRateSlowMotion -> VERY_HIGH_FRAME_RATE_LATE_DROP_US
                highFrameRatePlayback -> HIGH_FRAME_RATE_LATE_DROP_US
                smoothMotionPlayback -> SMOOTH_MOTION_LATE_DROP_US
                else -> PERFORMANCE_LATE_DROP_US
            }
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setAllowedVideoJoiningTimeMs(if (compatibility) 25_000L else 15_000L)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .experimentalSetLateThresholdToDropDecoderInputUs(lateDropThresholdUs)
                .apply {
                    if (compatibility) forceDisableMediaCodecAsynchronousQueueing()
                    else forceEnableMediaCodecAsynchronousQueueing()
                }

            val minBufferMs = when {
                compatibility -> COMPATIBILITY_MIN_BUFFER_MS
                demandingPlayback -> DEMANDING_MIN_BUFFER_MS
                highFrameRatePlayback -> HIGH_FRAME_RATE_MIN_BUFFER_MS
                else -> PERFORMANCE_MIN_BUFFER_MS
            }
            val maxBufferMs = when {
                compatibility -> COMPATIBILITY_MAX_BUFFER_MS
                demandingPlayback -> DEMANDING_MAX_BUFFER_MS
                highFrameRatePlayback -> HIGH_FRAME_RATE_MAX_BUFFER_MS
                else -> PERFORMANCE_MAX_BUFFER_MS
            }
            val playbackBufferMs = when {
                compatibility -> COMPATIBILITY_PLAYBACK_BUFFER_MS
                demandingPlayback -> DEMANDING_PLAYBACK_BUFFER_MS
                highFrameRatePlayback -> HIGH_FRAME_RATE_PLAYBACK_BUFFER_MS
                else -> PERFORMANCE_PLAYBACK_BUFFER_MS
            }
            val rebufferMs = when {
                compatibility -> COMPATIBILITY_REBUFFER_MS
                demandingPlayback -> DEMANDING_REBUFFER_MS
                highFrameRatePlayback -> HIGH_FRAME_RATE_REBUFFER_MS
                else -> PERFORMANCE_REBUFFER_MS
            }
            val backBufferMs = if (highFrameRatePlayback || demandingPlayback) HIGH_FRAME_RATE_BACK_BUFFER_MS else BACK_BUFFER_MS
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxBufferMs, playbackBufferMs, rebufferMs)
                .setBackBuffer(backBufferMs, !highFrameRatePlayback)
                .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
                .build()

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(true)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
                .build()

            player.addListener(media3Listener)
            player.setSeekParameters(SeekParameters.EXACT)
            player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
            if (previewSeekActive) player.setScrubbingModeEnabled(true)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            player.playbackParameters = PlaybackParameters(speed)
            firstFrameRendered = false
            vlcVideoOutputReady = false
            media3Player = player
            playerView.player = player
            player.setMediaItem(MediaItem.fromUri(uri))
            if (pendingSeekMs > 0L) {
                if (pendingSeekFast) player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                player.seekTo(pendingSeekMs)
                player.setSeekParameters(SeekParameters.EXACT)
            }
            player.playWhenReady = playWhenReady || previewFrameRequested
            player.prepare()
            recoveryTransitionPending = false
            notifyEngineChanged()
            Log.i(TAG, "Media3 aberto em ${media3Profile.name}: fps=$sourceFrameRate speed=$speed")
            post { applySurfaceFrameRate() }
        } catch (throwable: Throwable) {
            recoveryTransitionPending = false
            prepared = false
            playbackListener?.invoke(false)
            recoverFromMedia3Failure(throwable)
        }
    }

    private fun recoverFromMedia3Failure(cause: Throwable?): Boolean {
        captureMedia3State()
        return advanceRecovery(cause)
    }

    private fun recoverFromVlcFailure(cause: Throwable?): Boolean {
        captureVlcState()
        return advanceRecovery(cause)
    }

    private fun advanceRecovery(cause: Throwable?): Boolean {
        if (recoveryTransitionPending) return true
        recoveryTransitionPending = true
        cancelBufferingRecovery()
        val demandingHardwarePlayback = playbackProfile.demandingDecode ||
            (sourceFrameRate >= 50f && sourceVideoWidth.toLong() * sourceVideoHeight.toLong() >= 3_840L * 2_160L)
        return when (val next = recoveryPolicy.advanceAfterFailure(skipVlcSoftware = demandingHardwarePlayback)) {
            PlaybackRecoveryPolicy.Stage.VLC_SOFTWARE -> {
                Log.w(TAG, "VLC alternando para decodificação por software", cause)
                engine = PlaybackEngine.VLC
                notifyEngineChanged()
                post { requestEngineOpen(preservePosition = true) }
                true
            }

            PlaybackRecoveryPolicy.Stage.MEDIA3_PERFORMANCE,
            PlaybackRecoveryPolicy.Stage.MEDIA3_COMPATIBILITY -> {
                media3Profile = if (next == PlaybackRecoveryPolicy.Stage.MEDIA3_PERFORMANCE) {
                    Media3Profile.PERFORMANCE
                } else {
                    Media3Profile.COMPATIBILITY
                }
                engine = PlaybackEngine.MEDIA3
                notifyEngineChanged()
                Log.w(TAG, "Alternando para ${engineDescription()}", cause)
                post { requestEngineOpen(preservePosition = true) }
                true
            }

            PlaybackRecoveryPolicy.Stage.EXHAUSTED -> {
                recoveryTransitionPending = false
                terminalFailure = true
                playWhenReady = false
                prepared = false
                releaseMedia3Player(keepPosition = true)
                releaseVlcPlayer(keepPosition = true, releaseCore = false)
                errorListener?.invoke(cause)
                false
            }

            PlaybackRecoveryPolicy.Stage.VLC_HARDWARE -> {
                engine = PlaybackEngine.VLC
                notifyEngineChanged()
                Log.w(TAG, "Alternando para VLC com aceleração de hardware", cause)
                post { requestEngineOpen(preservePosition = true) }
                true
            }
        }
    }

    private fun resetRecoveryForReplay() {
        recoveryPolicy.reset()
        media3Profile = Media3Profile.PERFORMANCE
        selectInitialEngine()
        playbackEnded = false
        terminalFailure = false
        recoveryTransitionPending = false
        surfaceRecoveryPending = false
        sameEngineSurfaceRecoveryAttempts = 0
        firstFrameRendered = false
        vlcVideoOutputReady = false
        vlcMediaAssigned = false
        vlcBufferPercentage = 0
        notifyEngineChanged()
    }

    private fun openVlcPlayer(preservePosition: Boolean = true) {
        val uri = source ?: return
        if (hostSuspended || !isAttachedToWindow) return
        releaseMedia3Player(keepPosition = preservePosition)
        releaseVlcPlayer(keepPosition = preservePosition, releaseCore = false)
        engine = PlaybackEngine.VLC
        replaceVlcVideoLayout()
        showActiveEngine()

        try {
            val cacheMs = effectiveVlcCacheMs()
            val core = libVlc ?: LibVLC(
                context.applicationContext,
                arrayListOf(
                    "--audio-time-stretch",
                    "--no-sub-autodetect-file",
                    "--file-caching=$cacheMs",
                    "--network-caching=$VLC_NETWORK_CACHING_MS",
                    "--live-caching=$VLC_NETWORK_CACHING_MS"
                )
            ).also { libVlc = it }
            val player = VlcMediaPlayer(core)
            val sessionId = ++vlcSessionId
            player.setEventListener(VlcMediaPlayer.EventListener { event: VlcMediaPlayer.Event ->
                val eventType = event.type
                val buffering = if (eventType == VlcMediaPlayer.Event.Buffering) event.buffering else 0f
                val voutCount = if (eventType == VlcMediaPlayer.Event.Vout) event.voutCount else 0
                val timeChangedMs = if (eventType == VlcMediaPlayer.Event.TimeChanged) event.timeChanged else -1L
                post {
                    if (sessionId == vlcSessionId) handleVlcEvent(eventType, buffering, voutCount, timeChangedMs)
                }
            })
            vlcPlayer = player
            vlcMediaAssigned = false
            vlcVideoOutputReady = false
            firstFrameRendered = false
            player.attachViews(vlcVideoLayout, null, false, false)
            recoveryTransitionPending = false
            notifyEngineChanged()
            waitForVlcSurface(sessionId, player, core, uri, attempt = 0)
            Log.i(TAG, "LibVLC aguardando SurfaceView: stage=${recoveryPolicy.stage} fps=$sourceFrameRate speed=$speed")
        } catch (throwable: Throwable) {
            recoveryTransitionPending = false
            surfaceRecoveryPending = false
            prepared = false
            playbackListener?.invoke(false)
            recoverFromVlcFailure(throwable)
        }
    }

    private fun waitForVlcSurface(
        sessionId: Long,
        player: VlcMediaPlayer,
        core: LibVLC,
        uri: Uri,
        attempt: Int
    ) {
        val waitGeneration = surfaceWaitGeneration
        if (sessionId != vlcSessionId || player !== vlcPlayer || hostSuspended || source != uri) return
        val surface = findSurfaceView(vlcVideoLayout)?.holder?.surface
        if (surface?.isValid == true && vlcVideoLayout.width > 0 && vlcVideoLayout.height > 0) {
            startVlcMediaOnReadySurface(sessionId, player, core, uri)
            return
        }
        if (attempt >= VLC_SURFACE_WAIT_ATTEMPTS) {
            recoverMissingVideoOutput(IllegalStateException("A SurfaceView do VLC não ficou disponível"))
            return
        }
        postDelayed({
            if (waitGeneration == surfaceWaitGeneration) {
                waitForVlcSurface(sessionId, player, core, uri, attempt + 1)
            }
        }, VLC_SURFACE_WAIT_STEP_MS)
    }

    private fun startVlcMediaOnReadySurface(
        sessionId: Long,
        player: VlcMediaPlayer,
        core: LibVLC,
        uri: Uri
    ) {
        if (sessionId != vlcSessionId || player !== vlcPlayer || hostSuspended || source != uri) return
        runCatching {
            val media = createVlcMedia(core, uri)
            player.media = media
            media.release()
            vlcMediaAssigned = true
            runCatching { player.setAspectRatio(null) }
            runCatching { player.setScale(0f) }
            applyVlcPlaybackSpeed()
            applyVlcVideoLayout()
            surfaceRecoveryPending = false
            if (playWhenReady || previewFrameRequested) requestVlcPlay(player)
            post { applySurfaceFrameRate() }
        }.onFailure { throwable ->
            vlcMediaAssigned = false
            recoverFromVlcFailure(throwable)
        }
    }

    private fun createVlcMedia(core: LibVLC, uri: Uri): VlcMedia {
        val softwareMode = recoveryPolicy.stage == PlaybackRecoveryPolicy.Stage.VLC_SOFTWARE
        return VlcMedia(core, uri).apply {
            setHWDecoderEnabled(!softwareMode, !softwareMode)
            addOption(":file-caching=${effectiveVlcCacheMs()}")
            addOption(":network-caching=$VLC_NETWORK_CACHING_MS")
            addOption(":live-caching=$VLC_NETWORK_CACHING_MS")
            val adaptiveDrop = playbackSettings.dropLateFrames &&
                (isHighFrameRatePlayback() || softwareMode || playbackProfile.cadenceUnstable)
            if (adaptiveDrop) addOption(":drop-late-frames") else addOption(":no-drop-late-frames")
            if (softwareMode) addOption(":skip-frames") else addOption(":no-skip-frames")
            addOption(":no-sub-autodetect-file")
        }
    }

    private fun effectiveVlcCacheMs(): Int {
        val configured = playbackSettings.fileCacheMs
        val intelligent = if (
            playbackSettings.prebuffer4k60 || playbackProfile.cadenceUnstable || isHighFrameRatePlayback()
        ) playbackProfile.recommendedCacheMs else 0
        return max(configured, intelligent).coerceIn(250, 5_000)
    }

    private fun restartCurrentVlcMedia(startPositionMs: Long = 0L): Boolean {
        val uri = source ?: return false
        val core = libVlc ?: return false
        val player = vlcPlayer ?: return false
        if (!isVlcSurfaceValid()) return false
        return runCatching {
            cancelBufferingRecovery()
            pendingSeekMs = startPositionMs.coerceAtLeast(0L)
            prepared = false
            playbackEnded = false
            terminalFailure = false
            vlcBuffering = true
            runCatching { player.stop() }
            val media = createVlcMedia(core, uri)
            player.media = media
            media.release()
            vlcMediaAssigned = true
            vlcVideoOutputReady = false
            firstFrameRendered = false
            applyVlcPlaybackSpeed()
            applyVlcVideoLayout()
            requestVlcPlay(player)
        }.getOrElse { throwable ->
            Log.w(TAG, "Falha ao reiniciar a mídia no player VLC atual", throwable)
            false
        }
    }

    private fun requestVlcPlay(player: VlcMediaPlayer): Boolean {
        if (player !== vlcPlayer) return false
        if (!vlcMediaAssigned) {
            return true
        }
        armFirstFrameWatchdog()
        return runCatching { player.play() }
            .fold(
                onSuccess = { true },
                onFailure = { throwable ->
                    recoverFromVlcFailure(throwable)
                    false
                }
            )
    }

    private fun scheduleBufferingRecovery() {
        if (!playWhenReady || bufferingRecoveryScheduled || recoveryTransitionPending) return
        bufferingRecoveryScheduled = true
        val generation = ++bufferingGeneration
        bufferingStartedAtMs = SystemClock.uptimeMillis()
        val delay = when {
            engine == PlaybackEngine.MEDIA3 && media3Profile == Media3Profile.PERFORMANCE -> PERFORMANCE_RECOVERY_DELAY_MS
            engine == PlaybackEngine.MEDIA3 -> COMPATIBILITY_RECOVERY_DELAY_MS
            recoveryPolicy.stage == PlaybackRecoveryPolicy.Stage.VLC_HARDWARE -> VLC_HARDWARE_RECOVERY_DELAY_MS
            else -> VLC_SOFTWARE_RECOVERY_DELAY_MS
        }
        postDelayed({
            if (generation != bufferingGeneration || !playWhenReady) return@postDelayed
            bufferingRecoveryScheduled = false
            val stalled = when (engine) {
                PlaybackEngine.MEDIA3 -> media3Player?.playbackState == Player.STATE_BUFFERING
                PlaybackEngine.VLC -> vlcBuffering
            }
            if (!stalled || SystemClock.uptimeMillis() - bufferingStartedAtMs < delay) return@postDelayed
            when (engine) {
                PlaybackEngine.MEDIA3 -> recoverFromMedia3Failure(null)
                PlaybackEngine.VLC -> recoverFromVlcFailure(null)
            }
        }, delay)
    }

    private fun cancelBufferingRecovery() {
        bufferingGeneration++
        bufferingStartedAtMs = 0L
        bufferingRecoveryScheduled = false
    }

    private fun captureCurrentState() {
        when (engine) {
            PlaybackEngine.MEDIA3 -> captureMedia3State()
            PlaybackEngine.VLC -> captureVlcState()
        }
    }

    private fun captureMedia3State() {
        media3Player?.let { player ->
            rememberPlaybackPosition(player.currentPosition)
            playWhenReady = player.playWhenReady || player.isPlaying || playWhenReady
        }
    }

    private fun captureVlcState() {
        vlcPlayer?.let { player ->
            rememberPlaybackPosition(runCatching { player.time }.getOrDefault(-1L))
            playWhenReady = runCatching { player.isPlaying }.getOrDefault(false) || playWhenReady
        }
    }

    private fun rememberPlaybackPosition(candidateMs: Long) {
        val valid = candidateMs.coerceAtLeast(0L)
        if (valid > 0L || pendingSeekMs <= 0L) pendingSeekMs = valid
    }

    private fun quickPlaybackProfile(metadata: SourceMetadata): PlaybackProfile {
        val fps = metadata.frameRate.takeIf { it > 0f } ?: 30f
        val demanding = metadata.width.toLong() * metadata.height.toLong() * fps.toDouble() >=
            1_920L * 1_080L * 90.0
        return PlaybackProfile(
            fps = metadata.frameRate,
            width = metadata.width,
            height = metadata.height,
            bitrateMbps = 0.0,
            cadenceScore = 100,
            largeGapCount = 0,
            jitterPercent = 0,
            demandingDecode = demanding,
            cadenceUnstable = false,
            recommendedCacheMs = if (demanding) 1_500 else 700
        )
    }

    private fun startSourceMetadataAnalysis(uri: Uri) {
        val generation = ++analysisGeneration
        playbackAnalysisExecutor.execute {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val metadata = readSourceMetadata(uri)
            post {
                if (generation != analysisGeneration || source != uri) return@post
                val wasHighFrameRate = isHighFrameRatePlayback()
                playbackProfile = quickPlaybackProfile(metadata)
                sourceFrameRate = metadata.frameRate
                sourceVideoWidth = metadata.width
                sourceVideoHeight = metadata.height
                analysisListener?.invoke(playbackProfile)
                applySurfaceFrameRate()
                val nowHighFrameRate = isHighFrameRatePlayback()
                if (!wasHighFrameRate && nowHighFrameRate && engine == PlaybackEngine.MEDIA3 && media3Player != null) {
                    // O player pode ter sido criado antes da leitura dos PTS. Reabrir
                    // preservando posição aplica thresholds/buffer específicos de 120/240.
                    captureCurrentState()
                    requestEngineOpen(preservePosition = true)
                }
                val delayMs = if (metadata.frameRate >= HIGH_FRAME_RATE_SOURCE_MIN) {
                    HIGH_FRAME_RATE_ANALYSIS_DELAY_MS
                } else {
                    STANDARD_ANALYSIS_DELAY_MS
                }
                postDelayed({
                    if (generation == analysisGeneration && source == uri && !hostSuspended && !playWhenReady && !isPlaying) {
                        startDetailedPlaybackAnalysis(uri, metadata)
                    }
                }, delayMs)
            }
        }
    }

    private fun startDetailedPlaybackAnalysis(uri: Uri, metadata: SourceMetadata) {
        if (!playbackSettings.intelligentPlayback) return
        val generation = ++analysisGeneration
        playbackAnalysisExecutor.execute {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val refined = PlaybackIntelligence.analyze(
                context.applicationContext,
                uri,
                metadata.frameRate,
                metadata.width,
                metadata.height
            )
            post {
                if (generation != analysisGeneration || source != uri) return@post
                playbackProfile = refined
                sourceFrameRate = refined.fps.takeIf { it > 0f } ?: metadata.frameRate
                sourceVideoWidth = refined.width.takeIf { it > 0 } ?: metadata.width
                sourceVideoHeight = refined.height.takeIf { it > 0 } ?: metadata.height
                analysisListener?.invoke(refined)
                applySurfaceFrameRate()
            }
        }
    }

    private fun readSourceMetadata(uri: Uri): SourceMetadata {
        val retriever = MediaMetadataRetriever()
        var frameRate = 0f
        var encodedWidth = 0
        var encodedHeight = 0
        var metadataRotation = 0
        try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(requireNotNull(uri.path))
            } else {
                retriever.setDataSource(context, uri)
            }
            frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE } ?: 0f
            encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            metadataRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            Unit
        } finally {
            runCatching { retriever.release() }
        }

        val extractorFrameRate = readExtractorFrameRate(uri)
        val measuredFrameRate = readMeasuredFrameRate(uri)
        val validRates = listOf(frameRate, extractorFrameRate, measuredFrameRate)
            .filter { it in MIN_FRAME_RATE..MAX_FRAME_RATE }
        frameRate = validRates.firstOrNull { it >= HIGH_FRAME_RATE_SOURCE_MIN }
            ?.let { validRates.maxOrNull() ?: it }
            ?: measuredFrameRate.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE }
            ?: frameRate.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE }
            ?: extractorFrameRate.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE }
            ?: 0f
        val physicallyPortrait = encodedHeight > encodedWidth
        val rotation = when {
            physicallyPortrait && metadataRotation == 0 -> 0
            metadataRotation != 0 -> metadataRotation
            else -> externalRotationDegrees
        }
        val rotated = ((rotation % 360) + 360) % 360 in setOf(90, 270)
        val displayWidth = if (rotated) encodedHeight else encodedWidth
        val displayHeight = if (rotated) encodedWidth else encodedHeight
        return SourceMetadata(frameRate, displayWidth, displayHeight)
    }

    private fun readExtractorFrameRate(uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(requireNotNull(uri.path))
            } else {
                extractor.setDataSource(context, uri, null)
            }
            (0 until extractor.trackCount).asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.getInteger(MediaFormat.KEY_FRAME_RATE)
                ?.toFloat()
                ?.takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE }
                ?: 0f
        } catch (_: Throwable) {
            0f
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun readMeasuredFrameRate(uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(requireNotNull(uri.path))
            } else {
                extractor.setDataSource(context, uri, null)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0f
            extractor.selectTrack(track)
            val presentationTimes = ArrayList<Long>(SOURCE_FPS_SAMPLE_COUNT)
            while (extractor.sampleTrackIndex >= 0 && presentationTimes.size < SOURCE_FPS_SAMPLE_COUNT) {
                val pts = extractor.sampleTime
                if (pts < 0L) break
                presentationTimes += pts
                if (!extractor.advance()) break
            }
            presentationTimes.sort()
            val positiveDeltas = ArrayList<Long>((presentationTimes.size - 1).coerceAtLeast(0))
            for (index in 1 until presentationTimes.size) {
                val delta = presentationTimes[index] - presentationTimes[index - 1]
                if (delta > 0L) positiveDeltas += delta
            }
            if (positiveDeltas.size < 8) return 0f
            positiveDeltas.sort()
            val medianDeltaUs = positiveDeltas[positiveDeltas.size / 2].coerceAtLeast(1L)
            (1_000_000f / medianDeltaUs.toFloat()).takeIf { it in MIN_FRAME_RATE..MAX_FRAME_RATE } ?: 0f
        } catch (_: Throwable) {
            0f
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun selectInitialEngine() {
        // Arquivos locais começam sempre no Media3, que usa diretamente o codec e
        // o relógio de exibição do Android. O VLC fica reservado para fallback real.
        recoveryPolicy.startAtMedia3()
        media3Profile = Media3Profile.PERFORMANCE
        engine = PlaybackEngine.MEDIA3
    }

    private fun isVeryHighFrameRateSlowMotion(): Boolean =
        sourceFrameRate >= VERY_HIGH_FRAME_RATE_SOURCE_MIN && speed <= 0.75f

    private fun isHighFrameRatePlayback(
        frameRate: Float = sourceFrameRate,
        playbackSpeed: Float = speed
    ): Boolean = frameRate >= HIGH_FRAME_RATE_SOURCE_MIN || frameRate * playbackSpeed >= HIGH_REFRESH_OUTPUT_MIN

    private fun applyVlcVideoLayout() {
        if (engine != PlaybackEngine.VLC) return
        val player = vlcPlayer ?: return
        val hostWidth = vlcVideoLayout.width
        val hostHeight = vlcVideoLayout.height
        if (hostWidth <= 0 || hostHeight <= 0) return

        runCatching { player.vlcVout.setWindowSize(hostWidth, hostHeight) }
        runCatching { player.setAspectRatio(null) }
        val scale = if (
            fillMode && sourceVideoWidth > 0 && sourceVideoHeight > 0
        ) {
            max(
                hostWidth.toFloat() / sourceVideoWidth.toFloat(),
                hostHeight.toFloat() / sourceVideoHeight.toFloat()
            )
        } else {
            0f
        }
        runCatching { player.setScale(scale) }
        vlcVideoLayout.invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0 || (width == oldWidth && height == oldHeight)) return
        post {
            applyVlcVideoLayout()
            applySurfaceFrameRate()
        }
    }

    private fun showActiveEngine() {
        val media3 = engine == PlaybackEngine.MEDIA3
        playerView.visibility = if (media3) View.VISIBLE else View.GONE
        vlcVideoLayout.visibility = if (media3) View.GONE else View.VISIBLE
        if (!media3) post { applyVlcVideoLayout() }
    }

    private fun notifyEngineChanged() {
        engineListener?.invoke(engineDescription())
    }

    private fun engineDescription(): String = when {
        engine == PlaybackEngine.MEDIA3 && media3Profile == Media3Profile.PERFORMANCE -> ENGINE_MEDIA3
        engine == PlaybackEngine.MEDIA3 -> ENGINE_MEDIA3_COMPATIBILITY
        recoveryPolicy.stage == PlaybackRecoveryPolicy.Stage.VLC_HARDWARE -> ENGINE_VLC_HARDWARE
        else -> ENGINE_VLC_SOFTWARE
    }

    private fun applyVlcPlaybackSpeed() {
        vlcPlayer?.let { player -> runCatching { player.rate = speed } }
    }

    private fun applySurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val root: View = if (engine == PlaybackEngine.MEDIA3) playerView else vlcVideoLayout
        val surface = findSurfaceView(root)?.holder?.surface?.takeIf { it.isValid } ?: return
        val requested = if (playbackProfile.cadenceUnstable && sourceFrameRate < HIGH_FRAME_RATE_SOURCE_MIN) 0f
        else normalizedDisplayFrameRate(sourceFrameRate * speed)
        runCatching {
            val compatibility = if (requested > 0f) {
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            } else {
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    requested.coerceAtLeast(0f),
                    compatibility,
                    if (requested >= HIGH_REFRESH_OUTPUT_MIN) Surface.CHANGE_FRAME_RATE_ALWAYS
                    else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(requested.coerceAtLeast(0f), compatibility)
            }
        }
    }

    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findSurfaceView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun normalizedDisplayFrameRate(rate: Float): Float = when {
        rate in 23f..25.5f -> 24f
        rate in 28f..31.5f -> 30f
        rate in 47f..51.5f -> 50f
        rate in 52f..61.5f -> 60f
        rate in 88f..91.5f -> 90f
        rate in 115f..121.5f -> 120f
        rate in 230f..241.5f -> 120f
        else -> 0f
    }

    private fun startHealthMonitor() {
        if (healthMonitorRunning || !playbackSettings.intelligentPlayback) return
        healthMonitorRunning = true
        healthLastPositionMs = currentPosition.toLong()
        healthLastCheckMs = SystemClock.uptimeMillis()
        healthStallSamples = 0
        removeCallbacks(playbackHealthMonitor)
        postDelayed(playbackHealthMonitor, HEALTH_CHECK_MS)
    }

    private fun stopHealthMonitor() {
        healthMonitorRunning = false
        healthStallSamples = 0
        removeCallbacks(playbackHealthMonitor)
    }

    fun release() {
        cancelPreviewRender()
        stopHealthMonitor()
        hostSuspended = false
        playWhenReady = false
        previewFrameRequested = false
        previewSeekActive = false
        pendingSeekMs = 0L
        pendingSeekFast = false
        recoveryTransitionPending = false
        zoomController.resetImmediately()
        releasePlayers(clearSource = true, keepPosition = false, releaseVlcCore = true)
    }

    private fun releasePlayers(clearSource: Boolean, keepPosition: Boolean, releaseVlcCore: Boolean) {
        cancelPreviewRender()
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        engineOpenGeneration++
        surfaceWaitGeneration++
        analysisGeneration++
        if (keepPosition) captureCurrentState()
        releaseMedia3Player(keepPosition)
        releaseVlcPlayer(keepPosition, releaseCore = releaseVlcCore)
        prepared = false
        playbackEnded = false
        terminalFailure = false
        playbackListener?.invoke(false)
        if (clearSource) source = null
        clearSurfaceFrameRate(playerView)
        clearSurfaceFrameRate(vlcVideoLayout)
    }

    private fun releaseMedia3Player(keepPosition: Boolean) {
        media3Player?.let { player ->
            if (keepPosition) {
                rememberPlaybackPosition(player.currentPosition)
                playWhenReady = player.playWhenReady || player.isPlaying || playWhenReady
            }
            player.removeListener(media3Listener)
            runCatching { player.setScrubbingModeEnabled(false) }
            player.release()
        }
        playerView.player = null
        media3Player = null
    }

    private fun releaseVlcPlayer(keepPosition: Boolean, releaseCore: Boolean) {
        vlcSessionId++
        surfaceWaitGeneration++
        vlcPlayer?.let { player ->
            if (keepPosition) {
                rememberPlaybackPosition(runCatching { player.time }.getOrDefault(-1L))
                playWhenReady = runCatching { player.isPlaying }.getOrDefault(false) || playWhenReady
            }
            runCatching { player.setEventListener(null) }
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            runCatching { player.release() }
        }
        vlcPlayer = null
        vlcMediaAssigned = false
        vlcVideoOutputReady = false
        vlcBuffering = false
        removeVlcVideoLayoutFromHost()
        if (releaseCore) {
            runCatching { libVlc?.release() }
            libVlc = null
        }
    }

    private fun clearSurfaceFrameRate(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = findSurfaceView(root)?.holder?.surface?.takeIf { it.isValid } ?: return
        runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    override fun start() {
        if (source == null) return
        hostSuspended = false
        playWhenReady = true
        previewFrameRequested = false
        startHealthMonitor()
        playbackListener?.invoke(true)

        if (playbackEnded) {
            pendingSeekMs = 0L
            resetRecoveryForReplay()
            val replayed = when (engine) {
                PlaybackEngine.MEDIA3 -> media3Player?.let { player ->
                    runCatching {
                        player.seekTo(0L)
                        player.playWhenReady = true
                        player.play()
                        prepared = true
                        playbackEnded = false
                        firstFrameRendered = false
                        armFirstFrameWatchdog()
                        true
                    }.getOrDefault(false)
                } ?: false
                PlaybackEngine.VLC -> restartCurrentVlcMedia(0L)
            }
            if (!replayed) requestEngineOpen(preservePosition = false)
            return
        }
        if (terminalFailure) {
            resetRecoveryForReplay()
            requestEngineOpen(preservePosition = true)
            return
        }

        when (engine) {
            PlaybackEngine.MEDIA3 -> {
                val player = media3Player
                if (player == null) requestEngineOpen(preservePosition = true)
                else {
                    runCatching {
                        player.playWhenReady = true
                        player.play()
                    }.onFailure { recoverFromMedia3Failure(it) }
                }
            }

            PlaybackEngine.VLC -> {
                val player = vlcPlayer
                if (player == null) requestEngineOpen(preservePosition = true)
                else requestVlcPlay(player)
            }
        }
        applySurfaceFrameRate()
    }

    override fun pause() {
        playWhenReady = false
        if (!previewSeekActive) previewFrameRequested = false
        cancelBufferingRecovery()
        if (!previewSeekActive) cancelFirstFrameWatchdog()
        when (engine) {
            PlaybackEngine.MEDIA3 -> runCatching { media3Player?.pause() }
            PlaybackEngine.VLC -> runCatching { vlcPlayer?.pause() }
        }
        playbackListener?.invoke(false)
    }

    override fun getDuration(): Int {
        val value = when (engine) {
            PlaybackEngine.MEDIA3 -> media3Player?.duration ?: C.TIME_UNSET
            PlaybackEngine.VLC -> runCatching { vlcPlayer?.length ?: 0L }.getOrDefault(0L)
        }
        return if (value == C.TIME_UNSET || value < 0L) 0 else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun getCurrentPosition(): Int {
        val value = when (engine) {
            PlaybackEngine.MEDIA3 -> media3Player?.currentPosition ?: pendingSeekMs
            PlaybackEngine.VLC -> runCatching { vlcPlayer?.time ?: pendingSeekMs }.getOrDefault(pendingSeekMs)
        }
        return value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun seekTo(position: Int) {
        seekInternal(position)
    }

    fun seekFromTimeline(position: Int, resumePlayback: Boolean, precise: Boolean = false) {
        val target = position.coerceAtLeast(0).toLong()
        cancelPreviewRender()
        previewSeekActive = false
        previewFrameRequested = false
        pendingSeekMs = target
        pendingSeekFast = !precise
        playbackEnded = false
        terminalFailure = false
        playWhenReady = resumePlayback
        cancelBufferingRecovery()
        cancelFirstFrameWatchdog()
        healthLastPositionMs = target
        healthLastCheckMs = SystemClock.uptimeMillis()
        healthStallSamples = 0

        when (engine) {
            PlaybackEngine.MEDIA3 -> {
                val player = media3Player
                if (player == null) {
                    requestEngineOpen(preservePosition = true)
                } else {
                    val seekParameters = if (precise) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
                    runCatching {
                        player.setScrubbingModeEnabled(false)
                        player.setSeekParameters(seekParameters)
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.seekTo(target)
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.playWhenReady = resumePlayback
                        if (resumePlayback) {
                            player.play()
                            startHealthMonitor()
                        } else {
                            player.pause()
                        }
                    }.onFailure { recoverFromMedia3Failure(it) }
                }
            }

            PlaybackEngine.VLC -> {
                        val player = vlcPlayer
                if (player == null) {
                    requestEngineOpen(preservePosition = true)
                } else {
                    runCatching {
                        player.setTime(target, !precise)
                        if (resumePlayback) {
                            requestVlcPlay(player)
                            startHealthMonitor()
                        } else {
                            previewFrameRequested = true
                            requestVlcPlay(player)
                            val generation = ++previewSeekGeneration
                            postDelayed({
                                if (generation == previewSeekGeneration && !playWhenReady) {
                                    previewFrameRequested = false
                                    runCatching { player.pause() }
                                    playbackListener?.invoke(false)
                                }
                            }, VLC_PREVIEW_SEEK_RENDER_MS)
                        }
                    }.onFailure { recoverFromVlcFailure(it) }
                }
            }
        }
        playbackListener?.invoke(resumePlayback)
    }

    private fun seekInternal(position: Int) {
        val target = position.coerceAtLeast(0).toLong()
        pendingSeekMs = target
        pendingSeekFast = false
        if (playbackEnded) {
            terminalFailure = false
            playbackEnded = false
            when (engine) {
                PlaybackEngine.MEDIA3 -> {
                    media3Player?.let { player ->
                        runCatching {
                            if (!playWhenReady) {
                                player.pause()
                                player.playWhenReady = false
                            }
                            player.seekTo(target)
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            prepared = true
                        }.onFailure { recoverFromMedia3Failure(it) }
                    } ?: requestEngineOpen(preservePosition = false)
                }
                PlaybackEngine.VLC -> {
                    previewFrameRequested = !playWhenReady
                    if (!restartCurrentVlcMedia(target)) requestEngineOpen(preservePosition = false)
                }
            }
            return
        }
        when (engine) {
            PlaybackEngine.MEDIA3 -> runCatching {
                val player = media3Player
                if (player == null) requestEngineOpen(preservePosition = true)
                else {
                    player.seekTo(target)
                    if (!playWhenReady && player.playbackState == Player.STATE_IDLE) player.prepare()
                }
            }.onFailure { recoverFromMedia3Failure(it) }
            PlaybackEngine.VLC -> runCatching {
                val player = vlcPlayer
                if (player == null) requestEngineOpen(preservePosition = true)
                else player.time = target
            }.onFailure { recoverFromVlcFailure(it) }
        }
    }

    override fun isPlaying(): Boolean = when (engine) {
        PlaybackEngine.MEDIA3 -> media3Player?.isPlaying == true
        PlaybackEngine.VLC -> runCatching { vlcPlayer?.isPlaying == true }.getOrDefault(false)
    }

    override fun getBufferPercentage(): Int = when (engine) {
        PlaybackEngine.MEDIA3 -> media3Player?.bufferedPercentage ?: 0
        PlaybackEngine.VLC -> vlcBufferPercentage
    }

    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = true
    override fun canSeekForward(): Boolean = true
    override fun getAudioSessionId(): Int = if (engine == PlaybackEngine.MEDIA3) media3Player?.audioSessionId ?: 0 else 0

    private data class SourceMetadata(val frameRate: Float, val width: Int, val height: Int)
    private enum class PlaybackEngine { MEDIA3, VLC }
    private enum class Media3Profile { PERFORMANCE, COMPATIBILITY }

    companion object {
        private val playbackAnalysisExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "SteadyVaultPlaybackAnalysis").apply { isDaemon = true }
        }
        private const val MAX_ZOOM = 6f
        private const val MIN_FRAME_RATE = 1f
        private const val MAX_FRAME_RATE = 250f
        private const val TAG = "SteadyVaultPlayer"

        private const val PERFORMANCE_MIN_BUFFER_MS = 5_000
        private const val PERFORMANCE_MAX_BUFFER_MS = 45_000
        private const val PERFORMANCE_PLAYBACK_BUFFER_MS = 250
        private const val PERFORMANCE_REBUFFER_MS = 250
        private const val DEMANDING_MIN_BUFFER_MS = 8_000
        private const val DEMANDING_MAX_BUFFER_MS = 60_000
        private const val DEMANDING_PLAYBACK_BUFFER_MS = 500
        private const val DEMANDING_REBUFFER_MS = 500
        private const val HIGH_FRAME_RATE_MIN_BUFFER_MS = 4_000
        private const val HIGH_FRAME_RATE_MAX_BUFFER_MS = 25_000
        private const val HIGH_FRAME_RATE_PLAYBACK_BUFFER_MS = 300
        private const val HIGH_FRAME_RATE_REBUFFER_MS = 300
        private const val COMPATIBILITY_MIN_BUFFER_MS = 10_000
        private const val COMPATIBILITY_MAX_BUFFER_MS = 75_000
        private const val COMPATIBILITY_PLAYBACK_BUFFER_MS = 500
        private const val COMPATIBILITY_REBUFFER_MS = 500
        private const val BACK_BUFFER_MS = 10_000
        private const val HIGH_FRAME_RATE_BACK_BUFFER_MS = 1_000
        private const val PERFORMANCE_LATE_DROP_US = 75_000L
        private const val SMOOTH_MOTION_LATE_DROP_US = 18_000L
        private const val HIGH_FRAME_RATE_LATE_DROP_US = 12_000L
        private const val VERY_HIGH_FRAME_RATE_LATE_DROP_US = 24_000L
        private const val COMPATIBILITY_LATE_DROP_US = 150_000L
        private const val SMOOTH_MOTION_SOURCE_MIN = 50f
        private const val HIGH_FRAME_RATE_SOURCE_MIN = 115f
        private const val VERY_HIGH_FRAME_RATE_SOURCE_MIN = 180f
        private const val HIGH_REFRESH_OUTPUT_MIN = 90f
        private const val SOURCE_FPS_SAMPLE_COUNT = 120
        private const val STANDARD_ANALYSIS_DELAY_MS = 2_000L
        private const val HIGH_FRAME_RATE_ANALYSIS_DELAY_MS = 5_000L

        private const val PERFORMANCE_RECOVERY_DELAY_MS = 5_000L
        private const val COMPATIBILITY_RECOVERY_DELAY_MS = 7_000L
        private const val VLC_HARDWARE_RECOVERY_DELAY_MS = 8_000L
        private const val VLC_SOFTWARE_RECOVERY_DELAY_MS = 12_000L
        private const val VLC_NETWORK_CACHING_MS = 3_000
        private const val HEALTH_CHECK_MS = 1_000L
        private const val HEALTH_STALL_SAMPLES = 4
        private const val HEALTH_HFR_STALL_SAMPLES = 6
        private const val HEALTH_END_GUARD_MS = 1_500L
        private const val ENGINE_OPEN_WAIT_ATTEMPTS = 100
        private const val ENGINE_OPEN_WAIT_STEP_MS = 25L
        private const val VLC_SURFACE_WAIT_ATTEMPTS = 80
        private const val VLC_SURFACE_WAIT_STEP_MS = 25L
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val FIRST_FRAME_TIMEOUT_DEMANDING_MS = 9_000L
        private const val MAX_SAME_ENGINE_SURFACE_RECOVERIES = 1
        private const val SURFACE_RECONNECT_DELAY_MS = 120L
        private const val VLC_FIRST_FRAME_CONFIRM_DELAY_MS = 350L
        private const val PREVIEW_FRAME_PAUSE_DELAY_MS = 120L
        private const val VLC_PREVIEW_SEEK_RENDER_MS = 48L

        const val ENGINE_MEDIA3 = "Media3"
        const val ENGINE_MEDIA3_COMPATIBILITY = "Media3 compatibilidade"
        const val ENGINE_VLC_HARDWARE = "VLC hardware"
        const val ENGINE_VLC_SOFTWARE = "VLC software"
    }
}
