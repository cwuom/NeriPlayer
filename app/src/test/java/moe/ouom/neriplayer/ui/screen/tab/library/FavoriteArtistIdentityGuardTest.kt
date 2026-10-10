package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteArtistIdentityGuardTest {

    @Test
    fun `bilibili uploader needs a positive mid and a visible name`() {
        assertNull(favorite(FAVORITE_SOURCE_BILI_ARTIST, id = 0L).toFavoriteBiliUploader())
        assertNull(favorite(FAVORITE_SOURCE_BILI_ARTIST, name = "  ").toFavoriteBiliUploader())
    }

    @Test
    fun `bilibili uploader without avatar uses an empty avatar url`() {
        assertEquals(
            BiliUploaderSummary(mid = 42L, name = "Creator", avatarUrl = ""),
            favorite(FAVORITE_SOURCE_BILI_ARTIST, coverUrl = null).toFavoriteBiliUploader()
        )
    }

    @Test
    fun `youtube creator needs its own source a visible name and a browse id`() {
        assertNull(favorite(FAVORITE_SOURCE_BILI_ARTIST, browseId = "UCcreator").toFavoriteYouTubeCreator())
        assertNull(
            favorite(FAVORITE_SOURCE_YOUTUBE_ARTIST, name = " ", browseId = "UCcreator")
                .toFavoriteYouTubeCreator()
        )
        assertNull(favorite(FAVORITE_SOURCE_YOUTUBE_ARTIST, browseId = "   ").toFavoriteYouTubeCreator())
    }

    @Test
    fun `youtube creator without subtitle or cover uses empty strings`() {
        assertEquals(
            YouTubeMusicCreatorSummary(
                browseId = "UCcreator",
                title = "Creator",
                subtitle = "",
                coverUrl = ""
            ),
            favorite(
                FAVORITE_SOURCE_YOUTUBE_ARTIST,
                browseId = "UCcreator",
                coverUrl = null,
                subtitle = null
            ).toFavoriteYouTubeCreator()
        )
    }

    private fun favorite(
        source: String,
        id: Long = 42L,
        name: String = "Creator",
        browseId: String? = null,
        coverUrl: String? = "avatar",
        subtitle: String? = "description"
    ) = FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = coverUrl,
        trackCount = 0,
        source = source,
        browseId = browseId,
        subtitle = subtitle,
        songs = emptyList()
    )
}
