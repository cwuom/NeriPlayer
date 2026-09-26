package moe.ouom.neriplayer.ui.component.comment

import android.graphics.Bitmap
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
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
import moe.ouom.neriplayer.core.comment.model.CommentQuote
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState
import moe.ouom.neriplayer.ui.viewmodel.CommentReplyState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
    fun longPressHighlightDoesNotCoverCardCorners() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).background(Color.White)) {
                    CommentItem(
                        comment = sampleState().comments.single(), floor = 1, offlineMode = false,
                        isLiking = false, likeEnabled = true, onLike = {}, onReply = {}, onToggleReplies = {},
                        repliesExpanded = false, hasReplyThread = false, replyEnabled = true,
                        modifier = Modifier.testTag("highlight-card")
                    )
                }
            }
        }
        val card = composeRule.onNodeWithTag("highlight-card")
        val before = card.captureToImage().asAndroidBitmap().getPixel(2, 2)
        card.performTouchInput { longClick(Offset(width / 2f, 24f)) }
        composeRule.waitForIdle()
        val after = card.captureToImage().asAndroidBitmap()
        File(context.cacheDir, "comment-long-press.png").outputStream().use {
            after.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals(before, after.getPixel(2, 2))
    }

    @Test
    fun openingWithRetainedReplyDoesNotFocusEditorButExplicitReplyDoes() = withSoftwareKeyboard {
        val state = mutableStateOf(sampleState().copy(
            replyTarget = CommentReplyTarget("1", "1", "海边的风"), draft = "未发出的回复"
        ))
        val visible = mutableStateOf(true)
        val imeBottom = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                if (visible.value) {
                    ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                        SideEffect { imeBottom.set(bottom) }
                        CommentSheetContent(
                            ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                            onSort = {}, onLike = {}, onDismissLikeError = {},
                            onReply = { state.value = state.value.copy(replyTarget = it) }
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("comment-draft").assertIsNotFocused()
        composeRule.runOnIdle { assertEquals(0, imeBottom.get()) }
        composeRule.onNodeWithText(state.value.comments.single().content).performClick()
        composeRule.onNodeWithTag("comment-draft").assertIsNotFocused()
        composeRule.onNodeWithText(context.getString(R.string.comment_copy)).performClick()
        composeRule.onNodeWithText(state.value.comments.single().content).performTouchInput { longClick() }
        composeRule.onNodeWithText(context.getString(R.string.comment_reply)).performClick()
        composeRule.onNodeWithTag("comment-draft").assertIsFocused()
        composeRule.waitUntil(timeoutMillis = 5_000L) { imeBottom.get() > 0 }
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNodeWithTag("comment-draft").assertIsNotFocused()
        composeRule.waitUntil(timeoutMillis = 5_000L) { imeBottom.get() == 0 }
    }

    @Test
    fun longPressCopiesExactContentAndCanReplyToNestedComment() {
        val original = sampleState().comments.single()
        val child = original.copy(id = "2", content = "楼中楼正文\nSecond line", username = "回复者", rootId = "1")
        val state = mutableStateOf(sampleState().copy(comments = listOf(original.copy(
            quotedComments = listOf(CommentQuote("原作者", "被回复的内容")),
            previewReplies = listOf(child)
        ))))
        composeRule.setContent {
            MaterialTheme {
                CommentSheetContent(
                    ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                    onSort = {}, onLike = {}, onDismissLikeError = {},
                    onReply = { state.value = state.value.copy(replyTarget = it) }
                )
            }
        }
        composeRule.onNodeWithText("被回复的内容", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(child.content, useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.onNodeWithText(context.getString(R.string.comment_copy)).performClick()
        composeRule.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(child.content, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        composeRule.onNodeWithText(child.content, useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.onNodeWithText(context.getString(R.string.comment_reply)).performClick()
        composeRule.runOnIdle {
            assertEquals("2", state.value.replyTarget?.commentId)
            assertEquals("1", state.value.replyTarget?.rootId)
        }
        composeRule.onNodeWithText(context.getString(R.string.comment_reply_to, child.username)).assertIsDisplayed()
    }

    @Test
    fun replyThreadExpandsAndCollapsesWithoutLosingRoot() {
        val original = sampleState().comments.single()
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MaterialTheme {
                CommentSheetContent(
                    ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                    onSort = {}, onLike = {}, onDismissLikeError = {},
                    onToggleReplies = {
                        val thread = state.value.replyThreads[it]
                        state.value = state.value.copy(replyThreads = mapOf(it to (thread?.copy(expanded = !thread.expanded)
                            ?: CommentReplyState(comments = listOf(original.copy(id = "2", content = "完整回复")), hasMore = false))))
                    }
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.comment_reply_count_format, "6")).performClick()
        composeRule.onNodeWithText("完整回复", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.comment_collapse_replies)).performClick()
        composeRule.onNodeWithText("完整回复").assertDoesNotExist()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                comments = listOf(original.copy(replyCount = 0L)),
                replyThreads = mapOf("1" to CommentReplyState(expanded = false, hasMore = false))
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.comment_reply_count_format, "0")).performClick()
        composeRule.onNodeWithText(context.getString(R.string.comment_empty_replies)).assertExists()
    }

    @Test
    fun composerKeepsSendVisibleWithKeyboardAndDisablesItWhileSending() = withSoftwareKeyboard {
        val state = mutableStateOf(sampleState())
        val imeBottom = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect { imeBottom.set(bottom) }
                    Box(Modifier.testTag("comment-preview")) {
                        CommentSheetContent(
                            ui = state.value, offlineMode = false, onRefresh = {}, onRetry = {}, onLoadMore = {},
                            onSort = {}, onLike = {}, onDismissLikeError = {},
                            onDraft = { state.value = state.value.copy(draft = it) },
                            onSend = { state.value = state.value.copy(isSending = true) }
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("comment-send").assertIsNotEnabled()
        composeRule.onNodeWithTag("comment-draft").performClick().performTextInput("准备发送的评论")
        composeRule.waitUntil(timeoutMillis = 5_000L) { imeBottom.get() > 0 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("comment-send").assertIsDisplayed().assertIsEnabled()
        savePreview("comment-keyboard")
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "comment-keyboard-screen.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        composeRule.onNodeWithTag("comment-send").performClick().assertIsNotEnabled()
        composeRule.onNodeWithTag("comment-draft").assertIsNotEnabled()
    }

    private fun withSoftwareKeyboard(test: () -> Unit) {
        fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            }
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            test()
        } finally {
            if (previous == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previous")
        }
    }

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
                userLevel = 7, isLiked = index == 2,
                quotedComments = if (many && index == 1) listOf(CommentQuote("昨天的听众", "每次听到这里，都会想起那段日子。")) else emptyList()
            )
        }
    )
}
