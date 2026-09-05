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

    data class ExposureSnapshot(
        val exposureTimeNs: Long,
        val sensitivityIso: Int,
        val frameDurationNs: Long,
        val capturedAtElapsedMs: Long
    )

    private val whiteBalance = ConcurrentHashMap<String, WhiteBalanceSnapshot>()
    private val exposure = ConcurrentHashMap<String, ExposureSnapshot>()

    fun updateWhiteBalance(cameraId: String, gains: RggbChannelVector, transform: ColorSpaceTransform) {
        whiteBalance[cameraId] = WhiteBalanceSnapshot(gains, transform, SystemClock.elapsedRealtime())
    }

    fun recentWhiteBalance(cameraId: String, maximumAgeMs: Long = 3_000L): WhiteBalanceSnapshot? {
        val snapshot = whiteBalance[cameraId] ?: return null
        return snapshot.takeIf { SystemClock.elapsedRealtime() - it.capturedAtElapsedMs <= maximumAgeMs }
    }

    fun updateExposure(cameraId: String, exposureTimeNs: Long, sensitivityIso: Int, frameDurationNs: Long) {
        if (exposureTimeNs <= 0L || sensitivityIso <= 0) return
        exposure[cameraId] = ExposureSnapshot(exposureTimeNs, sensitivityIso, frameDurationNs, SystemClock.elapsedRealtime())
    }

    fun recentExposure(cameraId: String, maximumAgeMs: Long = 3_000L): ExposureSnapshot? {
        val snapshot = exposure[cameraId] ?: return null
        return snapshot.takeIf { SystemClock.elapsedRealtime() - it.capturedAtElapsedMs <= maximumAgeMs }
    }
}
