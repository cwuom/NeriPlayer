package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheCleanupContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun invalidRetentionDisablesCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = true,
                    isClearing = false,
                    options = StorageCacheClearOptions(),
                    onOptionsChange = {},
                    onClear = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("-1")
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_cleanup_retain_error))
            .assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("0")
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
            .assertIsEnabled()
    }

    @Test
    fun pendingScanDoesNotShowUnscannedZeroAndDisablesCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = false,
                    isClearing = false,
                    options = StorageCacheClearOptions(),
                    onOptionsChange = {},
                    onClear = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_scan_title)).assertExists()
        composeRule.onNodeWithText(formatFileSize(0L)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
            .assertIsNotEnabled()
    }

    @Test
    fun runningCleanupDisablesInputAndRepeatedConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = true,
                    isClearing = true,
                    options = StorageCacheClearOptions(),
                    onOptionsChange = {},
                    onClear = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_cleanup_running))
            .assertIsNotEnabled()
    }

    @Test
    fun selectAllIncludesOtherCachesAndUsesOneRetentionCountWithoutDownloadStaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var options by mutableStateOf(StorageCacheClearOptions())
        var cleared: StorageCacheClearOptions? = null
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = true,
                    isClearing = false,
                    options = options,
                    onOptionsChange = { options = it },
                    onClear = { cleared = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_cleanup_select_all))
            .performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("7")
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
            .performClick()
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
            assertTrue(result.logFiles)
            assertTrue(result.crashLogs)
            assertTrue(result.otherCache)
            assertFalse(result.downloadStaging)
            assertEquals(7, result.retainRecentSongs)
        }
    }

    @Test
    fun presetOptionsAndEditsSurviveClosingAndReopeningContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var options by mutableStateOf(StorageCacheClearOptions(otherCache = true, retainRecentSongs = 12))
        var visible by mutableStateOf(true)
        var cleared: StorageCacheClearOptions? = null
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    StorageCacheCleanupContent(
                        storageDetails = StorageUsageSummary.Empty,
                        isScanning = false,
                        hasScanned = true,
                        isClearing = false,
                        options = options,
                        onOptionsChange = { options = it },
                        onClear = { cleared = it },
                        onDismiss = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.runOnIdle {
            assertEquals(12, requireNotNull(cleared).retainRecentSongs)
            assertTrue(requireNotNull(cleared).otherCache)
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("23")
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).performClick()
        composeRule.runOnIdle {
            assertEquals(23, requireNotNull(cleared).retainRecentSongs)
            assertTrue(requireNotNull(cleared).otherCache)
        }
    }

    @Test
    fun failedScanShowsRetryAndDisablesCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var retryCount = 0
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = false,
                    isClearing = false,
                    scanFailed = true,
                    onRetryScan = { retryCount++ },
                    options = StorageCacheClearOptions(),
                    onOptionsChange = {},
                    onClear = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_scan_failed)).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_scan_refresh)).performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun compactViewportKeepsCleanupActionVisibleWhileContentScrolls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(260.dp)) {
                    StorageCacheCleanupContent(
                        storageDetails = StorageUsageSummary.Empty,
                        isScanning = false,
                        hasScanned = true,
                        isClearing = false,
                        options = StorageCacheClearOptions(),
                        onOptionsChange = {},
                        onClear = {},
                        onDismiss = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear)).assertIsDisplayed()
    }

    @Test
    fun invalidRetentionSurvivesTypeChangesAndSelectAllWithoutReplacingLastValidCount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var options by mutableStateOf(StorageCacheClearOptions(retainRecentSongs = 12))
        composeRule.setContent {
            MaterialTheme {
                StorageCacheCleanupContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = false,
                    hasScanned = true,
                    isClearing = false,
                    options = options,
                    onOptionsChange = { options = it },
                    onClear = {},
                    onDismiss = {}
                )
            }
        }

        listOf("", "2147483648").forEach { invalid ->
            composeRule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(invalid)
            composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_audio_cache))
                .performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
                .assertIsNotEnabled()
            composeRule.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(invalid))
            )
            composeRule.runOnIdle { assertEquals(12, options.retainRecentSongs) }

            composeRule.onNodeWithText(context.getString(CoreCommonR.string.storage_cleanup_select_all))
                .performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm_clear))
                .assertIsNotEnabled()
            composeRule.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(invalid))
            )
            composeRule.runOnIdle { assertEquals(12, options.retainRecentSongs) }
        }
    }
}
