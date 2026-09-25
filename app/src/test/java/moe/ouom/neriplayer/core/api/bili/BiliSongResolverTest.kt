package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class BiliSongResolverTest {

    @Test
    fun `canonical source avid takes precedence over local identity hash`() {
        val song = biliSong().copy(id = 260898678746518642L, audioId = "123")
        assertEquals(123L, song.toBiliResolutionSongOrNull()?.id)
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

    private fun biliSong() = SongItem(id = 123L, name = "song", artist = "artist",
        album = "Bilibili|456", albumId = 0L, durationMs = 1000L, coverUrl = null,
        channelId = "bilibili", audioId = "123", subAudioId = "456")

    private fun video(cid: Long = 456L) = BiliClient.VideoBasicInfo(
        aid = 123L, bvid = "BV1test", title = "song", coverUrl = "", desc = "", durationSec = 1,
        ownerMid = 0L, ownerName = "artist", ownerFace = "",
        stats = BiliClient.VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = listOf(BiliClient.VideoPage(cid, 1, "song", 1, 0, 0))
    )

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

        assertEquals("${PlayerManager.BILI_SOURCE_TAG}|456|BV1parttest", album)
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

        assertEquals("${PlayerManager.BILI_SOURCE_TAG}||BV1videotest", album)
        assertNull(song.biliCidOrNull())
        assertEquals("BV1videotest", song.biliBvidOrNull())
        assertTrue(song.toBiliResolutionSongOrNull() != null)
    }

    @Test
    fun `plain multi-part Bili videos keep the resolved first page metadata`() {
        val firstPage = BiliClient.VideoPage(
            cid = 33_638_122_342L,
            page = 1,
            part = "RapTure",
            durationSec = 725,
            width = 0,
            height = 0
        )
        val secondPage = BiliClient.VideoPage(
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
}
