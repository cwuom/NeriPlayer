package moe.ouom.neriplayer.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongActionRow
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongCoverPreview
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongCoverPreviewState
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongEditableTextField
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongInfoLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsButton
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldUseCompactEditSongLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingSongEditLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val width = mutableStateOf(840.dp)
    private val height = mutableStateOf(360.dp)
    private val title = mutableStateOf("未保存的歌曲标题")
    private val artist = mutableStateOf("未保存的艺术家")
    private var parentMotionCount = 0
    private var coverClicks = 0
    private var saves = 0
    private var restores = 0
    private var searches = 0
    private var lyricClicks = 0
    private var cancels = 0
    private lateinit var focusManager: FocusManager
    private var keyboardController: SoftwareKeyboardController? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun shortLandscapeSeparatesFullCoverFromScrollableFieldsAndKeepsActionsFixed() {
        render(840.dp, 360.dp, fontScale = 1.3f)
        assertCompactLayoutAndScroll()
        assertActionsAndCallbacks()
        capture("song-edit-840x360")
    }

    @Test
    fun narrowLandscapeLargeFontKeepsEachFieldFullyReachable() {
        render(540.dp, 300.dp, fontScale = 1.3f)
        assertCompactLayoutAndScroll()
        assertActionsAndCallbacks()
        capture("song-edit-540x300")
    }

    @Test
    fun veryShortLandscapeMovesHeaderIntoScrollWithoutClippingTheCoverOrSaveAction() {
        render(640.dp, 240.dp, fontScale = 1.5f)
        val layout = composeRule.onNodeWithTag("song-edit-layout").getUnclippedBoundsInRoot()
        val availableHeight = layout.bottom - layout.top
        assertTrue("扣除把手与系统边距后应进入标题滚动模式", availableHeight < 240.dp)
        val header = composeRule.onNodeWithTag("song-edit-header").performScrollTo().assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val fields = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
        assertTrue("标题应能完整滚到表单可见区域", header.top >= fields.top - 1.dp && header.bottom <= fields.bottom + 1.dp)
        // 可用高度受宿主系统边距影响，只有低于160dp时操作区才随表单滚动
        val actionsScrollable = availableHeight < 160.dp
        assertCompactLayoutAndScroll(actionsScrollable = actionsScrollable)
        assertActionsAndCallbacks(actionsScrollable = actionsScrollable)
        capture("song-edit-640x240")
    }

    @Test
    fun keyboardRemainingHeightKeepsFieldsAndScrollableActionsFullyReachable() {
        // 只模拟 Sheet 在键盘和系统边距之后交给编辑页的剩余高度
        render(840.dp, 144.dp, fontScale = 1.3f, keyboardRemaining = true)
        composeRule.onNodeWithTag("fixtureHandle").assertDoesNotExist()
        assertCompactLayoutAndScroll(actionsScrollable = true, handlePresent = false)
        assertActionsAndCallbacks(actionsScrollable = true)
        val cancel = composeRule.onNodeWithText("Cancel").performScrollTo()
        val cancelBounds = cancel.getUnclippedBoundsInRoot()
        val fields = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
        assertTrue("取消操作也应能完整滚到可见区域", cancelBounds.top >= fields.top - 1.dp &&
            cancelBounds.bottom <= fields.bottom + 1.dp)
        cancel.assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, cancels) }
        capture("song-edit-keyboard-remaining")
    }

    @Test
    fun keyboardHeightChangesKeepTitleFocusedWhileHeaderAndActionsMoveIntoTheForm() {
        render(840.dp, 400.dp, fontScale = 1.3f, keyboardRemaining = true)
        val draft = "键盘缩小视窗时保留焦点的歌曲草稿"
        val field = composeRule.onNodeWithTag("fixtureTitle")
        field.performScrollTo().assertIsDisplayed().performClick().performTextReplacement(draft)
        field.assertIsFocused()

        for (availableHeight in listOf(220.dp, 150.dp, 400.dp)) {
            composeRule.runOnIdle { height.value = availableHeight }
            composeRule.waitForIdle()
            // 不主动清焦点或再次点击输入框，直接检查布局重排后的原输入会话
            field.assertIsFocused()
            assertEquals(draft, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            composeRule.runOnIdle { assertEquals(draft, title.value) }
        }

        field.performScrollTo().assertIsDisplayed().assertIsFocused()
            .performTextReplacement("恢复高度后继续编辑")
        field.assertIsFocused()
        composeRule.runOnIdle { assertEquals("恢复高度后继续编辑", title.value) }
    }

    @Test
    fun tabletPortraitKeepsFullCoverDraftEditingAndActionsReachable() {
        render(800.dp, 1280.dp, fontScale = 1.3f, smallestScreenWidthDp = 800)
        assertTabletLayoutAndEditing()
        capture("song-edit-tablet-portrait")
    }

    @Test
    fun tabletLandscapeKeepsFullCoverDraftEditingAndActionsReachable() {
        render(1280.dp, 800.dp, fontScale = 1.3f, smallestScreenWidthDp = 800)
        assertTabletLayoutAndEditing()
        capture("song-edit-tablet-landscape")
    }

    @Test
    fun rotationKeepsDraftAndPortraitCoverSizeAndOrdering() {
        render(840.dp, 360.dp, fontScale = 1.3f)
        composeRule.onNodeWithTag("fixtureTitle").performScrollTo().performTextReplacement("保留旋转前草稿")
        composeRule.runOnIdle {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            width.value = 360.dp
            height.value = 840.dp
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("song-edit-cover-pane").assertDoesNotExist()
        val cover = composeRule.onNodeWithTag("song-edit-cover-preview").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("fixtureEditViewport").getUnclippedBoundsInRoot()
        assertEquals((cover.right - cover.left).value, (cover.bottom - cover.top).value, 0.001f)
        assertEquals(120f / 360f, (cover.right - cover.left).value / (viewport.right - viewport.left).value, 0.01f)
        val actions = composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("fixtureTitle").performScrollTo().assertIsDisplayed()
        assertEquals("保留旋转前草稿", composeRule.onNodeWithTag("fixtureTitle").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        composeRule.onNodeWithTag("fixtureArtist").performScrollTo().assertIsDisplayed()
        assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
        capture("song-edit-portrait")
    }

    private fun assertTabletLayoutAndEditing() {
        composeRule.onNodeWithTag("song-edit-cover-pane").assertDoesNotExist()
        composeRule.onNodeWithTag("song-edit-cover-preview").performScrollTo().assertIsDisplayed()
        val cover = composeRule.onNodeWithTag("song-edit-cover-preview").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("fixtureEditViewport").getUnclippedBoundsInRoot()
        assertEquals((cover.right - cover.left).value, (cover.bottom - cover.top).value, 0.001f)
        assertEquals(120f / width.value.value,
            (cover.right - cover.left).value / (viewport.right - viewport.left).value, 0.01f)
        val fields = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
        assertTrue("平板保留完整120dp封面", cover.top >= fields.top - 1.dp && cover.bottom <= fields.bottom + 1.dp)
        val actions = composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot()
        assertTrue("操作区应完整留在可见窗口内", actions.top >= viewport.top && actions.bottom <= viewport.bottom)
        assertTrue("操作区应在表单滚动区下方", actions.top >= fields.bottom - 1.dp)

        listOf("fixtureTitle" to "平板歌曲草稿", "fixtureArtist" to "平板艺术家草稿").forEach { (tag, draft) ->
            val field = composeRule.onNodeWithTag(tag).performScrollTo()
            val fieldBounds = field.getUnclippedBoundsInRoot()
            val fieldViewport = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
            assertTrue("$tag 应完整显示后再编辑", fieldBounds.top >= fieldViewport.top - 1.dp &&
                fieldBounds.bottom <= fieldViewport.bottom + 1.dp)
            field.assertIsDisplayed().assertIsEnabled().performTextReplacement(draft)
            assertEquals(draft, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        composeRule.runOnIdle {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        composeRule.waitForIdle()
        val lyrics = composeRule.onNodeWithText(resource(CoreCommonR.string.music_edit_lyrics)).performScrollTo()
        lyrics.assertIsDisplayed().assertIsEnabled()
        val lyricsBounds = lyrics.getUnclippedBoundsInRoot()
        val fieldsAfterEditing = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
        assertTrue("歌词入口应能完整滚到可见区域", lyricsBounds.top >= fieldsAfterEditing.top - 1.dp &&
            lyricsBounds.bottom <= fieldsAfterEditing.bottom + 1.dp)
        assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeDown() }
        assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
        assertActionsAndCallbacks(coverScrollable = true)
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, cancels) }
        assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
    }

    private fun assertCompactLayoutAndScroll(actionsScrollable: Boolean = false, handlePresent: Boolean = true) {
        val cover = composeRule.onNodeWithTag("song-edit-cover-preview").getUnclippedBoundsInRoot()
        val coverPane = composeRule.onNodeWithTag("song-edit-cover-pane").getUnclippedBoundsInRoot()
        val fields = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("fixtureEditViewport").getUnclippedBoundsInRoot()
        assertTrue(cover.right < fields.left)
        assertEquals((cover.right - cover.left).value, (cover.bottom - cover.top).value, 0.001f)
        assertTrue("封面应完整留在左栏", cover.top >= coverPane.top && cover.bottom <= coverPane.bottom)
        assertTrue("短横屏封面应小于竖屏120dp", (cover.right - cover.left).value / (viewport.right - viewport.left).value <= 96f / width.value.value + 0.01f)
        val actions = if (actionsScrollable) null else
            composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot()
        val header = if (handlePresent) composeRule.onNodeWithTag("fixtureHandle").getUnclippedBoundsInRoot() else null
        listOf("fixtureCoverUrl", "fixtureTitle", "fixtureArtist").forEach { tag ->
            val field = composeRule.onNodeWithTag(tag).performScrollTo()
            field.assertIsDisplayed().assertIsEnabled()
            val visibleField = field.getUnclippedBoundsInRoot()
            val fieldViewport = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
            assertTrue("$tag 应完整可见", visibleField.top >= fieldViewport.top - 1.dp &&
                visibleField.bottom <= fieldViewport.bottom + 1.dp)
            if (actions != null) {
                assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
            }
            assertEquals(cover, composeRule.onNodeWithTag("song-edit-cover-preview").getUnclippedBoundsInRoot())
        }
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeDown() }
        composeRule.onNodeWithTag("fixtureCoverUrl").performScrollTo()
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeDown() }
        if (header != null) {
            assertEquals(header, composeRule.onNodeWithTag("fixtureHandle").getUnclippedBoundsInRoot())
        }
        if (actions != null) {
            assertEquals(actions, composeRule.onNodeWithTag("song-edit-actions").getUnclippedBoundsInRoot())
        }
        composeRule.runOnIdle { assertEquals("表单滚动不能交给父sheet", 0, parentMotionCount) }
    }

    private fun assertActionsAndCallbacks(actionsScrollable: Boolean = false, coverScrollable: Boolean = false) {
        val cover = composeRule.onNodeWithTag("song-edit-cover-preview")
        if (coverScrollable) cover.performScrollTo()
        cover.performClick()
        composeRule.onNodeWithTag("fixtureTitle").performScrollTo()
        composeRule.onNodeWithContentDescription("Restore title").performClick()
        composeRule.onNodeWithText(resource(CoreCommonR.string.music_edit_lyrics)).performScrollTo().performClick()
        listOf(CoreCommonR.string.music_auto_fill, CoreCommonR.string.music_restore_original,
            CoreCommonR.string.music_save_changes).forEach { actionId ->
            val action = composeRule.onNodeWithText(resource(actionId))
            if (actionsScrollable) {
                action.performScrollTo()
                val bounds = action.getUnclippedBoundsInRoot()
                val viewport = composeRule.onNodeWithTag("song-edit-fields").getUnclippedBoundsInRoot()
                assertTrue("每个操作都应能完整滚到可见区域", bounds.top >= viewport.top - 1.dp &&
                    bounds.bottom <= viewport.bottom + 1.dp)
            }
            action.assertIsDisplayed().assertIsEnabled().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(1, coverClicks)
            assertEquals(1, lyricClicks)
            assertEquals(1, searches)
            assertEquals(2, restores)
            assertEquals(1, saves)
        }
    }

    private fun render(
        initialWidth: Dp, initialHeight: Dp, fontScale: Float, keyboardRemaining: Boolean = false,
        smallestScreenWidthDp: Int = 360
    ) {
        width.value = initialWidth
        height.value = initialHeight
        composeRule.setContent {
            MaterialTheme {
                FittedTestViewport(width.value, height.value, fontScale = fontScale, layoutOnlyTextInput = true) {
                    val currentFocusManager = LocalFocusManager.current
                    val currentKeyboardController = LocalSoftwareKeyboardController.current
                    SideEffect {
                        focusManager = currentFocusManager
                        keyboardController = currentKeyboardController
                    }
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
                    Column(Modifier.testTag("fixtureEditViewport").nestedScroll(parentConnection)) {
                        if (!keyboardRemaining) {
                            Box(Modifier.fillMaxWidth().testTag("fixtureHandle"),
                                contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                        }
                        val scrollState = rememberScrollState()
                        EditSongInfoLayout(
                            compactLandscape = shouldUseCompactEditSongLayout(smallestScreenWidthDp,
                                width.value > height.value, height.value),
                            scrollState = scrollState,
                            header = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(resource(CoreCommonR.string.music_edit_info), Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium)
                                    HapticTextButton(onClick = { cancels++ }) { Text("Cancel") }
                                }
                            },
                            coverUrl = {
                                EditSongEditableTextField(
                                    value = "content://fixture/cover", onValueChange = {}, label = "Cover URL",
                                    restoreDescription = "Restore cover", enabled = true, onRestore = { restores++ },
                                    modifier = Modifier.testTag("fixtureCoverUrl")
                                )
                            },
                            coverPreview = { size ->
                                EditSongCoverPreview(EditSongCoverPreviewState("", true, true, true), size) { coverClicks++ }
                            },
                            fields = {
                                EditSongEditableTextField(
                                    value = title.value, onValueChange = { title.value = it }, label = "Title",
                                    restoreDescription = "Restore title", enabled = true, onRestore = { restores++ },
                                    modifier = Modifier.testTag("fixtureTitle")
                                )
                                EditSongEditableTextField(
                                    value = artist.value, onValueChange = { artist.value = it }, label = "Artist",
                                    restoreDescription = "Restore artist", enabled = true, onRestore = { restores++ },
                                    modifier = Modifier.testTag("fixtureArtist")
                                )
                                EditSongLyricsButton(false, true) { lyricClicks++ }
                            },
                            actions = {
                                EditSongActionRow(false, false, false,
                                    onSearch = { searches++ }, onRestoreAll = { restores++ }, onSave = { saves++ })
                            }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun resource(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

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
}
