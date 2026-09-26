package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class CommentRepositoryPagingTest {
    @Test
    fun `netease floor passes time cursor and rejects a stalled response`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        val repository = NeteaseCommentRepository { client }
        val source = CommentSource(CommentPlatform.NETEASE, AID)
        `when`(client.getSongCommentReplies(AID, "42", 20, null)).thenReturn(
            """{"code":200,"data":{"comments":[{"commentId":43}],"hasMore":true,"time":100}}"""
        )
        `when`(client.getSongCommentReplies(AID, "42", 20, "100")).thenReturn(
            """{"code":200,"data":{"comments":[{"commentId":43}],"hasMore":true,"time":100}}"""
        )
        val first = repository.loadReplies(source, "42", 1, 20)
        assertEquals("100", first.nextCursor)
        val failure = runCatching { repository.loadReplies(source, "42", 2, 20, first.nextCursor) }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        verify(client).getSongCommentReplies(AID, "42", 20, "100")
    }

    @Test
    fun `netease send invalidates anonymous cache and preserves platform rejection`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        val repository = NeteaseCommentRepository { client }
        val source = CommentSource(CommentPlatform.NETEASE, AID)
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(1..1, false))
        repository.loadComments(source, 1, 20)
        `when`(client.sendSongComment(AID, "text", "42")).thenReturn("""{"code":200}""")
        repository.sendComment(source, "text", CommentReplyTarget("42", "40", "user"))
        assertNull(CommentMemoryCache.get("NETEASE", AID, 1))
        `when`(client.sendSongComment(AID, "text", null)).thenReturn("""{"code":250}""")
        val failure = runCatching { repository.sendComment(source, "text") }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        assertEquals(250, (failure as CommentApiException).code)
    }

    @Test
    fun `bilibili replies and writes use verified video identity and propagate closed comments`(): Unit = runBlocking {
        val client = biliClient()
        val repository = BiliCommentRepository { client }
        `when`(client.getVideoCommentReplies(AID, "42", 1, 20)).thenReturn(biliPage(43..44, 1, 2))
        assertEquals(listOf("43", "44"), repository.loadReplies(biliSource(), "42", 1, 20).comments.map { it.id })
        `when`(client.sendVideoComment(AID, "text", "42", "43")).thenReturn(JSONObject("""{"code":0}"""))
        repository.sendComment(biliSource(), "text", CommentReplyTarget("43", "42", "user"))
        verify(client).sendVideoComment(AID, "text", "42", "43")
        `when`(client.sendVideoComment(AID, "text", null, null)).thenReturn(JSONObject("""{"code":12002}"""))
        val failure = runCatching { repository.sendComment(biliSource(), "text") }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        assertEquals(CommentError.CLOSED, (failure as CommentApiException).reason)
    }

    @Test
    fun `netease passes newest cursor and isolates cached sorts`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        val source = CommentSource(CommentPlatform.NETEASE, AID)
        val repository = NeteaseCommentRepository { client }
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(1..2, false))
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 3, null)).thenReturn(
            """{"code":200,"data":{"comments":[{"commentId":9}],"hasMore":true,"cursor":"12345"}}"""
        )
        `when`(client.getSongCommentsCancellable(AID, 2, 20, 3, "12345")).thenReturn(neteasePage(10..11, false))
        assertEquals(listOf("1", "2"), repository.loadComments(source, 1, 20).comments.map { it.id })
        val newest = repository.loadComments(source, 1, 20, sort = CommentSort.NEWEST)
        assertEquals(listOf("9"), newest.comments.map { it.id })
        assertEquals("12345", newest.nextCursor)
        repository.loadComments(source, 2, 20, sort = CommentSort.NEWEST, cursor = newest.nextCursor)
        repository.loadComments(source, 1, 20)
        verify(client, times(1)).getSongCommentsCancellable(AID, 1, 20, 2, null)
        verify(client).getSongCommentsCancellable(AID, 2, 20, 3, "12345")
    }

    @Test
    fun `bilibili newest uses time order instead of cached hot comments`(): Unit = runBlocking {
        val client = biliClient()
        `when`(client.getVideoComments(AID, 1, 20, 1)).thenReturn(biliPage(1..2, 1, 2))
        `when`(client.getVideoComments(AID, 1, 20, 0)).thenReturn(biliPage(9..10, 1, 2))
        val repository = BiliCommentRepository { client }
        repository.loadComments(biliSource(), 1, 20)
        assertEquals(listOf("9", "10"), repository.loadComments(biliSource(), 1, 20, sort = CommentSort.NEWEST).comments.map { it.id })
    }

    @Test
    fun `authenticated comments bypass anonymous cache and previous account state`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        val source = CommentSource(CommentPlatform.NETEASE, AID)
        val repository = NeteaseCommentRepository { client }
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(1..1, false))
        repository.loadComments(source, 1, 20)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(2..2, false), neteasePage(3..3, false))
        assertEquals("2", repository.loadComments(source, 1, 20).comments.single().id)
        assertEquals("3", repository.loadComments(source, 1, 20).comments.single().id)
        assertNull(CommentMemoryCache.get("NETEASE", AID, 1))
    }

    @Test
    fun `netease like checks business response and invalidates cached pages`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        val source = CommentSource(CommentPlatform.NETEASE, AID)
        val repository = NeteaseCommentRepository { client }
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(1..1, false))
        `when`(client.setSongCommentLiked(AID, "1", true)).thenReturn("""{"code":200}""")
        repository.loadComments(source, 1, 20)
        repository.setLiked(source, "1", true)
        assertNull(CommentMemoryCache.get("NETEASE", AID, 1))
        `when`(client.setSongCommentLiked(AID, "1", false)).thenReturn("""{"code":301}""")
        val failure = runCatching { repository.setLiked(source, "1", false) }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
        assertEquals(CommentError.PERMISSION, (failure as CommentApiException).reason)
    }

    @Test
    fun `newest rejects stalled cursor instead of repeating a page`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getSongCommentsCancellable(AID, 2, 20, 3, "12345")).thenReturn(
            """{"code":200,"data":{"comments":[{"commentId":1}],"hasMore":true,"cursor":"12345"}}"""
        )
        val failure = runCatching {
            NeteaseCommentRepository { client }.loadComments(
                CommentSource(CommentPlatform.NETEASE, AID), 2, 20, sort = CommentSort.NEWEST, cursor = "12345"
            )
        }.exceptionOrNull()
        assertTrue(failure is CommentApiException)
    }

    @Before
    fun setUp() {
        CommentMemoryCache.clear()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        CommentMemoryCache.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `bilibili refresh reloads every page of the refreshed resource`(): Unit = runBlocking {
        val client = biliClient()
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(biliPage(1..20, 1, 40))
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(biliPage(21..40, 2, 40))
        verifyRefresh(BiliCommentRepository { client }, biliSource()) {
            `when`(client.getVideoComments(AID, 1, 20)).thenReturn(biliPage(2..21, 1, 40))
            `when`(client.getVideoComments(AID, 2, 20)).thenReturn(biliPage(22..41, 2, 40))
        }
        verify(client, times(2)).getVideoComments(AID, 2, 20)
    }

    @Test
    fun `netease refresh reloads every page of the refreshed resource`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(1..20, true))
        `when`(client.getSongCommentsCancellable(AID, 2, 20, 2, null)).thenReturn(neteasePage(21..40, false))
        verifyRefresh(NeteaseCommentRepository { client }, CommentSource(CommentPlatform.NETEASE, AID)) {
            `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(2..21, true))
            `when`(client.getSongCommentsCancellable(AID, 2, 20, 2, null)).thenReturn(neteasePage(22..41, false))
        }
        verify(client, times(2)).getSongCommentsCancellable(AID, 2, 20, 2, null)
    }

    @Test
    fun `anonymous degraded page preserves comments and retries without cached degradation`(): Unit = runBlocking {
        val client = biliClient()
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(biliPage(1..3, 1, 29))
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(
            JSONObject("""{"code":0,"data":{"page":{"num":0,"size":0,"count":0},"replies":null}}"""),
            biliPage(4..5, 2, 29)
        )
        val vm = CommentViewModel().apply { repositoryFactory = { BiliCommentRepository { client } } }
        try {
            withTimeout(5_000L) {
                vm.onSourceChanged(biliSource())
                vm.uiState.first { it.status == CommentListStatus.SUCCESS }
                vm.loadMore()
                val failed = vm.uiState.first { !it.isLoadingMore }
                assertEquals(listOf("1", "2", "3"), failed.comments.map { it.id })
                assertEquals(29L, failed.total)
                assertEquals(1, failed.page)
                assertTrue(failed.hasMore)
                assertEquals(CommentError.API, failed.loadMoreError)

                vm.loadMore()
                val recovered = vm.uiState.first { it.page == 2 }
                assertEquals(listOf("1", "2", "3", "4", "5"), recovered.comments.map { it.id })
                assertNull(recovered.loadMoreError)
            }
        } finally {
            vm.onSheetHidden()
        }
        verify(client, times(2)).getVideoComments(AID, 2, 20)
    }

    private suspend fun verifyRefresh(
        repository: CommentRepository,
        source: CommentSource,
        updateServer: suspend () -> Unit
    ) {
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        try {
            withTimeout(5_000L) {
                vm.onSourceChanged(source)
                vm.uiState.first { it.status == CommentListStatus.SUCCESS }
                vm.loadMore()
                vm.uiState.first { it.page == 2 }
                updateServer()
                vm.refresh()
                vm.uiState.first { it.page == 1 && !it.isRefreshing }
                vm.loadMore()
                val result = vm.uiState.first { it.page == 2 && !it.isLoadingMore }
                assertEquals((2..41).map(Int::toString), result.comments.map { it.id })
                assertFalse(result.hasMore)
            }
        } finally {
            vm.onSheetHidden()
        }
    }

    private suspend fun biliClient(): BiliClient = mock(BiliClient::class.java).also { client ->
        `when`(client.hasCommentLogin()).thenReturn(false)
        `when`(client.getVideoBasicInfoByAvid(AID)).thenReturn(
            BiliClient.VideoBasicInfo(
                aid = AID, bvid = "BV1test", title = "song", coverUrl = "", desc = "", durationSec = 1,
                ownerMid = 0L, ownerName = "", ownerFace = "",
                stats = BiliClient.VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
                pages = listOf(BiliClient.VideoPage(456L, 1, "song", 1, 0, 0))
            )
        )
    }

    private fun biliSource() = CommentSource(CommentPlatform.BILIBILI, AID, hasExplicitResourceId = true)

    private fun biliPage(ids: IntRange, page: Int, total: Int) = JSONObject(
        """{"code":0,"data":{"page":{"num":$page,"size":20,"count":$total},"replies":[""" +
            ids.joinToString(",") { """{"rpid":$it}""" } + "]}}"
    )

    private fun neteasePage(ids: IntRange, more: Boolean) =
        """{"code":200,"total":40,"more":$more,"comments":[""" +
            ids.joinToString(",") { """{"commentId":$it}""" } + "]}"

    private companion object {
        const val AID = 9912345L
    }
}
