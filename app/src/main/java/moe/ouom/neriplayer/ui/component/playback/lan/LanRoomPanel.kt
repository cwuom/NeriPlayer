package moe.ouom.neriplayer.ui.component.playback.lan

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ChevronRight
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
import moe.ouom.neriplayer.ui.component.playback.ListenTogetherPanel
import moe.ouom.neriplayer.ui.component.playback.ListenTogetherPageTransition
import moe.ouom.neriplayer.ui.component.playback.ListenTogetherTab
import moe.ouom.neriplayer.ui.component.playback.PlaybackSheetTextField

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
    if (show) LanSheet { show = false }
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
private fun LanSheet(dismiss: () -> Unit) {
    DensityScaledModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListenTogetherPanel(initialTab = ListenTogetherTab.Lan, onDismiss = dismiss)
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable
private fun JoinControls(manager: LanSessionManager) {
    var name by rememberSaveable { mutableStateOf(Build.MODEL.take(24)) }
    var address by rememberSaveable { mutableStateOf("") }
    val nearby by manager.discovery.nearby.collectAsState()
    PlaybackSheetTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        label = stringResource(CoreR.string.lan_name),
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { manager.host(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(CoreR.string.lan_host)) }
    Text(stringResource(CoreR.string.lan_nearby), style = MaterialTheme.typography.titleMedium)
    if (nearby.isEmpty()) Text(stringResource(CoreR.string.lan_empty), style = MaterialTheme.typography.bodySmall)
    nearby.forEach { room ->
        ListItem(headlineContent = { Text(room.name) }, supportingContent = { Text("${room.address}:${room.port}") },
            trailingContent = { TextButton(onClick = { val host = if (room.address.contains(':')) "[${room.address}]" else room.address; manager.join("$host:${room.port}", room.key, name) }) { Text(stringResource(CoreR.string.lan_join)) } })
    }
    PlaybackSheetTextField(
        value = address,
        onValueChange = { address = it },
        label = stringResource(CoreR.string.lan_address),
        placeholder = "192.168.1.2:39001",
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(onClick = { manager.join(address.trim(), null, name) }, enabled = address.isNotBlank() && name.isNotBlank()) { Text(stringResource(CoreR.string.lan_manual)) }
}
@Composable
private fun RoomControls(manager: LanSessionManager, state: LanSessionState, onDeviceVolume: () -> Unit) {
    Text(if (state.preparing) stringResource(if (state.hosting) CoreR.string.lan_host_preparing else CoreR.string.lan_preparing) else status(state))
    if (state.totalBytes > 0) {
        LinearProgressIndicator(progress = { (state.receivedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${state.receivedBytes / 1024} / ${state.totalBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
    }
    SelectionContainer { Text("${state.address}:${state.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val room = state.room
    if (state.audioSuspended) Text(stringResource(CoreR.string.lan_audio_suspended))
    val playPauseIntent = lanRoomPlayPauseIntent(state)
    val pauseRoom = playPauseIntent == "PAUSE"
    room?.playback?.track?.let { track -> Text(track.name + " · " + track.artist, style = MaterialTheme.typography.titleMedium, maxLines = 2) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { manager.requestPlayback(playPauseIntent, room?.playback?.track, 0.0) },
            enabled = lanPlaybackControls(state).playPause || (state.synced && state.audioSuspended && room?.playback?.playing == true)) {
            Text(stringResource(if (pauseRoom) CoreR.string.lan_pause else CoreR.string.lan_play))
        }
        OutlinedButton(onClick = manager::sync, enabled = state.synced) { Text(stringResource(CoreR.string.lan_sync)) }
    }
    if (room != null) {
        Text(stringResource(CoreR.string.lan_volume))
        LanGroupVolumeSlider(room.volume, enabled = state.synced && (state.hosting || room.permissions.volume)) {
            manager.setVolume(it)
        }
        if (state.hosting) {
            Text(stringResource(CoreR.string.lan_permissions))
            LanPermissionsRow(room.permissions, manager::setPermissions)
        }
        OutlinedButton(onClick = onDeviceVolume, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.lan_device_volume), modifier = Modifier.weight(1f))
            Text(room.members.size.toString())
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
    TextButton(onClick = { manager.leave() }) { Text(stringResource(CoreR.string.lan_leave), color = MaterialTheme.colorScheme.error) }
    HorizontalDivider()
    LanNudgeControls(manager, state)
}
@Composable
private fun LanNudgeControls(manager: LanSessionManager, state: LanSessionState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(CoreR.string.lan_nudge), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { manager.nudge(-state.nudgeMs) }) { Text(stringResource(CoreR.string.lan_reset)) }
    }
    Text(stringResource(CoreR.string.lan_nudge_value, state.nudgeMs), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(-100, -50, -10, 10, 50, 100).forEach { delta ->
            TextButton(onClick = { manager.nudge(delta) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Text(if (delta > 0) "+$delta" else "$delta")
            }
        }
    }
}
@Composable
private fun RoomVolumeSlider(value: Float, commit: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) draft = value }
    Slider(value = draft, onValueChange = { dragging = true; draft = it }, onValueChangeFinished = { dragging = false; commit(draft) })
}
@Composable
internal fun LanPermissionsRow(permissions: LanPermissions, change: (LanPermissions) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LanPermissionChip(permissions.playPause, stringResource(CoreR.string.lan_permission_play_short)) {
            change(permissions.copy(playPause = !permissions.playPause))
        }
        LanPermissionChip(permissions.seek, stringResource(CoreR.string.lan_permission_seek_short)) {
            change(permissions.copy(seek = !permissions.seek))
        }
        LanPermissionChip(permissions.volume, stringResource(CoreR.string.lan_permission_volume_short)) {
            change(permissions.copy(volume = !permissions.volume))
        }
    }
}

@Composable
private fun LanPermissionChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
private fun LanDeviceVolumes(manager: LanSessionManager, state: LanSessionState) {
    val room = state.room ?: return
    room.members.forEach { member ->
        key(member.id) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(member.name + if (member.host) " · ${stringResource(CoreR.string.lan_host_badge)}" else "",
                    modifier = Modifier.weight(1f), maxLines = 2)
                Text("${(member.volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            val editable by rememberUpdatedState(
                member.id == state.selfId || state.synced &&
                    (state.hosting || room.permissions.allowsMemberDeviceVolume(state.selfId, member))
            )
            if (editable) RoomVolumeSlider(member.volume) {
                if (editable) manager.setVolume(it, member.id)
            }
        }
    }
}
@Composable
private fun status(state: LanSessionState): String = when (state.phase) {
    "CONNECTED" -> stringResource(CoreR.string.lan_connected, state.rttMs.toInt())
    "SYNCING" -> stringResource(CoreR.string.lan_syncing)
    "RECONNECTING" -> stringResource(CoreR.string.lan_reconnecting)
    else -> stringResource(CoreR.string.lan_connecting)
}

@Composable
internal fun LanRoomPanel(onDismiss: (() -> Unit)? = null) {
    val manager = remember { AppContainer.lanListenTogether }
    val state by manager.state.collectAsState()
    DisposableEffect(manager) {
        manager.discovery.discover { manager.reportError("DISCOVERY") }
        onDispose { manager.discovery.stop() }
    }
    LanRoomPanelContent(manager, state, onDismiss)
}

@Composable
internal fun LanRoomPanelContent(manager: LanSessionManager, state: LanSessionState, dismiss: (() -> Unit)? = null) {
    var deviceVolumes by rememberSaveable(state.room?.id) { mutableStateOf(false) }
    BackHandler(enabled = deviceVolumes) { deviceVolumes = false }
    ListenTogetherPageTransition(showDetails = deviceVolumes) { showDevices ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (showDevices) IconButton(onClick = { deviceVolumes = false }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CoreR.string.lan_back_room))
                }
                Text(stringResource(if (showDevices) CoreR.string.lan_device_volume else CoreR.string.lan_title),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (dismiss != null) IconButton(onClick = dismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(CoreR.string.lan_close))
                }
            }
            state.error?.let { Text(stringResource(moe.ouom.neriplayer.ui.feedback.lanErrorText(it)), color = MaterialTheme.colorScheme.error) }
            if (showDevices) LanDeviceVolumes(manager, state)
            else if (state.active) RoomControls(manager, state) { deviceVolumes = true }
            else {
                JoinControls(manager)
                Text(stringResource(CoreR.string.lan_limits), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
