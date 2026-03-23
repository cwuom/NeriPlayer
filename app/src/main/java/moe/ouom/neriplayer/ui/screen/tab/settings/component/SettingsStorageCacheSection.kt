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
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatFileSize

@Composable
internal fun SettingsStorageCacheSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    currentDownloadDirectorySummary: String,
    isCustomDownloadDirectory: Boolean,
    onPickDownloadDirectory: () -> Unit,
    onResetDownloadDirectory: () -> Unit,
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    showStorageDetails: Boolean,
    onShowStorageDetailsChange: (Boolean) -> Unit,
    storageDetails: Map<String, Long>,
    onStorageDetailsChange: (Map<String, Long>) -> Unit,
    showClearCacheDialog: Boolean,
    onShowClearCacheDialogChange: (Boolean) -> Unit,
    clearAudioCache: Boolean,
    onClearAudioCacheChange: (Boolean) -> Unit,
    clearImageCache: Boolean,
    onClearImageCacheChange: (Boolean) -> Unit,
    onClearCacheClick: (clearAudio: Boolean, clearImage: Boolean) -> Unit
) {
    val context = LocalContext.current

    ExpandableHeader(
        icon = Icons.Outlined.SdStorage,
        title = stringResource(R.string.settings_storage_cache),
        subtitleCollapsed = stringResource(R.string.settings_storage_expand),
        subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
        expanded = expanded,
        onToggle = { onExpandedChange(!expanded) },
        arrowRotation = arrowRotation
    )

    LazyAnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
        ) {
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.settings_download_directory),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                headlineContent = { Text(stringResource(R.string.settings_download_directory)) },
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
                    }
                },
                trailingContent = {
                    HapticTextButton(onClick = onPickDownloadDirectory) {
                        Text(stringResource(R.string.settings_download_directory_choose))
                    }
                },
                modifier = Modifier.settingsItemClickable(onClick = onPickDownloadDirectory),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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
                    modifier = Modifier.settingsItemClickable(onClick = onResetDownloadDirectory),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_cache_limit)) },
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
                        Slider(
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
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onStorageDetailsChange(calculateStorageDetails(context))
                                onShowStorageDetailsChange(true)
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

                        OutlinedButton(onClick = { onShowClearCacheDialogChange(true) }) {
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
        AlertDialog(
            onDismissRequest = { onShowStorageDetailsChange(false) },
            title = { Text(stringResource(R.string.storage_details_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.storage_details_subtitle),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(12.dp))

                    storageDetails.forEach { (name, size) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatFileSize(size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
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
                            formatFileSize(storageDetails.values.sum()),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticTextButton(
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
                    HapticTextButton(onClick = { onShowStorageDetailsChange(false) }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { onShowClearCacheDialogChange(false) },
            title = { Text(stringResource(R.string.settings_confirm_clear_cache)) },
            text = {
                Column {
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
                        description = stringResource(R.string.settings_audio_cache_desc),
                        onCheckedChange = onClearAudioCacheChange
                    )
                    CacheTypeRow(
                        checked = clearImageCache,
                        title = stringResource(R.string.settings_image_cache),
                        description = stringResource(R.string.settings_image_cache_desc),
                        onCheckedChange = onClearImageCacheChange
                    )
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        onClearCacheClick(clearAudioCache, clearImageCache)
                        onShowClearCacheDialogChange(false)
                    },
                    enabled = clearAudioCache || clearImageCache
                ) {
                    Text(
                        stringResource(R.string.action_confirm_clear),
                        color = if (clearAudioCache || clearImageCache) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { onShowClearCacheDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
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

private fun calculateStorageDetails(context: android.content.Context): Map<String, Long> {
    val details = linkedMapOf<String, Long>()
    return runCatching {
        val mediaCacheDir = File(context.cacheDir, "media_cache")
        details[context.getString(R.string.storage_type_audio_cache)] =
            mediaCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val imageCacheDir = File(context.cacheDir, "image_cache")
        details[context.getString(R.string.storage_type_image_cache)] =
            imageCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        details[context.getString(R.string.storage_type_downloaded_music)] =
            runBlocking {
                ManagedDownloadStorage.listDownloadedAudio(context).sumOf { it.sizeBytes }
            }

        val logDir = context.getExternalFilesDir(null)?.let { File(it, "logs") }
        details[context.getString(R.string.storage_type_log_files)] =
            logDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

        val crashDir = context.getExternalFilesDir(null)?.let { File(it, "crashes") }
        details[context.getString(R.string.storage_type_crash_logs)] =
            crashDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

        details[context.getString(R.string.storage_type_other_cache)] = context.cacheDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    !file.path.contains("media_cache") &&
                    !file.path.contains("image_cache")
            }
            .sumOf { it.length() }

        details[context.getString(R.string.storage_type_app_data)] =
            context.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        details
    }.getOrElse {
        linkedMapOf(context.getString(R.string.storage_type_error) to 0L)
    }
}
