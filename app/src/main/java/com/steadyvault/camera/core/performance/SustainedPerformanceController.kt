package com.steadyvault.camera.core.performance

import android.app.Activity
import android.os.PowerManager

object SustainedPerformanceController {
    fun isSupported(activity: Activity): Boolean = activity.getSystemService(PowerManager::class.java)?.isSustainedPerformanceModeSupported == true

    fun set(activity: Activity, enabled: Boolean): Boolean {
        if (!isSupported(activity)) return false
        return runCatching {
            activity.window.setSustainedPerformanceMode(enabled)
            true
        }.getOrDefault(false)
    }
}
