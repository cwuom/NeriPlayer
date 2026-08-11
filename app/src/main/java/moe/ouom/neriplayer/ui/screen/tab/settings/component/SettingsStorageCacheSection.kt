package moe.ouom.neriplayer.ui.screen.tab.settings.component

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsStorageCacheSection
 * Updated: 2026/3/23
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.download.renderManagedDownloadBaseName
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.CacheSizePolicy
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.storage.StorageCacheKind
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsCheckbox
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.util.format.formatFileSize

@Composable
internal fun SettingsStorageCacheSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    currentDownloadDirectorySummary: String,
    isCustomDownloadDirectory: Boolean,
    downloadDirectoryChangeEnabled: Boolean,
    onPickDownloadDirectory: () -> Unit,
    onResetDownloadDirectory: () -> Unit,
    downloadFileNameTemplate: String?,
    onDownloadFileNameTemplateChange: (String?) -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onOpenStorageDetails: () -> Unit,
    storageDetails: StorageUsageSummary,
    showClearCacheDialog: Boolean,
    onShowClearCacheDialogChange: (Boolean) -> Unit,
    clearAudioCache: Boolean,
    onClearAudioCacheChange: (Boolean) -> Unit,
    clearImageCache: Boolean,
    onClearImageCacheChange: (Boolean) -> Unit,
    clearDownloadStagingCache: Boolean,
    onClearDownloadStagingCacheChange: (Boolean) -> Unit,
    clearSharedMediaCache: Boolean,
    onClearSharedMediaCacheChange: (Boolean) -> Unit,
    clearNeteasePlaylistCache: Boolean,
    onClearNeteasePlaylistCacheChange: (Boolean) -> Unit,
    clearBiliFavoriteCache: Boolean,
    onClearBiliFavoriteCacheChange: (Boolean) -> Unit,
    clearBiliArchiveCache: Boolean,
    onClearBiliArchiveCacheChange: (Boolean) -> Unit,
    clearYoutubePlaylistCache: Boolean,
    onClearYoutubePlaylistCacheChange: (Boolean) -> Unit,
    clearLogFiles: Boolean,
    onClearLogFilesChange: (Boolean) -> Unit,
    clearCrashLogs: Boolean,
    onClearCrashLogsChange: (Boolean) -> Unit,
    downloadStagingClearEnabled: Boolean,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val composeResources = LocalResources.current
    val showDownloadFileNameDialog = remember { mutableStateOf(false) }
    var pendingDownloadFileNameTemplate by rememberSaveable {
        mutableStateOf(downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)
    }

    androidx.compose.runtime.LaunchedEffect(downloadFileNameTemplate) {
        val savedValue = downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
        if (pendingDownloadFileNameTemplate != savedValue) {
            pendingDownloadFileNameTemplate = savedValue
        }
    }

    val effectiveTemplate = normalizeDownloadFileNameTemplate(
        pendingDownloadFileNameTemplate
    ) ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    val currentSavedTemplate = downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    fun dismissDownloadFileNameDialog() {
        pendingDownloadFileNameTemplate = currentSavedTemplate
        showDownloadFileNameDialog.value = false
    }
    val samplePreview = renderManagedDownloadBaseName(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        source = "网易云",
        template = effectiveTemplate
    )
    val canApplyDownloadFileNameTemplate = effectiveTemplate != currentSavedTemplate
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.SdStorage,
            title = stringResource(R.string.settings_storage_cache),
            subtitleCollapsed = stringResource(R.string.settings_storage_expand),
            subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            arrowRotation = arrowRotation
        )
    }

    LazyAnimatedVisibility(
        visible = expanded || !showHeader,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (showHeader) 16.dp else 0.dp,
                    end = if (showHeader) 8.dp else 0.dp,
                    bottom = if (showHeader) 8.dp else 0.dp
                )
        ) {
            if (shouldShowCard(0)) StorageDetailCard(
                showCard = !showHeader
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_storage_download_section),
                    description = stringResource(R.string.settings_storage_download_section_desc)
                )
                AutoSettingsListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DOWNLOAD_DIRECTORY_URI),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.settings_download_directory_desc))
                            Text(
                                text = stringResource(
                                    R.string.settings_download_directory_current,
                                    currentDownloadDirectorySummary
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_download_directory_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (!downloadDirectoryChangeEnabled) {
                                Text(
                                    text = stringResource(
                                        R.string.settings_download_directory_change_blocked_active_download
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    trailingContent = {
                        MiuixSettingsTextButton(
                            onClick = onPickDownloadDirectory,
                            enabled = downloadDirectoryChangeEnabled
                        ) {
                            Text(stringResource(R.string.settings_download_directory_choose))
                        }
                    },
                    modifier = Modifier
                        .settingsHighlightTarget(
                            targetId = "setting:download_directory_uri",
                            highlightTargetId = highlightTargetId,
                            highlightPulse = highlightPulse,
                            onHighlightFinished = onHighlightFinished
                        )
                        .alpha(if (downloadDirectoryChangeEnabled) 1f else 0.6f),
                    enabled = downloadDirectoryChangeEnabled,
                    onClick = onPickDownloadDirectory
                )

                AnimatedVisibility(visible = isCustomDownloadDirectory) {
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Outlined.Restore,
                                contentDescription = stringResource(R.string.settings_download_directory_reset),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.settings_download_directory_reset)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_download_directory_reset_desc))
                        },
                        modifier = Modifier
                            .alpha(if (downloadDirectoryChangeEnabled) 1f else 0.6f)
                            .settingsItemClickable(
                                enabled = downloadDirectoryChangeEnabled,
                                onClick = onResetDownloadDirectory
                            ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
            if (cardIndex == null) StorageDetailGap(showHeader)

            if (shouldShowCard(1)) StorageDetailCard(
                showCard = !showHeader
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_storage_filename_section),
                    description = stringResource(R.string.settings_storage_filename_section_desc)
                )
                AutoSettingsListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.TextSnippet,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.settings_download_file_name_format_desc))
                            Text(
                                text = effectiveTemplate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_download_file_name_format_preview,
                                    samplePreview
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    trailingContent = {
                        Text(
                            text = stringResource(R.string.action_details),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished,
                    onClick = { showDownloadFileNameDialog.value = true }
                )
            }
            if (cardIndex == null) StorageDetailGap(showHeader)

            if (shouldShowCard(2)) StorageDetailCard(
                showCard = !showHeader
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_storage_cache_limit_section),
                    description = stringResource(R.string.settings_storage_cache_limit_section_desc)
                )
                AutoSettingsListItem(
                    setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.MAX_CACHE_SIZE_BYTES),
                    showDefaultIcon = false,
                    supportingContent = {
                        var sliderValue by remember(maxCacheSizeBytes) {
                            mutableFloatStateOf(CacheSizePolicy.toSliderValue(maxCacheSizeBytes))
                        }
                        val displaySize = when {
                            sliderValue >= CacheSizePolicy.CACHE_SIZE_SLIDER_UNLIMITED_VALUE ->
                                stringResource(R.string.settings_cache_unlimited)
                            sliderValue >= 1024f ->
                                composeResources.getString(
                                    R.string.settings_cache_size_gb,
                                    sliderValue / 1024
                                )
                            else ->
                                composeResources.getString(
                                    R.string.settings_cache_size_mb,
                                    sliderValue.toInt()
                                )
                        }

                        Column {
                            Text(
                                text = if (
                                    sliderValue < CacheSizePolicy.CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB
                                ) {
                                    stringResource(R.string.settings_no_cache)
                                } else {
                                    displaySize
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MiuixSettingsSlider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    onMaxCacheSizeBytesChange(
                                        CacheSizePolicy.fromSliderValue(sliderValue)
                                    )
                                },
                                valueRange = 0f..CacheSizePolicy.CACHE_SIZE_SLIDER_UNLIMITED_VALUE,
                                steps = 0
                            )
                            Text(
                                stringResource(R.string.settings_cache_notice),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                )
            }
            if (cardIndex == null) StorageDetailGap(showHeader)

            if (shouldShowCard(3)) StorageDetailCard(
                showCard = !showHeader
            ) {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_storage_cache_clear_section),
                    description = stringResource(R.string.settings_storage_cache_clear_section_desc)
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiuixSettingsOutlinedButton(
                                onClick = onOpenStorageDetails
                            ) {
                                Icon(
                                    Icons.Outlined.SdStorage,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_details))
                            }

                            MiuixSettingsOutlinedButton(onClick = { onShowClearCacheDialogChange(true) }) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                    },
                    modifier = Modifier.settingsHighlightTarget(
                        targetId = "manual:clear_cache",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }

    val shouldRenderCacheDialogs = cardIndex == null || cardIndex == 3

    if (shouldRenderCacheDialogs && showClearCacheDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { onShowClearCacheDialogChange(false) },
            title = { Text(stringResource(R.string.settings_confirm_clear_cache)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_clear_cache_warning))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.settings_select_cache_types),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    CacheTypeRow(
                        checked = clearAudioCache,
                        title = stringResource(R.string.settings_audio_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.Audio,
                            fallback = stringResource(R.string.settings_audio_cache_desc)
                        ),
                        onCheckedChange = onClearAudioCacheChange
                    )
                    CacheTypeRow(
                        checked = clearImageCache,
                        title = stringResource(R.string.settings_image_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.Image,
                            fallback = stringResource(R.string.settings_image_cache_desc)
                        ),
                        onCheckedChange = onClearImageCacheChange
                    )
                    CacheTypeRow(
                        checked = clearDownloadStagingCache,
                        title = stringResource(R.string.storage_type_download_staging),
                        description = if (downloadStagingClearEnabled) {
                            cacheTypeDescription(
                                storageDetails = storageDetails,
                                kind = StorageCacheKind.DownloadStaging,
                                fallback = stringResource(R.string.storage_desc_download_staging)
                            )
                        } else {
                            stringResource(R.string.storage_download_staging_active_desc)
                        },
                        enabled = downloadStagingClearEnabled,
                        onCheckedChange = onClearDownloadStagingCacheChange
                    )
                    CacheTypeRow(
                        checked = clearSharedMediaCache,
                        title = stringResource(R.string.storage_type_shared_media),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.SharedMedia,
                            fallback = stringResource(R.string.storage_desc_shared_media)
                        ),
                        onCheckedChange = onClearSharedMediaCacheChange
                    )
                    CacheTypeRow(
                        checked = clearNeteasePlaylistCache,
                        title = stringResource(R.string.storage_type_netease_playlist_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.NeteasePlaylist,
                            fallback = stringResource(R.string.storage_desc_netease_playlist_cache)
                        ),
                        onCheckedChange = onClearNeteasePlaylistCacheChange
                    )
                    CacheTypeRow(
                        checked = clearBiliFavoriteCache,
                        title = stringResource(R.string.storage_type_bili_favorite_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.BiliFavorite,
                            fallback = stringResource(R.string.storage_desc_bili_favorite_cache)
                        ),
                        onCheckedChange = onClearBiliFavoriteCacheChange
                    )
                    CacheTypeRow(
                        checked = clearBiliArchiveCache,
                        title = stringResource(R.string.storage_type_bili_archive_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.BiliArchive,
                            fallback = stringResource(R.string.storage_desc_bili_archive_cache)
                        ),
                        onCheckedChange = onClearBiliArchiveCacheChange
                    )
                    CacheTypeRow(
                        checked = clearYoutubePlaylistCache,
                        title = stringResource(R.string.storage_type_youtube_playlist_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.YouTubePlaylist,
                            fallback = stringResource(R.string.storage_desc_youtube_playlist_cache)
                        ),
                        onCheckedChange = onClearYoutubePlaylistCacheChange
                    )
                    CacheTypeRow(
                        checked = clearLogFiles,
                        title = stringResource(R.string.storage_type_log_files),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.LogFiles,
                            fallback = stringResource(R.string.storage_desc_log_files)
                        ),
                        onCheckedChange = onClearLogFilesChange
                    )
                    CacheTypeRow(
                        checked = clearCrashLogs,
                        title = stringResource(R.string.storage_type_crash_logs),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.CrashLogs,
                            fallback = stringResource(R.string.storage_desc_crash_logs)
                        ),
                        onCheckedChange = onClearCrashLogsChange
                    )
                }
            },
            confirmButton = {
                val clearOptions = StorageCacheClearOptions(
                    audioCache = clearAudioCache,
                    imageCache = clearImageCache,
                    downloadStaging = clearDownloadStagingCache && downloadStagingClearEnabled,
                    sharedMedia = clearSharedMediaCache,
                    neteasePlaylistCache = clearNeteasePlaylistCache,
                    biliFavoriteCache = clearBiliFavoriteCache,
                    biliArchiveCache = clearBiliArchiveCache,
                    youtubePlaylistCache = clearYoutubePlaylistCache,
                    logFiles = clearLogFiles,
                    crashLogs = clearCrashLogs
                )
                MiuixSettingsTextButton(
                    onClick = {
                        onClearCacheClick(clearOptions)
                        onShowClearCacheDialogChange(false)
                    },
                    enabled = clearOptions.hasSelection
                ) {
                    Text(
                        stringResource(R.string.action_confirm_clear),
                        color = if (clearOptions.hasSelection) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { onShowClearCacheDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDownloadFileNameDialog.value) {
        MiuixSettingsDialog(
            onDismissRequest = ::dismissDownloadFileNameDialog,
            title = { Text(stringResource(R.string.settings_download_file_name_format)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_download_file_name_format_desc))
                    MiuixSettingsTextField(
                        value = pendingDownloadFileNameTemplate,
                        onValueChange = { pendingDownloadFileNameTemplate = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_download_file_name_format_supported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_download_file_name_format_preview,
                            samplePreview
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        onDownloadFileNameTemplateChange(
                            normalizeDownloadFileNameTemplate(pendingDownloadFileNameTemplate)
                        )
                        showDownloadFileNameDialog.value = false
                    },
                    enabled = canApplyDownloadFileNameTemplate
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiuixSettingsTextButton(
                        onClick = {
                            pendingDownloadFileNameTemplate = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
                            onDownloadFileNameTemplateChange(null)
                            showDownloadFileNameDialog.value = false
                        },
                        enabled = currentSavedTemplate != DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
                    ) {
                        Text(stringResource(R.string.action_reset))
                    }
                    MiuixSettingsTextButton(onClick = ::dismissDownloadFileNameDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }
}

@Composable
private fun StorageDetailCard(
    showCard: Boolean,
    content: @Composable () -> Unit
) {
    if (showCard) {
        MiuixSettingsSectionCard(
            content = content
        )
    } else {
        content()
    }
}

@Composable
private fun StorageDetailGap(showHeader: Boolean) {
    if (!showHeader) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CacheTypeRow(
    checked: Boolean,
    title: String,
    description: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixSettingsCheckbox(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) }
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun cacheTypeDescription(
    storageDetails: StorageUsageSummary,
    kind: StorageCacheKind,
    fallback: String
): String {
    val size = storageDetails.sizeOf(kind)
    return if (size > 0L) {
        stringResource(R.string.storage_clear_type_size, fallback, formatFileSize(size))
    } else {
        fallback
    }
}
