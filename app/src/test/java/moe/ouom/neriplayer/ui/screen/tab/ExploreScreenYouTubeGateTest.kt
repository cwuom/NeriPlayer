package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExploreScreenYouTubeGateTest {

    @Test
    fun `search sources exclude YouTube when disabled`() {
        val sources = exploreSearchSourceDisplayOrder(
            isInternational = true,
            youtubeEnabled = false
        )

        assertFalse(sources.contains(SearchSource.YOUTUBE_MUSIC))
        assertEquals(
            listOf(SearchSource.NETEASE, SearchSource.BILIBILI),
            sources
        )
    }

    @Test
    fun `international search keeps YouTube first when enabled`() {
        val sources = exploreSearchSourceDisplayOrder(
            isInternational = true,
            youtubeEnabled = true
        )

        assertEquals(SearchSource.YOUTUBE_MUSIC, sources.first())
    }
}
