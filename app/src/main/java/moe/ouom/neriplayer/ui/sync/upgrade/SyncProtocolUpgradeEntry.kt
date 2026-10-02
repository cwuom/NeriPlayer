package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
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
                            approvedFlow = repository.approvedFlow,
                            saveConfirmation = repository::confirmAllDevicesUpdated
                        )
                    )
                )
            }
        }
    }
    return viewModel(viewModelStoreOwner = activity, factory = factory)
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
    viewModel: SyncProtocolUpgradeViewModel = rememberSyncProtocolUpgradeViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = when {
        state.approved == null -> CoreCommonR.string.sync_upgrade_status_loading
        state.approved == true -> CoreCommonR.string.sync_upgrade_confirmed
        state.isSaving -> CoreCommonR.string.sync_upgrade_saving
        state.hasError -> CoreCommonR.string.sync_upgrade_failed
        else -> CoreCommonR.string.sync_upgrade_status_pending
    }
    ListItem(
        leadingContent = {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.sync_upgrade_title)) },
        supportingContent = { Text(stringResource(summary)) },
        modifier = Modifier.settingsItemClickable(
            enabled = state.approved == false && !state.isSaving,
            onClick = viewModel::openConfirmation
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
