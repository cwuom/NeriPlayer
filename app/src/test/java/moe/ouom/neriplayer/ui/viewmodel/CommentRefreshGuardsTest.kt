package moe.ouom.neriplayer.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.comments.COMMENT_PAGE_SIZE
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentRefreshGuardsTest {

    @Test
    fun `refresh without an active source does nothing`() = commentTest {
        val repository = GatedCommentRepository()
        val viewModel = CommentViewModel().apply { repositoryFactory = { repository } }

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(repository.requestedPages.isEmpty())
        assertEquals(CommentListStatus.IDLE, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh while a refresh is running sends no second request`() = commentTest {
        val repository = GatedCommentRepository()
        val viewModel = loadedViewModel(repository)
        repository.loadDelayMs = 1_000L

        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.uiState.value.isRefreshing)
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(1, 1), repository.requestedPages)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1, 1), repository.requestedPages)
    }

    @Test
    fun `refresh while cached comments are being checked waits for the background check`() = commentTest {
        val repository = GatedCommentRepository().apply {
            cachedPage = page(1, "cached")
            pages[1] = page(1, "cached")
            loadDelayMs = 1_000L
        }
        val viewModel = CommentViewModel().apply { repositoryFactory = { repository } }
        viewModel.onSourceChanged(SOURCE)
        runCurrent()
        assertTrue(viewModel.uiState.value.isCheckingCache)
        assertEquals(listOf("cached"), viewModel.uiState.value.comments.map { it.id })

        viewModel.refresh()
        runCurrent()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1), repository.requestedPages)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCheckingCache)
        assertEquals(listOf("cached"), viewModel.uiState.value.comments.map { it.id })
        assertEquals(listOf(1), repository.requestedPages)
    }

    @Test
    fun `refresh while the next page is loading keeps the pagination request`() = commentTest {
        val repository = GatedCommentRepository().apply {
            pages[1] = page(1, "1", hasMore = true)
            pages[2] = page(2, "2")
        }
        val viewModel = loadedViewModel(repository)
        repository.loadDelayMs = 1_000L

        viewModel.loadMore()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoadingMore)
        viewModel.refresh()
        runCurrent()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1, 2), repository.requestedPages)
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), viewModel.uiState.value.comments.map { it.id })
    }

    @Test
    fun `refresh while a comment is being sent is ignored`() = commentTest {
        val repository = GatedCommentRepository()
        val viewModel = loadedViewModel(repository)
        repository.sendDelayMs = 1_000L
        viewModel.updateDraft("hello")

        viewModel.sendComment()
        runCurrent()
        assertTrue(viewModel.uiState.value.isSending)
        viewModel.refresh()
        runCurrent()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1), repository.requestedPages)
        advanceUntilIdle()
        assertEquals(listOf("hello"), repository.sentContents)
    }

    @Test
    fun `refresh while a like is pending is ignored`() = commentTest {
        val repository = GatedCommentRepository()
        val viewModel = loadedViewModel(repository)
        repository.likeDelayMs = 1_000L

        viewModel.toggleLike("1")
        runCurrent()
        assertEquals(setOf("1"), viewModel.uiState.value.likingIds)
        viewModel.refresh()
        runCurrent()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1), repository.requestedPages)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.comments.single().isLiked)
    }

    @Test
    fun `refresh while a sort change is pending is ignored`() = commentTest {
        val repository = GatedCommentRepository()
        val viewModel = loadedViewModel(repository)
        repository.loadDelayMs = 1_000L

        viewModel.selectSort(CommentSort.NEWEST)
        runCurrent()
        assertEquals(CommentSort.NEWEST, viewModel.uiState.value.pendingSort)
        viewModel.refresh()
        runCurrent()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1, 1), repository.requestedPages)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingSort)
        assertEquals(CommentSort.NEWEST, viewModel.uiState.value.sort)
        assertEquals(listOf(CommentSort.HOT, CommentSort.NEWEST), repository.requestedSorts)
    }

    private fun TestScope.loadedViewModel(repository: GatedCommentRepository): CommentViewModel {
        if (repository.pages.isEmpty()) repository.pages[1] = page(1, "1")
        val viewModel = CommentViewModel().apply { repositoryFactory = { repository } }
        viewModel.onSourceChanged(SOURCE)
        advanceUntilIdle()
        assertEquals(CommentListStatus.SUCCESS, viewModel.uiState.value.status)
        return viewModel
    }

    private fun commentTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class GatedCommentRepository : CommentRepository {
        override val platform: CommentPlatform = CommentPlatform.NETEASE
        val pages = mutableMapOf<Int, CommentPage>()
        val requestedPages = mutableListOf<Int>()
        val requestedSorts = mutableListOf<CommentSort>()
        val sentContents = mutableListOf<String>()
        var cachedPage: CommentPage? = null
        var loadDelayMs = 0L
        var likeDelayMs = 0L
        var sendDelayMs = 0L

        override suspend fun cachedComments(source: CommentSource, pageSize: Int, sort: CommentSort) = cachedPage

        override suspend fun loadComments(
            source: CommentSource,
            page: Int,
            pageSize: Int,
            forceRefresh: Boolean,
            sort: CommentSort,
            cursor: String?
        ): CommentPage {
            requestedPages += page
            requestedSorts += sort
            delay(loadDelayMs)
            return pages[page] ?: CommentPage(emptyList(), page, pageSize, total = null, hasMore = false)
        }

        override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
            delay(likeDelayMs)
        }

        override suspend fun loadReplies(
            source: CommentSource,
            rootId: String,
            page: Int,
            pageSize: Int,
            cursor: String?
        ): CommentPage = CommentPage(emptyList(), page, pageSize, total = 0L, hasMore = false)

        override suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget?) {
            sentContents += content
            delay(sendDelayMs)
        }
    }

    private companion object {
        val SOURCE = CommentSource(platform = CommentPlatform.NETEASE, resourceId = 1L, secondaryId = null)

        fun page(page: Int, vararg ids: String, hasMore: Boolean = false) = CommentPage(
            comments = ids.map(::comment),
            page = page,
            pageSize = COMMENT_PAGE_SIZE,
            total = null,
            hasMore = hasMore
        )

        fun comment(id: String) = SongComment(
            id = id,
            userId = null,
            username = "u-$id",
            avatarUrl = null,
            content = "c-$id",
            likeCount = 0L,
            replyCount = null,
            createTime = null,
            platform = CommentPlatform.NETEASE,
            userLevel = null
        )
    }
}
