package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import android.widget.Toast
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.github.GitHubSyncManager
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncManager
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun rememberSyncProtocolUpgradeViewModel(): SyncProtocolUpgradeViewModel {
    val activity = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
    val appContext = LocalContext.current.applicationContext
    val factory = remember(appContext) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SyncProtocolUpgradeRepository(appContext)
                val immediateSync = SyncUpgradeImmediateSync(
                    performSync = { target -> performConfiguredSync(appContext, target) },
                    confirmLegacy = repository::confirmAllDevicesUpdated,
                    isTargetActive = { target -> target in activeSyncTargets(appContext) },
                    hasCurrentProtocol = { target ->
                        repository.versionFlow(target).first() == SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION
                    }
                )
                return checkNotNull(
                    modelClass.cast(
                        SyncProtocolUpgradeViewModel(
                            pendingFlow = repository.pendingFlow,
                            saveConfirmation = repository::confirmAllDevicesUpdated,
                            loadActiveTargets = { activeSyncTargets(appContext) },
                            startupTargetsFlow = repository.startupPendingFlow,
                            initializeStartupTargets = { repository.initializeStartupTargets(activeSyncTargets(appContext)) },
                            performImmediateSync = { target, approveDetectedLegacy ->
                                val result = immediateSync.execute(target, approveDetectedLegacy)
                                if (result.getOrNull()?.success == true) repository.completeStartupUpgrade(target)
                                result
                            }
                        )
                    )
                )
            }
        }
    }
    return viewModel(viewModelStoreOwner = activity, factory = factory)
}

private suspend fun performConfiguredSync(context: Context, targetId: String): Result<SyncResult> = withContext(Dispatchers.IO) {
    val github = SecureTokenStorage(context)
    if (github.isConfigured() && SyncProtocolUpgradeRepository.githubTargetHash(
        github.getRepoOwner().orEmpty(), github.getRepoName().orEmpty()
    ) == targetId) {
        return@withContext GitHubSyncManager.getInstance(context).performSyncForTarget(targetId)
    }
    val webDav = WebDavStorage(context)
    if (webDav.isConfigured() && SyncProtocolUpgradeRepository.webDavTargetHash(
        webDav.getServerUrl().orEmpty(), webDav.getBasePath(), webDav.getUsername().orEmpty()
    ) == targetId) {
        return@withContext WebDavSyncManager.getInstance(context).performSyncForTarget(targetId)
    }
    Result.failure(IOException("Sync target changed"))
}

private suspend fun activeSyncTargets(context: Context): Set<String> = withContext(Dispatchers.IO) {
    SyncProtocolUpgradeRepository.configuredTargetIds(context)
}

@Composable
private fun rememberSyncUpgradeResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
internal fun StartupSyncUpgradePrompt(
    canShowDialog: Boolean,
    viewModel: SyncProtocolUpgradeViewModel = rememberSyncProtocolUpgradeViewModel(),
    isResumed: Boolean = rememberSyncUpgradeResumed(),
    resultContent: @Composable (SyncResult, () -> Unit) -> Unit = { result, consume ->
        SyncUpgradeResultNotice(result, consume)
    },
    dialogContent: @Composable (SyncProtocolUpgradeUiState, () -> Unit) -> Unit = { state, onDefer ->
        SyncProtocolUpgradeDialog(
            state = state,
            onAllDevicesUpdatedChange = viewModel::setAllDevicesUpdated,
            onConfirm = viewModel::confirm,
            onDefer = onDefer
        )
    }
) {
    val state by viewModel.uiState.collectAsState()
    var deferred by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canShowDialog, isResumed, state.startupRegistrationComplete, state.approved, state.challenge, state.startupTargetId, deferred) {
        if (canShowDialog && isResumed && state.startupRegistrationComplete && state.approved == false && !deferred) {
            viewModel.openConfirmation()
        }
    }
    if (canShowDialog && isResumed && state.startupRegistrationComplete && state.dialogRequested && state.approved == false) {
        dialogContent(state) {
            if (viewModel.dismissConfirmation()) deferred = true
        }
    }
    state.syncResult?.takeIf { it.message.isNotBlank() }?.let { result ->
        resultContent(result, viewModel::clearSyncResult)
    }
}

@Composable
internal fun syncProtocolStartupConfigurationGate(
    dialogRequested: Boolean,
    onDismiss: () -> Unit,
    viewModel: SyncProtocolUpgradeViewModel = rememberSyncProtocolUpgradeViewModel()
): Boolean {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!dialogRequested || state.startupRegistrationComplete) return false
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.sync_database_version_title)) },
        text = {
            if (state.hasError) Text(stringResource(CoreCommonR.string.sync_upgrade_failed))
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(CoreCommonR.string.sync_upgrade_status_loading))
            }
        },
        confirmButton = {
            if (state.hasError) MiuixSettingsTextButton(onClick = viewModel::refreshTargets) {
                Text(stringResource(CoreCommonR.string.action_retry))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
    return true
}

@Composable
private fun SyncUpgradeResultNotice(result: SyncResult, consume: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(result) {
        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        consume()
    }
}

@Composable
internal fun SyncProtocolUpgradeSetting(
    targetId: String?,
    viewModel: SyncProtocolUpgradeViewModel = rememberSyncProtocolUpgradeViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { SyncProtocolUpgradeRepository(context) }
    val versionFlow = remember(repository, targetId) {
        val versions = if (targetId == null) flowOf(SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION)
            else repository.versionFlow(targetId)
        versions.map { Result.success(it) }.catch { emit(Result.failure(it)) }
    }
    val version by versionFlow.collectAsStateWithLifecycle(initialValue = null)
    val summary = when {
        version == null -> stringResource(CoreCommonR.string.sync_upgrade_status_loading)
        version?.isFailure == true -> stringResource(CoreCommonR.string.sync_upgrade_failed)
        version?.getOrNull() == 0 -> stringResource(CoreCommonR.string.sync_database_version_legacy)
        else -> stringResource(CoreCommonR.string.sync_database_version_number, checkNotNull(version?.getOrNull()))
    }
    ListItem(
        leadingContent = {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.sync_database_version_title)) },
        supportingContent = { Text(summary) },
        modifier = Modifier.settingsItemClickable(
            enabled = version?.getOrNull()?.let {
                it in 0 until SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION
            } == true && !state.isSaving,
            onClick = { viewModel.openConfirmation(targetId) }
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
