package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import moe.ouom.neriplayer.R as AppR

internal fun isWebDavCleartextUrl(serverUrl: String): Boolean =
    serverUrl.trim().toHttpUrlOrNull()?.scheme == "http"

@Composable
internal fun WebDavCleartextWarning(serverUrl: String, modifier: Modifier = Modifier) {
    if (!isWebDavCleartextUrl(serverUrl)) return
    MiuixSettingsInlineMessage(
        message = stringResource(AppR.string.webdav_cleartext_warning),
        isSuccess = false,
        modifier = modifier
    )
}

@Composable
internal fun WebDavCleartextConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(AppR.string.webdav_cleartext_confirm_title)) },
        text = { Text(stringResource(AppR.string.webdav_cleartext_confirm_message)) },
        confirmButton = {
            MiuixSettingsButton(onClick = onConfirm) {
                Text(stringResource(AppR.string.webdav_cleartext_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(AppR.string.webdav_cleartext_cancel))
            }
        }
    )
}
