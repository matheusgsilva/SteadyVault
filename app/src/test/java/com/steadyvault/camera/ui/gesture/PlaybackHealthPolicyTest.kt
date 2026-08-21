package com.steadyvault.camera.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHealthPolicyTest {
    @Test fun classifiesHealthyPlayback() = assertEquals(
        PlaybackHealthPolicy.State.HEALTHY,
        PlaybackHealthPolicy.classify(900, 890, 1f)
    )

    @Test fun classifiesSlowPlayback() = assertEquals(
        PlaybackHealthPolicy.State.SLOW,
        PlaybackHealthPolicy.classify(900, 500, 1f)
    )

    @Test fun classifiesStall() = assertEquals(
        PlaybackHealthPolicy.State.STALLED,
        PlaybackHealthPolicy.classify(900, 40, 1f)
    )
}
