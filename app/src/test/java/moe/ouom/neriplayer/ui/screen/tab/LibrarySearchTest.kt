package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySearchTest {
    @Test
    fun biliPlaylistSearch_matchesTitlesKindsAndIds() {
        val playlists = listOf(
            BiliPlaylist(
                mediaId = 101L,
                fid = 11L,
                mid = 21L,
                title = "JavaEE通关路线",
                count = 7,
                coverUrl = "",
                kind = BiliPlaylistKind.CREATED_FAVORITE,
                subtitle = "后端"
            ),
            BiliPlaylist(
                mediaId = 202L,
                fid = 12L,
                mid = 22L,
                title = "Spring 路线",
                count = 3,
                coverUrl = "",
                kind = BiliPlaylistKind.COLLECTED_FAVORITE,
                subtitle = "收藏夹"
            ),
            BiliPlaylist(
                mediaId = 303L,
                fid = 13L,
                mid = 23L,
                title = "2021哔哩哔哩拜年纪",
                count = 1,
                coverUrl = "",
                kind = BiliPlaylistKind.COLLECTION,
                subtitle = "合集"
            )
        )

        assertEquals(
            listOf(playlists[0]),
            filterBiliPlaylists(playlists, "Java", "创建的收藏夹", "订阅收藏夹", "合集")
        )
        assertEquals(
            listOf(playlists[1]),
            filterBiliPlaylists(playlists, "订阅", "创建的收藏夹", "订阅收藏夹", "合集")
        )
        assertEquals(
            listOf(playlists[2]),
            filterBiliPlaylists(playlists, "合集", "创建的收藏夹", "订阅收藏夹", "合集")
        )
        assertEquals(
            listOf(playlists[0]),
            filterBiliPlaylists(playlists, "101", "创建的收藏夹", "订阅收藏夹", "合集")
        )
    }

    @Test
    fun neteaseSearch_filtersPlaylistsAndAlbums() {
        val playlists = listOf(
            PlaylistSummary(1L, "日推歌单", "", 1_200, 30),
            PlaylistSummary(2L, "学习歌单", "", 5, 2)
        )
        val albums = listOf(
            AlbumSummary(11L, "Java 专辑", "", 12),
            AlbumSummary(12L, "Kotlin 专辑", "", 8)
        )

        assertEquals(
            listOf(playlists[1]),
            filterNeteasePlaylists(playlists, "学习")
        )
        assertEquals(
            listOf(playlists[0]),
            filterNeteasePlaylists(playlists, "1")
        )
        assertEquals(
            listOf(albums[1]),
            filterNeteaseAlbums(albums, "Kotlin")
        )
        assertEquals(
            listOf(albums[0]),
            filterNeteaseAlbums(albums, "11")
        )
    }
}
