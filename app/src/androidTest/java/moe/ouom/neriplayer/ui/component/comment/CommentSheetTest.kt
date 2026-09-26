package moe.ouom.neriplayer.ui.component.comment

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.SongComment
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CommentSheetTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() = assumeComposeHostAvailable()

    @Test
    fun sortingAndLikeActionsHaveAccessibleControlsAndFloors() {
        val state = mutableStateOf(sampleState())
        var likedId: String? = null
        composeRule.setContent {
            MaterialTheme {
                CommentSheetContent(
                    ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                    onSort = { state.value = state.value.copy(sort = it) },
                    onLike = { likedId = it; state.value = state.value.copy(likingIds = setOf(it)) },
                    onDismissLikeError = {}
                )
            }
        }
        composeRule.onNodeWithText("#1").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.comment_sort)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.comment_sort_newest)).performClick()
        composeRule.runOnIdle { assertEquals(CommentSort.NEWEST, state.value.sort) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.comment_like)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("1", likedId) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.comment_like)).assertIsNotEnabled()
    }

    @Test fun lightLayoutAndScrolledList() = renderPreview(dark = false, fontScale = 1f)
    @Test fun darkLayoutAtLargeFont() = renderPreview(dark = true, fontScale = 1.3f)

    @Test
    fun switchingSortKeepsCommentsAndSheetBoundsThroughLoadingFailureAndEmptyResult() {
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MaterialTheme {
                CommentSheetContent(
                    ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                    onSort = { state.value = state.value.copy(pendingSort = it) },
                    onLike = {}, onDismissLikeError = {}
                )
            }
        }
        val bounds = composeRule.onNodeWithTag("comment-sheet-content").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription(context.getString(R.string.comment_sort)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.comment_sort_newest)).performClick()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.onNodeWithText(sampleState().comments.single().content).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.comment_sort_loading)).assertIsDisplayed()
        assertEquals(bounds, composeRule.onNodeWithTag("comment-sheet-content").fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle { state.value = state.value.copy(pendingSort = null, error = CommentError.NETWORK) }
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        assertEquals(bounds, composeRule.onNodeWithTag("comment-sheet-content").fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle {
            state.value = state.value.copy(status = CommentListStatus.EMPTY, comments = emptyList(), error = null)
        }
        composeRule.onNodeWithText(context.getString(R.string.comment_empty)).assertIsDisplayed()
        assertEquals(bounds, composeRule.onNodeWithTag("comment-sheet-content").fetchSemanticsNode().boundsInRoot)
    }

    private fun renderPreview(dark: Boolean, fontScale: Float) {
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                ModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Box(Modifier.width(360.dp).testTag("comment-preview")) {
                            CommentSheetContent(
                                ui = sampleState(many = true), offlineMode = false,
                                onRefresh = {}, onRetry = {}, onLoadMore = {}, onSort = {}, onLike = {},
                                onDismissLikeError = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        savePreview(if (dark) "comment-dark-large-font" else "comment-light")
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("#1").assertIsNotDisplayed()
        savePreview(if (dark) "comment-dark-scrolled" else "comment-light-scrolled")
    }

    private fun savePreview(name: String) {
        val bitmap = composeRule.onNodeWithTag("comment-preview").captureToImage().asAndroidBitmap()
        File(context.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun sampleState(many: Boolean = false) = CommentUiState(
        source = CommentSource(CommentPlatform.NETEASE, 1L),
        status = CommentListStatus.SUCCESS,
        total = 128L,
        comments = (1..if (many) 12 else 1).map { index ->
            SongComment(
                id = index.toString(), userId = null,
                username = if (index == 1) "海边的风" else "听歌的人 $index",
                avatarUrl = null,
                content = if (index == 1) "前奏一响，就像又回到了那个夏天。戴上耳机，慢慢听完这一首。"
                    else "Some songs feel like a letter from an old friend. 音乐把很远的回忆带到了眼前。",
                likeCount = if (index == 1) 1234L else 28L, replyCount = 6L,
                createTime = 1758800000000L, platform = CommentPlatform.NETEASE,
                userLevel = 7, isLiked = index == 2
            )
        }
    )
}
