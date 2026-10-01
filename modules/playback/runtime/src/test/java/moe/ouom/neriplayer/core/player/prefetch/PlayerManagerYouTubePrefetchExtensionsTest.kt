package moe.ouom.neriplayer.core.player.prefetch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerManagerYouTubePrefetchExtensionsTest {

    @Test
    fun `playback prefetch keeps only the first two tracks`() {
        assertEquals(
            listOf("current", "next"),
            selectYouTubePrefetchVideoIds(
                videoIds = listOf("current", "next", "later", "last")
            )
        )
    }

    @Test
    fun `playable url warmup uses the same priority window`() {
        assertEquals(
            listOf("current", "next"),
            selectYouTubePlayableUrlWarmupIds(
                videoIds = listOf("current", "next", "later", "last")
            )
        )
    }

    @Test
    fun `playable url warmup accepts a smaller priority window`() {
        assertEquals(
            listOf("current"),
            selectYouTubePlayableUrlWarmupIds(
                videoIds = listOf("current", "next"),
                maxIds = 1
            )
        )
    }
}
