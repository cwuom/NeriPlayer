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
        `when`(client.getSongCommentsCancellable(AID, 20, 0)).thenReturn(neteasePage(1..20, true))
        `when`(client.getSongCommentsCancellable(AID, 20, 20)).thenReturn(neteasePage(21..40, false))
        verifyRefresh(NeteaseCommentRepository { client }, CommentSource(CommentPlatform.NETEASE, AID)) {
            `when`(client.getSongCommentsCancellable(AID, 20, 0)).thenReturn(neteasePage(2..21, true))
            `when`(client.getSongCommentsCancellable(AID, 20, 20)).thenReturn(neteasePage(22..41, false))
        }
        verify(client, times(2)).getSongCommentsCancellable(AID, 20, 20)
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
