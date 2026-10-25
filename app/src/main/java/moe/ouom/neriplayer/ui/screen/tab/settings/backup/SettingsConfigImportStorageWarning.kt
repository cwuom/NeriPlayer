package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun SettingsConfigImportStorageWarning(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.auth_storage_compatibility_title)) },
        text = { Text(stringResource(CoreCommonR.string.auth_storage_compatibility_message)) },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onConfirm) {
                Text(stringResource(CoreCommonR.string.auth_storage_compatibility_continue))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}
