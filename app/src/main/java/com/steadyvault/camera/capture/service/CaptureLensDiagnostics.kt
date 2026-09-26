package com.steadyvault.camera.capture.service

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Observes metadata only; never changes camera selection or capture requests. */
internal class CaptureLensDiagnostics(private val openedId: String) {
    private var reportedLens = false
    private var lastPhysicalId: String? = null
    private var lastStatusMs = 0L

    fun onResult(result: TotalCaptureResult) {
        runCatching {
            val physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            val nowMs = SystemClock.elapsedRealtime()
            val lensChanged = !reportedLens || physicalId != lastPhysicalId
            if (!lensChanged && nowMs - lastStatusMs < 2_000L) return

            val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.get(CaptureResult.CONTROL_ZOOM_RATIO)
            } else null
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val aeName = when (aeState) {
                CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
                CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
                CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
                CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
                CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
                else -> "UNKNOWN($aeState)"
            }
            Log.i(TAG, "CAMERA_ACTIVE: opened=$openedId physical=${physicalId ?: "unknown"} " +
                "lensChanged=$lensChanged frame=${result.frameNumber} " +
                "focalMm=${result.get(CaptureResult.LENS_FOCAL_LENGTH)} zoom=$zoom " +
                "crop=${result.get(CaptureResult.SCALER_CROP_REGION)} " +
                "aeMode=${result.get(CaptureResult.CONTROL_AE_MODE)} AE=$aeName " +
                "aeLock=${result.get(CaptureResult.CONTROL_AE_LOCK)} " +
                "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                "ISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                "frameDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)}")
            reportedLens = true
            lastPhysicalId = physicalId
            lastStatusMs = nowMs
        }
    }

    companion object {
        private const val TAG = "SteadyVaultCapture"

        fun logCatalog(manager: CameraManager, openedId: String, opened: CameraCharacteristics) {
            runCatching {
                val publicIds = manager.cameraIdList.toSet()
                val physicalIds = opened.physicalCameraIds
                val ids = linkedSetOf(openedId).apply {
                    addAll(physicalIds)
                    addAll(publicIds)
                }
                Log.i(TAG, "CAMERA_MAP: opened=$openedId physicalIds=${physicalIds.joinToString()}")
                for (id in ids) {
                    runCatching {
                        val c = if (id == openedId) opened else manager.getCameraCharacteristics(id)
                        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        // Horizontal 35mm-equivalent estimate, before video crop/stabilization.
                        val equivalent = if (sensor != null && sensor.width > 0f) {
                            focals?.joinToString { (it * 36f / sensor.width).toString() }
                        } else null
                        val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                        Log.i(TAG, "CAMERA_LENS: id=$id public=${id in publicIds} " +
                            "memberOfOpened=${id in physicalIds} logical=$logical " +
                            "facing=${c.get(CameraCharacteristics.LENS_FACING)} " +
                            "focalMm=${focals?.joinToString()} sensorMm=$sensor " +
                            "equivalent35mmApprox=$equivalent " +
                            "pixels=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")
                    }.onFailure { Log.w(TAG, "CAMERA_LENS: id=$id unavailable=${it.javaClass.simpleName}") }
                }
            }.onFailure { Log.w(TAG, "CAMERA_MAP: unavailable=${it.javaClass.simpleName}") }
        }
    }
}
