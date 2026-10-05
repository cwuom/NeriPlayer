package moe.ouom.neriplayer.ui.screen

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsDraft
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsEditorOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsEditorLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val width = mutableStateOf(840.dp)
    private val height = mutableStateOf(360.dp)
    private lateinit var owner: NowPlayingLyricsEditorOwner
    private lateinit var focusManager: FocusManager
    private var keyboardController: SoftwareKeyboardController? = null
    private val savedDrafts = mutableListOf<EditSongLyricsDraft>()
    private var matches = 0
    private var cancels = 0
    private var saved = 0
    private var parentMotionCount = 0

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun shortLandscapeKeepsAllTabsEditableAndEveryActionReachable() {
        render(840.dp, 360.dp, 1.3f)
        assertEditorHeight()
        editEveryTabAndDispatchActions()
        capture("lyrics-editor-840x360")
    }

    @Test
    fun veryShortLandscapeScrollsEditorAndToolbarWithoutMovingParentSheet() {
        render(640.dp, 240.dp, 1.5f)
        assertTrue(outerScrollRange().maxValue() > 0f)
        scrollTo("lyrics-editor-input")
        assertEditorHeight()
        val parentBounds = composeRule.onNodeWithTag("lyrics-editor-fixture").getUnclippedBoundsInRoot()
        editEveryTabAndDispatchActions()
        scrollTo("lyrics-editor-header")
        composeRule.onNodeWithTag("lyrics-editor-content").performTouchInput { swipeDown() }
        assertEquals(parentBounds, composeRule.onNodeWithTag("lyrics-editor-fixture").getUnclippedBoundsInRoot())
        composeRule.runOnIdle { assertEquals("歌词滚动不能拖动父sheet", 0, parentMotionCount) }
        capture("lyrics-editor-640x240")
    }

    @Test
    fun keyboardSizedViewportAndRotationRetainDraftAndSelectedRomanizedTab() {
        render(840.dp, 360.dp, 1.3f)
        scrollTo("lyrics-editor-tab-2").performClick()
        scrollTo("lyrics-editor-input").performTextReplacement("键盘打开前的罗马字草稿")
        clearFocus()
        composeRule.runOnIdle { height.value = 180.dp }
        composeRule.waitForIdle()
        assertTrue(outerScrollRange().maxValue() > 0f)
        scrollTo("lyrics-editor-input")
        assertEditorHeight()
        assertEquals("键盘打开前的罗马字草稿", editableText())
        scrollTo("lyrics-editor-input").performTextReplacement("键盘打开后的罗马字草稿")
        clearFocus()
        listOf("lyrics-editor-match", "lyrics-editor-cancel", "lyrics-editor-clear",
            "lyrics-editor-paste", "lyrics-editor-save").forEach { scrollTo(it).assertIsEnabled() }
        capture("lyrics-editor-keyboard-budget")

        composeRule.runOnIdle {
            width.value = 360.dp
            height.value = 840.dp
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-editor-song").assertIsDisplayed()
        assertEquals("键盘打开后的罗马字草稿", editableText())
        composeRule.runOnIdle {
            assertEquals(2, owner.selectedTabState.intValue)
            assertEquals(LongOriginal, owner.lyricsTextState.value)
            assertEquals("初始译文", owner.translatedLyricsTextState.value)
        }
        composeRule.onNodeWithTag("lyrics-editor-save").assertIsDisplayed().performClick()
        composeRule.waitUntil { saved == 1 }
        composeRule.runOnIdle {
            assertEquals(EditSongLyricsDraft(LongOriginal, "初始译文", "键盘打开后的罗马字草稿", false), savedDrafts.single())
        }
    }

    @Test
    fun keyboardHeightChangesKeepLyricsFocusedWhenSongInfoAndScrollingChromeChange() {
        render(840.dp, 640.dp, 1.3f)
        composeRule.onNodeWithTag("lyrics-editor-song").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-editor-tab-2").performClick()
        val draft = "歌词输入区跨高度阈值后保留的罗马字草稿"
        val input = composeRule.onNodeWithTag("lyrics-editor-input")
        input.assertIsDisplayed().performClick().performTextReplacement(draft)
        input.assertIsFocused()

        for (availableHeight in listOf(360.dp, 180.dp, 640.dp)) {
            composeRule.runOnIdle { height.value = availableHeight }
            composeRule.waitForIdle()
            // 切换紧凑标题及整页滚动时，不重新获取焦点掩盖输入节点被重建的问题
            input.assertIsFocused()
            assertEquals(draft, editableText())
            composeRule.runOnIdle {
                assertEquals(2, owner.selectedTabState.intValue)
                assertEquals(draft, owner.romanizedLyricsTextState.value)
                assertEquals(LongOriginal, owner.lyricsTextState.value)
                assertEquals("初始译文", owner.translatedLyricsTextState.value)
            }
        }

        composeRule.onNodeWithTag("lyrics-editor-song").assertIsDisplayed()
        input.assertIsDisplayed().assertIsFocused().performTextReplacement("恢复高度后继续歌词编辑")
        input.assertIsFocused()
        assertEquals("恢复高度后继续歌词编辑", editableText())
    }

    @Test
    fun portraitPhoneLongLyricsScrollInsideInputWhileHeaderAndActionsStayFixed() {
        render(360.dp, 840.dp, 1.3f)
        assertFixedChromeAndInnerTextScroll()
        editEveryTabAndDispatchActions()
        capture("lyrics-editor-phone-portrait")
    }

    @Test
    fun portraitTabletKeepsContentCenteredAndLongLyricsScrollableAtLargeFont() {
        render(800.dp, 1280.dp, 1.5f)
        assertContentMaxWidth()
        assertFixedChromeAndInnerTextScroll()
        editEveryTabAndDispatchActions()
        capture("lyrics-editor-tablet-portrait")
    }

    @Test
    fun landscapeTabletKeepsInputAndToolbarAvailable() {
        render(1280.dp, 800.dp, 1.3f)
        assertContentMaxWidth()
        assertFixedChromeAndInnerTextScroll()
        editEveryTabAndDispatchActions()
        capture("lyrics-editor-tablet-landscape")
    }

    private fun editEveryTabAndDispatchActions() {
        val drafts = listOf("原文编辑后", "翻译编辑后", "罗马字编辑后")
        drafts.forEachIndexed { index, text ->
            scrollTo("lyrics-editor-tab-$index").performClick()
            scrollTo("lyrics-editor-input").performTextReplacement(text)
            clearFocus()
            scrollTo("lyrics-editor-clear").performClick()
            scrollTo("lyrics-editor-input")
            assertEquals("", editableText())
            scrollTo("lyrics-editor-paste").performClick()
            scrollTo("lyrics-editor-input")
            assertEquals("粘贴到标签$index", editableText())
            composeRule.runOnIdle {
                assertEquals((0..index).map { "粘贴到标签$it" }, listOf(
                    owner.lyricsTextState.value, owner.translatedLyricsTextState.value,
                    owner.romanizedLyricsTextState.value
                ).take(index + 1))
            }
        }
        scrollTo("lyrics-editor-match").performClick()
        scrollTo("lyrics-editor-cancel").performClick()
        scrollTo("lyrics-editor-save").performClick()
        composeRule.waitUntil { saved == 1 }
        composeRule.runOnIdle {
            assertEquals(1, matches)
            assertEquals(1, cancels)
            assertEquals(EditSongLyricsDraft("粘贴到标签0", "粘贴到标签1", "粘贴到标签2", false), savedDrafts.single())
        }
    }

    private fun assertFixedChromeAndInnerTextScroll() {
        assertEditorHeight()
        val header = composeRule.onNodeWithTag("lyrics-editor-header").getUnclippedBoundsInRoot()
        val actions = composeRule.onNodeWithTag("lyrics-editor-actions").getUnclippedBoundsInRoot()
        val field = composeRule.onNodeWithTag("lyrics-editor-input")
        val before = field.captureToImage().toPixelMap()
        field.performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val after = field.captureToImage().toPixelMap()
        var changedPixels = 0
        for (y in before.height / 5 until before.height * 4 / 5) {
            for (x in before.width / 5 until before.width * 4 / 5) {
                val a = before[x, y]
                val b = after[x, y]
                if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.1f) changedPixels++
            }
        }
        assertTrue("长歌词拖动后输入框中的文本应滚动", changedPixels > before.width * before.height / 200)
        assertEquals(header, composeRule.onNodeWithTag("lyrics-editor-header").getUnclippedBoundsInRoot())
        assertEquals(actions, composeRule.onNodeWithTag("lyrics-editor-actions").getUnclippedBoundsInRoot())
        assertEquals(LongOriginal, editableText())
        clearFocus()
        composeRule.runOnIdle { assertEquals("文本滚动不能拖动父sheet", 0, parentMotionCount) }
    }

    private fun assertEditorHeight() {
        val field = composeRule.onNodeWithTag("lyrics-editor-input").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("lyrics-editor-fixture").getUnclippedBoundsInRoot()
        assertTrue("输入区应至少保留128dp供两行以上歌词编辑", (field.bottom - field.top).value / (viewport.bottom - viewport.top).value >=
            128f / height.value.value - 0.01f)
        composeRule.onNodeWithTag("lyrics-editor-input").assertIsDisplayed().assertIsEnabled()
    }

    private fun assertContentMaxWidth() {
        val viewport = composeRule.onNodeWithTag("lyrics-editor-fixture").getUnclippedBoundsInRoot()
        val content = composeRule.onNodeWithTag("lyrics-editor-content").getUnclippedBoundsInRoot()
        assertTrue((content.right - content.left).value / (viewport.right - viewport.left).value <= 720f / width.value.value + 0.01f)
        val viewportNode = composeRule.onNodeWithTag("lyrics-editor-fixture").fetchSemanticsNode()
        val contentNode = composeRule.onNodeWithTag("lyrics-editor-content").fetchSemanticsNode()
        // 虚拟密度会放大亚像素舍入，居中误差按真实像素检查
        assertEquals(viewportNode.positionInRoot.x + viewportNode.size.width / 2f,
            contentNode.positionInRoot.x + contentNode.size.width / 2f, 1f)
    }

    private fun outerScrollRange() = composeRule.onNodeWithTag("lyrics-editor-content")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun scrollTo(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val node = composeRule.onNodeWithTag(tag)
        var ancestor = node.fetchSemanticsNode().parent
        while (ancestor != null && !ancestor.config.contains(SemanticsActions.ScrollBy)) ancestor = ancestor.parent
        if (ancestor != null) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun editableText() = composeRule.onNodeWithTag("lyrics-editor-input").fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    private fun clearFocus() {
        composeRule.runOnIdle {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        composeRule.waitForIdle()
    }

    private fun render(initialWidth: Dp, initialHeight: Dp, fontScale: Float) {
        width.value = initialWidth
        height.value = initialHeight
        composeRule.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val song = remember { SongItem(1L, "夜航星", "不才", "专辑A", 10L, 222_000L, null) }
                owner = remember {
                    NowPlayingLyricsEditorOwner(song, LongOriginal, "初始译文", "初始罗马字", scope, { emptyList() })
                }
                DisposableEffect(owner) { onDispose(owner::dispose) }
                val currentFocusManager = LocalFocusManager.current
                val currentKeyboardController = LocalSoftwareKeyboardController.current
                SideEffect {
                    focusManager = currentFocusManager
                    keyboardController = currentKeyboardController
                }
                FittedTestViewport(width.value, height.value, fontScale = fontScale, layoutOnlyTextInput = true) {
                    val parentConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(consumed: Offset, available: Offset,
                                source: NestedScrollSource): Offset {
                                if (available.y != 0f) parentMotionCount++
                                return available
                            }
                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                if (available.y != 0f) parentMotionCount++
                                return available
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Box(
                        Modifier.testTag("lyrics-editor-fixture")
                            .nestedScroll(parentConnection)
                    ) {
                        LyricsEditorContent(song, owner,
                            onDismiss = { cancels++ },
                            onMatch = { matches++ },
                            onPaste = { owner.pasteSelectedText("粘贴到标签${owner.selectedTabState.intValue}") },
                            onSave = {
                                owner.save(false, { draft -> savedDrafts += draft; true }, {}, { saved++ })
                            })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

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
        val LongOriginal = (1..100).joinToString("\n") { index ->
            "[$index] " + when (index % 3) {
                0 -> "海阔天空，继续聆听这一行歌词"
                1 -> "IIIIIIIIIIIIIIIIIIIIIIIIII"
                else -> "WWWWWWWWWWWWWWWWWWWW"
            }
        }
    }
}
