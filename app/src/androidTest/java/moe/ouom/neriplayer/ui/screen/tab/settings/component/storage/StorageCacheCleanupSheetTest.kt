package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheCleanupSheetTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var visible by mutableStateOf(true)
    private var clearing by mutableStateOf(false)
    private var options by mutableStateOf(StorageCacheClearOptions(retainRecentSongs = 50))
    private val estimatedCount = AtomicInteger(-1)
    private var dismissCalls = 0
    private var clearCalls = 0

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test
    fun cleanLightEditsRetentionAndBackDismisses() = verifyEditableSheet(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkEditsRetentionAndBackDismisses() = verifyEditableSheet(UiStyle.Clean, dark = true)

    @Test
    fun materialEditsRetentionAndBackDismisses() = verifyEditableSheet(UiStyle.Material, dark = false)

    @Test
    fun serverBrowseCacheCanBeSelectedAndFollowsSelectAll() {
        render(UiStyle.Clean, dark = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_type_server_browse_cache))
            .performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(options.serverBrowseCache) }

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_select_all))
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(options.serverBrowseCache)
            assertTrue(options.otherCache)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_deselect_all)).performClick()
        composeRule.runOnIdle {
            assertFalse(options.serverBrowseCache)
            assertFalse(options.hasSelection)
        }
    }

    @Test
    fun runningCleanupKeepsSheetOpenAndDisablesActions() {
        render(UiStyle.Clean, dark = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_running)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_select_all)).assertIsNotEnabled()
        slider().assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_audio_cache)).assertIsNotEnabled()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_running)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, dismissCalls)
            assertEquals(1, clearCalls)
            assertEquals(50, options.retainRecentSongs)
        }
    }

    @Test
    fun horizontalSliderDragChangesRetentionWithoutScrollingOrDismissingSheet() {
        render(UiStyle.Clean, dark = false)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        awaitEstimate(0)

        slider().performTouchInput {
            swipe(
                start = Offset(width * 0.05f, centerY),
                end = Offset(width * 0.84f, centerY),
                durationMillis = 500L
            )
        }

        val retainedCount = composeRule.runOnIdle {
            val count = options.retainRecentSongs
            assertTrue("横向拖动应更新歌曲保留数", count in 700..950)
            assertEquals(0, count % 50)
            assertEquals(0, dismissCalls)
            assertEquals(0, clearCalls)
            count
        }
        awaitEstimate(retainedCount)
        slider().assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed().assertIsEnabled()
        assertPreviewSizes(retainedCount)
    }

    private fun verifyEditableSheet(style: UiStyle, dark: Boolean) {
        render(style, dark)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed().assertIsEnabled()
        slider().performScrollTo().assertIsDisplayed()
        assertPreviewSizes(50)
        if (style == UiStyle.Clean) {
            val theme = if (dark) "dark" else "light"
            captureCacheCleanupScreenshot("cache-cleanup-slider-clean-$theme.png")
        }
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(230f) }
        awaitEstimate(250)
        assertPreviewSizes(250)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(250, options.retainRecentSongs) }

        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, dismissCalls)
            assertEquals(0, clearCalls)
        }
    }

    private fun render(style: UiStyle, dark: Boolean) {
        val summary = cacheCleanupPreviewSummary()
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = dark, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false
            ) {
                if (visible) StorageCacheCleanupSheet(
                    storageDetails = summary, isScanning = false, hasScanned = true,
                    isClearing = clearing, options = options, onOptionsChange = { options = it },
                    onClear = { options = it; clearCalls++; clearing = true },
                    onDismiss = { dismissCalls++; visible = false },
                    estimateCacheClear = { count ->
                        cacheCleanupPreviewEstimate(count).also { estimatedCount.set(count) }
                    }
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        awaitEstimate(50)
    }

    private fun awaitEstimate(count: Int) {
        composeRule.waitUntil(timeoutMillis = 5_000L) { estimatedCount.get() == count }
        composeRule.waitForIdle()
    }

    private fun assertPreviewSizes(count: Int) {
        val estimate = cacheCleanupPreviewEstimate(count)
        val audio = requireNotNull(estimate.audioBytes)
        val image = requireNotNull(estimate.imageBytes)
        composeRule.onNodeWithTag("cache_estimate_Audio", useUnmergedTree = true).assertTextEquals(formatFileSize(audio))
        composeRule.onNodeWithTag("cache_estimate_Image", useUnmergedTree = true).assertTextEquals(formatFileSize(image))
        composeRule.onNodeWithTag("cache_estimate_selected", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_selected_size, formatFileSize(audio + image))
        )
    }

    private fun slider() = composeRule.onNodeWithTag("cache_retention_slider", useUnmergedTree = true)

    private fun string(id: Int): String = context.getString(id)
}
