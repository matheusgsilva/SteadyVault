package com.steadyvault.camera.capture.recorder

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfrTimeResamplerTest {
    private val interval = 1_000_000_000L / 60L

    /** Alimenta o reamostrador com posições em unidades de intervalo; devolve os pesos por frame. */
    private fun run(positions: List<Double>): List<List<Float>> {
        val resampler = CfrTimeResampler(interval)
        resampler.start((positions.first() * interval).toLong())
        val result = ArrayList<List<Float>>()
        for (index in 1 until positions.size) {
            val previous = (positions[index - 1] * interval).toLong()
            val current = (positions[index] * interval).toLong()
            val count = resampler.plan(previous, current)
            result.add(List(count) { resampler.alphaAt(it) })
        }
        return result
    }

    @Test
    fun exactCadenceUsesEveryFrameDirectly() {
        val result = run((0 until 600).map { it.toDouble() })
        assertTrue(result.all { it == listOf(1f) })
    }

    @Test
    fun oneMissingFrameProducesOneHalfwayBlend() {
        val result = run((0 until 600).filter { it != 100 }.map { it.toDouble() })
        val multi = result.filter { it.size != 1 }
        assertEquals(1, multi.size)
        assertEquals(2, multi.single().size)
        assertEquals(0.5f, multi.single()[0], 0.02f)
        assertEquals(1f, multi.single()[1], 0.0001f)
    }

    @Test
    fun longGapIsFilledWithIncreasingWeights() {
        val positions = (0 until 100).map { it.toDouble() } + (130 until 300).map { it.toDouble() }
        val biggest = run(positions).maxByOrNull { it.size }!!
        assertEquals(31, biggest.size)
        assertTrue((0 until biggest.size - 1).all { biggest[it] < biggest[it + 1] })
        assertEquals(1f, biggest.last(), 0.0001f)
    }

    @Test
    fun steadyJitterWithinToleranceIsNotBlendedAfterWarmup() {
        for (sign in listOf(1.0, -1.0)) {
            val positions = (0 until 2000).map { it + sign * if (it % 2 == 1) 0.2 else -0.2 }
            val result = run(positions)
            // A grade começa ancorada no 1º frame e o PLL leva ~0,3 s para centralizar.
            assertTrue(result.drop(60).all { it == listOf(1f) })
        }
    }

    @Test
    fun oneVeryLateFrameKeepsOutputCountEqualToRealTime() {
        val positions = (0 until 600).map { it.toDouble() }.toMutableList()
        positions[300] += 0.5
        val result = run(positions)
        assertEquals(599, result.sumOf { it.size })
        assertTrue(result.all { frame -> frame.all { it in 0f..1f } })
    }

    @Test
    fun cameraFasterThanNominalDropsExcessAndFollowsRealTime() {
        val result = run((0 until 3660).map { it * 60.0 / 61.0 })
        val outputs = 1 + result.sumOf { it.size }
        assertTrue("saídas=$outputs", abs(outputs - 3600) <= 2)
        assertTrue(result.count { it.isEmpty() } >= 55)
    }

    @Test
    fun slowClockDriftStaysDirectAndKeepsAvSyncWithinOneFrame() {
        val positions = (0 until 3600).map { it * 1.001 }
        val result = run(positions)
        val outputs = 1 + result.sumOf { it.size }
        val all = result.flatten()
        val direct = all.count { it == 0f || it == 1f }.toDouble() / all.size
        assertTrue("diretos=$direct", direct > 0.97)
        assertTrue(abs(outputs - (positions.last() - positions.first() + 1.0)) <= 1.0)
    }

    @Test
    fun weightsAlwaysStayInsideZeroToOneUnderHeavyJitter() {
        val random = Random(3)
        val positions = (0 until 3000).map { it + random.nextDouble(-0.36, 0.36) }.sorted()
        assertTrue(run(positions).all { frame -> frame.all { it in 0f..1f } })
    }
}
