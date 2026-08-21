package com.steadyvault.camera.core.camera

import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

object Camera3AStateStore {
    data class WhiteBalanceSnapshot(
        val gains: RggbChannelVector,
        val transform: ColorSpaceTransform,
        val capturedAtElapsedMs: Long
    )

    private val whiteBalance = ConcurrentHashMap<String, WhiteBalanceSnapshot>()

    fun updateWhiteBalance(cameraId: String, gains: RggbChannelVector, transform: ColorSpaceTransform) {
        whiteBalance[cameraId] = WhiteBalanceSnapshot(gains, transform, SystemClock.elapsedRealtime())
    }

    fun recentWhiteBalance(cameraId: String, maximumAgeMs: Long = 3_000L): WhiteBalanceSnapshot? {
        val snapshot = whiteBalance[cameraId] ?: return null
        return snapshot.takeIf { SystemClock.elapsedRealtime() - it.capturedAtElapsedMs <= maximumAgeMs }
    }
}
