package moe.ouom.neriplayer.data.platform.bili.playback.resolver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.bilibili.client.BiliClient
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class BiliSongResolverTest {

    @Test
    fun `canonical source avid takes precedence over local identity hash`() {
        val song = biliSong().copy(id = 260898678746518642L, audioId = "123")
        assertEquals(123L, song.toBiliResolutionSongOrNull()?.id)
        assertEquals("Bilibili|456", song.toBiliResolutionSongOrNull()?.album)
        assertEquals(456L, song.toBiliResolutionSongOrNull()?.biliCidOrNull())
    }

    @Test
    fun `missing requested part never falls back to first part or a different avid`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video(cid = 999L))
        assertNull(resolveBiliSong(biliSong(), client))
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `canonical source without cid resolves once without ambiguous legacy lookup`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123001L)).thenReturn(video().copy(aid = 123001L))
        val song = biliSong().copy(id = 123001L, audioId = "123001", subAudioId = null, album = "Bilibili")
        assertEquals(456L, resolveBiliSong(song, client)?.cid)
        verify(client).getVideoBasicInfoByAvid(123001L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `legacy encoded identity still resolves its requested part`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video())
        val song = biliSong().copy(id = 1230002L, audioId = null)
        assertEquals(456L, resolveBiliSong(song, client)?.cid)
        verify(client).getVideoBasicInfoByAvid(1230002L)
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `legacy downloaded source fields still allow exact cid recovery`() = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(123L)).thenReturn(video())
        val song = biliSong().copy(id = 1230002L, audioId = "1230002")
        assertEquals(456L, resolveBiliSong(song, client)?.cid)
        verify(client).getVideoBasicInfoByAvid(1230002L)
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `cancellation immediately releases resolution instead of trying another identity`() = runBlocking {
        val client = mock(BiliClient::class.java)
        val cancelled = CancellationException("network generation changed")
        `when`(client.getVideoBasicInfoByAvid(123L)).thenThrow(cancelled)
        val result = runCatching { resolveBiliSong(biliSong(), client) }
        assertSame(cancelled, result.exceptionOrNull())
        verify(client).getVideoBasicInfoByAvid(123L)
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `Bili part album retains both the cid and BVID`() {
        val album = buildBiliSongAlbum(cid = 456L, bvid = "BV1parttest")
        val song = SongItem(
            id = 123L,
            name = "song",
            artist = "artist",
            album = album,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "123"
        )

        assertEquals("${SongSourceTags.BILIBILI}|456|BV1parttest", album)
        assertEquals(456L, song.biliCidOrNull())
        assertEquals("BV1parttest", song.biliBvidOrNull())
    }

    @Test
    fun `BVID-only Bili albums preserve their video address`() {
        val album = buildBiliSongAlbum(bvid = "BV1videotest")
        val song = SongItem(
            id = 123L,
            name = "song",
            artist = "artist",
            album = album,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "123"
        )

        assertEquals("${SongSourceTags.BILIBILI}||BV1videotest", album)
        assertNull(song.biliCidOrNull())
        assertEquals("BV1videotest", song.biliBvidOrNull())
        assertTrue(song.toBiliResolutionSongOrNull() != null)
    }

    @Test
    fun `plain multi-part Bili videos keep the resolved first page metadata`() {
        val firstPage = VideoPage(
            cid = 33_638_122_342L,
            page = 1,
            part = "RapTure",
            durationSec = 725,
            width = 0,
            height = 0
        )
        val secondPage = VideoPage(
            cid = 33_717_159_668L,
            page = 2,
            part = "IMG_5153",
            durationSec = 57,
            width = 0,
            height = 0
        )

        assertEquals(
            firstPage,
            selectBiliPlaybackPage(
                pages = listOf(firstPage, secondPage),
                songName = "Full video title"
            )
        )
        assertEquals(
            secondPage,
            selectBiliPlaybackPage(
                pages = listOf(firstPage, secondPage),
                songName = "Full video title",
                preferredCid = secondPage.cid
            )
        )
    }

    private fun biliSong() = SongItem(id = 123L, name = "song", artist = "artist",
        album = "Bilibili|456", albumId = 0L, durationMs = 1000L, coverUrl = null,
        channelId = "bilibili", audioId = "123", subAudioId = "456")

    private fun video(cid: Long = 456L) = VideoBasicInfo(
        aid = 123L, bvid = "BV1test", title = "song", coverUrl = "", desc = "", durationSec = 1,
        ownerMid = 0L, ownerName = "artist", ownerFace = "",
        stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = listOf(VideoPage(cid, 1, "song", 1, 0, 0))
    )

}
