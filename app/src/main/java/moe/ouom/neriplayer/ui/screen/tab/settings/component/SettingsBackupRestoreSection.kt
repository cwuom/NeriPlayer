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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component/SettingsBackupRestoreSection
 * Updated: 2026/3/23
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.util.HapticTextButton

@Composable
internal fun SettingsBackupRestoreSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    currentPlaylistCount: Int,
    backupRestoreUiState: BackupRestoreUiState,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onClearExportStatus: () -> Unit,
    onClearImportStatus: () -> Unit,
    silentGitHubSyncFailure: Boolean,
    onSilentGitHubSyncFailureChange: (Boolean) -> Unit,
    onOpenGitHubConfig: () -> Unit,
    onOpenClearGitHubConfig: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val githubVm: GitHubSyncViewModel = viewModel()
    val githubState by githubVm.uiState.collectAsState()

    var showPlayHistoryModeDialog by remember { mutableStateOf(false) }
    val storage = remember(context) { SecureTokenStorage(context) }
    val currentMode = remember { mutableStateOf(storage.getPlayHistoryUpdateMode()) }
    var dataSaverMode by remember { mutableStateOf(storage.isDataSaverMode()) }
    var pendingDataSaverMode by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(githubVm, context) {
        githubVm.initialize(context)
    }

    ExpandableHeader(
        icon = Icons.Outlined.Backup,
        title = stringResource(R.string.settings_backup_restore),
        subtitleCollapsed = stringResource(R.string.settings_backup_expand),
        subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
        expanded = expanded,
        onToggle = { onExpandedChange(!expanded) },
        arrowRotation = arrowRotation
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
        ) {
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = stringResource(R.string.settings_current_playlist),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                headlineContent = { Text(stringResource(R.string.playlist_count)) },
                supportingContent = {
                    Text(
                        pluralStringResource(
                            R.plurals.playlist_count_format,
                            currentPlaylistCount,
                            currentPlaylistCount
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Upload,
                        contentDescription = stringResource(R.string.settings_export_playlist),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.playlist_export)) },
                supportingContent = { Text(stringResource(R.string.playlist_export_desc)) },
                modifier = Modifier.settingsItemClickable(onClick = onExportClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.settings_import_playlist),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.playlist_import)) },
                supportingContent = { Text(stringResource(R.string.playlist_import_desc)) },
                modifier = Modifier.settingsItemClickable(onClick = onImportClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            backupRestoreUiState.exportProgress?.let { progress ->
                ProgressStatusItem(
                    title = stringResource(R.string.playlist_export_progress),
                    message = progress
                )
            }
            backupRestoreUiState.importProgress?.let { progress ->
                ProgressStatusItem(
                    title = stringResource(R.string.playlist_import_progress),
                    message = progress
                )
            }
            backupRestoreUiState.analysisProgress?.let { progress ->
                ProgressStatusItem(
                    title = stringResource(R.string.sync_analysis_progress),
                    message = progress
                )
            }

            AnimatedVisibility(
                visible = backupRestoreUiState.lastExportMessage != null,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(durationMillis = 250, easing = EaseInCubic))
            ) {
                backupRestoreUiState.lastExportMessage?.let { message ->
                    ResultStatusCard(
                        title = if (backupRestoreUiState.lastExportSuccess == true) {
                            stringResource(R.string.settings_export_success)
                        } else {
                            stringResource(R.string.settings_export_failed)
                        },
                        message = message,
                        isSuccess = backupRestoreUiState.lastExportSuccess == true,
                        onClose = onClearExportStatus
                    )
                }
            }

            AnimatedVisibility(
                visible = backupRestoreUiState.lastImportMessage != null,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(durationMillis = 250, easing = EaseInCubic))
            ) {
                backupRestoreUiState.lastImportMessage?.let { message ->
                    ResultStatusCard(
                        title = if (backupRestoreUiState.lastImportSuccess == true) {
                            stringResource(R.string.settings_import_success)
                        } else {
                            stringResource(R.string.settings_import_failed)
                        },
                        message = message,
                        isSuccess = backupRestoreUiState.lastImportSuccess == true,
                        onClose = onClearImportStatus
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.CloudSync,
                        contentDescription = stringResource(R.string.github_auto_sync),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.github_auto_sync)) },
                supportingContent = {
                    Text(
                        if (githubState.isConfigured) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (!githubState.isConfigured) {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_configure),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_config)) },
                    supportingContent = { Text(stringResource(R.string.sync_config_desc)) },
                    modifier = Modifier.settingsItemClickable(onClick = onOpenGitHubConfig),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            } else {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = stringResource(R.string.settings_auto_sync),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_auto)) },
                    supportingContent = { Text(stringResource(R.string.sync_auto_desc)) },
                    trailingContent = {
                        Switch(
                            checked = githubState.autoSyncEnabled,
                            onCheckedChange = { githubVm.toggleAutoSync(context, it) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.CloudUpload,
                            contentDescription = stringResource(R.string.settings_sync_now),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_now)) },
                    supportingContent = {
                        if (githubState.lastSyncTime > 0) {
                            Text(
                                stringResource(
                                    R.string.sync_last_time,
                                    formatSyncTime(githubState.lastSyncTime)
                                )
                            )
                        } else {
                            Text(stringResource(R.string.sync_not_synced))
                        }
                    },
                    trailingContent = {
                        if (githubState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            HapticTextButton(onClick = { githubVm.performSync(context) }) {
                                Text(stringResource(R.string.sync_title))
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = stringResource(R.string.settings_play_history_update_freq),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_history_frequency)) },
                    supportingContent = {
                        Text(
                            when (currentMode.value) {
                                SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE -> {
                                    stringResource(R.string.settings_update_immediate)
                                }
                                SecureTokenStorage.PlayHistoryUpdateMode.BATCHED -> {
                                    stringResource(R.string.settings_sync_batch_update_time)
                                }
                            }
                        )
                    },
                    modifier = Modifier.settingsItemClickable {
                        showPlayHistoryModeDialog = true
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.settings_data_saver),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_data_saver)) },
                    supportingContent = { Text(stringResource(R.string.sync_data_saver_desc)) },
                    trailingContent = {
                        Switch(
                            checked = dataSaverMode,
                            onCheckedChange = { enabled ->
                                if (enabled != dataSaverMode) {
                                    pendingDataSaverMode = enabled
                                }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Error,
                            contentDescription = stringResource(R.string.github_sync_silent_failure),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.github_sync_silent_failure)) },
                    supportingContent = {
                        Text(stringResource(R.string.github_sync_silent_failure_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = silentGitHubSyncFailure,
                            onCheckedChange = onSilentGitHubSyncFailureChange
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                HapticTextButton(
                    onClick = onOpenClearGitHubConfig,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_clear_config),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            githubState.errorMessage?.let { error ->
                GitHubMessageCard(
                    message = error,
                    isSuccess = false,
                    onClose = githubVm::clearMessages
                )
            }

            githubState.successMessage?.let { message ->
                GitHubMessageCard(
                    message = message,
                    isSuccess = true,
                    onClose = githubVm::clearMessages
                )
            }
        }
    }

    if (showPlayHistoryModeDialog) {
        PlayHistoryModeDialog(
            currentMode = currentMode.value,
            onDismiss = { showPlayHistoryModeDialog = false },
            onSelect = { mode ->
                storage.setPlayHistoryUpdateMode(mode)
                currentMode.value = mode
                showPlayHistoryModeDialog = false
            }
        )
    }

    if (pendingDataSaverMode != null) {
        AlertDialog(
            onDismissRequest = { pendingDataSaverMode = null },
            title = { Text(stringResource(R.string.sync_data_saver_warning_title)) },
            text = { Text(stringResource(R.string.sync_data_saver_warning_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        val enabled = pendingDataSaverMode ?: return@HapticTextButton
                        dataSaverMode = enabled
                        storage.setDataSaverMode(enabled)
                        pendingDataSaverMode = null
                    }
                ) {
                    Text(stringResource(R.string.sync_data_saver_warning_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { pendingDataSaverMode = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProgressStatusItem(title: String, message: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(message) },
        trailingContent = {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ResultStatusCard(
    title: String,
    message: String,
    isSuccess: Boolean,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSuccess) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        tonalElevation = 2.dp
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(message) },
            trailingContent = {
                HapticTextButton(onClick = onClose) {
                    Text(
                        stringResource(R.string.action_close),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun GitHubMessageCard(
    message: String,
    isSuccess: Boolean,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isSuccess) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSuccess) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.Error
                },
                contentDescription = null,
                tint = if (isSuccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.settings_close)
                )
            }
        }
    }
}

@Composable
private fun PlayHistoryModeDialog(
    currentMode: SecureTokenStorage.PlayHistoryUpdateMode,
    onDismiss: () -> Unit,
    onSelect: (SecureTokenStorage.PlayHistoryUpdateMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_history_frequency)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_frequency_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                PlayHistoryModeOption(
                    selected = currentMode == SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE,
                    title = stringResource(R.string.sync_after_play),
                    description = stringResource(R.string.sync_after_play_desc),
                    onClick = {
                        onSelect(SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE)
                    }
                )

                PlayHistoryModeOption(
                    selected = currentMode == SecureTokenStorage.PlayHistoryUpdateMode.BATCHED,
                    title = stringResource(R.string.sync_batch_update),
                    description = stringResource(R.string.sync_batch_update_desc),
                    onClick = {
                        onSelect(SecureTokenStorage.PlayHistoryUpdateMode.BATCHED)
                    }
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun PlayHistoryModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
