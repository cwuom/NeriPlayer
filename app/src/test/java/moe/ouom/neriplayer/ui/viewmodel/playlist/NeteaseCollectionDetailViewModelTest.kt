package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseCollectionDetailViewModelTest {

    @Test
    fun `album cover fallback fills blank track cover`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "",
            fallback = "http://example.com/album.jpg"
        )

        assertEquals("https://example.com/album.jpg", resolved)
    }

    @Test
    fun `track cover wins over album fallback`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "http://example.com/track.jpg",
            fallback = "https://example.com/album.jpg"
        )

        assertEquals("https://example.com/track.jpg", resolved)
    }

    @Test
    fun `missing covers stay blank`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "   ",
            fallback = null
        )

        assertEquals("", resolved)
    }
}
