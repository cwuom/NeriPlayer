package moe.ouom.neriplayer.ui.viewmodel

import java.io.IOException
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

    private class FakeCommentRepository(
        override val platform: CommentPlatform
    ) : CommentRepository {

        val requestedPages = mutableListOf<Int>()
        val forceRefreshes = mutableListOf<Boolean>()
        val pages = mutableMapOf<Int, CommentPage>()
        var failPages: Set<Int> = emptySet()
        var failure: Throwable? = null
        var delayMs: Long = 0L
        var secondaryIds = mutableListOf<String?>()

        /**
         * 假仓库实现：记录请求页码/强制刷新标志/次生 id，按页码返回预置数据，并可注入延迟与失败。
         */
        override suspend fun loadComments(
            resourceId: Long,
            secondaryId: String?,
            page: Int,
            pageSize: Int,
            forceRefresh: Boolean
        ): CommentPage {
            requestedPages += page
            forceRefreshes += forceRefresh
            secondaryIds += secondaryId
            if (delayMs > 0L) delay(delayMs)
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
     * isSameCommentSource 只比较平台与资源 id：secondaryId 不同视为同一来源，null 与 null 相等。
     */
    @Test
    fun `isSameCommentSource compares platform and resource id only`() {
        assertTrue(
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
}
