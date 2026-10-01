package moe.ouom.neriplayer.ui.screen.tab.settings.listentogether

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

@Composable
internal fun SettingsListenTogetherDialogs(controller: SettingsListenTogetherController) {
    ListenTogetherDialogHost(controller.showResetUuidDialog) {
        SettingsListenTogetherResetUuidDialog(controller)
    }
    ListenTogetherDialogHost(controller.showNicknameDialog) {
        SettingsListenTogetherNicknameDialog(controller)
    }
    ListenTogetherDialogHost(controller.showJoinDialog) {
        SettingsListenTogetherJoinDialog(controller)
    }
    ListenTogetherDialogHost(controller.showServerDialog) {
        SettingsListenTogetherServerDialog(controller)
    }
}

@Composable
private fun ListenTogetherDialogHost(visible: Boolean, content: @Composable () -> Unit) {
    if (visible) content()
}

@Composable
private fun SettingsListenTogetherResetUuidDialog(controller: SettingsListenTogetherController) {
    MiuixSettingsDialog(
        onDismissRequest = controller.dismissResetUuidAction,
        title = { Text(stringResource(CoreCommonR.string.listen_together_reset_uuid)) },
        text = { Text(stringResource(CoreCommonR.string.listen_together_reset_uuid_confirm)) },
        confirmButton = {
            MiuixSettingsTextButton(onClick = controller::resetUuid) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = controller::dismissResetUuidDialog) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SettingsListenTogetherNicknameDialog(controller: SettingsListenTogetherController) {
    MiuixSettingsDialog(
        onDismissRequest = controller.dismissNicknameAction,
        title = { Text(stringResource(CoreCommonR.string.settings_listen_together_default_nickname_title)) },
        text = { SettingsListenTogetherNicknameInput(controller) },
        confirmButton = {
            MiuixSettingsTextButton(onClick = controller::applyNickname) {
                Text(stringResource(CoreCommonR.string.action_apply))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = controller::dismissNicknameDialog) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SettingsListenTogetherNicknameInput(controller: SettingsListenTogetherController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixSettingsTextField(
            value = controller.nicknameInput,
            onValueChange = controller.updateNicknameAction,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(CoreCommonR.string.settings_listen_together_default_nickname_input_label)) }
        )
        SettingsListenTogetherErrorMessage(controller.nicknameError)
    }
}

@Composable
private fun SettingsListenTogetherJoinDialog(controller: SettingsListenTogetherController) {
    MiuixSettingsDialog(
        onDismissRequest = controller.dismissJoinAction,
        title = { Text(stringResource(CoreCommonR.string.listen_together_join_room)) },
        text = { SettingsListenTogetherJoinInput(controller) },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = controller::confirmJoin,
                enabled = !controller.joining
            ) {
                Text(stringResource(listenTogetherJoinButtonLabelId(controller.joining)))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(
                onClick = controller::dismissJoinDialog,
                enabled = !controller.joining
            ) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

internal fun listenTogetherJoinButtonLabelId(joining: Boolean): Int =
    if (joining) CoreCommonR.string.listen_together_joining_room else CoreCommonR.string.listen_together_join_room

@Composable
private fun SettingsListenTogetherJoinInput(controller: SettingsListenTogetherController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(CoreCommonR.string.settings_listen_together_join_room_desc))
        MiuixSettingsTextField(
            value = controller.inviteInput,
            onValueChange = controller.updateInviteAction,
            enabled = !controller.joining,
            minLines = 2,
            maxLines = 5,
            label = { Text(stringResource(CoreCommonR.string.settings_listen_together_join_invite_input_label)) },
            placeholder = { Text(stringResource(CoreCommonR.string.settings_listen_together_join_invite_input_placeholder)) }
        )
        SettingsListenTogetherErrorMessage(controller.inviteError)
        SettingsListenTogetherJoinProgress(controller.joining)
    }
}

@Composable
private fun SettingsListenTogetherJoinProgress(joining: Boolean) {
    if (joining) SettingsListenTogetherProgress(CoreCommonR.string.listen_together_joining_room)
}

@Composable
private fun SettingsListenTogetherServerDialog(controller: SettingsListenTogetherController) {
    MiuixSettingsDialog(
        onDismissRequest = controller.dismissServerAction,
        title = { Text(stringResource(CoreCommonR.string.settings_listen_together_server_title)) },
        text = { SettingsListenTogetherServerDialogContent(controller) },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = controller::applyServer,
                enabled = !controller.serverTesting
            ) {
                Text(stringResource(CoreCommonR.string.action_apply))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(
                onClick = controller::dismissServerDialog,
                enabled = !controller.serverTesting
            ) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SettingsListenTogetherServerDialogContent(controller: SettingsListenTogetherController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(listenTogetherServerDescriptionId(controller.isUsingDefaultServer)))
        SettingsListenTogetherServerInput(controller)
        SettingsListenTogetherServerFeedback(controller)
        SettingsListenTogetherServerActions(controller)
    }
}

internal fun listenTogetherServerDescriptionId(usingDefault: Boolean): Int =
    if (usingDefault) CoreCommonR.string.settings_listen_together_server_default_desc
    else CoreCommonR.string.settings_listen_together_server_custom_desc

@Composable
private fun SettingsListenTogetherServerInput(controller: SettingsListenTogetherController) {
    MiuixSettingsTextField(
        value = controller.serverInput,
        onValueChange = controller.updateServerAction,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(CoreCommonR.string.settings_listen_together_server_input_label)) },
        placeholder = { Text(stringResource(CoreCommonR.string.settings_listen_together_server_input_placeholder)) }
    )
}

@Composable
private fun SettingsListenTogetherServerFeedback(controller: SettingsListenTogetherController) {
    if (controller.serverTesting) {
        SettingsListenTogetherProgress(CoreCommonR.string.settings_listen_together_server_testing)
    } else {
        SettingsListenTogetherServerResult(controller.serverTestMessage)
    }
}

@Composable
private fun SettingsListenTogetherServerResult(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsListenTogetherServerActions(controller: SettingsListenTogetherController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsListenTogetherTestServerButton(controller)
        SettingsListenTogetherResetServerButton(controller)
    }
}

@Composable
private fun SettingsListenTogetherTestServerButton(controller: SettingsListenTogetherController) {
    MiuixSettingsOutlinedButton(
        onClick = controller.testServerAction,
        enabled = !controller.serverTesting
    ) {
        Text(stringResource(CoreCommonR.string.settings_listen_together_server_test))
    }
}

@Composable
private fun SettingsListenTogetherResetServerButton(controller: SettingsListenTogetherController) {
    MiuixSettingsTextButton(
        onClick = controller.resetServerInputAction,
        enabled = !controller.serverTesting
    ) {
        Text(stringResource(CoreCommonR.string.action_reset))
    }
}

@Composable
private fun SettingsListenTogetherErrorMessage(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SettingsListenTogetherProgress(messageId: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text = stringResource(messageId), style = MaterialTheme.typography.bodySmall)
    }
}
