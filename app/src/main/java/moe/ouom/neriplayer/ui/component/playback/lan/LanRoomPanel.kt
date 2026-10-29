package moe.ouom.neriplayer.ui.component.playback.lan

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.lanltw.session.LanSessionManager
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet

@Composable
internal fun LanRoomEntry(showDescription: Boolean = false) {
    val manager = remember { AppContainer.lanListenTogether }
    val state by manager.state.collectAsState()
    var show by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (showDescription) {
            ListItem(modifier = Modifier.clickable { show = true }, headlineContent = { Text(stringResource(CoreR.string.lan_title)) },
                supportingContent = { Text(if (state.active) status(state) else stringResource(CoreR.string.lan_description)) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
        }
        LanRoomEntryActions(state.active,
            onCreate = { manager.host(Build.MODEL.take(24)); show = true },
            onJoin = { show = true }, onManage = { show = true })
    }
    if (show) LanSheet(manager, state) { show = false }
}

@Composable
internal fun LanRoomEntryActions(active: Boolean, onCreate: () -> Unit, onJoin: () -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (active) {
            OutlinedButton(onClick = onManage, modifier = Modifier.weight(1f)) { Text(stringResource(CoreR.string.lan_manage)) }
        } else {
            OutlinedButton(onClick = onCreate, modifier = Modifier.weight(1f)) { Text(stringResource(CoreR.string.lan_create)) }
            OutlinedButton(onClick = onJoin, modifier = Modifier.weight(1f)) { Text(stringResource(CoreR.string.lan_join_room)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanSheet(manager: LanSessionManager, state: LanSessionState, dismiss: () -> Unit) {
    DisposableEffect(manager) { manager.discovery.discover { manager.reportError("DISCOVERY") }; onDispose { manager.discovery.stop() } }
    DensityScaledModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(CoreR.string.lan_title), style = MaterialTheme.typography.titleLarge)
            state.error?.let { Text(stringResource(moe.ouom.neriplayer.ui.feedback.lanErrorText(it)), color = MaterialTheme.colorScheme.error) }
            if (state.active) RoomControls(manager, state) else JoinControls(manager)
            Text(stringResource(CoreR.string.lan_limits), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.lan_close)) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
@Composable
private fun JoinControls(manager: LanSessionManager) {
    var name by rememberSaveable { mutableStateOf(Build.MODEL.take(24)) }
    var address by rememberSaveable { mutableStateOf("") }
    val nearby by manager.discovery.nearby.collectAsState()
    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text(stringResource(CoreR.string.lan_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = { manager.host(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(CoreR.string.lan_host)) }
    Text(stringResource(CoreR.string.lan_nearby), style = MaterialTheme.typography.titleMedium)
    if (nearby.isEmpty()) Text(stringResource(CoreR.string.lan_empty), style = MaterialTheme.typography.bodySmall)
    nearby.forEach { room ->
        ListItem(headlineContent = { Text(room.name) }, supportingContent = { Text("${room.address}:${room.port}") },
            trailingContent = { TextButton(onClick = { val host = if (room.address.contains(':')) "[${room.address}]" else room.address; manager.join("$host:${room.port}", room.key, name) }) { Text(stringResource(CoreR.string.lan_join)) } })
    }
    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text(stringResource(CoreR.string.lan_address)) }, placeholder = { Text("192.168.1.2:39001") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedButton(onClick = { manager.join(address.trim(), null, name) }, enabled = address.isNotBlank() && name.isNotBlank()) { Text(stringResource(CoreR.string.lan_manual)) }
}
@Composable
private fun RoomControls(manager: LanSessionManager, state: LanSessionState) {
    Text(if (state.preparing) stringResource(CoreR.string.lan_preparing) else status(state))
    SelectionContainer { Text("${state.address}:${state.port}") }
    val room = state.room
    room?.playback?.track?.let { track -> Text(track.name, style = MaterialTheme.typography.titleMedium); Text(track.artist) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (room?.playback?.playing == true) manager.requestPlayback("PAUSE", null, 0.0) else manager.requestPlayback("PLAY_PAUSE", room?.playback?.track, 0.0) }, enabled = lanPlaybackControls(state).playPause) {
            Text(stringResource(if (room?.playback?.playing == true) CoreR.string.lan_pause else CoreR.string.lan_play))
        }
        OutlinedButton(onClick = manager::sync, enabled = state.synced) { Text(stringResource(CoreR.string.lan_sync)) }
    }
    Text(stringResource(CoreR.string.lan_nudge))
    Text(stringResource(CoreR.string.lan_nudge_value, state.nudgeMs))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(-100, -50, -10, 10, 50, 100).forEach { delta ->
            TextButton(onClick = { manager.nudge(delta) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(if (delta > 0) "+$delta" else "$delta") }
        }
    }
    TextButton(onClick = { manager.nudge(-state.nudgeMs) }) { Text(stringResource(CoreR.string.lan_reset)) }
    if (room != null) {
        if (state.hosting) {
            Text(stringResource(CoreR.string.lan_volume))
            RoomVolumeSlider(room.volume) { manager.setVolume(it) }
            Text(stringResource(CoreR.string.lan_permissions))
            PermissionToggle(CoreR.string.lan_allow_next, room.permissions.next) { manager.setPermissions(room.permissions.copy(next = it)) }
            PermissionToggle(CoreR.string.lan_allow_play, room.permissions.playPause) { manager.setPermissions(room.permissions.copy(playPause = it)) }
            PermissionToggle(CoreR.string.lan_allow_seek, room.permissions.seek) { manager.setPermissions(room.permissions.copy(seek = it)) }
        }
        Text(stringResource(CoreR.string.lan_members), style = MaterialTheme.typography.titleMedium)
        room.members.forEach { member ->
            Text(member.name + if (member.host) " · ${stringResource(CoreR.string.lan_host_badge)}" else "")
            if (state.hosting || member.id == state.selfId) RoomVolumeSlider(member.volume) { manager.setVolume(it, member.id) }
        }
    }
    OutlinedButton(onClick = { manager.leave() }) { Text(stringResource(CoreR.string.lan_leave)) }
}
@Composable
private fun RoomVolumeSlider(value: Float, commit: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) draft = value }
    Slider(value = draft, onValueChange = { dragging = true; draft = it }, onValueChangeFinished = { dragging = false; commit(draft) })
}
@Composable
private fun PermissionToggle(label: Int, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(label)); Switch(checked = checked, onCheckedChange = change)
    }
}
@Composable
private fun status(state: LanSessionState): String = when (state.phase) {
    "CONNECTED" -> stringResource(CoreR.string.lan_connected, state.rttMs.toInt())
    "SYNCING" -> stringResource(CoreR.string.lan_syncing)
    "RECONNECTING" -> stringResource(CoreR.string.lan_reconnecting)
    else -> stringResource(CoreR.string.lan_connecting)
}
