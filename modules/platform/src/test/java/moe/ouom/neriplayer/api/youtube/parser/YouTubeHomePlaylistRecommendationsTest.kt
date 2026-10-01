package moe.ouom.neriplayer.api.youtube.parser

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeHomePlaylistRecommendationsTest {
    @Test
    fun `empty shelves produce no playlist recommendations`() {
        assertTrue(YouTubeMusicParser.parseHomePlaylistRecommendations(emptyList()).isEmpty())
        assertTrue(
            YouTubeMusicParser.parseHomePlaylistRecommendations(
                listOf(YouTubeMusicHomeShelf(title = "empty", items = emptyList()))
            ).isEmpty()
        )
    }

    @Test
    fun `duplicate cards across shelves preserve the first card and shelf fallback`() {
        val first = playlist(" VLPL-first ", "first")
        val shelves = listOf(
            YouTubeMusicHomeShelf(title = "first shelf", items = listOf(first)),
            YouTubeMusicHomeShelf(
                title = "second shelf",
                items = listOf(first.copy(browseId = "VLPL-first", title = "duplicate"), playlist("VLPL-second", "second"))
            )
        )

        val result = YouTubeMusicParser.parseHomePlaylistRecommendations(shelves)

        assertEquals(listOf("first", "second"), result.map { it.title })
        assertEquals(listOf("VLPL-first", "VLPL-second"), result.map { it.browseId })
        assertEquals(listOf("first shelf", "second shelf"), result.map { it.subtitle })
    }

    @Test
    fun `playlist limits stop traversal and nonpositive limits retain one recommendation`() {
        val shelves = listOf(
            YouTubeMusicHomeShelf(title = "first shelf", items = listOf(playlist("VLPL-first", "first"))),
            YouTubeMusicHomeShelf(title = "second shelf", items = listOf(playlist("VLPL-second", "second")))
        )

        for (limit in listOf(-1, 0, 1)) {
            val result = YouTubeMusicParser.parseHomePlaylistRecommendations(shelves, limit)
            assertEquals(listOf("first"), result.map { it.title })
        }
        assertEquals(2, YouTubeMusicParser.parseHomePlaylistRecommendations(shelves, limit = 2).size)
    }

    private fun playlist(browseId: String, title: String) = YouTubeMusicHomeItem(
        title = title,
        subtitle = "",
        coverUrl = "",
        browseId = browseId,
        pageType = "MUSIC_PAGE_TYPE_PLAYLIST"
    )
}
