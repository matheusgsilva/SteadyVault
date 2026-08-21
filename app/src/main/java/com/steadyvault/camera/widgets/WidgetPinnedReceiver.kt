package com.steadyvault.camera.widgets

import com.steadyvault.camera.core.feedback.Haptics

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class WidgetPinnedReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != ACTION_PINNED) return

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Haptics.success(context)
            ExpandedControlWidget.updateAll(context)

            Toast.makeText(
                context,
                "Widget SteadyVault adicionado.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_PINNED =
            "com.steadyvault.camera.WIDGET_PINNED"
    }
}
