package com.steadyvault.camera.capture.service

import android.hardware.camera2.CameraMetadata

internal object NativeCameraMetadataProbe {
    private val loadResult by lazy {
        runCatching { System.loadLibrary("steadyvault_camera_probe") }
    }

    fun inspect(metadata: CameraMetadata<*>, names: Array<String>): List<String> {
        val error = loadResult.exceptionOrNull()
        if (error != null) {
            return listOf("nativeLoad=false error=${error.javaClass.simpleName}:${error.message}")
        }
        return runCatching { nativeInspect(metadata, names).toList() }
            .getOrElse {
                listOf("nativeCall=false error=${it.javaClass.simpleName}:${it.message}")
            }
    }

    @JvmStatic
    private external fun nativeInspect(
        metadata: CameraMetadata<*>,
        names: Array<String>
    ): Array<String>
}
