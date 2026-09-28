package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class BiliSongResolverTest {

    @Test
    fun `Bili cid is recovered from explicit sub id before album decoration`() {
        val song = SongItem(
            id = 123L,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "123",
            subAudioId = "456"
        )

        assertEquals(456L, song.biliCidOrNull())
    }

    @Test
    fun `downloaded bilibili song becomes resolvable with its preserved avid and cid`() = runBlocking {
        val downloadedSong = DownloadedSong(
            id = 123L,
            name = "song",
            artist = "artist",
            album = "Bilibili|456",
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L
        )

        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video())
        val resolved = resolveBiliSong(downloadedSong.toPlaybackSongItem(), client)

        requireNotNull(resolved)
        assertEquals(123L, resolved.avid)
        assertEquals(456L, resolved.cid)
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `downloaded local bilibili song resolves through preserved source fields`() = runBlocking {
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

        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video())
        val resolved = resolveBiliSong(downloadedSong.toPlaybackSongItem(), client)

        requireNotNull(resolved)
        assertEquals(123L, resolved.avid)
        assertEquals(456L, resolved.cid)
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `plain local song cannot be treated as a bilibili video`() = runBlocking {
        val localSong = DownloadedSong(
            id = 123L,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L
        ).toPlaybackSongItem()

        val client = mock(BiliClient::class.java)
        assertNull(resolveBiliSong(localSong, client))
        verifyNoMoreInteractions(client)
    }

    private fun video(cid: Long = 456L) = BiliClient.VideoBasicInfo(
        aid = 123L, bvid = "BV1test", title = "song", coverUrl = "", desc = "", durationSec = 1,
        ownerMid = 0L, ownerName = "artist", ownerFace = "",
        stats = BiliClient.VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = listOf(BiliClient.VideoPage(cid, 1, "song", 1, 0, 0))
    )

}
