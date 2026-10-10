package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel

/** Account management lives in Settings; entering this page never starts a library request. */
@Composable
fun MusicServerSettingsContent() {
    val repository = AppContainer.subsonicRepository
    val profiles by repository.accounts.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<SubsonicProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SubsonicProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loaded = false; error = null
        try { repository.accounts.load(); loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = CoreCommonR.string.server_error_load_accounts }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!loaded && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            if (!loaded) TextButton(onClick = { reload++ }) { Text(stringResource(CoreCommonR.string.server_retry)) }
        }
        profiles.forEach { profile ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
                Text(profile.username, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = loaded && !busy, onClick = { editing = profile; showEditor = true }) {
                        Text(stringResource(CoreCommonR.string.server_edit))
                    }
                    TextButton(enabled = loaded && !busy, onClick = { removing = profile }) {
                        Text(stringResource(CoreCommonR.string.server_remove))
                    }
                }
            }
        }
        Button(enabled = loaded && !busy, onClick = { editing = null; showEditor = true }) {
            Text(stringResource(CoreCommonR.string.server_add))
        }
    }
    if (showEditor) ServerAccountEditor(editing, onDismiss = { showEditor = false })
    removing?.let { profile ->
        AlertDialog(onDismissRequest = { if (!busy) removing = null },
            title = { Text(stringResource(CoreCommonR.string.server_remove)) },
            text = { Text(stringResource(CoreCommonR.string.server_remove_warning, profile.label)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { repository.accounts.remove(profile.id); removing = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = CoreCommonR.string.server_error_remove_account; removing = null }
                        finally { busy = false }
                    }
                }) { Text(stringResource(CoreCommonR.string.server_remove)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) {
                Text(stringResource(CoreCommonR.string.server_cancel))
            } })
    }
}

@Composable
private fun ServerAccountEditor(profile: SubsonicProfile?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember(profile) { mutableStateOf(profile?.label.orEmpty()) }
    var address by remember(profile) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var username by remember(profile) { mutableStateOf(profile?.username.orEmpty()) }
    // Never populate the form with a stored password or save it into saved-instance state.
    var password by remember(profile) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (profile == null) CoreCommonR.string.server_add else CoreCommonR.string.server_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(CoreCommonR.string.server_label)) })
                OutlinedTextField(address, { address = it }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(stringResource(CoreCommonR.string.server_address)) })
                OutlinedTextField(username, { username = it }, enabled = !busy && profile == null, singleLine = true,
                    label = { Text(stringResource(CoreCommonR.string.server_username)) })
                OutlinedTextField(password, { password = it }, enabled = !busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(CoreCommonR.string.server_password)) })
                Text(stringResource(if (profile == null) CoreCommonR.string.server_address_help else CoreCommonR.string.server_edit_help))
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && address.isNotBlank() && username.isNotBlank() &&
                (profile != null || password.isNotEmpty()), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val repository = AppContainer.subsonicRepository
                        if (profile == null) repository.addAccount(label, address, username, password)
                        else repository.updateAccount(profile, label, address, password)
                        password = ""; onDismiss()
                    } catch (failure: Exception) {
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        error = MusicServerViewModel.userError(failure, accountInput = true)
                    } finally { busy = false }
                }
            }) { Text(stringResource(if (profile == null) CoreCommonR.string.server_connect else CoreCommonR.string.server_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) {
            Text(stringResource(CoreCommonR.string.server_cancel))
        } })
}
