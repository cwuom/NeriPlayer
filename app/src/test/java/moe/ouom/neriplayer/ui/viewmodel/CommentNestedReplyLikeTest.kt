package moe.ouom.neriplayer.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentNestedReplyLikeTest {

    @Test
    fun `liking a preview reply updates only that reply inside its root comment`() = commentTest {
        val untouchedRoot = comment("2", previewReplies = listOf(comment("2-a")))
        val repository = LikeRecordingRepository(
            roots = listOf(comment("1", previewReplies = listOf(comment("1-a"), comment("1-b"))), untouchedRoot)
        )
        val viewModel = loadedViewModel(repository)

        viewModel.toggleLike("1-b")
        advanceUntilIdle()

        val comments = viewModel.uiState.value.comments
        assertEquals(listOf("1-b" to true), repository.likes)
        assertFalse(comments[0].isLiked)
        assertEquals(listOf(false, true), comments[0].previewReplies.map { it.isLiked })
        assertEquals(listOf(0L, 1L), comments[0].previewReplies.map { it.likeCount })
        assertEquals(untouchedRoot, comments[1])
        assertTrue(viewModel.uiState.value.likingIds.isEmpty())
    }

    @Test
    fun `liking an expanded reply updates only the thread that contains it`() = commentTest {
        val repository = LikeRecordingRepository(
            roots = listOf(comment("1"), comment("2")),
            replies = mapOf(
                "1" to listOf(comment("r1", rootId = "1"), comment("r2", rootId = "1")),
                "2" to listOf(comment("s1", rootId = "2"))
            )
        )
        val viewModel = loadedViewModel(repository)
        viewModel.toggleReplies("1")
        viewModel.toggleReplies("2")
        advanceUntilIdle()

        viewModel.toggleLike("r2")
        advanceUntilIdle()

        val threads = viewModel.uiState.value.replyThreads
        assertEquals(listOf("r2" to true), repository.likes)
        assertEquals(listOf(false, true), threads.getValue("1").comments.map { it.isLiked })
        assertEquals(listOf(0L, 1L), threads.getValue("1").comments.map { it.likeCount })
        assertEquals(listOf(false), threads.getValue("2").comments.map { it.isLiked })
        assertEquals(listOf(false, false), viewModel.uiState.value.comments.map { it.isLiked })
    }

    @Test
    fun `liking an id that is not on screen sends nothing`() = commentTest {
        val repository = LikeRecordingRepository(
            roots = listOf(comment("1", previewReplies = listOf(comment("1-a")))),
            replies = mapOf("1" to listOf(comment("r1", rootId = "1")))
        )
        val viewModel = loadedViewModel(repository)
        viewModel.toggleReplies("1")
        advanceUntilIdle()

        viewModel.toggleLike("missing")
        advanceUntilIdle()

        assertTrue(repository.likes.isEmpty())
        assertTrue(viewModel.uiState.value.likingIds.isEmpty())
        assertFalse(viewModel.uiState.value.comments.single().isLiked)
    }

    private fun TestScope.loadedViewModel(repository: LikeRecordingRepository): CommentViewModel {
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

    private class LikeRecordingRepository(
        private val roots: List<SongComment>,
        private val replies: Map<String, List<SongComment>> = emptyMap()
    ) : CommentRepository {
        override val platform: CommentPlatform = CommentPlatform.NETEASE
        val likes = mutableListOf<Pair<String, Boolean>>()

        override suspend fun loadComments(
            source: CommentSource,
            page: Int,
            pageSize: Int,
            forceRefresh: Boolean,
            sort: CommentSort,
            cursor: String?
        ): CommentPage = CommentPage(roots, page, pageSize, total = roots.size.toLong(), hasMore = false)

        override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
            likes += commentId to liked
        }

        override suspend fun loadReplies(
            source: CommentSource,
            rootId: String,
            page: Int,
            pageSize: Int,
            cursor: String?
        ): CommentPage {
            val thread = replies[rootId].orEmpty()
            return CommentPage(thread, page, pageSize, total = thread.size.toLong(), hasMore = false)
        }

        override suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget?) = Unit
    }

    private companion object {
        val SOURCE = CommentSource(platform = CommentPlatform.NETEASE, resourceId = 1L, secondaryId = null)

        fun comment(
            id: String,
            previewReplies: List<SongComment> = emptyList(),
            rootId: String? = null
        ) = SongComment(
            id = id,
            userId = null,
            username = "u-$id",
            avatarUrl = null,
            content = "c-$id",
            likeCount = 0L,
            replyCount = null,
            createTime = null,
            platform = CommentPlatform.NETEASE,
            userLevel = null,
            previewReplies = previewReplies,
            rootId = rootId
        )
    }
}
