package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import moe.ouom.neriplayer.ui.sync.upgrade.syncProtocolStartupConfigurationGate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Stable
internal class WebDavConfigDraft(
    val serverUrl: MutableState<String>,
    val username: MutableState<String>,
    val password: MutableState<String>,
    val basePath: MutableState<String>
)

/** The password is deliberately kept out of saved instance state and re-read from storage on restore. */
@Composable
internal fun rememberWebDavConfigDraft(
    loadServerUrl: () -> String,
    loadUsername: () -> String,
    loadPassword: () -> String,
    loadBasePath: () -> String
): WebDavConfigDraft {
    val serverUrl = rememberSaveable { mutableStateOf(loadServerUrl()) }
    val username = rememberSaveable { mutableStateOf(loadUsername()) }
    val password = remember { mutableStateOf(loadPassword()) }
    val basePath = rememberSaveable { mutableStateOf(loadBasePath()) }
    return remember(serverUrl, username, password, basePath) {
        WebDavConfigDraft(serverUrl, username, password, basePath)
    }
}

@Composable
internal fun SettingsWebDavDialogs(
    showWebDavConfigDialog: Boolean,
    onShowWebDavConfigDialogChange: (Boolean) -> Unit,
    showClearWebDavConfigDialog: Boolean,
    onShowClearWebDavConfigDialogChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val webDavVm: WebDavSyncViewModel = viewModel()

    LaunchedEffect(webDavVm, context) {
        webDavVm.initialize(context)
    }

    if (syncProtocolStartupConfigurationGate(
            dialogRequested = showWebDavConfigDialog || showClearWebDavConfigDialog,
            onDismiss = {
                onShowWebDavConfigDialogChange(false)
                onShowClearWebDavConfigDialogChange(false)
            }
        )) return

    if (showWebDavConfigDialog) {
        val webDavState by webDavVm.uiState.collectAsStateWithLifecycleCompat()
        val storage = remember(context) { WebDavStorage(context) }
        val draft = rememberWebDavConfigDraft(
            loadServerUrl = { storage.getServerUrl().orEmpty() },
            loadUsername = { storage.getUsername().orEmpty() },
            loadPassword = { storage.getPassword().orEmpty() },
            loadBasePath = storage::getBasePath
        )
        var serverUrl by draft.serverUrl
        var username by draft.username
        var password by draft.password
        var basePath by draft.basePath

        val dismissConfigDialog = {
            webDavVm.clearMessages()
            onShowWebDavConfigDialogChange(false)
        }

        MiuixSettingsDialog(
            onDismissRequest = dismissConfigDialog,
            title = { Text(stringResource(CoreCommonR.string.webdav_sync_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    webDavState.errorMessage?.let { error ->
                        MiuixSettingsInlineMessage(
                            message = error,
                            isSuccess = false,
                            onClose = webDavVm::clearMessages
                        )
                    }
                    webDavState.successMessage?.let { message ->
                        MiuixSettingsInlineMessage(
                            message = message,
                            isSuccess = true,
                            onClose = webDavVm::clearMessages
                        )
                    }
                    Text(
                        text = stringResource(CoreCommonR.string.webdav_sync_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    MiuixSettingsTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(CoreCommonR.string.webdav_server_url_label)) },
                        placeholder = { Text(stringResource(CoreCommonR.string.webdav_server_url_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixSettingsTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(CoreCommonR.string.webdav_username_label)) },
                        placeholder = { Text(stringResource(CoreCommonR.string.webdav_username_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixSettingsTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(CoreCommonR.string.webdav_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixSettingsTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { Text(stringResource(CoreCommonR.string.webdav_base_path_label)) },
                        placeholder = { Text(stringResource(CoreCommonR.string.webdav_base_path_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(CoreCommonR.string.webdav_remote_file_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                when {
                    webDavState.isConfigured -> {
                        MiuixSettingsButton(onClick = dismissConfigDialog) {
                            Text(stringResource(CoreCommonR.string.action_close))
                        }
                    }
                    else -> {
                        MiuixSettingsButton(
                            onClick = {
                                webDavVm.validateAndSaveConfiguration(
                                    context = context,
                                    serverUrl = serverUrl,
                                    username = username,
                                    password = password,
                                    basePath = basePath
                                )
                            },
                            enabled = !webDavState.isValidating
                        ) {
                            if (webDavState.isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(stringResource(CoreCommonR.string.webdav_validate_and_save))
                        }
                    }
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = dismissConfigDialog) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }

    if (showClearWebDavConfigDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { onShowClearWebDavConfigDialogChange(false) },
            title = { Text(stringResource(CoreCommonR.string.sync_clear_config)) },
            text = { Text(stringResource(CoreCommonR.string.webdav_clear_config_desc)) },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        webDavVm.clearConfiguration(context)
                        onShowClearWebDavConfigDialogChange(false)
                    }
                ) {
                    Text(
                        stringResource(CoreCommonR.string.action_confirm_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { onShowClearWebDavConfigDialogChange(false) }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}
