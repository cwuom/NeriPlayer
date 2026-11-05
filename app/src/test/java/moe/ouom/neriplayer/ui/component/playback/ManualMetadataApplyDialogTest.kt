package moe.ouom.neriplayer.ui.component.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyOptions
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.stringResId
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
class ManualMetadataApplyDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val originalFontScale = RuntimeEnvironment.getFontScale()
    private val dialogVisible = mutableStateOf(true)
    private var darkTheme = false

    @After
    fun restoreFontScale() {
        composeRule.runOnIdle { dialogVisible.value = false }
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            RuntimeEnvironment.setFontScale(originalFontScale)
        }
    }

    @Test fun `clean dialog keeps every choice reachable when saving in app`() = verifySelection(UiStyle.Clean, false)
    @Test fun `material dialog keeps every choice reachable when saving in app`() = verifySelection(UiStyle.Material, false)
    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `clean dialog confirms file writeback after destination selection`() = verifySelection(UiStyle.Clean, true)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `material dialog confirms file writeback after destination selection`() = verifySelection(UiStyle.Material, true)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `clean dark dialog preserves selection and fixed actions`() = verifySelection(UiStyle.Clean, false, true)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `material dark dialog preserves selection and fixed actions`() = verifySelection(UiStyle.Material, true, true)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `clean sources use aligned equal width columns`() = verifySourceColumns(UiStyle.Clean)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `material sources use aligned equal width columns`() = verifySourceColumns(UiStyle.Material)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `clean lyric choices have separate rows and a separate save group`() = verifyContentGroups(UiStyle.Clean)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-mdpi")
    fun `material lyric choices have separate rows and a separate save group`() = verifyContentGroups(UiStyle.Material)

    @Test
    @Config(qualifiers = "en-w320dp-h568dp-mdpi")
    fun `clean narrow large text choices stay complete with fixed actions`() = verifyLargeText(UiStyle.Clean)

    @Test
    @Config(qualifiers = "en-w320dp-h568dp-mdpi")
    fun `material narrow large text choices stay complete with fixed actions`() = verifyLargeText(UiStyle.Material)

    private fun verifySelection(style: UiStyle, writeLocalMetadata: Boolean, dark: Boolean = false) {
        var applied: SongMetadataApplyOptions? = null
        darkTheme = dark
        showOptions(style) { applied = it }
        composeRule.onNodeWithTag("metadata-apply-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("metadata-apply-cancel").assertIsDisplayed()
        listOf("metadata-apply-write", "metadata-apply-app-only").forEach { tag ->
            val choice = composeRule.onNodeWithTag(tag).assertIsDisplayed()
            val visible = choice.getBoundsInRoot()
            val full = choice.getUnclippedBoundsInRoot()
            assertEquals("destination must be fully visible before scrolling: $tag",
                (full.bottom - full.top).value, (visible.bottom - visible.top).value, 0.5f)
            assertEquals("destination must fit the dialog width: $tag",
                (full.right - full.left).value, (visible.right - visible.left).value, 0.5f)
        }
        capture(style, "sources")
        EditableLyricMatchSource.entries.forEach { source ->
            composeRule.onNodeWithTag("metadata-source-${source.name}").performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag("metadata-source-KUGOU").performScrollTo().performClick()
        composeRule.onNodeWithTag("metadata-content-original").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metadata-content-translated").performScrollTo().performClick()
        composeRule.onNodeWithTag("metadata-content-romanized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metadata-prefer-word-timed").performScrollTo().assertIsOn()
            .assertTextContains(composeRule.activity.getString(CoreCommonR.string.settings_prefer_word_timed_lyrics))
            .performClick()
        composeRule.onNodeWithTag("metadata-apply-app-only").assertIsDisplayed().performClick()
        if (writeLocalMetadata) {
            composeRule.onNodeWithTag("metadata-apply-write").assertIsDisplayed().performClick()
        }
        composeRule.runOnIdle { assertNull("choosing a destination must not apply metadata", applied) }
        composeRule.onNodeWithTag("metadata-apply-cancel").assertIsDisplayed()
        capture(style, "preferences")
        composeRule.onNodeWithTag("metadata-apply-confirm").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            val options = requireNotNull(applied)
            assertEquals(
                setOf(EditableLyricMatchSource.CLOUD_MUSIC, EditableLyricMatchSource.KUGOU),
                options.lyricSources
            )
            assertTrue(options.originalLyrics)
            assertFalse(options.translatedLyrics)
            assertTrue(options.romanizedLyrics)
            assertFalse(options.preferWordTimed)
            assertEquals(writeLocalMetadata, options.writeLocalMetadata)
        }
    }

    private fun showOptions(style: UiStyle, onApply: (SongMetadataApplyOptions) -> Unit = {}) {
        composeRule.setContent {
            if (!dialogVisible.value) return@setContent
            NeriTheme(followSystemDark = false, forceDark = darkTheme, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                val surfaceIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                SideEffect { assertEquals("render must use the requested appearance", darkTheme, surfaceIsDark) }
                ManualMetadataApplyOptionsDialog(
                    selectedSong = SongSearchInfo(
                        id = "42", songName = "Synthetic song", singer = "Synthetic artist", duration = "03:00",
                        albumName = "Synthetic album", coverUrl = null, source = MusicPlatform.CLOUD_MUSIC
                    ),
                    isLocalSong = true,
                    initialOptions = SongMetadataApplyOptions(
                        lyricSources = setOf(EditableLyricMatchSource.CLOUD_MUSIC)
                    ),
                    onDismiss = {},
                    onApply = onApply
                )
            }
        }
    }

    private fun verifySourceColumns(style: UiStyle) {
        showOptions(style)
        assertSourceColumns()
    }

    private fun assertSourceColumns() {
        val rows = EditableLyricMatchSource.entries.chunked(2).map { sources ->
            composeRule.onNodeWithTag("metadata-source-${sources.first().name}").performScrollTo()
            sources.map { source -> composeRule.onNodeWithTag("metadata-source-${source.name}").getUnclippedBoundsInRoot() }
        }
        rows.forEach { (left, right) ->
            assertEquals("source columns must have equal widths", (left.right - left.left).value, (right.right - right.left).value, 0.5f)
            assertEquals("source choices must align by row", left.top.value, right.top.value, 0.5f)
            assertEquals("source choices must share row height", left.bottom.value, right.bottom.value, 0.5f)
            assertTrue("source columns need a visible gap", right.left.value - left.right.value >= 7.5f)
        }
    }

    private fun verifyContentGroups(style: UiStyle) {
        showOptions(style)
        composeRule.onNodeWithTag("metadata-content-original").performScrollTo()
        val rows = listOf("original", "translated", "romanized").map { content ->
            composeRule.onNodeWithTag("metadata-content-$content").getUnclippedBoundsInRoot()
        }
        rows.zipWithNext().forEach { (previous, next) ->
            assertTrue("each lyric content choice needs its own row", next.top >= previous.bottom)
            assertEquals(previous.left.value, next.left.value, 0.5f)
            assertEquals(previous.right.value, next.right.value, 0.5f)
        }
        assertDestinationGroup()
    }

    private fun assertDestinationGroup() {
        val heading = composeRule.onNodeWithText(composeRule.activity.getString(AppR.string.metadata_apply_destination), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val write = composeRule.onNodeWithTag("metadata-apply-write").assertIsDisplayed().getUnclippedBoundsInRoot()
        val app = composeRule.onNodeWithTag("metadata-apply-app-only").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("save destination heading needs its own line and spacing", write.top.value - heading.bottom.value >= 7.5f)
        assertEquals("save destinations must share one row", write.top.value, app.top.value, 0.5f)
        assertEquals("save destinations must have equal widths", (write.right - write.left).value, (app.right - app.left).value, 0.5f)
    }

    private fun verifyLargeText(style: UiStyle) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { RuntimeEnvironment.setFontScale(1.5f) }
        showOptions(style)
        val actions = listOf("metadata-apply-confirm", "metadata-apply-cancel").associateWith { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        EditableLyricMatchSource.entries.forEach { source ->
            composeRule.onNodeWithTag("metadata-source-${source.name}").performScrollTo().assertIsDisplayed()
            assertCompleteLabel(composeRule.onNodeWithText(composeRule.activity.getString(source.stringResId()), useUnmergedTree = true))
        }
        listOf("metadata-content-original", "metadata-content-translated", "metadata-content-romanized", "metadata-prefer-word-timed").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        assertDestinationGroup()
        listOf(AppR.string.metadata_apply_destination_file, AppR.string.metadata_apply_destination_app).forEach { id ->
            assertCompleteLabel(composeRule.onNodeWithText(composeRule.activity.getString(id), useUnmergedTree = true))
        }
        actions.forEach { (tag, bounds) ->
            assertEquals("dialog actions must remain fixed while options scroll", bounds, composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot())
        }
        capture(style, "large-text")
    }

    private fun assertCompleteLabel(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1.5f, layout.layoutInput.density.fontScale, 0f)
        assertFalse("choice labels must not exceed their line limit", layout.multiParagraph.didExceedMaxLines)
        for (line in 0 until layout.lineCount) {
            assertFalse("choice labels must not be ellipsized", layout.isLineEllipsized(line))
            assertTrue("choice text must paint within its measured width", layout.getLineRight(line) <= layout.size.width + 1f)
        }
    }

    private fun capture(style: UiStyle, section: String) {
        val directory = System.getProperty("neriplayer.metadataScreenshotDir")
            ?: System.getenv("NERIPLAYER_METADATA_SCREENSHOT_DIR") ?: return
        val target = File(directory).apply { mkdirs() }
        val bitmap = composeRule.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val language = composeRule.activity.resources.configuration.locales[0].language
        val appearance = if (darkTheme) "dark" else "light"
        File(target, "metadata-options-${style.name.lowercase()}-$language-$appearance-$section-${bitmap.width}x${bitmap.height}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
