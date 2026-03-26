package moe.ouom.neriplayer.ui.screen.debug

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.ListenTogetherPreferences
import moe.ouom.neriplayer.listentogether.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.ListenTogetherSessionManager
import moe.ouom.neriplayer.listentogether.buildListenTogetherInviteUri
import moe.ouom.neriplayer.listentogether.normalizeListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.validateListenTogetherNickname
import moe.ouom.neriplayer.listentogether.validateListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.validateListenTogetherUserUuid

@Composable
fun ListenTogetherRoomPanel(
    modifier: Modifier = Modifier,
    showBaseUrlEditor: Boolean = false
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val clipboard = LocalClipboardManager.current
    val sessionManager = remember { AppContainer.listenTogetherSessionManager }
    val preferences = remember { AppContainer.listenTogetherPreferences }
    val sessionState by sessionManager.sessionState.collectAsState()
    val roomState by sessionManager.roomState.collectAsState()
    val savedBaseUrl by preferences.workerBaseUrlFlow.collectAsState(initial = "")
    val savedUserUuid by preferences.userUuidFlow.collectAsState(initial = "")
    val savedNickname by preferences.nicknameFlow.collectAsState(initial = "")
    val savedAllowMemberControl by preferences.allowMemberControlFlow.collectAsState(initial = true)
    val savedAutoPauseOnMemberChange by preferences.autoPauseOnMemberChangeFlow.collectAsState(initial = true)
    val savedShareAudioLinks by preferences.shareAudioLinksFlow.collectAsState(initial = true)
    val currentQueue by PlayerManager.currentQueueFlow.collectAsState()
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val positionMs by PlayerManager.playbackPositionFlow.collectAsState()

    var baseUrl by rememberSaveable { mutableStateOf("") }
    var roomIdInput by rememberSaveable { mutableStateOf("") }
    var userUuid by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var allowMemberControl by rememberSaveable { mutableStateOf(true) }
    var autoPauseOnMemberChange by rememberSaveable { mutableStateOf(true) }
    var shareAudioLinks by rememberSaveable { mutableStateOf(true) }
    var runningActionResId by remember { mutableStateOf<Int?>(null) }

    val isInRoom = !sessionState.roomId.isNullOrBlank()
    val role = resolveListenTogetherRole(sessionState.userUuid, sessionState.role, roomState)
    val isController = role == "controller"
    val effectiveBaseUrl = resolveListenTogetherBaseUrl(baseUrl)
    val roomSettings = roomState?.settings ?: ListenTogetherRoomSettings(
        allowMemberControl = allowMemberControl,
        autoPauseOnMemberChange = autoPauseOnMemberChange,
        shareAudioLinks = shareAudioLinks
    )
    val inviteUri = remember(sessionState.roomId, sessionState.nickname, effectiveBaseUrl) {
        sessionState.roomId?.let {
            buildListenTogetherInviteUri(it, sessionState.nickname, effectiveBaseUrl)
        }
    }

    LaunchedEffect(savedBaseUrl) { if (baseUrl.isBlank()) baseUrl = resolveListenTogetherBaseUrl(savedBaseUrl) }
    LaunchedEffect(savedUserUuid) {
        if (userUuid.isBlank()) {
            userUuid = if (savedUserUuid.isBlank()) {
                preferences.getOrCreateUserUuid()
            } else {
                savedUserUuid
            }
        }
    }
    LaunchedEffect(savedNickname) {
        if (nickname.isBlank()) {
            nickname = if (savedNickname.isBlank()) {
                preferences.getOrCreateNickname()
            } else {
                savedNickname
            }
        }
    }
    LaunchedEffect(sessionState.roomId) { sessionState.roomId?.let { roomIdInput = it } }
    LaunchedEffect(savedAllowMemberControl, savedAutoPauseOnMemberChange, savedShareAudioLinks, isInRoom) {
        if (!isInRoom) {
            allowMemberControl = savedAllowMemberControl
            autoPauseOnMemberChange = savedAutoPauseOnMemberChange
            shareAudioLinks = savedShareAudioLinks
        }
    }
    LaunchedEffect(roomState?.settings, isController) {
        if (isController && roomState != null) {
            allowMemberControl = roomSettings.allowMemberControl
            autoPauseOnMemberChange = roomSettings.autoPauseOnMemberChange
            shareAudioLinks = roomSettings.shareAudioLinks
        }
    }
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showBaseUrlEditor) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    label = { Text(stringResource(R.string.listen_together_worker_base_url)) },
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.trim().take(24) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                label = { Text(stringResource(R.string.listen_together_nickname)) },
                singleLine = true
            )
            OutlinedTextField(
                value = roomIdInput,
                onValueChange = { roomIdInput = normalizeListenTogetherRoomId(it).take(6) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                label = { Text(stringResource(R.string.listen_together_room_id)) },
                singleLine = true,
                readOnly = isInRoom
            )
            runningActionResId?.let { resId ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(resId), style = MaterialTheme.typography.bodySmall)
                }
            }
            validateListenTogetherNickname(nickname)?.let { ErrorText(it) }
            if (!isInRoom) validateListenTogetherRoomId(roomIdInput)?.takeIf { roomIdInput.isNotBlank() }?.let { ErrorText(it) }
            if (!isInRoom) RoomActions(
                runningActionResId = runningActionResId,
                currentQueue = currentQueue,
                currentSong = currentSong,
                isPlaying = isPlaying,
                positionMs = positionMs,
                activity = activity,
                userUuid = userUuid,
                nickname = nickname,
                roomIdInput = roomIdInput,
                effectiveBaseUrl = effectiveBaseUrl,
                roomSettings = ListenTogetherRoomSettings(allowMemberControl, autoPauseOnMemberChange, shareAudioLinks),
                sessionState = sessionState,
                preferences = preferences,
                sessionManager = sessionManager,
                onRunningActionChange = { runningActionResId = it }
            )
            if (isInRoom) ConnectedActions(runningActionResId, effectiveBaseUrl, nickname, roomIdInput, sessionState, sessionManager, preferences, activity) { runningActionResId = it }
            if (isController) {
                TextButton(
                    onClick = {
                        val roomId = sessionState.roomId ?: return@TextButton
                        val inviteText = buildString {
                            append(context.getString(R.string.listen_together_invite_share_text, sessionState.nickname ?: context.getString(R.string.listen_together_title), roomId))
                            inviteUri?.let { append("\n"); append(it) }
                        }
                        clipboard.setText(AnnotatedString(inviteText))
                        Toast.makeText(context, context.getString(R.string.listen_together_invite_copied), Toast.LENGTH_SHORT).show()
                    },
                    enabled = !sessionState.roomId.isNullOrBlank(),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text(stringResource(R.string.listen_together_copy_invite)) }
            }
            if (isController || !isInRoom) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SettingsSection(
                    settings = if (isInRoom) roomSettings else ListenTogetherRoomSettings(allowMemberControl, autoPauseOnMemberChange, shareAudioLinks),
                    enabled = runningActionResId == null && (!isInRoom || isController),
                    onSettingsChange = { updated ->
                        allowMemberControl = updated.allowMemberControl
                        autoPauseOnMemberChange = updated.autoPauseOnMemberChange
                        shareAudioLinks = updated.shareAudioLinks
                        activity?.lifecycleScope?.launch {
                            runCatching {
                                persistSettings(preferences, effectiveBaseUrl, userUuid, nickname, updated)
                                if (isInRoom && isController) {
                                    val result = sessionManager.updateRoomSettings(updated)
                                    check(result.ok) { result.error ?: "websocket unavailable" }
                                }
                            }.onFailure { Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show() }
                        } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
                    }
                )
            }
            StatusSection(sessionState, roomState, role, currentSong?.name, isPlaying)
            MemberSection(roomState?.members?.sortedBy { it.joinedAt }.orEmpty())
        }
    }
}

@Composable
private fun RoomActions(
    runningActionResId: Int?,
    currentQueue: List<moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem>,
    currentSong: moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem?,
    isPlaying: Boolean,
    positionMs: Long,
    activity: ComponentActivity?,
    userUuid: String,
    nickname: String,
    roomIdInput: String,
    effectiveBaseUrl: String,
    roomSettings: ListenTogetherRoomSettings,
    sessionState: moe.ouom.neriplayer.listentogether.ListenTogetherSessionState,
    preferences: ListenTogetherPreferences,
    sessionManager: ListenTogetherSessionManager,
    onRunningActionChange: (Int?) -> Unit
) {
    val context = LocalContext.current
    val userUuidError = validateListenTogetherUserUuid(userUuid)
    val nicknameError = validateListenTogetherNickname(nickname)
    val roomIdError = validateListenTogetherRoomId(roomIdInput)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            activity?.lifecycleScope?.launch {
                onRunningActionChange(R.string.listen_together_creating_room)
                runCatching {
                    persistSettings(preferences, effectiveBaseUrl, userUuid, nickname, roomSettings)
                    sessionManager.createRoom(effectiveBaseUrl, userUuid, nickname, currentQueue, currentQueue.indexOfFirst { it == currentSong }.takeIf { it >= 0 } ?: 0, positionMs, isPlaying, roomSettings)
                    sessionManager.connectWebSocket()
                }.onFailure { Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show() }
                onRunningActionChange(null)
            } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
        }, enabled = runningActionResId == null && currentQueue.isNotEmpty() && userUuidError == null && nicknameError == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null); Text(stringResource(R.string.listen_together_create_and_connect))
        }
        Button(onClick = {
            activity?.lifecycleScope?.launch {
                onRunningActionChange(R.string.listen_together_joining_room)
                runCatching {
                    val targetRoomId = normalizeListenTogetherRoomId(roomIdInput)
                    val currentRoomId = sessionState.roomId?.let(::normalizeListenTogetherRoomId)
                    if (currentRoomId != null && currentRoomId == targetRoomId) {
                        Toast.makeText(context, context.getString(R.string.listen_together_same_room_join_ignored, targetRoomId), Toast.LENGTH_SHORT).show()
                        return@runCatching
                    }
                    persistSettings(preferences, effectiveBaseUrl, userUuid, nickname, roomSettings)
                    PlayerManager.resetForListenTogetherJoin()
                    sessionManager.leaveRoom()
                    sessionManager.joinRoom(effectiveBaseUrl, targetRoomId, userUuid, nickname)
                    sessionManager.connectWebSocket()
                }.onFailure { Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show() }
                onRunningActionChange(null)
            } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
        }, enabled = runningActionResId == null && roomIdInput.isNotBlank() && userUuidError == null && nicknameError == null && roomIdError == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Link, contentDescription = null); Text(stringResource(R.string.listen_together_join_and_connect))
        }
    }
}

@Composable
private fun ConnectedActions(
    runningActionResId: Int?,
    effectiveBaseUrl: String,
    nickname: String,
    roomIdInput: String,
    sessionState: moe.ouom.neriplayer.listentogether.ListenTogetherSessionState,
    sessionManager: ListenTogetherSessionManager,
    preferences: ListenTogetherPreferences,
    activity: ComponentActivity?,
    onRunningActionChange: (Int?) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            activity?.lifecycleScope?.launch {
                val roomId = sessionState.roomId ?: roomIdInput
                if (roomId.isBlank()) return@launch
                onRunningActionChange(R.string.listen_together_refreshing_room_state)
                runCatching {
                    preferences.setNickname(nickname)
                    sessionManager.refreshRoomState(effectiveBaseUrl, roomId)
                }.onFailure { Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show() }
                onRunningActionChange(null)
            } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
        }, enabled = runningActionResId == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null); Text(stringResource(R.string.action_refresh))
        }
        Button(onClick = { sessionManager.leaveRoom() }, enabled = sessionState.connectionState != ListenTogetherConnectionState.CONNECTING, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null); Text(stringResource(R.string.listen_together_leave_room))
        }
    }
}

private fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@Composable
private fun SettingsSection(settings: ListenTogetherRoomSettings, enabled: Boolean, onSettingsChange: (ListenTogetherRoomSettings) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.listen_together_settings_title), style = MaterialTheme.typography.titleSmall)
        SettingToggleRow(stringResource(R.string.listen_together_setting_member_control_title), stringResource(R.string.listen_together_setting_member_control_desc), settings.allowMemberControl, enabled) { onSettingsChange(settings.copy(allowMemberControl = it)) }
        SettingToggleRow(stringResource(R.string.listen_together_setting_auto_pause_title), stringResource(R.string.listen_together_setting_auto_pause_desc), settings.autoPauseOnMemberChange, enabled) { onSettingsChange(settings.copy(autoPauseOnMemberChange = it)) }
        SettingToggleRow(stringResource(R.string.listen_together_setting_share_audio_links_title), stringResource(R.string.listen_together_setting_share_audio_links_desc), settings.shareAudioLinks, enabled) { onSettingsChange(settings.copy(shareAudioLinks = it)) }
    }
}

@Composable
private fun SettingToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusSection(
    sessionState: moe.ouom.neriplayer.listentogether.ListenTogetherSessionState,
    roomState: moe.ouom.neriplayer.listentogether.ListenTogetherRoomState?,
    role: String?,
    fallbackTrackName: String?,
    isPlaying: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DebugValueRow(stringResource(R.string.listen_together_connection), stringResource(sessionState.connectionState.labelResId()))
        DebugValueRow(stringResource(R.string.listen_together_role), stringResource(roleLabelResId(role)))
        DebugValueRow(stringResource(R.string.listen_together_room_status), stringResource(roomStatusLabelResId(roomState?.roomStatus)))
        DebugValueRow(stringResource(R.string.listen_together_room_id), sessionState.roomId ?: "-")
        DebugValueRow(stringResource(R.string.listen_together_version), roomState?.version?.toString() ?: "-")
        DebugValueRow(stringResource(R.string.listen_together_members), roomState?.members?.size?.toString() ?: "0")
        DebugValueRow(stringResource(R.string.listen_together_queue_size), roomState?.queue?.size?.toString() ?: "0")
        DebugValueRow(stringResource(R.string.listen_together_track), roomState?.track?.name ?: fallbackTrackName ?: "-")
        DebugValueRow(stringResource(R.string.listen_together_playback), stringResource(if ((roomState?.playback?.state ?: if (isPlaying) "playing" else "paused") == "playing") R.string.listen_together_playback_playing else R.string.listen_together_playback_paused))
        sessionState.lastError?.takeIf { it.isNotBlank() }?.let { DebugValueRow(stringResource(R.string.listen_together_last_error), it) }
        sessionState.roomNotice?.takeIf { it.isNotBlank() && !it.startsWith("member_joined:") && !it.startsWith("member_left:") }?.let { DebugValueRow(stringResource(R.string.listen_together_notice), it.toDisplayNotice(LocalContext.current)) }
    }
}

@Composable
private fun MemberSection(members: List<moe.ouom.neriplayer.listentogether.ListenTogetherMember>) {
    if (members.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.listen_together_member_list_title), style = MaterialTheme.typography.titleSmall)
        members.forEach { member ->
            DebugValueRow(
                member.nickname.ifBlank { member.userUuid.ifBlank { member.userId.orEmpty() } },
                stringResource(roleLabelResId(member.role))
            )
        }
    }
}

@Composable private fun ErrorText(message: String) { Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
@Composable private fun DebugValueRow(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(value, style = MaterialTheme.typography.bodySmall) } }
@Composable fun ListenTogetherDebugPanel(modifier: Modifier = Modifier) { ListenTogetherRoomPanel(modifier, false) }

private suspend fun persistSettings(
    preferences: ListenTogetherPreferences,
    baseUrl: String,
    userUuid: String,
    nickname: String,
    settings: ListenTogetherRoomSettings
) {
    preferences.setWorkerBaseUrl(baseUrl)
    preferences.setUserUuid(userUuid)
    preferences.setNickname(nickname)
    preferences.setAllowMemberControl(settings.allowMemberControl)
    preferences.setAutoPauseOnMemberChange(settings.autoPauseOnMemberChange)
    preferences.setShareAudioLinks(settings.shareAudioLinks)
}

private fun ListenTogetherConnectionState.labelResId(): Int = when (this) {
    ListenTogetherConnectionState.DISCONNECTED -> R.string.listen_together_connection_disconnected
    ListenTogetherConnectionState.CONNECTING -> R.string.listen_together_connection_connecting
    ListenTogetherConnectionState.CONNECTED -> R.string.listen_together_connection_connected
}

private fun roleLabelResId(role: String?): Int = when (role) {
    "controller" -> R.string.listen_together_role_controller
    "listener" -> R.string.listen_together_role_listener
    else -> R.string.listen_together_role_none
}

private fun resolveListenTogetherRole(userUuid: String?, fallbackRole: String?, roomState: moe.ouom.neriplayer.listentogether.ListenTogetherRoomState?): String? {
    val sessionUserId = userUuid?.trim()?.takeIf { it.isNotBlank() }
    val controllerUserId = roomState?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
        ?: roomState?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
    return if (sessionUserId != null && controllerUserId != null) if (sessionUserId == controllerUserId) "controller" else "listener" else fallbackRole
}

private fun roomStatusLabelResId(status: String?): Int = when (status) {
    ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> R.string.listen_together_room_status_controller_offline
    ListenTogetherRoomStatuses.CLOSED -> R.string.listen_together_room_status_closed
    else -> R.string.listen_together_room_status_active
}

private fun String.toDisplayNotice(context: android.content.Context): String = when {
    startsWith("controller_offline:") -> context.getString(R.string.listen_together_notice_controller_offline, substringAfter(':').toLongOrNull() ?: 10L)
    startsWith("member_joined:") -> context.getString(R.string.listen_together_notice_member_joined, substringAfter(':'))
    startsWith("member_left:") -> context.getString(R.string.listen_together_notice_member_left, substringAfter(':'))
    this == "controller_reconnected" -> context.getString(R.string.listen_together_notice_controller_reconnected)
    this == "controller_timeout" || this == "room_closed" -> context.getString(R.string.listen_together_notice_room_closed)
    this == "controller_offline" -> context.getString(R.string.listen_together_notice_controller_offline, 10L)
    else -> this
}
