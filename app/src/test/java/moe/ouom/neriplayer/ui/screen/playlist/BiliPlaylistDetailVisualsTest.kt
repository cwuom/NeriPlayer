package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliPlaylistDetailVisualsTest {
    @Test
    fun heroCoverUsesTheSameBoundedBiliRenditionAsVisualWarmup() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/cover.jpg@320w_320h_1c.webp",
            buildBiliPlaylistHeroCoverUrl(
                "https://i0.hdslb.com/bfs/archive/cover.jpg"
            )
        )
    }

    @Test
    fun blankHeroCoverDoesNotCreateAnImageRequestUrl() {
        assertEquals("", buildBiliPlaylistHeroCoverUrl(""))
    }

}
