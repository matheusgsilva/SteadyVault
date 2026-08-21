package com.steadyvault.camera.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class LockScreenControlWidget : AppWidgetProvider() {

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
        appWidgetIds.forEach { widgetId ->
            WidgetRenderer.updateWidget(
                context,
                appWidgetManager,
                widgetId,
                WidgetRenderer.WidgetType.LOCK_SCREEN
            )
        }
    }


    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        val manager = AppWidgetManager.getInstance(context)
        newWidgetIds.forEach { widgetId ->
            WidgetRenderer.updateWidget(
                context,
                manager,
                widgetId,
                WidgetRenderer.WidgetType.LOCK_SCREEN
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetRenderer.updateWidget(
            context,
            appWidgetManager,
            appWidgetId,
            WidgetRenderer.WidgetType.LOCK_SCREEN
        )
    }
}
