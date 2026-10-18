package moe.ouom.neriplayer.ui.component.overlay

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLocalMetadataWriteBackConfirmDialog
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanAlertDialogMultiActionTouchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalCleanMotion = NeriMotionStyle.clean
    private val visible = mutableStateOf(true)
    private val actions = mutableListOf<String>()
    private var contentInstalled = false
    private val writeLabel get() = composeRule.activity.getString(CoreCommonR.string.local_song_metadata_write_confirm_write)
    private val appOnlyLabel get() = composeRule.activity.getString(CoreCommonR.string.local_song_metadata_write_confirm_app_only)
    private val cancelLabel get() = composeRule.activity.getString(CoreCommonR.string.action_cancel)

    @Before
    fun verifyHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun restoreFixture() {
        try {
            if (contentInstalled) {
                composeRule.runOnIdle { visible.value = false }
                composeRule.waitForIdle()
            }
        } finally {
            NeriMotionStyle.clean = originalCleanMotion
        }
    }

    @Test
    fun cleanStyleKeepsThreeSeparateActionsForNativeTouches() {
        assertNativeSaveDecisions(UiStyle.Clean)
    }

    @Test
    fun materialStyleKeepsThreeSeparateActionsForNativeTouches() {
        assertNativeSaveDecisions(UiStyle.Material)
    }

    private fun assertNativeSaveDecisions(style: UiStyle) {
        render(style)
        assertSeparateActionBounds(style)
        composeRule.runOnIdle { assertTrue("showing the confirmation must not save anything", actions.isEmpty()) }
        capture(if (style == UiStyle.Clean) "clean-three-actions" else "material-three-actions")

        for ((label, expected) in listOf(writeLabel to "write", appOnlyLabel to "app", cancelLabel to "cancel")) {
            composeRule.runOnIdle { actions.clear() }
            action(label).assertIsDisplayed().assertIsEnabled().performNativeClick()
            composeRule.runOnIdle {
                assertEquals("touching $label must trigger only its own save decision", listOf(expected), actions)
            }
        }
    }

    private fun render(style: UiStyle) {
        contentInstalled = true
        composeRule.setContent {
            if (visible.value) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = style,
                    animateColors = false
                ) {
                    EditSongLocalMetadataWriteBackConfirmDialog(
                        isSaving = false,
                        onWriteToLocal = { actions += "write" },
                        onSaveInAppOnly = { actions += "app" },
                        onCancel = { actions += "cancel" }
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun assertSeparateActionBounds(style: UiStyle) {
        val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val bounds = listOf(writeLabel, appOnlyLabel, cancelLabel).map { label ->
            action(label).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot.also {
                assertTrue("$label must have a complete hit target inside its dialog: action=$it dialog=$dialog",
                    it.width > 0f && it.height > 0f && it.left >= dialog.left - 1f &&
                        it.right <= dialog.right + 1f && it.top >= dialog.top - 1f && it.bottom <= dialog.bottom + 1f)
            }
        }
        for (first in bounds.indices) {
            for (second in first + 1 until bounds.size) {
                assertFalse("save decisions must not overlap: style=$style bounds=$bounds",
                    bounds[first].overlaps(bounds[second]))
            }
        }
        if (style == UiStyle.Clean) {
            val allInOneRow = bounds.all { abs(it.center.y - bounds.first().center.y) <= 1f }
            if (!allInOneRow) {
                for ((previous, next) in bounds.zipWithNext()) {
                    assertTrue("narrow Clean dialogs need three separate centered pills with vertical gaps: $bounds",
                        abs(previous.center.x - next.center.x) <= 1f && next.top > previous.bottom)
                }
            }
        }
    }

    private fun action(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank) ?: return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("DialogThreeActions", "capture=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
