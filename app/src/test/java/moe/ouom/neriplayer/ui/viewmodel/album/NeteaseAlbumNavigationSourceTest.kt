package moe.ouom.neriplayer.ui.viewmodel.album

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAlbumNavigationSourceTest {

    @Test
    fun `netease source is recognised by channel album tag or media host`() {
        assertTrue(isNeteaseAlbumNavigationSource(song(channelId = "NetEase")))
        assertTrue(isNeteaseAlbumNavigationSource(song(album = "${PlayerManager.NETEASE_SOURCE_TAG}Album")))
        assertTrue(isNeteaseAlbumNavigationSource(song(mediaUri = "https://Music.163.com/song?id=1")))
        assertFalse(isNeteaseAlbumNavigationSource(song(mediaUri = "https://www.youtube.com/watch?v=x")))
        assertFalse(isNeteaseAlbumNavigationSource(song(channelId = "youtubeMusic")))
    }

    @Test
    fun `album resolution returns the known album without a song detail lookup`() = runTest {
        val song = song(
            channelId = "netease",
            album = "${PlayerManager.NETEASE_SOURCE_TAG} Known Album ",
            albumId = 5L
        )

        assertEquals(
            AlbumSummary(id = 5L, name = "Known Album", picUrl = "", size = 0),
            resolveNeteaseAlbum(song)
        )
    }

    @Test
    fun `album resolution skips songs outside netease`() = runTest {
        assertNull(
            resolveNeteaseAlbum(
                song(channelId = "youtubeMusic", albumId = 9L, matchedSongId = "123")
            )
        )
    }

    private fun song(
        channelId: String? = null,
        album: String = "Album",
        albumId: Long = 0L,
        mediaUri: String? = null,
        matchedSongId: String? = null
    ) = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = album,
        albumId = albumId,
        durationMs = 0L,
        coverUrl = null,
        mediaUri = mediaUri,
        matchedSongId = matchedSongId,
        channelId = channelId
    )
}
