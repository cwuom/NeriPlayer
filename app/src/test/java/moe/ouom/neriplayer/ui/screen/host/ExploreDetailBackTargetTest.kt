package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreDetailBackTargetTest {

    private val artist = NeteaseArtistSummary(id = 6452L, name = "Artist")
    private val creator = YouTubeMusicCreatorSummary(
        browseId = "UCcreator",
        title = "Creator",
        subtitle = "Artist",
        coverUrl = ""
    )
    private val parentCreator = creator.copy(browseId = "UCparent", title = "Parent")
    private val playlist = YouTubeMusicPlaylist(
        browseId = "VLPLlist",
        playlistId = "PLlist",
        title = "List",
        subtitle = "",
        coverUrl = ""
    )

    @Test
    fun `root level selections have no detail back target`() {
        assertNull(resolveExploreSelectedDetailBackTarget(null))
        assertNull(
            resolveExploreSelectedDetailBackTarget(
                ExploreSelectedItem.Netease(PlaylistSummary(1L, "Daily", "", 0L, 30))
            )
        )
        assertNull(
            resolveExploreSelectedDetailBackTarget(
                ExploreSelectedItem.Bilibili(BiliPlaylist(1L, 2L, 3L, "Fav", 4, ""))
            )
        )
        assertNull(resolveExploreSelectedDetailBackTarget(ExploreSelectedItem.NeteaseArtist(artist)))
    }

    @Test
    fun `artist album returns to its artist`() {
        assertEquals(
            ExploreSelectedItem.NeteaseArtist(artist),
            resolveExploreSelectedDetailBackTarget(
                ExploreSelectedItem.NeteaseArtistAlbum(artist, AlbumSummary(9L, "Album", "", 10))
            )
        )
    }

    @Test
    fun `creator item list returns to the creator page`() {
        assertEquals(
            ExploreSelectedItem.YouTubeMusicCreator(creator),
            resolveExploreSelectedDetailBackTarget(
                ExploreSelectedItem.YouTubeMusicCreatorItems(
                    creator = creator,
                    section = YouTubeMusicCreatorSection(title = "Singles", items = emptyList())
                )
            )
        )
    }

    @Test
    fun `playlist opened from search has no detail back target`() {
        assertNull(resolveExploreSelectedDetailBackTarget(ExploreSelectedItem.YouTubeMusic(playlist)))
    }

    @Test
    fun `nested creator returns to its parent creator`() {
        assertEquals(
            ExploreSelectedItem.YouTubeMusicCreator(parentCreator),
            resolveExploreSelectedDetailBackTarget(
                ExploreSelectedItem.YouTubeMusicCreator(creator, parentCreator = parentCreator)
            )
        )
        assertNull(resolveExploreSelectedDetailBackTarget(ExploreSelectedItem.YouTubeMusicCreator(creator)))
    }
}
