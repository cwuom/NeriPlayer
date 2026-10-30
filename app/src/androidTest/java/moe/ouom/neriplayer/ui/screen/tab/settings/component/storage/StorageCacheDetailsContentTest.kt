package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.storage.StorageUsageItem
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSection
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheDetailsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun scanningStateShowsDedicatedScanContent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent {
            MaterialTheme {
                StorageCacheDetailsContent(
                    storageDetails = StorageUsageSummary.Empty,
                    isScanning = true,
                    onRefresh = {},
                    onClearCache = {},
                    onOpenSystemSettings = {}
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(CoreCommonR.string.storage_scan_title)
        ).assertExists()
    }

    @Test
    fun usageRowsDoNotRenderAbsolutePaths() {
        val summary = StorageUsageSummary(
            sections = listOf(
                StorageUsageSection(
                    title = "Cache",
                    items = listOf(
                        StorageUsageItem(
                            title = "Audio cache",
                            description = "Regenerable audio files",
                            path = "/private/cache/audio",
                            sizeBytes = 1L,
                            fileCount = 1,
                            kind = StorageUsageItemKind.AudioCache
                        )
                    )
                )
            )
        )
        composeRule.setContent {
            MaterialTheme {
                StorageCacheDetailsContent(
                    storageDetails = summary,
                    isScanning = false,
                    onRefresh = {},
                    onClearCache = {},
                    onOpenSystemSettings = {}
                )
            }
        }

        composeRule.onAllNodesWithText("/private/cache/audio").assertCountEquals(0)
        composeRule.onNodeWithText("Audio cache").assertExists()
    }

    @Test
    fun cleanLightRendersEveryStorageGlyph() = verifyStorageGlyphPages(dark = false)

    @Test
    fun cleanDarkRendersEveryStorageGlyph() = verifyStorageGlyphPages(dark = true)

    private fun verifyStorageGlyphPages(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pages = storageGlyphSamples.mapIndexed { index, (kind, title) ->
            StorageUsageItem(
                title = context.getString(title),
                description = "示例缓存文件",
                path = null,
                sizeBytes = (24L - index) * CACHE_PREVIEW_MIB,
                fileCount = index + 3,
                kind = kind
            )
        }.chunked(6)
        var page by mutableStateOf(pages.first())
        val previewScrollState = ScrollState(0)
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = dark, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(previewScrollState).padding(16.dp)) {
                        StorageCacheDetailsContent(
                            storageDetails = StorageUsageSummary(listOf(StorageUsageSection("缓存详情", page))),
                            isScanning = false,
                            onRefresh = {}, onClearCache = {}, onOpenSystemSettings = {}
                        )
                    }
                }
            }
        }

        pages.forEachIndexed { index, items ->
            composeRule.runOnIdle { page = items }
            composeRule.onNodeWithText(items.last().title).performScrollTo().assertIsDisplayed()
            composeRule.runOnIdle { previewScrollState.dispatchRawDelta(-previewScrollState.value.toFloat()) }
            composeRule.waitForIdle()
            val theme = if (dark) "dark" else "light"
            captureCacheCleanupScreenshot("cache-details-icons-clean-$theme-${index + 1}.png")
        }
    }

    private val storageGlyphSamples = listOf(
        StorageUsageItemKind.AudioCache to CoreCommonR.string.storage_type_audio_cache,
        StorageUsageItemKind.ImageCache to CoreCommonR.string.storage_type_image_cache,
        StorageUsageItemKind.DownloadStaging to CoreCommonR.string.storage_type_download_staging,
        StorageUsageItemKind.SharedMedia to CoreCommonR.string.storage_type_shared_media,
        StorageUsageItemKind.LyricsCache to CoreCommonR.string.storage_type_lyrics_cache,
        StorageUsageItemKind.NeteasePlaylistCache to CoreCommonR.string.storage_type_netease_playlist_cache,
        StorageUsageItemKind.BiliFavoriteCache to CoreCommonR.string.storage_type_bili_favorite_cache,
        StorageUsageItemKind.BiliArchiveCache to CoreCommonR.string.storage_type_bili_archive_cache,
        StorageUsageItemKind.YouTubePlaylistCache to CoreCommonR.string.storage_type_youtube_playlist_cache,
        StorageUsageItemKind.OtherCache to CoreCommonR.string.storage_type_other_cache,
        StorageUsageItemKind.ProtectedCache to CoreCommonR.string.storage_type_protected_cache,
        StorageUsageItemKind.DownloadedMusic to CoreCommonR.string.storage_type_downloaded_music,
        StorageUsageItemKind.DownloadedLyrics to CoreCommonR.string.storage_type_downloaded_lyrics,
        StorageUsageItemKind.DownloadedCovers to CoreCommonR.string.storage_type_downloaded_covers,
        StorageUsageItemKind.DownloadIndex to CoreCommonR.string.storage_type_download_index,
        StorageUsageItemKind.LogFiles to CoreCommonR.string.storage_type_log_files,
        StorageUsageItemKind.CrashLogs to CoreCommonR.string.storage_type_crash_logs,
        StorageUsageItemKind.LocalCovers to CoreCommonR.string.storage_type_local_covers,
        StorageUsageItemKind.LocalImportCache to CoreCommonR.string.storage_type_local_import_cache,
        StorageUsageItemKind.CustomBackground to CoreCommonR.string.storage_type_custom_background,
        StorageUsageItemKind.LegacyMigrationFiles to CoreCommonR.string.storage_type_legacy_migration_files,
        StorageUsageItemKind.Database to CoreCommonR.string.storage_type_database,
        StorageUsageItemKind.AppData to CoreCommonR.string.storage_type_app_data
    )
}
