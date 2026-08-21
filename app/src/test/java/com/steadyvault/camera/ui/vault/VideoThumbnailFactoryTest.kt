package com.steadyvault.camera.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoThumbnailFactoryTest {
    @Test
    fun rotatesRawLandscapeFrameForPortraitVideo() {
        assertEquals(
            270,
            VideoThumbnailFactory.rotationToApply(640, 360, 3840, 2160, -90)
        )
    }

    @Test
    fun doesNotRotateFrameAlreadyReturnedInDisplayOrientation() {
        assertEquals(
            0,
            VideoThumbnailFactory.rotationToApply(360, 640, 3840, 2160, -90)
        )
    }

    @Test
    fun preservesZeroAndHalfTurnMetadata() {
        assertEquals(0, VideoThumbnailFactory.rotationToApply(640, 360, 640, 360, 0))
        assertEquals(180, VideoThumbnailFactory.rotationToApply(640, 360, 640, 360, 180))
    }
}
