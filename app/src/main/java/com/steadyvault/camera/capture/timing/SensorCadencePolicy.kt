package com.steadyvault.camera.capture.timing

import kotlin.math.roundToLong

object SensorCadencePolicy {
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
        if (!manualSensorSupported || fps <= 0 || fps > 60 || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null
        if (exposureMinNs <= 0L || exposureMaxNs < exposureMinNs || sensitivityMinIso <= 0 || sensitivityMaxIso < sensitivityMinIso) return null

        val frameDurationNs = (1_000_000_000.0 / fps.toDouble()).roundToLong()
        if (maxFrameDurationNs in 1 until frameDurationNs) return null

        // Mantém o mesmo contrato de cadência em todos os modos regulares até 60 FPS:
        // reserva 2 ms reais para readout/ISP em vez de deixar a exposição ocupar todo
        // o período do frame. Em 60 FPS isso evita o teto de ~16,667 ms; em 30/24 FPS
        // a folga é calculada proporcionalmente ao próprio período do modo.
        val headroomExposureCapNs = frameDurationNs - 2_000_000L
        val exposureCapNs = minOf(exposureMaxNs, headroomExposureCapNs)
        if (exposureCapNs < exposureMinNs) return null

        val exposureTimeNs = observedExposureNs.coerceIn(exposureMinNs, exposureCapNs)
        val requiredIso =
            observedSensitivityIso.toDouble() *
                observedExposureNs.toDouble() /
                exposureTimeNs.toDouble()
        val compensatedIso = requiredIso.roundToLong()
            .coerceIn(sensitivityMinIso.toLong(), sensitivityMaxIso.toLong())
            .toInt()

        return Plan(frameDurationNs, exposureTimeNs, compensatedIso)
    }
}
