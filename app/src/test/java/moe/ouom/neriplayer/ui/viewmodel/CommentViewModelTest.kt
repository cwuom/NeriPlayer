package moe.ouom.neriplayer.ui.viewmodel

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.COMMENT_PAGE_SIZE
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.SongComment
import moe.ouom.neriplayer.core.comment.repository.CommentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论 ViewModel 的单元测试。
 *
 * 覆盖: 首屏加载 / 重复打开不重复请求 / 歌曲切换丢弃旧结果 /
 * 分页去重 / 刷新替换 / 空态与错误态区分 / 错误映射。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {

    @Test
    fun `completed source reloads when video identity evidence changes`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI)
        repository.pages[1] = pageOf(1, listOf("old"), CommentPlatform.BILIBILI)
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(CommentSource(CommentPlatform.BILIBILI, 1700010003L))
        advanceUntilIdle()
        repository.pages[1] = pageOf(1, listOf("verified"), CommentPlatform.BILIBILI)
        vm.onSourceChanged(CommentSource(CommentPlatform.BILIBILI, 1700010003L, "BV17x411w7KC", 279787L))
        advanceUntilIdle()
        assertEquals(listOf("verified"), vm.uiState.value.comments.map { it.id })
        assertEquals(listOf(null, "BV17x411w7KC"), repository.secondaryIds)
    }

    private class FakeCommentRepository(
        override val platform: CommentPlatform
    ) : CommentRepository {

        val requestedPages = mutableListOf<Int>()
        val forceRefreshes = mutableListOf<Boolean>()
        val pages = mutableMapOf<Int, CommentPage>()
        val sorts = mutableListOf<CommentSort>()
        val cursors = mutableListOf<String?>()
        val likes = mutableListOf<Pair<String, Boolean>>()
        var likeFailure: Throwable? = null
        var likeDelayMs: Long = 0L
        var failPages: Set<Int> = emptySet()
        var failure: Throwable? = null
        var delayMs: Long = 0L

        /** 模拟「仓库层把取消吞成业务错误」的形态 (曾经的 runCatching 就是如此) */
        var swallowCancellation: Boolean = false
        var secondaryIds = mutableListOf<String?>()

        /**
         * 假仓库实现：记录请求页码/强制刷新标志/次生 id，按页码返回预置数据，并可注入延迟与失败。
         */
        override suspend fun loadComments(
            source: CommentSource,
            page: Int,
            pageSize: Int,
            forceRefresh: Boolean,
            sort: CommentSort,
            cursor: String?
        ): CommentPage {
            sorts += sort
            cursors += cursor
            requestedPages += page
            forceRefreshes += forceRefresh
            secondaryIds += source.secondaryId
            if (delayMs > 0L) {
                if (swallowCancellation) {
                    try {
                        delay(delayMs)
                    } catch (cancellation: CancellationException) {
                        throw IOException("cancelled but reported as failure", cancellation)
                    }
                } else {
                    delay(delayMs)
                }
            }
            failure?.let { throw it }
            if (page in failPages) throw IOException("boom page $page")
            return pages[page] ?: CommentPage(
                comments = emptyList(),
                page = page,
                pageSize = pageSize,
                total = null,
                hasMore = false
            )
        }

        override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
            likes += commentId to liked
            delay(likeDelayMs)
            likeFailure?.let { throw it }
        }
    }

    @Test
    fun `sort defaults to hot and switching cancels old paging and resets cursor`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("hot"), CommentPlatform.NETEASE, hasMore = true)
        repository.pages[2] = pageOf(2, listOf("old-page"), CommentPlatform.NETEASE)
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        assertEquals(CommentSort.HOT, vm.uiState.value.sort)
        repository.delayMs = 1_000L
        vm.loadMore()
        advanceTimeBy(100L)
        repository.pages[1] = pageOf(1, listOf("newest"), CommentPlatform.NETEASE, hasMore = true)
            .copy(nextCursor = "1700000000000")
        vm.selectSort(CommentSort.NEWEST)
        assertEquals(listOf("hot"), vm.uiState.value.comments.map { it.id })
        assertEquals(CommentSort.HOT, vm.uiState.value.sort)
        assertEquals(CommentSort.NEWEST, vm.uiState.value.pendingSort)
        assertEquals(CommentListStatus.SUCCESS, vm.uiState.value.status)
        advanceUntilIdle()
        assertEquals(listOf("newest"), vm.uiState.value.comments.map { it.id })
        assertEquals(1, vm.uiState.value.page)
        assertNull(vm.uiState.value.pendingSort)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(CommentSort.HOT, CommentSort.HOT, CommentSort.NEWEST, CommentSort.NEWEST), repository.sorts)
        assertEquals(listOf(null, null, null, "1700000000000"), repository.cursors)
    }

    @Test
    fun `selecting the same or unsupported sort makes no request`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI)
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.BILIBILI, 1L))
        advanceUntilIdle()
        vm.selectSort(CommentSort.HOT)
        vm.selectSort(CommentSort.RECOMMENDED)
        advanceUntilIdle()
        assertEquals(listOf(CommentSort.HOT), repository.sorts)
    }

    @Test
    fun `failed sort retains original comments order and paging cursor`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            pages[1] = pageOf(1, listOf("hot"), platform, hasMore = true).copy(nextCursor = "original")
        }
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        repository.failure = IOException("unavailable")
        vm.selectSort(CommentSort.NEWEST)
        vm.loadMore()
        vm.toggleLike("hot")
        advanceUntilIdle()
        assertEquals(listOf("hot"), vm.uiState.value.comments.map { it.id })
        assertEquals(CommentSort.HOT, vm.uiState.value.sort)
        assertEquals("original", vm.uiState.value.nextCursor)
        assertEquals(1, vm.uiState.value.page)
        assertEquals(CommentListStatus.SUCCESS, vm.uiState.value.status)
        assertEquals(CommentError.NETWORK, vm.uiState.value.error)
        assertNull(vm.uiState.value.pendingSort)
        assertEquals(listOf(1, 1), repository.requestedPages)
        assertTrue(repository.likes.isEmpty())
        vm.dismissLoadError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `rapid sort changes apply only the last requested sort`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            pages[1] = pageOf(1, listOf("initial"), platform)
        }
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        repository.delayMs = 1_000L
        vm.selectSort(CommentSort.NEWEST)
        advanceTimeBy(100)
        vm.selectSort(CommentSort.RECOMMENDED)
        repository.pages[1] = pageOf(1, listOf("recommended"), CommentPlatform.NETEASE)
        advanceUntilIdle()
        assertEquals(CommentSort.RECOMMENDED, vm.uiState.value.sort)
        assertEquals(listOf("recommended"), vm.uiState.value.comments.map { it.id })
        assertNull(vm.uiState.value.pendingSort)
    }

    @Test
    fun `likes wait for success ignore duplicate taps and support unlike`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            pages[1] = pageOf(1, listOf("1"), platform)
            likeDelayMs = 1_000L
        }
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        vm.toggleLike("1")
        vm.toggleLike("1")
        advanceTimeBy(100L)
        assertFalse(vm.uiState.value.comments.single().isLiked)
        assertEquals(setOf("1"), vm.uiState.value.likingIds)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.comments.single().isLiked)
        assertEquals(1L, vm.uiState.value.comments.single().likeCount)
        vm.toggleLike("1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.comments.single().isLiked)
        assertEquals(0L, vm.uiState.value.comments.single().likeCount)
        assertEquals(listOf("1" to true, "1" to false), repository.likes)
        assertTrue(vm.uiState.value.likingIds.isEmpty())
    }

    @Test
    fun `like failure preserves content and provides dismissible permission error`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            pages[1] = pageOf(1, listOf("1"), platform)
            likeFailure = CommentApiException(301, CommentError.PERMISSION, "login required")
        }
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        vm.toggleLike("1")
        advanceUntilIdle()
        assertEquals(CommentListStatus.SUCCESS, vm.uiState.value.status)
        assertFalse(vm.uiState.value.comments.single().isLiked)
        assertEquals(0L, vm.uiState.value.comments.single().likeCount)
        assertEquals(CommentError.PERMISSION, vm.uiState.value.likeError)
        assertEquals(301, vm.uiState.value.likeErrorCode)
        assertTrue(vm.uiState.value.likingIds.isEmpty())
        vm.dismissLikeError()
        assertNull(vm.uiState.value.likeError)
        assertNull(vm.uiState.value.likeErrorCode)
    }

    @Test
    fun `changing source cannot apply an old like to the same comment id`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            pages[1] = pageOf(1, listOf("1"), platform)
            likeDelayMs = 1_000L
        }
        val vm = CommentViewModel().apply { repositoryFactory = { repository } }
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        vm.toggleLike("1")
        advanceTimeBy(100L)
        vm.onSourceChanged(source(CommentPlatform.NETEASE, 2L))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.comments.single().isLiked)
        assertTrue(vm.uiState.value.likingIds.isEmpty())
        assertNull(vm.uiState.value.likeError)
    }

    /**
     * 构造测试用 SongComment：内容与用户名由 id 派生，其余可选字段留空。
     */
    private fun comment(id: String, platform: CommentPlatform) = SongComment(
        id = id,
        userId = null,
        username = "u-$id",
        avatarUrl = null,
        content = "c-$id",
        likeCount = 0L,
        replyCount = null,
        createTime = null,
        platform = platform,
        userLevel = null
    )

    /**
     * 按 id 列表构造 CommentPage，页大小统一用 COMMENT_PAGE_SIZE。
     */
    private fun pageOf(
        page: Int,
        ids: List<String>,
        platform: CommentPlatform,
        hasMore: Boolean = false,
        total: Long? = null
    ) = CommentPage(
        comments = ids.map { comment(it, platform) },
        page = page,
        pageSize = COMMENT_PAGE_SIZE,
        total = total,
        hasMore = hasMore
    )

    /**
     * 构造不带 secondaryId 的 CommentSource（平台 + 资源 id）。
     */
    private fun source(platform: CommentPlatform, resourceId: Long) =
        CommentSource(platform = platform, resourceId = resourceId, secondaryId = null)

    /**
     * 测试脚手架：在 runTest 中把 Main 调度器替换为 StandardTestDispatcher，结束后恢复。
     */
    private fun commentTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * 切换音源：加载首页后状态为 SUCCESS，评论、页码与来源正确，且只请求第 1 页。
     */
    @Test
    fun `onSourceChanged loads the first page and exposes the comments`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("n1", "n2"), CommentPlatform.NETEASE)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 100L))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.SUCCESS, state.status)
        assertEquals(listOf("n1", "n2"), state.comments.map { it.id })
        assertEquals(1, state.page)
        assertEquals(CommentPlatform.NETEASE, state.source?.platform)
        assertEquals(listOf(1), repository.requestedPages)
    }

    /**
     * 同一首歌重复触发 onSourceChanged（重组 / 重新打开弹窗）时不会重复请求。
     */
    @Test
    fun `repeated source change for the same song requests only once`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("n1"), CommentPlatform.NETEASE)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        val same = source(CommentPlatform.NETEASE, 100L)
        viewModel.onSourceChanged(same)
        advanceUntilIdle()
        // 重组 / 重新打开弹窗不应该再次请求
        viewModel.onSourceChanged(same)
        advanceUntilIdle()
        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 100L))
        advanceUntilIdle()

        assertEquals(listOf(1), repository.requestedPages)
    }

    /**
     * 切换歌曲：旧请求在飞行途中被切走，最终只展示新歌评论且状态为 SUCCESS。
     */
    @Test
    fun `switching songs loads the new song and never shows the old comments`() = commentTest {
        val netease = FakeCommentRepository(CommentPlatform.NETEASE)
        netease.pages[1] = pageOf(1, listOf("old"), CommentPlatform.NETEASE)
        netease.delayMs = 1_000L

        val bili = FakeCommentRepository(CommentPlatform.BILIBILI)
        bili.pages[1] = pageOf(1, listOf("new"), CommentPlatform.BILIBILI)

        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { platform ->
            if (platform == CommentPlatform.NETEASE) netease else bili
        }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceTimeBy(100L)
        // 歌曲在请求飞行途中被切走
        viewModel.onSourceChanged(source(CommentPlatform.BILIBILI, 2L))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentPlatform.BILIBILI, state.source?.platform)
        assertEquals(listOf("new"), state.comments.map { it.id })
        assertEquals(CommentListStatus.SUCCESS, state.status)
    }

    /**
     * 加载下一页：追加第 2 页并按评论 id 去重，页码推进到 2，hasMore 与 isLoadingMore 复位。
     */
    @Test
    fun `load more appends the next page and deduplicates by comment id`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("a", "b", "c"), CommentPlatform.NETEASE, hasMore = true)
        repository.pages[2] = pageOf(2, listOf("c", "d"), CommentPlatform.NETEASE, hasMore = false)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c", "d"), state.comments.map { it.id })
        assertEquals(2, state.page)
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    /**
     * 连续调用 loadMore 时仅第一个请求生效，飞行中的重复请求被忽略（页码只到 2）。
     */
    @Test
    fun `load more is ignored while another page request is in flight`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("a"), CommentPlatform.NETEASE, hasMore = true)
        repository.pages[2] = pageOf(2, listOf("b"), CommentPlatform.NETEASE, hasMore = true)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()

        repository.delayMs = 1_000L
        viewModel.loadMore()
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), repository.requestedPages)
    }

    /**
     * 已到最后一页（hasMore=false）时 loadMore 不再发起请求。
     */
    @Test
    fun `load more is ignored once the last page was reached`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("a"), CommentPlatform.NETEASE, hasMore = false)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1), repository.requestedPages)
    }

    /**
     * 刷新：用新数据替换首页，清除刷新态与 hasMore，且刷新请求带 forceRefresh=true。
     */
    @Test
    fun `refresh replaces the first page data`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("old"), CommentPlatform.NETEASE, hasMore = true)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()

        repository.pages[1] = pageOf(1, listOf("new1", "new2"), CommentPlatform.NETEASE, hasMore = false)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("new1", "new2"), state.comments.map { it.id })
        assertFalse(state.isRefreshing)
        assertFalse(state.hasMore)
        assertEquals(listOf(1, 1), repository.requestedPages)
        assertEquals(listOf(false, true), repository.forceRefreshes)
    }

    /**
     * 首页加载失败映射为 ERROR / NETWORK，retry 后恢复 SUCCESS 并清空错误。
     */
    @Test
    fun `first page failure reports an error and retry recovers`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.failure = IOException("network down")
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()

        assertEquals(CommentListStatus.ERROR, viewModel.uiState.value.status)
        assertEquals(CommentError.NETWORK, viewModel.uiState.value.error)

        repository.failure = null
        repository.pages[1] = pageOf(1, listOf("a"), CommentPlatform.NETEASE)
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.SUCCESS, state.status)
        assertNull(state.error)
        assertEquals(listOf("a"), state.comments.map { it.id })
    }

    /**
     * CommentApiException 保留其语义原因（12061 → CLOSED），不被降级为 UNKNOWN。
     */
    @Test
    fun `api failure keeps its semantic reason`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI)
        repository.failure = CommentApiException(
            code = 12061,
            reason = CommentError.CLOSED,
            message = "closed"
        )
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.BILIBILI, 1L))
        advanceUntilIdle()

        assertEquals(CommentListStatus.ERROR, viewModel.uiState.value.status)
        assertEquals(CommentError.CLOSED, viewModel.uiState.value.error)
    }

    /**
     * 空结果状态为 EMPTY 且无错误，与失败态明确区分。
     */
    @Test
    fun `an empty result is distinguished from a failure`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, emptyList(), CommentPlatform.NETEASE, hasMore = false)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.EMPTY, state.status)
        assertNull(state.error)
        assertTrue(state.comments.isEmpty())
    }

    /**
     * 加载更多失败：保留已有评论与 SUCCESS 状态，仅设置 loadMoreError 页脚错误。
     */
    @Test
    fun `load more failure keeps existing comments and exposes a footer error`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("a", "b"), CommentPlatform.NETEASE, hasMore = true)
        repository.failPages = setOf(2)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.SUCCESS, state.status)
        assertEquals(listOf("a", "b"), state.comments.map { it.id })
        assertEquals(CommentError.NETWORK, state.loadMoreError)
        assertFalse(state.isLoadingMore)
        assertNull(state.error)
    }

    @Test
    fun `an unavailable later page keeps the total and remains retryable`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI)
        repository.pages[1] = pageOf(
            1, listOf("a", "b", "c"), CommentPlatform.BILIBILI, hasMore = true, total = 29L
        )
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }
        viewModel.onSourceChanged(source(CommentPlatform.BILIBILI, 1L))
        advanceUntilIdle()
        repository.failure = CommentApiException(0, CommentError.API, "invalid pagination")
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.SUCCESS, state.status)
        assertEquals(listOf("a", "b", "c"), state.comments.map { it.id })
        assertEquals(29L, state.total ?: -1L)
        assertEquals(1, state.page)
        assertTrue(state.hasMore)
        assertEquals(CommentError.API, state.loadMoreError)

        repository.failure = null
        repository.pages[2] = pageOf(2, listOf("d"), CommentPlatform.BILIBILI)
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d"), viewModel.uiState.value.comments.map { it.id })
        assertNull(viewModel.uiState.value.loadMoreError)
    }

    /**
     * 音源置为 null 时状态重置为 IDLE，并清空评论与来源。
     */
    @Test
    fun `null source resets the state to idle`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE)
        repository.pages[1] = pageOf(1, listOf("a"), CommentPlatform.NETEASE)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 1L))
        advanceUntilIdle()

        viewModel.onSourceChanged(null)

        val state = viewModel.uiState.value
        assertEquals(CommentListStatus.IDLE, state.status)
        assertTrue(state.comments.isEmpty())
        assertNull(state.source)
    }

    /**
     * Bilibili 来源的 bvid（secondaryId）被原样透传给仓库。
     */
    @Test
    fun `bilibili source passes the bvid through to the repository`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI)
        repository.pages[1] = pageOf(1, listOf("b1"), CommentPlatform.BILIBILI)
        val viewModel = CommentViewModel()
        viewModel.repositoryFactory = { repository }

        viewModel.onSourceChanged(
            CommentSource(
                platform = CommentPlatform.BILIBILI,
                resourceId = 12345L,
                secondaryId = "BV1xx411c7mD"
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("BV1xx411c7mD"), repository.secondaryIds)
    }

    /**
     * mergeComments 按 id 去重并保持已有顺序，id 不同而内容相同的两条都保留。
     */
    @Test
    fun `mergeComments deduplicates by comment id and keeps existing order`() {
        val existing = listOf(
            comment("a", CommentPlatform.NETEASE),
            comment("b", CommentPlatform.NETEASE)
        )
        val incoming = listOf(
            comment("b", CommentPlatform.NETEASE),
            comment("c", CommentPlatform.NETEASE)
        )

        assertEquals(listOf("a", "b", "c"), mergeComments(existing, incoming).map { it.id })
        // 内容相同但 id 不同 -> 两条都保留 (按 id 而不是内容去重)
        assertEquals(
            listOf("a", "x"),
            mergeComments(
                listOf(comment("a", CommentPlatform.NETEASE)),
                listOf(comment("x", CommentPlatform.NETEASE))
            ).map { it.id }
        )
    }

    /**
     * 身份线索变化必须重新解析，避免相同数值的历史 id 和真实 aid 串用结果
     */
    @Test
    fun `isSameCommentSource includes video identity evidence`() {
        assertFalse(
            isSameCommentSource(
                CommentSource(CommentPlatform.BILIBILI, 1L, "BV1xx411c7mD"),
                CommentSource(CommentPlatform.BILIBILI, 1L, null)
            )
        )
        assertFalse(
            isSameCommentSource(
                CommentSource(CommentPlatform.BILIBILI, 1L),
                CommentSource(CommentPlatform.BILIBILI, 2L)
            )
        )
        assertFalse(
            isSameCommentSource(
                CommentSource(CommentPlatform.NETEASE, 1L),
                CommentSource(CommentPlatform.BILIBILI, 1L)
            )
        )
        assertTrue(isSameCommentSource(null, null))
        assertFalse(isSameCommentSource(null, CommentSource(CommentPlatform.NETEASE, 1L)))
    }

    /**
     * toCommentError 映射语义原因：CommentApiException 用其自带 reason，IOException→NETWORK，其余→UNKNOWN。
     */
    @Test
    fun `toCommentError maps failures to semantic reasons`() {
        assertEquals(
            CommentError.PERMISSION,
            toCommentError(CommentApiException(403, CommentError.PERMISSION, "forbidden"))
        )
        assertEquals(CommentError.NETWORK, toCommentError(IOException("network down")))
        assertEquals(CommentError.UNKNOWN, toCommentError(IllegalStateException("boom")))
    }

    /**
     * 取消不能变成错误：即使仓库层把 CancellationException 吞成业务异常，
     * 被取消的那次请求也不得把状态写成 ERROR / loadMoreError。
     */
    @Test
    fun `a cancelled load never publishes an error`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.NETEASE).apply {
            delayMs = 1_000L
            swallowCancellation = true
            pages[1] = pageOf(1, listOf("1"), CommentPlatform.NETEASE)
        }
        val viewModel = CommentViewModel().apply { repositoryFactory = { repository } }

        viewModel.onSourceChanged(source(CommentPlatform.NETEASE, 11L))
        advanceTimeBy(100L)

        // 刷新会取消上一次首屏请求, 此时新请求还在途中
        viewModel.refresh()
        advanceTimeBy(50L)

        assertEquals(CommentListStatus.LOADING, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.error)

        advanceUntilIdle()
        assertEquals(CommentListStatus.SUCCESS, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.error)
    }

    /**
     * 面板隐藏时取消在途请求并把「加载中」回落到 IDLE，重新打开会重新请求一次，
     * 而不是因为没有取消而把结果留给已关闭的面板，也不是卡在 LOADING。
     */
    @Test
    fun `hiding the sheet cancels the in-flight load and returns to idle`() = commentTest {
        val repository = FakeCommentRepository(CommentPlatform.BILIBILI).apply {
            delayMs = 1_000L
            pages[1] = pageOf(1, listOf("1"), CommentPlatform.BILIBILI)
        }
        val viewModel = CommentViewModel().apply { repositoryFactory = { repository } }

        viewModel.onSourceChanged(source(CommentPlatform.BILIBILI, 22L))
        advanceTimeBy(100L)
        viewModel.onSheetHidden()
        advanceUntilIdle()

        assertEquals(CommentListStatus.IDLE, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.comments.isEmpty())
        assertNull(viewModel.uiState.value.error)

        // 重新打开面板: 同一个音源也要重新请求一次 (§51 打开面板 = 一次请求)
        viewModel.onSourceChanged(source(CommentPlatform.BILIBILI, 22L))
        advanceUntilIdle()

        assertEquals(CommentListStatus.SUCCESS, viewModel.uiState.value.status)
        assertEquals(listOf(1, 1), repository.requestedPages)
    }
}
