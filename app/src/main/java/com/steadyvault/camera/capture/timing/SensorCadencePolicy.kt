package com.steadyvault.camera.capture.timing

import kotlin.math.roundToLong

object SensorCadencePolicy {
    data class Plan(
        val frameDurationNs: Long,
        val exposureTimeNs: Long,
        val sensitivityIso: Int
    )

    /** Cadência fixa (AE_MODE_OFF + duração de quadro fixa) vale para as taxas altas do sensor. */
    fun supportsFixedCadence(fps: Int): Boolean = fps == 60 || fps == 120

    fun resolve(
        fps: Int,
        observedExposureNs: Long,
        observedSensitivityIso: Int,
        exposureMinNs: Long,
        exposureMaxNs: Long,
        sensitivityMinIso: Int,
        sensitivityMaxIso: Int,
        maxFrameDurationNs: Long,
        manualSensorSupported: Boolean,
        minFrameDurationNs: Long = 0L
    ): Plan? {
        if (!manualSensorSupported || !supportsFixedCadence(fps) || observedExposureNs <= 0L || observedSensitivityIso <= 0) return null
        if (exposureMinNs <= 0L || exposureMaxNs < exposureMinNs || sensitivityMinIso <= 0 || sensitivityMaxIso < sensitivityMinIso) return null

        val frameDurationNs = maxOf((1_000_000_000.0 / fps.toDouble()).roundToLong(), minFrameDurationNs)
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

    /**
     * Reparte a "luz" total (exposição em ns x ISO) entre exposição e ISO, mantendo a duração do
     * quadro fixa. Padrão 1/120 s (menos borrão); com [lowLightPriority] a exposição sobe até
     * quase 1/60 s antes de o ISO passar de [comfortIso] (menos ruído no escuro, mais borrão).
     */
    fun resolveFromLight(
        fps: Int,
        light: Double,
        exposureMinNs: Long,
        exposureMaxNs: Long,
        sensitivityMinIso: Int,
        sensitivityMaxIso: Int,
        maxFrameDurationNs: Long,
        manualSensorSupported: Boolean,
        lowLightPriority: Boolean,
        comfortIso: Int = 800,
        minFrameDurationNs: Long = 0L
    ): Plan? {
        if (!manualSensorSupported || !supportsFixedCadence(fps) || light <= 0.0) return null
        if (exposureMinNs <= 0L || exposureMaxNs < exposureMinNs || sensitivityMinIso <= 0 || sensitivityMaxIso < sensitivityMinIso) return null

        val frameDurationNs = maxOf((1_000_000_000.0 / fps.toDouble()).roundToLong(), minFrameDurationNs)
        if (maxFrameDurationNs in 1 until frameDurationNs) return null

        val motionCapNs = (1_000_000_000.0 / (fps * 2.0)).roundToLong()
        val absCapNs = minOf(exposureMaxNs, frameDurationNs - 500_000L)
        if (absCapNs < exposureMinNs) return null

        val baseCap = minOf(motionCapNs, absCapNs)
        var exposure = if (lowLightPriority) {
            (light / comfortIso).toLong().coerceIn(baseCap, absCapNs)
        } else baseCap
        exposure = exposure.coerceIn(exposureMinNs, absCapNs)

        var iso = light / exposure.toDouble()
        if (iso > sensitivityMaxIso) {
            iso = sensitivityMaxIso.toDouble()
            exposure = (light / iso).toLong().coerceIn(exposureMinNs, absCapNs)
        } else if (iso < sensitivityMinIso) {
            iso = sensitivityMinIso.toDouble()
            exposure = (light / iso).toLong().coerceIn(exposureMinNs, absCapNs)
        }
        val isoInt = iso.roundToLong().coerceIn(sensitivityMinIso.toLong(), sensitivityMaxIso.toLong()).toInt()
        return Plan(frameDurationNs, exposure, isoInt)
    }
}
