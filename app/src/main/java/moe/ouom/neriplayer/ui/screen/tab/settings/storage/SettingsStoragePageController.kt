package moe.ouom.neriplayer.ui.screen.tab.settings.storage

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.data.local.storage.analyzeStorageUsage
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.ManagedLibraryProcessingDetailsCard
import moe.ouom.neriplayer.ui.screen.tab.settings.component.download.SettingsDownloadQualityFollowPlaybackCard
import moe.ouom.neriplayer.ui.screen.tab.settings.component.download.SettingsDownloadSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.storage.SettingsStorageCacheSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.storage.StorageCacheDetailsContent
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectoryProcessingPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectorySettingsController
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.miuixSettingsSectionCardItem

internal class SettingsStorageSelectionState {
    var showClearCacheDialog by mutableStateOf(false)
    var clearAudioCache by mutableStateOf(true)
    var clearImageCache by mutableStateOf(true)
    var clearSharedMediaCache by mutableStateOf(false)
    var clearLyricsCache by mutableStateOf(false)
    var clearNeteasePlaylistCache by mutableStateOf(false)
    var clearBiliFavoriteCache by mutableStateOf(false)
    var clearBiliArchiveCache by mutableStateOf(false)
    var clearYoutubePlaylistCache by mutableStateOf(false)
    var clearServerBrowseCache by mutableStateOf(false)
    var clearLogFiles by mutableStateOf(false)
    var clearCrashLogs by mutableStateOf(false)
}

@Composable
internal fun rememberSettingsStorageSelectionState(): SettingsStorageSelectionState =
    remember { SettingsStorageSelectionState() }

internal interface DownloadDirectoryStoragePort {
    val currentSummary: String
    val permissionLost: Boolean
    val changeEnabled: Boolean
    val hasActiveDownloadOperations: Boolean
    val onPickRequested: () -> Unit
    val onResetRequested: () -> Unit
}

internal data class SettingsStoragePageAvailability(
    val isCustomDirectory: Boolean
)

internal fun settingsStoragePageAvailability(
    directoryUri: String?
): SettingsStoragePageAvailability = SettingsStoragePageAvailability(
    isCustomDirectory = !directoryUri.isNullOrBlank()
)

internal fun shouldShowSharedDownloadDirectoryProcessing(
    selectedPage: SettingsPage,
    presentation: DownloadDirectoryProcessingPresentation
): Boolean = selectedPage == SettingsPage.Storage && presentation.usesSharedProcessing

internal fun LazyListScope.settingsStorageProcessingItem(
    selectedPage: SettingsPage,
    directory: DownloadDirectorySettingsController
) {
    if (!shouldShowSharedDownloadDirectoryProcessing(selectedPage, directory.processingPresentation)) return
    item(key = "${selectedPage.name}:processing") {
        ManagedLibraryProcessingDetailsCard(
            state = directory.libraryProcessing,
            migrationProgress = directory.migrationProgress
        )
    }
}

internal class SettingsStorageDetailsController(
    private val detailsState: MutableState<StorageUsageSummary>,
    private val loadingState: MutableState<Boolean>,
    private val scanRequestState: MutableIntState,
    private val analyze: suspend () -> StorageUsageSummary
) {
    val details: StorageUsageSummary get() = detailsState.value
    val isScanning: Boolean get() = loadingState.value

    fun requestRefresh() {
        if (!isScanning) scanRequestState.intValue++
    }

    fun requestIfDetailsPage(activePage: SettingsPage?) {
        if (activePage == SettingsPage.StorageCacheDetails && details == StorageUsageSummary.Empty) {
            requestRefresh()
        }
    }

    suspend fun scanRequested() {
        if (scanRequestState.intValue == 0) return
        loadingState.value = true
        yield()
        try {
            detailsState.value = analyze()
        } finally {
            loadingState.value = false
        }
    }
}

@Composable
internal fun rememberSettingsStorageDetailsController(
    context: Context
): SettingsStorageDetailsController {
    val detailsState = remember { mutableStateOf(StorageUsageSummary.Empty) }
    val loadingState = remember { mutableStateOf(false) }
    val scanRequestState = rememberSaveable { mutableIntStateOf(0) }
    val controller = remember(context, detailsState, loadingState, scanRequestState) {
        SettingsStorageDetailsController(
            detailsState = detailsState,
            loadingState = loadingState,
            scanRequestState = scanRequestState,
            analyze = { analyzeStorageUsage(context) }
        )
    }
    LaunchedEffect(scanRequestState.intValue) { controller.scanRequested() }
    return controller
}

internal fun LazyListScope.settingsStoragePageItems(
    directory: DownloadDirectoryStoragePort,
    downloadDirectoryUri: String?,
    downloadFileNameTemplate: String?,
    onDownloadFileNameTemplateChange: (String?) -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onOpenStorageDetails: () -> Unit,
    storageDetails: StorageUsageSummary,
    selection: SettingsStorageSelectionState,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: () -> Unit
) {
    val availability = settingsStoragePageAvailability(downloadDirectoryUri)
    for (cardIndex in 0..3) {
        item(key = "${SettingsPage.Storage.name}:card:$cardIndex") {
            SettingsStorageCacheSection(
                expanded = true,
                arrowRotation = 0f,
                onExpandedChange = {},
                showHeader = false,
                currentDownloadDirectorySummary = directory.currentSummary,
                isCustomDownloadDirectory = availability.isCustomDirectory,
                downloadDirectoryPermissionLost = directory.permissionLost,
                downloadDirectoryChangeEnabled = directory.changeEnabled,
                onPickDownloadDirectory = directory.onPickRequested,
                onResetDownloadDirectory = directory.onResetRequested,
                downloadFileNameTemplate = downloadFileNameTemplate,
                onDownloadFileNameTemplateChange = onDownloadFileNameTemplateChange,
                maxCacheSizeBytes = maxCacheSizeBytes,
                onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                onOpenStorageDetails = onOpenStorageDetails,
                storageDetails = storageDetails,
                showClearCacheDialog = selection.showClearCacheDialog,
                onShowClearCacheDialogChange = { selection.showClearCacheDialog = it },
                clearAudioCache = selection.clearAudioCache,
                onClearAudioCacheChange = { selection.clearAudioCache = it },
                clearImageCache = selection.clearImageCache,
                onClearImageCacheChange = { selection.clearImageCache = it },
                clearSharedMediaCache = selection.clearSharedMediaCache,
                onClearSharedMediaCacheChange = { selection.clearSharedMediaCache = it },
                clearLyricsCache = selection.clearLyricsCache,
                onClearLyricsCacheChange = { selection.clearLyricsCache = it },
                clearNeteasePlaylistCache = selection.clearNeteasePlaylistCache,
                onClearNeteasePlaylistCacheChange = { selection.clearNeteasePlaylistCache = it },
                clearBiliFavoriteCache = selection.clearBiliFavoriteCache,
                onClearBiliFavoriteCacheChange = { selection.clearBiliFavoriteCache = it },
                clearBiliArchiveCache = selection.clearBiliArchiveCache,
                onClearBiliArchiveCacheChange = { selection.clearBiliArchiveCache = it },
                clearYoutubePlaylistCache = selection.clearYoutubePlaylistCache,
                onClearYoutubePlaylistCacheChange = { selection.clearYoutubePlaylistCache = it },
                clearServerBrowseCache = selection.clearServerBrowseCache,
                onClearServerBrowseCacheChange = { selection.clearServerBrowseCache = it },
                clearLogFiles = selection.clearLogFiles,
                onClearLogFilesChange = { selection.clearLogFiles = it },
                clearCrashLogs = selection.clearCrashLogs,
                onClearCrashLogsChange = { selection.clearCrashLogs = it },
                onClearCacheClick = onClearCacheClick,
                cardIndex = cardIndex,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }
    }
}

internal fun LazyListScope.settingsStorageCacheDetailsItem(
    storageDetails: StorageUsageSummary,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    item(key = "${SettingsPage.StorageCacheDetails.name}:content") {
        StorageCacheDetailsContent(
            storageDetails = storageDetails,
            isScanning = isScanning,
            onRefresh = onRefresh,
            onClearCache = onClearCache,
            onOpenSystemSettings = onOpenSystemSettings
        )
    }
}

internal fun openStorageSystemSettings(context: Context, onFailure: () -> Unit) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        )
        context.startActivity(intent)
    }.onFailure { onFailure() }
}

internal fun LazyListScope.settingsDownloadsPageItems(
    onNavigateToDownloadManager: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: () -> Unit
) {
    item(key = "${SettingsPage.Downloads.name}:quality_follow_playback") {
        SettingsDownloadQualityFollowPlaybackCard(
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished,
            modifier = Modifier.animateItem()
        )
    }
    miuixSettingsSectionCardItem("${SettingsPage.Downloads.name}:content") {
        SettingsDownloadSection(
            expanded = true,
            arrowRotation = 0f,
            onExpandedChange = {},
            showHeader = false,
            onNavigateToDownloadManager = onNavigateToDownloadManager,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
    }
}
