package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsCheckbox
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard

internal fun parseRetainRecentSongs(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageCacheCleanupSheet(
    storageDetails: StorageUsageSummary,
    isScanning: Boolean,
    hasScanned: Boolean,
    isClearing: Boolean,
    options: StorageCacheClearOptions,
    onOptionsChange: (StorageCacheClearOptions) -> Unit,
    onClear: (StorageCacheClearOptions) -> Unit,
    onDismiss: () -> Unit,
    scanFailed: Boolean = false,
    onRetryScan: () -> Unit = {}
) {
    val clearing by rememberUpdatedState(isClearing)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !clearing }
    )
    DensityScaledModalBottomSheet(
        onDismissRequest = { if (!isClearing) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !isClearing
    ) {
        StorageCacheCleanupContent(
            storageDetails, isScanning, hasScanned, isClearing, options,
            onOptionsChange, onClear, onDismiss, scanFailed, onRetryScan
        )
    }
}

@Composable
internal fun StorageCacheCleanupContent(
    storageDetails: StorageUsageSummary,
    isScanning: Boolean,
    hasScanned: Boolean,
    isClearing: Boolean,
    options: StorageCacheClearOptions,
    onOptionsChange: (StorageCacheClearOptions) -> Unit,
    onClear: (StorageCacheClearOptions) -> Unit,
    onDismiss: () -> Unit,
    scanFailed: Boolean = false,
    onRetryScan: () -> Unit = {}
) {
    var retentionInput by rememberSaveable { mutableStateOf(options.retainRecentSongs.toString()) }
    LaunchedEffect(options.retainRecentSongs) {
        if (parseRetainRecentSongs(retentionInput) != options.retainRecentSongs) {
            retentionInput = options.retainRecentSongs.toString()
        }
    }
    val retainedCount = parseRetainRecentSongs(retentionInput)
    val waitingForScan = isScanning || !hasScanned
    val enabled = !waitingForScan && !isClearing && !scanFailed
    val selectedOptions = options.copy(
        retainRecentSongs = retainedCount ?: options.retainRecentSongs
    )
    val choices = cleanupChoices(selectedOptions)
    val (mediaChoices, otherChoices) = choices.partition {
        it.kind == StorageCacheKind.Audio || it.kind == StorageCacheKind.Image
    }
    val allSelected = choices.all { it.checked }
    val selectedSize = choices.filter { it.checked }.sumOf { storageDetails.sizeOf(it.kind) }

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding()) {
        Column(
            modifier = Modifier.weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .bottomSheetScrollGuard()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(CoreCommonR.string.storage_cleanup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            MiuixSettingsSectionCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (scanFailed) stringResource(CoreCommonR.string.storage_scan_failed)
                        else if (waitingForScan) stringResource(CoreCommonR.string.storage_scan_title)
                        else stringResource(CoreCommonR.string.storage_cleanable_total),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (scanFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanFailed) {
                        Text(
                            stringResource(CoreCommonR.string.storage_scan_failed_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        MiuixSettingsTextButton(onClick = onRetryScan, enabled = !isClearing) {
                            Text(stringResource(CoreCommonR.string.storage_scan_refresh))
                        }
                    } else if (waitingForScan) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(CoreCommonR.string.storage_cleanup_waiting_scan), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            formatFileSize(storageDetails.cleanableSizeBytes),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(CoreCommonR.string.storage_cleanup_selected_size, formatFileSize(selectedSize)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(CoreCommonR.string.storage_cleanup_size_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(CoreCommonR.string.settings_select_cache_types),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                MiuixSettingsTextButton(
                    enabled = enabled,
                    onClick = {
                        val next = !allSelected
                        onOptionsChange(selectedOptions.copy(
                            audioCache = next, imageCache = next, sharedMedia = next,
                            lyricsCache = next, neteasePlaylistCache = next,
                            biliFavoriteCache = next, biliArchiveCache = next,
                            youtubePlaylistCache = next, logFiles = next, crashLogs = next,
                            otherCache = next
                        ))
                    }
                ) {
                    Text(stringResource(if (allSelected) CoreCommonR.string.storage_cleanup_deselect_all else CoreCommonR.string.storage_cleanup_select_all))
                }
            }
            MiuixSettingsSectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(CoreCommonR.string.storage_cleanup_media_group), style = MaterialTheme.typography.titleSmall)
                    mediaChoices.forEach { choice ->
                        CacheCleanupChoiceRow(choice, storageDetails, waitingForScan || scanFailed, enabled) {
                            onOptionsChange(choice.withChecked(selectedOptions, it))
                        }
                    }
                    HorizontalDivider()
                    MiuixSettingsTextField(
                        value = retentionInput,
                        onValueChange = { input ->
                            retentionInput = input
                            parseRetainRecentSongs(input)?.let { count ->
                                onOptionsChange(options.copy(retainRecentSongs = count))
                            }
                        },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(CoreCommonR.string.storage_cleanup_retain_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (retainedCount == null) {
                        Text(
                            stringResource(CoreCommonR.string.storage_cleanup_retain_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        stringResource(CoreCommonR.string.storage_cleanup_retain_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(CoreCommonR.string.storage_cleanup_retain_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MiuixSettingsSectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(CoreCommonR.string.storage_cleanup_other_group), style = MaterialTheme.typography.titleSmall)
                    otherChoices.forEach { choice ->
                        CacheCleanupChoiceRow(choice, storageDetails, waitingForScan || scanFailed, enabled) { checked ->
                            onOptionsChange(choice.withChecked(selectedOptions, checked))
                        }
                    }
                }
            }
            Text(
                stringResource(CoreCommonR.string.settings_clear_cache_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiuixSettingsTextButton(onClick = onDismiss, enabled = !isClearing) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
            MiuixSettingsButton(
                modifier = Modifier.weight(1f),
                enabled = enabled && retainedCount != null && selectedOptions.hasSelection,
                onClick = { onClear(selectedOptions) }
            ) {
                Text(stringResource(if (isClearing) CoreCommonR.string.storage_cleanup_running else CoreCommonR.string.action_confirm_clear))
            }
        }
    }
}

private data class CacheCleanupChoice(
    val kind: StorageCacheKind,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val checked: Boolean,
    val withChecked: (StorageCacheClearOptions, Boolean) -> StorageCacheClearOptions
)

private fun cleanupChoices(options: StorageCacheClearOptions) = listOf(
    CacheCleanupChoice(
        kind = StorageCacheKind.Audio,
        title = CoreCommonR.string.settings_audio_cache,
        description = CoreCommonR.string.settings_audio_cache_desc,
        checked = options.audioCache
    ) { current, checked -> current.copy(audioCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.Image,
        title = CoreCommonR.string.settings_image_cache,
        description = CoreCommonR.string.settings_image_cache_desc,
        checked = options.imageCache
    ) { current, checked -> current.copy(imageCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.SharedMedia,
        title = CoreCommonR.string.storage_type_shared_media,
        description = CoreCommonR.string.storage_desc_shared_media,
        checked = options.sharedMedia
    ) { current, checked -> current.copy(sharedMedia = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.Lyrics,
        title = CoreCommonR.string.storage_type_lyrics_cache,
        description = CoreCommonR.string.storage_desc_lyrics_cache,
        checked = options.lyricsCache
    ) { current, checked -> current.copy(lyricsCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.NeteasePlaylist,
        title = CoreCommonR.string.storage_type_netease_playlist_cache,
        description = CoreCommonR.string.storage_desc_netease_playlist_cache,
        checked = options.neteasePlaylistCache
    ) { current, checked -> current.copy(neteasePlaylistCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.BiliFavorite,
        title = CoreCommonR.string.storage_type_bili_favorite_cache,
        description = CoreCommonR.string.storage_desc_bili_favorite_cache,
        checked = options.biliFavoriteCache
    ) { current, checked -> current.copy(biliFavoriteCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.BiliArchive,
        title = CoreCommonR.string.storage_type_bili_archive_cache,
        description = CoreCommonR.string.storage_desc_bili_archive_cache,
        checked = options.biliArchiveCache
    ) { current, checked -> current.copy(biliArchiveCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.YouTubePlaylist,
        title = CoreCommonR.string.storage_type_youtube_playlist_cache,
        description = CoreCommonR.string.storage_desc_youtube_playlist_cache,
        checked = options.youtubePlaylistCache
    ) { current, checked -> current.copy(youtubePlaylistCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.Other,
        title = CoreCommonR.string.storage_type_other_cache,
        description = CoreCommonR.string.storage_desc_other_cache,
        checked = options.otherCache
    ) { current, checked -> current.copy(otherCache = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.LogFiles,
        title = CoreCommonR.string.storage_type_log_files,
        description = CoreCommonR.string.storage_desc_log_files,
        checked = options.logFiles
    ) { current, checked -> current.copy(logFiles = checked) },
    CacheCleanupChoice(
        kind = StorageCacheKind.CrashLogs,
        title = CoreCommonR.string.storage_type_crash_logs,
        description = CoreCommonR.string.storage_desc_crash_logs,
        checked = options.crashLogs
    ) { current, checked -> current.copy(crashLogs = checked) }
)

@Composable
private fun CacheCleanupChoiceRow(
    choice: CacheCleanupChoice,
    storageDetails: StorageUsageSummary,
    waitingForScan: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = choice.checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MiuixSettingsCheckbox(checked = choice.checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(choice.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(choice.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (waitingForScan) stringResource(CoreCommonR.string.storage_cleanup_size_unavailable)
            else formatFileSize(storageDetails.sizeOf(choice.kind)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
