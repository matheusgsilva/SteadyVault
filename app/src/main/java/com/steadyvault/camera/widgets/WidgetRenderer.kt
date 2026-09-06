package com.steadyvault.camera.widgets

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.steadyvault.camera.R
import com.steadyvault.camera.capture.service.CaptureService
import com.steadyvault.camera.core.settings.BackgroundRecordingZoom
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.settings.RecordingDisplayPreferences
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.core.state.PhotoCaptureStateStore
import com.steadyvault.camera.photo.service.PhotoService
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.capture.DiscreetRecordingActivity

object WidgetRenderer {
    enum class WidgetType(
        val layoutId: Int,
        val buttonSizeDp: Float,
        val hasZoom: Boolean,
        val hasSequence: Boolean,
        val hasPhoto: Boolean
    ) {
        EXPANDED(
            R.layout.widget_control_expanded_large,
            58f,
            hasZoom = true,
            hasSequence = true,
            hasPhoto = true
        ),
        COMPACT(
            R.layout.widget_control_compact_large,
            58f,
            hasZoom = false,
            hasSequence = true,
            hasPhoto = true
        ),
        LOCK_SCREEN(
            R.layout.widget_control_lock_screen,
            40f,
            hasZoom = true,
            hasSequence = false,
            hasPhoto = true
        )
    }

    private data class ProviderSpec(
        val provider: Class<*>,
        val type: WidgetType
    )

    private data class RecordingControlsState(
        val recording: Boolean,
        val recordingFinalizing: Boolean,
        val photoBusy: Boolean,
        val cameraGranted: Boolean,
        val audioGranted: Boolean
    ) {
        val cameraBusy: Boolean
            get() = recording || photoBusy

        val recordingActive: Boolean
            get() = recording && !recordingFinalizing && !photoBusy
    }

    private val providers = listOf(
        ProviderSpec(ExpandedControlWidget::class.java, WidgetType.EXPANDED),
        ProviderSpec(CompactControlWidget::class.java, WidgetType.COMPACT),
        ProviderSpec(LockScreenControlWidget::class.java, WidgetType.LOCK_SCREEN)
    )

    private var lastRecordingControlsState: RecordingControlsState? = null

    @Synchronized
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { spec ->
            updateProvider(context, manager, spec.provider, spec.type)
        }
        lastRecordingControlsState = recordingControlsState(context)
    }

    /**
     * Atualiza os estados dos controles sem reinflar o widget inteiro.
     * A atualização parcial bloqueia todos os comandos durante foto/processamento
     * e mantém apenas Parar disponível enquanto a gravação está ativa.
     */
    @Synchronized
    fun updateRecordingControls(context: Context) {
        renderRecordingControls(context, force = false)
    }

    @Synchronized
    fun forceRecordingControls(context: Context) {
        renderRecordingControls(context, force = true)
    }

    private fun renderRecordingControls(context: Context, force: Boolean) {
        val state = recordingControlsState(context)
        if (!force && state == lastRecordingControlsState) return

        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { spec ->
            updateRecordingControlsForProvider(
                context,
                manager,
                spec.provider,
                spec.type
            )
        }
        lastRecordingControlsState = state
    }

    private fun recordingControlsState(context: Context): RecordingControlsState =
        RecordingControlsState(
            recording = CaptureStateStore.isBusy(context),
            recordingFinalizing = CaptureStateStore.isFinalizing(context),
            photoBusy = PhotoCaptureStateStore.isBusy(context),
            cameraGranted =
                context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            audioGranted =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )

    /** Atualiza somente o valor e a ação do zoom, sem reinflar o widget. */
    fun updateZoomControl(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val state = recordingControlsState(context)

        providers.filter { it.type.hasZoom }.forEach { spec ->
            manager.getAppWidgetIds(ComponentName(context, spec.provider)).forEach { widgetId ->
                val views = RemoteViews(context.packageName, spec.type.layoutId)
                val zoomAvailable = supportsUsefulZoom(context)
                views.setViewVisibility(R.id.widgetZoom, if (zoomAvailable) View.VISIBLE else View.GONE)
                if (spec.type == WidgetType.LOCK_SCREEN) {
                    views.setViewVisibility(R.id.widgetZoomBackground, if (zoomAvailable) View.VISIBLE else View.GONE)
                }
                if (zoomAvailable) {
                    configureZoomButton(
                        views,
                        context,
                        enabled = !state.cameraBusy,
                        clickIntent = widgetZoomIntent(context),
                        type = spec.type
                    )
                }
                manager.partiallyUpdateAppWidget(widgetId, views)
            }
        }
    }

    private fun updateRecordingControlsForProvider(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        type: WidgetType
    ) {
        val widgetIds = manager.getAppWidgetIds(ComponentName(context, provider))
        if (widgetIds.isEmpty()) return

        val state = recordingControlsState(context)
        widgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, type.layoutId)
            configureControlStates(views, context, type, state)
            manager.partiallyUpdateAppWidget(widgetId, views)
        }
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        type: WidgetType
    ) {
        manager.getAppWidgetIds(ComponentName(context, provider)).forEach { widgetId ->
            updateWidget(context, manager, widgetId, type)
        }
    }

    private fun configureControlStates(
        views: RemoteViews,
        context: Context,
        type: WidgetType,
        state: RecordingControlsState
    ) {
        val videoPermissionIntent = PendingIntent.getActivity(
            context,
            501,
            Intent(context, CaptureActivity::class.java)
                .setAction(CaptureActivity.ACTION_WIDGET_REQUEST_PERMISSIONS)
                .putExtra(
                    CaptureActivity.EXTRA_WIDGET_PERMISSION_MODE,
                    CaptureActivity.WIDGET_PERMISSION_VIDEO
                )
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("steadyvault://widget/permission/video")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val startIntent = if (!state.cameraGranted) {
            videoPermissionIntent
        } else if (RecordingDisplayPreferences.widget(context)) {
            PendingIntent.getActivity(
                context,
                510,
                Intent(context, DiscreetRecordingActivity::class.java)
                    .setAction(DiscreetRecordingActivity.ACTION_START_FROM_WIDGET)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(Uri.parse("steadyvault://widget/start/black")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                510,
                Intent(context, WidgetStartReceiver::class.java)
                    .setAction(WidgetStartReceiver.ACTION_START)
                    .putExtra(WidgetStartReceiver.EXTRA_TARGET_FPS, CaptureModeStore.getTargetFps(context))
                    .setData(Uri.parse("steadyvault://widget/start")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        // O widget sempre inicia a captura dedicada no CaptureService. Enviar a parada
        // diretamente ao serviço evita que a One UI descarte uma etapa intermediária
        // de BroadcastReceiver enquanto o aparelho está bloqueado.
        val stopIntent = PendingIntent.getService(
            context,
            511,
            Intent(context, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP)
                .putExtra(CaptureService.EXTRA_USER_REQUESTED_STOP, true)
                .setData(Uri.parse("steadyvault://widget/stop")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (type == WidgetType.EXPANDED) {
            views.setBoolean(R.id.widgetLogoArea, "setEnabled", !state.cameraBusy)
        }
        if (type.hasZoom) {
            val zoomAvailable = supportsUsefulZoom(context)
            views.setViewVisibility(R.id.widgetZoom, if (zoomAvailable) View.VISIBLE else View.GONE)
            if (type == WidgetType.LOCK_SCREEN) {
                views.setViewVisibility(R.id.widgetZoomBackground, if (zoomAvailable) View.VISIBLE else View.GONE)
            }
            if (zoomAvailable) {
                configureZoomButton(
                    views,
                    context,
                    enabled = !state.cameraBusy,
                    clickIntent = widgetZoomIntent(context),
                    type = type
                )
            }
        }
        if (type.hasPhoto) {
            val photoPermissionIntent = PendingIntent.getActivity(
                context,
                504,
                Intent(context, CaptureActivity::class.java)
                    .setAction(CaptureActivity.ACTION_WIDGET_REQUEST_PERMISSIONS)
                    .putExtra(
                        CaptureActivity.EXTRA_WIDGET_PERMISSION_MODE,
                        CaptureActivity.WIDGET_PERMISSION_PHOTO
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(Uri.parse("steadyvault://widget/permission/photo")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val photoIntent = if (!state.cameraGranted) {
                photoPermissionIntent
            } else {
                PendingIntent.getForegroundService(
                    context,
                    512,
                    Intent(context, PhotoService::class.java)
                        .setAction(PhotoService.ACTION_CAPTURE)
                        .putExtra(PhotoService.EXTRA_FROM_WIDGET, true)
                        .setData(Uri.parse("steadyvault://widget/photo")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val captureEnabled = state.cameraGranted && !state.cameraBusy
            if (type.hasSequence) {
                val sequenceIntent = if (!state.cameraGranted) {
                    photoPermissionIntent
                } else {
                    PendingIntent.getForegroundService(
                        context,
                        513,
                        Intent(context, PhotoService::class.java)
                            .setAction(PhotoService.ACTION_BURST)
                            .putExtra(PhotoService.EXTRA_FROM_WIDGET, true)
                            .setData(Uri.parse("steadyvault://widget/sequence")),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val neutralWidgetActions = VisualIdentityStore.neutralWidgetActions(context)
                configureButton(
                    views = views,
                    context = context,
                    type = type,
                    viewId = R.id.widgetSequence,
                    enabled = captureEnabled,
                    clickIntent = sequenceIntent,
                    enabledIcon = if (neutralWidgetActions) R.drawable.ic_grid else R.drawable.ic_burst,
                    disabledIcon = if (neutralWidgetActions) R.drawable.ic_grid else R.drawable.ic_burst_disabled,
                    enabledBackground = if (type == WidgetType.LOCK_SCREEN) WidgetAppearance.lockAccentBackground(context) else WidgetAppearance.expandedAccentBackground(context),
                    disabledBackground = if (type == WidgetType.LOCK_SCREEN) WidgetAppearance.lockDisabledBackground(context) else WidgetAppearance.expandedDisabledBackground(context)
                )
            }
            val neutralWidgetActions = VisualIdentityStore.neutralWidgetActions(context)
            configureButton(
                views = views,
                context = context,
                type = type,
                viewId = R.id.widgetPhoto,
                enabled = captureEnabled,
                clickIntent = photoIntent,
                enabledIcon = if (neutralWidgetActions) R.drawable.ic_add else R.drawable.ic_camera,
                disabledIcon = if (neutralWidgetActions) R.drawable.ic_add else R.drawable.ic_camera_disabled,
                enabledBackground = if (type == WidgetType.LOCK_SCREEN) WidgetAppearance.lockAccentBackground(context) else WidgetAppearance.expandedAccentBackground(context),
                disabledBackground = if (type == WidgetType.LOCK_SCREEN) WidgetAppearance.lockDisabledBackground(context) else WidgetAppearance.expandedDisabledBackground(context)
            )
        }
        configureRecordingIndicator(
            views,
            context,
            R.id.widgetStart,
            enabled = state.cameraGranted && !state.cameraBusy,
            clickIntent = startIntent,
            recording = state.recording,
            stopIcon = false,
            type = type
        )
        configureRecordingIndicator(
            views,
            context,
            R.id.widgetStop,
            enabled = state.recordingActive,
            clickIntent = stopIntent,
            recording = state.recording,
            stopIcon = true,
            type = type
        )
    }

    fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        type: WidgetType
    ) {
        val state = recordingControlsState(context)
        val views = RemoteViews(context.packageName, type.layoutId)
        // Preserve the original widget geometry that already worked correctly on One UI.
        // Themes are applied as backgrounds only; they must never resize/reflow the installed widget.
        applyWidgetDimensions(views, type)
        applyWidgetChrome(views, context, type)

        if (type == WidgetType.EXPANDED) {
            val openAppIntent = PendingIntent.getActivity(
                context,
                500,
                Intent(context, CaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(Uri.parse("steadyvault://widget/open")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetLogoArea, openAppIntent)
        }
        configureControlStates(views, context, type, state)
        manager.updateAppWidget(widgetId, views)
    }

    /**
     * O preview mantém os mesmos controles, ícones e tema do widget instalado.
     * No 6x1 usa um layout proporcional menor, próprio para o seletor da One UI,
     * evitando que a miniatura seja recortada ou ampliada além do cartão do launcher.
     */
    fun buildPreviewViews(context: Context, type: WidgetType): RemoteViews {
        val previewLayoutId = when (type) {
            WidgetType.EXPANDED -> R.layout.widget_control_expanded_preview
            WidgetType.COMPACT -> R.layout.widget_control_compact_large_preview
            WidgetType.LOCK_SCREEN -> R.layout.widget_control_lock_screen_preview
        }
        val views = RemoteViews(context.packageName, previewLayoutId)
        val accentBackground = WidgetAppearance.lockAccentBackground(context)
        val disabledBackground = WidgetAppearance.lockDisabledBackground(context)
        val neutral = VisualIdentityStore.neutralWidgetActions(context)

        // Todas as prévias usam a mesma linguagem visual da prévia da tela de bloqueio:
        // tiles de 40 dp, espaçamento curto, sem shell grande e respeitando o tema atual.
        if (type == WidgetType.EXPANDED) {
            views.setImageViewResource(R.id.widgetAppLogo, VisualIdentityStore.widgetLogo(context))
            views.setContentDescription(R.id.widgetLogoArea, VisualIdentityStore.notificationTitle(context, ""))
            views.setInt(R.id.widgetLogoArea, "setBackgroundResource", disabledBackground)
        }

        if (type.hasZoom) {
            val zoomAvailable = supportsUsefulZoom(context)
            views.setViewVisibility(R.id.widgetZoom, if (zoomAvailable) View.VISIBLE else View.GONE)
            if (zoomAvailable) {
                views.setBoolean(R.id.widgetZoom, "setEnabled", true)
                views.setFloat(R.id.widgetZoom, "setAlpha", 1f)
                views.setTextViewText(R.id.widgetZoom, BackgroundRecordingZoom.label(CaptureSettings.snapshot(context).zoomRatio))
                views.setTextColor(R.id.widgetZoom, context.getColor(android.R.color.white))
                views.setTextViewCompoundDrawables(R.id.widgetZoom, 0, R.drawable.ic_zoom, 0, 0)
                views.setInt(R.id.widgetZoom, "setBackgroundResource", accentBackground)
            }
        }
        if (type.hasSequence) {
            views.setBoolean(R.id.widgetSequence, "setEnabled", true)
            views.setFloat(R.id.widgetSequence, "setAlpha", 1f)
            views.setImageViewResource(R.id.widgetSequence, if (neutral) R.drawable.ic_grid else R.drawable.ic_burst)
            views.setInt(R.id.widgetSequence, "setBackgroundResource", accentBackground)
        }
        if (type.hasPhoto) {
            views.setBoolean(R.id.widgetPhoto, "setEnabled", true)
            views.setFloat(R.id.widgetPhoto, "setAlpha", 1f)
            views.setImageViewResource(R.id.widgetPhoto, if (neutral) R.drawable.ic_add else R.drawable.ic_camera)
            views.setInt(R.id.widgetPhoto, "setBackgroundResource", accentBackground)
        }
        views.setBoolean(R.id.widgetStart, "setEnabled", true)
        views.setFloat(R.id.widgetStart, "setAlpha", 1f)
        views.setImageViewResource(R.id.widgetStart, if (neutral) R.drawable.ic_play else R.drawable.ic_record)
        views.setInt(R.id.widgetStart, "setBackgroundResource", accentBackground)

        views.setBoolean(R.id.widgetStop, "setEnabled", false)
        views.setFloat(R.id.widgetStop, "setAlpha", 1f)
        views.setImageViewResource(R.id.widgetStop, if (neutral) R.drawable.ic_stop else R.drawable.ic_widget_stop_off)
        views.setInt(R.id.widgetStop, "setBackgroundResource", disabledBackground)
        return views
    }

    private fun applyWidgetChrome(views: RemoteViews, context: Context, type: WidgetType) {
        when (type) {
            WidgetType.EXPANDED -> {
                views.setInt(R.id.widgetRoot, "setBackgroundResource", WidgetAppearance.expandedShellBackground(context))
                views.setInt(R.id.widgetDivider, "setBackgroundResource", WidgetAppearance.dividerBackground(context))
                views.setImageViewResource(R.id.widgetAppLogo, VisualIdentityStore.widgetLogo(context))
                views.setContentDescription(R.id.widgetLogoArea, VisualIdentityStore.notificationTitle(context, ""))
                views.setInt(R.id.widgetLogoArea, "setBackgroundResource", WidgetAppearance.logoBackground(context))
            }
            WidgetType.COMPACT -> views.setInt(R.id.widgetRoot, "setBackgroundResource", WidgetAppearance.compactShellBackground(context))
            WidgetType.LOCK_SCREEN -> Unit
        }
    }

    private fun applyWidgetDimensions(views: RemoteViews, type: WidgetType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val buttonIds = buildList {
            if (type.hasZoom) add(R.id.widgetZoom)
            if (type.hasSequence) add(R.id.widgetSequence)
            if (type.hasPhoto) add(R.id.widgetPhoto)
            add(R.id.widgetStart)
            add(R.id.widgetStop)
        }
        buttonIds.forEach { viewId ->
            views.setViewLayoutWidth(viewId, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(viewId, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
        }
        if (type == WidgetType.EXPANDED) {
            views.setViewLayoutWidth(R.id.widgetLogoArea, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.widgetLogoArea, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(R.id.widgetAppLogo, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.widgetAppLogo, type.buttonSizeDp, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    fun supportsUsefulZoom(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching false
        val preferred = CaptureSettings.snapshot(context).selectedCameraId
        val cameraId = preferred?.takeIf { it in manager.cameraIdList } ?: manager.cameraIdList.firstOrNull() ?: return@runCatching false
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val ratioZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        } else {
            1f
        }
        val digitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        maxOf(ratioZoom, digitalZoom) > 1.01f
    }.getOrDefault(false)

    private fun widgetZoomIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            514,
            Intent(context, WidgetZoomReceiver::class.java)
                .setAction(WidgetZoomReceiver.ACTION_CYCLE)
                .setData(Uri.parse("steadyvault://widget/background-zoom")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun configureZoomButton(
        views: RemoteViews,
        context: Context,
        enabled: Boolean,
        clickIntent: PendingIntent,
        type: WidgetType
    ) {
        val label = BackgroundRecordingZoom.label(CaptureSettings.snapshot(context).zoomRatio)
        views.setBoolean(R.id.widgetZoom, "setEnabled", enabled)
        views.setFloat(R.id.widgetZoom, "setAlpha", 1f)
        views.setTextViewText(R.id.widgetZoom, label)
        views.setTextColor(
            R.id.widgetZoom,
            context.getColor(if (enabled) android.R.color.white else R.color.text_muted)
        )
        views.setTextViewCompoundDrawables(
            R.id.widgetZoom,
            0,
            if (enabled) R.drawable.ic_zoom else R.drawable.ic_zoom_disabled,
            0,
            0
        )
        if (type == WidgetType.LOCK_SCREEN) {
            applyLockButtonBackground(
                views,
                context,
                R.id.widgetZoom,
                if (enabled) WidgetAppearance.LockTone.ACCENT else WidgetAppearance.LockTone.DISABLED
            )
        } else {
            views.setInt(
                R.id.widgetZoom,
                "setBackgroundResource",
                if (enabled) WidgetAppearance.expandedAccentBackground(context) else WidgetAppearance.expandedDisabledBackground(context)
            )
        }
        views.setOnClickPendingIntent(R.id.widgetZoom, clickIntent)
        views.setContentDescription(R.id.widgetZoom, "Zoom da foto e do vídeo: $label. Toque para mudar.")
    }

    private fun configureRecordingIndicator(
        views: RemoteViews,
        context: Context,
        viewId: Int,
        enabled: Boolean,
        clickIntent: PendingIntent,
        recording: Boolean,
        stopIcon: Boolean,
        type: WidgetType
    ) {
        views.setBoolean(viewId, "setEnabled", enabled)
        // ImageView não aplica o flash de estado pressionado do ImageButton.
        // Reafirmar alpha integral também impede que uma atualização parcial
        // herde a aparência esmaecida do RemoteViews anterior.
        views.setFloat(viewId, "setAlpha", 1f)

        val neutralWidgetActions = VisualIdentityStore.neutralWidgetActions(context)
        val icon = if (neutralWidgetActions) {
            if (stopIcon) R.drawable.ic_stop else R.drawable.ic_play
        } else {
            when {
                recording && stopIcon -> R.drawable.ic_widget_stop_red
                recording -> R.drawable.ic_widget_record_active
                stopIcon -> R.drawable.ic_widget_stop_off
                enabled -> R.drawable.ic_record
                else -> R.drawable.ic_widget_record_off
            }
        }

        views.setImageViewResource(viewId, icon)

        if (type == WidgetType.LOCK_SCREEN) {
            val tone = when {
                recording && stopIcon -> WidgetAppearance.LockTone.STOP
                recording -> WidgetAppearance.LockTone.RECORDING
                !stopIcon && enabled -> WidgetAppearance.LockTone.ACCENT
                else -> WidgetAppearance.LockTone.DISABLED
            }
            applyLockButtonBackground(views, context, viewId, tone)
        } else {
            views.setInt(
                viewId,
                "setBackgroundResource",
                when {
                    recording && stopIcon -> R.drawable.bg_widget_expanded_button_red
                    recording -> R.drawable.bg_widget_expanded_button_recording
                    !stopIcon && enabled -> WidgetAppearance.expandedAccentBackground(context)
                    else -> WidgetAppearance.expandedDisabledBackground(context)
                }
            )
        }
        views.setOnClickPendingIntent(viewId, clickIntent)
    }

    private fun configureButton(
        views: RemoteViews,
        context: Context,
        type: WidgetType,
        viewId: Int,
        enabled: Boolean,
        clickIntent: PendingIntent,
        enabledIcon: Int,
        disabledIcon: Int,
        enabledBackground: Int,
        disabledBackground: Int
    ) {
        views.setBoolean(viewId, "setEnabled", enabled)
        views.setFloat(viewId, "setAlpha", 1f)
        views.setImageViewResource(viewId, if (enabled) enabledIcon else disabledIcon)
        if (type == WidgetType.LOCK_SCREEN) {
            applyLockButtonBackground(
                views,
                context,
                viewId,
                if (enabled) WidgetAppearance.LockTone.ACCENT else WidgetAppearance.LockTone.DISABLED
            )
        } else {
            views.setInt(
                viewId,
                "setBackgroundResource",
                if (enabled) enabledBackground else disabledBackground
            )
        }
        views.setOnClickPendingIntent(viewId, clickIntent)
    }

    private fun applyLockButtonBackground(
        views: RemoteViews,
        context: Context,
        viewId: Int,
        tone: WidgetAppearance.LockTone
    ) {
        val backgroundViewId = when (viewId) {
            R.id.widgetZoom -> R.id.widgetZoomBackground
            R.id.widgetPhoto -> R.id.widgetPhotoBackground
            R.id.widgetStart -> R.id.widgetStartBackground
            R.id.widgetStop -> R.id.widgetStopBackground
            else -> return
        }
        views.setInt(viewId, "setBackgroundResource", android.R.color.transparent)
        views.setImageViewBitmap(
            backgroundViewId,
            WidgetAppearance.lockButtonBitmap(context, tone, WidgetType.LOCK_SCREEN.buttonSizeDp)
        )
    }
}
