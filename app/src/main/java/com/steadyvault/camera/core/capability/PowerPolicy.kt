package com.steadyvault.camera.core.capability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object PowerPolicy {

    fun isIgnoring(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    fun openSettings(activity: Activity): Boolean {
        if (isIgnoring(activity)) {
            return startIfResolvable(
                activity,
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }

        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )

        if (startIfResolvable(activity, direct)) {
            return true
        }

        return startIfResolvable(
            activity,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }

    private fun startIfResolvable(
        activity: Activity,
        intent: Intent
    ): Boolean =
        runCatching {
            if (intent.resolveActivity(activity.packageManager) == null) {
                return@runCatching false
            }

            activity.startActivity(intent)
            true
        }.getOrDefault(false)
}
