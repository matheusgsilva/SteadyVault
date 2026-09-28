package com.steadyvault.camera.ui.capture

import com.steadyvault.camera.photo.service.PhotoService
import com.steadyvault.camera.capture.service.CaptureService
import com.steadyvault.camera.capture.service.RecordingServiceRouter
import com.steadyvault.camera.core.capability.CaptureCapabilityMatrix
import com.steadyvault.camera.core.capability.CaptureModeCatalog
import com.steadyvault.camera.core.capability.HardwareSupportPolicy
import com.steadyvault.camera.core.capability.HardwareSupportPolicy.Support
import com.steadyvault.camera.core.capability.PowerPolicy
import com.steadyvault.camera.core.camera.CameraLensCatalog
import com.steadyvault.camera.core.camera.CameraResourceCoordinator
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.RecordingDisplayPreferences
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.SensorPixelModeSettings
import com.steadyvault.camera.core.settings.CameraProfileStore
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.state.CapturePhase
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.state.PhotoCaptureStateStore
import com.steadyvault.camera.core.storage.RecordingStorageGuard
import com.steadyvault.camera.core.validation.UiBehaviorRules
import com.steadyvault.camera.core.state.VideoProcessingStateStore
import com.steadyvault.camera.processing.service.VideoProcessingService
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.settings.SettingsActivity
import com.steadyvault.camera.ui.navigation.BottomNavigation
import com.steadyvault.camera.ui.vault.PrimaryVaultActivity
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.widgets.CompactControlWidget
import com.steadyvault.camera.widgets.ExpandedControlWidget
import com.steadyvault.camera.widgets.WidgetPinnedReceiver

import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceStore

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class CaptureActivity : ComponentActivity() {

    private enum class PreviewSettingsTab { GENERAL, CAMERAS, PHOTO, VIDEO, PRO }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPermissionRequestCode = 0
    private val permissionRequestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val requestCode = pendingPermissionRequestCode
            pendingPermissionRequestCode = 0
            handlePermissionResult(requestCode)
        }

    private val hideFocusIndicatorRunnable = Runnable {
        if (::previewFocusIndicator.isInitialized) {
            previewFocusIndicator.animate().cancel()
            previewFocusIndicator.animate().alpha(0f).setDuration(160L).withEndAction {
                previewFocusIndicator.visibility = View.GONE
            }.start()
        }
    }
    private val hidePreviewTransitionRunnable = Runnable { hidePreviewTransitionCover(animated = true) }
    private val previewUpdateRunnable = Runnable { applyPendingPreviewUpdate() }

    private lateinit var mainRoot: View
    private lateinit var bottomNavigationRoot: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var modeSummaryText: TextView
    private lateinit var modeInfoText: TextView
    private lateinit var fps30Button: TextView
    private lateinit var fps60Button: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var startButton: View
    private lateinit var stopButton: View
    private lateinit var photoButton: View
    private lateinit var burstButton: View
    private lateinit var batteryButton: View
    private lateinit var batteryButtonText: TextView
    private lateinit var widgetButton: View
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewTexture: SurfaceView
    private lateinit var previewTopPanel: View
    private lateinit var previewBottomPanel: View
    private lateinit var previewModeBadge: TextView
    private lateinit var previewHideTopButton: ImageView
    private lateinit var previewShowTopButton: ImageView
    private lateinit var previewModeButton: TextView
    private lateinit var backgroundRecordingZoomButton: TextView
    private lateinit var previewOverlayButton: ImageView
    private lateinit var previewQuickCloseButton: ImageView
    private lateinit var previewSettingsButton: ImageView
    private lateinit var previewPhotoModeButton: TextView
    private lateinit var previewVideoModeButton: TextView
    private lateinit var previewFpsRow: View
    private lateinit var previewSettingsText: TextView
    private lateinit var previewPerformanceText: TextView
    private lateinit var previewPausedScrim: View
    private lateinit var previewPausedText: TextView
    private lateinit var previewFocusIndicator: View
    private lateinit var previewGridOverlay: View
    private var previewTransitionCover: ImageView? = null
    private var previewTransitionBitmap: Bitmap? = null
    private var previewTransitionPending = false
    private var previewModeTransitionTarget: Boolean? = null
    private var previewBufferWidth = 0
    private var previewBufferHeight = 0
    private lateinit var previewRecordButton: View
    private lateinit var previewRecordFill: View
    private lateinit var previewPhotoButton: View
    private lateinit var previewCameraSwitchButton: ImageView
    private lateinit var previewLastMediaButton: View
    private lateinit var previewLastMediaThumbnail: ImageView
    private lateinit var previewLastMediaPlay: ImageView
    private lateinit var previewFps30Button: TextView
    private lateinit var previewFps60Button: TextView
    private lateinit var previewZoom06Button: TextView
    private lateinit var previewZoom1Button: TextView
    private lateinit var previewZoom3Button: TextView
    private lateinit var previewZoom5Button: TextView
    private lateinit var previewSettingsScrim: View
    private lateinit var previewSettingsSheet: View
    private lateinit var previewSettingsGeneralTab: LinearLayout
    private lateinit var previewSettingsCamerasTab: LinearLayout
    private lateinit var previewSettingsPhotoTab: LinearLayout
    private lateinit var previewSettingsVideoTab: LinearLayout
    private lateinit var previewSettingsProTab: LinearLayout
    private lateinit var previewSettingsGeneralTabText: TextView
    private lateinit var previewSettingsCamerasTabText: TextView
    private lateinit var previewSettingsPhotoTabText: TextView
    private lateinit var previewSettingsVideoTabText: TextView
    private lateinit var previewSettingsProTabText: TextView
    private lateinit var previewSettingsGeneralTabIcon: ImageView
    private lateinit var previewSettingsCamerasTabIcon: ImageView
    private lateinit var previewSettingsPhotoTabIcon: ImageView
    private lateinit var previewSettingsVideoTabIcon: ImageView
    private lateinit var previewSettingsProTabIcon: ImageView
    private lateinit var previewSettingsContentTitle: TextView
    private lateinit var previewSettingsContentSubtitle: TextView
    private lateinit var previewCameraProfilesScroll: ScrollView
    private lateinit var previewCameraProfilesRow: LinearLayout
    private lateinit var previewSettingsActionsScroll: View
    private lateinit var previewSettingsActionsContainer: LinearLayout
    private lateinit var previewManageProfilesButton: TextView
    private lateinit var captureHeader: View
    private lateinit var captureStatusCard: View
    private var previewSurface: Surface? = null
    private var previewOpen = false
    private var previewUserRequested = false
    private var previewTopControlsVisible = true
    private var previewPhotoMode = true
    private var previewSettingsTab = PreviewSettingsTab.GENERAL
    private var previewAeAfLocked = false
    private var previewFpsWindowStartedNs = 0L
    private var previewFpsLastTimestampNs = 0L
    private var previewFpsFrameCount = 0
    private var previewMeasuredFps = 0.0
    private var closePreviewAfterCapture = false
    private var pendingPreviewOpen = false
    private var recordingRequestedFromPreview = false
    private var recordingRequestedFromQuickShortcut = false
    private var photoRequestedFromPreview = false
    private var activePreviewVideoCapture = false
    private var activePreviewPhotoCapture = false
    private var pendingPhotoBurst = false
    private var previewDisabledForCurrentRecording = false
    private val previewCoordinatorToken = Any()
    private val thumbnailExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-CaptureThumbnail")
    }
    private val capabilityExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-CapabilityScan")
    }
    @Volatile private var latestCapturedMedia: VaultRepository.MediaItem? = null
    private val thumbnailRefreshGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val idlePreview by lazy {
        IdleCameraPreviewController(
            context = this,
            onFailure = { message ->
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            },
            onPreviewFrame = { photoMode ->
                mainHandler.post { releasePreviewModeTransition(photoMode) }
            },
            onFrameTimestampNs = { timestampNs ->
                mainHandler.post { updatePreviewFpsMeter(timestampNs) }
            }
        )
    }

    private var receiversRegistered = false
    private var widgetPermissionRequestMode: String? = null
    private var pendingRecordingAfterOptimizationCancel = false
    private var pendingBlackScreenForRecording = false
    private var photoBusy = false
    private var initialPaddingLeft = 0
    private var initialPaddingTop = 0
    private var initialPaddingRight = 0
    private var initialPaddingBottom = 0
    private var initialNavigationPaddingLeft = 0
    private var initialNavigationPaddingTop = 0
    private var initialNavigationPaddingRight = 0
    private var initialNavigationPaddingBottom = 0
    private var reopenPreviewAfterSettings = false
    private var reopenPreviewPhotoMode = true
    private var navigatingToAdvancedSettings = false

    private var cameraOptions: List<CameraLensCatalog.Option> = emptyList()

    @Volatile
    private var capabilityMatrix: CaptureCapabilityMatrix.Matrix? = null

    @Volatile
    private var capabilityScanCompleted = false

    @Volatile
    private var capabilityScanInProgress = false

    private var capabilityRescanPending = false
    private var lastCapabilityScanStartedAtMs = 0L

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent
                ?.getStringExtra(CaptureService.EXTRA_MESSAGE)
                ?: return

            val widgetWasBusy = CaptureStateStore.isBusy(this@CaptureActivity)
            val phase = CapturePhase.from(intent.getStringExtra(CaptureService.EXTRA_PHASE))
                ?: CaptureStateStore.phaseForMessage(message)
            CaptureStateStore.update(
                context = this@CaptureActivity,
                state = message,
                phase = phase,
                sessionId = intent.getStringExtra(CaptureService.EXTRA_SESSION_ID).orEmpty()
            )
            renderState(message)
            updatePreviewTransitionForState(message)
            val busy = phase.busy
            if (pendingBlackScreenForRecording && message.startsWith("Gravando", ignoreCase = true)) {
                pendingBlackScreenForRecording = false
                openBlackScreen()
            }
            if (message.contains("Preview foi pausado", ignoreCase = true)) previewDisabledForCurrentRecording = true
            if (!busy) {
                pendingBlackScreenForRecording = false
                previewDisabledForCurrentRecording = false
                activePreviewVideoCapture = false
                capabilityMatrix = CaptureCapabilityMatrix.cached(this@CaptureActivity) ?: capabilityMatrix
                        refreshLastMediaThumbnail()
                cleanupHiddenPreviewIfIdle()
                if (capabilityRescanPending && !capabilityScanInProgress) {
                    refreshCapabilityMatrix()
                }
            }
            renderRecordingMode(busy = busy)
            renderPreviewCaptureState(message, busy)
            if (!busy && closePreviewAfterCapture && previewOpen && !photoBusy) {
                closeCameraPreview()
            } else if (!busy && previewOpen && !photoBusy) {
                mainHandler.postDelayed({ startIdlePreviewIfPossible() }, PREVIEW_RESTART_DELAY_MS)
            }
            if (widgetWasBusy != busy) {
                ExpandedControlWidget.updateRecordingControls(this@CaptureActivity)
            }
        }
    }

    private val photoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent
                ?.getStringExtra(PhotoService.EXTRA_MESSAGE)
                ?: return

            photoBusy = UiBehaviorRules.isPhotoProgress(message)
            if (message.contains("Preview foi pausado", ignoreCase = true)) activePreviewPhotoCapture = false
            if (UiBehaviorRules.isPhotoResult(message)) {
                photoBusy = false
                activePreviewPhotoCapture = false
                refreshLastMediaThumbnail()
                cleanupHiddenPreviewIfIdle()
                if (
                    capabilityRescanPending &&
                    !capabilityScanInProgress &&
                    !CaptureStateStore.isBusy(this@CaptureActivity)
                ) {
                    refreshCapabilityMatrix()
                }
            }
            renderTemporaryMessage(message)
            renderCaptureControls(
                recordingBusy = CaptureStateStore.isBusy(this@CaptureActivity),
                stopping = UiBehaviorRules.isRecordingFinalizing(CaptureStateStore.currentState(this@CaptureActivity))
            )
            renderPreviewCaptureState(message, CaptureStateStore.isBusy(this@CaptureActivity))
            if (!photoBusy && closePreviewAfterCapture && previewOpen && !CaptureStateStore.isBusy(this@CaptureActivity)) {
                closeCameraPreview()
            } else if (!photoBusy && previewOpen && !CaptureStateStore.isBusy(this@CaptureActivity)) {
                mainHandler.postDelayed({ startIdlePreviewIfPossible() }, PREVIEW_RESTART_DELAY_MS)
            }
            ExpandedControlWidget.updateAll(this@CaptureActivity)
        }
    }

    private val optimizationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(VideoProcessingService.EXTRA_STATE).orEmpty()
            val message = intent?.getStringExtra(VideoProcessingService.EXTRA_MESSAGE).orEmpty()
            val progress = intent?.getIntExtra(VideoProcessingService.EXTRA_PROGRESS, 0) ?: 0
            renderOptimizationState(state, message, progress)
            if (state != VideoProcessingService.STATE_PROGRESS) {
                mainHandler.postDelayed({ refreshIdleOrStoredState() }, OPTIMIZATION_RESULT_HOLD_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::previewSettingsSheet.isInitialized && previewSettingsSheet.visibility == View.VISIBLE) {
                    hidePreviewSettingsSheet()
                } else if (previewOpen) {
                    requestClosePreview()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setContentView(R.layout.activity_capture)
        BottomNavigation.bind(this, BottomNavigation.TAB_RECORD)

        mainRoot = findViewById(R.id.mainRoot)
        bottomNavigationRoot = findViewById(R.id.bottomNavigationRoot)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        modeSummaryText = findViewById(R.id.modeSummaryText)
        modeInfoText = findViewById(R.id.modeInfoText)
        fps30Button = findViewById(R.id.fps30Button)
        fps60Button = findViewById(R.id.fps60Button)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        photoButton = findViewById(R.id.photoButton)
        burstButton = findViewById(R.id.burstButton)
        batteryButton = findViewById(R.id.batteryButton)
        batteryButtonText = findViewById(R.id.batteryButtonText)
        widgetButton = findViewById(R.id.widgetButton)
        previewContainer = findViewById(R.id.cameraPreviewContainer)
        previewTexture = findViewById(R.id.cameraPreviewTexture)
        previewTexture.setZOrderOnTop(false)
        previewTexture.holder.setFormat(PixelFormat.OPAQUE)
        previewTopPanel = findViewById(R.id.previewTopPanel)
        previewBottomPanel = findViewById(R.id.previewBottomPanel)
        previewModeBadge = findViewById(R.id.previewModeBadge)
        previewHideTopButton = findViewById(R.id.previewHideTopButton)
        previewShowTopButton = findViewById(R.id.previewShowTopButton)
        previewModeButton = findViewById(R.id.previewModeButton)
        backgroundRecordingZoomButton = findViewById(R.id.backgroundRecordingZoomButton)
        previewOverlayButton = findViewById(R.id.previewOverlayButton)
        previewQuickCloseButton = findViewById(R.id.previewQuickCloseButton)
        previewSettingsButton = findViewById(R.id.previewSettingsButton)
        previewPhotoModeButton = findViewById(R.id.previewPhotoModeButton)
        previewVideoModeButton = findViewById(R.id.previewVideoModeButton)
        previewFpsRow = findViewById(R.id.previewFpsRow)
        previewSettingsText = findViewById(R.id.previewSettingsText)
        previewPerformanceText = findViewById(R.id.previewPerformanceText)
        previewPausedScrim = findViewById(R.id.previewPausedScrim)
        previewPausedText = findViewById(R.id.previewPausedText)
        previewGridOverlay = findViewById(R.id.previewGridOverlay)
        previewGridOverlay.visibility = if (isPreviewGridEnabled()) View.VISIBLE else View.GONE
        previewRecordButton = findViewById(R.id.previewRecordButton)
        previewRecordFill = findViewById(R.id.previewRecordFill)
        previewPhotoButton = findViewById(R.id.previewPhotoButton)
        previewCameraSwitchButton = findViewById(R.id.previewCameraSwitchButton)
        previewLastMediaButton = findViewById(R.id.previewLastMediaButton)
        previewLastMediaThumbnail = findViewById(R.id.previewLastMediaThumbnail)
        previewLastMediaPlay = findViewById(R.id.previewLastMediaPlay)
        previewFps30Button = findViewById(R.id.previewFps30Button)
        previewFps60Button = findViewById(R.id.previewFps60Button)
        previewZoom06Button = findViewById(R.id.previewZoom06Button)
        previewZoom1Button = findViewById(R.id.previewZoom1Button)
        previewZoom3Button = findViewById(R.id.previewZoom3Button)
        previewZoom5Button = findViewById(R.id.previewZoom5Button)
        previewSettingsScrim = findViewById(R.id.previewSettingsScrim)
        previewSettingsSheet = findViewById(R.id.previewSettingsSheet)
        previewSettingsGeneralTab = findViewById(R.id.previewSettingsGeneralTab)
        previewSettingsCamerasTab = findViewById(R.id.previewSettingsCamerasTab)
        previewSettingsPhotoTab = findViewById(R.id.previewSettingsPhotoTab)
        previewSettingsVideoTab = findViewById(R.id.previewSettingsVideoTab)
        previewSettingsProTab = findViewById(R.id.previewSettingsProTab)
        previewSettingsGeneralTabText = findViewById(R.id.previewSettingsGeneralTabText)
        previewSettingsCamerasTabText = findViewById(R.id.previewSettingsCamerasTabText)
        previewSettingsPhotoTabText = findViewById(R.id.previewSettingsPhotoTabText)
        previewSettingsVideoTabText = findViewById(R.id.previewSettingsVideoTabText)
        previewSettingsProTabText = findViewById(R.id.previewSettingsProTabText)
        previewSettingsGeneralTabIcon = findViewById(R.id.previewSettingsGeneralTabIcon)
        previewSettingsCamerasTabIcon = findViewById(R.id.previewSettingsCamerasTabIcon)
        previewSettingsPhotoTabIcon = findViewById(R.id.previewSettingsPhotoTabIcon)
        previewSettingsVideoTabIcon = findViewById(R.id.previewSettingsVideoTabIcon)
        previewSettingsProTabIcon = findViewById(R.id.previewSettingsProTabIcon)
        previewSettingsGeneralTabText.text = "Configurar"
        previewSettingsPhotoTab.visibility = View.GONE
        previewSettingsVideoTab.visibility = View.GONE
        previewSettingsProTab.visibility = View.GONE
        previewSettingsContentTitle = findViewById(R.id.previewSettingsContentTitle)
        previewSettingsContentSubtitle = findViewById(R.id.previewSettingsContentSubtitle)
        previewCameraProfilesScroll = findViewById(R.id.previewCameraProfilesScroll)
        previewCameraProfilesRow = findViewById(R.id.previewCameraProfilesRow)
        previewSettingsActionsScroll = findViewById(R.id.previewSettingsActionsScroll)
        previewSettingsActionsContainer = findViewById(R.id.previewSettingsActionsContainer)
        previewManageProfilesButton = findViewById(R.id.previewManageProfilesButton)
        captureHeader = findViewById(R.id.captureHeader)
        captureStatusCard = findViewById(R.id.captureStatusCard)
        val allCameraOptions = CameraLensCatalog.options(this)
        cameraOptions = allCameraOptions.filter { it.isFront || it.logical }
        CaptureSettings.snapshot(this).let { current ->
            val storedOption = allCameraOptions.firstOrNull { it.id == current.selectedCameraId }
            val selectedId = when {
                storedOption?.isFront == true -> storedOption.id
                else -> CameraLensCatalog.resolveRecordingCameraId(this, current.selectedCameraId, current.resolution)
            } ?: cameraOptions.firstOrNull()?.id
            val migratedZoom = if (storedOption?.isBack == true && !storedOption.logical) {
                CameraLensCatalog.shortcutRatio(storedOption)
            } else current.zoomRatio
            val normalized = current.copy(
                previewMode = CaptureSettings.PREVIEW_OFF,
                selectedCameraId = selectedId,
                zoomRatio = migratedZoom
            )
            CameraProfileStore.setActiveMode(this, CameraProfileStore.FunctionMode.VIDEO)
            cameraOptions.forEach { CameraProfileStore.ensureProfiles(this, it.id, normalized.copy(selectedCameraId = it.id)) }
            if (selectedId != null) {
                CameraProfileStore.rememberCamera(this, selectedId, CameraLensCatalog.isFront(this, selectedId))
                CameraProfileStore.ensureProfiles(this, selectedId, normalized)
                CameraProfileStore.activate(this, selectedId, CameraProfileStore.FunctionMode.VIDEO, normalized)
            } else if (normalized != current) {
                CaptureSettings.save(this, normalized)
            }
        }
        CameraPreviewRegistry.clear()
        configureTapToFocus()
        configureCameraPreview()

        capabilityMatrix = CaptureCapabilityMatrix.cached(this)
        capabilityScanCompleted = capabilityMatrix != null

        applySystemBarInsets()
        refreshIdleOrStoredState()
        updateBatteryStatus()

        startButton.setOnClickListener {
            if (!startButton.isEnabled) return@setOnClickListener
            recordingRequestedFromPreview = false
            recordingRequestedFromQuickShortcut = false
            activateCurrentCameraProfile(CameraProfileStore.FunctionMode.VIDEO)
            beginRecordingFlow()
        }

        stopButton.setOnClickListener {
            if (!stopButton.isEnabled) return@setOnClickListener
            stopRecordingSafely()
        }

        photoButton.setOnClickListener {
            if (!photoButton.isEnabled) return@setOnClickListener
            Haptics.photo(this)
            photoRequestedFromPreview = false
            beginPhotoFlow(isBurst = false)
        }

        burstButton.setOnClickListener {
            if (!burstButton.isEnabled) return@setOnClickListener
            Haptics.photo(this)
            photoRequestedFromPreview = false
            beginPhotoFlow(isBurst = true)
        }

        batteryButton.setOnClickListener {
            Haptics.tap(this)
            openBatteryProtection()
        }

        widgetButton.setOnClickListener {
            Haptics.tap(this)
            requestWidgetPin()
        }

        backgroundRecordingZoomButton.setOnClickListener {
            if (!backgroundRecordingZoomButton.isEnabled) return@setOnClickListener
            Haptics.tap(this)
            showBackgroundRecordingZoomChooser()
        }

        previewModeButton.setOnClickListener {
            Haptics.tap(this)
            previewUserRequested = true
            openCameraPreview()
        }
        previewOverlayButton.setOnClickListener {
            Haptics.tap(this)
            requestClosePreview()
        }
        previewQuickCloseButton.setOnClickListener {
            Haptics.tap(this)
            requestClosePreview()
        }
        previewPhotoModeButton.setOnClickListener {
            if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return@setOnClickListener
            Haptics.tap(this)
            switchPreviewFunction(CameraProfileStore.FunctionMode.PHOTO)
        }
        previewVideoModeButton.setOnClickListener {
            if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return@setOnClickListener
            Haptics.tap(this)
            switchPreviewFunction(CameraProfileStore.FunctionMode.VIDEO)
        }
        previewHideTopButton.setOnClickListener {
            Haptics.tap(this)
            setPreviewTopControlsVisible(false)
        }
        previewShowTopButton.setOnClickListener {
            Haptics.tap(this)
            setPreviewTopControlsVisible(true)
        }
        previewSettingsButton.setOnClickListener {
            Haptics.tap(this)
            showPreviewSettingsSheet(PreviewSettingsTab.GENERAL)
        }
        previewRecordButton.setOnClickListener {
            Haptics.tap(this)
            previewPhotoMode = false
            if (CaptureStateStore.isBusy(this)) {
                hidePreviewTransitionCover(animated = false)
                stopRecordingSafely()
            } else {
                recordingRequestedFromPreview = true
                beginRecordingFlow()
            }
        }
        previewPhotoButton.setOnClickListener {
            if (!previewPhotoButton.isEnabled) return@setOnClickListener
            Haptics.photo(this)
            previewPhotoMode = true
            photoRequestedFromPreview = true
            beginPhotoFlow(isBurst = false)
        }
        previewLastMediaButton.setOnClickListener {
            Haptics.tap(this)
            openLatestCapturedMedia()
        }
        previewCameraSwitchButton.setOnClickListener {
            if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return@setOnClickListener
            Haptics.tap(this)
            flipPreviewCamera()
        }
        previewCameraSwitchButton.setOnLongClickListener {
            if (!CaptureStateStore.isBusy(this) && !photoBusy && !PhotoCaptureStateStore.isBusy(this)) {
                Haptics.tap(this)
                showPreviewSettingsSheet(PreviewSettingsTab.CAMERAS)
            }
            true
        }
        previewSettingsScrim.setOnClickListener { hidePreviewSettingsSheet() }
        previewSettingsGeneralTab.setOnClickListener { selectPreviewSettingsTab(PreviewSettingsTab.GENERAL) }
        previewSettingsCamerasTab.setOnClickListener { selectPreviewSettingsTab(PreviewSettingsTab.CAMERAS) }
        previewSettingsPhotoTab.setOnClickListener { selectPreviewSettingsTab(PreviewSettingsTab.PHOTO) }
        previewSettingsVideoTab.setOnClickListener { selectPreviewSettingsTab(PreviewSettingsTab.VIDEO) }
        previewSettingsProTab.setOnClickListener { selectPreviewSettingsTab(PreviewSettingsTab.PRO) }
        previewManageProfilesButton.setOnClickListener {
            Haptics.tap(this)
            openAdvancedSettingsFromPreview()
        }
        previewFps30Button.setOnClickListener { selectPreviewRecordingMode(CaptureModeStore.FPS_30) }
        previewFps60Button.setOnClickListener { selectPreviewRecordingMode(CaptureModeStore.FPS_60) }
        previewZoom06Button.setOnClickListener { selectPreviewLensShortcut(0.6f) }
        previewZoom1Button.setOnClickListener { selectPreviewLensShortcut(1f) }
        previewZoom3Button.setOnClickListener { selectPreviewLensShortcut(3f) }
        previewZoom5Button.setOnClickListener { selectPreviewLensShortcut(5f) }

        fps30Button.setOnClickListener {
            if (!fps30Button.isEnabled) return@setOnClickListener
            Haptics.tap(this)
            selectRecordingMode(CaptureModeStore.FPS_30)
        }

        fps60Button.setOnClickListener {
            if (!fps60Button.isEnabled) {
                return@setOnClickListener
            }

            Haptics.tap(this)
            selectRecordingMode(
                CaptureModeStore.FPS_60
            )
        }


        renderRecordingMode(
            busy = CaptureStateStore.isBusy(this)
        )
        renderBackgroundRecordingZoom()
        refreshCapabilityMatrix()

        handleWidgetPermissionRequest(intent)
        consumeLauncherVideoShortcut(intent)
        consumeLauncherPhotoShortcut(intent)

        mainHandler.postDelayed(
            {
                requestNotificationPermissionForUpgrade()
            },
            NOTIFICATION_PERMISSION_DELAY_MS
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissPreviewForNavigation()
        setIntent(intent)
        handleWidgetPermissionRequest(intent)
        consumeLauncherVideoShortcut(intent)
        consumeLauncherPhotoShortcut(intent)
    }

    private fun consumeLauncherVideoShortcut(sourceIntent: Intent) {
        if (sourceIntent.action != ACTION_LAUNCHER_RECORD_VIDEO) return
        sourceIntent.action = Intent.ACTION_MAIN
        mainHandler.post {
            if (isFinishing || isDestroyed || CaptureStateStore.isBusy(this)) return@post
            recordingRequestedFromPreview = false
            recordingRequestedFromQuickShortcut = true
            activateCurrentCameraProfile(CameraProfileStore.FunctionMode.VIDEO)
            beginRecordingFlow()
        }
    }

    private fun consumeLauncherPhotoShortcut(sourceIntent: Intent) {
        if (sourceIntent.action != ACTION_LAUNCHER_TAKE_PHOTO) return
        sourceIntent.action = Intent.ACTION_MAIN
        mainHandler.post {
            if (isFinishing || isDestroyed || CaptureStateStore.isBusy(this) || PhotoCaptureStateStore.isBusy(this)) return@post
            Haptics.photo(this)
            photoRequestedFromPreview = false
            beginPhotoFlow(isBurst = false)
        }
    }

    override fun onStart() {
        super.onStart()
        registerStateReceivers()
        refreshIdleOrStoredState()
        refreshOptimizationState()
    }

    override fun onResume() {
        super.onResume()
        photoBusy = PhotoCaptureStateStore.isBusy(this)
        updateBatteryStatus()
        renderBackgroundRecordingZoom()
        refreshLastMediaThumbnail()
        refreshIdleOrStoredState()
        refreshOptimizationState()
        val currentState = CaptureStateStore.currentState(this)
        renderRecordingMode(busy = CaptureStateStore.isBusyMessage(currentState))
        if (pendingBlackScreenForRecording && currentState.startsWith("Gravando", ignoreCase = true)) {
            pendingBlackScreenForRecording = false
            openBlackScreen()
        }
        cleanupHiddenPreviewIfIdle()
        if (reopenPreviewAfterSettings && !previewOpen && !CaptureStateStore.isBusy(this) && !photoBusy && !PhotoCaptureStateStore.isBusy(this)) {
            val reopenAsPhoto = reopenPreviewPhotoMode
            reopenPreviewAfterSettings = false
            mainHandler.post {
                previewPhotoMode = reopenAsPhoto
                previewUserRequested = true
                openCameraPreview()
                if (!reopenAsPhoto) switchPreviewFunction(CameraProfileStore.FunctionMode.VIDEO, restartPreview = true)
            }
        }
        if (CaptureCapabilityMatrix.cached(this) == null &&
            SystemClock.elapsedRealtime() - lastCapabilityScanStartedAtMs >= CAPABILITY_RESCAN_DEBOUNCE_MS
        ) {
            refreshCapabilityMatrix()
        }
    }

    override fun onStop() {
        if (navigatingToAdvancedSettings) {
            hidePreviewSettingsSheet(animated = false)
            navigatingToAdvancedSettings = false
        }
        dismissPreviewForNavigation()
        unregisterStateReceivers()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hidePreviewTransitionCover(animated = false)
        releaseCameraPreview()
        idlePreview.release()
        thumbnailExecutor.shutdownNow()
        capabilityExecutor.shutdownNow()
        super.onDestroy()
    }


    private fun ensurePreviewTransitionCover(): ImageView {
        previewTransitionCover?.let { return it }
        return ImageView(this).apply {
            visibility = View.GONE
            alpha = 0f
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
            previewContainer.addView(this, 1, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            previewTransitionCover = this
            syncPreviewTransitionCoverLayout()
        }
    }

    private fun syncPreviewTransitionCoverLayout() {
        val cover = previewTransitionCover ?: return
        if (!::previewTexture.isInitialized) return
        val source = previewTexture.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = (cover.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(source.width, source.height, source.gravity)
        target.width = source.width
        target.height = source.height
        target.gravity = source.gravity
        target.leftMargin = source.leftMargin
        target.topMargin = source.topMargin
        target.rightMargin = source.rightMargin
        target.bottomMargin = source.bottomMargin
        cover.layoutParams = target
    }

    private fun preparePreviewRecordingTransition(startRecording: () -> Unit) {
        if (!previewOpen || !::previewTexture.isInitialized || previewTexture.width <= 0 || previewTexture.height <= 0) {
            startRecording()
            return
        }
        if (previewTransitionPending) return
        previewTransitionPending = true
        mainHandler.removeCallbacks(hidePreviewTransitionRunnable)
        val cover = ensurePreviewTransitionCover()
        syncPreviewTransitionCoverLayout()
        val sourceWidth = previewTexture.width.coerceAtLeast(2)
        val sourceHeight = previewTexture.height.coerceAtLeast(2)
        val scale = minOf(1f, PREVIEW_TRANSITION_MAX_DIMENSION_PX.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).toInt().coerceAtLeast(2)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(2)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
        var completed = false
        val finish: (Boolean) -> Unit = { copied ->
            if (!completed) {
                completed = true
                previewTransitionPending = false
                if (!copied || bitmap == null) {
                    bitmap?.recycle()
                    cover.setImageDrawable(null)
                } else {
                    previewTransitionBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                    previewTransitionBitmap = bitmap
                    cover.setImageBitmap(bitmap)
                }
                cover.visibility = View.VISIBLE
                cover.alpha = 1f
                mainHandler.removeCallbacks(hidePreviewTransitionRunnable)
                mainHandler.postDelayed(hidePreviewTransitionRunnable, PREVIEW_TRANSITION_MAX_HOLD_MS)
                startRecording()
            }
        }
        if (bitmap == null || !previewTexture.holder.surface.isValid) {
            finish(false)
            return
        }
        val timeout = Runnable { finish(false) }
        mainHandler.postDelayed(timeout, PREVIEW_FRAME_COPY_TIMEOUT_MS)
        runCatching {
            PixelCopy.request(previewTexture, bitmap, { result ->
                mainHandler.removeCallbacks(timeout)
                finish(result == PixelCopy.SUCCESS)
            }, mainHandler)
        }.onFailure {
            mainHandler.removeCallbacks(timeout)
            finish(false)
        }
    }

    private fun updatePreviewTransitionForState(message: String) {
        val normalized = message.lowercase()
        if (
            normalized.startsWith("gravando") ||
            normalized.contains("preview foi pausado") ||
            normalized.startsWith("falha") ||
            normalized.startsWith("finalizando") ||
            normalized.startsWith("salvo") ||
            normalized.contains("cancelada")
        ) {
            mainHandler.removeCallbacks(hidePreviewTransitionRunnable)
            mainHandler.postDelayed(hidePreviewTransitionRunnable, PREVIEW_TRANSITION_RELEASE_DELAY_MS)
        }
    }

    private fun releasePreviewModeTransition(photoMode: Boolean) {
        if (previewModeTransitionTarget != photoMode) return
        previewModeTransitionTarget = null
        mainHandler.removeCallbacks(hidePreviewTransitionRunnable)
        mainHandler.postDelayed(
            { hidePreviewTransitionCover(animated = true) },
            PREVIEW_MODE_TRANSITION_RELEASE_DELAY_MS
        )
    }

    private fun hidePreviewTransitionCover(animated: Boolean) {
        previewTransitionPending = false
        previewModeTransitionTarget = null
        mainHandler.removeCallbacks(hidePreviewTransitionRunnable)
        val cover = previewTransitionCover ?: return
        cover.animate().cancel()
        val release = {
            cover.visibility = View.GONE
            cover.alpha = 0f
            cover.setImageDrawable(null)
            previewTransitionBitmap?.takeIf { !it.isRecycled }?.recycle()
            previewTransitionBitmap = null
        }
        if (animated && cover.visibility == View.VISIBLE) {
            cover.animate().alpha(0f).setDuration(PREVIEW_TRANSITION_FADE_MS).withEndAction { release() }.start()
        } else {
            release()
        }
    }

    private fun configureTapToFocus() {
        val indicatorSize = (72f * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        previewFocusIndicator = View(this).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = false
            isFocusable = false
            background = focusIndicatorBackground(Color.WHITE)
        }
        previewContainer.addView(
            previewFocusIndicator,
            FrameLayout.LayoutParams(indicatorSize, indicatorSize)
        )
        previewTexture.isClickable = true
        previewTexture.setOnTouchListener { view, event ->
            if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
            view.performClick()
            if (
                !previewOpen || CaptureStateStore.isBusy(this) || photoBusy ||
                PhotoCaptureStateStore.isBusy(this) || view.width <= 0 || view.height <= 0
            ) return@setOnTouchListener true

            val normalizedX = (event.x / view.width.toFloat()).coerceIn(0f, 1f)
            val normalizedY = (event.y / view.height.toFloat()).coerceIn(0f, 1f)
            showFocusIndicator(event.x, event.y)
            Haptics.tap(this)
            idlePreview.focusAt(normalizedX, normalizedY) { focused ->
                showFocusResult(focused)
            }
            true
        }
    }

    private fun showFocusIndicator(touchX: Float, touchY: Float) {
        mainHandler.removeCallbacks(hideFocusIndicatorRunnable)
        val params = previewFocusIndicator.layoutParams as FrameLayout.LayoutParams
        val size = params.width
        val maxLeft = (previewContainer.width - size).coerceAtLeast(0)
        val maxTop = (previewContainer.height - size).coerceAtLeast(0)
        params.leftMargin = (previewTexture.left + touchX - size / 2f).toInt().coerceIn(0, maxLeft)
        params.topMargin = (previewTexture.top + touchY - size / 2f).toInt().coerceIn(0, maxTop)
        previewFocusIndicator.layoutParams = params
        previewFocusIndicator.background = focusIndicatorBackground(Color.WHITE)
        previewFocusIndicator.visibility = View.VISIBLE
        previewFocusIndicator.animate().cancel()
        previewFocusIndicator.alpha = 1f
        previewFocusIndicator.scaleX = 1.35f
        previewFocusIndicator.scaleY = 1.35f
        previewFocusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
    }

    private fun showFocusResult(focused: Boolean) {
        if (!::previewFocusIndicator.isInitialized || previewFocusIndicator.visibility != View.VISIBLE) return
        previewFocusIndicator.background = focusIndicatorBackground(
            if (focused) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54)
        )
        mainHandler.removeCallbacks(hideFocusIndicatorRunnable)
        mainHandler.postDelayed(hideFocusIndicatorRunnable, if (focused) 520L else 760L)
    }

    private fun focusIndicatorBackground(strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((2.5f * resources.displayMetrics.density).toInt().coerceAtLeast(2), strokeColor)
        }

    private fun configureCameraPreview() {
        applyPreviewSurfaceBuffer()
        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewSurfaceLayout()
        }
        previewTopPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewSurfaceLayout()
        }
        previewTexture.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewBufferWidth = 0
                previewBufferHeight = 0
                applyPreviewSurfaceBuffer()
                applyPreviewSurfaceLayout()
                registerCameraPreview(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                applyPreviewSurfaceLayout()
                val surface = holder.surface
                if (previewSurface !== surface || previewSurface?.isValid != true) {
                    registerCameraPreview(surface)
                } else {
                    updatePreviewSurfaceFrameRate()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewBufferWidth = 0
                previewBufferHeight = 0
                if (previewSurface === holder.surface) releasePreviewSurface()
            }
        })
        previewContainer.visibility = View.GONE
        previewModeButton.text = "Abrir câmera"
        setPreviewTopControlsVisible(true)
        renderPreviewQuickControls()
    }

    private fun applyPreviewSurfaceBuffer() {
        if (!::previewTexture.isInitialized) return
        val (width, height) = currentPreviewBufferSize()
        if (previewBufferWidth == width && previewBufferHeight == height) return
        previewBufferWidth = width
        previewBufferHeight = height
        previewTexture.holder.setFixedSize(width, height)
    }

    private fun currentPreviewBufferSize(): Pair<Int, Int> {
        val settings = CaptureSettings.snapshot(this)
        val selectedCameraId = settings.selectedCameraId ?: idlePreview.currentCameraId()
        CameraLensCatalog.previewSize(
            context = this,
            cameraId = selectedCameraId,
            resolution = settings.resolution,
            photoMode = previewPhotoMode,
            highSpeed = false
        )?.let { size ->
            val width = size.width.coerceAtLeast(1)
            val height = size.height.coerceAtLeast(1)
            if (previewPhotoMode) return width to height
            val aspect = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
            if (aspect in 1.70f..1.90f) return width to height
        }
        return if (previewPhotoMode) {
            PHOTO_PREVIEW_BUFFER_WIDTH to PHOTO_PREVIEW_BUFFER_HEIGHT
        } else {
            STANDARD_VIDEO_PREVIEW_BUFFER_WIDTH to STANDARD_VIDEO_PREVIEW_BUFFER_HEIGHT
        }
    }

    private fun currentPreviewDisplayAspect(): Float {
        val (width, height) = currentPreviewBufferSize()
        val longSide = maxOf(width, height).coerceAtLeast(1)
        val shortSide = minOf(width, height).coerceAtLeast(1)
        return shortSide.toFloat() / longSide.toFloat()
    }

    private fun applyPreviewSurfaceLayout() {
        if (!::previewContainer.isInitialized || !::previewTexture.isInitialized) return
        val containerWidth = previewContainer.width.takeIf { it > 0 } ?: return
        val containerHeight = previewContainer.height.takeIf { it > 0 } ?: return
        val contentAspect = currentPreviewDisplayAspect().coerceAtLeast(0.1f)
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()

        var targetWidth: Int
        var targetHeight: Int
        if (containerAspect > contentAspect) {
            targetWidth = containerWidth
            targetHeight = kotlin.math.ceil(containerWidth / contentAspect.toDouble()).toInt().coerceAtLeast(1)
        } else {
            targetHeight = containerHeight
            targetWidth = kotlin.math.ceil(containerHeight * contentAspect.toDouble()).toInt().coerceAtLeast(1)
        }

        val targetGravity = Gravity.CENTER
        val layoutParams = (previewTexture.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight, targetGravity)
        if (
            layoutParams.width != targetWidth || layoutParams.height != targetHeight ||
            layoutParams.gravity != targetGravity || layoutParams.topMargin != 0
        ) {
            layoutParams.width = targetWidth
            layoutParams.height = targetHeight
            layoutParams.gravity = targetGravity
            layoutParams.topMargin = 0
            previewTexture.layoutParams = layoutParams
            previewTexture.requestLayout()
        }
        syncPreviewTransitionCoverLayout()
    }

    private fun openCameraPreview() {
        if (!previewUserRequested && !pendingPreviewOpen) return
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            previewUserRequested = false
            Toast.makeText(this, "Pare a captura antes de abrir o preview.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasCameraPermission()) {
            pendingPreviewOpen = true
            requestAppPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_PREVIEW_PERMISSION)
            return
        }

        if (!CameraResourceCoordinator.canStartPreview()) {
            previewUserRequested = false
            Toast.makeText(this, "A câmera está ocupada por uma captura em andamento.", Toast.LENGTH_LONG).show()
            return
        }
        pendingPreviewOpen = false
        closePreviewAfterCapture = false
        previewPhotoMode = true
        val selectedCameraId = CaptureSettings.snapshot(this).selectedCameraId
        if (selectedCameraId != null) {
            CameraProfileStore.activate(this, selectedCameraId, CameraProfileStore.FunctionMode.PHOTO)
        } else {
            CameraProfileStore.setActiveMode(this, CameraProfileStore.FunctionMode.PHOTO)
        }
        previewOpen = true
        setPreviewTopControlsVisible(true)
        updatePreviewSettingsText()
        renderPreviewQuickControls()
        renderPreviewCaptureState(CaptureStateStore.currentState(this), CaptureStateStore.isBusy(this))
        previewContainer.visibility = View.VISIBLE
        previewContainer.bringToFront()
        previewContainer.requestFocus()
        previewContainer.requestApplyInsets()
        applyPreviewSurfaceBuffer()
        applyPreviewSurfaceLayout()
        previewTexture.holder.surface.takeIf { it.isValid }?.let(::registerCameraPreview)
    }

    private fun closeCameraPreview(afterClosed: (() -> Unit)? = null) {
        hidePreviewSettingsSheet(animated = false)
        mainHandler.removeCallbacks(previewUpdateRunnable)
        pendingPreviewOpen = false
        previewUserRequested = false
        if (!previewOpen && previewSurface == null) {
            if (::previewContainer.isInitialized) previewContainer.visibility = View.GONE
            afterClosed?.invoke()
            return
        }

        previewOpen = false
        closePreviewAfterCapture = false
        hidePreviewTransitionCover(animated = false)
        CameraResourceCoordinator.unregisterPreview(previewCoordinatorToken)
        idlePreview.stopAndThen {
            CameraPreviewRegistry.clear()
            releasePreviewSurface()
            if (::previewContainer.isInitialized) previewContainer.visibility = View.GONE
            afterClosed?.invoke()
        }
    }

    private fun registerCameraPreview(surface: Surface) {
        if (!previewOpen || previewContainer.visibility != View.VISIBLE || !surface.isValid) return
        val current = previewSurface
        if (current === surface && current.isValid) {
            updatePreviewSurfaceFrameRate()
            startIdlePreviewIfPossible()
            return
        }
        releasePreviewSurface()
        previewSurface = surface
        val (bufferWidth, bufferHeight) = currentPreviewBufferSize()
        CameraPreviewRegistry.register(surface, bufferWidth, bufferHeight)
        updatePreviewSurfaceFrameRate()
        startIdlePreviewIfPossible()
    }

    private fun startIdlePreviewIfPossible() {
        if (!previewOpen || !::previewContainer.isInitialized || previewContainer.visibility != View.VISIBLE) return
        if (CaptureStateStore.isBusy(this) || photoBusy || isFinishing || isDestroyed) return
        if (!CameraResourceCoordinator.canStartPreview()) return
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        val (bufferWidth, bufferHeight) = currentPreviewBufferSize()
        CameraPreviewRegistry.register(surface, bufferWidth, bufferHeight)
        val registered = CameraResourceCoordinator.registerPreview(previewCoordinatorToken) { released ->
            idlePreview.stopAndThen { released() }
        }
        if (!registered) return
        previewPausedScrim.visibility = View.GONE
        val inspectionSettings = CaptureSettings.snapshot(this).copy(previewMode = CaptureSettings.PREVIEW_FULL)
        updatePreviewSurfaceFrameRate(inspectionSettings.fps)
        idlePreview.start(surface, inspectionSettings, previewPhotoMode)
    }

    private fun updatePreviewSettingsText() {
        if (!::previewSettingsText.isInitialized) return
        val settings = CaptureSettings.snapshot(this)
        val whiteBalance = when (settings.whiteBalanceMode) {
            CaptureSettings.WHITE_BALANCE_INCANDESCENT -> "Tungstênio"
            CaptureSettings.WHITE_BALANCE_FLUORESCENT -> "Fluorescente"
            CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> "Fluor. quente"
            CaptureSettings.WHITE_BALANCE_DAYLIGHT -> "Luz do dia"
            CaptureSettings.WHITE_BALANCE_CLOUDY -> "Nublado"
            CaptureSettings.WHITE_BALANCE_TWILIGHT -> "Entardecer"
            CaptureSettings.WHITE_BALANCE_SHADE -> "Sombra"
            else -> "WB Auto"
        }
        val hdr = if (settings.hdrHlg10) "HDR10" else "SDR"
        val zoom = if (settings.zoomRatio < 1f) String.format(java.util.Locale.US, "%.1fx", settings.zoomRatio)
            else String.format(java.util.Locale.US, "%.0fx", settings.zoomRatio)
        val activeCameraId = idlePreview.currentCameraId() ?: settings.selectedCameraId
        val cameraLabel = CameraLensCatalog.labelFor(this, activeCameraId)
        previewModeBadge.text = if (previewPhotoMode) "FOTO" else "VÍDEO"
        previewSettingsText.text = if (previewPhotoMode) {
            "FOTO • $cameraLabel • $zoom • toque para focar\n" +
                "$whiteBalance • ${stabilizationLabel(settings.stabilization)} • " +
                exposureLabel(settings.exposureCompensation)
        } else {
            "${CaptureSettings.resolutionLabel(settings.resolution)} • ${settings.fps} FPS • $hdr • $cameraLabel • $zoom\n" +
                "${codecLabel(settings.codec)} • ${stabilizationLabel(settings.stabilization)} • " +
                exposureLabel(settings.exposureCompensation)
        }
        previewPerformanceText.text = if (previewPhotoMode) {
            "Foto • foco e exposição em tempo real"
        } else {
            "${CaptureSettings.resolutionLabel(settings.resolution)} ${settings.fps} • configuração real"
        }
    }

    private fun selectPreviewRecordingMode(targetFps: Int) {
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Pare a captura antes de mudar o modo.", Toast.LENGTH_SHORT).show()
            return
        }
        Haptics.tap(this)
        selectRecordingMode(targetFps)
    }

    private fun selectedCameraFeatures(): CaptureCapabilityMatrix.CameraFeatures? {
        val settings = CaptureSettings.snapshot(this)
        return selectedCapabilityMatrix()?.featuresFor(settings.selectedCameraId)
    }

    private fun featureSelectable(support: Support): Boolean =
        HardwareSupportPolicy.isSelectable(support)

    private fun featureDescription(support: Support, normal: String): String = when (support) {
        Support.SUPPORTED -> normal
        Support.UNVERIFIED -> "$normal • validação real ao abrir a sessão"
        Support.UNSUPPORTED -> "Não suportado pela câmera selecionada"
    }

    private fun exposedFeatureValues(
        candidates: List<String>,
        currentValue: String,
        supportOf: (String) -> Support
    ): List<String> {
        val exposed = candidates.filter { value ->
            HardwareSupportPolicy.shouldExpose(supportOf(value))
        }
        return exposed.ifEmpty {
            candidates.filter { it == currentValue }.ifEmpty { candidates.take(1) }
        }
    }

    private fun strongestSupport(vararg values: Support): Support = when {
        values.any { it == Support.SUPPORTED } -> Support.SUPPORTED
        values.any { it == Support.UNVERIFIED } -> Support.UNVERIFIED
        else -> Support.UNSUPPORTED
    }

    private fun stabilizationSupport(
        settings: CaptureSettings.Snapshot,
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String
    ): Support {
        fun direct(candidate: String): Support =
            features?.stabilizationSupport(candidate) ?: Support.UNVERIFIED
        return if (value == CaptureSettings.STABILIZATION_OFF) Support.SUPPORTED else direct(value)
    }

    private fun focusSupport(
        settings: CaptureSettings.Snapshot,
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String
    ): Support = features?.focusSupport(value) ?: Support.UNVERIFIED

    private fun processingSupport(
        settings: CaptureSettings.Snapshot,
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String,
        noise: Boolean
    ): Support = if (noise) {
        features?.noiseReductionSupport(value) ?: Support.UNVERIFIED
    } else {
        features?.edgeSupport(value) ?: Support.UNVERIFIED
    }

    private fun yellowReductionSupport(
        features: CaptureCapabilityMatrix.CameraFeatures?,
        value: String
    ): Support {
        if (value == CaptureSettings.YELLOW_REDUCTION_OFF) return Support.SUPPORTED
        val manual = features?.manualPostProcessing ?: Support.UNVERIFIED
        val incandescent = features?.whiteBalanceSupport(CaptureSettings.WHITE_BALANCE_INCANDESCENT)
            ?: Support.UNVERIFIED
        return when (value) {
            CaptureSettings.YELLOW_REDUCTION_AUTO,
            CaptureSettings.YELLOW_REDUCTION_LIGHT -> manual
            else -> strongestSupport(manual, incandescent)
        }
    }

    private fun hasMultipleExposedValues(
        candidates: Collection<String>,
        supportOf: (String) -> Support
    ): Boolean = candidates.count { value ->
        HardwareSupportPolicy.shouldExpose(supportOf(value))
    } > 1

    private fun showLiveSettingsMenu() {
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Pare a captura antes de alterar as configurações.", Toast.LENGTH_LONG).show()
            return
        }
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        val actions = mutableListOf<Pair<OneUiDialog.Choice, () -> Unit>>()
        actions.add(OneUiDialog.Choice("Resolução", CaptureSettings.resolutionLabel(settings.resolution)) to ::showLiveResolutionChoices)
        actions.add(OneUiDialog.Choice("Taxa de quadros", "${settings.fps} FPS") to ::showLiveFpsChoices)
        val hdrSupport = features?.hdrHlg10 ?: Support.UNVERIFIED
        val hdrAvailable = settings.codec != CaptureSettings.CODEC_AVC &&
            HardwareSupportPolicy.shouldExpose(hdrSupport)
        if (hdrAvailable) {
            actions.add(OneUiDialog.Choice("HDR HLG10", if (settings.hdrHlg10) "Ativado" else "Desativado") to ::showLiveHdrChoices)
        }
        if (hasMultipleExposedValues(CaptureSettings.supportedWhiteBalanceValues) { value ->
                features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
            }) {
            actions.add(OneUiDialog.Choice("Balanço de branco", whiteBalanceLabel(settings.whiteBalanceMode)) to ::showLiveWhiteBalanceChoices)
        }
        if (hasMultipleExposedValues(CaptureSettings.supportedYellowReductionValues) { value ->
                yellowReductionSupport(features, value)
            }) {
            actions.add(OneUiDialog.Choice("Neutralizar amarelo", yellowReductionLabel(settings.yellowReduction)) to ::showLiveYellowReductionChoices)
        }
        if (features?.exposureCompensationSupported != false) {
            actions.add(OneUiDialog.Choice("Exposição", exposureLabel(settings.exposureCompensation)) to ::showLiveExposureChoices)
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.STABILIZATION_PREVIEW,
                    CaptureSettings.STABILIZATION_EIS,
                    CaptureSettings.STABILIZATION_OIS,
                    CaptureSettings.STABILIZATION_OFF
                )
            ) { value -> stabilizationSupport(settings, features, value) }) {
            actions.add(OneUiDialog.Choice("Estabilização", stabilizationLabel(settings.stabilization)) to ::showLiveStabilizationChoices)
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                    CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
                    CaptureSettings.FOCUS_AUTO,
                    CaptureSettings.FOCUS_OFF
                )
            ) { value -> focusSupport(settings, features, value) }) {
            actions.add(OneUiDialog.Choice("Foco", focusLabel(settings.focusMode)) to ::showLiveFocusChoices)
        }
        if (!settings.hdrHlg10 && settings.fps < CaptureModeStore.FPS_60) {
            actions.add(OneUiDialog.Choice("Perfil de cor", CaptureSettings.colorProfileLabel(settings.colorProfile)) to ::showLiveColorProfileChoices)
        }
        val processingCandidates = listOf(
            CaptureSettings.PROCESSING_AUTO,
            CaptureSettings.PROCESSING_OFF,
            CaptureSettings.PROCESSING_FAST,
            CaptureSettings.PROCESSING_HIGH_QUALITY
        )
        if (hasMultipleExposedValues(processingCandidates) { value ->
                processingSupport(settings, features, value, noise = true)
            }) {
            actions.add(OneUiDialog.Choice("Redução de ruído", processingLabel(settings.noiseReduction)) to { showLiveProcessingChoices(noise = true) })
        }
        if (hasMultipleExposedValues(processingCandidates) { value ->
                processingSupport(settings, features, value, noise = false)
            }) {
            actions.add(OneUiDialog.Choice("Nitidez", processingLabel(settings.edgeMode)) to { showLiveProcessingChoices(noise = false) })
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.ANTIBANDING_AUTO,
                    CaptureSettings.ANTIBANDING_50HZ,
                    CaptureSettings.ANTIBANDING_60HZ,
                    CaptureSettings.ANTIBANDING_OFF
                )
            ) { value -> features?.antibandingSupport(value) ?: Support.UNVERIFIED }) {
            actions.add(OneUiDialog.Choice("Anti-flicker", antibandingLabel(settings.antibanding)) to ::showLiveAntibandingChoices)
        }
        actions.add(OneUiDialog.Choice("Codec e bitrate", "${codecLabel(settings.codec)} • ${settings.bitrateMbps} Mbps") to ::showLiveCodecAndBitrateMenu)
        actions.add(OneUiDialog.Choice("Todos os ajustes avançados", "Abre a tela completa e retorna ao preview") to ::openAdvancedSettingsFromPreview)
        OneUiDialog.choices(
            activity = this,
            title = "Ajustes ao vivo",
            message = "Cada alteração é salva imediatamente, reinicia apenas a sessão de visualização e será usada também nas capturas feitas fora do preview.",
            choices = actions.map { it.first }
        ) { index ->
            actions.getOrNull(index)?.second?.invoke()
        }
    }

    private fun showSensorPixelModeChoices() {
        val values = listOf(SensorPixelModeSettings.AUTO, SensorPixelModeSettings.NORMAL, SensorPixelModeSettings.MAXIMUM_RESOLUTION)
        val current = SensorPixelModeSettings.selected(this)
        OneUiDialog.choices(
            activity = this,
            title = "Leitura do sensor",
            message = "O Android não expõe 12,5/50 MP diretamente. Compare os modos Camera2 no mesmo 1x/4K60.",
            choices = listOf(
                OneUiDialog.Choice("Auto • Samsung HAL", "A Samsung escolhe o caminho interno de leitura/binning."),
                OneUiDialog.Choice("Normal pixel mode", "Solicita explicitamente o caminho normal do sensor."),
                OneUiDialog.Choice("Maximum-resolution", "Solicita máxima resolução quando suportado; pode ser mais pesado.")
            ),
            selectedIndex = values.indexOf(current).coerceAtLeast(0)
        ) { position ->
            SensorPixelModeSettings.save(this, values[position])
            resetPreviewFpsMeter()
            if (previewOpen) restartPreviewForUpdatedSettings()
            renderPreviewSettingsSheetContent()
        }
    }

    private fun updatePreviewFpsMeter(timestampNs: Long) {
        if (!previewOpen || timestampNs <= 0L) return
        if (previewFpsLastTimestampNs > 0L && timestampNs <= previewFpsLastTimestampNs) return
        previewFpsLastTimestampNs = timestampNs
        if (previewFpsWindowStartedNs == 0L) {
            previewFpsWindowStartedNs = timestampNs
            previewFpsFrameCount = 1
            return
        }
        previewFpsFrameCount++
        val elapsedNs = timestampNs - previewFpsWindowStartedNs
        if (elapsedNs < 1_000_000_000L) return
        previewMeasuredFps = (previewFpsFrameCount - 1).toDouble() * 1_000_000_000.0 / elapsedNs.toDouble()
        val target = CaptureSettings.snapshot(this).fps
        previewPerformanceText.text = if (previewPhotoMode) {
            "Preview • ${String.format(java.util.Locale.US, "%.1f", previewMeasuredFps)} FPS"
        } else {
            "FPS real ${String.format(java.util.Locale.US, "%.1f", previewMeasuredFps)} / $target"
        }
        previewFpsWindowStartedNs = timestampNs
        previewFpsFrameCount = 1
    }

    private fun resetPreviewFpsMeter() {
        previewFpsWindowStartedNs = 0L
        previewFpsLastTimestampNs = 0L
        previewFpsFrameCount = 0
        previewMeasuredFps = 0.0
        if (::previewPerformanceText.isInitialized && previewOpen) previewPerformanceText.text = "FPS real --"
    }
    private fun showLiveResolutionChoices() {
        val settings = CaptureSettings.snapshot(this)
        val candidates = listOf(
            CaptureSettings.RESOLUTION_8K,
            CaptureSettings.RESOLUTION_4K,
            CaptureSettings.RESOLUTION_1080P,
            CaptureSettings.RESOLUTION_720P
        )
        val profiles = candidates.associateWith { value ->
            CaptureModeCatalog.resolveSelection(
                this,
                settings.fps,
                value,
                selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
                capabilityMatrix == null && capabilityScanInProgress
            )
        }
        OneUiDialog.choices(
            activity = this,
            title = "Resolução",
            message = "A escolha é manual. Combinações não confirmadas ficam desabilitadas em vez de serem substituídas.",
            choices = candidates.map { value ->
                val profile = profiles.getValue(value)
                OneUiDialog.Choice(CaptureSettings.resolutionLabel(value), profile.capabilityDescription, profile.selectable)
            },
            selectedIndex = candidates.indexOf(settings.resolution).coerceAtLeast(0)
        ) { position ->
            val value = candidates[position]
            if (!profiles.getValue(value).selectable) {
                Toast.makeText(this, "Esta resolução não foi confirmada para o FPS atual.", Toast.LENGTH_LONG).show()
            } else {
                applyLiveSettings(settings.copy(resolution = value), "Resolução atualizada")
            }
        }
    }

    private fun showLiveFpsChoices() {
        val settings = CaptureSettings.snapshot(this)
        val catalog = modeCatalog()
        val values = CaptureSettings.supportedFpsValues.filter {
            catalog.profile(it).selectable
        }.ifEmpty { listOf(settings.fps) }
        OneUiDialog.choices(
            activity = this,
            title = "Taxa de quadros",
            message = "O preview tenta reproduzir a cadência selecionada. A captura final valida a câmera e o encoder.",
            choices = values.map { fps ->
                val profile = catalog.profile(fps)
                OneUiDialog.Choice(profile.inlineText, profile.capabilityDescription, profile.selectable)
            },
            selectedIndex = values.indexOf(settings.fps).coerceAtLeast(0)
        ) { position -> selectPreviewRecordingMode(values[position]) }
    }

    private fun showLiveHdrChoices() {
        val settings = CaptureSettings.snapshot(this)
        val support = selectedCameraFeatures()?.hdrHlg10 ?: Support.UNVERIFIED
        val hdrAllowed = featureSelectable(support)
        OneUiDialog.choices(
            activity = this,
            title = "HDR HLG10",
            message = "A opção só aparece quando câmera e HEVC Main10 não apresentam incompatibilidade. Metadado incompleto é validado pela sessão real.",
            choices = listOf(
                OneUiDialog.Choice("Desativado", "SDR com maior compatibilidade"),
                OneUiDialog.Choice(
                    "Ativado",
                    if (hdrAllowed) featureDescription(support, "HEVC Main10 e BT.2020 HLG") else "Indisponível nesta combinação",
                    hdrAllowed
                )
            ),
            selectedIndex = if (settings.hdrHlg10) 1 else 0
        ) { position ->
            val enabled = position == 1
            applyLiveSettings(
                settings.copy(
                    hdrHlg10 = enabled,
                    codec = if (enabled) CaptureSettings.CODEC_HEVC else settings.codec,
                    colorProfile = if (enabled) CaptureSettings.COLOR_NATURAL else settings.colorProfile
                ),
                if (enabled) "HDR HLG10 ativado" else "HDR desativado"
            )
        }
    }

    private fun showLiveWhiteBalanceChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        val candidates = CaptureSettings.supportedWhiteBalanceValues.toList()
        val values = exposedFeatureValues(candidates, settings.whiteBalanceMode) { value ->
            features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
        }
        OneUiDialog.choices(
            activity = this,
            title = "Balanço de branco",
            message = "A diferença aparece após a sessão do preview ser reaberta.",
            choices = values.map { value ->
                val support = features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
                OneUiDialog.Choice(
                    whiteBalanceLabel(value),
                    featureDescription(support, whiteBalanceDescription(value)),
                    featureSelectable(support)
                )
            },
            selectedIndex = values.indexOf(settings.whiteBalanceMode).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(whiteBalanceMode = values[position]), "Balanço de branco atualizado") }
    }

    private fun showLiveYellowReductionChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        fun support(value: String): Support = yellowReductionSupport(features, value)
        val candidates = CaptureSettings.supportedYellowReductionValues.toList()
        val values = exposedFeatureValues(candidates, settings.yellowReduction, ::support)
        OneUiDialog.choices(
            activity = this,
            title = "Neutralizar dominante amarela",
            message = "A correção usa os ganhos medidos pela própria câmera quando o aparelho permite pós-processamento manual.",
            choices = values.map { value ->
                val featureSupport = support(value)
                OneUiDialog.Choice(
                    yellowReductionLabel(value),
                    featureDescription(featureSupport, yellowReductionDescription(value)),
                    featureSelectable(featureSupport)
                )
            },
            selectedIndex = values.indexOf(settings.yellowReduction).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(yellowReduction = values[position]), "Correção de cor atualizada") }
    }

    private fun showLiveExposureChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        val minimum = features?.minimumExposureCompensation?.coerceAtLeast(-12) ?: -6
        val maximum = features?.maximumExposureCompensation?.coerceAtMost(12) ?: 6
        val values = if (minimum <= maximum) (minimum..maximum).toList() else listOf(0)
        OneUiDialog.choices(
            activity = this,
            title = "Compensação de exposição",
            message = "Valores positivos clareiam e negativos escurecem. A câmera limita automaticamente à faixa suportada.",
            choices = values.map { OneUiDialog.Choice(exposureLabel(it), if (it == 0) "Medição padrão" else "Compensação EV da câmera") },
            selectedIndex = values.indexOf(settings.exposureCompensation.coerceIn(minimum, maximum)).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(exposureCompensation = values[position]), "Exposição atualizada") }
    }

    private fun showLiveStabilizationChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        val candidates = listOf(
            CaptureSettings.STABILIZATION_PREVIEW,
            CaptureSettings.STABILIZATION_EIS,
            CaptureSettings.STABILIZATION_OIS,
            CaptureSettings.STABILIZATION_OFF
        )
        val values = exposedFeatureValues(candidates, settings.stabilization) { value ->
            stabilizationSupport(settings, features, value)
        }
        OneUiDialog.choices(
            activity = this,
            title = "Estabilização",
            message = "As opções são específicas desta câmera. OIS também consulta as lentes físicas da câmera lógica.",
            choices = values.map { value ->
                val support = stabilizationSupport(settings, features, value)
                OneUiDialog.Choice(
                    stabilizationLabel(value),
                    featureDescription(support, stabilizationDescription(value)),
                    featureSelectable(support)
                )
            },
            selectedIndex = values.indexOf(settings.stabilization).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(stabilization = values[position]), "Estabilização atualizada") }
    }

    private fun showLiveFocusChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        fun support(value: String): Support = focusSupport(settings, features, value)
        val candidates = listOf(
            CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
            CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
            CaptureSettings.FOCUS_AUTO,
            CaptureSettings.FOCUS_OFF
        )
        val values = exposedFeatureValues(candidates, settings.focusMode, ::support)
        OneUiDialog.choices(
            activity = this,
            title = "Foco",
            choices = values.map { value ->
                val featureSupport = support(value)
                OneUiDialog.Choice(
                    focusLabel(value),
                    featureDescription(featureSupport, focusDescription(value)),
                    featureSelectable(featureSupport)
                )
            },
            selectedIndex = values.indexOf(settings.focusMode).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(focusMode = values[position]), "Modo de foco atualizado") }
    }

    private fun showLiveColorProfileChoices() {
        val settings = CaptureSettings.snapshot(this)
        val values = listOf(CaptureSettings.COLOR_NATURAL, CaptureSettings.COLOR_SOFT, CaptureSettings.COLOR_FLAT)
        val enabled = !settings.hdrHlg10 && settings.fps < CaptureModeStore.FPS_60
        OneUiDialog.choices(
            activity = this,
            title = "Perfil de cor",
            message = if (enabled) "A curva tonal é aplicada na câmera e aparece no preview." else "Perfis personalizados exigem SDR, câmera única e 30 FPS; em FPS alto a HAL usa o tonemap rápido.",
            choices = values.map { value -> OneUiDialog.Choice(CaptureSettings.colorProfileLabel(value), colorProfileDescription(value), enabled || value == CaptureSettings.COLOR_NATURAL) },
            selectedIndex = values.indexOf(settings.colorProfile)
        ) { position -> applyLiveSettings(settings.copy(colorProfile = values[position]), "Perfil de cor atualizado") }
    }

    private fun showLiveProcessingChoices(noise: Boolean) {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        fun support(value: String): Support = processingSupport(settings, features, value, noise)
        val candidates = listOf(
            CaptureSettings.PROCESSING_AUTO,
            CaptureSettings.PROCESSING_OFF,
            CaptureSettings.PROCESSING_FAST,
            CaptureSettings.PROCESSING_HIGH_QUALITY
        )
        val current = if (noise) settings.noiseReduction else settings.edgeMode
        val values = exposedFeatureValues(candidates, current, ::support)
        OneUiDialog.choices(
            activity = this,
            title = if (noise) "Redução de ruído" else "Nitidez da câmera",
            message = if (settings.fps >= CaptureModeStore.FPS_60) {
                "Em FPS alto a câmera pode substituir o modo escolhido por uma opção mais leve."
            } else {
                null
            },
            choices = values.map { value ->
                val featureSupport = support(value)
                OneUiDialog.Choice(
                    processingLabel(value),
                    featureDescription(featureSupport, processingDescription(value, noise)),
                    featureSelectable(featureSupport)
                )
            },
            selectedIndex = values.indexOf(current).coerceAtLeast(0)
        ) { position ->
            val updated = if (noise) settings.copy(noiseReduction = values[position]) else settings.copy(edgeMode = values[position])
            applyLiveSettings(updated, if (noise) "Redução de ruído atualizada" else "Nitidez atualizada")
        }
    }

    private fun showLiveAntibandingChoices() {
        val settings = CaptureSettings.snapshot(this)
        val features = selectedCameraFeatures()
        val candidates = listOf(
            CaptureSettings.ANTIBANDING_AUTO,
            CaptureSettings.ANTIBANDING_50HZ,
            CaptureSettings.ANTIBANDING_60HZ,
            CaptureSettings.ANTIBANDING_OFF
        )
        val values = exposedFeatureValues(candidates, settings.antibanding) { value ->
            features?.antibandingSupport(value) ?: Support.UNVERIFIED
        }
        OneUiDialog.choices(
            activity = this,
            title = "Anti-flicker",
            choices = values.map { value ->
                val support = features?.antibandingSupport(value) ?: Support.UNVERIFIED
                OneUiDialog.Choice(
                    antibandingLabel(value),
                    featureDescription(support, antibandingDescription(value)),
                    featureSelectable(support)
                )
            },
            selectedIndex = values.indexOf(settings.antibanding).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(antibanding = values[position]), "Anti-flicker atualizado") }
    }

    private fun showLiveCodecAndBitrateMenu() {
        val settings = CaptureSettings.snapshot(this)
        OneUiDialog.choices(
            activity = this,
            title = "Codec e bitrate",
            choices = listOf(
                OneUiDialog.Choice("Codec", codecLabel(settings.codec)),
                OneUiDialog.Choice("Bitrate", "${settings.bitrateMbps} Mbps"),
                OneUiDialog.Choice("Recalcular recomendado", "${CaptureSettings.defaultBitrateMbps(settings.resolution, settings.fps, settings.codec)} Mbps")
            )
        ) { position ->
            when (position) {
                0 -> showLiveCodecChoices()
                1 -> showLiveBitrateChoices()
                2 -> {
                    val bitrate = CaptureSettings.defaultBitrateMbps(settings.resolution, settings.fps, settings.codec)
                    applyLiveSettings(settings.copy(bitrateMbps = bitrate), "Bitrate recomendado aplicado")
                }
            }
        }
    }

    private fun showLiveCodecChoices() {
        val settings = CaptureSettings.snapshot(this)
        val matrix = selectedCapabilityMatrix()
        val candidates = listOf(CaptureSettings.CODEC_HEVC, CaptureSettings.CODEC_AVC)
        fun support(value: String): Support = when (value) {
            CaptureSettings.CODEC_HEVC -> matrix?.encoderSupport(settings.selectedCameraId, settings.resolution, settings.fps, android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) ?: Support.UNVERIFIED
            CaptureSettings.CODEC_AVC -> matrix?.encoderSupport(settings.selectedCameraId, settings.resolution, settings.fps, android.media.MediaFormat.MIMETYPE_VIDEO_AVC) ?: Support.UNVERIFIED
            else -> Support.UNSUPPORTED
        }
        OneUiDialog.choices(
            activity = this,
            title = "Codec de vídeo",
            choices = candidates.map { value ->
                val codecSupport = support(value)
                val compatible = !(settings.hdrHlg10 && value == CaptureSettings.CODEC_AVC) && featureSelectable(codecSupport)
                OneUiDialog.Choice(codecLabel(value), featureDescription(codecSupport, codecDescription(value)), compatible)
            },
            selectedIndex = candidates.indexOf(settings.codec).coerceAtLeast(0)
        ) { position ->
            val codec = candidates[position]
            if (featureSelectable(support(codec))) applyLiveSettings(settings.copy(codec = codec), "Codec atualizado")
            else Toast.makeText(this, "Este codec não foi confirmado para a combinação atual.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLiveBitrateChoices() {
        val settings = CaptureSettings.snapshot(this)
        val recommended = CaptureSettings.defaultBitrateMbps(settings.resolution, settings.fps, settings.codec)
        val values = (listOf(4, 6, 8, 10, 12, 15, 20, 24, 28, 32, 40, 44, 48, 50, 60, 64, 80, 90, 100, 120, 135, 150, 180, 200, 220, 240) +
            listOf(settings.bitrateMbps, recommended)).distinct().sorted()
        OneUiDialog.choices(
            activity = this,
            title = "Bitrate do vídeo",
            message = "O valor escolhido permanece salvo e é enviado ao encoder, limitado apenas pela faixa física publicada pelo próprio encoder.",
            choices = values.map { value ->
                val suffix = if (value == recommended) " • recomendado" else ""
                OneUiDialog.Choice("$value Mbps$suffix", "Seleção sem campo de texto.")
            },
            selectedIndex = values.indexOf(settings.bitrateMbps).coerceAtLeast(0)
        ) { position -> applyLiveSettings(settings.copy(bitrateMbps = values[position]), "Bitrate atualizado") }
    }

    private fun applyLiveSettings(snapshot: CaptureSettings.Snapshot, confirmation: String) {
        val before = CaptureSettings.snapshot(this)
        val normalized = snapshot.copy(previewMode = CaptureSettings.PREVIEW_OFF)
        if (normalized.hdrHlg10 && normalized.codec != CaptureSettings.CODEC_HEVC) {
            Toast.makeText(this, "HLG10 exige HEVC. Escolha HEVC ou desligue o HDR.", Toast.LENGTH_LONG).show()
            return
        }
        if (normalized == before) {
            updatePreviewSettingsText(); renderPreviewQuickControls(); renderPreviewSettingsSheetContent()
            Toast.makeText(this, "Já estava aplicado", Toast.LENGTH_SHORT).show()
            return
        }
        if (previewAeAfLocked) {
            idlePreview.unlockFocusAndExposure()
            previewAeAfLocked = false
        }
        CaptureStateStore.clearEffectiveMode(this)
        CaptureSettings.save(this, normalized)
        val applied = CaptureSettings.snapshot(this)
        val profile = configuredProfile(applied.fps, applied.resolution)
        val message = selectedModeStateMessage(profile)
        CaptureStateStore.update(this, message)
        renderState(message)
        renderRecordingMode(busy = false)
        updatePreviewSettingsText()
        renderPreviewQuickControls()
        renderPreviewSettingsSheetContent()
        ExpandedControlWidget.updateAll(this)
        val cameraLabel = CameraLensCatalog.labelFor(this, applied.selectedCameraId)
        val functionLabel = if (previewPhotoMode) "foto" else "vídeo"
        Toast.makeText(this, "$confirmation • perfil de $functionLabel de $cameraLabel", Toast.LENGTH_SHORT).show()
        restartPreviewForUpdatedSettings()
    }

    private fun openAdvancedSettingsFromPreview() {
        CameraProfileStore.saveCurrent(this)
        val profileMode = if (previewPhotoMode) CameraProfileStore.FunctionMode.PHOTO else CameraProfileStore.FunctionMode.VIDEO
        reopenPreviewAfterSettings = true
        reopenPreviewPhotoMode = previewPhotoMode
        navigatingToAdvancedSettings = true

        val intent = Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PROFILE_MODE, profileMode.name)
        val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure {
                navigatingToAdvancedSettings = false
                reopenPreviewAfterSettings = false
                Toast.makeText(this, "Não foi possível abrir os ajustes.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun whiteBalanceLabel(value: String): String = when (value) {
        CaptureSettings.WHITE_BALANCE_INCANDESCENT -> "Luz quente / tungstênio"
        CaptureSettings.WHITE_BALANCE_FLUORESCENT -> "Fluorescente"
        CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> "Fluorescente quente"
        CaptureSettings.WHITE_BALANCE_DAYLIGHT -> "Luz do dia"
        CaptureSettings.WHITE_BALANCE_CLOUDY -> "Nublado"
        CaptureSettings.WHITE_BALANCE_TWILIGHT -> "Entardecer"
        CaptureSettings.WHITE_BALANCE_SHADE -> "Sombra"
        else -> "Automático estabilizado"
    }

    private fun whiteBalanceDescription(value: String): String = when (value) {
        CaptureSettings.WHITE_BALANCE_INCANDESCENT -> "Esfria lâmpadas internas amarelas"
        CaptureSettings.WHITE_BALANCE_FLUORESCENT -> "Compensa fluorescente comum"
        CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT -> "Compensa LED ou fluorescente quente"
        CaptureSettings.WHITE_BALANCE_DAYLIGHT -> "Sol direto"
        CaptureSettings.WHITE_BALANCE_CLOUDY -> "Aquece cenas externas frias"
        CaptureSettings.WHITE_BALANCE_TWILIGHT -> "Fim do dia"
        CaptureSettings.WHITE_BALANCE_SHADE -> "Sombra forte"
        else -> "A câmera mede e estabiliza a cena"
    }

    private fun yellowReductionLabel(value: String): String = when (value) {
        CaptureSettings.YELLOW_REDUCTION_LIGHT -> "Leve"
        CaptureSettings.YELLOW_REDUCTION_MEDIUM -> "Média"
        CaptureSettings.YELLOW_REDUCTION_STRONG -> "Forte"
        CaptureSettings.YELLOW_REDUCTION_AUTO -> "Automática conservadora"
        else -> "Desativada"
    }

    private fun yellowReductionDescription(value: String): String = when (value) {
        CaptureSettings.YELLOW_REDUCTION_LIGHT -> "Correção discreta"
        CaptureSettings.YELLOW_REDUCTION_MEDIUM -> "Correção visível sem exagerar o azul"
        CaptureSettings.YELLOW_REDUCTION_STRONG -> "Somente para luz muito amarela"
        CaptureSettings.YELLOW_REDUCTION_AUTO -> "Só corrige quando os ganhos indicam luz quente"
        else -> "Mantém a resposta original da câmera"
    }

    private fun exposureLabel(value: Int): String = when {
        value > 0 -> "+$value EV"
        value < 0 -> "$value EV"
        else -> "0 EV"
    }

    private fun stabilizationLabel(value: String): String = when (value) {
        CaptureSettings.STABILIZATION_PREVIEW -> "Preview stabilization"
        CaptureSettings.STABILIZATION_EIS -> "EIS eletrônica"
        CaptureSettings.STABILIZATION_OIS -> "OIS óptica"
        CaptureSettings.STABILIZATION_OFF -> "Desativada"
        else -> "Automática"
    }

    private fun stabilizationDescription(value: String): String = when (value) {
        CaptureSettings.STABILIZATION_PREVIEW -> "Estabilização avançada em aparelhos compatíveis"
        CaptureSettings.STABILIZATION_EIS -> "Recorta a imagem e aumenta o processamento em alta taxa de quadros"
        CaptureSettings.STABILIZATION_OIS -> "Movimento físico da lente"
        CaptureSettings.STABILIZATION_OFF -> "Enquadramento integral e menor processamento"
        else -> "Prioriza OIS e usa estabilização eletrônica apenas como alternativa"
    }

    private fun focusLabel(value: String): String = when (value) {
        CaptureSettings.FOCUS_CONTINUOUS_PICTURE -> "Contínuo para foto"
        CaptureSettings.FOCUS_AUTO -> "Automático pontual"
        CaptureSettings.FOCUS_OFF -> "Fixo"
        else -> "Contínuo para vídeo"
    }

    private fun focusDescription(value: String): String = when (value) {
        CaptureSettings.FOCUS_CONTINUOUS_PICTURE -> "Reage rápido, podendo oscilar em vídeo"
        CaptureSettings.FOCUS_AUTO -> "A câmera executa foco automático pontual"
        CaptureSettings.FOCUS_OFF -> "Evita caça de foco"
        else -> "Acompanha continuamente a cena"
    }

    private fun colorProfileDescription(value: String): String = when (value) {
        CaptureSettings.COLOR_SOFT -> "Contraste levemente reduzido"
        CaptureSettings.COLOR_FLAT -> "Imagem plana para edição"
        else -> "Curva natural BT.709"
    }

    private fun processingLabel(value: String): String = when (value) {
        CaptureSettings.PROCESSING_OFF -> "Desativada"
        CaptureSettings.PROCESSING_FAST -> "Rápida"
        CaptureSettings.PROCESSING_HIGH_QUALITY -> "Alta qualidade"
        else -> "Automática"
    }

    private fun processingDescription(value: String, noise: Boolean): String = when (value) {
        CaptureSettings.PROCESSING_OFF -> if (noise) "Preserva textura e granulação" else "Evita halos artificiais"
        CaptureSettings.PROCESSING_FAST -> "Processamento leve para manter FPS"
        CaptureSettings.PROCESSING_HIGH_QUALITY -> if (noise) "Reduz mais ruído, podendo suavizar detalhes" else "Realça contornos com maior carga"
        else -> "A câmera escolhe conforme o modo"
    }

    private fun antibandingLabel(value: String): String = when (value) {
        CaptureSettings.ANTIBANDING_50HZ -> "50 Hz"
        CaptureSettings.ANTIBANDING_60HZ -> "60 Hz"
        CaptureSettings.ANTIBANDING_OFF -> "Desativado"
        else -> "Automático"
    }

    private fun antibandingDescription(value: String): String = when (value) {
        CaptureSettings.ANTIBANDING_50HZ -> "Iluminação ligada a redes de 50 Hz"
        CaptureSettings.ANTIBANDING_60HZ -> "Recomendado para muitas fontes no Brasil"
        CaptureSettings.ANTIBANDING_OFF -> "Pode produzir faixas em luz artificial"
        else -> "A câmera detecta a frequência"
    }

    private fun codecLabel(value: String): String = when (value) {
        CaptureSettings.CODEC_HEVC -> "HEVC / H.265"
        CaptureSettings.CODEC_AVC -> "AVC / H.264"
        else -> "Inválido"
    }

    private fun codecDescription(value: String): String = when (value) {
        CaptureSettings.CODEC_HEVC -> "Melhor qualidade por tamanho e necessário para HDR"
        CaptureSettings.CODEC_AVC -> "Maior compatibilidade, arquivos maiores"
        else -> "Configuração inválida"
    }

    private fun restartPreviewForUpdatedSettings() {
        if (!previewOpen || !previewUserRequested) return
        applyPreviewSurfaceBuffer()
        applyPreviewSurfaceLayout()
        updatePreviewSettingsText()
        renderPreviewQuickControls()
        mainHandler.removeCallbacks(previewUpdateRunnable)
        mainHandler.postDelayed(previewUpdateRunnable, PREVIEW_UPDATE_DEBOUNCE_MS)
    }

    private fun applyPendingPreviewUpdate() {
        if (!previewOpen || !previewUserRequested) return
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        val settings = CaptureSettings.snapshot(this).copy(previewMode = CaptureSettings.PREVIEW_FULL)
        val registered = CameraResourceCoordinator.registerPreview(previewCoordinatorToken) { released ->
            idlePreview.stopAndThen { released() }
        }
        if (!registered) return
        val (bufferWidth, bufferHeight) = currentPreviewBufferSize()
        CameraPreviewRegistry.register(surface, bufferWidth, bufferHeight)
        updatePreviewSurfaceFrameRate(settings.fps)
        idlePreview.update(surface, settings, previewPhotoMode)
        mainHandler.post {
            applyPreviewSurfaceLayout()
            syncPreviewTransitionCoverLayout()
        }
    }

    private fun activateCurrentCameraProfile(mode: CameraProfileStore.FunctionMode): CaptureSettings.Snapshot {
        val current = CaptureSettings.snapshot(this)
        val cameraId = current.selectedCameraId
        return if (cameraId != null) {
            CameraProfileStore.activate(this, cameraId, mode, current)
        } else {
            CameraProfileStore.setActiveMode(this, mode)
            current
        }
    }

    private fun switchPreviewFunction(
        mode: CameraProfileStore.FunctionMode,
        restartPreview: Boolean = true
    ) {
        val targetPhotoMode = mode == CameraProfileStore.FunctionMode.PHOTO
        if (previewPhotoMode == targetPhotoMode || previewModeTransitionTarget != null) return
        if (restartPreview && previewOpen) {
            previewModeTransitionTarget = targetPhotoMode
            preparePreviewRecordingTransition {
                applyPreviewFunction(mode, targetPhotoMode, restartPreview)
                mainHandler.postDelayed({
                    if (previewModeTransitionTarget == targetPhotoMode) {
                        hidePreviewTransitionCover(animated = true)
                    }
                }, PREVIEW_MODE_TRANSITION_TIMEOUT_MS)
            }
            return
        }
        applyPreviewFunction(mode, targetPhotoMode, restartPreview)
    }

    private fun applyPreviewFunction(
        mode: CameraProfileStore.FunctionMode,
        targetPhotoMode: Boolean,
        restartPreview: Boolean
    ) {
        val current = CaptureSettings.snapshot(this)
        val cameraId = current.selectedCameraId ?: return
        CameraProfileStore.saveCurrent(this)
        previewPhotoMode = targetPhotoMode
        val activated = CameraProfileStore.activate(this, cameraId, mode, current)
        CaptureStateStore.clearEffectiveMode(this)
        if (!targetPhotoMode) synchronizeSelectedModeWithCapabilities()
        applyPreviewSurfaceBuffer()
        previewTexture.requestLayout()
        previewContainer.requestLayout()
        applyPreviewSurfaceLayout()
        mainHandler.post {
            applyPreviewSurfaceLayout()
            syncPreviewTransitionCoverLayout()
        }
        updatePreviewSettingsText()
        renderPreviewQuickControls()
        renderPreviewSettingsSheetContent()
        if (restartPreview) restartPreviewForUpdatedSettings()
        if (activated.selectedCameraId != null) {
            CameraProfileStore.rememberCamera(
                this,
                activated.selectedCameraId,
                CameraLensCatalog.isFront(this, activated.selectedCameraId)
            )
        }
    }

    private fun flipPreviewCamera() {
        val current = CaptureSettings.snapshot(this)
        val currentOption = cameraOptions.firstOrNull { it.id == current.selectedCameraId }
        val targetFront = currentOption?.isFront != true
        val candidates = cameraOptions.filter { if (targetFront) it.isFront else it.isBack }
        if (candidates.isEmpty()) {
            showPreviewSettingsSheet(PreviewSettingsTab.CAMERAS)
            return
        }
        val remembered = CameraProfileStore.lastCamera(this, targetFront)
        val target = candidates.firstOrNull { it.id == remembered }
            ?: candidates.firstOrNull { it.logical }
            ?: candidates.first()
        selectPreviewCamera(target.id)
    }

    private fun showPreviewSettingsSheet(tab: PreviewSettingsTab = PreviewSettingsTab.CAMERAS) {
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Pare a captura antes de alterar as configurações.", Toast.LENGTH_LONG).show()
            return
        }
        previewSettingsTab = tab
        renderPreviewSettingsSheetContent()
        previewSettingsScrim.visibility = View.VISIBLE
        previewSettingsSheet.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(previewSettingsSheet)
        previewSettingsScrim.alpha = 0f
        previewSettingsSheet.translationY = previewSettingsSheet.height.takeIf { it > 0 }?.toFloat() ?: dp(396).toFloat()
        previewSettingsScrim.animate().alpha(1f).setDuration(180L).start()
        previewSettingsSheet.animate().translationY(0f).setDuration(220L).start()
    }

    private fun hidePreviewSettingsSheet(animated: Boolean = true) {
        if (!::previewSettingsSheet.isInitialized || previewSettingsSheet.visibility != View.VISIBLE) return
        previewSettingsScrim.animate().cancel()
        previewSettingsSheet.animate().cancel()
        val finish = {
            previewSettingsScrim.visibility = View.GONE
            previewSettingsSheet.visibility = View.GONE
            previewSettingsScrim.alpha = 1f
            previewSettingsSheet.translationY = 0f
        }
        if (!animated) {
            finish()
            return
        }
        previewSettingsScrim.animate().alpha(0f).setDuration(150L).start()
        previewSettingsSheet.animate()
            .translationY(previewSettingsSheet.height.toFloat().coerceAtLeast(dp(300).toFloat()))
            .setDuration(190L)
            .withEndAction { finish() }
            .start()
    }

    private fun selectPreviewSettingsTab(tab: PreviewSettingsTab) {
        Haptics.tap(this)
        when (tab) {
            PreviewSettingsTab.PHOTO -> if (!previewPhotoMode) {
                switchPreviewFunction(CameraProfileStore.FunctionMode.PHOTO)
            }
            PreviewSettingsTab.VIDEO -> if (previewPhotoMode) {
                switchPreviewFunction(CameraProfileStore.FunctionMode.VIDEO)
            }
            else -> Unit
        }
        previewSettingsTab = tab
        renderPreviewSettingsSheetContent()
    }


    private fun renderPreviewSettingsSheetContent() {
        if (!::previewSettingsSheet.isInitialized) return
        val tabs = listOf(
            Triple(PreviewSettingsTab.GENERAL, previewSettingsGeneralTab, previewSettingsGeneralTabText) to previewSettingsGeneralTabIcon,
            Triple(PreviewSettingsTab.CAMERAS, previewSettingsCamerasTab, previewSettingsCamerasTabText) to previewSettingsCamerasTabIcon,
            Triple(PreviewSettingsTab.PHOTO, previewSettingsPhotoTab, previewSettingsPhotoTabText) to previewSettingsPhotoTabIcon,
            Triple(PreviewSettingsTab.VIDEO, previewSettingsVideoTab, previewSettingsVideoTabText) to previewSettingsVideoTabIcon,
            Triple(PreviewSettingsTab.PRO, previewSettingsProTab, previewSettingsProTabText) to previewSettingsProTabIcon
        )
        tabs.forEach { (tabData, icon) ->
            val (tab, container, label) = tabData
            val selected = previewSettingsTab == tab
            container.setBackgroundResource(
                if (selected) R.drawable.bg_preview_settings_tab_selected
                else R.drawable.bg_preview_settings_tab_unselected
            )
            val color = getColor(if (selected) android.R.color.white else R.color.text_secondary)
            label.setTextColor(color)
            label.setTypeface(label.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            icon.setColorFilter(color)
        }

        val settings = CaptureSettings.snapshot(this)
        val cameraLabel = CameraLensCatalog.labelFor(this, settings.selectedCameraId)
        val profileMode = if (previewPhotoMode) CameraProfileStore.FunctionMode.PHOTO else CameraProfileStore.FunctionMode.VIDEO
        when (previewSettingsTab) {
            PreviewSettingsTab.CAMERAS -> {
                previewSettingsContentTitle.text = "Perfis por câmera"
                previewSettingsContentSubtitle.text = "Cada câmera mantém um perfil próprio de ${if (previewPhotoMode) "foto" else "vídeo"} e cada modo de vídeo fica isolado por FPS."
                previewCameraProfilesScroll.visibility = View.VISIBLE
                previewSettingsActionsContainer.removeAllViews()
                previewSettingsActionsScroll.visibility = View.GONE
                previewManageProfilesButton.text = "Gerenciar ajustes avançados"
                renderCameraProfileCards(profileMode)
            }
            PreviewSettingsTab.GENERAL -> {
                previewSettingsContentTitle.text = "Configurar • $cameraLabel"
                previewSettingsContentSubtitle.text = if (previewPhotoMode) {
                    "Ajustes úteis de foto e câmera em uma única tela."
                } else {
                    "Vídeo, câmera e controles Pro em uma única tela. Alterações são aplicadas ao preview."
                }
                previewCameraProfilesScroll.visibility = View.GONE
                previewSettingsActionsScroll.visibility = View.VISIBLE
                previewManageProfilesButton.text = "Abrir todos os ajustes"
                renderUnifiedPreviewSettings(settings)
            }
            PreviewSettingsTab.PHOTO -> {
                previewSettingsContentTitle.text = "Foto • $cameraLabel"
                previewSettingsContentSubtitle.text = "Foco, exposição, cor e processamento salvos somente para esta câmera."
                previewCameraProfilesScroll.visibility = View.GONE
                previewSettingsActionsScroll.visibility = View.VISIBLE
                previewManageProfilesButton.text = "Ajustes avançados de foto"
                renderPhotoSettingsActions(settings)
            }
            PreviewSettingsTab.VIDEO -> {
                previewSettingsContentTitle.text = "Vídeo • $cameraLabel"
                previewSettingsContentSubtitle.text = "Resolução, FPS, codec e imagem independentes para esta câmera, sem afetar os outros modos."
                previewCameraProfilesScroll.visibility = View.GONE
                previewSettingsActionsScroll.visibility = View.VISIBLE
                previewManageProfilesButton.text = "Ajustes avançados de vídeo"
                renderVideoSettingsActions(settings)
            }
            PreviewSettingsTab.PRO -> {
                previewSettingsContentTitle.text = "Pro • $cameraLabel"
                previewSettingsContentSubtitle.text = "Controles reais do preview. Salva no perfil atual sem alterar o motor de gravação."
                previewCameraProfilesScroll.visibility = View.GONE
                previewSettingsActionsScroll.visibility = View.VISIBLE
                previewManageProfilesButton.text = "Ajustes avançados completos"
                renderProSettingsActions(settings)
            }
        }
        previewSettingsSheet.requestLayout()
    }

    private fun renderCameraProfileCards(mode: CameraProfileStore.FunctionMode) {
        previewCameraProfilesRow.removeAllViews()
        val current = CaptureSettings.snapshot(this)
        val options = cameraOptions
        if (options.isEmpty()) return

        // Até cinco câmeras ficam na mesma linha. Acima disso, o painel usa linhas
        // compactas de quatro colunas. Não há rolagem lateral nem cartão parcialmente visível.
        val columns = if (options.size <= 5) options.size else 4
        val rowCount = (options.size + columns - 1) / columns
        val gap = dp(2)
        val cardHeight = dp(if (columns >= 5) 112 else 106)
        val profileAreaHeight = rowCount * cardHeight + (rowCount - 1).coerceAtLeast(0) * gap
        previewCameraProfilesScroll.layoutParams = previewCameraProfilesScroll.layoutParams.apply {
            height = profileAreaHeight.coerceAtMost(dp(218))
        }

        options.chunked(columns).forEachIndexed { rowIndex, rowOptions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            rowOptions.forEachIndexed { columnIndex, option ->
                CameraProfileStore.ensureProfiles(
                    this,
                    option.id,
                    current.copy(selectedCameraId = option.id, zoomRatio = 1f)
                )
                val summary = CameraProfileStore.summary(this, option.id, mode, current)
                val selected = option.id == current.selectedCameraId
                val root = FrameLayout(this).apply {
                    setBackgroundResource(
                        if (selected) R.drawable.bg_camera_profile_card_selected
                        else R.drawable.bg_camera_profile_card
                    )
                    isClickable = true
                    isFocusable = true
                    clipChildren = false
                    clipToPadding = false
                    foreground = ContextCompat.getDrawable(
                        this@CaptureActivity,
                        android.R.drawable.list_selector_background
                    )
                    contentDescription = "Selecionar ${option.label} ${cameraProfileBadge(option)}"
                    setOnClickListener { selectPreviewCamera(option.id) }
                }

                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(3), dp(5), dp(3), dp(4))
                }
                val lensBadge = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_profile_lens)
                    setPadding(dp(3), dp(3), dp(3), dp(2))
                    clipChildren = false
                    clipToPadding = false
                }
                lensBadge.addView(ImageView(this).apply {
                    setImageResource(cameraProfileIcon(option))
                    setColorFilter(getColor(android.R.color.white))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                lensBadge.addView(TextView(this).apply {
                    text = cameraProfileBadge(option)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 8.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(13)))
                content.addView(lensBadge, LinearLayout.LayoutParams(dp(44), dp(44)))

                content.addView(TextView(this).apply {
                    text = cameraProfileTitle(option)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(getColor(android.R.color.white))
                    textSize = if (columns >= 5) 8.5f else 9.5f
                    maxLines = 2
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

                content.addView(TextView(this).apply {
                    text = profileName(summary, option)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(if (selected) getColor(R.color.record_yellow) else AppearanceStore.palette(this@CaptureActivity).accent)
                    textSize = 8.2f
                    maxLines = 1
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(15)))

                root.addView(
                    content,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                if (selected) {
                    root.addView(TextView(this).apply {
                        text = "✓"
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(getColor(android.R.color.black))
                        setBackgroundResource(R.drawable.bg_camera_chip_selected_yellow)
                        textSize = 9f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(3)
                        marginEnd = dp(3)
                    })
                }

                row.addView(root, LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    if (columnIndex < columns - 1) marginEnd = gap
                })
            }
            repeat(columns - rowOptions.size) { spacerIndex ->
                row.addView(View(this), LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    if (rowOptions.size + spacerIndex < columns - 1) marginEnd = gap
                })
            }
            previewCameraProfilesRow.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cardHeight).apply {
                    if (rowIndex < rowCount - 1) bottomMargin = gap
                }
            )
        }
        previewCameraProfilesScroll.post { previewCameraProfilesScroll.scrollTo(0, 0) }
    }

    private fun renderUnifiedPreviewSettings(settings: CaptureSettings.Snapshot) {
        previewSettingsActionsContainer.removeAllViews()
        addPreviewSettingsAction("Câmera", CameraLensCatalog.labelFor(this, settings.selectedCameraId)) {
            selectPreviewSettingsTab(PreviewSettingsTab.CAMERAS)
        }
        if (!previewPhotoMode) {
            addPreviewSettingsAction("Resolução", CaptureSettings.resolutionLabel(settings.resolution)) { showLiveResolutionChoices() }
            addPreviewSettingsAction("FPS", "${settings.fps} FPS • medido ${String.format(java.util.Locale.US, "%.1f", previewMeasuredFps)}") { showLiveFpsChoices() }
            addPreviewSettingsAction("Leitura do sensor", when (SensorPixelModeSettings.selected(this)) {
                SensorPixelModeSettings.NORMAL -> "Normal pixel mode"
                SensorPixelModeSettings.MAXIMUM_RESOLUTION -> "Maximum-resolution"
                else -> "Auto • Samsung HAL"
            }) { showSensorPixelModeChoices() }
            addPreviewSettingsAction("Codec e bitrate", "${codecLabel(settings.codec)} • ${settings.bitrateMbps} Mbps") { showLiveCodecAndBitrateMenu() }
        }
        addPreviewSettingsAction("Estabilização", stabilizationLabel(settings.stabilization)) { showLiveStabilizationChoices() }
        addPreviewSettingsAction("Exposição", exposureLabel(settings.exposureCompensation)) { showLiveExposureChoices() }
        addPreviewSettingsAction("Foco", focusLabel(settings.focusMode)) { showLiveFocusChoices() }
        addPreviewSettingsAction("Balanço de branco", whiteBalanceLabel(settings.whiteBalanceMode)) { showLiveWhiteBalanceChoices() }
        addPreviewSettingsAction("Temperatura / cor", yellowReductionLabel(settings.yellowReduction)) { showLiveYellowReductionChoices() }
        addPreviewSettingsAction("Anti-flicker", antibandingLabel(settings.antibanding)) { showLiveAntibandingChoices() }
        addPreviewSettingsAction(if (previewAeAfLocked) "Liberar AF/AE" else "Travar AF/AE", if (previewAeAfLocked) "Travado" else "Contínuo") { togglePreviewAeAfLock() }
        addPreviewSettingsAction("Grade 3×3", if (isPreviewGridEnabled()) "Ativada" else "Desativada") { togglePreviewGrid() }
    }
    private fun renderGeneralSettingsActions(settings: CaptureSettings.Snapshot) {
        previewSettingsActionsContainer.removeAllViews()
        addPreviewSettingsAction(
            "Perfil ativo",
            activePreviewProfileSummary(settings)
        ) { selectPreviewSettingsTab(PreviewSettingsTab.CAMERAS) }
        addPreviewSettingsAction("Como funciona", "Preview configura o perfil ativo. Widget/tela preta usam o perfil salvo, sem reduzir qualidade escondido.") {
            showPreviewProfileExplanation()
        }
        addPreviewSettingsAction("Ajustes rápidos", "Exposição, foco, cor, estabilização, codec e bitrate") {
            showLiveSettingsMenu()
        }
        addPreviewSettingsAction("Redefinir perfil atual", "Restaura somente esta câmera e esta função", destructive = true) {
            resetActiveCameraProfile()
        }
    }

    private fun renderPhotoSettingsActions(settings: CaptureSettings.Snapshot) {
        previewSettingsActionsContainer.removeAllViews()
        val features = selectedCameraFeatures()
        if (hasMultipleExposedValues(CaptureSettings.supportedWhiteBalanceValues) { value ->
                features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
            }) {
            addPreviewSettingsAction("Balanço de branco", whiteBalanceLabel(settings.whiteBalanceMode)) { showLiveWhiteBalanceChoices() }
        }
        if (features?.exposureCompensationSupported != false) {
            addPreviewSettingsAction("Exposição", exposureLabel(settings.exposureCompensation)) { showLiveExposureChoices() }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                    CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
                    CaptureSettings.FOCUS_AUTO,
                    CaptureSettings.FOCUS_OFF
                )
            ) { value -> focusSupport(settings, features, value) }) {
            addPreviewSettingsAction("Foco", focusLabel(settings.focusMode)) { showLiveFocusChoices() }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.STABILIZATION_PREVIEW,
                    CaptureSettings.STABILIZATION_EIS,
                    CaptureSettings.STABILIZATION_OIS,
                    CaptureSettings.STABILIZATION_OFF
                )
            ) { value -> stabilizationSupport(settings, features, value) }) {
            addPreviewSettingsAction("Estabilização", stabilizationLabel(settings.stabilization)) { showLiveStabilizationChoices() }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.PROCESSING_AUTO,
                    CaptureSettings.PROCESSING_OFF,
                    CaptureSettings.PROCESSING_FAST,
                    CaptureSettings.PROCESSING_HIGH_QUALITY
                )
            ) { value -> processingSupport(settings, features, value, noise = true) }) {
            addPreviewSettingsAction("Redução de ruído", processingLabel(settings.noiseReduction)) { showLiveProcessingChoices(noise = true) }
        }
        if (hasMultipleExposedValues(CaptureSettings.supportedYellowReductionValues) { value ->
                yellowReductionSupport(features, value)
            }) {
            addPreviewSettingsAction("Neutralizar amarelo", yellowReductionLabel(settings.yellowReduction)) { showLiveYellowReductionChoices() }
        }
    }

    private fun renderVideoSettingsActions(settings: CaptureSettings.Snapshot) {
        previewSettingsActionsContainer.removeAllViews()
        val features = selectedCameraFeatures()
        addPreviewSettingsAction("Resolução", CaptureSettings.resolutionLabel(settings.resolution)) { showLiveResolutionChoices() }
        addPreviewSettingsAction("Taxa de quadros", "${settings.fps} FPS") { showLiveFpsChoices() }
        val hdrSupport = features?.hdrHlg10 ?: Support.UNVERIFIED
        if (
            settings.codec != CaptureSettings.CODEC_AVC &&
            HardwareSupportPolicy.shouldExpose(hdrSupport)
        ) {
            addPreviewSettingsAction("HDR HLG10", if (settings.hdrHlg10) "Ativado" else "Desativado") { showLiveHdrChoices() }
        }
        addPreviewSettingsAction("Codec e bitrate", "${codecLabel(settings.codec)} • ${settings.bitrateMbps} Mbps") { showLiveCodecAndBitrateMenu() }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.STABILIZATION_PREVIEW,
                    CaptureSettings.STABILIZATION_EIS,
                    CaptureSettings.STABILIZATION_OIS,
                    CaptureSettings.STABILIZATION_OFF
                )
            ) { value -> stabilizationSupport(settings, features, value) }) {
            addPreviewSettingsAction("Estabilização", stabilizationLabel(settings.stabilization)) { showLiveStabilizationChoices() }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                    CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
                    CaptureSettings.FOCUS_AUTO,
                    CaptureSettings.FOCUS_OFF
                )
            ) { value -> focusSupport(settings, features, value) }) {
            addPreviewSettingsAction("Foco", focusLabel(settings.focusMode)) { showLiveFocusChoices() }
        }
        if (!settings.hdrHlg10 && settings.fps < CaptureModeStore.FPS_60) {
            addPreviewSettingsAction("Perfil de cor", CaptureSettings.colorProfileLabel(settings.colorProfile)) { showLiveColorProfileChoices() }
        }
        val processingCandidates = listOf(
            CaptureSettings.PROCESSING_AUTO,
            CaptureSettings.PROCESSING_OFF,
            CaptureSettings.PROCESSING_FAST,
            CaptureSettings.PROCESSING_HIGH_QUALITY
        )
        if (hasMultipleExposedValues(processingCandidates) { value ->
                processingSupport(settings, features, value, noise = true)
            }) {
            addPreviewSettingsAction("Redução de ruído", processingLabel(settings.noiseReduction)) { showLiveProcessingChoices(noise = true) }
        }
        if (hasMultipleExposedValues(processingCandidates) { value ->
                processingSupport(settings, features, value, noise = false)
            }) {
            addPreviewSettingsAction("Nitidez", processingLabel(settings.edgeMode)) { showLiveProcessingChoices(noise = false) }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.ANTIBANDING_AUTO,
                    CaptureSettings.ANTIBANDING_50HZ,
                    CaptureSettings.ANTIBANDING_60HZ,
                    CaptureSettings.ANTIBANDING_OFF
                )
            ) { value -> features?.antibandingSupport(value) ?: Support.UNVERIFIED }) {
            addPreviewSettingsAction("Anti-flicker", antibandingLabel(settings.antibanding)) { showLiveAntibandingChoices() }
        }
    }

    private fun renderProSettingsActions(settings: CaptureSettings.Snapshot) {
        previewSettingsActionsContainer.removeAllViews()
        val features = selectedCameraFeatures()
        addPreviewSettingsAction("Resumo real do perfil", activePreviewProfileSummary(settings)) {
            showPreviewProfileExplanation()
        }
        addPreviewSettingsAction(
            if (previewAeAfLocked) "Liberar AF/AE" else "Travar AF/AE no centro",
            if (previewAeAfLocked) "Volta ao foco/exposição contínuos do perfil" else "Trava foco e exposição no preview atual, quando a câmera permite"
        ) {
            togglePreviewAeAfLock()
        }
        addPreviewSettingsAction("Grade 3×3", if (isPreviewGridEnabled()) "Ativada" else "Desativada") {
            togglePreviewGrid()
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                    CaptureSettings.FOCUS_CONTINUOUS_PICTURE,
                    CaptureSettings.FOCUS_AUTO,
                    CaptureSettings.FOCUS_OFF
                )
            ) { value -> focusSupport(settings, features, value) }) {
            addPreviewSettingsAction("Foco", focusLabel(settings.focusMode)) { showLiveFocusChoices() }
        }
        if (features?.exposureCompensationSupported != false) {
            addPreviewSettingsAction("Exposição", exposureLabel(settings.exposureCompensation)) { showLiveExposureChoices() }
        }
        if (hasMultipleExposedValues(CaptureSettings.supportedWhiteBalanceValues) { value ->
                features?.whiteBalanceSupport(value) ?: Support.UNVERIFIED
            }) {
            addPreviewSettingsAction("Balanço de branco", whiteBalanceLabel(settings.whiteBalanceMode)) { showLiveWhiteBalanceChoices() }
        }
        if (hasMultipleExposedValues(
                listOf(
                    CaptureSettings.ANTIBANDING_AUTO,
                    CaptureSettings.ANTIBANDING_50HZ,
                    CaptureSettings.ANTIBANDING_60HZ,
                    CaptureSettings.ANTIBANDING_OFF
                )
            ) { value -> features?.antibandingSupport(value) ?: Support.UNVERIFIED }) {
            addPreviewSettingsAction("Anti-flicker", antibandingLabel(settings.antibanding)) { showLiveAntibandingChoices() }
        }
        addPreviewSettingsAction("Testar compatibilidade", configuredProfile(settings.fps, settings.resolution).capabilityDescription) {
            showPreviewCapabilityReport()
        }
    }

    private fun activePreviewProfileSummary(settings: CaptureSettings.Snapshot): String {
        val camera = CameraLensCatalog.labelFor(this, settings.selectedCameraId)
        val kind = if (previewPhotoMode) "Foto" else "Vídeo"
        val mode = if (previewPhotoMode) "foto" else "${CaptureSettings.resolutionLabel(settings.resolution)} • ${settings.fps} FPS"
        val hdr = if (settings.hdrHlg10) " • HDR" else ""
        return "$camera • $kind • $mode$hdr • ${settings.bitrateMbps} Mbps"
    }

    private fun showPreviewProfileExplanation() {
        val settings = CaptureSettings.snapshot(this)
        OneUiDialog.message(
            activity = this,
            title = "Perfil do preview",
            message = "O preview edita o perfil ativo: câmera, foto/vídeo e FPS atual. Suas configurações antigas são preservadas e cada câmera/FPS fica separado. A gravação final continua validando câmera e encoder no CaptureService, sem reduzir qualidade escondido. Perfil atual: ${activePreviewProfileSummary(settings)}."
        )
    }

    private fun showPreviewCapabilityReport() {
        val settings = CaptureSettings.snapshot(this)
        val profile = configuredProfile(settings.fps, settings.resolution)
        OneUiDialog.message(
            activity = this,
            title = "Compatibilidade do perfil",
            message = "${activePreviewProfileSummary(settings)}\n\n${profile.capabilityDescription}\n\nA confirmação final acontece ao iniciar a gravação; se a câmera/encoder recusarem, o app não muda resolução/FPS em silêncio."
        )
    }

    private fun isPreviewGridEnabled(): Boolean =
        getSharedPreferences(PREVIEW_UI_PREFS, MODE_PRIVATE).getBoolean(KEY_PREVIEW_GRID, false)

    private fun togglePreviewGrid() {
        val enabled = !isPreviewGridEnabled()
        getSharedPreferences(PREVIEW_UI_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PREVIEW_GRID, enabled)
            .apply()
        if (::previewGridOverlay.isInitialized) {
            previewGridOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        Toast.makeText(this, if (enabled) "Grade 3×3 ativada" else "Grade 3×3 desativada", Toast.LENGTH_SHORT).show()
        renderPreviewSettingsSheetContent()
    }

    private fun togglePreviewAeAfLock() {
        if (!previewOpen || !::previewTexture.isInitialized) return
        if (previewAeAfLocked) {
            idlePreview.unlockFocusAndExposure()
            previewAeAfLocked = false
            showFocusResult(true)
            Toast.makeText(this, "AF/AE liberado", Toast.LENGTH_SHORT).show()
            renderPreviewSettingsSheetContent()
            return
        }
        showFocusIndicator(previewTexture.width / 2f, previewTexture.height / 2f)
        idlePreview.lockFocusAndExposureAt(0.5f, 0.5f) { locked ->
            previewAeAfLocked = locked
            showFocusResult(locked)
            Toast.makeText(
                this,
                if (locked) "AF/AE travado no centro do preview" else "A câmera não permitiu travar AF/AE neste modo",
                Toast.LENGTH_SHORT
            ).show()
            renderPreviewSettingsSheetContent()
        }
    }

    private fun addPreviewSettingsAction(
        title: String,
        subtitle: String,
        destructive: Boolean = false,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(16), dp(9), dp(12), dp(9))
            setBackgroundResource(R.drawable.bg_profile_action)
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@CaptureActivity, android.R.drawable.list_selector_background)
            setOnClickListener {
                Haptics.tap(this@CaptureActivity)
                action()
            }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@CaptureActivity).apply {
                text = title
                setTextColor(getColor(if (destructive) R.color.record_red else R.color.text_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@CaptureActivity).apply {
                text = subtitle
                setTextColor(getColor(R.color.text_secondary))
                textSize = 9.5f
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            alpha = 0.72f
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        previewSettingsActionsContainer.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun resetActiveCameraProfile() {
        val current = CaptureSettings.snapshot(this)
        val cameraId = current.selectedCameraId ?: return
        val mode = if (previewPhotoMode) CameraProfileStore.FunctionMode.PHOTO else CameraProfileStore.FunctionMode.VIDEO
        OneUiDialog.confirm(
            activity = this,
            title = "Redefinir perfil",
            message = "Somente o perfil ${if (previewPhotoMode) "de foto" else "de vídeo"} de ${CameraLensCatalog.labelFor(this, cameraId)} será restaurado.",
            positiveLabel = "Redefinir",
            destructive = true
        ) {
            CameraProfileStore.resetProfile(this, cameraId, mode)
            val defaults = current.copy(
                resolution = CaptureSettings.RESOLUTION_4K,
                fps = 60,
                codec = CaptureSettings.CODEC_HEVC,
                bitrateMbps = CaptureSettings.defaultBitrateMbps(CaptureSettings.RESOLUTION_4K, 60, CaptureSettings.CODEC_HEVC),
                hdrHlg10 = false,
                colorProfile = CaptureSettings.COLOR_NATURAL,
                stabilization = CaptureSettings.STABILIZATION_OFF,
                focusMode = if (previewPhotoMode) CaptureSettings.FOCUS_CONTINUOUS_PICTURE else CaptureSettings.FOCUS_CONTINUOUS_VIDEO,
                noiseReduction = CaptureSettings.PROCESSING_FAST,
                edgeMode = CaptureSettings.PROCESSING_FAST,
                antibanding = CaptureSettings.ANTIBANDING_AUTO,
                whiteBalanceMode = CaptureSettings.WHITE_BALANCE_AUTO,
                yellowReduction = CaptureSettings.YELLOW_REDUCTION_AUTO,
                exposureCompensation = 0,
                zoomRatio = 1f,
                selectedCameraId = cameraId
            )
            CameraProfileStore.activate(this, cameraId, mode, defaults)
            updatePreviewSettingsText()
            renderPreviewQuickControls()
            renderPreviewSettingsSheetContent()
            restartPreviewForUpdatedSettings()
        }
    }


    private fun cameraProfileIcon(option: CameraLensCatalog.Option): Int = when {
        option.isFront -> R.drawable.ic_camera_profile_selfie
        option.label.startsWith("Ultra-wide") -> R.drawable.ic_camera_profile_ultra
        option.label.startsWith("Super tele") -> R.drawable.ic_camera_profile_supertele
        option.label.startsWith("Tele") -> R.drawable.ic_camera_profile_tele
        option.label.startsWith("Externa") -> R.drawable.ic_camera_profile_external
        else -> R.drawable.ic_camera_profile_main
    }

    private fun cameraProfileBadge(option: CameraLensCatalog.Option): String = when {
        option.isFront -> "Self"
        option.label.startsWith("Ultra-wide") -> "0,6x"
        option.label.startsWith("Super tele") -> "5x"
        option.label.startsWith("Tele") -> "3x"
        option.label.startsWith("Externa") -> "USB"
        else -> "1x"
    }

    private fun cameraProfileTitle(option: CameraLensCatalog.Option): String = when {
        option.isFront -> option.label
        option.label.startsWith("Ultra-wide") -> "Ultra-wide"
        option.label.startsWith("Super tele") -> "Super tele"
        option.label.startsWith("Tele") -> "Tele"
        option.label.startsWith("Externa") -> "Externa"
        else -> "Wide"
    }

    private fun profileName(
        summary: CameraProfileStore.Summary,
        option: CameraLensCatalog.Option
    ): String = when {
        summary.mode == CameraProfileStore.FunctionMode.PHOTO && option.isFront -> "Selfie"
        summary.mode == CameraProfileStore.FunctionMode.PHOTO -> "Foto"
        summary.fps >= 120 -> "HFR"
        summary.hdr -> "HDR"
        else -> "Padrão"
    }

    private fun selectPreviewLensShortcut(shortcut: Float) {
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return
        val current = CaptureSettings.snapshot(this)
        if (cameraOptions.firstOrNull { it.id == current.selectedCameraId }?.isFront != true) {
            BackgroundRecordingZoom.set(this, shortcut)
        }
        val currentOption = cameraOptions.firstOrNull { it.id == current.selectedCameraId }
        if (currentOption?.isFront == true) {
            if (kotlin.math.abs(shortcut - 1f) < 0.05f) applyPreviewZoom(1f)
            return
        }
        val target = CameraLensCatalog.optionForShortcut(cameraOptions, shortcut)
        if (target != null && target.id != current.selectedCameraId) {
            val targetZoom = if (target.logical) {
                shortcut.coerceIn(target.minZoom, target.maxZoom)
            } else {
                1f
            }
            selectPreviewCamera(target.id, requestedZoom = targetZoom)
            return
        }
        val zoom = when {
            target?.logical == true -> shortcut.coerceIn(target.minZoom, target.maxZoom)
            currentOption?.logical == true -> shortcut.coerceIn(currentOption.minZoom, currentOption.maxZoom)
            else -> 1f
        }
        applyPreviewZoom(zoom)
    }

    private fun applyPreviewZoom(ratio: Float) {
        val current = CaptureSettings.snapshot(this)
        val option = cameraOptions.firstOrNull { it.id == current.selectedCameraId }
        if (option != null && (ratio < option.minZoom - 0.02f || ratio > option.maxZoom + 0.02f)) return
        if (kotlin.math.abs(current.zoomRatio - ratio) < 0.01f) return
        Haptics.tap(this)
        CaptureSettings.save(this, current.copy(zoomRatio = ratio))
        renderPreviewZoomControls(ratio)
        restartPreviewForUpdatedSettings()
    }

    private fun renderPreviewZoomControls(ratio: Float = CaptureSettings.snapshot(this).zoomRatio) {
        if (!::previewZoom1Button.isInitialized) return
        val choices = listOf(
            0.6f to previewZoom06Button,
            1f to previewZoom1Button,
            3f to previewZoom3Button,
            5f to previewZoom5Button
        )
        val settings = CaptureSettings.snapshot(this)
        val selectedOption = cameraOptions.firstOrNull { it.id == settings.selectedCameraId }
        val physicalShortcut = selectedOption?.takeUnless { it.logical || it.isFront }
            ?.let(CameraLensCatalog::shortcutRatio)
        choices.forEach { (value, button) ->
            val target = CameraLensCatalog.optionForShortcut(cameraOptions, value)
            val supported = when {
                selectedOption?.isFront == true -> kotlin.math.abs(value - 1f) < 0.05f
                target != null -> true
                selectedOption == null -> false
                else -> value >= selectedOption.minZoom - 0.02f && value <= selectedOption.maxZoom + 0.02f
            }
            val selected = supported && when {
                physicalShortcut != null -> kotlin.math.abs(physicalShortcut - value) < 0.05f
                else -> kotlin.math.abs(ratio - value) < 0.08f
            }
            button.visibility = if (supported) View.VISIBLE else View.GONE
            button.isEnabled = supported
            button.setBackgroundResource(
                if (selected) R.drawable.bg_camera_chip_selected_yellow
                else R.drawable.bg_camera_chip_unselected
            )
            button.setTextColor(getColor(if (selected) android.R.color.black else android.R.color.white))
            button.alpha = when { selected -> 1f; supported -> 0.88f; else -> 0.28f }
        }
    }

    private fun selectPreviewCamera(cameraId: String?, requestedZoom: Float? = null) {
        if (CaptureStateStore.isBusy(this) || photoBusy || PhotoCaptureStateStore.isBusy(this)) return
        val resolvedId = cameraId ?: CameraLensCatalog.resolveCameraId(
            this,
            null,
            CaptureSettings.snapshot(this).resolution
        ) ?: return
        val current = CaptureSettings.snapshot(this)
        if (current.selectedCameraId == resolvedId) {
            if (requestedZoom != null) applyPreviewZoom(requestedZoom) else hidePreviewSettingsSheet()
            return
        }
        Haptics.tap(this)
        if (previewAeAfLocked) {
            idlePreview.unlockFocusAndExposure()
            previewAeAfLocked = false
        }
        CameraProfileStore.saveCurrent(this)
        val mode = if (previewPhotoMode) CameraProfileStore.FunctionMode.PHOTO else CameraProfileStore.FunctionMode.VIDEO
        var activated = CameraProfileStore.activate(this, resolvedId, mode, current.copy(selectedCameraId = resolvedId, zoomRatio = 1f))
        if (requestedZoom != null && kotlin.math.abs(activated.zoomRatio - requestedZoom) >= 0.01f) {
            activated = activated.copy(zoomRatio = requestedZoom)
            CaptureSettings.save(this, activated)
        }
        CameraProfileStore.rememberCamera(this, resolvedId, CameraLensCatalog.isFront(this, resolvedId))
        CaptureStateStore.clearEffectiveMode(this)
        synchronizeSelectedModeWithCapabilities()
        renderPreviewZoomControls(activated.zoomRatio)
        applyPreviewSurfaceBuffer()
        applyPreviewSurfaceLayout()
        updatePreviewSettingsText()
        renderPreviewSettingsSheetContent()
        restartPreviewForUpdatedSettings()
    }

    private fun renderPreviewQuickControls() {
        if (!::previewRecordButton.isInitialized) return
        val settings = CaptureSettings.snapshot(this)
        renderPreviewZoomControls(settings.zoomRatio)
        val recordingBusy = CaptureStateStore.isBusy(this)
        val captureBusy = photoBusy || PhotoCaptureStateStore.isBusy(this)
        val controlsLocked = recordingBusy || captureBusy
        val stopping = UiBehaviorRules.isRecordingFinalizing(CaptureStateStore.currentState(this))
        val fpsButtons = listOf(
            CaptureModeStore.FPS_30 to previewFps30Button,
            CaptureModeStore.FPS_60 to previewFps60Button
        )
        fpsButtons.forEach { (fps, button) ->
            val selected = settings.fps == fps
            val capability = modeCatalog().profile(fps)
            val visible = capability.available
            val selectable = visible && !recordingBusy && !captureBusy && capability.selectable
            button.visibility = if (visible) View.VISIBLE else View.GONE
            button.isEnabled = selectable
            button.alpha = if (selectable || selected) 1f else 0.38f
            button.setBackgroundResource(if (selected) R.drawable.bg_camera_chip_selected_yellow else R.drawable.bg_camera_chip_unselected)
            button.setTextColor(getColor(if (selected) android.R.color.black else android.R.color.white))
        }

        val effectivePhotoMode = previewPhotoMode && !recordingBusy
        previewPhotoModeButton.isEnabled = !controlsLocked
        previewVideoModeButton.isEnabled = !controlsLocked
        previewPhotoModeButton.setBackgroundResource(
            if (effectivePhotoMode) R.drawable.bg_camera_chip_selected_yellow else R.drawable.bg_camera_chip_unselected
        )
        previewVideoModeButton.setBackgroundResource(
            if (!effectivePhotoMode) R.drawable.bg_camera_chip_selected_yellow else R.drawable.bg_camera_chip_unselected
        )
        previewPhotoModeButton.setTextColor(
            getColor(if (effectivePhotoMode) android.R.color.black else android.R.color.white)
        )
        previewVideoModeButton.setTextColor(
            getColor(if (!effectivePhotoMode) android.R.color.black else android.R.color.white)
        )

        previewFpsRow.visibility = if (effectivePhotoMode) View.GONE else View.VISIBLE
        previewRecordButton.visibility = if (effectivePhotoMode) View.GONE else View.VISIBLE
        previewPhotoButton.visibility = if (effectivePhotoMode) View.VISIBLE else View.GONE

        previewRecordButton.isEnabled = if (recordingBusy) !stopping else !captureBusy
        previewPhotoButton.isEnabled = !recordingBusy && !captureBusy
        previewSettingsButton.isEnabled = !recordingBusy && !captureBusy
        previewCameraSwitchButton.visibility = if (
            cameraOptions.any { it.isFront } && cameraOptions.any { it.isBack }
        ) View.VISIBLE else View.GONE
        previewOverlayButton.isEnabled = true
        previewQuickCloseButton.isEnabled = true
        previewRecordButton.alpha = if (previewRecordButton.isEnabled) 1f else 0.42f
        previewPhotoButton.alpha = if (previewPhotoButton.isEnabled) 1f else 0.42f
        previewSettingsButton.alpha = if (previewSettingsButton.isEnabled) 1f else 0.42f
        previewOverlayButton.alpha = if (previewOverlayButton.isEnabled) 1f else 0.42f
        previewQuickCloseButton.alpha = 1f
        previewRecordButton.setBackgroundResource(
            if (recordingBusy) R.drawable.bg_widget_button_yellow_outline_active else R.drawable.bg_camera_shutter_outer
        )
        val fillParams = (previewRecordFill.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        if (recordingBusy) {
            fillParams.width = dp(32)
            fillParams.height = dp(32)
            previewRecordFill.setBackgroundResource(R.drawable.bg_shutter_recording_square)
        } else {
            fillParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            fillParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            previewRecordFill.setBackgroundResource(R.drawable.bg_shutter_yellow_fill)
        }
        fillParams.gravity = Gravity.CENTER
        previewRecordFill.layoutParams = fillParams
        previewRecordButton.contentDescription = if (recordingBusy) "Parar gravação" else "Gravar vídeo"
        previewCameraSwitchButton.isEnabled = !controlsLocked
        previewCameraSwitchButton.alpha = if (previewCameraSwitchButton.isEnabled) 1f else 0.4f
    }

    private fun renderPreviewCaptureState(message: String, busy: Boolean) {
        if (!::previewContainer.isInitialized || !previewOpen) return
        val captureBusy = photoBusy || PhotoCaptureStateStore.isBusy(this)
        if ((busy || captureBusy) && ::previewSettingsSheet.isInitialized) hidePreviewSettingsSheet(animated = false)
        val keepVideoLive = busy && activePreviewVideoCapture && !previewDisabledForCurrentRecording
        val keepPhotoLive = captureBusy && activePreviewPhotoCapture
        val pauseImage = (captureBusy && !keepPhotoLive) || (busy && !keepVideoLive)
        previewPausedScrim.visibility = if (pauseImage) View.VISIBLE else View.GONE
        previewPausedText.visibility = if (pauseImage) View.VISIBLE else View.GONE
        previewPausedText.text = when {
            captureBusy && !keepPhotoLive -> "Captura externa em qualidade máxima.\nO preview não divide recursos."
            busy && !keepVideoLive -> "Captura externa ou fallback da câmera.\nO encoder está com prioridade total."
            else -> ""
        }
        previewPerformanceText.text = when {
            captureBusy && keepPhotoLive -> "Foto de teste • preview e captura na mesma sessão"
            captureBusy -> "Foto externa • câmera exclusiva para máxima qualidade"
            busy && keepVideoLive -> "Vídeo de teste • preview e encoder compartilhando a sessão"
            busy -> "Vídeo externo • câmera exclusiva para máxima qualidade"
            else -> "Modo de configuração • alterações salvas em tempo real"
        }
        if (message.isNotBlank() && (busy || captureBusy)) previewSettingsText.text = message
        else updatePreviewSettingsText()
        renderPreviewQuickControls()
    }

    private fun setPreviewTopControlsVisible(visible: Boolean) {
        previewTopControlsVisible = visible
        if (!::previewTopPanel.isInitialized || !::previewShowTopButton.isInitialized) return
        previewTopPanel.visibility = if (visible) View.VISIBLE else View.GONE
        previewShowTopButton.visibility = if (visible) View.GONE else View.VISIBLE
        previewQuickCloseButton.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            previewTopPanel.requestApplyInsets()
        } else {
            previewShowTopButton.requestApplyInsets()
            previewQuickCloseButton.requestApplyInsets()
        }
        applyPreviewSurfaceLayout()
    }

    private fun updatePreviewSurfaceFrameRate(targetFps: Int = CaptureSettings.snapshot(this).fps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        val rates = display?.supportedModes?.map { it.refreshRate }?.filter { it > 0f }.orEmpty()
        val maximumRate = rates.maxOrNull() ?: display?.refreshRate?.takeIf { it > 0f } ?: 60f
        val minimumUsefulRate = minOf(targetFps.toFloat(), maximumRate)
        val requestedRate = rates.filter { it + 0.1f >= minimumUsefulRate }.maxOrNull() ?: maximumRate
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    requestedRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(requestedRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    private fun releasePreviewSurface() {
        CameraResourceCoordinator.unregisterPreview(previewCoordinatorToken)
        previewSurface?.let(CameraPreviewRegistry::clear)
        // A Surface pertence ao SurfaceView; o app apenas remove as referências.
        previewSurface = null
    }

    private fun requestClosePreview() {
        val recordingBusy = CaptureStateStore.isBusy(this)
        val captureBusy = photoBusy || PhotoCaptureStateStore.isBusy(this)
        when {
            recordingBusy -> {
                // Fechar o preview nunca equivale a mandar parar. Mantemos a
                // Surface viva até o vídeo terminar e apenas ocultamos a UI.
                closePreviewAfterCapture = true
                previewUserRequested = false
                pendingPreviewOpen = false
                previewOpen = false
                mainHandler.removeCallbacks(previewUpdateRunnable)
                previewContainer.visibility = View.INVISIBLE
            }
            captureBusy -> {
                closePreviewAfterCapture = true
                Toast.makeText(this, "Fechando o preview assim que a foto for salva…", Toast.LENGTH_SHORT).show()
            }
            else -> closeCameraPreview()
        }
    }

    private fun dismissPreviewForNavigation() {
        mainHandler.removeCallbacks(previewUpdateRunnable)
        previewUserRequested = false
        pendingPreviewOpen = false
        previewOpen = false
        closePreviewAfterCapture = false
        recordingRequestedFromPreview = false
        CameraResourceCoordinator.unregisterPreview(previewCoordinatorToken)
        CameraPreviewRegistry.clear()
        val captureBusy = CaptureStateStore.isBusy(this) || PhotoCaptureStateStore.isBusy(this)
        if (captureBusy) {
            if (::previewContainer.isInitialized) previewContainer.visibility = View.INVISIBLE
        } else {
            idlePreview.stop()
            releasePreviewSurface()
            if (::previewContainer.isInitialized) previewContainer.visibility = View.GONE
        }
    }

    private fun cleanupHiddenPreviewIfIdle() {
        if (previewOpen || CaptureStateStore.isBusy(this) || PhotoCaptureStateStore.isBusy(this)) return
        idlePreview.stop()
        releasePreviewSurface()
        if (::previewContainer.isInitialized) previewContainer.visibility = View.GONE
    }

    private fun releaseCameraPreview() {
        previewUserRequested = false
        previewOpen = false
        closePreviewAfterCapture = false
        mainHandler.removeCallbacks(previewUpdateRunnable)
        CameraResourceCoordinator.unregisterPreview(previewCoordinatorToken)
        idlePreview.stop()
        CameraPreviewRegistry.clear()
        releasePreviewSurface()
        if (::previewContainer.isInitialized) previewContainer.visibility = View.GONE
    }

    private fun handleWidgetPermissionRequest(intent: Intent?) {
        if (intent?.action != ACTION_WIDGET_REQUEST_PERMISSIONS) return

        val mode = intent.getStringExtra(EXTRA_WIDGET_PERMISSION_MODE)
            ?: WIDGET_PERMISSION_VIDEO

        widgetPermissionRequestMode = mode
        intent.action = null
        intent.removeExtra(EXTRA_WIDGET_PERMISSION_MODE)

        mainHandler.postDelayed(
            {
                when (mode) {
                    WIDGET_PERMISSION_PHOTO -> {
                        if (hasCameraPermission()) {
                            widgetPermissionRequestMode = null
                            Toast.makeText(
                                this,
                                "Permissão da câmera já está liberada.",
                                Toast.LENGTH_SHORT
                            ).show()
                            ExpandedControlWidget.updateAll(this)
                        } else {
                            requestAppPermissions(
                                arrayOf(Manifest.permission.CAMERA),
                                REQUEST_PHOTO_PERMISSION
                            )
                        }
                    }

                    else -> {
                        val missing = missingRecordingPermissions()

                        if (missing.isEmpty()) {
                            widgetPermissionRequestMode = null
                            Toast.makeText(
                                this,
                                "Câmera e microfone já estão liberados.",
                                Toast.LENGTH_SHORT
                            ).show()
                            ExpandedControlWidget.updateAll(this)
                        } else {
                            requestAppPermissions(
                                missing,
                                REQUEST_RECORDING_PERMISSIONS
                            )
                        }
                    }
                }
            },
            WIDGET_PERMISSION_DIALOG_DELAY_MS
        )
    }

    private fun applySystemBarInsets() {
        initialPaddingLeft = mainRoot.paddingLeft
        initialPaddingTop = mainRoot.paddingTop
        initialPaddingRight = mainRoot.paddingRight
        initialPaddingBottom = mainRoot.paddingBottom
        initialNavigationPaddingLeft = bottomNavigationRoot.paddingLeft
        initialNavigationPaddingTop = bottomNavigationRoot.paddingTop
        initialNavigationPaddingRight = bottomNavigationRoot.paddingRight
        initialNavigationPaddingBottom = bottomNavigationRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialPaddingLeft + bars.left,
                initialPaddingTop + bars.top,
                initialPaddingRight + bars.right,
                initialPaddingBottom
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialNavigationPaddingLeft + bars.left,
                initialNavigationPaddingTop,
                initialNavigationPaddingRight + bars.right,
                initialNavigationPaddingBottom + bars.bottom
            )
            insets
        }

        applyPreviewSystemBarInsets()
        ViewCompat.requestApplyInsets(mainRoot)
        ViewCompat.requestApplyInsets(bottomNavigationRoot)
        ViewCompat.requestApplyInsets(previewContainer)
        ViewCompat.requestApplyInsets(previewSettingsSheet)
    }

    private fun applyPreviewSystemBarInsets() {
        val topLeft = previewTopPanel.paddingLeft
        val topTop = previewTopPanel.paddingTop
        val topRight = previewTopPanel.paddingRight
        val topBottom = previewTopPanel.paddingBottom
        val bottomLeft = previewBottomPanel.paddingLeft
        val bottomTop = previewBottomPanel.paddingTop
        val bottomRight = previewBottomPanel.paddingRight
        val bottomBottom = previewBottomPanel.paddingBottom
        val sheetLeft = previewSettingsSheet.paddingLeft
        val sheetTop = previewSettingsSheet.paddingTop
        val sheetRight = previewSettingsSheet.paddingRight
        val sheetBottom = previewSettingsSheet.paddingBottom
        val showInitialTopMargin = (previewShowTopButton.layoutParams as FrameLayout.LayoutParams).topMargin
        val closeInitialTopMargin = (previewQuickCloseButton.layoutParams as FrameLayout.LayoutParams).topMargin

        ViewCompat.setOnApplyWindowInsetsListener(previewTopPanel) { view, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            view.setPadding(topLeft, topTop + top, topRight, topBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(previewBottomPanel) { view, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            view.setPadding(bottomLeft, bottomTop, bottomRight, bottomBottom + bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(previewSettingsSheet) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val tappableElement = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val mandatoryGestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val safeBottom = maxOf(
                navigationBars.bottom,
                tappableElement.bottom,
                mandatoryGestures.bottom,
                displayCutout.bottom
            )
            view.setPadding(
                sheetLeft + maxOf(navigationBars.left, displayCutout.left),
                sheetTop,
                sheetRight + maxOf(navigationBars.right, displayCutout.right),
                sheetBottom + safeBottom + dp(4)
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(previewShowTopButton) { view, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = showInitialTopMargin + top
            view.layoutParams = params
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(previewQuickCloseButton) { view, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = closeInitialTopMargin + top
            view.layoutParams = params
            insets
        }
    }

    private fun selectRecordingMode(targetFps: Int) {
        if (CaptureStateStore.isBusy(this)) return

        val settings = CaptureSettings.snapshot(this)
        val capability = modeCatalog().profile(targetFps)
        if (!capability.selectable) {
            Haptics.error(this)
            val message = when (capability.source) {
                CaptureModeCatalog.Source.ANALYZING -> "Aguarde a análise da câmera e do encoder"
                else -> "$targetFps FPS não foi confirmado neste aparelho"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        CameraProfileStore.saveCurrent(this)
        val requestedResolution = settings.resolution
        val targetResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = requestedResolution,
            matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
            scanInProgress = capabilityMatrix == null && capabilityScanInProgress
        )
        CaptureSettings.saveResolutionForFps(this, targetFps, targetResolution)
        val profile = configuredProfile(targetFps, targetResolution)
        CaptureStateStore.clearEffectiveMode(this)
        CaptureSettings.updateResolutionAndFps(this, targetResolution, targetFps)
        settings.selectedCameraId?.let { cameraId ->
            CameraProfileStore.activate(
                this,
                cameraId,
                CameraProfileStore.FunctionMode.VIDEO,
                CaptureSettings.snapshot(this).copy(selectedCameraId = cameraId)
            )
        }
        val message = selectedModeStateMessage(profile)
        CaptureStateStore.update(this, message)
        renderState(message)
        renderRecordingMode(busy = false)
        updatePreviewSettingsText()
        renderPreviewQuickControls()
        renderPreviewSettingsSheetContent()
        ExpandedControlWidget.updateAll(this)
        if (previewOpen) restartPreviewForUpdatedSettings()
    }

    private fun refreshCapabilityMatrix(force: Boolean = false) {
        if (capabilityScanInProgress) {
            if (force) capabilityRescanPending = true
            return
        }
        if (CaptureStateStore.isBusy(this) || photoBusy) {
            capabilityRescanPending = true
            return
        }
        val cached = if (force) null else CaptureCapabilityMatrix.cached(this)
        if (cached != null) {
            applyCapabilityMatrix(cached)
            return
        }
        capabilityScanInProgress = true
        capabilityScanCompleted = false
        capabilityRescanPending = false
        lastCapabilityScanStartedAtMs = SystemClock.elapsedRealtime()
        renderRecordingMode(busy = false)
        renderPreviewQuickControls()
        runCatching {
            capabilityExecutor.execute {
                val result = runCatching { CaptureCapabilityMatrix.scan(applicationContext, force = force) }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    capabilityScanInProgress = false
                    capabilityScanCompleted = true
                    result.onSuccess(::applyCapabilityMatrix)
                        .onFailure { applyCapabilityMatrix(CaptureCapabilityMatrix.cached(this) ?: capabilityMatrix) }
                    if (capabilityRescanPending && !CaptureStateStore.isBusy(this) && !photoBusy) {
                        capabilityRescanPending = false
                        mainHandler.post { refreshCapabilityMatrix(force = force) }
                    }
                }
            }
        }.onFailure {
            capabilityScanInProgress = false
            capabilityScanCompleted = true
            applyCapabilityMatrix(CaptureCapabilityMatrix.cached(this) ?: capabilityMatrix)
        }
    }

    private fun applyCapabilityMatrix(matrix: CaptureCapabilityMatrix.Matrix?) {
        capabilityMatrix = matrix ?: capabilityMatrix
        cameraOptions = CameraLensCatalog.options(this).filter { it.isFront || it.logical }
        synchronizeSelectedCameraWithCatalog()
        capabilityMatrix?.let { detected ->
            val rearCameraIds = cameraOptions.filter { it.isBack }.mapTo(mutableSetOf()) { it.id }
            val rearMatrix = detected.copy(
                modes = detected.modes.filter { it.cameraId in rearCameraIds },
                cameras = detected.cameras.filter { it.cameraId in rearCameraIds }
            )
            if (rearMatrix.modes.isNotEmpty()) CaptureModeCatalog.remember(this, rearMatrix)
        }
        synchronizeSelectedModeWithCapabilities()
        alignStoredModeMessage()
        renderRecordingMode(busy = CaptureStateStore.isBusy(this))
        renderPreviewQuickControls()
        renderPreviewSettingsSheetContent()
    }

    private fun synchronizeSelectedModeWithCapabilities() {
        val settings = CaptureSettings.snapshot(this)
        val preferredResolution = preferredResolutionForFps(settings.fps)
        if (preferredResolution != settings.resolution) {
            CaptureSettings.updateResolutionAndFps(this, preferredResolution, settings.fps)
            CaptureStateStore.clearEffectiveMode(this)
            return
        }
        val profile = configuredProfile(settings.fps, settings.resolution)
        if (!profile.selectable) CaptureStateStore.clearEffectiveMode(this)
    }

    private fun synchronizeSelectedCameraWithCatalog() {
        if (cameraOptions.isEmpty()) return
        val current = CaptureSettings.snapshot(this)
        val currentOption = cameraOptions.firstOrNull { it.id == current.selectedCameraId }
        val selectedId = when {
            currentOption?.isFront == true -> currentOption.id
            else -> CameraLensCatalog.resolveRecordingCameraId(this, current.selectedCameraId, current.resolution)
        } ?: cameraOptions.first().id
        val migratedZoom = if (currentOption?.isBack == true && !currentOption.logical) {
            CameraLensCatalog.shortcutRatio(currentOption)
        } else {
            current.zoomRatio
        }
        if (current.selectedCameraId == selectedId && kotlin.math.abs(current.zoomRatio - migratedZoom) < 0.01f) return
        CaptureSettings.save(this, current.copy(selectedCameraId = selectedId, zoomRatio = migratedZoom))
        CameraProfileStore.rememberCamera(this, selectedId, CameraLensCatalog.isFront(this, selectedId))
    }

    private fun selectedCapabilityMatrix(): CaptureCapabilityMatrix.Matrix? {
        val matrix = capabilityMatrix ?: return null
        val selectedId = CaptureSettings.snapshot(this).selectedCameraId
        val allOptions = CameraLensCatalog.options(this)
        val selectedOption = allOptions.firstOrNull { it.id == selectedId }
        if (selectedOption?.isFront == true) return matrix.forCamera(selectedId)
        val allowedIds = allOptions.filter { it.isBack }.mapTo(mutableSetOf()) { it.id }
        if (allowedIds.isEmpty() && selectedId != null) return matrix.forCamera(selectedId)
        return matrix.copy(
            modes = matrix.modes.filter { it.cameraId in allowedIds },
            cameras = matrix.cameras.filter { it.cameraId in allowedIds }
        )
    }

    private fun modeCatalog(): CaptureModeCatalog.Catalog = CaptureModeCatalog.resolve(
        context = this,
        matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
        scanInProgress = capabilityMatrix == null && capabilityScanInProgress
    )

    private fun preferredResolutionForFps(fpsValue: Int): String = CaptureModeCatalog.preferredResolution(
        context = this,
        fps = fpsValue,
        requestedResolution = CaptureSettings.resolutionForFps(this, fpsValue),
        matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
        scanInProgress = capabilityMatrix == null && capabilityScanInProgress
    )

    private fun configuredProfile(
        fpsValue: Int,
        resolutionValue: String = preferredResolutionForFps(fpsValue)
    ): CaptureModeCatalog.Profile = CaptureModeCatalog.resolveSelection(
        context = this,
        fps = fpsValue,
        resolution = resolutionValue,
        matrix = selectedCapabilityMatrix()?.takeIf { it.modes.isNotEmpty() },
        scanInProgress = capabilityMatrix == null && capabilityScanInProgress
    )

    private fun renderRecordingMode(busy: Boolean) {
        val settings = CaptureSettings.snapshot(this)
        val catalog = modeCatalog()
        val effectiveMode = if (busy) CaptureStateStore.effectiveMode(this) else null
        val selectedFps = effectiveMode?.fps ?: settings.fps

        fun configureButton(button: TextView, fps: Int) {
            val capability = catalog.profile(fps)
            val configured = configuredProfile(fps)
            val selected = selectedFps == fps
            val visible = capability.available
            val enabled = !busy && capability.selectable
            val label = if (selected && effectiveMode?.fps == fps) "${effectiveMode.resolutionLabel}\n$fps FPS" else configured.buttonText
            button.text = label
            button.visibility = if (visible) View.VISIBLE else View.GONE
            button.setBackgroundResource(if (selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_unselected)
            button.setTextColor(if (selected) AppearanceStore.palette(this).accent else getColor(R.color.text_primary))
            button.isEnabled = enabled
            button.alpha = if (enabled || selected) 1f else 0.32f
        }

        configureButton(fps30Button, CaptureModeStore.FPS_30)
        configureButton(fps60Button, CaptureModeStore.FPS_60)
        (fps30Button.parent as? View)?.visibility = if (fps30Button.visibility == View.VISIBLE || fps60Button.visibility == View.VISIBLE) View.VISIBLE else View.GONE

        val selectedProfile = configuredProfile(settings.fps, settings.resolution)
        val displayedResolution = effectiveMode?.resolutionLabel
            ?: selectedProfile.resolutionLabel.takeUnless { selectedProfile.source == CaptureModeCatalog.Source.UNAVAILABLE || selectedProfile.source == CaptureModeCatalog.Source.ANALYZING }
            ?: CaptureSettings.resolutionLabel(settings.resolution)
        val displayedFps = effectiveMode?.fps ?: settings.fps
        val codecLabel = when (settings.codec) {
            CaptureSettings.CODEC_HEVC -> "HEVC"
            CaptureSettings.CODEC_AVC -> "H.264"
            else -> "codec inválido"
        }
        val hdrLabel = if (settings.hdrHlg10) "HLG10 BT.2020" else "SDR BT.709"
        val colorLabel = if (settings.hdrHlg10) "cor HDR explícita" else CaptureSettings.colorProfileLabel(settings.colorProfile)
        val validationLabel = if (effectiveMode != null) " • modo real usado" else " • ${profileValidationText(selectedProfile)}"
        modeSummaryText.text = "$displayedResolution • $displayedFps FPS\n$hdrLabel • ${settings.bitrateMbps} Mbps"
        modeInfoText.text = "$codecLabel • $colorLabel • ${stabilizationUiLabel(settings.stabilization)} • áudio ${settings.audioBitrateKbps} kbps$validationLabel"
    }

    private fun selectedModeStateMessage(
        profile: CaptureModeCatalog.Profile = CaptureSettings.snapshot(this).let {
            configuredProfile(it.fps, it.resolution)
        }
    ): String = "Modo ${profile.inlineText} selecionado; ${selectionValidationLabel(profile)}"

    private fun alignStoredModeMessage() {
        if (CaptureStateStore.isBusy(this)) return
        val current = CaptureStateStore.currentState(this)
        if (!current.startsWith("Modo ")) return
        val aligned = selectedModeStateMessage()
        if (current == aligned) return
        CaptureStateStore.update(this, aligned)
        renderState(aligned)
    }

    private fun selectionValidationLabel(profile: CaptureModeCatalog.Profile): String = when (profile.source) {
        CaptureModeCatalog.Source.VALIDATED -> "capacidade validada na prática"
        CaptureModeCatalog.Source.DETECTED,
        CaptureModeCatalog.Source.CACHED -> "capacidade máxima confirmada"
        else -> "aguardando confirmação de hardware"
    }

    private fun profileValidationText(profile: CaptureModeCatalog.Profile): String = when (profile.source) {
        CaptureModeCatalog.Source.VALIDATED -> "validado na prática"
        CaptureModeCatalog.Source.DETECTED -> "máximo detectado"
        CaptureModeCatalog.Source.CACHED -> "máximo detectado anteriormente"
        CaptureModeCatalog.Source.ANALYZING -> "analisando suporte"
        CaptureModeCatalog.Source.UNAVAILABLE -> "modo indisponível"
        CaptureModeCatalog.Source.UNVERIFIED -> "aguardando confirmação"
    }

    private fun stabilizationUiLabel(value: String): String = when (value) {
        CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
        CaptureSettings.STABILIZATION_EIS -> "EIS"
        CaptureSettings.STABILIZATION_OIS -> "OIS"
        CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
        else -> "estabilização automática"
    }

    private fun selectedModeLabel(targetFps: Int, dedicatedSingleCapture: Boolean = false): String {
        val settings = CaptureSettings.snapshot(this)
        val profile = configuredProfile(targetFps)
        val resolution = profile.resolutionLabel.takeUnless {
            profile.source == CaptureModeCatalog.Source.UNAVAILABLE ||
                profile.source == CaptureModeCatalog.Source.ANALYZING
        } ?: CaptureSettings.resolutionLabel(CaptureSettings.resolutionForFps(this, targetFps))
        return "$resolution $targetFps FPS ${if (settings.hdrHlg10) "HLG10" else "SDR"}"
    }

    private fun beginRecordingFlow() {
        if (VideoProcessingStateStore.snapshot(this).running) {
            OneUiDialog.confirm(
                activity = this,
                title = "Otimização em andamento",
                message = "Para preservar o máximo de FPS, a otimização não deve competir com a câmera e o encoder.",
                positiveLabel = "Cancelar e gravar",
                negativeLabel = "Aguardar"
            ) {
                pendingRecordingAfterOptimizationCancel = true
                VideoProcessingService.cancel(this)
                renderOptimizationState(
                    VideoProcessingService.STATE_PROGRESS,
                    "Cancelando para liberar o processador para a gravação",
                    VideoProcessingStateStore.snapshot(this).progress
                )
                waitForOptimizationToStop(0)
            }
            return
        }

        val missing = missingRecordingPermissions()

        if (missing.isNotEmpty()) {
            renderPermissionState()
            requestAppPermissions(
                missing,
                REQUEST_RECORDING_PERMISSIONS
            )
            return
        }

        startRecordingFromApp()
    }

    private fun waitForOptimizationToStop(attempt: Int) {
        if (!pendingRecordingAfterOptimizationCancel) return
        val snapshot = VideoProcessingStateStore.snapshot(this)
        if (!snapshot.running) {
            pendingRecordingAfterOptimizationCancel = false
            mainHandler.postDelayed({ beginRecordingFlow() }, OPTIMIZATION_RELEASE_DELAY_MS)
            return
        }
        if (attempt >= OPTIMIZATION_CANCEL_MAX_POLLS) {
            pendingRecordingAfterOptimizationCancel = false
            Haptics.error(this)
            Toast.makeText(
                this,
                "A otimização ainda não liberou o processador. Tente novamente em alguns segundos.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        mainHandler.postDelayed(
            { waitForOptimizationToStop(attempt + 1) },
            OPTIMIZATION_CANCEL_POLL_MS
        )
    }

    private fun startRecordingFromApp() {
        if (!hasRecordingPermissions()) {
            renderPermissionState()
            return
        }

        if (CaptureStateStore.isBusy(this)) {
            if (!previewOpen && RecordingDisplayPreferences.appHeadless(this)) openBlackScreen()
            return
        }

        var startSettings = CaptureSettings.snapshot(this)
        val preferredResolution = preferredResolutionForFps(startSettings.fps)
        if (preferredResolution != startSettings.resolution) {
            CaptureSettings.updateResolutionAndFps(this, preferredResolution, startSettings.fps)
            startSettings = CaptureSettings.snapshot(this)
        }
        val recordingProfile = configuredProfile(startSettings.fps, startSettings.resolution)
        if (!recordingProfile.selectable) {
            refreshCapabilityMatrix()
            Haptics.error(this)
            val message = if (recordingProfile.source == CaptureModeCatalog.Source.ANALYZING ||
                recordingProfile.source == CaptureModeCatalog.Source.UNVERIFIED
            ) {
                "Aguardando confirmar este modo na câmera e no encoder"
            } else {
                "Este modo não é compatível com a câmera e o encoder selecionados"
            }
            CaptureStateStore.update(this, message)
            renderState(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        val spaceCheck = RecordingStorageGuard.checkProfile(this, CaptureSettings.snapshot(this))
        if (!spaceCheck.allowed) {
            Haptics.error(this)
            CaptureStateStore.update(this, spaceCheck.message)
            renderState(spaceCheck.message)
            renderPreviewCaptureState(spaceCheck.message, busy = false)
            ExpandedControlWidget.updateAll(this)
            Toast.makeText(this, spaceCheck.message, Toast.LENGTH_LONG).show()
            return
        }

        val keepPreviewControls = recordingRequestedFromPreview && previewOpen
        if (keepPreviewControls) {
            startRecordingAfterPreviewClosed(keepPreviewControls = true)
        } else {
            closeCameraPreview {
                if (!isFinishing && !isDestroyed && !CaptureStateStore.isBusy(this)) {
                    startRecordingAfterPreviewClosed(keepPreviewControls = false)
                }
            }
        }
    }

    private fun startRecordingAfterPreviewClosed(keepPreviewControls: Boolean) {
        activePreviewVideoCapture = keepPreviewControls
        CaptureStateStore.clearEffectiveMode(this)
        renderRecordingMode(busy = true)

        val selectedFps = CaptureModeStore.getTargetFps(this)
        val preparing = "Preparando ${selectedModeLabel(selectedFps, dedicatedSingleCapture = !keepPreviewControls)}…"
        CaptureStateStore.update(this, preparing)
        renderState(preparing)
        ExpandedControlWidget.updateRecordingControls(this)
        if (keepPreviewControls) {
            pendingBlackScreenForRecording = false
            renderPreviewCaptureState(preparing, busy = true)
        } else {
            // O atalho rápido e o botão do app podem ser configurados separadamente.
            // Quando habilitada, a tela discreta abre assim que o serviço é despachado;
            // o broadcast de estado permanece apenas como fallback.
            pendingBlackScreenForRecording = if (recordingRequestedFromQuickShortcut) {
                RecordingDisplayPreferences.quickShortcut(this)
            } else {
                RecordingDisplayPreferences.appHeadless(this)
            }
        }

        val settings = CaptureSettings.snapshot(this)
        val preferredCameraId = idlePreview.currentCameraId().takeIf { keepPreviewControls }
            ?: settings.selectedCameraId
        val started = runCatching {
            if (keepPreviewControls) {
                RecordingServiceRouter.start(
                    context = this,
                    targetFps = selectedFps,
                    fromPreview = true,
                    preferredCameraId = preferredCameraId,
                    headless = false
                )
            } else {
                val exactCameraId = idlePreview.currentCameraId() ?: settings.selectedCameraId
                RecordingServiceRouter.startHeadless(this, selectedFps, exactCameraId)
            }
            true
        }.getOrElse {
            showFailure("Não foi possível iniciar a gravação", it)
            false
        }
        if (started && !keepPreviewControls && pendingBlackScreenForRecording) {
            pendingBlackScreenForRecording = false
            openBlackScreen()
        }
        recordingRequestedFromPreview = false
        recordingRequestedFromQuickShortcut = false
        if (!started) {
            pendingBlackScreenForRecording = false
            activePreviewVideoCapture = false
            hidePreviewTransitionCover(animated = false)
            mainHandler.postDelayed({ startIdlePreviewIfPossible() }, 300L)
        }
    }

    private fun stopRecordingSafely() {
        val stopped = runCatching {
            RecordingServiceRouter.stop(this)
        }.getOrElse {
            showFailure("Não foi possível parar a gravação", it)
            false
        }

        if (stopped) {
            val settings = CaptureSettings.snapshot(this)
            val finalizing = "Finalizando ${CaptureSettings.resolutionLabel(settings.resolution)} ${settings.fps} FPS…"
            CaptureStateStore.update(this, finalizing)
            renderState(finalizing)
            ExpandedControlWidget.updateRecordingControls(this)
        }
    }

    private fun openBlackScreen() {
        runCatching {
            val intent = Intent(this, DiscreetRecordingActivity::class.java)
                .setAction(DiscreetRecordingActivity.ACTION_SHOW)
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
            startActivity(intent, options.toBundle())
        }.onFailure {
            showFailure("Não foi possível abrir a tela preta", it)
        }
    }


    private fun capturePhotoInsidePreview(isBurst: Boolean) {
        if (!previewOpen || !previewPhotoMode) return
        if (photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "A câmera ainda está salvando a foto anterior.", Toast.LENGTH_SHORT).show()
            return
        }
        if (CaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Pare o vídeo antes de tirar a foto.", Toast.LENGTH_SHORT).show()
            return
        }

        photoBusy = true
        activePreviewPhotoCapture = true
        PhotoCaptureStateStore.begin(this)
        val preparing = if (isBurst) "Capturando sequência…" else "Capturando foto…"
        renderTemporaryMessage(preparing)
        renderCaptureControls(recordingBusy = false, stopping = false)
        renderPreviewCaptureState(preparing, busy = false)
        ExpandedControlWidget.updateAll(this)

        idlePreview.capturePhoto(
            burst = isBurst,
            onSaved = { saved ->
                latestCapturedMedia = VaultRepository.readItem(saved.file)
                refreshLastMediaThumbnail()
                val message = if (saved.total > 1) "Foto ${saved.index} de ${saved.total} salva" else "Foto salva"
                renderTemporaryMessage(message)
            },
            onComplete = {
                photoBusy = false
                activePreviewPhotoCapture = false
                PhotoCaptureStateStore.finish(this)
                Haptics.success(this)
                val message = if (isBurst) "Sequência de 5 fotos salva" else "Foto salva"
                renderTemporaryMessage(message)
                renderCaptureControls(recordingBusy = false, stopping = false)
                renderPreviewCaptureState(message, busy = false)
                refreshLastMediaThumbnail()
                ExpandedControlWidget.updateAll(this)
                if (closePreviewAfterCapture) closeCameraPreview()
            },
            onError = { reason ->
                photoBusy = false
                activePreviewPhotoCapture = false
                PhotoCaptureStateStore.finish(this)
                Haptics.error(this)
                val message = "Foto não capturada: $reason"
                renderTemporaryMessage(message)
                renderCaptureControls(recordingBusy = false, stopping = false)
                renderPreviewCaptureState(message, busy = false)
                ExpandedControlWidget.updateAll(this)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (closePreviewAfterCapture) closeCameraPreview()
            }
        )
    }

    private fun refreshLastMediaThumbnail() {
        if (!::previewLastMediaThumbnail.isInitialized || !::previewLastMediaPlay.isInitialized || !::previewLastMediaButton.isInitialized) return

        val requestGeneration = thumbnailRefreshGeneration.incrementAndGet()
        val cachedItem = latestCapturedMedia?.takeIf { it.file.isFile && it.file.length() > 0L }
        val appContext = applicationContext

        runCatching {
            thumbnailExecutor.execute {
                val indexedItem = runCatching { VaultRepository.listPage(appContext, 0, 1).firstOrNull() }.getOrNull()
                val item = listOfNotNull(cachedItem, indexedItem).maxByOrNull { it.modifiedAt }
                val thumbnail = item?.let { media ->
                    runCatching { MediaThumbnailRepository.load(appContext, media.file, media.video, 256) }.getOrNull()
                }

                mainHandler.post {
                    if (requestGeneration != thumbnailRefreshGeneration.get()) {
                        thumbnail?.takeIf { !it.isRecycled }?.recycle()
                        return@post
                    }
                    if (isFinishing || isDestroyed || !::previewLastMediaThumbnail.isInitialized) {
                        thumbnail?.takeIf { !it.isRecycled }?.recycle()
                        return@post
                    }

                    latestCapturedMedia = item
                    if (item != null && thumbnail != null && !thumbnail.isRecycled) {
                        previewLastMediaThumbnail.setImageBitmap(thumbnail)
                        previewLastMediaPlay.visibility = if (item.video) View.VISIBLE else View.GONE
                        previewLastMediaButton.alpha = 1f
                    } else {
                        previewLastMediaThumbnail.setImageResource(R.drawable.ic_nav_vault)
                        previewLastMediaPlay.visibility = View.GONE
                        previewLastMediaButton.alpha = 0.72f
                    }
                }
            }
        }.onFailure {
            if (!isFinishing && !isDestroyed) {
                previewLastMediaThumbnail.setImageResource(R.drawable.ic_nav_vault)
                previewLastMediaPlay.visibility = View.GONE
                previewLastMediaButton.alpha = 0.72f
            }
        }
    }

    private fun openLatestCapturedMedia() {
        closeCameraPreview {
            startActivity(Intent(this, PrimaryVaultActivity::class.java))
        }
    }

    private fun beginPhotoFlow(isBurst: Boolean) {
        pendingPhotoBurst = isBurst
        if (photoBusy || PhotoCaptureStateStore.isBusy(this)) {
            Haptics.tap(this)
            Toast.makeText(this, "A câmera ainda está finalizando a foto anterior.", Toast.LENGTH_SHORT).show()
            return
        }

        if (CaptureStateStore.isBusy(this)) {
            Haptics.error(this)
            Toast.makeText(
                this,
                "Pare a gravação antes da foto para preservar o modo selecionado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!hasCameraPermission()) {
            renderPermissionState()
            requestAppPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_PHOTO_PERMISSION
            )
            return
        }

        val fromPreview = photoRequestedFromPreview && previewOpen
        if (fromPreview && isBurst) {
            pendingPhotoBurst = false
            photoRequestedFromPreview = false
            capturePhotoInsidePreview(isBurst = true)
        } else if (fromPreview) {
            startPhotoCapture(isBurst, fromPreview = true)
        } else {
            closeCameraPreview {
                if (!isFinishing && !isDestroyed && !PhotoCaptureStateStore.isBusy(this)) {
                    startPhotoCapture(isBurst, fromPreview = false)
                }
            }
        }
    }

    private fun startPhotoCapture(isBurst: Boolean, fromPreview: Boolean) {
        pendingPhotoBurst = false
        photoRequestedFromPreview = false
        activePreviewPhotoCapture = fromPreview
        val action = if (isBurst) PhotoService.ACTION_BURST else PhotoService.ACTION_CAPTURE
        val currentSettings = CaptureSettings.snapshot(this)
        val preferredCameraId = idlePreview.currentCameraId().takeIf { fromPreview }
        val captureZoomRatio = currentSettings.zoomRatio.takeIf { fromPreview }
        val started = runCatching {
            val intent = Intent(this, PhotoService::class.java)
                .setAction(action)
                .putExtra(PhotoService.EXTRA_FROM_WIDGET, false)
                .putExtra(PhotoService.EXTRA_FROM_PREVIEW, fromPreview)
            captureZoomRatio?.let { intent.putExtra(PhotoService.EXTRA_ZOOM_RATIO, it) }
            preferredCameraId?.takeIf { it.isNotBlank() }?.let {
                intent.putExtra(PhotoService.EXTRA_PREFERRED_CAMERA_ID, it)
            }
            startForegroundService(intent)
            true
        }.getOrElse {
            showFailure("Não foi possível iniciar a câmera", it)
            false
        }

        if (started) {
            photoBusy = true
            renderTemporaryMessage(if (isBurst) "Iniciando sequência…" else "Capturando foto…")
            renderCaptureControls(recordingBusy = false, stopping = false)
            renderPreviewCaptureState(if (isBurst) "Sequência pelo preview…" else "Foto pelo preview…", busy = false)
            ExpandedControlWidget.updateAll(this)
        } else {
            activePreviewPhotoCapture = false
            mainHandler.postDelayed({ startIdlePreviewIfPossible() }, 300L)
        }
    }

    private fun openBatteryProtection() {
        val opened = PowerPolicy.openSettings(this)

        if (!opened) {
            Toast.makeText(
                this,
                "Não foi possível abrir a lista de otimização de bateria.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestWidgetPin() {
        val manager = AppWidgetManager.getInstance(this)

        if (!manager.isRequestPinAppWidgetSupported) {
            showWidgetInstructions()
            return
        }

        val provider = ComponentName(
            this,
            CompactControlWidget::class.java
        )

        val callbackMutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val successCallback = PendingIntent.getBroadcast(
            this,
            WIDGET_PIN_REQUEST_CODE,
            Intent(this, WidgetPinnedReceiver::class.java)
                .setAction(WidgetPinnedReceiver.ACTION_PINNED),
            PendingIntent.FLAG_UPDATE_CURRENT or callbackMutability
        )

        val accepted = runCatching {
            manager.requestPinAppWidget(
                provider,
                null,
                successCallback
            )
        }.getOrDefault(false)

        if (!accepted) {
            showWidgetInstructions()
        }
    }

    private fun showWidgetInstructions() {
        runCatching {
            OneUiDialog.message(
                activity = this,
                title = "Adicionar o widget",
                message = "Na tela inicial, mantenha pressionado um espaço vazio, toque em Widgets e procure por SteadyVault — compacto."
            )
        }
    }

    private fun registerStateReceivers() {
        if (receiversRegistered) return

        val stateFilter = IntentFilter(CaptureService.ACTION_STATE)
        val photoFilter = IntentFilter(PhotoService.ACTION_PHOTO_STATE)
        val optimizationFilter = IntentFilter(VideoProcessingService.ACTION_STATE)

        receiversRegistered = runCatching {
            ContextCompat.registerReceiver(this, stateReceiver, stateFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, photoReceiver, photoFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, optimizationReceiver, optimizationFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
    }

    private fun unregisterStateReceivers() {
        if (!receiversRegistered) return

        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(photoReceiver) }
        runCatching { unregisterReceiver(optimizationReceiver) }
        receiversRegistered = false
    }

    private fun refreshIdleOrStoredState() {
        val optimization = VideoProcessingStateStore.snapshot(this)
        if (optimization.running) {
            renderOptimizationState(
                VideoProcessingService.STATE_PROGRESS,
                optimization.message,
                optimization.progress
            )
            return
        }

        val stored = CaptureStateStore.currentState(this)
        val finalState = when {
            CaptureStateStore.isBusyMessage(stored) -> stored
            isPermissionFailure(stored) -> permissionAwareIdleMessage()
            stored.startsWith("Foto salva") -> "Pronto para gravar"
            stored.startsWith("Foto não capturada") -> permissionAwareIdleMessage()
            stored.contains("Otimização iniciada", ignoreCase = true) -> {
                val recent = System.currentTimeMillis() - optimization.updatedAtMs <= OPTIMIZATION_RESULT_VISIBLE_MS
                if (recent) optimization.message.ifBlank { "Pronto para gravar" } else "Pronto para gravar"
            }
            else -> stored
        }

        val alignedState = if (finalState.startsWith("Modo ")) {
            selectedModeStateMessage()
        } else {
            finalState
        }

        if (alignedState != stored) {
            CaptureStateStore.update(this, alignedState)
        }

        renderState(alignedState)
    }

    private fun refreshOptimizationState() {
        val snapshot = VideoProcessingStateStore.snapshot(this)
        if (snapshot.state == VideoProcessingStateStore.STATE_IDLE || snapshot.message.isBlank()) return
        if (!snapshot.running && System.currentTimeMillis() - snapshot.updatedAtMs > OPTIMIZATION_RESULT_VISIBLE_MS) return
        val state = when (snapshot.state) {
            VideoProcessingStateStore.STATE_RUNNING -> VideoProcessingService.STATE_PROGRESS
            VideoProcessingStateStore.STATE_SUCCESS -> VideoProcessingService.STATE_SUCCESS
            VideoProcessingStateStore.STATE_CANCELLED -> VideoProcessingService.STATE_CANCELLED
            else -> VideoProcessingService.STATE_ERROR
        }
        renderOptimizationState(state, snapshot.message, snapshot.progress)
    }

    private fun renderOptimizationState(state: String, message: String, progress: Int) {
        val safeProgress = progress.coerceIn(0, 100)
        val display = when (state) {
            VideoProcessingService.STATE_PROGRESS ->
                "Otimização do último vídeo • $safeProgress%\n${message.ifBlank { "Processando no aparelho" }}"
            VideoProcessingService.STATE_SUCCESS ->
                permissionAwareIdleMessage()
            VideoProcessingService.STATE_CANCELLED -> "Processamento cancelado"
            else -> "Falha na otimização\n${message.ifBlank { "Abra o cofre para tentar novamente" }}"
        }
        statusText.text = display
        statusDot.setBackgroundResource(
            when (state) {
                VideoProcessingService.STATE_PROGRESS -> R.drawable.bg_recording_dot
                VideoProcessingService.STATE_SUCCESS -> R.drawable.bg_ready_dot
                else -> R.drawable.bg_error_dot
            }
        )
    }

    private fun permissionAwareIdleMessage(): String =
        when {
            !hasCameraPermission() &&
                    !hasAudioPermission() ->
                "Toque em Iniciar e permita a câmera • microfone é opcional"

            !hasCameraPermission() ->
                "Toque em Iniciar e permita a câmera"

            !hasAudioPermission() ->
                "Pronto para gravar sem áudio • permita o microfone quando quiser som"

            else ->
                if (hasNotificationPermission()) "Pronto para gravar" else "Pronto para gravar • notificações desativadas"
        }

    private fun isPermissionFailure(message: String): Boolean =
        message.contains("permiss", ignoreCase = true) ||
                message.contains("microfone não", ignoreCase = true) ||
                message.contains("câmera não", ignoreCase = true)

    private fun renderPermissionState() {
        val message = permissionAwareIdleMessage()
        CaptureStateStore.update(this, message)
        renderState(message)
    }

    private fun renderTemporaryMessage(message: String) {
        statusText.text = message
        statusDot.setBackgroundResource(
            when {
                UiBehaviorRules.isPhotoProgress(message) -> R.drawable.bg_recording_dot
                UiBehaviorRules.isPhotoResult(message) && !message.contains("não capturada", ignoreCase = true) -> R.drawable.bg_ready_dot
                else -> R.drawable.bg_error_dot
            }
        )

        if (!UiBehaviorRules.isPhotoProgress(message)) {
            mainHandler.postDelayed(
                { refreshIdleOrStoredState() },
                TEMPORARY_MESSAGE_MS
            )
        }
    }

    private fun renderState(message: String) {
        statusText.text = message

        val busy = CaptureStateStore.isBusyMessage(message)
        val stopping = UiBehaviorRules.isRecordingFinalizing(message)
        val failure = message.startsWith("Falha") ||
            message.contains("permita", ignoreCase = true)

        statusDot.setBackgroundResource(
            when {
                failure -> R.drawable.bg_error_dot
                busy -> R.drawable.bg_recording_dot
                else -> R.drawable.bg_ready_dot
            }
        )

        renderCaptureControls(recordingBusy = busy, stopping = stopping)
        renderRecordingMode(busy = busy)
    }

    private fun renderCaptureControls(recordingBusy: Boolean, stopping: Boolean) {
        val captureBusy = photoBusy || PhotoCaptureStateStore.isBusy(this)
        startButton.isEnabled = !recordingBusy && !captureBusy
        stopButton.isEnabled = recordingBusy && !stopping
        photoButton.isEnabled = !recordingBusy && !captureBusy
        burstButton.isEnabled = !recordingBusy && !captureBusy
        previewModeButton.isEnabled = !recordingBusy && !captureBusy
        backgroundRecordingZoomButton.isEnabled = !recordingBusy && !captureBusy

        startButton.alpha = if (startButton.isEnabled) 1f else 0.42f
        stopButton.setBackgroundResource(
            if (stopButton.isEnabled) R.drawable.bg_capture_action_red else R.drawable.bg_capture_action_disabled
        )
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.62f
        photoButton.alpha = if (photoButton.isEnabled) 1f else 0.42f
        burstButton.alpha = if (burstButton.isEnabled) 1f else 0.42f
        previewModeButton.alpha = if (previewModeButton.isEnabled) 1f else 0.42f
        backgroundRecordingZoomButton.alpha = if (backgroundRecordingZoomButton.isEnabled) 1f else 0.42f
        renderPreviewQuickControls()
    }

    private fun showBackgroundRecordingZoomChooser() {
        val values = BackgroundRecordingZoom.choices
        val current = CaptureSettings.snapshot(this).zoomRatio
        OneUiDialog.choices(
            activity = this,
            title = "Zoom da captura rápida",
            message = "Usado somente ao gravar sem preview pela tela inicial ou pelo widget. A escolha fica salva até você mudar.",
            choices = values.map { ratio ->
                OneUiDialog.Choice(
                    title = BackgroundRecordingZoom.label(ratio),
                    subtitle = when {
                        ratio < 1f -> "Ultra-angular"
                        ratio < 2f -> "Câmera principal"
                        ratio < 4f -> "Teleobjetiva ou zoom da câmera"
                        else -> "Longo alcance ou zoom da câmera"
                    },
                    enabled = true
                )
            },
            selectedIndex = values.indexOf(current).coerceAtLeast(0),
            confirmLabel = "Aplicar"
        ) { index ->
            val effectiveZoom = BackgroundRecordingZoom.set(this, values[index])
            val settings = CaptureSettings.snapshot(this)
            CaptureSettings.save(this, settings.copy(zoomRatio = effectiveZoom))
            renderBackgroundRecordingZoom()
            ExpandedControlWidget.updateAll(this)
        }
    }

    private fun renderBackgroundRecordingZoom() {
        if (!::backgroundRecordingZoomButton.isInitialized) return
        backgroundRecordingZoomButton.visibility = View.VISIBLE
        val label = BackgroundRecordingZoom.label(BackgroundRecordingZoom.selected(this))
        backgroundRecordingZoomButton.text = label
        backgroundRecordingZoomButton.contentDescription = "Alterar zoom da foto e do vídeo rápidos. Atual: $label"
    }

    private fun updateBatteryStatus() {
        val ignored = PowerPolicy.isIgnoring(this)

        batteryStatusText.text = if (ignored) {
            "Bateria sem restrições"
        } else {
            "Bateria pode limitar a gravação"
        }

        batteryButtonText.text = if (ignored) {
            "Bateria protegida"
        } else {
            "Configurar bateria"
        }
    }

    private fun missingRecordingPermissions(): Array<String> {
        val missing = mutableListOf<String>()

        if (!hasCameraPermission()) {
            missing += Manifest.permission.CAMERA
        }
        if (!hasAudioPermission()) {
            missing += Manifest.permission.RECORD_AUDIO
        }

        return missing.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT <
                Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

    private fun hasRecordingPermissions(): Boolean =
        hasCameraPermission() && hasAudioPermission()

    private fun requestAppPermissions(
        permissions: Array<String>,
        requestCode: Int
    ) {
        pendingPermissionRequestCode = requestCode
        permissionRequestLauncher.launch(permissions)
    }

    private fun requestNotificationPermissionForUpgrade() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU ||
            hasNotificationPermission() ||
            !hasCameraPermission() ||
            pendingPermissionRequestCode != 0
        ) {
            return
        }

        requestAppPermissions(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS
            ),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun showPermissionDialog(forVideo: Boolean) {
        val message = if (forVideo) {
            "O SteadyVault precisa da Câmera e do Microfone para gravar vídeo com áudio. As notificações continuam opcionais."
        } else {
            "O SteadyVault precisa da Câmera para tirar fotos."
        }

        runCatching {
            OneUiDialog.confirm(
                activity = this,
                title = "Permissão necessária",
                message = message,
                positiveLabel = "Abrir permissões",
                negativeLabel = "Cancelar"
            ) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
    }

    private fun showFailure(prefix: String, throwable: Throwable) {
        Haptics.error(this)

        val detail = throwable.message
            ?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName

        val message = "$prefix: $detail"
        CaptureStateStore.update(this, message)
        renderState(message)

        Toast.makeText(
            this,
            prefix,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handlePermissionResult(requestCode: Int) {
        when (requestCode) {
            REQUEST_RECORDING_PERMISSIONS -> {
                if (hasRecordingPermissions()) {
                    mainHandler.postDelayed({ refreshCapabilityMatrix() }, CAPABILITY_PERMISSION_RESCAN_DELAY_MS)
                    if (widgetPermissionRequestMode == WIDGET_PERMISSION_VIDEO) {
                        widgetPermissionRequestMode = null
                        CaptureStateStore.update(this, "Pronto para gravar")
                        renderState("Pronto para gravar")
                        ExpandedControlWidget.updateAll(this)

                        Toast.makeText(
                            this,
                            "Permissões liberadas. O widget já pode gravar.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        mainHandler.postDelayed(
                            {
                                if (hasRecordingPermissions()) {
                                    startRecordingFromApp()
                                }
                            },
                            PERMISSION_PROPAGATION_DELAY_MS
                        )
                    }
                } else {
                    widgetPermissionRequestMode = null
                    Haptics.error(this)
                    renderPermissionState()
                    showPermissionDialog(forVideo = true)
                }
            }

            REQUEST_NOTIFICATION_PERMISSION -> {
                if (hasNotificationPermission()) {
                    CaptureStateStore.update(
                        this,
                        "Notificações liberadas • pronto para gravar"
                    )
                    renderState(
                        "Notificações liberadas • pronto para gravar"
                    )
                    ExpandedControlWidget.updateAll(this)
                } else {
                    CaptureStateStore.update(this, "Pronto para gravar • notificações desativadas")
                    renderState("Pronto para gravar • notificações desativadas")
                }
            }

            REQUEST_PREVIEW_PERMISSION -> {
                if (hasCameraPermission()) {
                    mainHandler.postDelayed({ refreshCapabilityMatrix() }, CAPABILITY_PERMISSION_RESCAN_DELAY_MS)
                    if (pendingPreviewOpen && previewUserRequested) {
                        mainHandler.postDelayed({ openCameraPreview() }, PERMISSION_PROPAGATION_DELAY_MS)
                    }
                } else {
                    pendingPreviewOpen = false
                    previewUserRequested = false
                    Haptics.error(this)
                    showPermissionDialog(forVideo = false)
                }
            }

            REQUEST_PHOTO_PERMISSION -> {
                if (hasCameraPermission()) {
                    mainHandler.postDelayed({ refreshCapabilityMatrix() }, CAPABILITY_PERMISSION_RESCAN_DELAY_MS)
                    if (widgetPermissionRequestMode == WIDGET_PERMISSION_PHOTO) {
                        widgetPermissionRequestMode = null
                        CaptureStateStore.update(this, "Pronto para gravar")
                        renderState("Pronto para gravar")
                        ExpandedControlWidget.updateAll(this)

                        Toast.makeText(
                            this,
                            "Permissão liberada. Foto e sequência já funcionam no widget.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        mainHandler.postDelayed(
                            { beginPhotoFlow(isBurst = pendingPhotoBurst) },
                            PERMISSION_PROPAGATION_DELAY_MS
                        )
                    }
                } else {
                    widgetPermissionRequestMode = null
                    Haptics.error(this)
                    renderPermissionState()
                    showPermissionDialog(forVideo = false)
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        // Fallbacks usados quando a câmera não publica um tamanho de preview adequado.
        // O layout sempre respeita a proporção real do buffer e nunca estica a imagem.
        private const val STANDARD_VIDEO_PREVIEW_BUFFER_WIDTH = 1280
        private const val STANDARD_VIDEO_PREVIEW_BUFFER_HEIGHT = 720
        private const val HIGH_SPEED_VIDEO_PREVIEW_BUFFER_WIDTH = 1280
        private const val HIGH_SPEED_VIDEO_PREVIEW_BUFFER_HEIGHT = 720
        private const val PHOTO_PREVIEW_BUFFER_WIDTH = 1280
        private const val PHOTO_PREVIEW_BUFFER_HEIGHT = 720
        private const val PREVIEW_UI_PREFS = "steadyvault_preview_ui"
        private const val KEY_PREVIEW_GRID = "preview_grid"
        const val ACTION_WIDGET_REQUEST_PERMISSIONS =
            "com.steadyvault.camera.WIDGET_REQUEST_PERMISSIONS"
        const val EXTRA_WIDGET_PERMISSION_MODE =
            "widget_permission_mode"
        const val ACTION_LAUNCHER_RECORD_VIDEO =
            "com.steadyvault.camera.action.RECORD_VIDEO"
        const val ACTION_LAUNCHER_TAKE_PHOTO =
            "com.steadyvault.camera.action.TAKE_PHOTO"
        const val WIDGET_PERMISSION_VIDEO = "video"
        const val WIDGET_PERMISSION_PHOTO = "photo"

        private const val REQUEST_RECORDING_PERMISSIONS = 10
        private const val REQUEST_PHOTO_PERMISSION = 11
        private const val REQUEST_NOTIFICATION_PERMISSION = 12
        private const val REQUEST_PREVIEW_PERMISSION = 13
        private const val WIDGET_PIN_REQUEST_CODE = 301
        private const val PERMISSION_PROPAGATION_DELAY_MS = 300L
        private const val WIDGET_PERMISSION_DIALOG_DELAY_MS = 250L
        private const val NOTIFICATION_PERMISSION_DELAY_MS = 500L
        private const val TEMPORARY_MESSAGE_MS = 2_000L
        private const val OPTIMIZATION_RESULT_HOLD_MS = 3_500L
        private const val OPTIMIZATION_RESULT_VISIBLE_MS = 20_000L
        private const val OPTIMIZATION_RELEASE_DELAY_MS = 450L
        private const val OPTIMIZATION_CANCEL_POLL_MS = 300L
        private const val OPTIMIZATION_CANCEL_MAX_POLLS = 20
        private const val CAPABILITY_RESCAN_DEBOUNCE_MS = 2_000L
        private const val CAPABILITY_PERMISSION_RESCAN_DELAY_MS = 750L
        private const val PREVIEW_RESTART_DELAY_MS = 350L
        private const val PREVIEW_UPDATE_DEBOUNCE_MS = 90L
        private const val PREVIEW_FRAME_COPY_TIMEOUT_MS = 140L
        private const val PREVIEW_TRANSITION_RELEASE_DELAY_MS = 90L
        private const val PREVIEW_TRANSITION_FADE_MS = 120L
        private const val PREVIEW_MODE_TRANSITION_RELEASE_DELAY_MS = 110L
        private const val PREVIEW_MODE_TRANSITION_TIMEOUT_MS = 1_800L
        private const val PREVIEW_TRANSITION_MAX_HOLD_MS = 12_000L
        private const val PREVIEW_TRANSITION_MAX_DIMENSION_PX = 1_280
    }
}
