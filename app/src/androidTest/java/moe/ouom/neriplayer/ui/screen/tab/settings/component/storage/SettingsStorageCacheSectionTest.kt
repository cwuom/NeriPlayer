package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
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
    fun localImportSelectionSurvivesReopeningAndSavedStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restoration = StateRestorationTester(composeRule)
        val estimateLoads = AtomicInteger()
        var showCleanup by mutableStateOf(true)
        var cleared: StorageCacheClearOptions? = null
        restoration.setContent {
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
                    downloadFileNameTemplate = null,
                    onDownloadFileNameTemplateChange = {},
                    maxCacheSizeBytes = 0L,
                    onMaxCacheSizeBytesChange = {},
                    onOpenStorageDetails = {},
                    storageDetails = StorageUsageSummary.Empty,
                    showClearCacheDialog = showCleanup,
                    onShowClearCacheDialogChange = { showCleanup = it },
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
                    onClearCacheClick = { cleared = it },
                    hasStorageScan = true,
                    estimateCacheClear = {
                        estimateLoads.incrementAndGet()
                        PlayerCacheClearEstimate(0L, 0L)
                    },
                    cardIndex = 3
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { estimateLoads.get() > 0 }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_type_local_import_cache))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_cancel)).performClick()
        composeRule.onNode(hasText(context.getString(CoreCommonR.string.action_clear)) and hasClickAction()).performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_type_local_import_cache))
            .performScrollTo().assertIsOn()

        val loadsBeforeRestore = estimateLoads.get()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 5_000L) { estimateLoads.get() > loadsBeforeRestore }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_type_local_import_cache))
            .performScrollTo().assertIsOn()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
            .assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(
                StorageCacheClearOptions(audioCache = false, imageCache = false, localImportCache = true),
                cleared
            )
        }
    }

    @Test
    fun saveableFileNameEditorComposesAndRestoresTemplateInCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val savedTemplate = "%id% - %audioId% - %subAudioId%"
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
                    onClearCacheClick = { _: StorageCacheClearOptions -> },
                    cardIndex = 1
                )
            }
        }

        composeRule.onNodeWithText(savedTemplate).assertExists()
        composeRule.onNodeWithText(
            context.getString(
                CoreCommonR.string.settings_download_file_name_format_preview,
                "123456 - 456789 - 789012"
            )
        ).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_details)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" temporary")
        closeSoftKeyboard()
        pressBack()

        composeRule.onNodeWithText(savedTemplate).assertExists()
        composeRule.runOnIdle {
            assertEquals(savedTemplate, currentTemplate)
            assertEquals(0, saveCallbackCount)
        }
    }
}
