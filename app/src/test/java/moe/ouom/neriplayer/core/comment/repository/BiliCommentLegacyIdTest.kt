package moe.ouom.neriplayer.core.comment.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.resolveCommentSource
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BiliCommentLegacyIdTest {
    @Before
    fun clearCacheBefore() = CommentMemoryCache.clear()

    @After
    fun clearCacheAfter() = CommentMemoryCache.clear()

    @Test
    fun `packed id never requests comments from a colliding real video`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid(BVID)).thenReturn(video())
        `when`(client.getVideoComments(PACKED_ID, 1, 20)).thenReturn(comments(901))
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(comments(101))
        val repository = BiliCommentRepository { client }

        val result = repository.loadComments(source(song()), 1, 20)

        assertEquals(listOf("101"), result.comments.map { it.id })
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
    }

    @Test
    fun `legacy records without bvid resolve by their exact cid`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenReturn(
            video(aid = PACKED_ID, bvid = "BV1BK421y7Z1", cid = 1425003104L, part = 1)
        )
        `when`(client.getVideoBasicInfoByAvid(AID)).thenReturn(video())
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(comments(101))
        val repository = BiliCommentRepository { client }

        for (audioId in listOf(null, PACKED_ID.toString())) {
            val legacy = song().copy(album = "Bilibili|$CID", audioId = audioId)
            val result = repository.loadComments(source(legacy), 1, 20)
            assertEquals(listOf("101"), result.comments.map { it.id })
        }
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
    }

    @Test
    fun `legacy records with only packed id retain playback part resolution`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenReturn(
            video(aid = PACKED_ID, bvid = "BV1BK421y7Z1", cid = 1425003104L, part = 1)
        )
        `when`(client.getVideoBasicInfoByAvid(AID)).thenReturn(video())
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(comments(101))
        val legacy = song().copy(album = "Bilibili", subAudioId = null)
        val result = BiliCommentRepository { client }.loadComments(source(legacy), 1, 20)
        assertEquals(listOf("101"), result.comments.map { it.id })
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
    }

    @Test
    fun `canonical aid is not decoded as a packed id`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        val aid = 123001L
        `when`(client.getVideoBasicInfoByAvid(aid)).thenReturn(video(aid = aid))
        `when`(client.getVideoComments(aid, 1, 20)).thenReturn(comments(101))
        val canonical = song().copy(id = aid, audioId = aid.toString(), album = "Bilibili", subAudioId = null)
        val result = BiliCommentRepository { client }.loadComments(source(canonical), 1, 20)
        assertEquals(listOf("101"), result.comments.map { it.id })
        verify(client, never()).getVideoBasicInfoByAvid(12L)
    }

    @Test
    fun `unverified part does not request any guessed comments`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenReturn(video(aid = PACKED_ID, cid = 99L))
        `when`(client.getVideoBasicInfoByAvid(AID)).thenReturn(video(cid = 98L))
        val failure = runCatching {
            BiliCommentRepository { client }.loadComments(
                source(song().copy(album = "Bilibili|$CID")), 1, 20
            )
        }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        assertEquals(CommentError.API, (failure as CommentApiException).reason)
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
        verify(client, never()).getVideoComments(AID, 1, 20)
    }

    @Test
    fun `canonical id lookup failure cannot switch to a guessed video`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenAnswer { throw IOException("unavailable") }
        `when`(client.getVideoBasicInfoByAvid(AID)).thenReturn(video())
        val canonical = song().copy(album = "Bilibili", audioId = PACKED_ID.toString(), subAudioId = null)
        val failure = runCatching {
            BiliCommentRepository { client }.loadComments(source(canonical), 1, 20)
        }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        verify(client, never()).getVideoComments(AID, 1, 20)
    }

    @Test
    fun `bvid evidence must still match after playback resolver fallback`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid(BVID)).thenAnswer { throw IOException("unavailable") }
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenReturn(
            video(aid = PACKED_ID, bvid = "BV1different")
        )
        val failure = runCatching {
            BiliCommentRepository { client }.loadComments(source(song()), 1, 20)
        }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
    }

    @Test
    fun `resolved identity is reused across pages and refresh`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid(BVID)).thenReturn(video())
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(comments(101))
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(comments(102, page = 2))
        val repository = BiliCommentRepository { client }
        val source = source(song())
        repository.loadComments(source, 1, 20)
        repository.loadComments(source, 2, 20)
        repository.loadComments(source, 1, 20, forceRefresh = true)
        verify(client, times(1)).getVideoBasicInfoByBvid(BVID)
    }

    @Test
    fun `colliding logical ids do not share a comment cache entry`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.getVideoBasicInfoByBvid(BVID)).thenReturn(video())
        `when`(client.getVideoBasicInfoByAvid(PACKED_ID)).thenReturn(
            video(aid = PACKED_ID, bvid = "BV1BK421y7Z1", cid = 1425003104L, part = 1)
        )
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(comments(101))
        `when`(client.getVideoComments(PACKED_ID, 1, 20)).thenReturn(comments(901))
        val repository = BiliCommentRepository { client }
        val legacy = repository.loadComments(source(song()), 1, 20)
        val canonical = repository.loadComments(
            source(song().copy(album = "Bilibili", audioId = PACKED_ID.toString(), subAudioId = null)), 1, 20
        )
        assertEquals(listOf("101"), legacy.comments.map { it.id })
        assertEquals(listOf("901"), canonical.comments.map { it.id })
    }

    @Test
    fun `identity verification propagates cancellation`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        val cancelled = CancellationException("cancelled")
        `when`(client.getVideoBasicInfoByBvid(BVID)).thenThrow(cancelled)
        val result = runCatching {
            BiliCommentRepository { client }.loadComments(source(song()), 1, 20)
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(cancelled.message, result.exceptionOrNull()?.message)
        verify(client, never()).getVideoComments(PACKED_ID, 1, 20)
    }

    private fun source(song: SongItem) = requireNotNull(resolveCommentSource(song))

    private fun song() = SongItem(
        id = PACKED_ID, name = "song", artist = "artist", album = "Bilibili|$CID|$BVID",
        albumId = 0L, durationMs = 1_000L, coverUrl = null, subAudioId = CID.toString()
    )

    private fun video(
        aid: Long = AID,
        bvid: String = BVID,
        cid: Long = CID,
        part: Int = 3
    ) = BiliClient.VideoBasicInfo(
        aid = aid, bvid = bvid, title = "song", coverUrl = "", desc = "", durationSec = 1,
        ownerMid = 0L, ownerName = "artist", ownerFace = "",
        stats = BiliClient.VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
        pages = listOf(BiliClient.VideoPage(cid, part, "song", 1, 0, 0))
    )

    private fun comments(id: Int, page: Int = 1) = JSONObject(
        """{"code":0,"data":{"page":{"num":$page,"size":20,"count":40},"replies":[{"rpid":$id}]}}"""
    )

    private companion object {
        const val AID = 170001L
        const val PACKED_ID = 1700010003L
        const val CID = 279787L
        const val BVID = "BV17x411w7KC"
    }
}
