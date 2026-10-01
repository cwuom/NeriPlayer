package moe.ouom.neriplayer.ui.screen.tab.settings.listentogether

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable

@Composable
internal fun SettingsListenTogetherSection(
    controller: SettingsListenTogetherController,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsListenTogetherJoinItem(controller)
        SettingsListenTogetherServerItem(controller)
        SettingsListenTogetherNicknameItem(controller)
        SettingsListenTogetherIdentityItem(controller)
    }
}

@Composable
private fun SettingsListenTogetherJoinItem(controller: SettingsListenTogetherController) {
    ListItem(
        modifier = Modifier.listenTogetherRoomActionModifier(controller.isInRoom, controller.openJoinAction),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.MeetingRoom,
                contentDescription = stringResource(CoreCommonR.string.listen_together_join_room),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.listen_together_join_room)) },
        supportingContent = {
            Text(stringResource(listenTogetherJoinDescriptionId(controller.isInRoom)))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsListenTogetherServerItem(controller: SettingsListenTogetherController) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = controller.openServerAction),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = stringResource(CoreCommonR.string.settings_listen_together_server_title),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.settings_listen_together_server_title)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(listenTogetherServerDescriptionId(controller.isUsingDefaultServer))
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsListenTogetherNicknameItem(controller: SettingsListenTogetherController) {
    ListItem(
        modifier = Modifier.listenTogetherRoomActionModifier(controller.isInRoom, controller.openNicknameAction),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.FormatSize,
                contentDescription = stringResource(CoreCommonR.string.settings_listen_together_default_nickname_title),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.settings_listen_together_default_nickname_title)) },
        supportingContent = {
            Text(
                listenTogetherNicknameDescription(
                    isInRoom = controller.isInRoom,
                    nickname = controller.currentNickname,
                    disabledText = stringResource(CoreCommonR.string.settings_listen_together_default_nickname_disabled),
                    unsetText = stringResource(CoreCommonR.string.settings_listen_together_default_nickname_unset)
                )
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsListenTogetherIdentityItem(controller: SettingsListenTogetherController) {
    ListItem(
        modifier = Modifier.listenTogetherRoomActionModifier(controller.isInRoom, controller.openResetUuidAction),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.RestartAlt,
                contentDescription = stringResource(CoreCommonR.string.listen_together_reset_uuid),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.listen_together_reset_uuid)) },
        supportingContent = {
            Text(stringResource(listenTogetherIdentityDescriptionId(controller.isInRoom)))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun Modifier.listenTogetherRoomActionModifier(isInRoom: Boolean, onClick: () -> Unit): Modifier =
    if (isInRoom) this.alpha(0.5f) else this.settingsItemClickable(onClick = onClick)

internal fun listenTogetherJoinDescriptionId(isInRoom: Boolean): Int =
    if (isInRoom) CoreCommonR.string.settings_listen_together_join_room_disabled
    else CoreCommonR.string.settings_listen_together_join_room_desc

internal fun listenTogetherIdentityDescriptionId(isInRoom: Boolean): Int =
    if (isInRoom) CoreCommonR.string.listen_together_reset_uuid_disabled
    else CoreCommonR.string.settings_listen_together_reset_identity_desc

internal fun listenTogetherNicknameDescription(
    isInRoom: Boolean,
    nickname: String,
    disabledText: String,
    unsetText: String
): String = if (isInRoom) disabledText else nickname.ifBlank { unsetText }
