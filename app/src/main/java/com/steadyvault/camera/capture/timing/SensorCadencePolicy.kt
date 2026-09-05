package com.steadyvault.camera.capture.timing

import kotlin.math.roundToLong

object SensorCadencePolicy {
    private val SUPPORTED_FPS = setOf(30, 60, 120, 240)

    data class Plan(
        val frameDurationNs: Long,
        val exposureTimeNs: Long,
        val sensitivityIso: Int
    )

    fun resolve(
        fps: Int,
        observedExposureNs: Long,
        observedSensitivityIso: Int,
        exposureMinNs: Long,
        exposureMaxNs: Long,
        sensitivityMinIso: Int,
        sensitivityMaxIso: Int,
        maxFrameDurationNs: Long,
        manualSensorSupported: Boolean
    ): Plan? {
        if (!manualSensorSupported || fps !in SUPPORTED_FPS || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null
        if (exposureMinNs <= 0L || exposureMaxNs < exposureMinNs || sensitivityMinIso <= 0 || sensitivityMaxIso < sensitivityMinIso) return null

        val frameDurationNs = (1_000_000_000.0 / fps.toDouble()).roundToLong()
        if (maxFrameDurationNs in 1 until frameDurationNs) return null

        val motionExposureCapNs = (1_000_000_000.0 / (fps * 2.0)).roundToLong()
        val absoluteExposureCapNs = minOf(exposureMaxNs, frameDurationNs - 500_000L)
        if (absoluteExposureCapNs < exposureMinNs) return null

        var exposureTimeNs = observedExposureNs.coerceIn(exposureMinNs, minOf(absoluteExposureCapNs, motionExposureCapNs))
        var requiredIso = observedSensitivityIso.toDouble() * observedExposureNs.toDouble() / exposureTimeNs.toDouble()
        if (requiredIso > sensitivityMaxIso.toDouble()) {
            val exposureForMaxIso = (observedSensitivityIso.toDouble() * observedExposureNs.toDouble() / sensitivityMaxIso.toDouble()).roundToLong()
            exposureTimeNs = maxOf(exposureTimeNs, exposureForMaxIso).coerceIn(exposureMinNs, absoluteExposureCapNs)
            requiredIso = observedSensitivityIso.toDouble() * observedExposureNs.toDouble() / exposureTimeNs.toDouble()
        }
        val compensatedIso = requiredIso.roundToLong()
            .coerceIn(sensitivityMinIso.toLong(), sensitivityMaxIso.toLong())
            .toInt()

        return Plan(frameDurationNs, exposureTimeNs, compensatedIso)
    }
}
