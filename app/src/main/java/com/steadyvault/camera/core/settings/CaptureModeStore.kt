package com.steadyvault.camera.core.settings

import android.content.Context

object CaptureModeStore {
    const val FPS_30 = 30
    const val FPS_60 = 60
    const val FPS_120 = 120
    const val FPS_240 = 240

    fun getTargetFps(context: Context): Int = CaptureSettings.snapshot(context).fps

}
