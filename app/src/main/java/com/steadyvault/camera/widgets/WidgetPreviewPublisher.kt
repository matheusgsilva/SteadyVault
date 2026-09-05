package com.steadyvault.camera.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.ui.theme.AppearanceStore

object WidgetPreviewPublisher {
    private const val PREF = "steadyvault_widget_preview_state"

    private data class PreviewSpec(
        val provider: Class<*>,
        val type: WidgetRenderer.WidgetType,
        val categories: Int
    )

    private val specs = listOf(
        PreviewSpec(
            ExpandedControlWidget::class.java,
            WidgetRenderer.WidgetType.EXPANDED,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        ),
        PreviewSpec(
            CompactControlWidget::class.java,
            WidgetRenderer.WidgetType.COMPACT,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        ),
        PreviewSpec(
            LockScreenControlWidget::class.java,
            WidgetRenderer.WidgetType.LOCK_SCREEN,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN or AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
        )
    )

    fun publishIfNeeded(context: Context, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val zoomAvailable = WidgetRenderer.supportsUsefulZoom(appContext)
        val signature = buildString {
            append(AppearanceStore.summary(appContext))
            append("|zoom=").append(zoomAvailable)
            append("|identity=").append(VisualIdentityStore.mode(appContext))
            append("|neutral=").append(VisualIdentityStore.neutralWidgetActions(appContext))
            append("|label=").append(VisualIdentityStore.customLabel(appContext))
            append("|v8-lock-style-themed-previews")
        }

        specs.forEach { spec ->
            val key = "signature_${spec.provider.name}"
            if (!force && prefs.getString(key, null) == signature) return@forEach
            val preview = WidgetRenderer.buildPreviewViews(appContext, spec.type)
            runCatching {
                manager.setWidgetPreview(ComponentName(appContext, spec.provider), spec.categories, preview)
            }.onSuccess { published ->
                if (published) {
                    prefs.edit().putString(key, signature).apply()
                } else {
                    AppLogRepository.warn(appContext, "WidgetPreview", "Prévia dinâmica limitada pelo sistema para ${spec.provider.simpleName}; será tentada novamente.")
                }
            }.onFailure {
                AppLogRepository.warn(appContext, "WidgetPreview", "Falha ao publicar prévia de ${spec.provider.simpleName}: ${it.message}")
            }
        }
    }
}
