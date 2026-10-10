package moe.ouom.neriplayer.core.player.engine

import androidx.media3.exoplayer.Renderer
import org.junit.Assert.assertEquals
import org.junit.Test

class SteadyFeedAudioRendererTest {
    @Test
    fun `renderer sleep follows the audio buffer unless the visualizer needs a steady feed`() {
        assertEquals(240_000L, steadyFeedDurationToProgressUs(240_000L, steadyFeedRequired = false))
        assertEquals(
            Renderer.DEFAULT_DURATION_TO_PROGRESS_US,
            steadyFeedDurationToProgressUs(240_000L, steadyFeedRequired = true)
        )
        assertEquals(4_000L, steadyFeedDurationToProgressUs(4_000L, steadyFeedRequired = true))
    }
}
