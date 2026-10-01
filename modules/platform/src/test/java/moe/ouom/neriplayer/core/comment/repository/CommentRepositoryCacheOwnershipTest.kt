package moe.ouom.neriplayer.core.comment.repository

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.bilibili.client.BiliClient
import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.commentLengthLimit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats

@RunWith(Parameterized::class)
class CommentRepositoryCacheOwnershipTest(private val platform: CommentPlatform) {
    private val netease = mock(NeteaseClient::class.java)
    private val bili = mock(BiliClient::class.java)
    private val source = CommentSource(platform, 123L, hasExplicitResourceId = true)
    private val repository = newRepository()

    @Before
    fun setUp(): Unit = runBlocking {
        `when`(bili.getVideoBasicInfoByAvid(123L)).thenReturn(
            VideoBasicInfo(
                aid = 123L, bvid = "BV1test", title = "song", coverUrl = "", desc = "",
                durationSec = 1, ownerMid = 0L, ownerName = "", ownerFace = "",
                stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
                pages = listOf(VideoPage(456L, 1, "song", 1, 0, 0))
            )
        )
        setSession("session-a")
    }

    @Test
    fun `separate cache owners do not share pages`(): Unit = runBlocking {
        stubPage(1, 1)
        repository.loadComments(source, 1, 20)
        val other = newRepository()
        stubPage(1, 2)
        assertEquals("2", other.loadComments(source, 1, 20).comments.single().id)
        assertEquals("1", repository.cachedComments(source, 20, CommentSort.HOT)?.comments?.single()?.id)
    }

    @Test
    fun `first page refresh invalidates old pages while later refresh retains the first`(): Unit = runBlocking {
        stubPage(1, 1)
        stubPage(2, 2)
        repository.loadComments(source, 1, 20)
        repository.loadComments(source, 2, 20)
        stubPage(2, 3)
        assertEquals("2", repository.loadComments(source, 2, 20).comments.single().id)
        assertEquals("3", repository.loadComments(source, 2, 20, forceRefresh = true).comments.single().id)
        assertEquals("1", repository.cachedComments(source, 20, CommentSort.HOT)?.comments?.single()?.id)
        stubPage(1, 4)
        repository.loadComments(source, 1, 20, forceRefresh = true)
        stubPage(2, 5)
        assertEquals("5", repository.loadComments(source, 2, 20).comments.single().id)
        assertEquals("4", repository.cachedComments(source, 20, CommentSort.HOT)?.comments?.single()?.id)
    }

    @Test
    fun `session changed during request does not cache the response for either account`(): Unit = runBlocking {
        stubPage(1, 1)
        when (platform) {
            CommentPlatform.NETEASE -> `when`(netease.commentCacheSessionKey()).thenReturn("old", "new")
            CommentPlatform.BILIBILI -> `when`(bili.commentCacheSessionKey()).thenReturn("old", "new")
        }
        assertEquals("1", repository.loadComments(source, 1, 20, forceRefresh = true).comments.single().id)
        for (session in listOf("old", "new")) {
            setSession(session)
            assertNull(repository.cachedComments(source, 20, CommentSort.HOT))
        }
    }

    @Test
    fun `failed like invalidates every session for the resource`(): Unit = runBlocking {
        stubPage(1, 1)
        for (session in listOf("old", "new")) {
            setSession(session)
            repository.loadComments(source, 1, 20)
        }
        when (platform) {
            CommentPlatform.NETEASE -> `when`(netease.setSongCommentLiked(123L, "1", true))
                .thenAnswer { throw IOException("offline") }
            CommentPlatform.BILIBILI -> `when`(bili.setVideoCommentLiked(123L, "1", true))
                .thenAnswer { throw IOException("offline") }
        }
        assertTrue(runCatching { repository.setLiked(source, "1", true) }.exceptionOrNull() is IOException)
        for (session in listOf("old", "new")) {
            setSession(session)
            assertNull(repository.cachedComments(source, 20, CommentSort.HOT))
        }
    }

    @Test
    fun `failed send invalidates the resource cache`(): Unit = runBlocking {
        stubPage(1, 1)
        repository.loadComments(source, 1, 20)
        when (platform) {
            CommentPlatform.NETEASE -> `when`(netease.sendSongComment(123L, "text", null))
                .thenAnswer { throw IOException("offline") }
            CommentPlatform.BILIBILI -> `when`(bili.sendVideoComment(123L, "text", null, null))
                .thenAnswer { throw IOException("offline") }
        }
        assertTrue(runCatching { repository.sendComment(source, "text") }.exceptionOrNull() is IOException)
        assertNull(repository.cachedComments(source, 20, CommentSort.HOT))
    }

    @Test
    fun `wrong platform is rejected before client access`(): Unit = runBlocking {
        val wrong = source.copy(platform = CommentPlatform.entries.single { it != platform })
        assertTrue(runCatching { repository.loadComments(wrong, 1, 20) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.cachedComments(wrong, 20, CommentSort.HOT) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.setLiked(wrong, "1", true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.sendComment(wrong, "text") }.exceptionOrNull() is IllegalArgumentException)
        verifyNoInteractions(netease, bili)
    }

    @Test
    fun `blank and oversized text is rejected before client access`(): Unit = runBlocking {
        for (content in listOf("", "  ", "x".repeat(platform.commentLengthLimit() + 1))) {
            assertTrue(runCatching { repository.sendComment(source, content) }.exceptionOrNull() is IllegalArgumentException)
        }
        verifyNoInteractions(netease, bili)
    }

    @Test
    fun `recommended sorting is accepted only by netease`(): Unit = runBlocking {
        when (platform) {
            CommentPlatform.NETEASE -> {
                `when`(netease.getSongCommentsCancellable(123L, 1, 20, 99, null)).thenReturn(neteasePage(1))
                assertEquals("1", repository.loadComments(source, 1, 20, sort = CommentSort.RECOMMENDED).comments.single().id)
            }
            CommentPlatform.BILIBILI -> {
                assertTrue(runCatching {
                    repository.loadComments(source, 1, 20, sort = CommentSort.RECOMMENDED)
                }.exceptionOrNull() is IllegalArgumentException)
                verifyNoInteractions(netease, bili)
            }
        }
    }

    private fun newRepository(): CommentRepository = when (platform) {
        CommentPlatform.NETEASE -> NeteaseCommentRepository(CommentMemoryCache()) { netease }
        CommentPlatform.BILIBILI -> BiliCommentRepository(CommentMemoryCache()) { bili }
    }

    private suspend fun setSession(session: String) {
        when (platform) {
            CommentPlatform.NETEASE -> `when`(netease.commentCacheSessionKey()).thenReturn(session)
            CommentPlatform.BILIBILI -> `when`(bili.commentCacheSessionKey()).thenReturn(session)
        }
    }

    private suspend fun stubPage(page: Int, id: Int) {
        when (platform) {
            CommentPlatform.NETEASE -> `when`(netease.getSongCommentsCancellable(123L, page, 20, 2, null))
                .thenReturn(neteasePage(id))
            CommentPlatform.BILIBILI -> `when`(bili.getVideoComments(123L, page, 20, 1))
                .thenReturn(JSONObject("""{"code":0,"data":{"page":{"num":$page,"size":20,"count":1},"replies":[{"rpid":$id}]}}"""))
        }
    }

    private fun neteasePage(id: Int) = """{"code":200,"more":false,"comments":[{"commentId":$id}]}"""

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun platforms() = CommentPlatform.entries.map { arrayOf(it) }
    }
}
