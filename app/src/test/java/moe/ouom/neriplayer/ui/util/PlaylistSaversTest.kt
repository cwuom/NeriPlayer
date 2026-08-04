package moe.ouom.neriplayer.ui.util

import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaylistSaversTest {
    @Test
    fun biliPlaylistRoundTrip_keepsKindAndSubtitle() {
        val original = BiliPlaylist(
            mediaId = 9988L,
            fid = 7766L,
            mid = 5544L,
            title = "收藏的合集",
            count = 42,
            coverUrl = "https://example.test/cover.jpg",
            kind = BiliPlaylistKind.COLLECTION,
            subtitle = "哔哩哔哩拜年纪"
        )

        val restored = restoreBiliPlaylist(original.toSaveMap())
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun biliPlaylistRoundTrip_keepsSeriesKind() {
        val original = BiliPlaylist(
            mediaId = 1234L,
            fid = 1234L,
            mid = 5678L,
            title = "Android series",
            count = 8,
            coverUrl = "https://example.test/series.jpg",
            kind = BiliPlaylistKind.SERIES,
            subtitle = "Uploader"
        )

        assertEquals(original, restoreBiliPlaylist(original.toSaveMap()))
    }

    @Test
    fun youTubeMusicPlaylistRoundTrip_keepsCreatorName() {
        val original = YouTubeMusicPlaylist(
            browseId = "MPREdemoAlbum",
            playlistId = "MPREdemoAlbum",
            title = "Demo Album",
            subtitle = "Album",
            coverUrl = "https://example.test/album.jpg",
            trackCount = 12,
            creatorName = "Demo Creator"
        )

        assertEquals(original, restoreYouTubeMusicPlaylist(original.toSaveMap()))
    }
}
