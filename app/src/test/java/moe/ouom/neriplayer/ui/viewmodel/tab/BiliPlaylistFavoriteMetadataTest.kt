package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliPlaylistFavoriteMetadataTest {
    @Test
    fun `favorite collection restores archive kind uploader and owner id`() {
        val original = BiliPlaylist(
            mediaId = 4002195L,
            fid = 0L,
            mid = 123456L,
            title = "合集",
            count = 12,
            coverUrl = "https://example.test/collection.jpg",
            kind = BiliPlaylistKind.COLLECTION,
            subtitle = "UP 主"
        )

        assertEquals(original, favoriteFrom(original).toBiliPlaylist())
    }

    @Test
    fun `favorite series restores archive kind uploader and owner id`() {
        val original = BiliPlaylist(
            mediaId = 56789L,
            fid = 0L,
            mid = 123456L,
            title = "系列",
            count = 6,
            coverUrl = "https://example.test/series.jpg",
            kind = BiliPlaylistKind.SERIES,
            subtitle = "UP 主"
        )

        assertEquals(original, favoriteFrom(original).toBiliPlaylist())
    }

    @Test
    fun `legacy favorite falls back to a favorite folder`() {
        val favorite = FavoritePlaylist(
            id = 99L,
            name = "旧收藏夹",
            coverUrl = "cover",
            trackCount = 2,
            source = "bili",
            subtitle = "收藏者",
            songs = emptyList()
        )

        assertEquals(
            BiliPlaylist(
                mediaId = 99L,
                fid = 0L,
                mid = 0L,
                title = "旧收藏夹",
                count = 2,
                coverUrl = "cover",
                kind = BiliPlaylistKind.CREATED_FAVORITE,
                subtitle = "收藏者"
            ),
            favorite.toBiliPlaylist()
        )
    }

    private fun favoriteFrom(playlist: BiliPlaylist): FavoritePlaylist {
        return FavoritePlaylist(
            id = playlist.mediaId,
            name = playlist.title,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.count,
            source = "bili",
            browseId = playlist.toFavoriteBrowseId(),
            subtitle = playlist.subtitle,
            songs = emptyList()
        )
    }
}
