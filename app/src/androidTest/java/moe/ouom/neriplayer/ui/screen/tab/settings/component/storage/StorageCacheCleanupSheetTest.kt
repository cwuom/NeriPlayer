package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItem
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSection
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheCleanupSheetTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var visible by mutableStateOf(true)
    private var clearing by mutableStateOf(false)
    private var options by mutableStateOf(StorageCacheClearOptions(retainRecentSongs = 12))
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
    fun runningCleanupKeepsSheetOpenAndDisablesActions() {
        render(UiStyle.Clean, dark = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_running)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_select_all)).assertIsNotEnabled()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_audio_cache)).assertIsNotEnabled()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_running)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, dismissCalls)
            assertEquals(1, clearCalls)
            assertEquals(12, options.retainRecentSongs)
        }
    }

    private fun verifyEditableSheet(style: UiStyle, dark: Boolean) {
        render(style, dark)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear))
            .assertIsDisplayed().assertIsEnabled()
        composeRule.onNode(hasSetTextAction()).performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("12"))
        )
        if (style == UiStyle.Clean) capture(if (dark) "dark" else "light")
        composeRule.onNode(hasSetTextAction()).performClick().performTextReplacement("7")
        closeSoftKeyboard()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(7, options.retainRecentSongs) }

        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, dismissCalls)
            assertEquals(0, clearCalls)
        }
    }

    private fun render(style: UiStyle, dark: Boolean) {
        val summary = StorageUsageSummary(listOf(StorageUsageSection("缓存", listOf(
            StorageUsageItem("音频", "", null, 64L * 1024 * 1024, 18,
                StorageUsageItemKind.AudioCache, cacheKind = StorageCacheKind.Audio),
            StorageUsageItem("图片", "", null, 8L * 1024 * 1024, 24,
                StorageUsageItemKind.ImageCache, cacheKind = StorageCacheKind.Image)
        ))))
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = dark, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false
            ) {
                if (visible) StorageCacheCleanupSheet(
                    storageDetails = summary, isScanning = false, hasScanned = true,
                    isClearing = clearing, options = options, onOptionsChange = { options = it },
                    onClear = { options = it; clearCalls++; clearing = true },
                    onDismiss = { dismissCalls++; visible = false }
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun capture(theme: String) {
        if (InstrumentationRegistry.getArguments().getString("captureCacheCleanup") != "true") return
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            context.cacheDir.resolve("cache-cleanup-clean-$theme.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun string(id: Int): String = context.getString(id)
}
