package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

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

import moe.ouom.neriplayer.ui.settings.SettingRowIcon
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ExpandableHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.naming.DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
import moe.ouom.neriplayer.core.download.naming.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.download.naming.renderManagedDownloadBaseName
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.ui.settings.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.storage.CacheSizePolicy
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget

private val DOWNLOAD_FILE_NAME_PLACEHOLDERS = listOf(
    "%title%",
    "%artist%",
    "%album%",
    "%source%",
    "%id%",
    "%audioId%",
    "%subAudioId%",
    "%hash%"
)

internal fun buildDownloadFileNameTemplatePreview(template: String?): String {
    return renderManagedDownloadBaseName(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        source = "网易云",
        songId = "123456",
        audioId = "456789",
        subAudioId = "789012",
        template = normalizeDownloadFileNameTemplate(template)
            ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    )
}

internal fun insertDownloadFileNamePlaceholder(
    value: TextFieldValue,
    placeholder: String
): TextFieldValue {
    val textLength = value.text.length
    val start = value.selection.start.coerceIn(0, textLength)
    val end = value.selection.end.coerceIn(0, textLength)
    val rangeStart = minOf(start, end)
    val rangeEnd = maxOf(start, end)
    val updatedText = buildString(textLength - (rangeEnd - rangeStart) + placeholder.length) {
        append(value.text, 0, rangeStart)
        append(placeholder)
        append(value.text, rangeEnd, textLength)
    }
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(rangeStart + placeholder.length)
    )
}

@Composable
internal fun SettingsStorageCacheSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    currentDownloadDirectorySummary: String,
    isCustomDownloadDirectory: Boolean,
    downloadDirectoryPermissionLost: Boolean = false,
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
    clearSharedMediaCache: Boolean,
    onClearSharedMediaCacheChange: (Boolean) -> Unit,
    clearLyricsCache: Boolean,
    onClearLyricsCacheChange: (Boolean) -> Unit,
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
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    isStorageScanning: Boolean = false,
    hasStorageScan: Boolean = storageDetails != StorageUsageSummary.Empty,
    isClearing: Boolean = false,
    scanFailed: Boolean = false,
    onRetryScan: () -> Unit = {},
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val composeResources = LocalResources.current
    var clearOtherCache by rememberSaveable { mutableStateOf(false) }
    var retainRecentSongs by rememberSaveable { mutableStateOf(0) }
    val showDownloadFileNameDialog = remember { mutableStateOf(false) }
    var pendingDownloadFileNameTemplate by rememberSaveable(
        stateSaver = TextFieldValue.Saver
    ) {
        mutableStateOf(
            TextFieldValue(downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)
        )
    }

    androidx.compose.runtime.LaunchedEffect(downloadFileNameTemplate) {
        val savedValue = downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
        if (pendingDownloadFileNameTemplate.text != savedValue) {
            pendingDownloadFileNameTemplate = TextFieldValue(savedValue)
        }
    }

    val effectiveTemplate = normalizeDownloadFileNameTemplate(
        pendingDownloadFileNameTemplate.text
    ) ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    val currentSavedTemplate = downloadFileNameTemplate ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    fun dismissDownloadFileNameDialog() {
        pendingDownloadFileNameTemplate = TextFieldValue(currentSavedTemplate)
        showDownloadFileNameDialog.value = false
    }
    val samplePreview = buildDownloadFileNameTemplatePreview(effectiveTemplate)
    val canApplyDownloadFileNameTemplate = effectiveTemplate != currentSavedTemplate
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.SdStorage,
            title = stringResource(CoreCommonR.string.settings_storage_cache),
            subtitleCollapsed = stringResource(CoreCommonR.string.settings_storage_expand),
            subtitleExpanded = stringResource(CoreCommonR.string.settings_login_platforms_collapse),
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
                    title = stringResource(CoreCommonR.string.settings_storage_download_section),
                    description = stringResource(CoreCommonR.string.settings_storage_download_section_desc)
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
                            Text(stringResource(CoreCommonR.string.settings_download_directory_desc))
                            Text(
                                text = stringResource(
                                    CoreCommonR.string.settings_download_directory_current,
                                    currentDownloadDirectorySummary
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(CoreCommonR.string.settings_download_directory_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (downloadDirectoryPermissionLost) {
                                Text(
                                    text = stringResource(
                                        CoreCommonR.string.settings_download_directory_permission_lost
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (!downloadDirectoryChangeEnabled) {
                                Text(
                                    text = stringResource(
                                        CoreCommonR.string.settings_download_directory_change_blocked_active_download
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
                            Text(stringResource(CoreCommonR.string.settings_download_directory_choose))
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
                            SettingRowIcon(
                                key = "download_directory_uri",
                                Icons.Outlined.Restore,
                                contentDescription = stringResource(CoreCommonR.string.settings_download_directory_reset),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        headlineContent = { Text(stringResource(CoreCommonR.string.settings_download_directory_reset)) },
                        supportingContent = {
                            Text(stringResource(CoreCommonR.string.settings_download_directory_reset_desc))
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
                    title = stringResource(CoreCommonR.string.settings_storage_filename_section),
                    description = stringResource(CoreCommonR.string.settings_storage_filename_section_desc)
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
                            Text(stringResource(CoreCommonR.string.settings_download_file_name_format_desc))
                            Text(
                                text = effectiveTemplate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    CoreCommonR.string.settings_download_file_name_format_preview,
                                    samplePreview
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    trailingContent = {
                        Text(
                            text = stringResource(CoreCommonR.string.action_details),
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
                    title = stringResource(CoreCommonR.string.settings_storage_cache_limit_section),
                    description = stringResource(CoreCommonR.string.settings_storage_cache_limit_section_desc)
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
                                stringResource(CoreCommonR.string.settings_cache_unlimited)
                            sliderValue >= 1024f ->
                                composeResources.getString(
                                    CoreCommonR.string.settings_cache_size_gb,
                                    sliderValue / 1024
                                )
                            else ->
                                composeResources.getString(
                                    CoreCommonR.string.settings_cache_size_mb,
                                    sliderValue.toInt()
                                )
                        }

                        Column {
                            Text(
                                text = if (
                                    sliderValue < CacheSizePolicy.CACHE_SIZE_SLIDER_NO_CACHE_THRESHOLD_MB
                                ) {
                                    stringResource(CoreCommonR.string.settings_no_cache)
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
                                stringResource(CoreCommonR.string.settings_cache_notice),
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
                    title = stringResource(CoreCommonR.string.settings_storage_cache_clear_section),
                    description = stringResource(CoreCommonR.string.settings_storage_cache_clear_section_desc)
                )
                ListItem(
                    headlineContent = { Text(stringResource(CoreCommonR.string.settings_clear_cache)) },
                    supportingContent = { Text(stringResource(CoreCommonR.string.settings_clear_cache_desc)) },
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
                                Text(stringResource(CoreCommonR.string.action_details))
                            }

                            MiuixSettingsOutlinedButton(onClick = { onShowClearCacheDialogChange(true) }) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(CoreCommonR.string.action_clear))
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
        StorageCacheCleanupSheet(
            storageDetails = storageDetails,
            isScanning = isStorageScanning,
            hasScanned = hasStorageScan,
            isClearing = isClearing,
            scanFailed = scanFailed,
            onRetryScan = onRetryScan,
            options = StorageCacheClearOptions(
                audioCache = clearAudioCache,
                imageCache = clearImageCache,
                sharedMedia = clearSharedMediaCache,
                lyricsCache = clearLyricsCache,
                neteasePlaylistCache = clearNeteasePlaylistCache,
                biliFavoriteCache = clearBiliFavoriteCache,
                biliArchiveCache = clearBiliArchiveCache,
                youtubePlaylistCache = clearYoutubePlaylistCache,
                logFiles = clearLogFiles,
                crashLogs = clearCrashLogs,
                otherCache = clearOtherCache,
                retainRecentSongs = retainRecentSongs
            ),
            onOptionsChange = { options ->
                clearOtherCache = options.otherCache
                retainRecentSongs = options.retainRecentSongs
                onClearAudioCacheChange(options.audioCache)
                onClearImageCacheChange(options.imageCache)
                onClearSharedMediaCacheChange(options.sharedMedia)
                onClearLyricsCacheChange(options.lyricsCache)
                onClearNeteasePlaylistCacheChange(options.neteasePlaylistCache)
                onClearBiliFavoriteCacheChange(options.biliFavoriteCache)
                onClearBiliArchiveCacheChange(options.biliArchiveCache)
                onClearYoutubePlaylistCacheChange(options.youtubePlaylistCache)
                onClearLogFilesChange(options.logFiles)
                onClearCrashLogsChange(options.crashLogs)
            },
            onClear = onClearCacheClick,
            onDismiss = { onShowClearCacheDialogChange(false) }
        )
    }

    if (showDownloadFileNameDialog.value) {
        MiuixSettingsDialog(
            onDismissRequest = ::dismissDownloadFileNameDialog,
            title = { Text(stringResource(CoreCommonR.string.settings_download_file_name_format)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MiuixSettingsTextField(
                        value = pendingDownloadFileNameTemplate,
                        onValueChange = { value ->
                            pendingDownloadFileNameTemplate = value.copy(
                                text = value.text.replace('\r', ' ').replace('\n', ' ')
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(CoreCommonR.string.settings_download_file_name_format_template))
                        },
                        minLines = 2,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        placeholder = {
                            Text(DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)
                        }
                    )
                    Text(
                        text = stringResource(CoreCommonR.string.settings_download_file_name_format_insert_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DOWNLOAD_FILE_NAME_PLACEHOLDERS.forEach { placeholder ->
                            AssistChip(
                                modifier = Modifier.heightIn(min = 40.dp),
                                onClick = {
                                    pendingDownloadFileNameTemplate =
                                        insertDownloadFileNamePlaceholder(
                                            value = pendingDownloadFileNameTemplate,
                                            placeholder = placeholder
                                        )
                                },
                                label = {
                                    Text(
                                        placeholder,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                shape = CircleShape,
                                border = null,
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.settings_download_file_name_format_preview_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = samplePreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        onDownloadFileNameTemplateChange(
                            normalizeDownloadFileNameTemplate(pendingDownloadFileNameTemplate.text)
                        )
                        showDownloadFileNameDialog.value = false
                    },
                    enabled = canApplyDownloadFileNameTemplate
                ) {
                    Text(stringResource(CoreCommonR.string.action_apply))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        pendingDownloadFileNameTemplate = TextFieldValue(
                            DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
                        )
                        onDownloadFileNameTemplateChange(null)
                        showDownloadFileNameDialog.value = false
                    },
                    enabled = currentSavedTemplate != DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE ||
                        pendingDownloadFileNameTemplate.text != DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
                ) {
                    Text(stringResource(CoreCommonR.string.action_reset))
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
