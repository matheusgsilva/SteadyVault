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
        if (!manualSensorSupported || fps != 60 || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null
        if (exposureMinNs <= 0L || exposureMaxNs < exposureMinNs || sensitivityMinIso <= 0 || sensitivityMaxIso < sensitivityMinIso) return null

        val frameDurationNs = (1_000_000_000.0 / fps.toDouble()).roundToLong()
        if (maxFrameDurationNs in 1 until frameDurationNs) return null

        // Em 4K60 observamos a HAL operando com exposição praticamente igual ao
        // período inteiro do frame (~16,667 ms), seguida de stalls exatos de um frame.
        // Reserve 2 ms reais para readout/ISP. Se o ISO máximo não conseguir preservar
        // toda a luminosidade, priorize a cadência em vez de devolver a exposição ao teto.
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
