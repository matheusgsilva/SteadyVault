package com.steadyvault.camera.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ExpandedControlWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WidgetRenderer.updateAll(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRenderer.updateAll(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach {
            WidgetRenderer.updateWidget(
                context,
                appWidgetManager,
                it,
                WidgetRenderer.WidgetType.EXPANDED
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        WidgetRenderer.updateWidget(
            context,
            appWidgetManager,
            appWidgetId,
            WidgetRenderer.WidgetType.EXPANDED
        )
    }

    companion object {
        fun updateAll(context: Context) {
            WidgetRenderer.updateAll(context)
        }

        fun updateRecordingControls(context: Context) {
            WidgetRenderer.updateRecordingControls(context)
        }
    }
}
