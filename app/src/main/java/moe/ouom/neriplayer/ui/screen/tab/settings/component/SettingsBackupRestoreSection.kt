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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.data.sync.PlayHistoryUpdateMode
import moe.ouom.neriplayer.data.sync.PlayHistorySyncPreferences
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferUiState
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime

@Composable
internal fun SettingsBackupRestoreSection(
    expanded: Boolean,
    arrowRotation: Float,
    onExpandedChange: (Boolean) -> Unit,
    showHeader: Boolean = true,
    currentPlaylistCount: Int,
    backupRestoreUiState: BackupRestoreUiState,
    configTransferUiState: ConfigTransferUiState,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportConfigClick: () -> Unit,
    onImportConfigClick: () -> Unit,
    onClearExportStatus: () -> Unit,
    onClearImportStatus: () -> Unit,
    onClearConfigExportStatus: () -> Unit,
    onClearConfigImportStatus: () -> Unit,
    autoSettingsRepository: AutoSettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    showGitHubConfigDialog: Boolean,
    showWebDavConfigDialog: Boolean,
    onOpenGitHubConfig: () -> Unit,
    onOpenClearGitHubConfig: () -> Unit,
    onOpenWebDavConfig: () -> Unit,
    onOpenClearWebDavConfig: () -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    if (showHeader) {
        ExpandableHeader(
            icon = Icons.Outlined.Backup,
            title = stringResource(R.string.settings_backup_restore),
            subtitleCollapsed = stringResource(R.string.settings_backup_expand),
            subtitleExpanded = stringResource(R.string.settings_login_platforms_collapse),
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            arrowRotation = arrowRotation
        )
    }

    val content: @Composable () -> Unit = {
        val context = androidx.compose.ui.platform.LocalContext.current
        val needsGitHubState = shouldShowCard(2) || shouldShowCard(4)
        val needsWebDavState = shouldShowCard(3) || shouldShowCard(4)
        val needsPlayHistoryMode = shouldShowCard(1)
        val needsDataSaverMode = shouldShowCard(2)
        val githubVm: GitHubSyncViewModel? = if (needsGitHubState) {
            viewModel<GitHubSyncViewModel>()
        } else {
            null
        }
        val webDavVm: WebDavSyncViewModel? = if (needsWebDavState) {
            viewModel<WebDavSyncViewModel>()
        } else {
            null
        }
        val githubState = if (githubVm != null) {
            githubVm.uiState.collectAsStateWithLifecycleCompat().value
        } else {
            GitHubSyncUiState()
        }
        val webDavState = if (webDavVm != null) {
            webDavVm.uiState.collectAsStateWithLifecycleCompat().value
        } else {
            WebDavSyncUiState()
        }
        var showPlayHistoryModeDialog by remember { mutableStateOf(false) }
        var showConfigExportWarningDialog by remember { mutableStateOf(false) }
        var currentMode by remember { mutableStateOf(PlayHistoryUpdateMode.IMMEDIATE) }
        var dataSaverMode by remember { mutableStateOf(true) }
        var pendingDataSaverMode by remember { mutableStateOf<Boolean?>(null) }

        githubVm?.let { viewModel ->
            LaunchedEffect(viewModel, context) {
                withContext(Dispatchers.IO) {
                    viewModel.initialize(context)
                }
            }
        }
        webDavVm?.let { viewModel ->
            LaunchedEffect(viewModel, context) {
                withContext(Dispatchers.IO) {
                    viewModel.initialize(context)
                }
            }
        }
        LaunchedEffect(
            needsPlayHistoryMode,
            needsDataSaverMode,
            context,
            configTransferUiState.isImporting,
            configTransferUiState.lastImportSuccess
        ) {
            if (needsPlayHistoryMode || needsDataSaverMode) {
                val snapshot = withContext(Dispatchers.IO) {
                    loadBackupSyncPreferenceSnapshot(
                        context = context.applicationContext,
                        needsPlayHistoryMode = needsPlayHistoryMode,
                        needsDataSaverMode = needsDataSaverMode
                    )
                }
                if (needsPlayHistoryMode) {
                    currentMode = snapshot.playHistoryUpdateMode
                }
                if (needsDataSaverMode) {
                    dataSaverMode = snapshot.dataSaverMode
                }
            }
            if (!configTransferUiState.isImporting && configTransferUiState.lastImportSuccess == true) {
                withContext(Dispatchers.IO) {
                    githubVm?.initialize(context)
                    webDavVm?.initialize(context)
                }
                pendingDataSaverMode = null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(
                    start = if (showHeader) 16.dp else 0.dp,
                    end = if (showHeader) 8.dp else 0.dp,
                    bottom = if (showHeader) 8.dp else 0.dp
                )
        ) {
            val localBackupTargets = setOf("manual:backup_local")
            val messageTargets = setOf("manual:backup_messages")
            val isSyncConfigDialogOpen = showGitHubConfigDialog || showWebDavConfigDialog
            val hasSyncMessage =
                !isSyncConfigDialogOpen &&
                    (
                        githubState.errorMessage != null ||
                            githubState.successMessage != null ||
                            webDavState.errorMessage != null ||
                            webDavState.successMessage != null
                        )

            if (shouldShowCard(0)) BackupDetailCard(
                showCard = !showHeader,
                highlighted = highlightTargetId in localBackupTargets,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_backup_local_section),
                description = stringResource(R.string.settings_backup_local_section_desc)
            )
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
                modifier = Modifier
                    .settingsHighlightTarget(
                        targetId = "manual:playlist_export",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                    .settingsItemClickable(onClick = onExportClick),
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
                modifier = Modifier
                    .settingsHighlightTarget(
                        targetId = "manual:playlist_import",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                    .settingsItemClickable(onClick = onImportClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Upload,
                        contentDescription = stringResource(R.string.settings_export_config),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.settings_export_config)) },
                supportingContent = { Text(stringResource(R.string.settings_export_config_desc)) },
                modifier = Modifier
                    .settingsHighlightTarget(
                        targetId = "manual:config_export",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                    .settingsItemClickable {
                        if (!configTransferUiState.isExporting) {
                            showConfigExportWarningDialog = true
                        }
                    },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.settings_import_config),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.settings_import_config)) },
                supportingContent = { Text(stringResource(R.string.settings_import_config_desc)) },
                modifier = Modifier
                    .settingsHighlightTarget(
                        targetId = "manual:config_import",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                    .settingsItemClickable(onClick = onImportConfigClick),
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
            configTransferUiState.exportProgress?.let { progress ->
                ProgressStatusItem(
                    title = stringResource(R.string.settings_config_export_progress),
                    message = progress
                )
            }
            configTransferUiState.importProgress?.let { progress ->
                ProgressStatusItem(
                    title = stringResource(R.string.settings_config_import_progress),
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

            AnimatedVisibility(
                visible = configTransferUiState.lastExportMessage != null,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(durationMillis = 250, easing = EaseInCubic))
            ) {
                configTransferUiState.lastExportMessage?.let { message ->
                    ResultStatusCard(
                        title = if (configTransferUiState.lastExportSuccess == true) {
                            stringResource(R.string.settings_config_export_success)
                        } else {
                            stringResource(R.string.settings_config_export_failed)
                        },
                        message = message,
                        isSuccess = configTransferUiState.lastExportSuccess == true,
                        onClose = onClearConfigExportStatus
                    )
                }
            }

            AnimatedVisibility(
                visible = configTransferUiState.lastImportMessage != null,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = EaseOutCubic)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(durationMillis = 250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(durationMillis = 250, easing = EaseInCubic))
            ) {
                configTransferUiState.lastImportMessage?.let { message ->
                    ResultStatusCard(
                        title = if (configTransferUiState.lastImportSuccess == true) {
                            stringResource(R.string.settings_config_import_success)
                        } else {
                            stringResource(R.string.settings_config_import_failed)
                        },
                        message = message,
                        isSuccess = configTransferUiState.lastImportSuccess == true,
                        onClose = onClearConfigImportStatus
                    )
                }
            }

            }

            if (cardIndex == null) BackupDetailGap(showHeader)
            if (shouldShowCard(1)) BackupDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_backup_history_section),
                description = stringResource(R.string.settings_backup_history_section_desc)
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
                    Text(playHistoryUpdateModeSummary(currentMode))
                },
                modifier = Modifier
                    .settingsHighlightTarget(
                        targetId = "manual:backup_history",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    )
                    .settingsItemClickable {
                        showPlayHistoryModeDialog = true
                    },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            }

            if (cardIndex == null) BackupDetailGap(showHeader)
            if (shouldShowCard(2)) BackupDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_backup_github_section),
                description = stringResource(R.string.settings_backup_github_section_desc)
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
                modifier = Modifier.settingsHighlightTarget(
                    targetId = "manual:github_sync",
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                ),
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
                    modifier = Modifier
                        .settingsHighlightTarget(
                            targetId = "manual:github_auto_sync",
                            highlightTargetId = highlightTargetId,
                            highlightPulse = highlightPulse,
                            onHighlightFinished = onHighlightFinished
                        )
                        .settingsItemClickable(onClick = onOpenGitHubConfig),
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
                        MiuixSettingsSwitch(
                            checked = githubState.autoSyncEnabled,
                            onCheckedChange = { githubVm?.toggleAutoSync(context, it) }
                        )
                    },
                    modifier = Modifier.settingsHighlightTarget(
                        targetId = "manual:github_auto_sync",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    ),
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
                            MiuixSettingsTextButton(onClick = { githubVm?.performSync(context) }) {
                                Text(stringResource(R.string.sync_title))
                            }
                        }
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
                        MiuixSettingsSwitch(
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

                MiuixSettingsTextButton(
                    onClick = onOpenClearGitHubConfig,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_clear_config),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            AutoSettingsSwitchItems(
                repository = autoSettingsRepository,
                scope = scope,
                sectionScope = AutoSettingsScopes.backup,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            }

            if (cardIndex == null) BackupDetailGap(showHeader)
            if (shouldShowCard(3)) BackupDetailCard(
                showCard = !showHeader,
                highlighted = false,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_backup_webdav_section),
                description = stringResource(R.string.settings_backup_webdav_section_desc)
            )

            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Cloud,
                        contentDescription = stringResource(R.string.webdav_sync_title),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = { Text(stringResource(R.string.webdav_sync_title)) },
                supportingContent = {
                    Text(
                        if (webDavState.isConfigured) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        }
                    )
                },
                modifier = Modifier.settingsHighlightTarget(
                    targetId = "manual:webdav_sync",
                    highlightTargetId = highlightTargetId,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (!webDavState.isConfigured) {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_configure),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sync_config)) },
                    supportingContent = { Text(stringResource(R.string.webdav_sync_desc)) },
                    modifier = Modifier
                        .settingsHighlightTarget(
                            targetId = "manual:webdav_auto_sync",
                            highlightTargetId = highlightTargetId,
                            highlightPulse = highlightPulse,
                            onHighlightFinished = onHighlightFinished
                        )
                        .settingsItemClickable(onClick = onOpenWebDavConfig),
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
                    supportingContent = { Text(stringResource(R.string.webdav_auto_sync_desc)) },
                    trailingContent = {
                        MiuixSettingsSwitch(
                            checked = webDavState.autoSyncEnabled,
                            onCheckedChange = { webDavVm?.toggleAutoSync(context, it) }
                        )
                    },
                    modifier = Modifier.settingsHighlightTarget(
                        targetId = "manual:webdav_auto_sync",
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished
                    ),
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
                        if (webDavState.lastSyncTime > 0) {
                            Text(
                                stringResource(
                                    R.string.sync_last_time,
                                    formatSyncTime(webDavState.lastSyncTime)
                                )
                            )
                        } else {
                            Text(stringResource(R.string.sync_not_synced))
                        }
                    },
                    trailingContent = {
                        if (webDavState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            MiuixSettingsTextButton(onClick = { webDavVm?.performSync(context) }) {
                                Text(stringResource(R.string.sync_title))
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                MiuixSettingsTextButton(
                    onClick = onOpenClearWebDavConfig,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_clear_config),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            }

            if (hasSyncMessage && shouldShowCard(4)) {
                if (cardIndex == null) BackupDetailGap(showHeader)
                BackupDetailCard(
                    showCard = !showHeader,
                    highlighted = highlightTargetId in messageTargets,
                    highlightPulse = highlightPulse,
                    onHighlightFinished = onHighlightFinished
                ) {
                    MiuixSettingsSectionIntro(
                        title = stringResource(R.string.settings_backup_message_section),
                        description = stringResource(R.string.settings_backup_message_section_desc)
                    )
                    githubState.errorMessage?.let { error ->
                        SyncMessageCard(
                            message = error,
                            isSuccess = false,
                            onClose = { githubVm?.clearMessages() }
                        )
                    }

                    githubState.successMessage?.let { message ->
                        SyncMessageCard(
                            message = message,
                            isSuccess = true,
                            onClose = { githubVm?.clearMessages() }
                        )
                    }

                    webDavState.errorMessage?.let { error ->
                        SyncMessageCard(
                            message = error,
                            isSuccess = false,
                            onClose = { webDavVm?.clearMessages() }
                        )
                    }

                    webDavState.successMessage?.let { message ->
                        SyncMessageCard(
                            message = message,
                            isSuccess = true,
                            onClose = { webDavVm?.clearMessages() }
                        )
                    }
                }
            }
        }

        if (showPlayHistoryModeDialog) {
            PlayHistoryModeDialog(
                currentMode = currentMode,
                onDismiss = { showPlayHistoryModeDialog = false },
                onSelect = { mode ->
                    currentMode = mode
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PlayHistorySyncPreferences(context.applicationContext)
                                .setUpdateMode(mode)
                        }
                    }
                    showPlayHistoryModeDialog = false
                }
            )
        }

        if (showConfigExportWarningDialog) {
            MiuixSettingsDialog(
                onDismissRequest = { showConfigExportWarningDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text(stringResource(R.string.settings_config_export_warning_title)) },
                text = { Text(stringResource(R.string.settings_config_export_warning_message)) },
                confirmButton = {
                    MiuixSettingsTextButton(
                        onClick = {
                            showConfigExportWarningDialog = false
                            onExportConfigClick()
                        }
                    ) {
                        Text(stringResource(R.string.settings_config_export_warning_confirm))
                    }
                },
                dismissButton = {
                    MiuixSettingsTextButton(
                        onClick = { showConfigExportWarningDialog = false }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        if (pendingDataSaverMode != null) {
            MiuixSettingsDialog(
                onDismissRequest = { pendingDataSaverMode = null },
                title = { Text(stringResource(R.string.sync_data_saver_warning_title)) },
                text = { Text(stringResource(R.string.sync_data_saver_warning_message)) },
                confirmButton = {
                    MiuixSettingsTextButton(
                        onClick = {
                            val enabled = pendingDataSaverMode ?: return@MiuixSettingsTextButton
                            dataSaverMode = enabled
                            pendingDataSaverMode = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    SecureTokenStorage(context.applicationContext)
                                        .setDataSaverMode(enabled)
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.sync_data_saver_warning_confirm))
                    }
                },
                dismissButton = {
                    MiuixSettingsTextButton(onClick = { pendingDataSaverMode = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    if (showHeader) {
        LazyAnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            content()
        }
    } else {
        content()
    }
}

private data class BackupSyncPreferenceSnapshot(
    val playHistoryUpdateMode: PlayHistoryUpdateMode = PlayHistoryUpdateMode.IMMEDIATE,
    val dataSaverMode: Boolean = true
)

private fun loadBackupSyncPreferenceSnapshot(
    context: android.content.Context,
    needsPlayHistoryMode: Boolean,
    needsDataSaverMode: Boolean
): BackupSyncPreferenceSnapshot {
    val storage = if (needsPlayHistoryMode || needsDataSaverMode) {
        SecureTokenStorage(context.applicationContext)
    } else {
        null
    }
    val mode = if (needsPlayHistoryMode) {
        PlayHistorySyncPreferences(context.applicationContext)
            .getUpdateMode(storage?.getLegacyPlayHistoryUpdateModeName())
    } else {
        PlayHistoryUpdateMode.IMMEDIATE
    }
    val dataSaver = if (needsDataSaverMode) {
        storage?.isDataSaverMode() ?: true
    } else {
        true
    }
    return BackupSyncPreferenceSnapshot(
        playHistoryUpdateMode = mode,
        dataSaverMode = dataSaver
    )
}

@Composable
private fun BackupDetailCard(
    showCard: Boolean,
    highlighted: Boolean = false,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (showCard) {
        MiuixSettingsSectionCard(
            highlighted = highlighted,
            highlightPulse = highlightPulse,
            onHighlightFinished = if (highlighted) onHighlightFinished else null,
            content = content
        )
    } else {
        content()
    }
}

@Composable
private fun BackupDetailGap(showHeader: Boolean) {
    if (showHeader) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    } else {
        Spacer(Modifier.height(12.dp))
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
    val resolvedMessage = buildString {
        append(title)
        if (message.isNotBlank()) {
            append('\n')
            append(message)
        }
    }
    MiuixSettingsInlineMessage(
        message = resolvedMessage,
        isSuccess = isSuccess,
        modifier = Modifier.padding(horizontal = 8.dp),
        onClose = onClose
    )
}

@Composable
private fun SyncMessageCard(
    message: String,
    isSuccess: Boolean,
    onClose: () -> Unit
) {
    MiuixSettingsInlineMessage(
        message = message,
        isSuccess = isSuccess,
        modifier = Modifier.padding(horizontal = 8.dp),
        onClose = onClose
    )
}

@Composable
private fun PlayHistoryModeDialog(
    currentMode: PlayHistoryUpdateMode,
    onDismiss: () -> Unit,
    onSelect: (PlayHistoryUpdateMode) -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_history_frequency)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_frequency_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                PlayHistorySyncPreferences.UpdateMode.selectableModes.forEach { mode ->
                    PlayHistoryModeOption(
                        selected = currentMode == mode,
                        title = playHistoryUpdateModeTitle(mode),
                        description = playHistoryUpdateModeDescription(mode),
                        onClick = {
                            onSelect(mode)
                        }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun playHistoryUpdateModeSummary(
    mode: PlayHistoryUpdateMode
): String {
    return when (mode) {
        PlayHistoryUpdateMode.IMMEDIATE -> {
            stringResource(R.string.settings_update_immediate)
        }
        else -> {
            val intervalMinutes = mode.intervalMinutes
                ?: return stringResource(R.string.settings_update_immediate)
            pluralStringResource(
                R.plurals.settings_update_every_minutes,
                intervalMinutes,
                intervalMinutes
            )
        }
    }
}

@Composable
private fun playHistoryUpdateModeTitle(
    mode: PlayHistoryUpdateMode
): String {
    return when (mode) {
        PlayHistoryUpdateMode.IMMEDIATE -> {
            stringResource(R.string.sync_after_play)
        }
        else -> {
            val intervalMinutes = mode.intervalMinutes
                ?: return stringResource(R.string.sync_after_play)
            pluralStringResource(
                R.plurals.sync_every_minutes,
                intervalMinutes,
                intervalMinutes
            )
        }
    }
}

@Composable
private fun playHistoryUpdateModeDescription(
    mode: PlayHistoryUpdateMode
): String {
    return when (mode) {
        PlayHistoryUpdateMode.IMMEDIATE -> {
            stringResource(R.string.sync_after_play_desc)
        }
        else -> {
            val intervalMinutes = mode.intervalMinutes
                ?: return stringResource(R.string.sync_after_play_desc)
            pluralStringResource(
                R.plurals.sync_every_minutes_desc,
                intervalMinutes,
                intervalMinutes
            )
        }
    }
}

@Composable
private fun PlayHistoryModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    MiuixSettingsChoiceRow(
        title = title,
        subtitle = description,
        selected = selected,
        onClick = onClick
    )
}
