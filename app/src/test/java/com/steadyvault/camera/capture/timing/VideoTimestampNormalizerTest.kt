package com.steadyvault.camera.capture.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VideoTimestampNormalizerTest {
    @Test
    fun stableSourceKeepsExactRawPtsAtEverySupportedRecordingMode() {
        val cases = mapOf(
            30 to longArrayOf(0L, 33_332L, 66_670L, 100_001L, 133_336L),
            60 to longArrayOf(0L, 16_667L, 33_331L, 50_005L, 66_669L),
            120 to longArrayOf(0L, 8_332L, 16_668L, 25_001L, 33_335L),
            240 to longArrayOf(0L, 4_166L, 8_334L, 12_501L, 16_669L)
        )

        cases.forEach { (fps, rawPts) ->
            val normalized = normalizeAll(fps, rawPts)
            assertArrayEquals("FPS $fps não deve alterar PTS válidos", rawPts, normalized)
        }
    }

    @Test
    fun subNominalSourceAtSixtyKeepsItsRealCadenceWithoutPeriodicSnaps() {
        val rawAtAbout57Fps = longArrayOf(
            0L,
            17_544L,
            35_088L,
            52_632L,
            70_175L,
            87_719L,
            105_263L,
            122_807L,
            140_351L
        )

        assertArrayEquals(rawAtAbout57Fps, normalizeAll(60, rawAtAbout57Fps))
    }

    @Test
    fun jitterIsPreservedInsteadOfBeingRoundedToTheNominalGrid() {
        val rawPts = longArrayOf(
            73_000L,
            89_102L,
            106_774L,
            122_901L,
            140_880L,
            156_993L
        )

        assertArrayEquals(rawPts, normalizeAll(60, rawPts))
    }

    @Test
    fun duplicateAndRegressivePtsAdvanceOnlyByOneMicrosecond() {
        val normalizer = VideoTimestampNormalizer(60)
        val rawPts = longArrayOf(10_000L, 20_000L, 20_000L, 19_500L, 20_001L, 35_000L)
        val expected = longArrayOf(10_000L, 20_000L, 20_001L, 20_002L, 20_003L, 35_000L)

        assertArrayEquals(expected, rawPts.map(normalizer::normalize).toLongArray())
    }

    @Test
    fun realGapAndInitialAudioOffsetRemainExact() {
        val normalizer = VideoTimestampNormalizer(240)

        assertEquals(73_000L, normalizer.normalize(73_000L))
        assertEquals(77_167L, normalizer.normalize(77_167L))
        assertEquals(98_000L, normalizer.normalize(98_000L))
    }

    @Test
    fun resetStartsANewIndependentTimeline() {
        val normalizer = VideoTimestampNormalizer(120)
        assertEquals(10_000L, normalizer.normalize(10_000L))
        assertEquals(20_000L, normalizer.normalize(20_000L))
        assertEquals(20_001L, normalizer.normalize(19_000L))

        normalizer.reset()

        assertEquals(5_000L, normalizer.normalize(5_000L))
        assertEquals(13_350L, normalizer.normalize(13_350L))
    }

    @Test
    fun frameIntervalStillReportsTheNominalModeInterval() {
        assertEquals(33_333L, VideoTimestampNormalizer(30).frameIntervalUs())
        assertEquals(16_667L, VideoTimestampNormalizer(60).frameIntervalUs())
        assertEquals(8_333L, VideoTimestampNormalizer(120).frameIntervalUs())
        assertEquals(4_167L, VideoTimestampNormalizer(240).frameIntervalUs())
    }

    private fun normalizeAll(fps: Int, rawPts: LongArray): LongArray {
        val normalizer = VideoTimestampNormalizer(fps)
        return rawPts.map(normalizer::normalize).toLongArray()
    }
}
