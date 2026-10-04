package moe.ouom.neriplayer.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.comments.CommentMemoryCache
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
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
import kotlin.time.Duration.Companion.milliseconds
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import moe.ouom.neriplayer.platform.comments.repository.NeteaseCommentRepository
import moe.ouom.neriplayer.platform.comments.repository.BiliCommentRepository

@OptIn(ExperimentalCoroutinesApi::class)
class CommentRepositoryIntegrationTest {
    private val cache = CommentMemoryCache()

    @Before
    fun setUp() {
        cache.clear()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        cache.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `bilibili refresh reloads every page of the refreshed resource`(): Unit = runBlocking {
        val client = biliClient()
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(biliPage(1..20, 1, 40))
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(biliPage(21..40, 2, 40))
        verifyRefresh(BiliCommentRepository(cache) { client }, biliSource()) {
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
        verifyRefresh(NeteaseCommentRepository(cache) { client }, CommentSource(CommentPlatform.NETEASE, AID)) {
            `when`(client.getSongCommentsCancellable(AID, 1, 20, 2, null)).thenReturn(neteasePage(2..21, true))
            `when`(client.getSongCommentsCancellable(AID, 2, 20, 2, null)).thenReturn(neteasePage(22..41, false))
        }
        verify(client, times(2)).getSongCommentsCancellable(AID, 2, 20, 2, null)
    }

    @Test
    fun `bilibili stops after seventeen roots and fourteen replies account for all thirty one comments`(): Unit = runBlocking {
        val client = biliClient()
        val replies = (1..17).joinToString(",") {
            """{"rpid":$it,"rcount":${if (it <= 14) 1 else 0}}"""
        }
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(JSONObject(
            """{"code":0,"data":{"page":{"num":1,"size":20,"count":31,"acount":31},"replies":[$replies]}}"""
        ))
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(
            JSONObject("""{"code":0,"data":{"page":{"num":0,"size":0,"count":0},"replies":null}}""")
        )
        val vm = CommentViewModel().apply { repositoryFactory = { BiliCommentRepository(cache) { client } } }
        try {
            withTimeout(5_000.milliseconds) {
                vm.onSourceChanged(biliSource())
                val state = vm.uiState.first { it.status == CommentListStatus.SUCCESS }
                assertEquals((1..17).map(Int::toString), state.comments.map { it.id })
                assertEquals(14L, state.comments.sumOf { it.replyCount ?: 0L })
                assertEquals(31L, state.total)
                assertEquals(31L, state.totalIncludingReplies)
                assertFalse(state.hasMore)
                assertNull(state.loadMoreError)
                vm.loadMore()
                assertFalse(vm.uiState.value.isLoadingMore)
                assertNull(vm.uiState.value.loadMoreError)
            }
        } finally {
            vm.onSheetHidden()
        }
        verify(client, times(1)).getVideoComments(AID, 1, 20)
        verify(client, times(0)).getVideoComments(AID, 2, 20)
    }

    @Test
    fun `anonymous degraded page preserves comments and retries without cached degradation`(): Unit = runBlocking {
        val client = biliClient()
        `when`(client.getVideoComments(AID, 1, 20)).thenReturn(biliPage(1..3, 1, 29).apply {
            getJSONObject("data").getJSONObject("page").put("acount", 29)
        })
        `when`(client.getVideoComments(AID, 2, 20)).thenReturn(
            JSONObject("""{"code":0,"data":{"page":{"num":0,"size":0,"count":0},"replies":null}}"""),
            biliPage(4..5, 2, 29)
        )
        val vm = CommentViewModel().apply { repositoryFactory = { BiliCommentRepository(cache) { client } } }
        try {
            withTimeout(5_000.milliseconds) {
                vm.onSourceChanged(biliSource())
                vm.uiState.first { it.status == CommentListStatus.SUCCESS }
                vm.loadMore()
                val failed = vm.uiState.first { !it.isLoadingMore }
                assertEquals(listOf("1", "2", "3"), failed.comments.map { it.id })
                assertEquals(29L, failed.total)
                assertEquals(29L, failed.totalIncludingReplies)
                assertEquals(1, failed.page)
                assertTrue(failed.hasMore)
                assertEquals(CommentError.API, failed.loadMoreError)

                vm.loadMore()
                val recovered = vm.uiState.first { it.page == 2 }
                assertEquals(listOf("1", "2", "3", "4", "5"), recovered.comments.map { it.id })
                assertEquals(29L, recovered.totalIncludingReplies)
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
            withTimeout(5_000.milliseconds) {
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
            VideoBasicInfo(
                aid = AID, bvid = "BV1test", title = "song", coverUrl = "", desc = "", durationSec = 1,
                ownerMid = 0L, ownerName = "", ownerFace = "",
                stats = VideoStats(0L, 0L, 0L, 0L, 0L, 0L, 0L),
                pages = listOf(VideoPage(456L, 1, "song", 1, 0, 0))
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
