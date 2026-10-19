package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.platform.comments.CommentApiException
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SongEncyclopediaCommentPreviewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private lateinit var viewModel: CommentViewModel

    @Before
    fun prepare() {
        assumeComposeHostAvailable()
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(owner.viewModelStore, ViewModelProvider.NewInstanceFactory())[
                "encyclopedia_comment_preview", CommentViewModel::class.java
            ]
        }
    }

    @After
    fun clearFixture() {
        instrumentation.runOnMainSync { owner.viewModelStore.clear() }
    }

    @Test
    fun previewOmitsReplyAndThreadActions() {
        render(PreviewRepository(listOf(comment("1", replyCount = 6L), comment("2"))))

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.comment_reply_count_format, "6"))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.comment_reply)).assertDoesNotExist()
    }

    @Test
    fun longPressPreviewOffersCopyWithoutReply() {
        render(PreviewRepository(listOf(comment("1"))))

        composeRule.onNodeWithText("preview-comment-1", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.comment_copy)).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.comment_reply)).assertDoesNotExist()
    }

    @Test
    fun likeUsesViewModelAndDisablesOnlyWhileRequestIsPending() {
        val completion = CompletableDeferred<Unit>()
        val repository = PreviewRepository(listOf(comment("1")), likeCompletion = completion)
        render(repository)

        val like = composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.comment_like))
        like.assertIsEnabled().performClick()
        composeRule.waitUntil { viewModel.uiState.value.likingIds == setOf("1") }
        like.assertIsNotEnabled()
        assertEquals(listOf(LikeRequest(SOURCE, "1", true)), repository.likes)

        completion.complete(Unit)
        composeRule.waitUntil {
            viewModel.uiState.value.comments.single().isLiked && viewModel.uiState.value.likingIds.isEmpty()
        }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.comment_unlike))
            .assertIsEnabled()
        composeRule.onNodeWithText("4").assertExists()
    }

    @Test
    fun offlinePreviewKeepsLikeDisabled() {
        val repository = PreviewRepository(listOf(comment("1")))
        render(repository, offlineMode = true)

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.comment_like))
            .assertIsNotEnabled()
        assertTrue(repository.likes.isEmpty())
    }

    @Test
    fun loginRequiredLikeFailureIsVisibleAndKeepsTheCommentUnchanged() {
        val repository = PreviewRepository(
            listOf(comment("1")),
            likeFailure = CommentApiException(301, CommentError.PERMISSION, "login required")
        )
        render(repository)

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.comment_like))
            .assertIsEnabled().performClick()
        composeRule.waitUntil { viewModel.uiState.value.likeError == CommentError.PERMISSION }
        val message = context.getString(CoreCommonR.string.comment_like_login_required)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.comment_error_code_format, message, 301))
            .assertExists()
        composeRule.onNodeWithText("3").assertExists()
    }

    private fun render(repository: PreviewRepository, offlineMode: Boolean = false) {
        instrumentation.runOnMainSync { viewModel.repositoryFactory = { repository } }
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SongEncyclopediaCommentPreview(SOURCE, offlineMode, onOpenAll = {})
                    }
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            viewModel.uiState.value.status == CommentListStatus.SUCCESS
        }
    }

    private data class LikeRequest(val source: CommentSource, val commentId: String, val liked: Boolean)

    private class PreviewRepository(
        private val comments: List<SongComment>,
        private val likeCompletion: CompletableDeferred<Unit>? = null,
        private val likeFailure: CommentApiException? = null
    ) : CommentRepository {
        override val platform = CommentPlatform.NETEASE
        val likes = CopyOnWriteArrayList<LikeRequest>()

        override suspend fun loadComments(
            source: CommentSource,
            page: Int,
            pageSize: Int,
            forceRefresh: Boolean,
            sort: CommentSort,
            cursor: String?
        ) = CommentPage(comments, page, pageSize, total = comments.size.toLong(), hasMore = false)

        override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
            likes += LikeRequest(source, commentId, liked)
            likeCompletion?.await()
            likeFailure?.let { throw it }
        }

        override suspend fun loadReplies(
            source: CommentSource,
            rootId: String,
            page: Int,
            pageSize: Int,
            cursor: String?
        ): CommentPage = error("encyclopedia preview must not load reply threads")

        override suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget?) =
            error("encyclopedia preview must not send comments")
    }

    private companion object {
        val SOURCE = CommentSource(platform = CommentPlatform.NETEASE, resourceId = 1L)

        fun comment(id: String, replyCount: Long? = null) = SongComment(
            id = id,
            userId = null,
            username = "preview-user-$id",
            avatarUrl = null,
            content = "preview-comment-$id",
            likeCount = 3L,
            replyCount = replyCount,
            createTime = null,
            platform = CommentPlatform.NETEASE,
            userLevel = null
        )
    }
}
