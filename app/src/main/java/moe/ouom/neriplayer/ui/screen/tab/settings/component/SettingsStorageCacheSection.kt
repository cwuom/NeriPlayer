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

import android.content.Intent
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
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.download.renderManagedDownloadBaseName
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.storage.StorageCacheKind
import moe.ouom.neriplayer.data.storage.StorageUsageItem
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.data.storage.analyzeStorageUsage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsCheckbox
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.util.formatFileSize

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
    showStorageDetails: Boolean,
    onShowStorageDetailsChange: (Boolean) -> Unit,
    storageDetails: StorageUsageSummary,
    onStorageDetailsChange: (StorageUsageSummary) -> Unit,
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
    clearPlatformListCache: Boolean,
    onClearPlatformListCacheChange: (Boolean) -> Unit,
    downloadStagingClearEnabled: Boolean,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isStorageDetailsLoading by remember { mutableStateOf(false) }
    var showDownloadFileNameDialog by remember { mutableStateOf(false) }
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
    val samplePreview = renderManagedDownloadBaseName(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        source = "网易云",
        template = effectiveTemplate
    )
    val canApplyDownloadFileNameTemplate = effectiveTemplate != currentSavedTemplate

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
            AutoSettingsListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DOWNLOAD_DIRECTORY_URI),
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

            AutoSettingsListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE),
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
                onClick = { showDownloadFileNameDialog = true }
            )

            AutoSettingsListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.MAX_CACHE_SIZE_BYTES),
                showDefaultIcon = false,
                supportingContent = {
                    val sizeMb = maxCacheSizeBytes / (1024 * 1024).toFloat()
                    var sliderValue by remember(sizeMb) { mutableFloatStateOf(sizeMb) }
                    val displaySize = if (sliderValue >= 1024) {
                        context.getString(R.string.settings_cache_size_gb, sliderValue / 1024)
                    } else {
                        context.getString(R.string.settings_cache_size_mb, sliderValue.toInt())
                    }

                    Column {
                        Text(
                            text = if (sliderValue < 10f) {
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
                                val newBytes = if (sliderValue < 10f) {
                                    0L
                                } else {
                                    (sliderValue * 1024 * 1024).toLong()
                                }
                                onMaxCacheSizeBytesChange(newBytes)
                            },
                            valueRange = 0f..(10 * 1024f),
                            steps = 0
                        )
                        Text(
                            stringResource(R.string.settings_cache_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiuixSettingsOutlinedButton(
                            enabled = !isStorageDetailsLoading,
                            onClick = {
                                onShowStorageDetailsChange(true)
                                isStorageDetailsLoading = true
                                scope.launch {
                                    onStorageDetailsChange(analyzeStorageUsage(context))
                                    isStorageDetailsLoading = false
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Info,
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
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    if (showStorageDetails) {
        MiuixSettingsDialog(
            onDismissRequest = { onShowStorageDetailsChange(false) },
            title = { Text(stringResource(R.string.storage_details_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isStorageDetailsLoading) {
                        Text(
                            stringResource(R.string.storage_details_loading),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            stringResource(R.string.storage_details_subtitle),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(12.dp))

                        storageDetails.sections.forEachIndexed { index, section ->
                            if (index > 0) {
                                Spacer(Modifier.height(12.dp))
                            }
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            section.items.forEach { item ->
                                StorageUsageRow(item)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.storage_details_total),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                formatFileSize(storageDetails.totalSizeBytes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiuixSettingsTextButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = "package:${context.packageName}".toUri()
                                context.startActivity(intent)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.storage_open_system_settings))
                    }
                    MiuixSettingsTextButton(onClick = { onShowStorageDetailsChange(false) }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    if (showClearCacheDialog) {
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
                        checked = clearPlatformListCache,
                        title = stringResource(R.string.storage_type_platform_list_cache),
                        description = cacheTypeDescription(
                            storageDetails = storageDetails,
                            kind = StorageCacheKind.PlatformList,
                            fallback = stringResource(R.string.storage_desc_platform_list_cache)
                        ),
                        onCheckedChange = onClearPlatformListCacheChange
                    )
                }
            },
            confirmButton = {
                val clearOptions = StorageCacheClearOptions(
                    audioCache = clearAudioCache,
                    imageCache = clearImageCache,
                    downloadStaging = clearDownloadStagingCache && downloadStagingClearEnabled,
                    sharedMedia = clearSharedMediaCache,
                    platformList = clearPlatformListCache
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

    if (showDownloadFileNameDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showDownloadFileNameDialog = false },
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
                        showDownloadFileNameDialog = false
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
                            showDownloadFileNameDialog = false
                        },
                        enabled = currentSavedTemplate != DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
                    ) {
                        Text(stringResource(R.string.action_reset))
                    }
                    MiuixSettingsTextButton(onClick = { showDownloadFileNameDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
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
private fun StorageUsageRow(item: StorageUsageItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.path.isNullOrBlank()) {
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.storage_details_file_count, item.fileCount),
                style = MaterialTheme.typography.labelSmall,
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
