package moe.ouom.neriplayer.ui.sync.lyrics

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow

@Composable
private fun rememberSyncLyricOptimizationViewModel(): SyncLyricOptimizationViewModel {
    val activity = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
    val context = LocalContext.current.applicationContext
    val factory = remember(context) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val storage = SecureTokenStorage(context)
                return checkNotNull(modelClass.cast(SyncLyricOptimizationViewModel(
                    loadEnabled = { withContext(Dispatchers.IO) { storage.isLegacyLyricOptimizationEnabled() } },
                    saveEnabled = { enabled -> withContext(Dispatchers.IO) { storage.setLegacyLyricOptimizationEnabled(enabled) } }
                )))
            }
        }
    }
    return viewModel(viewModelStoreOwner = activity, factory = factory)
}

@Composable
internal fun SyncLyricOptimizationSetting(
    viewModel: SyncLyricOptimizationViewModel = rememberSyncLyricOptimizationViewModel(),
    upgradeSaving: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogOwner = remember { Any() }
    LaunchedEffect(viewModel, upgradeSaving) { if (!upgradeSaving) viewModel.refresh() }
    DisposableEffect(viewModel, dialogOwner) {
        onDispose { viewModel.release(dialogOwner) }
    }
    val summary = when {
        state.isLoading -> stringResource(CoreCommonR.string.sync_lyric_optimization_loading)
        state.enabled == null -> stringResource(CoreCommonR.string.sync_lyric_optimization_failed)
        else -> optimizationOptionLabel(state.enabled == true)
    }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Compress, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(CoreCommonR.string.sync_lyric_optimization_title)) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.settingsItemClickable(enabled = !state.isLoading && !state.isSaving && !upgradeSaving) {
            if (state.enabled == null) viewModel.refresh() else {
                viewModel.open(dialogOwner)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    if (state.dialogOwner === dialogOwner && state.dialogRequested) {
        SyncLyricOptimizationDialog(state, viewModel::choose, viewModel::confirm) {
            viewModel.dismiss()
        }
    }
}

@Composable
private fun optimizationOptionLabel(enabled: Boolean): String = stringResource(
    if (enabled) CoreCommonR.string.sync_lyric_optimization_yes else CoreCommonR.string.sync_lyric_optimization_no
)

@Composable
private fun SyncLyricOptimizationDialog(
    state: SyncLyricOptimizationUiState,
    onSelection: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DensityScaledAlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.isSaving, dismissOnClickOutside = !state.isSaving),
        title = { Text(stringResource(CoreCommonR.string.sync_lyric_optimization_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(CoreCommonR.string.sync_lyric_optimization_description))
                MiuixSettingsChoiceRow(title = optimizationOptionLabel(false), selected = !state.selectedEnabled,
                    enabled = !state.isSaving, onClick = { onSelection(false) })
                MiuixSettingsChoiceRow(title = optimizationOptionLabel(true), selected = state.selectedEnabled,
                    enabled = !state.isSaving, onClick = { onSelection(true) })
                Text(stringResource(CoreCommonR.string.sync_lyric_optimization_warning), color = MaterialTheme.colorScheme.error)
                Text(stringResource(CoreCommonR.string.sync_lyric_optimization_existing), style = MaterialTheme.typography.bodySmall)
                if (state.hasError) {
                    Text(stringResource(CoreCommonR.string.sync_lyric_optimization_failed), color = MaterialTheme.colorScheme.error)
                }
                if (state.isSaving) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(CoreCommonR.string.sync_lyric_optimization_saving))
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onConfirm, enabled = state.canConfirm) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss, enabled = !state.isSaving) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}
