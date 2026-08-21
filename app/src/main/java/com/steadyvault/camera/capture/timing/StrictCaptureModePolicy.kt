package com.steadyvault.camera.capture.timing

object StrictCaptureModePolicy {
    data class Dimensions(val width: Int, val height: Int)
    data class Request(val dimensions: Dimensions, val fps: Int)

    fun requestOrder(requestedSize: Dimensions, targetFps: Int): List<Request> {
        require(targetFps > 0) { "targetFps must be positive" }
        return listOf(Request(requestedSize, targetFps))
    }

    fun matches(requestedSize: Dimensions, targetFps: Int, actualSize: Dimensions, actualFps: Int): Boolean =
        actualFps == targetFps && actualSize == requestedSize

    fun requiresExactFpsRange(targetFps: Int): Boolean = targetFps == 60

    fun acceptsFpsRange(targetFps: Int, lower: Int, upper: Int): Boolean {
        require(targetFps > 0) { "targetFps must be positive" }
        return if (requiresExactFpsRange(targetFps)) {
            lower == targetFps && upper == targetFps
        } else {
            lower <= targetFps && upper >= targetFps
        }
    }
}
