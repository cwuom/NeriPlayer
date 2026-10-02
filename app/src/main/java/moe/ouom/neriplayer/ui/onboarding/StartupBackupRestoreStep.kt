package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeUiState
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel

@Composable
internal fun StartupBackupRestoreSyncGate(
    state: SyncProtocolUpgradeUiState,
    onRetry: () -> Unit,
    waitingContent: @Composable (Boolean, () -> Unit) -> Unit = { failed, retry ->
        BackupSyncRegistrationStatus(failed, retry)
    },
    content: @Composable () -> Unit
) {
    if (state.startupRegistrationComplete) content()
    else waitingContent(state.hasError, onRetry)
}

@Composable
private fun BackupSyncRegistrationStatus(failed: Boolean, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (failed) {
            Text(stringResource(CoreCommonR.string.sync_upgrade_failed))
            HapticOutlinedButton(onClick = onRetry) {
                Text(stringResource(CoreCommonR.string.action_retry))
            }
        } else {
            CircularProgressIndicator()
            Text(stringResource(CoreCommonR.string.sync_upgrade_status_loading))
        }
    }
}

internal fun requestStartupBackupSync(
    targetId: String,
    upgradeViewModel: SyncProtocolUpgradeViewModel,
    performSyncForTarget: (String, () -> Unit, (SyncProtocolUpgradeChallenge) -> Unit) -> Unit
) {
    if (!upgradeViewModel.uiState.value.startupRegistrationComplete) return
    fun perform(target: String) {
        performSyncForTarget(target, upgradeViewModel::refreshTargets) { challenge ->
            upgradeViewModel.requestUpgrade(challenge) { perform(challenge.targetId) }
        }
    }
    upgradeViewModel.requestSync(targetId) { perform(targetId) }
}

@Composable
internal fun BackupRestoreContent(
    gitHubState: GitHubSyncUiState,
    webDavState: WebDavSyncUiState,
    onDismissGitHubMessage: () -> Unit,
    onDismissWebDavMessage: () -> Unit,
    onOpenGitHubConfig: () -> Unit,
    onOpenClearGitHubConfig: () -> Unit,
    onToggleGitHubAutoSync: (Boolean) -> Unit,
    onGitHubSyncNow: () -> Unit,
    onOpenWebDavConfig: () -> Unit,
    onOpenClearWebDavConfig: () -> Unit,
    onToggleWebDavAutoSync: (Boolean) -> Unit,
    onWebDavSyncNow: () -> Unit
) {
    StepHeader(
        icon = Icons.Outlined.CloudSync,
        title = stringResource(CoreCommonR.string.onboarding_backup_restore_title),
        description = stringResource(CoreCommonR.string.onboarding_backup_restore_desc)
    )
    Spacer(Modifier.height(18.dp))
    BackupSyncMessage(gitHubState.errorMessage, onDismissGitHubMessage)
    BackupSyncMessage(gitHubState.successMessage, onDismissGitHubMessage)
    BackupSyncMessage(webDavState.errorMessage, onDismissWebDavMessage)
    BackupSyncMessage(webDavState.successMessage, onDismissWebDavMessage)
    GitHubSyncCard(
        state = gitHubState,
        onOpenConfig = onOpenGitHubConfig,
        onOpenClearConfig = onOpenClearGitHubConfig,
        onToggleAutoSync = onToggleGitHubAutoSync,
        onSyncNow = onGitHubSyncNow
    )
    Spacer(Modifier.height(14.dp))
    WebDavSyncCard(
        state = webDavState,
        onOpenConfig = onOpenWebDavConfig,
        onOpenClearConfig = onOpenClearWebDavConfig,
        onToggleAutoSync = onToggleWebDavAutoSync,
        onSyncNow = onWebDavSyncNow
    )
    Spacer(Modifier.height(18.dp))
    HintCard(body = stringResource(CoreCommonR.string.onboarding_backup_restore_hint))
}

@Composable
private fun BackupSyncMessage(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    InlineMessage(text = message, onClose = onDismiss)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun GitHubSyncCard(
    state: GitHubSyncUiState,
    onOpenConfig: () -> Unit,
    onOpenClearConfig: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    SyncServiceCard(state.isConfigured) {
        SyncCardHeader(
            title = stringResource(CoreCommonR.string.onboarding_backup_restore_github_title),
            configured = state.isConfigured,
            onOpenConfig = onOpenConfig,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = stringResource(CoreCommonR.string.common_github),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        ) {
            ConfiguredGitHubDetails(state)
        }
        ConfiguredSyncControls(
            configured = state.isConfigured,
            autoSyncEnabled = state.autoSyncEnabled,
            isSyncing = state.isSyncing,
            autoSyncDescription = stringResource(CoreCommonR.string.sync_auto_desc),
            onToggleAutoSync = onToggleAutoSync,
            onSyncNow = onSyncNow,
            onOpenClearConfig = onOpenClearConfig
        )
    }
}

@Composable
private fun ConfiguredGitHubDetails(state: GitHubSyncUiState) {
    if (!state.isConfigured) return
    GitHubRepoText(githubRepoFullName(state.repoOwner, state.repoName))
    SyncLastTime(state.lastSyncTime)
}

@Composable
private fun GitHubRepoText(repoFullName: String?) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (repoFullName == null) {
            stringResource(CoreCommonR.string.settings_configured)
        } else {
            stringResource(CoreCommonR.string.onboarding_github_repo_configured, repoFullName)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

internal fun githubRepoFullName(owner: String, name: String): String? {
    if (owner.isBlank() || name.isBlank()) return null
    return "$owner/$name"
}

@Composable
private fun WebDavSyncCard(
    state: WebDavSyncUiState,
    onOpenConfig: () -> Unit,
    onOpenClearConfig: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    SyncServiceCard(state.isConfigured) {
        SyncCardHeader(
            title = stringResource(CoreCommonR.string.onboarding_backup_restore_webdav_title),
            configured = state.isConfigured,
            onOpenConfig = onOpenConfig,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CloudSync,
                    contentDescription = stringResource(CoreCommonR.string.onboarding_backup_restore_webdav_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        ) {
            WebDavEndpointText(webDavEndpoint(state.serverUrl, state.basePath))
            ConfiguredSyncLastTime(state.isConfigured, state.lastSyncTime)
        }
        ConfiguredSyncControls(
            configured = state.isConfigured,
            autoSyncEnabled = state.autoSyncEnabled,
            isSyncing = state.isSyncing,
            autoSyncDescription = stringResource(CoreCommonR.string.webdav_auto_sync_desc),
            onToggleAutoSync = onToggleAutoSync,
            onSyncNow = onSyncNow,
            onOpenClearConfig = onOpenClearConfig
        )
    }
}

internal fun webDavEndpoint(serverUrl: String, basePath: String): String? {
    if (serverUrl.isBlank()) return null
    if (basePath.isBlank()) return serverUrl
    return "$serverUrl/$basePath"
}

@Composable
private fun WebDavEndpointText(endpoint: String?) {
    if (endpoint == null) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(CoreCommonR.string.onboarding_backup_restore_webdav_endpoint, endpoint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ConfiguredSyncLastTime(configured: Boolean, lastSyncTime: Long) {
    if (!configured) return
    SyncLastTime(lastSyncTime)
}

@Composable
private fun SyncLastTime(lastSyncTime: Long) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (lastSyncTime > 0) {
            stringResource(CoreCommonR.string.sync_last_time, formatSyncTime(lastSyncTime))
        } else {
            stringResource(CoreCommonR.string.sync_not_synced)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SyncServiceCard(
    configured: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        shape = OnboardingCardShape,
        color = if (configured) colors.secondaryContainer else colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun SyncCardHeader(
    title: String,
    configured: Boolean,
    onOpenConfig: () -> Unit,
    icon: @Composable () -> Unit,
    details: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = OnboardingControlShape,
            color = colors.surface
        ) {
            Box(contentAlignment = Alignment.Center, content = { icon() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(6.dp))
            SyncStatusPill(configured)
            details()
        }
        SyncConfigButton(configured, onOpenConfig)
    }
}

@Composable
private fun SyncStatusPill(configured: Boolean) {
    StatusPill(
        label = if (configured) {
            stringResource(CoreCommonR.string.settings_configured)
        } else {
            stringResource(CoreCommonR.string.settings_not_configured)
        },
        connected = configured
    )
}

@Composable
private fun SyncConfigButton(configured: Boolean, onOpenConfig: () -> Unit) {
    OnboardingActionButton(
        text = if (configured) {
            stringResource(CoreCommonR.string.onboarding_platform_action_manage)
        } else {
            stringResource(CoreCommonR.string.settings_configure)
        },
        onClick = onOpenConfig
    )
}

@Composable
private fun ConfiguredSyncControls(
    configured: Boolean,
    autoSyncEnabled: Boolean,
    isSyncing: Boolean,
    autoSyncDescription: String,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onOpenClearConfig: () -> Unit
) {
    if (!configured) return
    SyncAutoToggle(autoSyncEnabled, autoSyncDescription, onToggleAutoSync)
    SyncActionRow(isSyncing, onSyncNow, onOpenClearConfig)
}

@Composable
private fun SyncAutoToggle(
    autoSyncEnabled: Boolean,
    description: String,
    onToggleAutoSync: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = OnboardingControlShape, color = colors.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreCommonR.string.sync_auto),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Switch(checked = autoSyncEnabled, onCheckedChange = onToggleAutoSync)
        }
    }
}

@Composable
private fun SyncActionRow(
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onOpenClearConfig: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            HapticOutlinedButton(onClick = onSyncNow, shape = OnboardingControlShape) {
                Text(stringResource(CoreCommonR.string.settings_sync_now))
            }
        }
        HapticTextButton(onClick = onOpenClearConfig) {
            Text(
                text = stringResource(CoreCommonR.string.settings_clear_config),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
