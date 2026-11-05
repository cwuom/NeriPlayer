package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.naming.DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadFileNameDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var currentTemplate by mutableStateOf<String?>(null)
    private val savedTemplates = mutableListOf<String?>()

    private fun string(id: Int): String = context.getString(id)

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun editTemplate(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(text)
        settle()
    }

    private fun openEditor(saved: String? = null, fontScale: Float = 1f) {
        currentTemplate = saved
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsStorageCacheSection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            currentDownloadDirectorySummary = "",
                            isCustomDownloadDirectory = false,
                            downloadDirectoryChangeEnabled = true,
                            onPickDownloadDirectory = {},
                            onResetDownloadDirectory = {},
                            downloadFileNameTemplate = currentTemplate,
                            onDownloadFileNameTemplateChange = {
                                currentTemplate = it
                                savedTemplates += it
                            },
                            maxCacheSizeBytes = 0L,
                            onMaxCacheSizeBytesChange = {},
                            onOpenStorageDetails = {},
                            storageDetails = StorageUsageSummary.Empty,
                            showClearCacheDialog = false,
                            onShowClearCacheDialogChange = {},
                            clearAudioCache = false,
                            onClearAudioCacheChange = {},
                            clearImageCache = false,
                            onClearImageCacheChange = {},
                            clearSharedMediaCache = false,
                            onClearSharedMediaCacheChange = {},
                            clearLyricsCache = false,
                            onClearLyricsCacheChange = {},
                            clearNeteasePlaylistCache = false,
                            onClearNeteasePlaylistCacheChange = {},
                            clearBiliFavoriteCache = false,
                            onClearBiliFavoriteCacheChange = {},
                            clearBiliArchiveCache = false,
                            onClearBiliArchiveCacheChange = {},
                            clearYoutubePlaylistCache = false,
                            onClearYoutubePlaylistCacheChange = {},
                            clearServerBrowseCache = false,
                            onClearServerBrowseCacheChange = {},
                            clearLogFiles = false,
                            onClearLogFilesChange = {},
                            clearCrashLogs = false,
                            onClearCrashLogsChange = {},
                            onClearCacheClick = {},
                            cardIndex = 1
                        )
                    }
                }
            }
        }
        settle()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_details)).performClick()
        settle()
    }

    @Test
    fun resetBecomesAvailableAfterEditingSavedDefault() {
        openEditor()
        editTemplate("%title% custom")

        composeRule.onNodeWithText(string(CoreCommonR.string.action_reset))
            .assertIsEnabled().performClick()
        settle()

        composeRule.runOnIdle { assertEquals(listOf<String?>(null), savedTemplates) }
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun resetClearsSavedCustomTemplateWhenDraftIsDefault() {
        openEditor(saved = "%title% custom")
        editTemplate(DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)

        composeRule.onNodeWithText(string(CoreCommonR.string.action_reset)).performClick()
        settle()

        composeRule.runOnIdle { assertEquals(listOf<String?>(null), savedTemplates) }
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun resetAndApplyHaveMatchingBounds() {
        openEditor(saved = "%title% custom")
        val reset = composeRule.onNodeWithText(string(CoreCommonR.string.action_reset)).getBoundsInRoot()
        val apply = composeRule.onNodeWithText(string(CoreCommonR.string.action_apply)).getBoundsInRoot()

        assertEquals(apply.top.value, reset.top.value, 0.5f)
        assertEquals(apply.bottom.value, reset.bottom.value, 0.5f)
        assertTrue(reset.right <= apply.left)
        if (InstrumentationRegistry.getArguments().getString("captureFilenamePreview") == "true") {
            val bitmap = checkNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
            context.cacheDir.resolve("filename-dialog-preview.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun longTemplatesWrapInsideEditor() {
        openEditor()
        editTemplate("%artist% - %title% - %album% - %source% - %audioId%")
        val results = mutableListOf<TextLayoutResult>()

        composeRule.onNode(hasSetTextAction())
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }

        assertTrue(results.single().lineCount > 1)
    }

    @Test
    fun pastedLineBreaksBecomeSpacesInSavedFileName() {
        openEditor()
        editTemplate("%title%\n%artist%\r%album%")

        composeRule.onNodeWithText(string(CoreCommonR.string.action_apply)).performClick()
        settle()

        composeRule.runOnIdle { assertEquals(listOf("%title% %artist% %album%"), savedTemplates) }
    }

    @Test
    fun placeholdersRemainReachableWithLargerText() {
        openEditor(fontScale = 1.3f)
        editTemplate("%title%")

        composeRule.onNodeWithText("%hash%").performScrollTo().performClick()
        settle()

        composeRule.onNodeWithText(string(CoreCommonR.string.action_apply)).assertIsDisplayed().performClick()
        settle()
        composeRule.runOnIdle { assertEquals(listOf("%title%%hash%"), savedTemplates) }
    }

    @Test
    fun backDiscardsEditsWithoutSaving() {
        openEditor(saved = "%title% original")
        editTemplate("%artist% draft")

        closeSoftKeyboard()
        pressBack()
        settle()

        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(savedTemplates.isEmpty())
            assertEquals("%title% original", currentTemplate)
        }
    }
}
