package moe.ouom.neriplayer.ui.screen.debug

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.ListenTogetherPreferences
import moe.ouom.neriplayer.listentogether.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.ListenTogetherMember
import moe.ouom.neriplayer.listentogether.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.ListenTogetherSessionManager
import moe.ouom.neriplayer.listentogether.ListenTogetherTrack
import moe.ouom.neriplayer.listentogether.buildListenTogetherInviteUri
import moe.ouom.neriplayer.listentogether.normalizeListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.validateListenTogetherNickname
import moe.ouom.neriplayer.listentogether.validateListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.validateListenTogetherUserUuid
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DebugField(
    val label: String,
    val value: String
)

@Composable
fun ListenTogetherDebugScreen() {
    val miniH = LocalMiniPlayerHeight.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = miniH),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.listen_together_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.listen_together_debug_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ListenTogetherRoomPanel(showBaseUrlEditor = true, showAdvancedDebug = true)
    }
}

@Composable
fun ListenTogetherRoomPanel(
    modifier: Modifier = Modifier,
    showBaseUrlEditor: Boolean = false,
    showAdvancedDebug: Boolean = false
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
    var showSessionDetails by rememberSaveable { mutableStateOf(false) }
    var showTrackPayload by rememberSaveable { mutableStateOf(false) }
    var showMemberDetails by rememberSaveable { mutableStateOf(false) }

    val isInRoom = !sessionState.roomId.isNullOrBlank()
    val role = resolveListenTogetherRole(sessionState.userUuid, sessionState.role, roomState)
    val isController = role == "controller"
    val effectiveBaseUrl = resolveListenTogetherBaseUrl(baseUrl)
    val tokenPreview = remember(sessionState.token) { sessionState.token.maskedTokenPreview() }
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

    LaunchedEffect(savedBaseUrl) {
        if (baseUrl.isBlank()) baseUrl = resolveListenTogetherBaseUrl(savedBaseUrl)
    }
    LaunchedEffect(savedUserUuid, isInRoom) {
        if (!isInRoom) {
            userUuid = savedUserUuid.ifBlank { preferences.getOrCreateUserUuid() }
        }
    }
    LaunchedEffect(savedNickname) {
        if (nickname.isBlank()) {
            nickname = savedNickname.ifBlank { preferences.getOrCreateNickname() }
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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    ) {
        if (showAdvancedDebug) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DebugHeader(
                    connectionState = sessionState.connectionState,
                    role = role,
                    roomStatus = roomState?.roomStatus,
                    roomVersion = roomState?.version,
                    roomId = sessionState.roomId
                )
                if (showBaseUrlEditor) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.listen_together_worker_base_url)) },
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.trim().take(24) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.listen_together_nickname)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = roomIdInput,
                        onValueChange = { roomIdInput = normalizeListenTogetherRoomId(it).take(6) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.listen_together_room_id)) },
                        singleLine = true,
                        readOnly = isInRoom
                    )
                }
                runningActionResId?.let { resId ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(resId), style = MaterialTheme.typography.bodySmall)
                    }
                }
                validateListenTogetherNickname(nickname)?.let { ErrorText(it) }
                if (!isInRoom) {
                    validateListenTogetherRoomId(roomIdInput)?.takeIf { roomIdInput.isNotBlank() }?.let { ErrorText(it) }
                }
                QuickActionSection(
                    activity = activity,
                    sessionState = sessionState,
                    effectiveBaseUrl = effectiveBaseUrl,
                    clipboard = clipboard,
                    onRunningActionChange = { runningActionResId = it }
                )
                if (!isInRoom) {
                    RoomActions(
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
                } else {
                    ConnectedActions(
                        runningActionResId = runningActionResId,
                        effectiveBaseUrl = effectiveBaseUrl,
                        nickname = nickname,
                        roomIdInput = roomIdInput,
                        sessionState = sessionState,
                        sessionManager = sessionManager,
                        preferences = preferences,
                        activity = activity,
                        onRunningActionChange = { runningActionResId = it }
                    )
                }
                if (isController) {
                    TextButton(
                        onClick = {
                            val roomId = sessionState.roomId ?: return@TextButton
                            val inviteText = buildString {
                                append(context.getString(R.string.listen_together_invite_share_text, sessionState.nickname ?: context.getString(R.string.listen_together_title), roomId))
                                inviteUri?.let {
                                    append("\n")
                                    append(it)
                                }
                            }
                            clipboard.setText(AnnotatedString(inviteText))
                            Toast.makeText(context, context.getString(R.string.listen_together_invite_copied), Toast.LENGTH_SHORT).show()
                        },
                        enabled = !sessionState.roomId.isNullOrBlank()
                    ) {
                        Text(stringResource(R.string.listen_together_copy_invite))
                    }
                }
                if (isController || !isInRoom) {
                    HorizontalDivider()
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
                                        check(result.ok) {
                                            result.error ?: context.getString(R.string.listen_together_debug_ws_unavailable)
                                        }
                                    }
                                }.onFailure {
                                    Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                                }
                            } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                HorizontalDivider()
                StatusSection(
                    sessionState = sessionState,
                    roomState = roomState,
                    role = role,
                    fallbackTrackName = currentSong?.name,
                    isPlaying = isPlaying,
                    effectiveBaseUrl = effectiveBaseUrl,
                    tokenPreview = tokenPreview,
                    expanded = showSessionDetails,
                    onToggleExpanded = { showSessionDetails = !showSessionDetails }
                )
                TrackDebugSection(
                    track = roomState?.track,
                    fallbackTrackName = currentSong?.name,
                    expanded = showTrackPayload,
                    onToggleExpanded = { showTrackPayload = !showTrackPayload }
                )
                MemberSection(
                    members = roomState?.members?.sortedBy { it.joinedAt }.orEmpty(),
                    expanded = showMemberDetails,
                    onToggleExpanded = { showMemberDetails = !showMemberDetails }
                )
            }
        } else {
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
                validateListenTogetherNickname(nickname)?.let { SimpleErrorText(it) }
                if (!isInRoom) {
                    validateListenTogetherRoomId(roomIdInput)?.takeIf { roomIdInput.isNotBlank() }?.let { SimpleErrorText(it) }
                }
                if (!isInRoom) {
                    RoomActions(
                        modifier = Modifier.padding(horizontal = 20.dp),
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
                }
                if (isInRoom) {
                    ConnectedActions(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        runningActionResId = runningActionResId,
                        effectiveBaseUrl = effectiveBaseUrl,
                        nickname = nickname,
                        roomIdInput = roomIdInput,
                        sessionState = sessionState,
                        sessionManager = sessionManager,
                        preferences = preferences,
                        activity = activity,
                        onRunningActionChange = { runningActionResId = it }
                    )
                }
                if (isController) {
                    TextButton(
                        onClick = {
                            val roomId = sessionState.roomId ?: return@TextButton
                            val inviteText = buildString {
                                append(context.getString(R.string.listen_together_invite_share_text, sessionState.nickname ?: context.getString(R.string.listen_together_title), roomId))
                                inviteUri?.let {
                                    append("\n")
                                    append(it)
                                }
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
                        modifier = Modifier.padding(horizontal = 20.dp),
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
                SimpleStatusSection(sessionState, roomState, role, currentSong?.name, isPlaying)
                SimpleMemberSection(roomState?.members?.sortedBy { it.joinedAt }.orEmpty())
            }
        }
    }
}

@Composable
private fun DebugHeader(
    connectionState: ListenTogetherConnectionState,
    role: String?,
    roomStatus: String?,
    roomVersion: Long?,
    roomId: String?
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DebugChip(stringResource(connectionState.labelResId()))
        DebugChip(stringResource(roleLabelResId(role)))
        DebugChip(stringResource(roomStatusLabelResId(roomStatus)))
        roomId?.takeIf { it.isNotBlank() }?.let { DebugChip("#$it") }
        DebugChip("v${roomVersion ?: -1}")
    }
}

@Composable
private fun QuickActionSection(
    activity: ComponentActivity?,
    sessionState: moe.ouom.neriplayer.listentogether.ListenTogetherSessionState,
    effectiveBaseUrl: String,
    clipboard: ClipboardManager,
    onRunningActionChange: (Int?) -> Unit
) {
    val context = LocalContext.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                activity?.lifecycleScope?.launch {
                    onRunningActionChange(R.string.settings_listen_together_server_testing)
                    runCatching {
                        val result = AppContainer.listenTogetherApi.testServerAvailability(effectiveBaseUrl)
                        val detail = if (result.ok) {
                            context.getString(R.string.listen_together_debug_probe_reachable)
                        } else {
                            result.message
                        }
                        Toast.makeText(context, context.getString(R.string.listen_together_debug_probe_result, detail), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                    }
                    onRunningActionChange(null)
                }
            }
        ) {
            Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
            Text(stringResource(R.string.listen_together_debug_probe))
        }
        OutlinedButton(
            onClick = {
                val sent = AppContainer.listenTogetherSessionManager.sendPing()
                Toast.makeText(context, context.getString(if (sent) R.string.listen_together_debug_ping_sent else R.string.listen_together_debug_ping_failed), Toast.LENGTH_SHORT).show()
            },
            enabled = sessionState.connectionState == ListenTogetherConnectionState.CONNECTED
        ) {
            Icon(Icons.Outlined.SettingsEthernet, contentDescription = null)
            Text(stringResource(R.string.listen_together_debug_ping))
        }
        OutlinedButton(
            onClick = { AppContainer.listenTogetherSessionManager.connectWebSocket() },
            enabled = !sessionState.wsUrl.isNullOrBlank()
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text(stringResource(R.string.listen_together_debug_reconnect_ws))
        }
        OutlinedButton(
            onClick = { AppContainer.listenTogetherSessionManager.disconnectWebSocket() },
            enabled = sessionState.connectionState != ListenTogetherConnectionState.DISCONNECTED
        ) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null)
            Text(stringResource(R.string.listen_together_debug_disconnect_ws))
        }
        OutlinedButton(
            onClick = {
                sessionState.wsUrl?.let {
                    clipboard.setText(AnnotatedString(it))
                    Toast.makeText(context, context.getString(R.string.listen_together_debug_ws_url_copied), Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !sessionState.wsUrl.isNullOrBlank()
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
            Text(stringResource(R.string.listen_together_debug_copy_ws_url))
        }
    }
}

@Composable
private fun RoomActions(
    modifier: Modifier = Modifier,
    runningActionResId: Int?,
    currentQueue: List<SongItem>,
    currentSong: SongItem?,
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = {
                activity?.lifecycleScope?.launch {
                    onRunningActionChange(R.string.listen_together_creating_room)
                    runCatching {
                        persistSettings(preferences, effectiveBaseUrl, userUuid, nickname, roomSettings)
                        sessionManager.createRoom(
                            effectiveBaseUrl,
                            userUuid,
                            nickname,
                            currentQueue,
                            currentQueue.indexOfFirst { it == currentSong }.takeIf { it >= 0 } ?: 0,
                            positionMs,
                            isPlaying,
                            roomSettings
                        )
                        sessionManager.connectWebSocket()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                    }
                    onRunningActionChange(null)
                } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
            },
            enabled = runningActionResId == null &&
                currentQueue.isNotEmpty() &&
                userUuidError == null &&
                nicknameError == null,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.listen_together_create_and_connect))
        }
        Button(
            onClick = {
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
                        sessionManager.joinRoom(effectiveBaseUrl, targetRoomId, userUuid, nickname)
                        sessionManager.connectWebSocket()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                    }
                    onRunningActionChange(null)
                } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
            },
            enabled = runningActionResId == null &&
                roomIdInput.isNotBlank() &&
                userUuidError == null &&
                nicknameError == null &&
                roomIdError == null,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null)
            Text(stringResource(R.string.listen_together_join_and_connect))
        }
    }
}

@Composable
private fun ConnectedActions(
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = {
                activity?.lifecycleScope?.launch {
                    val roomId = sessionState.roomId ?: roomIdInput
                    if (roomId.isBlank()) return@launch
                    onRunningActionChange(R.string.listen_together_refreshing_room_state)
                    runCatching {
                        preferences.setNickname(nickname)
                        sessionManager.refreshRoomState(effectiveBaseUrl, roomId)
                    }.onFailure {
                        Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                    }
                    onRunningActionChange(null)
                } ?: Toast.makeText(context, context.getString(R.string.listen_together_action_unavailable), Toast.LENGTH_SHORT).show()
            },
            enabled = runningActionResId == null,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text(stringResource(R.string.action_refresh))
        }
        Button(
            onClick = { sessionManager.leaveRoom() },
            enabled = sessionState.connectionState != ListenTogetherConnectionState.CONNECTING,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null)
            Text(stringResource(R.string.listen_together_leave_room))
        }
    }
}

private fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@Composable
private fun SettingsSection(
    modifier: Modifier = Modifier,
    settings: ListenTogetherRoomSettings,
    enabled: Boolean,
    onSettingsChange: (ListenTogetherRoomSettings) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.listen_together_settings_title), style = MaterialTheme.typography.titleSmall)
        SettingToggleRow(
            stringResource(R.string.listen_together_setting_member_control_title),
            stringResource(R.string.listen_together_setting_member_control_desc),
            settings.allowMemberControl,
            enabled
        ) { onSettingsChange(settings.copy(allowMemberControl = it)) }
        SettingToggleRow(
            stringResource(R.string.listen_together_setting_auto_pause_title),
            stringResource(R.string.listen_together_setting_auto_pause_desc),
            settings.autoPauseOnMemberChange,
            enabled
        ) { onSettingsChange(settings.copy(autoPauseOnMemberChange = it)) }
        SettingToggleRow(
            stringResource(R.string.listen_together_setting_share_audio_links_title),
            stringResource(R.string.listen_together_setting_share_audio_links_desc),
            settings.shareAudioLinks,
            enabled
        ) { onSettingsChange(settings.copy(shareAudioLinks = it)) }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
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
    roomState: ListenTogetherRoomState?,
    role: String?,
    fallbackTrackName: String?,
    isPlaying: Boolean,
    effectiveBaseUrl: String,
    tokenPreview: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val context = LocalContext.current
    val playbackState = if (resolveDisplayedPlaybackState(roomState, role, isPlaying) == "playing") {
        stringResource(R.string.listen_together_playback_playing)
    } else {
        stringResource(R.string.listen_together_playback_paused)
    }
    val summaryFields = listOf(
        DebugField(stringResource(R.string.listen_together_connection), stringResource(sessionState.connectionState.labelResId())),
        DebugField(stringResource(R.string.listen_together_role), stringResource(roleLabelResId(role))),
        DebugField(stringResource(R.string.listen_together_room_status), stringResource(roomStatusLabelResId(roomState?.roomStatus))),
        DebugField(stringResource(R.string.listen_together_room_id), sessionState.roomId ?: "-"),
        DebugField(stringResource(R.string.listen_together_version), roomState?.version?.toString() ?: "-"),
        DebugField(stringResource(R.string.listen_together_members), roomState?.members?.size?.toString() ?: "0"),
        DebugField(stringResource(R.string.listen_together_queue_size), roomState?.queue?.size?.toString() ?: "0"),
        DebugField(stringResource(R.string.listen_together_playback), playbackState)
    )
    val detailFields = buildList {
        add(DebugField(stringResource(R.string.listen_together_track), roomState?.track?.name ?: fallbackTrackName ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_base_url), effectiveBaseUrl))
        add(DebugField(stringResource(R.string.listen_together_debug_ws_url), sessionState.wsUrl ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_token), tokenPreview))
        add(DebugField(stringResource(R.string.listen_together_user_uuid), sessionState.userUuid ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_nickname), sessionState.nickname ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_schema), roomState?.schemaVersion?.toString() ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_expected_position), sessionState.expectedPositionMs?.let(::formatDurationDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_playback_base_position), roomState?.playback?.basePositionMs?.let(::formatDurationDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_playback_base_time), roomState?.playback?.baseTimestampMs?.let(::formatEpochDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_playback_rate), roomState?.playback?.playbackRate?.toString() ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_controller_uuid), roomState?.controllerUserUuid ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_controller_user_id), roomState?.controllerUserId ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_controller_heartbeat), roomState?.controllerHeartbeatAt?.let(::formatEpochDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_controller_offline_since), roomState?.controllerOfflineSince?.let(::formatEpochDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_updated_at), roomState?.updatedAt?.let(::formatEpochDebug) ?: "-"))
        add(DebugField(stringResource(R.string.listen_together_debug_closed_reason), roomState?.closedReason ?: "-"))
        sessionState.lastError?.takeIf { it.isNotBlank() }?.let { add(DebugField(stringResource(R.string.listen_together_last_error), it)) }
        sessionState.roomNotice?.takeIf { it.isNotBlank() }?.let { add(DebugField(stringResource(R.string.listen_together_debug_raw_notice), it)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DebugSectionHeader(
            title = stringResource(R.string.listen_together_debug_session_title),
            expanded = expanded,
            onToggleExpanded = onToggleExpanded
        )
        DebugFieldGrid(summaryFields)
        sessionState.lastError?.takeIf { it.isNotBlank() }?.let {
            DebugBanner(stringResource(R.string.listen_together_last_error), it, highlighted = true)
        }
        sessionState.roomNotice?.takeIf { it.isNotBlank() && !it.startsWith("member_joined:") && !it.startsWith("member_left:") }?.let {
            DebugBanner(stringResource(R.string.listen_together_notice), it.toDisplayNotice(context))
        }
        if (expanded) {
            DebugFieldGrid(detailFields)
        }
    }
}

@Composable
private fun TrackDebugSection(
    track: ListenTogetherTrack?,
    fallbackTrackName: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val summaryFields = listOf(
        DebugField(stringResource(R.string.listen_together_debug_track_name), track?.name ?: fallbackTrackName ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_channel), track?.channelId ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_duration), track?.durationMs?.let(::formatDurationDebug) ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_stable_key), track?.stableKey ?: "-")
    )
    val detailFields = listOf(
        DebugField(stringResource(R.string.listen_together_debug_audio_id), track?.audioId ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_sub_audio_id), track?.subAudioId ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_playlist_context), track?.playlistContextId ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_media_uri), track?.mediaUri ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_stream_url), track?.streamUrl ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_cover), track?.coverUrl ?: "-")
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DebugSectionHeader(
            title = stringResource(R.string.listen_together_debug_track_payload_title),
            expanded = expanded,
            onToggleExpanded = onToggleExpanded
        )
        DebugFieldGrid(summaryFields)
        if (expanded) {
            DebugFieldGrid(detailFields)
        }
    }
}

@Composable
private fun MemberSection(
    members: List<ListenTogetherMember>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    if (members.isEmpty()) return

    val roleCounts = members.groupingBy { it.role.ifBlank { "unknown" } }.eachCount()
    val summaryFields = buildList {
        add(DebugField(stringResource(R.string.listen_together_members), members.size.toString()))
        roleCounts.forEach { (role, count) ->
            val label = when (role) {
                "controller" -> stringResource(R.string.listen_together_role_controller)
                "listener" -> stringResource(R.string.listen_together_role_listener)
                else -> stringResource(R.string.listen_together_role_none)
            }
            add(DebugField(label, count.toString()))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DebugSectionHeader(
            title = stringResource(R.string.listen_together_member_list_title),
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            suffix = members.size.toString()
        )
        DebugFieldGrid(summaryFields)
        if (expanded) {
            members.forEach { MemberCard(it) }
        }
    }
}

@Composable
private fun MemberCard(member: ListenTogetherMember) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = member.nickname.ifBlank { member.userUuid.ifBlank { member.userId.orEmpty() } },
                style = MaterialTheme.typography.bodyMedium
            )
            DebugFieldGrid(
                listOf(
                    DebugField(stringResource(R.string.listen_together_debug_member_role), member.role.ifBlank { "-" }),
                    DebugField(stringResource(R.string.listen_together_debug_member_user_id), member.userId.orEmpty().ifBlank { "-" }),
                    DebugField(stringResource(R.string.listen_together_debug_controller_uuid), member.userUuid.ifBlank { "-" }),
                    DebugField(stringResource(R.string.listen_together_debug_member_joined), formatEpochDebug(member.joinedAt))
                )
            )
        }
    }
}

@Composable
private fun DebugFieldGrid(fields: List<DebugField>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { field ->
                    DebugFieldCard(field = field, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DebugFieldCard(
    field: DebugField,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = field.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DebugBanner(
    label: String,
    value: String,
    highlighted: Boolean = false
) {
    val bg = if (highlighted) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    val fg = if (highlighted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = fg)
    }
}

@Composable
private fun DebugChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        enabled = false,
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun SimpleErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
private fun SimpleStatusSection(
    sessionState: moe.ouom.neriplayer.listentogether.ListenTogetherSessionState,
    roomState: ListenTogetherRoomState?,
    role: String?,
    fallbackTrackName: String?,
    isPlaying: Boolean
) {
    val context = LocalContext.current
    val playbackState = if (resolveDisplayedPlaybackState(roomState, role, isPlaying) == "playing") {
        stringResource(R.string.listen_together_playback_playing)
    } else {
        stringResource(R.string.listen_together_playback_paused)
    }
    val summaryFields = listOf(
        DebugField(stringResource(R.string.listen_together_connection), stringResource(sessionState.connectionState.labelResId())),
        DebugField(stringResource(R.string.listen_together_role), stringResource(roleLabelResId(role))),
        DebugField(stringResource(R.string.listen_together_room_status), stringResource(roomStatusLabelResId(roomState?.roomStatus))),
        DebugField(stringResource(R.string.listen_together_room_id), sessionState.roomId ?: "-"),
        DebugField(stringResource(R.string.listen_together_version), roomState?.version?.toString() ?: "-"),
        DebugField(stringResource(R.string.listen_together_debug_updated_at), roomState?.updatedAt?.let(::formatRoomUpdatedAtSimple) ?: "-"),
        DebugField(stringResource(R.string.listen_together_members), roomState?.members?.size?.toString() ?: "0"),
        DebugField(stringResource(R.string.listen_together_queue_size), roomState?.queue?.size?.toString() ?: "0"),
        DebugField(stringResource(R.string.listen_together_track), roomState?.track?.name ?: fallbackTrackName ?: "-"),
        DebugField(stringResource(R.string.listen_together_playback), playbackState)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DebugFieldGrid(summaryFields)
        sessionState.lastError?.takeIf { it.isNotBlank() }?.let {
            DebugBanner(stringResource(R.string.listen_together_last_error), it, highlighted = true)
        }
        sessionState.roomNotice
            ?.takeIf { it.isNotBlank() && !it.startsWith("member_joined:") && !it.startsWith("member_left:") }
            ?.let { DebugBanner(stringResource(R.string.listen_together_notice), it.toDisplayNotice(context)) }
    }
}

private fun resolveDisplayedPlaybackState(
    roomState: ListenTogetherRoomState?,
    role: String?,
    isPlaying: Boolean
): String {
    if (role == "controller" && roomState != null) {
        return if (isPlaying) "playing" else "paused"
    }
    return roomState?.playback?.state ?: if (isPlaying) "playing" else "paused"
}

@Composable
private fun SimpleMemberSection(members: List<ListenTogetherMember>) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = member.nickname.ifBlank { member.userUuid.ifBlank { member.userId.orEmpty() } },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(roleLabelResId(member.role)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ListenTogetherDebugPanel(modifier: Modifier = Modifier) {
    ListenTogetherRoomPanel(modifier = modifier, showBaseUrlEditor = true, showAdvancedDebug = true)
}

@Composable
private fun DebugSectionHeader(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    suffix: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            suffix?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = onToggleExpanded) {
            Text(
                text = stringResource(
                    if (expanded) {
                        R.string.action_collapse
                    } else {
                        R.string.action_expand
                    }
                )
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null
            )
        }
    }
}

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

private fun resolveListenTogetherRole(
    userUuid: String?,
    fallbackRole: String?,
    roomState: ListenTogetherRoomState?
): String? {
    val sessionUserId = userUuid?.trim()?.takeIf { it.isNotBlank() }
    val controllerUserId = roomState?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
        ?: roomState?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
    return if (sessionUserId != null && controllerUserId != null) {
        if (sessionUserId == controllerUserId) "controller" else "listener"
    } else {
        fallbackRole
    }
}

private fun roomStatusLabelResId(status: String?): Int = when (status) {
    ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> R.string.listen_together_room_status_controller_offline
    ListenTogetherRoomStatuses.CLOSED -> R.string.listen_together_room_status_closed
    else -> R.string.listen_together_room_status_active
}

private fun String.toDisplayNotice(context: Context): String =
    when {
        startsWith("controller_offline:") -> context.getString(R.string.listen_together_notice_controller_offline, substringAfter(':').toLongOrNull() ?: 10L)
        startsWith("member_joined:") -> context.getString(R.string.listen_together_notice_member_joined, substringAfter(':'))
        startsWith("member_left:") -> context.getString(R.string.listen_together_notice_member_left, substringAfter(':'))
        this == "controller_reconnected" -> context.getString(R.string.listen_together_notice_controller_reconnected)
        this == "controller_timeout" || this == "room_closed" || contains("room closed", ignoreCase = true) ->
            context.getString(R.string.listen_together_notice_room_closed)
        contains("unauthorized", ignoreCase = true) || contains("http=401", ignoreCase = true) || contains("(401)", ignoreCase = true) ->
            context.getString(R.string.listen_together_error_unauthorized)
        contains("room not initialized", ignoreCase = true) || contains("not found in do", ignoreCase = true) ->
            context.getString(R.string.listen_together_error_room_not_found)
        contains("controller offline", ignoreCase = true) ->
            context.getString(R.string.listen_together_error_controller_offline)
        contains("member control disabled", ignoreCase = true) ->
            context.getString(R.string.listen_together_error_member_control_disabled)
        else -> this
    }

private fun String?.maskedTokenPreview(): String {
    if (this.isNullOrBlank()) return "-"
    if (length <= 10) return this
    return "${take(6)}...${takeLast(4)}"
}

private fun formatEpochDebug(value: Long): String {
    return runCatching {
        "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))} ($value)"
    }.getOrDefault(value.toString())
}

private fun formatRoomUpdatedAtSimple(value: Long): String {
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
    }.getOrDefault(value.toString())
}

private fun formatDurationDebug(value: Long): String {
    val totalSeconds = value / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d (%d ms)".format(minutes, seconds, value)
}
