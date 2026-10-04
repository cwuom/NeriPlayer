package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@Composable
internal fun SyncProtocolUpgradeDialog(
    state: SyncProtocolUpgradeUiState,
    onAllDevicesUpdatedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDefer: () -> Unit
) {
    DensityScaledAlertDialog(
        onDismissRequest = { if (!state.isSaving) onDefer() },
        properties = DialogProperties(
            dismissOnBackPress = !state.isSaving,
            dismissOnClickOutside = !state.isSaving
        ),
        title = { Text(stringResource(CoreCommonR.string.sync_upgrade_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(CoreCommonR.string.sync_upgrade_message))
                Text(stringResource(CoreCommonR.string.sync_upgrade_lossless_hint),
                    style = MaterialTheme.typography.bodySmall)
                SyncUpgradeCheckbox(
                    checked = state.allDevicesUpdated,
                    title = stringResource(CoreCommonR.string.sync_upgrade_all_devices_updated),
                    enabled = !state.isSaving,
                    onValueChange = onAllDevicesUpdatedChange
                )
                if (state.hasError) {
                    Text(
                        text = stringResource(state.errorDetail?.messageRes ?: CoreCommonR.string.sync_upgrade_attempt_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                    state.errorDetail?.httpStatus?.let { status ->
                        Text(
                            text = "HTTP $status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (state.isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (state.isSyncing) CoreCommonR.string.sync_upgrade_syncing
                            else CoreCommonR.string.sync_upgrade_saving))
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onConfirm, enabled = state.canConfirm) {
                Text(stringResource(CoreCommonR.string.sync_upgrade_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDefer, enabled = !state.isSaving) {
                Text(stringResource(CoreCommonR.string.sync_upgrade_defer))
            }
        }
    )
}

@Composable
private fun SyncUpgradeCheckbox(checked: Boolean, title: String, enabled: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onValueChange
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(title)
    }
}
