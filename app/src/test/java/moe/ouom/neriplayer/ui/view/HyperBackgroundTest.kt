package moe.ouom.neriplayer.ui.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperBackgroundTest {
    @Test
    fun `late palette from a previous song cannot commit`() {
        assertFalse(
            shouldCommitDynamicBackgroundPalette(
                requestIdentity = "song-a|cover-a|1|false|true",
                latestIdentity = "song-b|cover-b|2|false|true"
            )
        )
        assertTrue(
            shouldCommitDynamicBackgroundPalette(
                requestIdentity = "song-b|cover-b|2|false|true",
                latestIdentity = "song-b|cover-b|2|false|true"
            )
        )
    }

    @Test
    fun `render schedule keeps cadence and resets after a long stall`() {
        val intervalNs = 1_000_000_000L / 45L
        val firstFrameNs = 1_000_000_000L
        val firstNextNs = nextDynamicBackgroundRenderNs(
            frameNs = firstFrameNs,
            currentNextNs = Long.MIN_VALUE,
            intervalNs = intervalNs
        )

        assertEquals(firstFrameNs + intervalNs, firstNextNs)
        assertEquals(
            firstFrameNs + intervalNs * 2L,
            nextDynamicBackgroundRenderNs(
                frameNs = firstNextNs,
                currentNextNs = firstNextNs,
                intervalNs = intervalNs
            )
        )

        val stalledFrameNs = firstNextNs + intervalNs * 3L
        assertEquals(
            stalledFrameNs + intervalNs,
            nextDynamicBackgroundRenderNs(
                frameNs = stalledFrameNs,
                currentNextNs = firstNextNs,
                intervalNs = intervalNs
            )
        )
    }
}
