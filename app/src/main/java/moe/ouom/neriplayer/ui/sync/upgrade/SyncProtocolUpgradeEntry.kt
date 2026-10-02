package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable

@Composable
internal fun rememberSyncProtocolUpgradeViewModel(): SyncProtocolUpgradeViewModel {
    val activity = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
    val appContext = LocalContext.current.applicationContext
    val factory = remember(appContext) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SyncProtocolUpgradeRepository(appContext)
                return checkNotNull(
                    modelClass.cast(
                        SyncProtocolUpgradeViewModel(
                            pendingFlow = repository.pendingFlow,
                            saveConfirmation = repository::confirmAllDevicesUpdated,
                            loadActiveTargets = { activeSyncTargets(appContext) }
                        )
                    )
                )
            }
        }
    }
    return viewModel(viewModelStoreOwner = activity, factory = factory)
}

private suspend fun activeSyncTargets(context: Context): Set<String> = withContext(Dispatchers.IO) {
    buildSet {
        val github = SecureTokenStorage(context)
        if (github.isConfigured()) {
            add(SyncProtocolUpgradeRepository.githubTargetHash(
                github.getRepoOwner().orEmpty(), github.getRepoName().orEmpty()
            ))
        }
        val webDav = WebDavStorage(context)
        if (webDav.isConfigured()) {
            add(SyncProtocolUpgradeRepository.webDavTargetHash(
                webDav.getServerUrl().orEmpty(), webDav.getBasePath(), webDav.getUsername().orEmpty()
            ))
        }
    }
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
    LaunchedEffect(canShowDialog, isResumed, state.approved, deferred) {
        if (canShowDialog && isResumed && state.approved == false && !deferred) {
            viewModel.openConfirmation()
        }
    }
    if (canShowDialog && isResumed && state.dialogRequested && state.approved == false) {
        dialogContent(state) {
            if (viewModel.dismissConfirmation()) deferred = true
        }
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
            enabled = version?.getOrNull() == 0 && !state.isSaving,
            onClick = { viewModel.openConfirmation(targetId) }
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
