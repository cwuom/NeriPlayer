package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreLinkQueryParameterEdgeTest {

    @Test
    fun `empty query parts and blank parameter names are ignored`() {
        assertEquals(
            ExploreLinkTarget.YouTubeVideo(videoId = "abcdefghijk", playlistId = "PL123"),
            recognizeExploreLink("https://www.youtube.com/watch?&=orphan&&v=abcdefghijk&list=PL123")
        )
    }

    @Test
    fun `a parameter without a value does not count as present`() {
        assertEquals(
            ExploreLinkTarget.YouTubePlaylist("PL789"),
            recognizeExploreLink("https://www.youtube.com/watch?v&list=PL789")
        )
    }

    @Test
    fun `percent encoded parameter names are decoded`() {
        assertEquals(
            ExploreLinkTarget.NeteaseSong(1824020871L),
            recognizeExploreLink("https://music.163.com/song?%69%64=1824020871")
        )
    }

    @Test
    fun `an empty query or fragment query carries no id`() {
        assertNull(recognizeExploreLink("https://music.163.com/song?"))
        assertNull(recognizeExploreLink("https://music.163.com/#/song?"))
    }
}
