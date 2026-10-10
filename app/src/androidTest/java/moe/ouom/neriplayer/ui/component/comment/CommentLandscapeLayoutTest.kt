package moe.ouom.neriplayer.ui.component.comment

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CommentLandscapeLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sheetView: View

    @Before
    fun requireLandscapeHost() {
        assumeComposeHostAvailable()
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        assumeTrue("需要至少 720dp 宽的横屏宿主验证真实评论弹层",
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 720)
    }

    @Test
    fun landscapeCommentsUseWideSurfaceAndKeepListVisible() {
        renderComments(fontScale = 1f)
        val scale = composeRule.density.density
        val surface = composeRule.onNodeWithTag("comment-sheet-surface", useUnmergedTree = true)
        surface.assertIsDisplayed()
        assertTrue("横屏评论应突破竖屏弹层的 640dp 宽度限制",
            surface.fetchSemanticsNode().boundsInRoot.width / scale > 640f)
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        if (configuration.smallestScreenWidthDp >= 600) {
            val screenLocation = IntArray(2)
            val windowLocation = IntArray(2)
            var statusBarHeight = 0
            composeRule.runOnIdle {
                sheetView.getLocationOnScreen(screenLocation)
                sheetView.getLocationInWindow(windowLocation)
                statusBarHeight = ViewCompat.getRootWindowInsets(sheetView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            }
            // 使用定位后的内容节点，外层容器标签位于弹层的位移修饰器之前
            val contentTop = composeRule.onNodeWithTag("comment-sheet-content", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInWindow.top
            val top = (contentTop +
                screenLocation[1] - windowLocation[1] - statusBarHeight) / scale
            assertTrue("平板评论面板与状态栏之间应留至少 24dp，实际 ${top}dp",
                top >= 23f)
        }
        assertVisibleListSpace()
        composeRule.onNodeWithText(commentText(1), useUnmergedTree = true).assertIsDisplayed()
        capture("landscape-comments")
    }

    @Test
    fun landscapeLargeFontKeepsDraftAndComposerFixedWhileScrolling() {
        renderComments(fontScale = 1.3f, draft = DRAFT)
        assertVisibleListSpace()
        val composer = composeRule.onNodeWithTag("comment-composer", useUnmergedTree = true)
        val before = composer.fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("comment-send").assertIsDisplayed().assertIsEnabled()
        assertEquals(DRAFT, composeRule.onNodeWithTag("comment-draft").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        composeRule.onNodeWithTag("comment-list").performScrollToIndex(6)
        assertEquals(before, composer.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithText(commentText(7), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("comment-send").assertIsDisplayed().assertIsEnabled()
        capture("landscape-comments-large-font")
    }

    private fun assertVisibleListSpace() {
        val panel = composeRule.onNodeWithTag("comment-sheet-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag("comment-header", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("comment-list-viewport", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val composer = composeRule.onNodeWithTag("comment-composer", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("评论列表应占至少 45% 的面板高度", list.height >= panel.height * 0.45f)
        assertTrue("标题和输入框应分别位于列表上下方", header.bottom <= list.top && list.bottom <= composer.top)
        composeRule.onNodeWithTag("comment-draft").assertIsDisplayed()
    }

    private fun renderComments(fontScale: Float, draft: String = "") {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    CommentSheetSurface(
                        onDismissRequest = {},
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ) {
                        val view = LocalView.current
                        SideEffect { sheetView = view }
                        CommentSheetContent(
                            ui = sampleState(draft), offlineMode = false,
                            onRefresh = {}, onRetry = {}, onLoadMore = {}, onSort = {},
                            onLike = {}, onDismissLikeError = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun sampleState(draft: String) = CommentUiState(
        source = CommentSource(CommentPlatform.NETEASE, 42L),
        status = CommentListStatus.SUCCESS,
        total = 12L,
        hasMore = false,
        draft = draft,
        comments = (1..12).map { index ->
            SongComment(id = index.toString(), userId = null, username = "听歌的人 $index",
                avatarUrl = null, content = commentText(index), likeCount = 0L, replyCount = 0L,
                createTime = null, platform = CommentPlatform.NETEASE, userLevel = null)
        }
    )

    private fun commentText(index: Int) = "横屏评论 $index：戴上耳机，留一点空间给音乐和此刻的心情。"

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() } ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DRAFT = "保留多行草稿\n第二行仍然存在\n第三行可以继续编辑"
    }
}
