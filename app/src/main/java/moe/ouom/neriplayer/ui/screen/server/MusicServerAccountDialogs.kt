package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel

@Composable
internal fun MusicServerAccountEditorDialog(
    profile: SubsonicProfile?,
    onDismiss: () -> Unit,
    onSave: suspend (label: String, address: String, username: String, password: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var label by remember(profile) { mutableStateOf(profile?.label.orEmpty()) }
    var address by remember(profile) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var username by remember(profile) { mutableStateOf(profile?.username.orEmpty()) }
    // 密码只保留在当前对话框内，不读取已保存密码或写入重建状态
    var password by remember(profile) { mutableStateOf("") }
    var busy by remember(profile) { mutableStateOf(false) }
    var error by remember(profile) { mutableStateOf<Int?>(null) }
    var errorRevision by remember(profile) { mutableIntStateOf(0) }
    val dismiss = {
        if (!busy) {
            password = ""
            onDismiss()
        }
    }
    MiuixSettingsDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(if (profile == null) CoreCommonR.string.server_add else CoreCommonR.string.server_edit)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MiuixSettingsTextField(label, { label = it }, enabled = !busy, singleLine = true,
                    modifier = Modifier.testTag("serverAccountLabel"),
                    label = { Text(stringResource(CoreCommonR.string.server_label)) })
                MiuixSettingsTextField(address, { address = it }, enabled = !busy && profile == null,
                    modifier = Modifier.testTag("serverAccountAddress"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(stringResource(CoreCommonR.string.server_address)) })
                MiuixSettingsTextField(username, { username = it }, enabled = !busy && profile == null,
                    modifier = Modifier.testTag("serverAccountUsername"), singleLine = true,
                    label = { Text(stringResource(CoreCommonR.string.server_username)) })
                MiuixSettingsTextField(password, { password = it }, enabled = !busy, singleLine = true,
                    modifier = Modifier.testTag("serverAccountPassword"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(CoreCommonR.string.server_password)) })
                Text(stringResource(if (profile == null) CoreCommonR.string.server_address_help else CoreCommonR.string.server_edit_help),
                    style = MaterialTheme.typography.bodySmall)
                error?.let { messageRes ->
                    key(errorRevision) { MusicServerAccountSaveError(messageRes) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                modifier = Modifier.testTag("serverAccountSave"),
                enabled = !busy && address.isNotBlank() && username.isNotBlank() &&
                    (profile != null || password.isNotEmpty()),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            onSave(label, address, username, password)
                            password = ""
                            onDismiss()
                        } catch (failure: Exception) {
                            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                            error = MusicServerViewModel.userError(failure, accountInput = true)
                            errorRevision++
                        } finally { busy = false }
                    }
                }
            ) { Text(stringResource(if (profile == null) CoreCommonR.string.server_connect else CoreCommonR.string.server_save)) }
        },
        dismissButton = {
            MiuixSettingsTextButton(enabled = !busy, onClick = dismiss,
                modifier = Modifier.testTag("serverAccountCancel")) {
                Text(stringResource(CoreCommonR.string.server_cancel))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicServerAccountSaveError(messageRes: Int) {
    val requester = remember { BringIntoViewRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(positioned) {
        if (!positioned) return@LaunchedEffect
        focusManager.clearFocus()
        keyboard?.hide()
        // 等错误节点完成布局后再定位，避免键盘和输入框争抢可见区域
        withFrameNanos { }
        requester.bringIntoView()
    }
    MiuixSettingsInlineMessage(stringResource(messageRes), isSuccess = false,
        modifier = Modifier.testTag("serverAccountError")
            .bringIntoViewRequester(requester)
            .onGloballyPositioned { positioned = true })
}

@Composable
internal fun MusicServerAccountRemovalDialog(
    profile: SubsonicProfile,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreCommonR.string.server_remove)) },
        text = { Text(stringResource(CoreCommonR.string.server_remove_warning, profile.label)) },
        confirmButton = {
            MiuixSettingsButton(enabled = !busy, onClick = onConfirm,
                modifier = Modifier.testTag("serverAccountRemoveConfirm")) {
                Text(stringResource(CoreCommonR.string.server_remove))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(enabled = !busy, onClick = onDismiss,
                modifier = Modifier.testTag("serverAccountRemoveCancel")) {
                Text(stringResource(CoreCommonR.string.server_cancel))
            }
        }
    )
}
