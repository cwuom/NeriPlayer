package moe.ouom.neriplayer.ui.component.overlay

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLocalMetadataWriteBackConfirmDialog
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w280dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanAlertDialogMultiActionLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val originalFontScale = RuntimeEnvironment.getFontScale()
    private val originalCleanMotion = NeriMotionStyle.clean
    private val contentVisible = mutableStateOf(true)
    private val actions = mutableListOf<String>()
    private val writeLabel = application.getString(CoreCommonR.string.local_song_metadata_write_confirm_write)
    private val appOnlyLabel = application.getString(CoreCommonR.string.local_song_metadata_write_confirm_app_only)
    private val cancelLabel = application.getString(CoreCommonR.string.action_cancel)

    @After
    fun restoreFixture() {
        try {
            composeRule.runOnIdle { contentVisible.value = false }
            composeRule.waitForIdle()
        } finally {
            NeriMotionStyle.clean = originalCleanMotion
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                RuntimeEnvironment.setFontScale(originalFontScale)
            }
        }
    }

    @Test
    fun localMetadataActionsKeepCompleteLabelsAndSeparateHitTargetsAtLargeTextInLtr() {
        render(fontScale = 2f, direction = LayoutDirection.Ltr)

        assertCompleteActionLabelsAndSeparateBounds()
    }

    @Test
    fun localMetadataActionsKeepCompleteLabelsAndSeparateHitTargetsAtLargeTextInRtl() {
        render(fontScale = 2f, direction = LayoutDirection.Rtl)

        assertCompleteActionLabelsAndSeparateBounds()
    }

    @Test
    fun localMetadataActionsKeepAllThreeSaveDecisionsIndependent() {
        render()
        composeRule.runOnIdle { assertTrue("showing the confirmation must not save anything", actions.isEmpty()) }

        action(appOnlyLabel).performTouchInput { click(center) }
        action(cancelLabel).performTouchInput { click(center) }
        action(writeLabel).performTouchInput { click(center) }

        composeRule.runOnIdle { assertEquals(listOf("app", "cancel", "write"), actions) }
    }

    @Test
    fun savingDisablesEveryLocalMetadataAction() {
        render(isSaving = true)

        for (label in listOf(writeLabel, appOnlyLabel, cancelLabel)) {
            action(label).assertIsNotEnabled().performTouchInput { click(center) }
        }

        composeRule.runOnIdle { assertTrue("saving must block every competing save decision", actions.isEmpty()) }
    }

    private fun render(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        isSaving: Boolean = false
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            RuntimeEnvironment.setFontScale(fontScale)
        }
        composeRule.setContent {
            if (contentVisible.value) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NeriTheme(
                        followSystemDark = false,
                        forceDark = false,
                        dynamicColor = false,
                        seedColorHex = "6750A4",
                        uiStyle = UiStyle.Clean,
                        animateColors = false
                    ) {
                        EditSongLocalMetadataWriteBackConfirmDialog(
                            isSaving = isSaving,
                            onWriteToLocal = { actions += "write" },
                            onSaveInAppOnly = { actions += "app" },
                            onCancel = { actions += "cancel" }
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun assertCompleteActionLabelsAndSeparateBounds() {
        val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val actionLayouts = listOf(writeLabel, appOnlyLabel, cancelLabel).map { label ->
            val actionBounds = action(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            val textNode = composeRule.onNodeWithText(label, useUnmergedTree = true)
            val textBounds = textNode.fetchSemanticsNode().boundsInRoot
            textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("$label must expose one text layout: action=$actionBounds dialog=$dialog", 1, layouts.size)
            ActionLayout(label, actionBounds, textBounds, layouts.single())
        }
        val diagnostics = "dialog=$dialog; " + actionLayouts.joinToString("; ") { it.diagnostics() }
        for ((label, bounds, _, layout) in actionLayouts) {
            assertEquals("$label must use the real system large-font configuration: $diagnostics",
                2f, layout.layoutInput.density.fontScale, 0f)
            assertTrue("$label must have a positive hit target: $diagnostics", bounds.width > 0f && bounds.height > 0f)
            assertInsideDialog(label, dialog, bounds)
        }
        for (first in actionLayouts.indices) {
            for (second in first + 1 until actionLayouts.size) {
                assertFalse("different save decisions must not share an overlapping hit target: $diagnostics",
                    actionLayouts[first].bounds.overlaps(actionLayouts[second].bounds))
            }
        }
        val availableRowWidth = actionLayouts.maxOf { it.bounds.right } - actionLayouts.minOf { it.bounds.left }
        val unwrappedLabelsWidth = actionLayouts.sumOf { it.text.multiParagraph.maxIntrinsicWidth.toDouble() }
        assertTrue("this narrow fixture must require more than one action row: $diagnostics",
            unwrappedLabelsWidth > availableRowWidth)
        for ((previous, next) in actionLayouts.zipWithNext()) {
            assertTrue("each narrow-screen save decision needs its own centered hit target and vertical gap: $diagnostics",
                abs(previous.bounds.center.x - next.bounds.center.x) <= 1f &&
                    next.bounds.top > previous.bounds.bottom)
        }
        for ((label, bounds, textBounds, layout) in actionLayouts) {
            assertEquals("$label must retain its complete source text: $diagnostics", label, layout.layoutInput.text.text)
            assertEquals("$label must retain enough width for its complete action label: $diagnostics", 1, layout.lineCount)
            assertFalse("$label must not be truncated by a line limit: $diagnostics", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertFalse("$label must not be ellipsized: $diagnostics", layout.isLineEllipsized(line))
                assertEquals("$label must retain every visible character: $diagnostics",
                    label.length, layout.getLineEnd(line, visibleEnd = true))
                assertTrue("$label must paint within the measured text width: $diagnostics",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("$label must paint within the measured text height: $diagnostics",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
                assertTrue("$label must paint within its actual text node and action: $diagnostics",
                    layout.getLineRight(line) <= textBounds.width + 1f &&
                        layout.getLineBottom(line) <= textBounds.height + 1f &&
                        textBounds.left + layout.getLineLeft(line) >= bounds.left - 1f &&
                        textBounds.right <= bounds.right + 1f && textBounds.top >= bounds.top - 1f &&
                        textBounds.bottom <= bounds.bottom + 1f)
            }
        }
    }

    private data class ActionLayout(val label: String, val bounds: Rect, val textBounds: Rect, val text: TextLayoutResult) {
        fun diagnostics(): String = "$label bounds=$bounds center=${bounds.center} " +
            "textBounds=$textBounds textSize=${text.size} lineCount=${text.lineCount} " +
            "didOverflowWidth=${text.didOverflowWidth} didOverflowHeight=${text.didOverflowHeight} " +
            "paragraphSize=${text.multiParagraph.width}x${text.multiParagraph.height} " +
            "unwrappedWidth=${text.multiParagraph.maxIntrinsicWidth} constraints=${text.layoutInput.constraints} " +
            "density=${text.layoutInput.density.density} fontScale=${text.layoutInput.density.fontScale} " +
            "lines=" + (0 until text.lineCount).joinToString { line ->
                "[${text.getLineLeft(line)},${text.getLineTop(line)},${text.getLineRight(line)},${text.getLineBottom(line)} " +
                    "visibleEnd=${text.getLineEnd(line, visibleEnd = true)} ellipsized=${text.isLineEllipsized(line)}]"
            }
    }

    private fun assertInsideDialog(label: String, dialog: Rect, action: Rect) {
        assertTrue("$label must stay fully inside the dialog: action=$action dialog=$dialog",
            action.left >= dialog.left - 1f && action.top >= dialog.top - 1f &&
                action.right <= dialog.right + 1f && action.bottom <= dialog.bottom + 1f)
    }

    private fun action(label: String) = composeRule.onNode(hasText(label) and hasClickAction())
}
