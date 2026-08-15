package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStorageCacheSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun saveableFileNameEditorComposesAndRestoresTemplateInCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val savedTemplate = "%title% - %artist% [%hash%]"
        var currentTemplate by mutableStateOf<String?>(savedTemplate)
        var saveCallbackCount by mutableStateOf(0)

        composeRule.setContent {
            MaterialTheme {
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
                        saveCallbackCount++
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
                    clearDownloadStagingCache = false,
                    onClearDownloadStagingCacheChange = {},
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
                    clearLogFiles = false,
                    onClearLogFilesChange = {},
                    clearCrashLogs = false,
                    onClearCrashLogsChange = {},
                    downloadStagingClearEnabled = true,
                    onClearCacheClick = { _: StorageCacheClearOptions -> },
                    cardIndex = 1
                )
            }
        }

        composeRule.onNodeWithText(savedTemplate).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_details)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" temporary")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        composeRule.onNodeWithText(savedTemplate).assertExists()
        composeRule.runOnIdle {
            assertEquals(savedTemplate, currentTemplate)
            assertEquals(0, saveCallbackCount)
        }
    }
}
