package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseSearchArtistFilteringTest {

    @Test
    fun `artist results without an artists array are empty but keep the reported total`() {
        val parsed = parseNeteaseSearchResults(
            """{"code":200,"result":{"artistCount":3}}""",
            NeteaseExploreSearchType.ARTIST
        )

        assertEquals(emptyList<ExploreSearchResult>(), parsed.items)
        assertEquals(3, parsed.totalCount)
    }

    @Test
    fun `artists without a positive id or a visible name are dropped`() {
        val parsed = parseArtists(
            """
                {"id": 0, "name": "Zero"},
                {"id": -4, "name": "Negative"},
                {"id": 7, "name": "   "},
                {"id": 8, "name": "Kept", "picUrl": "https://example.invalid/kept.jpg"}
            """
        )

        assertEquals(listOf(8L), parsed.map { it.artist.id })
        assertEquals("Kept", parsed.single().artist.name)
    }

    @Test
    fun `blank artist pictures fall back to the square avatar and then to no picture`() {
        val parsed = parseArtists(
            """
                {
                    "id": 1,
                    "name": "Fallback",
                    "picUrl": "  ",
                    "img1v1Url": "http://example.invalid/square.jpg",
                    "musicSize": 3,
                    "albumSize": 1
                },
                {"id": 2, "name": "No picture"}
            """
        )

        val fallback = parsed[0]
        assertEquals("https://example.invalid/square.jpg", fallback.picUrl)
        assertEquals(3, fallback.musicSize)
        assertEquals(1, fallback.albumSize)
        val withoutPicture = parsed[1]
        assertNull(withoutPicture.picUrl)
        assertEquals(0, withoutPicture.musicSize)
        assertEquals(0, withoutPicture.albumSize)
    }

    private fun parseArtists(artistsJson: String): List<NeteaseSearchArtistResult> {
        val parsed = parseNeteaseSearchResults(
            """{"code":200,"result":{"artists":[$artistsJson]}}""",
            NeteaseExploreSearchType.ARTIST
        )
        assertNull(parsed.totalCount)
        return parsed.items.map { (it as ExploreSearchResult.Artist).result }
    }
}
