package com.steadyvault.camera.capture.service

internal object NativeCameraMetadataProbe {
    private val loadResult by lazy {
        runCatching { System.loadLibrary("steadyvault_camera_probe") }
    }

    fun inspectCharacteristics(cameraId: String, names: Array<String>): List<String> {
        val error = loadResult.exceptionOrNull()
        if (error != null) {
            return listOf("nativeLoad=false error=${error.javaClass.simpleName}:${error.message}")
        }
        return runCatching { nativeInspectCharacteristics(cameraId, names).toList() }
            .getOrElse {
                listOf("nativeCall=false error=${it.javaClass.simpleName}:${it.message}")
            }
    }

    @JvmStatic
    private external fun nativeInspectCharacteristics(
        cameraId: String,
        names: Array<String>
    ): Array<String>
}
