package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItem
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSection
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheCleanupContentTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun retentionSliderHasZeroAndThousandEndpointsWithFiftySongSteps() {
        var options by mutableStateOf(StorageCacheClearOptions(retainRecentSongs = 50))
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = {}, onDismiss = {}
                )
            }
        }

        slider().performScrollTo().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(50f, 0f..1000f, 19)
            )
        )
        listOf(0f to 0, 24f to 0, 26f to 50, 74f to 50, 76f to 100, 1000f to 1000).forEach { (requested, expected) ->
            slider().performSemanticsAction(SemanticsActions.SetProgress) { it(requested) }
            composeRule.runOnIdle { assertEquals(expected, options.retainRecentSongs) }
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsEnabled()
    }

    @Test
    fun pendingScanDoesNotShowUnscannedZeroAndDisablesCleanup() {
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = false, isClearing = false,
                    options = StorageCacheClearOptions(), onOptionsChange = {},
                    onClear = {}, onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_scan_title)).assertExists()
        composeRule.onNodeWithText(formatFileSize(0L)).assertDoesNotExist()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
        slider().assertIsNotEnabled()
    }

    @Test
    fun runningCleanupDisablesSliderAndRepeatedConfirmation() {
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = true, isClearing = true,
                    options = StorageCacheClearOptions(), onOptionsChange = {},
                    onClear = {}, onDismiss = {}
                )
            }
        }

        slider().assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_running)).assertIsNotEnabled()
    }

    @Test
    fun selectAllIncludesOtherCachesAndUsesOneRetentionCountWithoutDownloadStaging() {
        var options by mutableStateOf(StorageCacheClearOptions())
        var cleared: StorageCacheClearOptions? = null
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = { cleared = it }, onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_select_all)).performClick()
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.runOnIdle {
            val result = requireNotNull(cleared)
            assertTrue(result.audioCache)
            assertTrue(result.imageCache)
            assertTrue(result.sharedMedia)
            assertTrue(result.lyricsCache)
            assertTrue(result.neteasePlaylistCache)
            assertTrue(result.biliFavoriteCache)
            assertTrue(result.biliArchiveCache)
            assertTrue(result.youtubePlaylistCache)
            assertTrue(result.serverBrowseCache)
            assertTrue(result.logFiles)
            assertTrue(result.crashLogs)
            assertTrue(result.localImportCache)
            assertTrue(result.otherCache)
            assertFalse(result.downloadStaging)
            assertEquals(50, result.retainRecentSongs)
        }
    }

    @Test
    fun localImportCacheCanBeClearedWithoutSelectingOtherCaches() {
        var options by mutableStateOf(StorageCacheClearOptions(audioCache = false, imageCache = false))
        var cleared: StorageCacheClearOptions? = null
        val summary = StorageUsageSummary(listOf(StorageUsageSection("Cache", listOf(
            StorageUsageItem(
                title = "Unused local imports", description = "", path = null,
                sizeBytes = 3L * CACHE_PREVIEW_MIB, fileCount = 2,
                kind = StorageUsageItemKind.LocalImportCache, cacheKind = StorageCacheKind.LocalImport
            )
        ))))
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = summary,
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = { cleared = it }, onDismiss = {},
                    estimate = PlayerCacheClearEstimate(0L, 0L)
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_type_local_import_cache))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_desc_local_import_cache)).assertExists()
        composeRule.onNodeWithTag("cache_estimate_LocalImport", useUnmergedTree = true)
            .assertTextEquals(formatFileSize(3L * CACHE_PREVIEW_MIB))
        composeRule.onNodeWithTag("cache_estimate_selected", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_selected_size, formatFileSize(3L * CACHE_PREVIEW_MIB))
        )
        captureCacheCleanupScreenshot("local-import-cache-cleanup.png")
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(
                StorageCacheClearOptions(audioCache = false, imageCache = false, localImportCache = true),
                cleared
            )
        }
    }

    @Test
    fun presetOptionsAndSliderEditsSurviveClosingAndReopeningContent() {
        var options by mutableStateOf(StorageCacheClearOptions(otherCache = true, retainRecentSongs = 20))
        var visible by mutableStateOf(true)
        var cleared: StorageCacheClearOptions? = null
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    StorageCacheCleanupContent(
                        storageDetails = StorageUsageSummary.Empty,
                        isScanning = false, hasScanned = true, isClearing = false,
                        options = options, onOptionsChange = { options = it },
                        onClear = { cleared = it }, onDismiss = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.runOnIdle {
            assertEquals(0, requireNotNull(cleared).retainRecentSongs)
            assertTrue(requireNotNull(cleared).otherCache)
        }
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(230f) }
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.runOnIdle {
            assertEquals(250, requireNotNull(cleared).retainRecentSongs)
            assertTrue(requireNotNull(cleared).otherCache)
        }
    }

    @Test
    fun failedScanShowsRetryAndDisablesCleanup() {
        var retryCount = 0
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = false, isClearing = false,
                    scanFailed = true, onRetryScan = { retryCount++ },
                    options = StorageCacheClearOptions(), onOptionsChange = {},
                    onClear = {}, onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.storage_scan_failed)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_scan_refresh)).performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun compactViewportKeepsCleanupActionVisibleWhileSliderScrollsIntoView() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(260.dp)) {
                    StorageCacheCleanupContent(
                        storageDetails = StorageUsageSummary.Empty,
                        isScanning = false, hasScanned = true, isClearing = false,
                        options = StorageCacheClearOptions(), onOptionsChange = {},
                        onClear = {}, onDismiss = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
        slider().performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
    }

    @Test
    fun changingCacheTypesAndSelectAllDoesNotResetSliderCount() {
        var options by mutableStateOf(StorageCacheClearOptions(retainRecentSongs = 50))
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = {}, onDismiss = {}
                )
            }
        }

        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(230f) }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_audio_cache)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(250, options.retainRecentSongs)
            assertFalse(options.audioCache)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.storage_cleanup_select_all)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(250, options.retainRecentSongs)
            assertTrue(options.audioCache)
        }
    }

    @Test
    fun sliderEndpointsUpdateAudioImageAndSelectedEstimates() {
        var options by mutableStateOf(StorageCacheClearOptions())
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = cacheCleanupPreviewSummary(),
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = {}, onDismiss = {},
                    estimate = cacheCleanupPreviewEstimate(options.retainRecentSongs)
                )
            }
        }

        assertEstimates(64L * CACHE_PREVIEW_MIB, 8L * CACHE_PREVIEW_MIB)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1000f) }
        assertEstimates(4L * CACHE_PREVIEW_MIB, CACHE_PREVIEW_MIB)
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEstimates(64L * CACHE_PREVIEW_MIB, 8L * CACHE_PREVIEW_MIB)
    }

    @Test
    fun pendingEstimateHidesPreviousValuesAndDisablesConfirmationWithoutDisablingSlider() {
        var pending by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = cacheCleanupPreviewSummary(),
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = StorageCacheClearOptions(), onOptionsChange = {},
                    onClear = {}, onDismiss = {},
                    estimate = cacheCleanupPreviewEstimate(0), isEstimating = pending
                )
            }
        }

        assertEstimates(64L * CACHE_PREVIEW_MIB, 8L * CACHE_PREVIEW_MIB)
        composeRule.runOnIdle { pending = true }
        val unavailable = string(CoreCommonR.string.storage_cleanup_size_unavailable)
        composeRule.onNodeWithTag("cache_estimate_Audio", useUnmergedTree = true).assertTextEquals(unavailable)
        composeRule.onNodeWithTag("cache_estimate_Image", useUnmergedTree = true).assertTextEquals(unavailable)
        composeRule.onNodeWithTag("cache_estimate_selected", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_selected_size, unavailable)
        )
        slider().assertIsEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
    }

    @Test
    fun unavailableAudioEstimateDoesNotHideKnownImageWhenAudioIsDeselected() {
        var options by mutableStateOf(StorageCacheClearOptions())
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = cacheCleanupPreviewSummary(),
                    isScanning = false, hasScanned = true, isClearing = false,
                    options = options, onOptionsChange = { options = it },
                    onClear = {}, onDismiss = {}, estimate = PlayerCacheClearEstimate(null, CACHE_PREVIEW_MIB)
                )
            }
        }

        composeRule.onNodeWithTag("cache_estimate_Audio", useUnmergedTree = true)
            .assertTextEquals(string(CoreCommonR.string.storage_cleanup_size_unavailable))
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_audio_cache)).performScrollTo().performClick()
        composeRule.onNodeWithTag("cache_estimate_Image", useUnmergedTree = true).assertTextEquals(formatFileSize(CACHE_PREVIEW_MIB))
        composeRule.onNodeWithTag("cache_estimate_selected", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_selected_size, formatFileSize(CACHE_PREVIEW_MIB))
        )
    }

    private fun assertEstimates(audio: Long, image: Long) {
        composeRule.onNodeWithTag("cache_estimate_Audio", useUnmergedTree = true).assertTextEquals(formatFileSize(audio))
        composeRule.onNodeWithTag("cache_estimate_Image", useUnmergedTree = true).assertTextEquals(formatFileSize(image))
        composeRule.onNodeWithTag("cache_estimate_selected", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_selected_size, formatFileSize(audio + image))
        )
    }

    private fun slider() = composeRule.onNodeWithTag("cache_retention_slider", useUnmergedTree = true)

    private fun string(id: Int): String = context.getString(id)
}
