package com.steadyvault.camera.processing.analysis

import kotlin.math.min

/**
 * Separa perda real de quadro de jitter compensado de PTS.
 *
 * Alguns encoders/HALs entregam pares como 27 ms + 6 ms em 60 FPS. O delta de
 * 27 ms isoladamente ultrapassa 1,5x o período nominal, mas o delta curto seguinte
 * devolve a timeline para a grade correta. Reconstruir um quadro nesse caso cria
 * conteúdo sintético sem existir uma perda real.
 */
object CadenceGapClassifier {
    data class Result(
        val largeGapCount: Int,
        val estimatedMissingFrames: Int,
        val compensatedJitterCount: Int
    )

    fun classify(deltasUs: List<Long>, nominalDeltaUs: Long): Result {
        if (nominalDeltaUs <= 0L || deltasUs.isEmpty()) return Result(0, 0, 0)

        val gapThreshold = nominalDeltaUs * 3L / 2L
        val shortThreshold = nominalDeltaUs * 4L / 5L
        var largeGapCount = 0
        var estimatedMissingFrames = 0
        var compensatedJitterCount = 0

        deltasUs.forEachIndexed { index, delta ->
            if (delta <= gapThreshold) return@forEachIndexed

            val rawMissing = missingSlots(delta, nominalDeltaUs)
            if (rawMissing <= 0) return@forEachIndexed

            var effectiveMissing = rawMissing
            val previous = deltasUs.getOrNull(index - 1)
            val next = deltasUs.getOrNull(index + 1)

            if (previous != null && previous < shortThreshold) {
                effectiveMissing = min(
                    effectiveMissing,
                    missingSlotsForWindow(previous + delta, nominalDeltaUs, observedIntervals = 2)
                )
            }
            if (next != null && next < shortThreshold) {
                effectiveMissing = min(
                    effectiveMissing,
                    missingSlotsForWindow(delta + next, nominalDeltaUs, observedIntervals = 2)
                )
            }

            if (effectiveMissing <= 0) {
                compensatedJitterCount++
            } else {
                largeGapCount++
                estimatedMissingFrames += effectiveMissing.coerceIn(1, 240)
            }
        }

        return Result(largeGapCount, estimatedMissingFrames, compensatedJitterCount)
    }

    private fun missingSlots(deltaUs: Long, nominalDeltaUs: Long): Int =
        (((deltaUs + nominalDeltaUs / 2L) / nominalDeltaUs) - 1L)
            .coerceAtLeast(0L)
            .toInt()

    private fun missingSlotsForWindow(
        spanUs: Long,
        nominalDeltaUs: Long,
        observedIntervals: Int
    ): Int {
        val expectedIntervals = ((spanUs + nominalDeltaUs / 2L) / nominalDeltaUs)
            .coerceAtLeast(observedIntervals.toLong())
        return (expectedIntervals - observedIntervals.toLong())
            .coerceAtLeast(0L)
            .toInt()
    }
}
