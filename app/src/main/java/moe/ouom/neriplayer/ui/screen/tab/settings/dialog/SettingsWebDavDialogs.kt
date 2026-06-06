package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.sync.webdav.WebDavStorage
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticTextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

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

    if (showWebDavConfigDialog) {
        val webDavState by webDavVm.uiState.collectAsState()
        val storage = remember(context) { WebDavStorage(context) }
        var serverUrl by remember(showWebDavConfigDialog) {
            mutableStateOf(storage.getServerUrl().orEmpty())
        }
        var username by remember(showWebDavConfigDialog) {
            mutableStateOf(storage.getUsername().orEmpty())
        }
        var password by remember(showWebDavConfigDialog) {
            mutableStateOf(storage.getPassword().orEmpty())
        }
        var basePath by remember(showWebDavConfigDialog) {
            mutableStateOf(storage.getBasePath())
        }

        LaunchedEffect(webDavState.isConfigured, webDavState.successMessage) {
            if (webDavState.isConfigured && webDavState.successMessage != null) {
                onShowWebDavConfigDialogChange(false)
            }
        }

        AlertDialog(
            onDismissRequest = { onShowWebDavConfigDialogChange(false) },
            title = { Text(stringResource(R.string.webdav_sync_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.webdav_sync_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.webdav_server_url_label)) },
                        placeholder = { Text(stringResource(R.string.webdav_server_url_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.webdav_username_label)) },
                        placeholder = { Text(stringResource(R.string.webdav_username_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.webdav_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { Text(stringResource(R.string.webdav_base_path_label)) },
                        placeholder = { Text(stringResource(R.string.webdav_base_path_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.webdav_remote_file_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                HapticButton(
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
                    Text(stringResource(R.string.webdav_validate_and_save))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { onShowWebDavConfigDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearWebDavConfigDialog) {
        AlertDialog(
            onDismissRequest = { onShowClearWebDavConfigDialogChange(false) },
            title = { Text(stringResource(R.string.sync_clear_config)) },
            text = { Text(stringResource(R.string.webdav_clear_config_desc)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        webDavVm.clearConfiguration(context)
                        onShowClearWebDavConfigDialogChange(false)
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { onShowClearWebDavConfigDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
