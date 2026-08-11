package moe.ouom.neriplayer.core.api.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NeteasePlaylistTrackParamsTest {
    @Test
    fun `playlist add params include batch fields used by netease manipulate tracks`() {
        val params = buildNeteasePlaylistAddTracksParams(
            playlistId = 88L,
            songIds = listOf(1L, 2L, 2L, 0L, -1L, 3L)
        )

        assertEquals("add", params["op"])
        assertEquals("88", params["pid"])
        assertEquals("88", params["id"])
        assertEquals("1,2,3", params["tracks"])
        assertEquals("[1,2,3]", params["trackIds"])
        assertEquals("true", params["imme"])
    }

    @Test
    fun `playlist add params reject empty positive song ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildNeteasePlaylistAddTracksParams(
                playlistId = 88L,
                songIds = listOf(0L, -1L)
            )
        }
    }
}
