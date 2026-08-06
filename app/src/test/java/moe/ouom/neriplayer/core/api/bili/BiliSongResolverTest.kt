package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliSongResolverTest {

    @Test
    fun `downloaded bilibili song becomes resolvable with its preserved avid and cid`() {
        val downloadedSong = DownloadedSong(
            id = 123L,
            name = "song",
            artist = "artist",
            album = "Bilibili|456",
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L
        )

        val resolutionSong = downloadedSong
            .toPlaybackSongItem()
            .toBiliResolutionSongOrNull()

        requireNotNull(resolutionSong)
        assertEquals(123L, resolutionSong.id)
        assertEquals("${PlayerManager.BILI_SOURCE_TAG}|456", resolutionSong.album)
    }

    @Test
    fun `downloaded local bilibili song resolves through preserved source fields`() {
        val downloadedSong = DownloadedSong(
            id = 66L,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L,
            sourceChannelId = "bilibili",
            sourceAudioId = "123",
            sourceSubAudioId = "456"
        )

        val resolutionSong = downloadedSong
            .toPlaybackSongItem()
            .toBiliResolutionSongOrNull()

        requireNotNull(resolutionSong)
        assertEquals(123L, resolutionSong.id)
        assertEquals("${PlayerManager.BILI_SOURCE_TAG}|456", resolutionSong.album)
    }

    @Test
    fun `plain local song cannot be treated as a bilibili video`() {
        val localSong = DownloadedSong(
            id = 123L,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L
        ).toPlaybackSongItem()

        assertNull(localSong.toBiliResolutionSongOrNull())
    }
}
