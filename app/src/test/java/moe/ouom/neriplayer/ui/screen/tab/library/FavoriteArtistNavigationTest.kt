package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteArtistNavigationTest {
    @Test
    fun `bilibili follows open the uploader using mid and avatar`() {
        val summary = favorite(FAVORITE_SOURCE_BILI_ARTIST).toFavoriteBiliUploader()!!
        assertEquals(42L, summary.mid)
        assertEquals("Creator", summary.name)
        assertEquals("avatar", summary.avatarUrl)
    }

    @Test
    fun `youtube follows use the saved browse id instead of the numeric hash`() {
        val summary = favorite(FAVORITE_SOURCE_YOUTUBE_ARTIST, "UCcreator").toFavoriteYouTubeCreator()!!
        assertEquals("UCcreator", summary.browseId)
        assertEquals("Creator", summary.title)
        assertEquals("description", summary.subtitle)
    }

    @Test
    fun `records without a valid creator identity cannot navigate`() {
        assertNull(favorite(FAVORITE_SOURCE_YOUTUBE_ARTIST).toFavoriteYouTubeCreator())
        assertNull(favorite("bili").toFavoriteBiliUploader())
    }

    private fun favorite(source: String, browseId: String? = null) = FavoritePlaylist(
        id = 42, name = "Creator", coverUrl = "avatar", trackCount = 0,
        source = source, browseId = browseId, subtitle = "description", songs = emptyList()
    )
}
