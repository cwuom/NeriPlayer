package moe.ouom.neriplayer.ui.component.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.ManualMetadataApplicationState
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ManualMetadataApplicationProgressTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var darkTheme = false

    @Test fun `clean progress permits cancellation then reaches complete only after commit`() = verifyProgress(UiStyle.Clean)
    @Test fun `material progress permits cancellation then reaches complete only after commit`() = verifyProgress(UiStyle.Material)
    @Test fun `clean dark progress preserves insets and completion`() = verifyProgress(UiStyle.Clean, true)
    @Test fun `material dark progress preserves insets and completion`() = verifyProgress(UiStyle.Material, true)

    @Test
    fun `stage progress moves through intermediate frames instead of jumping`() {
        val application = mutableStateOf(
            ManualMetadataApplicationState(isRunning = true, stage = SongMetadataApplyStage.FETCHING_DETAILS)
        )
        composeRule.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                ManualMetadataApplicationProgress(application.value, {})
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { application.value = application.value.copy(stage = SongMetadataApplyStage.APPLYING) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(96L)
        composeRule.waitForIdle()
        val progress = composeRule.onNodeWithTag("metadata-stage-progress").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("progress must animate between stages, actual=$progress", progress > 0f && progress < 0.8f)
    }

    @Test
    fun `cancelling retains the outgoing progress stage during its fade`() {
        val application = mutableStateOf(
            ManualMetadataApplicationState(isRunning = true, stage = SongMetadataApplyStage.FETCHING_LYRICS)
        )
        var completions = 0
        composeRule.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                MetadataSearchResults(
                    state = ManualSearchState(), offlineMode = true, enabled = true,
                    isWaitingForPlayback = false, application = application.value,
                    onCancelApplication = { application.value = ManualMetadataApplicationState() },
                    onSongSelected = {}, onApplied = { completions++ }
                )
            }
        }
        val previous = composeRule.onNodeWithTag("metadata-application-stage").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("metadata-application-cancel").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(64L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("metadata-application-stage").assertTextEquals(previous)
        composeRule.runOnIdle { assertEquals(0, completions) }
    }

    private fun verifyProgress(style: UiStyle, dark: Boolean = false) {
        darkTheme = dark
        val application = mutableStateOf(
            ManualMetadataApplicationState(isRunning = true, stage = SongMetadataApplyStage.FETCHING_LYRICS)
        )
        var cancellations = 0
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = darkTheme, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                val surfaceIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                SideEffect { assertEquals("render must use the requested appearance", darkTheme, surfaceIsDark) }
                Surface(Modifier.fillMaxWidth().testTag("metadata-progress-host"), color = MaterialTheme.colorScheme.surfaceContainer) {
                    MetadataSearchResults(
                        state = ManualSearchState(), offlineMode = true, enabled = false,
                        isWaitingForPlayback = false, application = application.value,
                        onCancelApplication = { cancellations++ }, onSongSelected = {}
                    )
                }
            }
        }
        val host = composeRule.onNodeWithTag("metadata-results-host").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag("metadata-application-progress").getUnclippedBoundsInRoot()
        assertEquals("progress must align with the search input inset", 16f, (card.left - host.left).value, 0.5f)
        assertEquals("progress must keep equal side insets", 16f, (host.right - card.right).value, 0.5f)
        composeRule.onNodeWithTag("metadata-application-cancel").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            application.value = application.value.copy(stage = SongMetadataApplyStage.APPLYING)
        }
        composeRule.onNodeWithTag("metadata-application-cancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("metadata-stage-progress").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.8f, 0f..1f))
        )
        capture(style, "applying")
        composeRule.runOnIdle {
            application.value = application.value.copy(isRunning = false, completed = true)
        }
        composeRule.onNodeWithTag("metadata-stage-progress").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f))
        )
        capture(style, "completed")
    }

    private fun capture(style: UiStyle, stage: String) {
        val directory = System.getProperty("neriplayer.metadataScreenshotDir")
            ?: System.getenv("NERIPLAYER_METADATA_SCREENSHOT_DIR") ?: return
        val target = File(directory).apply { mkdirs() }
        val bitmap = composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val appearance = if (darkTheme) "dark" else "light"
        File(target, "metadata-progress-${style.name.lowercase()}-$appearance-$stage.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
