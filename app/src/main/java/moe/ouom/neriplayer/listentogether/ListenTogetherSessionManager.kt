package moe.ouom.neriplayer.listentogether

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.policy.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.AudioPlayerService
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.resolveShareableListenTogetherStreamUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private data class PendingTrackFinishedLegacyFallback(
    val event: ListenTogetherEvent,
    val createdAtElapsedMs: Long,
    val attempted: Boolean = false
)

private data class PendingMemberControlRequest(
    val event: ListenTogetherEvent,
    val createdAtElapsedMs: Long,
    val lastSentAtElapsedMs: Long,
    val attempts: Int
)

private data class AcceptedRoomState(
    val state: ListenTogetherRoomState,
    val expectedPositionMs: Long?
)

private enum class RoomStateSource(val logName: String) {
    HTTP_REFRESH("http_refresh"),
    HTTP_CONTROL_FALLBACK("http_control_fallback"),
    HTTP_SESSION_UPDATE("http_session_update"),
    WEB_SOCKET_STATE("websocket_state"),
    WEB_SOCKET_CONTROL_RESULT("websocket_control_result"),
    WEB_SOCKET_ROOM_STATUS("websocket_room_status"),
    WEB_SOCKET_ROOM_CLOSED("websocket_room_closed"),
    LOCAL_SYNTHETIC("local_synthetic")
}

internal const val LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE = 2_000

class ListenTogetherSessionManager(
    private val api: ListenTogetherApi,
    private val webSocketClient: ListenTogetherWebSocketClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var membershipRecoveryJob: Job? = null
    private var syncWatchdogJob: Job? = null

    @Volatile
    private var started = false

    private val recentOutboundEventIds = LinkedHashSet<String>()
    private val recentInboundEventIds = LinkedHashSet<String>()
    private val recentEventLock = Any()
    @Volatile
    private var lastOutboundSyncAtMs: Long = 0L
    @Volatile
    private var lastRequestedLinkStableKey: String? = null
    @Volatile
    private var lastRequestedLinkAtElapsedMs: Long = 0L
    @Volatile
    private var lastAppliedRoomVersion: Long = -1L
    @Volatile
    private var lastControllerLocalControlAtElapsedMs: Long = 0L
    @Volatile
    private var lastControllerWatchdogHeartbeatAtElapsedMs: Long = 0L
    @Volatile
    private var reconnectEnabled = false
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var lastHandledForwardedRequestSequence: Long = 0L
    @Volatile
    private var pendingStateRefreshAfterReconnect = false
    @Volatile
    private var awaitingTrackFinishStableKey: String? = null
    @Volatile
    private var pendingTrackFinishedLegacyFallback: PendingTrackFinishedLegacyFallback? = null
    @Volatile
    private var pendingMemberControlRequest: PendingMemberControlRequest? = null
    @Volatile
    private var lastListenerStateRefreshAtElapsedMs: Long = 0L
    @Volatile
    private var listenerBufferingStartedAtElapsedMs: Long = 0L
    @Volatile
    private var listenerBufferingTrackStableKey: String? = null
    @Volatile
    private var lastListenerBufferingRecoveryAtElapsedMs: Long = 0L
    @Volatile
    private var controllerLinkResolveStableKey: String? = null
    @Volatile
    private var controllerLinkResolveJob: Job? = null
    @Volatile
    private var pingSentAtMs: Long = 0L
    @Volatile
    private var estimatedServerClockOffsetMs: Long = 0L

    private val _sessionState = MutableStateFlow(ListenTogetherSessionState())
    val sessionState: StateFlow<ListenTogetherSessionState> = _sessionState.asStateFlow()

    private val _roomState = MutableStateFlow<ListenTogetherRoomState?>(null)
    val roomState: StateFlow<ListenTogetherRoomState?> = _roomState.asStateFlow()

    init {
        start()
    }

    fun start() {
        if (started) return
        started = true
        NPLogger.d(TAG, "start(): subscribe playbackCommandFlow")
        scope.launch {
            PlayerManager.playbackCommandFlow.collectLatest(::handleLocalPlaybackCommand)
        }
        scope.launch {
            PlayerManager.currentMediaUrlFlow.collectLatest(::handleResolvedStreamUrlChanged)
        }
    }

    suspend fun createRoom(
        baseUrl: String,
        userUuid: String,
        nickname: String,
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        isPlaying: Boolean,
        roomSettings: ListenTogetherRoomSettings = ListenTogetherRoomSettings()
    ): ListenTogetherRoomResponse {
        val validatedUserUuid = requireValidListenTogetherUserUuid(userUuid)
        val validatedNickname = requireValidListenTogetherNickname(nickname)
        val (queueTracks, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomSettings
        )
        NPLogger.d(
            TAG,
            "createRoom(): baseUrl=$baseUrl, userUuid=$validatedUserUuid, nickname=$validatedNickname, queueSize=${queue.size}, shareableQueueSize=${queueTracks.size}, currentIndex=$currentIndex, resolvedCurrentIndex=$resolvedCurrentIndex, isPlaying=$isPlaying, positionMs=$positionMs"
        )
        val response = api.createRoom(
            baseUrl = baseUrl,
            userUuid = validatedUserUuid,
            nickname = validatedNickname,
            initialSnapshot = ListenTogetherInitialSnapshot(
                queue = queueTracks,
                currentIndex = resolvedCurrentIndex,
                track = queueTracks.getOrNull(resolvedCurrentIndex),
                settings = roomSettings.normalized(),
                isPlaying = isPlaying,
                positionMs = positionMs.coerceAtLeast(0L),
                repeatMode = PlayerManager.repeatModeFlow.value,
                shuffleEnabled = PlayerManager.shuffleModeFlow.value
            )
        )
        updateSession(baseUrl, response)
        NPLogger.d(
            TAG,
            "createRoom(): ok=${response.ok}, roomId=${response.roomId}, role=${response.role}, wsUrl=${response.wsUrl.redactListenTogetherWsUrlForLog()}"
        )
        return response
    }

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        userUuid: String,
        nickname: String
    ): ListenTogetherRoomResponse {
        val validatedRoomId = requireValidListenTogetherRoomId(roomId)
        val validatedUserUuid = requireValidListenTogetherUserUuid(userUuid)
        val validatedNickname = requireValidListenTogetherNickname(nickname)
        NPLogger.d(TAG, "joinRoom(): baseUrl=$baseUrl, roomId=$validatedRoomId, userUuid=$validatedUserUuid, nickname=$validatedNickname")
        val response = api.joinRoom(baseUrl, validatedRoomId, validatedUserUuid, validatedNickname)
        updateSession(baseUrl, response)
        NPLogger.d(
            TAG,
            "joinRoom(): ok=${response.ok}, roomId=${response.roomId}, role=${response.role}, wsUrl=${response.wsUrl.redactListenTogetherWsUrlForLog()}"
        )
        return response
    }

    suspend fun refreshRoomState(baseUrl: String, roomId: String): ListenTogetherStateResponse {
        val validatedRoomId = requireValidListenTogetherRoomId(roomId)
        NPLogger.d(TAG, "refreshRoomState(): baseUrl=$baseUrl, roomId=$validatedRoomId")
        val response = api.getRoomState(baseUrl, validatedRoomId)
        response.state?.let {
            val accepted = acceptRoomState(
                state = it,
                expectedPositionMs = response.expectedPositionMs,
                source = RoomStateSource.HTTP_REFRESH
            )
            if (accepted != null && !isCurrentUserController()) {
                applyRoomStateToPlayer(
                    accepted.state,
                    causeType = null,
                    expectedPositionMs = accepted.expectedPositionMs
                )
                maybeRequestControllerLink(accepted.state, "refresh_room_state")
            }
        }
        NPLogger.d(
            TAG,
            "refreshRoomState(): ok=${response.ok}, version=${response.state?.version}, expectedPositionMs=${response.expectedPositionMs}"
        )
        return response
    }

    fun connectWebSocket() {
        reconnectEnabled = true
        reconnectJob?.cancel()
        reconnectJob = null
        val wsUrl = _sessionState.value.wsUrl ?: return
        ensureListenTogetherForegroundService("connect_websocket")
        NPLogger.d(TAG, "connectWebSocket(): wsUrl=${wsUrl.redactListenTogetherWsUrlForLog()}")
        _sessionState.value = _sessionState.value.copy(
            connectionState = ListenTogetherConnectionState.CONNECTING,
            lastError = null
        )
        webSocketClient.connect(
            wsUrl = wsUrl,
            listener = object : ListenTogetherWebSocketClient.Listener {
                override fun onOpen() {
                    NPLogger.d(TAG, "websocket.onOpen()")
                    val shouldRefreshState = pendingStateRefreshAfterReconnect
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                    startHeartbeat()
                    startSyncWatchdog()
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.CONNECTED,
                        lastError = null
                    )
                    pendingStateRefreshAfterReconnect = false
                    if (shouldRefreshState) {
                        scope.launch {
                            refreshRoomStateAfterReconnect("socket_open")
                        }
                    }
                    _roomState.value?.let { currentState ->
                        maybeRequestControllerLink(currentState, "socket_open")
                    }
                    publishControllerHeartbeatIfNeeded(force = true, reason = "socket_open")
                }

                override fun onMessage(message: ListenTogetherSocketEnvelope) {
                    NPLogger.d(
                        TAG,
                        "websocket.onMessage(): type=${message.type}, roomId=${message.roomId ?: message.state?.roomId}, version=${message.version ?: message.state?.version}, causedBy=${message.causedBy?.type}:${message.causedBy?.eventId}, ok=${message.ok}, message=${message.message}, resultError=${message.result?.error}"
                    )
                    when (message.type) {
                        "welcome",
                        "room_state_updated" -> handleSocketRoomState(message)
                        "link_requested" -> handleLinkRequested(message)
                        "member_control_requested" -> handleMemberControlRequested(message)
                        "room_suspended" -> handleRoomSuspended(message)
                        "room_resumed" -> handleRoomResumed(message)
                        "room_closed" -> handleRoomClosed(message)
                        "control_result",
                        "ack" -> {
                            val error = message.result?.error
                            val applied = message.result?.applied
                            val appliedCause = applied?.causedBy
                            val appliedCauseType = appliedCause?.type
                            if (error.isNullOrBlank() && message.ok != false && appliedCauseType == "TRACK_FINISHED") {
                                pendingTrackFinishedLegacyFallback = null
                            }
                            if (
                                error.isNullOrBlank() &&
                                applied?.state != null &&
                                (
                                            appliedCauseType == "UPDATE_SETTINGS" ||
                                        (
                                            appliedCauseType == "TRACK_FINISHED" &&
                                                appliedCause?.userUuid == _sessionState.value.userUuid
                                            ) ||
                                        (
                                            appliedCauseType?.startsWith("REQUEST_") == true &&
                                                appliedCause?.userUuid == _sessionState.value.userUuid
                                            )
                                    )
                            ) {
                                NPLogger.d(
                                    TAG,
                                    "websocket.controlResult(): apply committed state locally, type=${applied.causedBy?.type}, version=${applied.version}"
                                )
                                val accepted = acceptRoomState(
                                    state = applied.state,
                                    expectedPositionMs = applied.expectedPositionMs,
                                    source = RoomStateSource.WEB_SOCKET_CONTROL_RESULT,
                                    cause = applied.causedBy
                                )
                                if (accepted != null && applied.causedBy?.type == "TRACK_FINISHED") {
                                    awaitingTrackFinishStableKey = null
                                }
                                if (accepted != null && !isCurrentUserController()) {
                                    applyRoomStateToPlayer(
                                        accepted.state,
                                        applied.causedBy?.type,
                                        accepted.expectedPositionMs
                                    )
                                }
                            }
                            if (!error.isNullOrBlank() || message.ok == false) {
                                val resolvedError = error
                                    ?: message.message
                                    ?: "control event rejected"
                                if (trySendTrackFinishedLegacyFallback(resolvedError)) {
                                    return
                                }
                                NPLogger.w(TAG, "websocket.controlResult(): $resolvedError")
                                _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
                                if (handleTerminalReconnectFailure(resolvedError, "control_result")) {
                                    return
                                }
                                maybeRecoverFromFatalMembershipError(
                                    errorMessage = resolvedError,
                                    reason = "control_result"
                                )
                            }
                        }

                        "error" -> {
                            val resolvedError = message.message ?: "socket error"
                            NPLogger.w(TAG, "websocket.error(): $resolvedError")
                            _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
                            if (handleTerminalReconnectFailure(resolvedError, "socket_error")) {
                                return
                            }
                            maybeRecoverFromFatalMembershipError(
                                errorMessage = resolvedError,
                                reason = "socket_error"
                            )
                        }

                        "pong" -> {
                            _sessionState.value = _sessionState.value.copy(lastError = null)
                            val serverNowMs = message.nowMs
                            val sentAt = pingSentAtMs
                            if (serverNowMs != null && serverNowMs > 0L && sentAt > 0L) {
                                val pongReceivedAt = System.currentTimeMillis()
                                val rtt = (pongReceivedAt - sentAt).coerceAtLeast(0L)
                                val newOffset = serverNowMs - (sentAt + rtt / 2)
                                // EWMA α=0.3 平滑时钟偏差估计
                                val prev = estimatedServerClockOffsetMs
                                estimatedServerClockOffsetMs = if (prev == 0L) {
                                    newOffset
                                } else {
                                    ((prev * 7 + newOffset * 3) / 10)
                                }
                            }
                        }
                    }
                }

                override fun onClosed(code: Int, reason: String) {
                    stopHeartbeat()
                    NPLogger.w(TAG, "websocket.onClosed(): code=$code, reason=$reason")
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.DISCONNECTED,
                        lastError = reason.takeIf { it.isNotBlank() }
                    )
                    if (handleTerminalReconnectFailure(reason, "socket_closed:$code")) {
                        return
                    }
                    scheduleReconnect("closed:$code:${reason.ifBlank { "unknown" }}")
                }

                override fun onFailure(error: Throwable) {
                    stopHeartbeat()
                    NPLogger.e(TAG, "websocket.onFailure(): ${error.message}", error)
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.DISCONNECTED,
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                    if (handleTerminalReconnectFailure(error.message, "socket_failure")) {
                        return
                    }
                    scheduleReconnect("failure:${error.message ?: error.javaClass.simpleName}")
                }

                override fun onProtocolError(rawText: String, error: Throwable) {
                    NPLogger.w(
                        "NERI-ListenTogether",
                        "WebSocket protocol decode failed: ${error.message}, raw=${rawText.take(512)}"
                    )
                    _sessionState.value = _sessionState.value.copy(
                        lastError = "Protocol: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        )
    }

    fun disconnectWebSocket() {
        reconnectEnabled = false
        reconnectAttempt = 0
        pendingStateRefreshAfterReconnect = false
        cancelListenTogetherBackgroundJobs(reconnectJob, membershipRecoveryJob)
        reconnectJob = null
        membershipRecoveryJob = null
        cancelControllerLinkResolve()
        stopHeartbeat()
        stopSyncWatchdog()
        lastOutboundSyncAtMs = 0L
        lastRequestedLinkStableKey = null
        lastRequestedLinkAtElapsedMs = 0L
        lastAppliedRoomVersion = -1L
        lastControllerLocalControlAtElapsedMs = 0L
        lastControllerWatchdogHeartbeatAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        awaitingTrackFinishStableKey = null
        pendingTrackFinishedLegacyFallback = null
        pendingMemberControlRequest = null
        pingSentAtMs = 0L
        estimatedServerClockOffsetMs = 0L
        resetListenerRecoveryState()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "disconnectWebSocket()")
        webSocketClient.disconnect()
        _sessionState.value = _sessionState.value.copy(
            connectionState = ListenTogetherConnectionState.DISCONNECTED,
            roomNotice = null
        )
    }

    fun leaveRoom() {
        reconnectEnabled = false
        reconnectAttempt = 0
        pendingStateRefreshAfterReconnect = false
        cancelListenTogetherBackgroundJobs(reconnectJob, membershipRecoveryJob)
        reconnectJob = null
        membershipRecoveryJob = null
        cancelControllerLinkResolve()
        stopHeartbeat()
        stopSyncWatchdog()
        lastOutboundSyncAtMs = 0L
        lastRequestedLinkStableKey = null
        lastRequestedLinkAtElapsedMs = 0L
        lastAppliedRoomVersion = -1L
        lastControllerLocalControlAtElapsedMs = 0L
        lastControllerWatchdogHeartbeatAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        awaitingTrackFinishStableKey = null
        pendingTrackFinishedLegacyFallback = null
        pendingMemberControlRequest = null
        resetListenerRecoveryState()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "leaveRoom(): roomId=${_sessionState.value.roomId}, role=${_sessionState.value.role}")
        webSocketClient.disconnect()
        synchronized(recentEventLock) {
            recentOutboundEventIds.clear()
            recentInboundEventIds.clear()
        }
        val snapshot = _sessionState.value
        _roomState.value = null
        _sessionState.value = ListenTogetherSessionState(
            baseUrl = snapshot.baseUrl,
            userUuid = snapshot.userUuid,
            nickname = snapshot.nickname,
            connectionState = ListenTogetherConnectionState.DISCONNECTED
        )
    }

    fun sendPing(): Boolean {
        pingSentAtMs = System.currentTimeMillis()
        return webSocketClient.sendPing()
    }

    suspend fun sendControlEvent(event: ListenTogetherEvent): ListenTogetherControlResponse {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl ?: error("baseUrl missing")
        val roomId = snapshot.roomId ?: error("roomId missing")
        val token = snapshot.token ?: error("token missing")
        return api.sendControlEvent(baseUrl, roomId, token, event)
    }

    fun sendControlEventOverWebSocket(event: ListenTogetherEvent): Boolean {
        return webSocketClient.sendEvent(event)
    }

    fun updateRoomSettings(settings: ListenTogetherRoomSettings): ListenTogetherControlResponse {
        val event = ListenTogetherEvent(
            type = "UPDATE_SETTINGS",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            roomSettings = settings.normalized()
        )
        markOutboundEvent(event.eventId)
        noteOutboundSync()
        return if (sendControlEventPureWebSocket(event, "update_settings")) {
            ListenTogetherControlResponse(ok = true)
        } else {
            ListenTogetherControlResponse(ok = false, error = "websocket unavailable")
        }
    }

    fun applyRoomStateToPlayer(
        state: ListenTogetherRoomState,
        causeType: String? = null,
        expectedPositionMs: Long? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            NPLogger.d(
                TAG,
                "applyRoomStateToPlayer(): repost to main thread, roomId=${state.roomId}, version=${state.version}, causeType=$causeType"
            )
            mainScope.launch {
                applyRoomStateToPlayer(state, causeType, expectedPositionMs)
            }
            return
        }
        val latestRoomVersion = _roomState.value?.version ?: state.version
        if (state.version < latestRoomVersion) {
            NPLogger.d(
                TAG,
                "applyRoomStateToPlayer(): skip stale state, roomId=${state.roomId}, version=${state.version}, latest=$latestRoomVersion, causeType=$causeType"
            )
            return
        }
        val queue = when {
            state.queue.isNotEmpty() -> state.queue
                .mergeCurrentTrack(state.currentIndex, state.track)
                .map { it.toSongItem() }
            state.track != null -> listOf(state.track.toSongItem())
            else -> emptyList()
        }
        if (queue.isEmpty()) {
            NPLogger.w(
                TAG,
                "applyRoomStateToPlayer(): skip empty queue, roomId=${state.roomId}, version=${state.version}, causeType=$causeType"
            )
            return
        }
        NPLogger.d(
            TAG,
            "applyRoomStateToPlayer(): roomId=${state.roomId}, version=${state.version}, queueSize=${queue.size}, currentIndex=${state.currentIndex}, playback=${state.playback.state}"
        )

        val targetIndex = state.currentIndex.coerceIn(0, queue.lastIndex)
        val targetSong = queue[targetIndex]
        val currentQueue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val localCurrentIndex = currentQueue.indexOfTrack(currentSong)
        val needsAuthoritativeStreamReload = shouldReloadForAuthoritativeStreamUrl(
            targetSong = targetSong,
            currentSong = currentSong
        )
        val forcePlaybackStallReload =
            causeType == "WATCHDOG_STALL" &&
                state.playback.state == "playing" &&
                currentSong?.sameTrackAs(targetSong) == true
        val playbackContextChanged =
            !currentQueue.hasSameTrackSequenceAs(queue) ||
                needsAuthoritativeStreamReload ||
                forcePlaybackStallReload
        val targetIndexChanged = localCurrentIndex != targetIndex

        if (playbackContextChanged || targetIndexChanged) {
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            PlayerManager.playPlaylist(queue, targetIndex, commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
        PlayerManager.applyListenTogetherPlaybackMode(
            repeatMode = state.playback.repeatMode,
            shuffleEnabled = state.playback.shuffleEnabled
        )

        val resolvedExpectedPositionMs = expectedPositionMs
            ?: state.playback.expectedPositionMs(serverClockOffsetMs = estimatedServerClockOffsetMs)
        val localPositionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        val desiredPlaying = state.playback.state == "playing"
        val localPlaying = PlayerManager.isPlayingFlow.value
        val localPlaybackAlreadyStarting = PlayerManager.playWhenReadyFlow.value
        val awaitingAuthoritativeStream = shouldWaitForListenTogetherAuthoritativeStreamPlayback(
            playerWaitingForAuthoritativeStream = PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(targetSong),
            localTrackMatchesTarget = currentSong?.sameTrackAs(targetSong) == true,
            localTrackStreamUrl = currentSong?.streamUrl,
            localResolvedStreamUrl = PlayerManager.currentMediaUrlFlow.value
        )
        val ignoreUnexpectedZeroPositionRollback = shouldIgnoreUnexpectedZeroPositionRollback(
            causeType = causeType,
            desiredPlaying = desiredPlaying,
            expectedPositionMs = resolvedExpectedPositionMs,
            localPositionMs = localPositionMs,
            playbackContextChanged = playbackContextChanged,
            targetIndexChanged = targetIndexChanged
        )
        val syncPlan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = playbackContextChanged,
                targetIndexChanged = targetIndexChanged,
                desiredPlaying = desiredPlaying,
                localPlaying = localPlaying,
                localPlaybackAlreadyStarting = localPlaybackAlreadyStarting,
                awaitingAuthoritativeStream = awaitingAuthoritativeStream,
                expectedPositionMs = resolvedExpectedPositionMs,
                localPositionMs = localPositionMs,
                ignoreUnexpectedZeroPositionRollback = ignoreUnexpectedZeroPositionRollback,
                causeType = causeType,
                trackSwitchForceSyncMs = TRACK_SWITCH_FORCE_SYNC_MS,
                heartbeatDriftForceSyncMs = HEARTBEAT_DRIFT_FORCE_SYNC_MS,
                playingDriftForceSyncMs = PLAYING_DRIFT_FORCE_SYNC_MS,
                pausedDriftForceSyncMs = PAUSED_DRIFT_FORCE_SYNC_MS
            )
        )
        NPLogger.d(
            TAG,
            "applyRoomStateToPlayer(): causeType=$causeType, desiredPlaying=$desiredPlaying, localPlaying=$localPlaying, localPlaybackAlreadyStarting=$localPlaybackAlreadyStarting, awaitingAuthoritativeStream=$awaitingAuthoritativeStream, localCurrentIndex=$localCurrentIndex, targetIndex=$targetIndex, playbackContextChanged=$playbackContextChanged, targetIndexChanged=$targetIndexChanged, shouldReloadPlaylist=${syncPlan.shouldReloadPlaylist}, effectiveExpectedPositionMs=${syncPlan.effectiveExpectedPositionMs}, driftMs=${syncPlan.driftMs}, signedDriftMs=${syncPlan.signedDriftMs}, shouldSeek=${syncPlan.shouldSeek}, shouldIssuePlay=${syncPlan.shouldIssuePlay}, needsAuthoritativeStreamReload=$needsAuthoritativeStreamReload, forcePlaybackStallReload=$forcePlaybackStallReload, ignoreUnexpectedZeroPositionRollback=$ignoreUnexpectedZeroPositionRollback, shouldForcePauseAfterRemoteLoad=${syncPlan.shouldForcePauseAfterRemoteLoad}"
        )
        when {
            desiredPlaying -> {
                if (syncPlan.shouldSeek) {
                    PlayerManager.resetListenTogetherSyncPlaybackRate()
                    PlayerManager.seekTo(syncPlan.effectiveExpectedPositionMs, commandSource = PlaybackCommandSource.REMOTE_SYNC)
                } else {
                    applySoftDriftCorrection(
                        driftMs = syncPlan.driftMs,
                        signedDriftMs = syncPlan.signedDriftMs,
                        allowSoftSync = true
                    )
                }
                if (syncPlan.shouldIssuePlay) {
                    PlayerManager.resetListenTogetherSyncPlaybackRate()
                    PlayerManager.play(commandSource = PlaybackCommandSource.REMOTE_SYNC)
                }
            }

            else -> {
                PlayerManager.resetListenTogetherSyncPlaybackRate()
                if (syncPlan.shouldSeek) {
                    PlayerManager.seekTo(syncPlan.effectiveExpectedPositionMs, commandSource = PlaybackCommandSource.REMOTE_SYNC)
                }
                if (syncPlan.shouldForcePauseAfterRemoteLoad || localPlaying) {
                    PlayerManager.pause(forcePersist = false, commandSource = PlaybackCommandSource.REMOTE_SYNC)
                }
            }
        }
    }

    fun buildSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = _roomState.value?.settings,
            includeResolvedStreamUrl = isCurrentUserController()
        )
        return ListenTogetherEvent(
            type = "SET_TRACK",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableQueue.getOrNull(resolvedCurrentIndex),
            queue = shareableQueue,
            shouldPlay = shouldPlay
        )
    }

    fun buildPlayEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("PLAY", positionMs)

    fun buildPauseEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("PAUSE", positionMs)

    fun buildSeekEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("SEEK", positionMs)

    fun buildRequestPlayEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("REQUEST_PLAY", positionMs)

    fun buildRequestPauseEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("REQUEST_PAUSE", positionMs)

    fun buildRequestSeekEvent(positionMs: Long): ListenTogetherEvent = playbackSnapshotEvent("REQUEST_SEEK", positionMs)

    fun buildPlaybackModeEvent(
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): ListenTogetherEvent {
        return playbackSnapshotEvent(
            type = if (isCurrentUserController()) "PLAYBACK_MODE" else "REQUEST_PLAYBACK_MODE",
            positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        ).copy(
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
    }

    fun buildHeartbeatEvent(
        state: String,
        positionMs: Long,
        includeQueue: Boolean = true
    ): ListenTogetherEvent {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = _roomState.value?.settings,
            includeResolvedStreamUrl = isCurrentUserController()
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        return ListenTogetherEvent(
            type = "HEARTBEAT",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue.takeIf { includeQueue },
            state = state,
            positionMs = positionMs.coerceAtLeast(0L)
        )
    }

    fun buildRequestLinkEvent(
        stableKey: String,
        currentIndex: Int? = null,
        track: ListenTogetherTrack? = null
    ): ListenTogetherEvent {
        return ListenTogetherEvent(
            type = "REQUEST_LINK",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            currentIndex = currentIndex,
            track = track,
            requestTrackStableKey = stableKey
        )
    }

    fun buildLinkReadyEvent(
        stableKey: String,
        positionMs: Long,
        streamUrlOverride: String? = null
    ): ListenTogetherEvent? {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value ?: run {
            NPLogger.w(TAG, "buildLinkReadyEvent(): currentSong missing, stableKey=$stableKey")
            return null
        }
        val rawIndex = queue.indexOfFirst { song -> song.sameTrackAs(currentSong) }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = _roomState.value?.settings,
            includeResolvedStreamUrl = true
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex) ?: run {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): shareableTrack missing, stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}"
            )
            return null
        }
        if (shareableTrack.stableKey != stableKey) {
            NPLogger.d(
                TAG,
                "buildLinkReadyEvent(): stableKey mismatch, expected=$stableKey, actual=${shareableTrack.stableKey}, resolvedCurrentIndex=$resolvedCurrentIndex"
            )
            return null
        }
        val resolvedStreamUrl = normalizedDirectStreamUrl(streamUrlOverride)
            ?: normalizedDirectStreamUrl(shareableTrack.streamUrl)
            ?: run {
                NPLogger.w(
                    TAG,
                    "buildLinkReadyEvent(): direct stream url missing, stableKey=$stableKey, track=${shareableTrack.name}"
                )
                return null
            }
        val trustedTrack = shareableTrack.withStreamUrl(resolvedStreamUrl)
        val trustedStreamUrl = normalizedDirectStreamUrl(trustedTrack.streamUrl) ?: run {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): rejected untrusted stream url, stableKey=$stableKey, track=${shareableTrack.name}, url=${resolvedStreamUrl.take(128)}"
            )
            return null
        }
        NPLogger.d(
            TAG,
            "buildLinkReadyEvent(): stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}, positionMs=$positionMs"
        )
        return ListenTogetherEvent(
            type = "LINK_READY",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            currentIndex = resolvedCurrentIndex,
            track = trustedTrack,
            queue = shareableQueue.mergeCurrentTrack(
                currentIndex = resolvedCurrentIndex,
                currentTrack = trustedTrack.withStreamUrl(trustedStreamUrl)
            ),
            state = resolveListenTogetherLinkReadyState(
                roomPlaybackState = _roomState.value?.playback?.state,
                localTransportActive = isLocalPlaybackTransportActive(),
                localPlaying = PlayerManager.isPlayingFlow.value
            ),
            positionMs = positionMs.coerceAtLeast(0L),
            requestTrackStableKey = stableKey
        )
    }

    fun buildRequestSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        return buildSetTrackEvent(
            queue = queue,
            currentIndex = currentIndex,
            positionMs = positionMs,
            shouldPlay = shouldPlay
        ).copy(type = "REQUEST_SET_TRACK")
    }

    private fun playbackSnapshotEvent(type: String, positionMs: Long): ListenTogetherEvent {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = _roomState.value?.settings,
            includeResolvedStreamUrl = isCurrentUserController()
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        val resolvedState = when (type.removePrefix("REQUEST_")) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> currentLocalPlaybackStateName()
        }
        return ListenTogetherEvent(
            type = type,
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue,
            shouldPlay = resolvedState == "playing",
            state = resolvedState,
            repeatMode = PlayerManager.repeatModeFlow.value,
            shuffleEnabled = PlayerManager.shuffleModeFlow.value
        )
    }

    private fun buildTrackFinishedEvent(
        command: PlaybackCommand,
        queue: List<SongItem>,
        currentSong: SongItem?,
        positionMs: Long
    ): ListenTogetherEvent? {
        if (queue.isEmpty() || currentSong == null) return null
        val finishedTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        val proposedNextIndex = command.currentIndex?.coerceIn(0, queue.lastIndex)
            ?: queue.indexOfTrack(currentSong).takeIf { it >= 0 }
            ?: 0
        val isController = isCurrentUserController()
        val (shareableQueue, resolvedNextIndex) = queue.toShareableQueueSnapshot(
            currentIndex = proposedNextIndex,
            roomSettings = _roomState.value?.settings,
            includeResolvedStreamUrl = false
        )
        val shouldAdvance = command.shouldPlay == true
        return ListenTogetherEvent(
            type = "TRACK_FINISHED",
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = if (isController) resolvedNextIndex else null,
            nextIndex = if (isController) resolvedNextIndex else null,
            track = if (isController && shouldAdvance) shareableQueue.getOrNull(resolvedNextIndex) else null,
            queue = if (isController) shareableQueue else null,
            shouldPlay = if (isController) shouldAdvance else null,
            finishedTrackStableKey = finishedTrack.stableKey
        )
    }

    private fun updateSession(baseUrl: String, response: ListenTogetherRoomResponse) {
        val normalizedBaseUrl = resolveListenTogetherBaseUrl(baseUrl)
        val resolvedWsUrl = response.wsUrl
            ?.takeUnless { wsUrl ->
                wsUrl.contains("://room.internal/", ignoreCase = true) ||
                    wsUrl.contains("://room.internal?", ignoreCase = true) ||
                    wsUrl.contains("://room.internal:", ignoreCase = true)
            }
            ?: run {
                val roomId = response.roomId
                val token = response.token
                if (!roomId.isNullOrBlank() && !token.isNullOrBlank()) {
                    buildListenTogetherWsUrl(normalizedBaseUrl, roomId, token)
                } else {
                    null
                }
            }
        NPLogger.d(
            TAG,
            "updateSession(): roomId=${response.roomId}, role=${response.role}, tokenPresent=${!response.token.isNullOrBlank()}, wsUrl=${resolvedWsUrl.redactListenTogetherWsUrlForLog()}"
        )
        _sessionState.value = _sessionState.value.copy(
            baseUrl = normalizedBaseUrl,
            roomId = response.roomId,
            userUuid = response.userUuid ?: response.userId,
            nickname = response.nickname,
            role = resolveSessionRole(
                sessionUserId = response.userUuid ?: response.userId,
                fallbackRole = response.role,
                state = response.state
            ),
            token = response.token,
            wsUrl = resolvedWsUrl,
            lastError = response.error,
            roomNotice = null
        )
        response.state?.let {
            val accepted = acceptRoomState(
                state = it,
                expectedPositionMs = null,
                source = RoomStateSource.HTTP_SESSION_UPDATE
            ) ?: return@let
            applyRoomStateToPlayer(
                accepted.state,
                causeType = resolveListenTogetherJoinAutoPauseCause(
                    autoPauseOnJoin = response.autoPauseOnJoin,
                    role = _sessionState.value.role,
                    state = accepted.state
                )
            )
        }
    }

    private fun acceptRoomState(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource,
        cause: ListenTogetherCause? = null
    ): AcceptedRoomState? {
        val currentState = _roomState.value
        val latestVersion = latestAcceptedRoomVersion(currentState)
        if (state.version < latestVersion) {
            NPLogger.d(
                TAG,
                "acceptRoomState(): drop stale source=${source.logName}, roomId=${state.roomId}, version=${state.version}, latest=$latestVersion"
            )
            return null
        }
        if (shouldDropControllerLocalEcho(state, cause, latestVersion)) {
            NPLogger.d(
                TAG,
                "acceptRoomState(): drop controller echo source=${source.logName}, roomId=${state.roomId}, version=${state.version}, latest=$latestVersion, causedBy=${cause?.type}:${cause?.eventId}"
            )
            return null
        }
        if (currentState != null && state.version == latestVersion) {
            lastAppliedRoomVersion = maxOf(lastAppliedRoomVersion, currentState.version)
            updateRoomPositionSupplement(currentState, expectedPositionMs, source)
            return AcceptedRoomState(
                state = currentState,
                expectedPositionMs = expectedPositionMs
            )
        }
        commitRoomState(state, expectedPositionMs, source)
        return AcceptedRoomState(
            state = state,
            expectedPositionMs = expectedPositionMs
        )
    }

    private fun commitRoomState(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource
    ) {
        NPLogger.d(
            TAG,
            "commitRoomState(): source=${source.logName}, roomId=${state.roomId}, version=${state.version}, members=${state.members.size}, expectedPositionMs=$expectedPositionMs"
        )
        lastAppliedRoomVersion = maxOf(lastAppliedRoomVersion, state.version)
        _roomState.value = state
        ensureListenTogetherForegroundService("room_state:${state.version}")
        awaitingTrackFinishStableKey?.let { waitingStableKey ->
            if (state.currentStableKey() != waitingStableKey) {
                awaitingTrackFinishStableKey = null
            }
        }
        _sessionState.value = _sessionState.value.copy(
            roomId = state.roomId,
            role = resolveSessionRole(
                sessionUserId = _sessionState.value.userUuid,
                fallbackRole = _sessionState.value.role,
                state = state
            ),
            expectedPositionMs = expectedPositionMs,
            roomNotice = roomNoticeForState(state)
        )
        maybeRecoverMissingListenerMembership(state, reason = "apply_room_state")
    }

    private fun updateRoomPositionSupplement(
        currentState: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource
    ) {
        if (expectedPositionMs == null) {
            NPLogger.d(
                TAG,
                "acceptRoomState(): keep current structure source=${source.logName}, roomId=${currentState.roomId}, version=${currentState.version}"
            )
            return
        }
        NPLogger.d(
            TAG,
            "acceptRoomState(): position supplement source=${source.logName}, roomId=${currentState.roomId}, version=${currentState.version}, expectedPositionMs=$expectedPositionMs"
        )
        _sessionState.value = _sessionState.value.copy(
            expectedPositionMs = expectedPositionMs
        )
    }

    private fun latestAcceptedRoomVersion(currentState: ListenTogetherRoomState?): Long {
        return maxOf(lastAppliedRoomVersion, currentState?.version ?: -1L)
    }

    private fun ensureListenTogetherForegroundService(reason: String) {
        if (AudioPlayerService.isReadyForPassiveLocalPlaybackSync()) {
            return
        }
        runCatching {
            AudioPlayerService.startSyncService(
                context = AppContainer.applicationContext,
                source = "listen_together_$reason",
                forceForeground = true
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "ensureListenTogetherForegroundService(): failed, reason=$reason, error=${error.message}",
                error
            )
        }
    }

    private fun handleSocketRoomState(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        if (shouldIgnoreIncomingState(message.causedBy)) {
            NPLogger.d(
                TAG,
                "handleSocketRoomState(): ignored causedBy=${message.causedBy?.type}:${message.causedBy?.eventId}"
            )
            return
        }
        if (shouldDeferIncomingStateForLocalTrackFinish(state, message.causedBy)) {
            NPLogger.d(
                TAG,
                "handleSocketRoomState(): defer while waiting track finish barrier, causedBy=${message.causedBy?.type}:${message.causedBy?.eventId}"
            )
            markInboundEvent(message.causedBy?.eventId)
            return
        }
        val accepted = acceptRoomState(
            state = state,
            expectedPositionMs = message.expectedPositionMs,
            source = RoomStateSource.WEB_SOCKET_STATE,
            cause = message.causedBy
        ) ?: return
        if (message.causedBy?.type == "TRACK_FINISHED") {
            awaitingTrackFinishStableKey = null
        }
        markInboundEvent(message.causedBy?.eventId)
        val currentUserUuid = _sessionState.value.userUuid
        if (
            isCurrentUserController() &&
            message.causedBy?.userUuid == currentUserUuid &&
            message.causedBy?.type != "TRACK_FINISHED"
        ) {
            NPLogger.d(
                TAG,
                "handleSocketRoomState(): current controller caused event locally, skip player apply, causedBy=${message.causedBy?.type}:${message.causedBy?.eventId}"
            )
            maybePublishControllerRecoveryHeartbeat(message)
            return
        }
        applyRoomStateToPlayer(
            accepted.state,
            message.causedBy?.type,
            accepted.expectedPositionMs
        )
        maybeRequestControllerLink(accepted.state, message.causedBy?.type)
        maybePublishControllerRecoveryHeartbeat(message)
    }

    private fun handleLinkRequested(message: ListenTogetherSocketEnvelope) {
        val snapshot = _sessionState.value
        if (!isCurrentUserController(snapshot)) {
            NPLogger.d(
                TAG,
                "handleLinkRequested(): ignore because current user is not controller, requester=${message.causedBy?.userUuid}, role=${currentRole(snapshot)}"
            )
            return
        }
        val stableKey = message.requestTrackStableKey
            ?: message.track?.stableKey
            ?: run {
                NPLogger.w(
                    TAG,
                    "handleLinkRequested(): missing stableKey, requester=${message.causedBy?.userUuid}, messageType=${message.type}"
                )
                return
            }
        NPLogger.d(
            TAG,
            "handleLinkRequested(): stableKey=$stableKey, requester=${message.causedBy?.userUuid}"
        )
        val published = publishControllerLinkReadyIfPossible(
            stableKey = stableKey,
            reason = "request:${message.causedBy?.userUuid}"
        )
        if (!published) {
            resolveAndPublishControllerLink(
                stableKey = stableKey,
                reason = "request:${message.causedBy?.userUuid}"
            )
        }
    }

    private fun handleMemberControlRequested(message: ListenTogetherSocketEnvelope) {
        val snapshot = _sessionState.value
        if (!isCurrentUserController(snapshot)) return
        val requestSequence = message.requestSequence ?: 0L
        if (requestSequence in 1..lastHandledForwardedRequestSequence) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): ignore duplicate/outdated requestSequence=$requestSequence, lastHandled=$lastHandledForwardedRequestSequence"
            )
            return
        }
        val forwardedEvent = buildControllerCommitEventFromForwardedRequest(message) ?: run {
            NPLogger.w(
                TAG,
                "handleMemberControlRequested(): invalid forwarded request type=${message.causedBy?.type}, requester=${message.causedBy?.userUuid}"
            )
            return
        }
        if (shouldRejectForwardedMemberControl(message, forwardedEvent)) {
            return
        }
        requestSequence.takeIf { it > 0L }?.let { lastHandledForwardedRequestSequence = it }
        if (
            SystemClock.elapsedRealtime() - lastControllerLocalControlAtElapsedMs <
            CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS
        ) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): controller local action wins, skip requestSequence=$requestSequence, requester=${message.causedBy?.userUuid}"
            )
            publishControllerHeartbeatIfNeeded(force = true, reason = "controller_priority")
            return
        }
        NPLogger.d(
            TAG,
            "handleMemberControlRequested(): requestSequence=$requestSequence, requester=${message.causedBy?.userUuid}, type=${message.causedBy?.type}, commitType=${forwardedEvent.type}"
        )
        applyForwardedControllerRequestLocally(message, forwardedEvent)
        markOutboundEvent(forwardedEvent.eventId)
        noteOutboundSync()
        if (!sendControlEventPureWebSocket(forwardedEvent, "forwarded_member_control")) {
            NPLogger.w(
                TAG,
                "handleMemberControlRequested(): websocket unavailable, requester=${message.causedBy?.userUuid}, requestSequence=$requestSequence"
            )
        }
    }

    private fun handleRoomSuspended(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        NPLogger.w(
            TAG,
            "handleRoomSuspended(): roomId=${state.roomId}, controllerOfflineSince=${state.controllerOfflineSince}"
        )
        val accepted = acceptRoomState(
            state = state,
            expectedPositionMs = message.expectedPositionMs,
            source = RoomStateSource.WEB_SOCKET_ROOM_STATUS,
            cause = message.causedBy
        ) ?: return
        _sessionState.value = _sessionState.value.copy(
            roomNotice = roomNoticeForState(accepted.state, message.message)
        )
    }

    private fun handleRoomResumed(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        NPLogger.d(TAG, "handleRoomResumed(): roomId=${state.roomId}, version=${state.version}")
        val accepted = acceptRoomState(
            state = state,
            expectedPositionMs = message.expectedPositionMs,
            source = RoomStateSource.WEB_SOCKET_ROOM_STATUS,
            cause = message.causedBy
        ) ?: return
        val currentUserUuid = _sessionState.value.userUuid
        if (!isCurrentUserController() || message.causedBy?.userUuid != currentUserUuid) {
            applyRoomStateToPlayer(accepted.state, message.message, accepted.expectedPositionMs)
        }
        _sessionState.value = _sessionState.value.copy(
            roomNotice = roomNoticeForState(accepted.state, message.message),
            lastError = null
        )
    }

    private fun handleRoomClosed(message: ListenTogetherSocketEnvelope) {
        val state = message.state
        NPLogger.w(
            TAG,
            "handleRoomClosed(): roomId=${message.roomId ?: state?.roomId}, message=${message.message}"
        )
        state?.let {
            acceptRoomState(
                state = it,
                expectedPositionMs = message.expectedPositionMs,
                source = RoomStateSource.WEB_SOCKET_ROOM_CLOSED,
                cause = message.causedBy
            )
        }
        closeRoomLocally(message.message ?: roomNoticeForState(state))
    }

    private suspend fun handleLocalPlaybackCommand(command: PlaybackCommand) {
        val snapshot = _sessionState.value
        NPLogger.d(
            TAG,
            "handleLocalPlaybackCommand(): type=${command.type}, source=${command.source}, connection=${snapshot.connectionState}, role=${currentRole(snapshot)}, roomId=${snapshot.roomId}"
        )
        if (command.source != PlaybackCommandSource.LOCAL) return
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) return
        if (snapshot.roomId.isNullOrBlank()) return
        resolveControlBlockReason(snapshot, _roomState.value, command)?.let { reason ->
            NPLogger.w(TAG, "handleLocalPlaybackCommand(): blocked, reason=$reason")
            _sessionState.value = _sessionState.value.copy(lastError = reason)
            return
        }

        val event = buildEventForPlaybackCommand(command) ?: run {
            NPLogger.w(
                TAG,
                "handleLocalPlaybackCommand(): unsupported command type=${command.type}, source=${command.source}"
            )
            return
        }
        if (shouldSuppressLocalListenerControlEvent(event)) {
            return
        }
        if (event.type == "TRACK_FINISHED") {
            awaitingTrackFinishStableKey = event.finishedTrackStableKey
            pendingTrackFinishedLegacyFallback = buildTrackFinishedLegacyFallbackEvent(
                event = event,
                isController = isCurrentUserController(),
                nowMs = System.currentTimeMillis(),
                eventIdFactory = ::nextEventId
            )?.let { fallbackEvent ->
                PendingTrackFinishedLegacyFallback(
                    event = fallbackEvent,
                    createdAtElapsedMs = SystemClock.elapsedRealtime()
                )
            }
        } else {
            awaitingTrackFinishStableKey = null
            pendingTrackFinishedLegacyFallback = null
        }
        pendingMemberControlRequest = buildPendingMemberControlRequest(event)
        noteControllerLocalControl(command)
        markOutboundEvent(event.eventId)
        noteOutboundSync()
        NPLogger.d(
            TAG,
            "sendEvent(): type=${event.type}, eventId=${event.eventId}, currentIndex=${event.currentIndex}, positionMs=${event.positionMs}, queueSize=${event.queue?.size}"
        )
        val wsSent = sendControlEventPureWebSocket(event, "local_playback_command")
        NPLogger.d(TAG, "sendEvent(): websocketSent=$wsSent, type=${event.type}, eventId=${event.eventId}")
        if (!wsSent) {
            NPLogger.w(TAG, "sendEvent(): websocket unavailable, type=${event.type}, eventId=${event.eventId}")
        }
    }

    private fun buildEventForPlaybackCommand(command: PlaybackCommand): ListenTogetherEvent? {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val currentIndex = command.currentIndex
            ?: queue.indexOfFirst { song ->
                currentSong != null && song.sameTrackAs(currentSong)
            }.takeIf { it >= 0 }
            ?: 0
        val positionMs = command.positionMs ?: PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        val localPlaying = PlayerManager.isPlayingFlow.value
        val shouldPlay = resolveListenTogetherPlaybackCommandShouldPlay(
            commandType = command.type,
            commandShouldPlay = command.shouldPlay,
            localTransportActive = isLocalPlaybackTransportActive(),
            localPlaying = localPlaying
        )
        val roomSettings = _roomState.value?.settings

        return when (command.type) {
            "PLAY_PLAYLIST",
            "PLAY_FROM_QUEUE",
            "NEXT",
            "PREVIOUS" -> if (isCurrentUserController()) {
                buildSetTrackEvent(
                    queue = queue,
                    currentIndex = currentIndex,
                    positionMs = positionMs,
                    shouldPlay = shouldPlay
                )
            } else {
                buildRequestSetTrackEvent(
                    queue = queue,
                    currentIndex = currentIndex,
                    positionMs = positionMs,
                    shouldPlay = shouldPlay
                )
            }

            "PLAY" -> if (isCurrentUserController()) buildPlayEvent(positionMs) else buildRequestPlayEvent(positionMs)
            "PAUSE" -> if (isCurrentUserController()) buildPauseEvent(positionMs) else buildRequestPauseEvent(positionMs)
            "PLAYBACK_MODE" -> buildPlaybackModeEvent(
                repeatMode = command.repeatMode ?: PlayerManager.repeatModeFlow.value,
                shuffleEnabled = command.shuffleEnabled ?: PlayerManager.shuffleModeFlow.value
            )
            "TRACK_FINISHED" -> buildTrackFinishedEvent(command, queue, currentSong, positionMs)
            "SEEK" -> {
                val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
                    currentIndex = currentIndex,
                    roomSettings = roomSettings,
                    includeResolvedStreamUrl = isCurrentUserController()
                )
                val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
                val event = if (isCurrentUserController()) buildSeekEvent(positionMs) else buildRequestSeekEvent(positionMs)
                event.copy(
                    currentIndex = resolvedCurrentIndex,
                    track = shareableTrack
                )
            }
            else -> null
        }
    }

    private fun buildControllerCommitEventFromForwardedRequest(
        message: ListenTogetherSocketEnvelope
    ): ListenTogetherEvent? {
        val requestType = message.causedBy?.type ?: return null
        val commitType = requestType.removePrefix("REQUEST_")
        if (commitType == requestType) return null
        val queue = message.queue
        val currentIndex = message.currentIndex
        val track = message.track
        val positionMs = message.positionMs ?: message.expectedPositionMs ?: 0L
        return ListenTogetherEvent(
            type = commitType,
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = currentIndex,
            track = track,
            queue = queue,
            shouldPlay = message.shouldPlay,
            state = message.stateName,
            repeatMode = message.repeatMode,
            shuffleEnabled = message.shuffleEnabled,
            requestTrackStableKey = message.requestTrackStableKey
        )
    }

    private fun shouldSuppressLocalListenerControlEvent(event: ListenTogetherEvent): Boolean {
        if (isCurrentUserController()) return false
        if (event.type !in REQUEST_CONTROL_EVENT_TYPES) return false
        val currentState = _roomState.value
        val currentStableKey = currentState?.currentStableKey()
        val requestedStableKey = event.requestedStableKey()
        if (!isListenTogetherMemberControlTargetCurrent(event.type, requestedStableKey, currentStableKey)) {
            NPLogger.w(
                TAG,
                "shouldSuppressLocalListenerControlEvent(): stale target, type=${event.type}, requested=$requestedStableKey, current=$currentStableKey"
            )
            return true
        }
        val awaitingAuthoritativeStream = currentState?.targetSongItem()?.let { targetSong ->
            shouldWaitForListenTogetherAuthoritativeStreamPlayback(
                playerWaitingForAuthoritativeStream = PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(targetSong),
                localTrackMatchesTarget = PlayerManager.currentSongFlow.value?.sameTrackAs(targetSong) == true,
                localTrackStreamUrl = PlayerManager.currentSongFlow.value?.streamUrl,
                localResolvedStreamUrl = PlayerManager.currentMediaUrlFlow.value
            )
        } ?: false
        val hasDirectStream = normalizedDirectStreamUrl(PlayerManager.currentSongFlow.value?.streamUrl) != null ||
            normalizedDirectStreamUrl(PlayerManager.currentMediaUrlFlow.value) != null
        if (
            shouldSuppressListenerControlWhileAwaitingStream(
                eventType = event.type,
                awaitingAuthoritativeStream = awaitingAuthoritativeStream,
                localTrackHasDirectStream = hasDirectStream
            )
        ) {
            NPLogger.w(
                TAG,
                "shouldSuppressLocalListenerControlEvent(): awaiting controller stream, type=${event.type}, requested=$requestedStableKey"
            )
            currentState?.let { maybeRequestControllerLink(it, "suppress_local_control:${event.type}", force = true) }
            return true
        }
        return false
    }

    private fun shouldRejectForwardedMemberControl(
        message: ListenTogetherSocketEnvelope,
        forwardedEvent: ListenTogetherEvent
    ): Boolean {
        val requestType = message.causedBy?.type ?: return false
        if (requestType !in TRACK_BOUND_REQUEST_CONTROL_EVENT_TYPES) return false
        val currentStableKey = _roomState.value?.currentStableKey()
        val requestedStableKey = forwardedEvent.requestedStableKey()
        if (isListenTogetherMemberControlTargetCurrent(requestType, requestedStableKey, currentStableKey)) {
            return false
        }
        NPLogger.w(
            TAG,
            "shouldRejectForwardedMemberControl(): stale target, requestType=$requestType, requested=$requestedStableKey, current=$currentStableKey, requester=${message.causedBy?.userUuid}"
        )
        publishControllerHeartbeatIfNeeded(force = true, reason = "reject_stale_member_control")
        return true
    }

    private fun applyForwardedControllerRequestLocally(
        message: ListenTogetherSocketEnvelope,
        committedEvent: ListenTogetherEvent
    ) {
        val currentState = _roomState.value ?: return
        val nextQueue = message.queue
            ?.mergeCurrentTrack(message.currentIndex ?: currentState.currentIndex, message.track)
            ?: currentState.queue.mergeCurrentTrack(currentState.currentIndex, currentState.track)
        val nextIndex = (message.currentIndex ?: currentState.currentIndex).coerceIn(
            0,
            (nextQueue.lastIndex).coerceAtLeast(0)
        )
        val nextTrack = message.track ?: nextQueue.getOrNull(nextIndex) ?: currentState.track
        val nextPlaybackState = when (committedEvent.type) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> message.stateName ?: if (message.shouldPlay == true) "playing" else currentState.playback.state
        }
        val syntheticState = currentState.copy(
            queue = nextQueue,
            currentIndex = nextIndex,
            track = nextTrack,
            playback = currentState.playback.copy(
                state = nextPlaybackState,
                basePositionMs = (committedEvent.positionMs ?: message.expectedPositionMs ?: 0L).coerceAtLeast(0L),
                baseTimestampMs = System.currentTimeMillis(),
                repeatMode = committedEvent.repeatMode ?: currentState.playback.repeatMode,
                shuffleEnabled = committedEvent.shuffleEnabled ?: currentState.playback.shuffleEnabled
            )
        )
        commitRoomState(
            state = syntheticState,
            expectedPositionMs = committedEvent.positionMs ?: message.expectedPositionMs,
            source = RoomStateSource.LOCAL_SYNTHETIC
        )
        applyRoomStateToPlayer(
            syntheticState,
            message.causedBy?.type ?: committedEvent.type,
            committedEvent.positionMs ?: message.expectedPositionMs
        )
    }

    private fun resolveControlBlockReason(
        sessionState: ListenTogetherSessionState,
        roomState: ListenTogetherRoomState?,
        command: PlaybackCommand
    ): String? {
        if (
            roomState?.roomStatus == ListenTogetherRoomStatuses.CONTROLLER_OFFLINE &&
            currentRole(sessionState) != "controller"
        ) {
            return if (roomState.settings.normalized().shareAudioLinks) "房主已离线，无法获取播放链接" else "controller offline"
        }
        if (
            currentRole(sessionState) == "listener" &&
            roomState?.settings.normalized()?.allowMemberControl == false &&
            command.type in CONTROLLED_PLAYBACK_COMMAND_TYPES
        ) {
            return "当前房间未开启共同控制"
        }
        return null
    }

    private fun shouldIgnoreIncomingState(cause: ListenTogetherCause?): Boolean {
        if (cause?.type == "TRACK_FINISHED") return false
        val eventId = cause?.eventId
        if (cause?.type?.startsWith("REQUEST_") == true) return false
        val currentUserId = _sessionState.value.userUuid
        if (!eventId.isNullOrBlank() && hasRecentOutboundEvent(eventId)) return true
        if (!eventId.isNullOrBlank() && hasRecentInboundEvent(eventId)) return true
        if (!eventId.isNullOrBlank() && cause?.userUuid == currentUserId) return true
        return false
    }

    private fun shouldDeferIncomingStateForLocalTrackFinish(
        state: ListenTogetherRoomState,
        cause: ListenTogetherCause?
    ): Boolean {
        val waitingStableKey = awaitingTrackFinishStableKey ?: return false
        if (cause?.type !in PASSIVE_POSITION_UPDATE_TYPES) return false
        if (state.playback.state != "playing") return false
        return state.currentStableKey() == waitingStableKey
    }

    private fun shouldDropControllerLocalEcho(
        state: ListenTogetherRoomState,
        cause: ListenTogetherCause?,
        latestVersion: Long
    ): Boolean {
        if (cause?.type == "TRACK_FINISHED") return false
        val currentUserId = _sessionState.value.userUuid
        return currentUserId == (state.controllerUserUuid ?: state.controllerUserId) &&
                cause?.userUuid == currentUserId &&
                SystemClock.elapsedRealtime() - lastControllerLocalControlAtElapsedMs < CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS &&
                state.version <= latestVersion + 1
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        NPLogger.d(TAG, "startHeartbeat()")
        if (lastOutboundSyncAtMs == 0L) {
            lastOutboundSyncAtMs = SystemClock.elapsedRealtime()
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_POLL_INTERVAL_MS)
                val snapshot = _sessionState.value
                if (
                    snapshot.connectionState != ListenTogetherConnectionState.CONNECTED ||
                    !isCurrentUserController(snapshot)
                ) {
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val idleMs = now - lastOutboundSyncAtMs
                if (idleMs < HEARTBEAT_IDLE_THRESHOLD_MS) {
                    continue
                }
                val heartbeat = buildHeartbeatEvent(
                    state = currentLocalPlaybackStateName(),
                    positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L),
                    includeQueue = false
                )
                markOutboundEvent(heartbeat.eventId)
                noteOutboundSync()
                NPLogger.d(
                    TAG,
                    "heartbeat(): eventId=${heartbeat.eventId}, positionMs=${heartbeat.positionMs}, idleMs=$idleMs"
                )
                sendControlEventPureWebSocket(heartbeat, "heartbeat")
            }
        }
    }

    private fun stopHeartbeat() {
        NPLogger.d(TAG, "stopHeartbeat()")
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startSyncWatchdog() {
        if (syncWatchdogJob?.isActive == true) return
        NPLogger.d(TAG, "startSyncWatchdog()")
        lastListenerStateRefreshAtElapsedMs = 0L
        syncWatchdogJob = scope.launch {
            while (isActive) {
                delay(SYNC_WATCHDOG_INTERVAL_MS)
                val snapshot = _sessionState.value
                if (
                    snapshot.connectionState != ListenTogetherConnectionState.CONNECTED ||
                    snapshot.roomId.isNullOrBlank()
                ) {
                    continue
                }
                if (isCurrentUserController(snapshot)) {
                    publishControllerWatchdogHeartbeatIfDue()
                    continue
                }
                retryPendingMemberControlRequestIfNeeded()
                val state = _roomState.value
                if (state != null) {
                    applyListenerWatchdogSync(state)
                    maybeRequestControllerLink(state, "listener_watchdog")
                }
                refreshListenerRoomStateIfDue(snapshot, "listener_watchdog")
            }
        }
    }

    private fun stopSyncWatchdog() {
        NPLogger.d(TAG, "stopSyncWatchdog()")
        syncWatchdogJob?.cancel()
        syncWatchdogJob = null
    }

    private fun publishControllerWatchdogHeartbeatIfDue() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            lastControllerWatchdogHeartbeatAtElapsedMs > 0L &&
            nowElapsedMs - lastControllerWatchdogHeartbeatAtElapsedMs < CONTROLLER_WATCHDOG_HEARTBEAT_INTERVAL_MS
        ) {
            return
        }
        val state = _roomState.value ?: return
        if (state.roomStatus == ListenTogetherRoomStatuses.CLOSED) return
        lastControllerWatchdogHeartbeatAtElapsedMs = nowElapsedMs
        val heartbeat = buildHeartbeatEvent(
            state = currentLocalPlaybackStateName(),
            positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L),
            includeQueue = false
        )
        if (heartbeat.track == null) return
        markOutboundEvent(heartbeat.eventId)
        noteOutboundSync()
        NPLogger.d(
            TAG,
            "publishControllerWatchdogHeartbeatIfDue(): eventId=${heartbeat.eventId}, positionMs=${heartbeat.positionMs}"
        )
        sendControlEventPureWebSocket(heartbeat, "controller_watchdog")
    }

    private fun buildPendingMemberControlRequest(event: ListenTogetherEvent): PendingMemberControlRequest? {
        if (isCurrentUserController()) return null
        if (event.type !in REQUEST_CONTROL_EVENT_TYPES) return null
        val nowElapsedMs = SystemClock.elapsedRealtime()
        return PendingMemberControlRequest(
            event = event,
            createdAtElapsedMs = nowElapsedMs,
            lastSentAtElapsedMs = nowElapsedMs,
            attempts = 1
        )
    }

    private fun retryPendingMemberControlRequestIfNeeded() {
        val pending = pendingMemberControlRequest ?: return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            isListenTogetherPendingMemberControlSatisfied(
                event = pending.event,
                state = _roomState.value,
                seekSatisfiedDriftMs = PENDING_MEMBER_SEEK_SATISFIED_DRIFT_MS
            )
        ) {
            NPLogger.d(TAG, "retryPendingMemberControlRequestIfNeeded(): request satisfied, type=${pending.event.type}")
            pendingMemberControlRequest = null
            return
        }
        if (nowElapsedMs - pending.createdAtElapsedMs > PENDING_MEMBER_CONTROL_REQUEST_TTL_MS) {
            NPLogger.w(TAG, "retryPendingMemberControlRequestIfNeeded(): request expired, type=${pending.event.type}")
            pendingMemberControlRequest = null
            return
        }
        if (pending.attempts >= PENDING_MEMBER_CONTROL_REQUEST_MAX_ATTEMPTS) {
            NPLogger.w(TAG, "retryPendingMemberControlRequestIfNeeded(): max attempts reached, type=${pending.event.type}")
            pendingMemberControlRequest = null
            return
        }
        if (nowElapsedMs - pending.lastSentAtElapsedMs < PENDING_MEMBER_CONTROL_REQUEST_RETRY_INTERVAL_MS) {
            return
        }
        val retryEvent = pending.event.copy(
            eventId = nextEventId(),
            clientTimeMs = System.currentTimeMillis()
        )
        pendingMemberControlRequest = pending.copy(
            event = retryEvent,
            lastSentAtElapsedMs = nowElapsedMs,
            attempts = pending.attempts + 1
        )
        markOutboundEvent(retryEvent.eventId)
        NPLogger.w(
            TAG,
            "retryPendingMemberControlRequestIfNeeded(): retry type=${retryEvent.type}, attempt=${pending.attempts + 1}"
        )
        sendControlEventPureWebSocket(retryEvent, "pending_member_control_retry")
    }

    private suspend fun refreshListenerRoomStateIfDue(
        snapshot: ListenTogetherSessionState,
        reason: String
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            lastListenerStateRefreshAtElapsedMs > 0L &&
            nowElapsedMs - lastListenerStateRefreshAtElapsedMs < LISTENER_STATE_REFRESH_INTERVAL_MS
        ) {
            return
        }
        lastListenerStateRefreshAtElapsedMs = nowElapsedMs
        val baseUrl = snapshot.baseUrl
        val roomId = snapshot.roomId
        if (baseUrl.isNullOrBlank() || roomId.isNullOrBlank()) return
        runCatching {
            refreshRoomState(baseUrl, roomId)
        }.onFailure { error ->
            val resolvedError = error.message ?: error.javaClass.simpleName
            NPLogger.w(
                TAG,
                "refreshListenerRoomStateIfDue(): failed, reason=$reason, roomId=$roomId, error=$resolvedError",
                error
            )
            _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
            if (handleTerminalReconnectFailure(resolvedError, "listener_watchdog_refresh")) {
                return@onFailure
            }
            if (!maybeRecoverFromFatalMembershipError(resolvedError, "listener_watchdog_refresh")) {
                scheduleReconnect("listener_watchdog_refresh_failed:$reason")
            }
        }
    }

    private fun applyListenerWatchdogSync(state: ListenTogetherRoomState) {
        if (isCurrentUserController()) return
        if (state.roomStatus != ListenTogetherRoomStatuses.ACTIVE) return
        val expectedPositionMs = state.playback.expectedPositionMs(
            serverClockOffsetMs = estimatedServerClockOffsetMs
        )
        val needsStallRecovery = shouldRecoverListenerPlaybackStall(state)
        val causeType = if (needsStallRecovery) "WATCHDOG_STALL" else "WATCHDOG"
        applyRoomStateToPlayer(
            state = state,
            causeType = causeType,
            expectedPositionMs = expectedPositionMs
        )
        if (needsStallRecovery) {
            maybeRequestControllerLink(state, causeType, force = true)
        }
    }

    private fun shouldRecoverListenerPlaybackStall(state: ListenTogetherRoomState): Boolean {
        if (state.playback.state != "playing") {
            resetListenerBufferingState()
            return false
        }
        if (PlayerManager.isPlayingFlow.value) {
            resetListenerBufferingState()
            return false
        }
        val targetSong = state.targetSongItem() ?: run {
            resetListenerBufferingState()
            return false
        }
        val currentSong = PlayerManager.currentSongFlow.value
        if (currentSong?.sameTrackAs(targetSong) != true) {
            resetListenerBufferingState()
            return false
        }
        val playerState = PlayerManager.playerPlaybackStateFlow.value
        val looksStalled =
            PlayerManager.playWhenReadyFlow.value ||
                PlayerManager.isPendingMediaLoadActive() ||
                playerState == Player.STATE_BUFFERING ||
                playerState == Player.STATE_IDLE
        if (!looksStalled) {
            resetListenerBufferingState()
            return false
        }
        val stableKey = state.currentStableKey() ?: run {
            resetListenerBufferingState()
            return false
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (listenerBufferingTrackStableKey != stableKey) {
            listenerBufferingTrackStableKey = stableKey
            listenerBufferingStartedAtElapsedMs = nowElapsedMs
            return false
        }
        if (nowElapsedMs - listenerBufferingStartedAtElapsedMs < LISTENER_PLAYBACK_STALL_TIMEOUT_MS) {
            return false
        }
        if (nowElapsedMs - lastListenerBufferingRecoveryAtElapsedMs < LISTENER_PLAYBACK_STALL_RECOVERY_COOLDOWN_MS) {
            return false
        }
        lastListenerBufferingRecoveryAtElapsedMs = nowElapsedMs
        listenerBufferingStartedAtElapsedMs = nowElapsedMs
        NPLogger.w(
            TAG,
            "shouldRecoverListenerPlaybackStall(): stableKey=$stableKey, playerState=$playerState, playWhenReady=${PlayerManager.playWhenReadyFlow.value}"
        )
        return true
    }

    private fun resetListenerBufferingState() {
        listenerBufferingStartedAtElapsedMs = 0L
        listenerBufferingTrackStableKey = null
    }

    private fun resetListenerRecoveryState() {
        lastListenerStateRefreshAtElapsedMs = 0L
        lastListenerBufferingRecoveryAtElapsedMs = 0L
        resetListenerBufferingState()
    }

    private fun cancelControllerLinkResolve() {
        controllerLinkResolveJob?.cancel()
        controllerLinkResolveJob = null
        controllerLinkResolveStableKey = null
    }

    private suspend fun handleResolvedStreamUrlChanged(url: String?) {
        val streamUrl = url?.trim().orEmpty()
        if (streamUrl.isBlank()) {
            NPLogger.d(TAG, "handleResolvedStreamUrlChanged(): ignored blank url")
            return
        }
        if (!streamUrl.startsWith("https://", ignoreCase = true) && !streamUrl.startsWith("http://", ignoreCase = true)) {
            NPLogger.d(TAG, "handleResolvedStreamUrlChanged(): ignored non-http url=$streamUrl")
            return
        }
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) {
            NPLogger.d(
                TAG,
                "handleResolvedStreamUrlChanged(): ignored because connection=${snapshot.connectionState}"
            )
            return
        }
        if (snapshot.roomId.isNullOrBlank()) {
            NPLogger.d(TAG, "handleResolvedStreamUrlChanged(): ignored because roomId is blank")
            return
        }
        if (!isCurrentUserController(snapshot)) {
            NPLogger.d(
                TAG,
                "handleResolvedStreamUrlChanged(): ignored because current user is not controller, roomId=${snapshot.roomId}"
            )
            return
        }
        if (!_roomState.value?.settings.normalized().shareAudioLinks) {
            NPLogger.d(
                TAG,
                "handleResolvedStreamUrlChanged(): ignored because shareAudioLinks is disabled, roomId=${snapshot.roomId}"
            )
            return
        }
        val currentStableKey = PlayerManager.currentSongFlow.value?.toListenTogetherTrackOrNull()?.stableKey
        NPLogger.d(
            TAG,
            "handleResolvedStreamUrlChanged(): roomId=${snapshot.roomId}, stableKey=$currentStableKey, url=${streamUrl.take(128)}"
        )
        if (!currentStableKey.isNullOrBlank()) {
            publishControllerLinkReadyIfPossible(
                stableKey = currentStableKey,
                reason = "stream_url_resolved"
            )
        } else {
            publishControllerHeartbeatIfNeeded(force = true, reason = "stream_url_resolved")
        }
    }

    private fun scheduleReconnect(reason: String) {
        val snapshot = _sessionState.value
        if (!reconnectEnabled) {
            NPLogger.d(TAG, "scheduleReconnect(): skipped, reconnect disabled, reason=$reason")
            return
        }
        if (snapshot.wsUrl.isNullOrBlank() || snapshot.roomId.isNullOrBlank()) {
            NPLogger.d(TAG, "scheduleReconnect(): skipped, missing room/wsUrl, reason=$reason")
            return
        }
        if (snapshot.connectionState == ListenTogetherConnectionState.CONNECTING) {
            NPLogger.d(TAG, "scheduleReconnect(): skipped, already connecting, reason=$reason")
            return
        }
        if (reconnectJob?.isActive == true) {
            NPLogger.d(TAG, "scheduleReconnect(): already scheduled, reason=$reason")
            return
        }
        val attempt = reconnectAttempt + 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            NPLogger.w(TAG, "scheduleReconnect(): max attempts reached ($MAX_RECONNECT_ATTEMPTS), giving up, reason=$reason")
            closeRoomLocally("reconnect_max_attempts_exceeded")
            return
        }
        val delayMs = reconnectDelayMs(attempt)
        reconnectAttempt = attempt
        NPLogger.w(
            TAG,
            "scheduleReconnect(): roomId=${snapshot.roomId}, attempt=$attempt, delayMs=$delayMs, reason=$reason"
        )
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            val latest = _sessionState.value
            if (!reconnectEnabled || latest.wsUrl.isNullOrBlank() || latest.roomId.isNullOrBlank()) {
                NPLogger.d(TAG, "scheduleReconnect(): cancelled before execution")
                return@launch
            }
            NPLogger.d(TAG, "reconnect(): roomId=${latest.roomId}, attempt=$attempt")
            if (tryRecoverMembershipBeforeReconnect("scheduled_reconnect:$reason")) {
                return@launch
            }
            connectWebSocket()
        }
    }

    private fun sendControlEventPureWebSocket(
        event: ListenTogetherEvent,
        reason: String
    ): Boolean {
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) {
            handleWebSocketControlSendFailure(
                event = event,
                reason = "$reason:not_connected"
            )
            sendControlEventOverHttpFallback(event, "$reason:not_connected")
            return false
        }
        val sent = sendControlEventOverWebSocket(event)
        if (!sent) {
            handleWebSocketControlSendFailure(
                event = event,
                reason = "$reason:send_failed"
            )
            sendControlEventOverHttpFallback(event, "$reason:send_failed")
        }
        return sent
    }

    private fun trySendTrackFinishedLegacyFallback(errorMessage: String): Boolean {
        if (!isUnsupportedTrackFinishedEventError(errorMessage)) return false
        val pending = pendingTrackFinishedLegacyFallback ?: return false
        if (pending.attempted) return false
        val elapsedMs = SystemClock.elapsedRealtime() - pending.createdAtElapsedMs
        if (elapsedMs > TRACK_FINISHED_LEGACY_FALLBACK_TTL_MS) {
            pendingTrackFinishedLegacyFallback = null
            return false
        }
        pendingTrackFinishedLegacyFallback = pending.copy(attempted = true)
        awaitingTrackFinishStableKey = null
        markOutboundEvent(pending.event.eventId)
        noteOutboundSync()
        NPLogger.w(
            TAG,
            "trySendTrackFinishedLegacyFallback(): server rejected TRACK_FINISHED, fallbackType=${pending.event.type}, eventId=${pending.event.eventId}, elapsedMs=$elapsedMs"
        )
        val sent = sendControlEventPureWebSocket(pending.event, "track_finished_legacy_fallback")
        if (sent) {
            _sessionState.value = _sessionState.value.copy(lastError = null)
        }
        return sent
    }

    private fun handleWebSocketControlSendFailure(
        event: ListenTogetherEvent,
        reason: String
    ) {
        pendingStateRefreshAfterReconnect = true
        val resolvedMessage = "一起听连接不可用，正在重连"
        NPLogger.w(
            TAG,
            "handleWebSocketControlSendFailure(): type=${event.type}, eventId=${event.eventId}, reason=$reason"
        )
        _sessionState.value = _sessionState.value.copy(lastError = resolvedMessage)
        scheduleReconnect("control_send_failed:${event.type}:$reason")
    }

    private fun sendControlEventOverHttpFallback(
        event: ListenTogetherEvent,
        reason: String
    ) {
        val snapshot = _sessionState.value
        if (
            snapshot.baseUrl.isNullOrBlank() ||
            snapshot.roomId.isNullOrBlank() ||
            snapshot.token.isNullOrBlank()
        ) {
            NPLogger.d(TAG, "sendControlEventOverHttpFallback(): skipped, missing session, reason=$reason")
            return
        }
        scope.launch {
            runCatching {
                sendControlEvent(event)
            }.onSuccess { response ->
                handleHttpFallbackControlResponse(response, event, reason)
            }.onFailure { error ->
                val resolvedError = error.message ?: error.javaClass.simpleName
                NPLogger.w(
                    TAG,
                    "sendControlEventOverHttpFallback(): failed, type=${event.type}, reason=$reason, error=$resolvedError",
                    error
                )
                _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
                if (handleTerminalReconnectFailure(resolvedError, "http_control_fallback")) {
                    return@onFailure
                }
                maybeRecoverFromFatalMembershipError(resolvedError, "http_control_fallback")
            }
        }
    }

    private fun handleHttpFallbackControlResponse(
        response: ListenTogetherControlResponse,
        event: ListenTogetherEvent,
        reason: String
    ) {
        if (!response.ok || !response.error.isNullOrBlank()) {
            val resolvedError = response.error ?: "control event rejected"
            NPLogger.w(
                TAG,
                "handleHttpFallbackControlResponse(): rejected, type=${event.type}, reason=$reason, error=$resolvedError"
            )
            _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
            if (trySendTrackFinishedLegacyFallback(resolvedError)) {
                return
            }
            if (handleTerminalReconnectFailure(resolvedError, "http_control_fallback_response")) {
                return
            }
            maybeRecoverFromFatalMembershipError(resolvedError, "http_control_fallback_response")
            return
        }
        _sessionState.value = _sessionState.value.copy(lastError = null)
        val applied = response.applied ?: return
        val state = applied.state ?: return
        NPLogger.d(
            TAG,
            "handleHttpFallbackControlResponse(): applied, type=${event.type}, reason=$reason, version=${applied.version}"
        )
        val accepted = acceptRoomState(
            state = state,
            expectedPositionMs = applied.expectedPositionMs,
            source = RoomStateSource.HTTP_CONTROL_FALLBACK,
            cause = applied.causedBy
        )
        if (accepted != null && !isCurrentUserController()) {
            applyRoomStateToPlayer(
                state = accepted.state,
                causeType = applied.causedBy?.type,
                expectedPositionMs = accepted.expectedPositionMs
            )
            maybeRequestControllerLink(accepted.state, applied.causedBy?.type)
        }
    }

    private suspend fun refreshRoomStateAfterReconnect(reason: String) {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl
        val roomId = snapshot.roomId
        if (baseUrl.isNullOrBlank() || roomId.isNullOrBlank()) return
        runCatching {
            refreshRoomState(baseUrl, roomId)
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "refreshRoomStateAfterReconnect(): failed, reason=$reason, error=${error.message}"
            )
            val resolvedError = error.message ?: error.javaClass.simpleName
            _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
            if (handleTerminalReconnectFailure(resolvedError, "refresh_after_reconnect")) {
                return@onFailure
            }
            if (!maybeRecoverFromFatalMembershipError(resolvedError, "refresh_after_reconnect")) {
                scheduleReconnect("refresh_state_failed:$reason")
            }
        }
    }

    private fun maybeRecoverMissingListenerMembership(
        state: ListenTogetherRoomState,
        reason: String
    ) {
        val snapshot = _sessionState.value
        val userUuid = snapshot.userUuid ?: return
        if (isCurrentUserController(snapshot)) return
        if (state.roomStatus == ListenTogetherRoomStatuses.CLOSED) return
        if (state.members.any { it.userUuid.ifBlank { it.userId.orEmpty() } == userUuid }) return
        NPLogger.w(
            TAG,
            "maybeRecoverMissingListenerMembership(): userUuid=$userUuid missing from roomId=${state.roomId}, reason=$reason"
        )
        triggerListenerMembershipRecovery("$reason:missing_member")
    }

    private fun maybeRecoverFromFatalMembershipError(
        errorMessage: String?,
        reason: String
    ): Boolean {
        val normalized = errorMessage?.trim()?.lowercase().orEmpty()
        if (
            "member not in room" !in normalized &&
            "member missing" !in normalized
        ) {
            return false
        }
        NPLogger.w(TAG, "maybeRecoverFromFatalMembershipError(): reason=$reason, error=$errorMessage")
        return triggerListenerMembershipRecovery("$reason:$normalized")
    }

    private fun tryRecoverMembershipBeforeReconnect(reason: String): Boolean {
        val snapshot = _sessionState.value
        if (isCurrentUserController(snapshot)) return false
        return triggerListenerMembershipRecovery(reason)
    }

    private fun triggerListenerMembershipRecovery(reason: String): Boolean {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl
        val roomId = snapshot.roomId
        val userUuid = snapshot.userUuid
        val nickname = snapshot.nickname
        if (baseUrl.isNullOrBlank() || roomId.isNullOrBlank() || userUuid.isNullOrBlank() || nickname.isNullOrBlank()) {
            NPLogger.d(TAG, "triggerListenerMembershipRecovery(): skipped, missing session, reason=$reason")
            return false
        }
        if (isCurrentUserController(snapshot)) {
            NPLogger.d(TAG, "triggerListenerMembershipRecovery(): skipped, current user is controller")
            return false
        }
        if (membershipRecoveryJob?.isActive == true) {
            NPLogger.d(TAG, "triggerListenerMembershipRecovery(): already running, reason=$reason")
            return true
        }
        reconnectEnabled = true
        reconnectJob?.cancel()
        reconnectJob = null
        pendingStateRefreshAfterReconnect = true
        stopHeartbeat()
        webSocketClient.disconnect(code = 1000, reason = "listener_recovering")
        _sessionState.value = snapshot.copy(
            connectionState = ListenTogetherConnectionState.CONNECTING,
            lastError = "一起听连接已失效，正在重新加入房间"
        )
        membershipRecoveryJob = scope.launch {
            try {
                NPLogger.w(
                    TAG,
                    "triggerListenerMembershipRecovery(): rejoin roomId=$roomId, userUuid=$userUuid, reason=$reason"
                )
                joinRoom(baseUrl, roomId, userUuid, nickname)
                connectWebSocket()
            } catch (error: Throwable) {
                val resolvedError = error.message ?: error.javaClass.simpleName
                NPLogger.e(
                    TAG,
                    "triggerListenerMembershipRecovery(): failed, roomId=$roomId, userUuid=$userUuid, reason=$reason, error=$resolvedError",
                    error
                )
                _sessionState.value = _sessionState.value.copy(
                    connectionState = ListenTogetherConnectionState.DISCONNECTED,
                    lastError = resolvedError
                )
                if (handleTerminalReconnectFailure(resolvedError, "listener_membership_recovery_failed")) {
                    return@launch
                }
                scheduleReconnect("listener_membership_recovery_failed:$reason")
            } finally {
                membershipRecoveryJob = null
            }
        }
        return true
    }

    private fun handleTerminalReconnectFailure(
        errorMessage: String?,
        reason: String
    ): Boolean {
        if (!isTerminalReconnectError(errorMessage)) {
            return false
        }
        NPLogger.w(
            TAG,
            "handleTerminalReconnectFailure(): stop reconnect, reason=$reason, error=$errorMessage"
        )
        closeRoomLocally(errorMessage ?: "listen_together_unavailable")
        return true
    }

    private fun isTerminalReconnectError(errorMessage: String?): Boolean {
        val normalized = errorMessage?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return isUnauthorizedReconnectError(normalized) ||
            isClosedRoomReconnectError(normalized) ||
            isMissingRoomReconnectError(normalized)
    }

    private fun noteOutboundSync() {
        lastOutboundSyncAtMs = SystemClock.elapsedRealtime()
    }

    private fun currentRole(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): String? {
        return resolveSessionRole(
            sessionUserId = sessionState.userUuid,
            fallbackRole = sessionState.role,
            state = _roomState.value
        )
    }

    private fun isCurrentUserController(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): Boolean = currentRole(sessionState) == "controller"

    private fun resolveSessionRole(
        sessionUserId: String?,
        fallbackRole: String?,
        state: ListenTogetherRoomState?
    ): String? {
        val normalizedUserId = sessionUserId?.trim()?.takeIf { it.isNotBlank() }
        val controllerUserId = state?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
            ?: state?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
        return when {
            normalizedUserId != null && controllerUserId != null -> {
                if (normalizedUserId == controllerUserId) "controller" else "listener"
            }

            else -> fallbackRole
        }
    }

    private fun hasRecentOutboundEvent(eventId: String): Boolean = synchronized(recentEventLock) {
        recentOutboundEventIds.contains(eventId)
    }

    private fun hasRecentInboundEvent(eventId: String): Boolean = synchronized(recentEventLock) {
        recentInboundEventIds.contains(eventId)
    }

    private fun markOutboundEvent(eventId: String?) {
        if (eventId.isNullOrBlank()) return
        synchronized(recentEventLock) {
            recentOutboundEventIds.add(eventId)
            trimRecentEvents(recentOutboundEventIds)
        }
    }

    private fun markInboundEvent(eventId: String?) {
        if (eventId.isNullOrBlank()) return
        synchronized(recentEventLock) {
            recentInboundEventIds.add(eventId)
            trimRecentEvents(recentInboundEventIds)
        }
    }

    private fun trimRecentEvents(events: LinkedHashSet<String>) {
        while (events.size > MAX_RECENT_EVENT_IDS) {
            val oldest = events.firstOrNull() ?: break
            events.remove(oldest)
        }
    }

    private fun closeRoomLocally(reason: String?) {
        val snapshot = _sessionState.value
        NPLogger.w(
            TAG,
            "closeRoomLocally(): roomId=${snapshot.roomId}, role=${snapshot.role}, reason=$reason, lastAppliedVersion=$lastAppliedRoomVersion"
        )
        reconnectEnabled = false
        reconnectAttempt = 0
        pendingStateRefreshAfterReconnect = false
        cancelListenTogetherBackgroundJobs(reconnectJob, membershipRecoveryJob)
        reconnectJob = null
        membershipRecoveryJob = null
        cancelControllerLinkResolve()
        stopHeartbeat()
        stopSyncWatchdog()
        lastOutboundSyncAtMs = 0L
        lastRequestedLinkStableKey = null
        lastRequestedLinkAtElapsedMs = 0L
        lastAppliedRoomVersion = -1L
        lastControllerLocalControlAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        pendingMemberControlRequest = null
        resetListenerRecoveryState()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        webSocketClient.disconnect(code = 1000, reason = "room_closed")
        _roomState.value = null
        _sessionState.value = ListenTogetherSessionState(
            baseUrl = snapshot.baseUrl,
            userUuid = snapshot.userUuid,
            nickname = snapshot.nickname,
            connectionState = ListenTogetherConnectionState.DISCONNECTED,
            lastError = reason,
            roomNotice = reason
        )
    }

    private fun roomNoticeForState(
        state: ListenTogetherRoomState?,
        fallbackMessage: String? = null
    ): String? {
        state ?: return fallbackMessage
        return when (state.roomStatus) {
            ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> {
                val offlineSince = state.controllerOfflineSince ?: return fallbackMessage ?: "controller_offline"
                val timeoutAt = offlineSince + CONTROLLER_GRACE_PERIOD_MS
                val remainingMs = (timeoutAt - System.currentTimeMillis()).coerceAtLeast(0L)
                val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(0L)
                "controller_offline:${remainingMinutes + 1}"
            }

            ListenTogetherRoomStatuses.CLOSED -> fallbackMessage ?: state.closedReason ?: "room_closed"
            else -> fallbackMessage
        }
    }

    private fun nextEventId(): String = "evt-${System.currentTimeMillis()}-${UUID.randomUUID()}"

    private fun noteControllerLocalControl(command: PlaybackCommand) {
        if (command.source != PlaybackCommandSource.LOCAL) return
        if (!isCurrentUserController()) return
        if (command.type !in CONTROLLED_PLAYBACK_COMMAND_TYPES) return
        lastControllerLocalControlAtElapsedMs = SystemClock.elapsedRealtime()
        NPLogger.d(
            TAG,
            "noteControllerLocalControl(): type=${command.type}, positionMs=${command.positionMs}, currentIndex=${command.currentIndex}"
        )
    }

    private fun applySoftDriftCorrection(
        driftMs: Long,
        signedDriftMs: Long,
        allowSoftSync: Boolean
    ) {
        if (!allowSoftSync || isCurrentUserController()) {
            NPLogger.d(
                TAG,
                "applySoftDriftCorrection(): reset sync rate, allowSoftSync=$allowSoftSync, isController=${isCurrentUserController()}, driftMs=$driftMs, signedDriftMs=$signedDriftMs"
            )
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            return
        }
        if (driftMs !in SOFT_SYNC_MIN_DRIFT_MS..<PLAYING_DRIFT_FORCE_SYNC_MS) {
            NPLogger.d(
                TAG,
                "applySoftDriftCorrection(): drift outside soft-sync window, driftMs=$driftMs, signedDriftMs=$signedDriftMs"
            )
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            return
        }
        val rate = when {
            signedDriftMs >= SOFT_SYNC_FAST_DRIFT_MS -> 1.05f
            signedDriftMs > 0L -> 1.03f
            signedDriftMs <= -SOFT_SYNC_FAST_DRIFT_MS -> 0.95f
            else -> 0.97f
        }
        NPLogger.d(
            TAG,
            "applySoftDriftCorrection(): driftMs=$driftMs, signedDriftMs=$signedDriftMs, applyRate=$rate"
        )
        PlayerManager.setListenTogetherSyncPlaybackRate(rate)
    }

    private fun shouldIgnoreUnexpectedZeroPositionRollback(
        causeType: String?,
        desiredPlaying: Boolean,
        expectedPositionMs: Long,
        localPositionMs: Long,
        playbackContextChanged: Boolean,
        targetIndexChanged: Boolean
    ): Boolean {
        if (causeType !in PASSIVE_POSITION_UPDATE_TYPES) return false
        if (!desiredPlaying) return false
        if (expectedPositionMs > 0L) return false
        if (localPositionMs < UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS) return false
        if (playbackContextChanged || targetIndexChanged) return false
        NPLogger.w(
            TAG,
            "shouldIgnoreUnexpectedZeroPositionRollback(): ignore suspicious rollback, causeType=$causeType, expectedPositionMs=$expectedPositionMs, localPositionMs=$localPositionMs"
        )
        return true
    }

    private fun publishControllerHeartbeatIfNeeded(
        force: Boolean = false,
        reason: String
    ) {
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) return
        if (!isCurrentUserController(snapshot)) return
        val state = _roomState.value ?: return
        if (state.roomStatus == ListenTogetherRoomStatuses.CLOSED) return
        val heartbeat = buildHeartbeatEvent(
            state = currentLocalPlaybackStateName(),
            positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L),
            includeQueue = force
        )
        if (!force && heartbeat.track == null) return
        markOutboundEvent(heartbeat.eventId)
        noteOutboundSync()
        NPLogger.d(
            TAG,
            "publishControllerHeartbeatIfNeeded(): reason=$reason, eventId=${heartbeat.eventId}, track=${heartbeat.track?.stableKey}, positionMs=${heartbeat.positionMs}"
        )
        sendControlEventPureWebSocket(heartbeat, "publish_controller_heartbeat:$reason")
    }

    private fun publishControllerLinkReadyIfPossible(
        stableKey: String,
        reason: String,
        streamUrlOverride: String? = null
    ): Boolean {
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) return false
        if (!isCurrentUserController(snapshot)) return false
        if (!_roomState.value?.settings.normalized().shareAudioLinks) return false
        val event = buildLinkReadyEvent(
            stableKey = stableKey,
            positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L),
            streamUrlOverride = streamUrlOverride
        ) ?: run {
            NPLogger.d(
                TAG,
                "publishControllerLinkReadyIfPossible(): skipped because buildLinkReadyEvent returned null, stableKey=$stableKey, reason=$reason"
            )
            return false
        }
        markOutboundEvent(event.eventId)
        noteOutboundSync()
        NPLogger.d(
            TAG,
            "publishControllerLinkReadyIfPossible(): reason=$reason, eventId=${event.eventId}, stableKey=$stableKey"
        )
        return sendControlEventPureWebSocket(event, "publish_link_ready:$reason")
    }

    private fun resolveAndPublishControllerLink(
        stableKey: String,
        reason: String
    ) {
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) return
        if (!isCurrentUserController(snapshot)) return
        if (!_roomState.value?.settings.normalized().shareAudioLinks) return
        val song = PlayerManager.currentSongFlow.value ?: run {
            NPLogger.w(
                TAG,
                "resolveAndPublishControllerLink(): skipped because currentSong missing, stableKey=$stableKey, reason=$reason"
            )
            return
        }
        val songStableKey = song.toListenTogetherTrackOrNull()?.stableKey
        if (songStableKey != stableKey) {
            NPLogger.d(
                TAG,
                "resolveAndPublishControllerLink(): skipped because current stableKey mismatch, expected=$stableKey, actual=$songStableKey, reason=$reason"
            )
            return
        }
        if (controllerLinkResolveJob?.isActive == true && controllerLinkResolveStableKey == stableKey) {
            NPLogger.d(
                TAG,
                "resolveAndPublishControllerLink(): already resolving, stableKey=$stableKey, reason=$reason"
            )
            return
        }
        controllerLinkResolveJob?.cancel()
        controllerLinkResolveStableKey = stableKey
        controllerLinkResolveJob = scope.launch {
            try {
                NPLogger.d(
                    TAG,
                    "resolveAndPublishControllerLink(): resolving shareable stream, stableKey=$stableKey, reason=$reason"
                )
                val result = PlayerManager.resolveShareableListenTogetherStreamUrl(song)
                val streamUrl = (result as? SongUrlResult.Success)
                    ?.url
                    ?.let(::normalizedDirectStreamUrl)
                if (streamUrl == null) {
                    NPLogger.w(
                        TAG,
                        "resolveAndPublishControllerLink(): no shareable stream resolved, stableKey=$stableKey, result=${result::class.simpleName}, reason=$reason"
                    )
                    return@launch
                }
                val latestSongStableKey = PlayerManager.currentSongFlow.value
                    ?.toListenTogetherTrackOrNull()
                    ?.stableKey
                if (latestSongStableKey != stableKey) {
                    NPLogger.d(
                        TAG,
                        "resolveAndPublishControllerLink(): drop stale resolved stream, expected=$stableKey, actual=$latestSongStableKey, reason=$reason"
                    )
                    return@launch
                }
                publishControllerLinkReadyIfPossible(
                    stableKey = stableKey,
                    reason = "resolved:$reason",
                    streamUrlOverride = streamUrl
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.w(
                    TAG,
                    "resolveAndPublishControllerLink(): failed, stableKey=$stableKey, reason=$reason, error=${e.message}"
                )
            } finally {
                if (controllerLinkResolveStableKey == stableKey) {
                    controllerLinkResolveStableKey = null
                    controllerLinkResolveJob = null
                }
            }
        }
    }

    private fun maybeRequestControllerLink(
        state: ListenTogetherRoomState,
        causeType: String?,
        force: Boolean = false
    ) {
        val snapshot = _sessionState.value
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) return
        if (isCurrentUserController(snapshot)) return
        if (!state.settings.normalized().shareAudioLinks) return
        if (state.roomStatus != ListenTogetherRoomStatuses.ACTIVE) return
        val targetTrack = state.track ?: state.queue.getOrNull(state.currentIndex) ?: return
        if (!force && normalizedDirectStreamUrl(targetTrack.streamUrl) != null) {
            NPLogger.d(
                TAG,
                "maybeRequestControllerLink(): skip because direct stream already present, stableKey=${targetTrack.stableKey}, causeType=$causeType"
            )
            return
        }
        if (!force && hasUsableLocalDirectStreamForListenTogetherTrack(targetTrack)) {
            NPLogger.d(
                TAG,
                "maybeRequestControllerLink(): skip because listener already has direct stream, stableKey=${targetTrack.stableKey}, causeType=$causeType"
            )
            return
        }
        val stableKey = targetTrack.stableKey
        if (stableKey.isBlank()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            lastRequestedLinkStableKey == stableKey &&
            nowElapsedMs - lastRequestedLinkAtElapsedMs < LINK_REQUEST_THROTTLE_MS
        ) {
            NPLogger.d(
                TAG,
                "maybeRequestControllerLink(): throttled, stableKey=$stableKey, causeType=$causeType, delta=${nowElapsedMs - lastRequestedLinkAtElapsedMs}ms"
            )
            return
        }
        val event = buildRequestLinkEvent(
            stableKey = stableKey,
            currentIndex = state.currentIndex,
            track = targetTrack.withStreamUrl(null)
        )
        lastRequestedLinkStableKey = stableKey
        lastRequestedLinkAtElapsedMs = nowElapsedMs
        markOutboundEvent(event.eventId)
        NPLogger.d(
            TAG,
            "maybeRequestControllerLink(): causeType=$causeType, eventId=${event.eventId}, stableKey=$stableKey"
        )
        sendControlEventPureWebSocket(event, "request_controller_link:$causeType")
    }

    private fun maybePublishControllerRecoveryHeartbeat(message: ListenTogetherSocketEnvelope) {
        val snapshot = _sessionState.value
        if (!isCurrentUserController(snapshot)) return
        val state = message.state ?: return
        if (!state.settings.normalized().shareAudioLinks) return
        val cause = message.causedBy ?: return
        if (cause.userUuid == snapshot.userUuid) return
        if (cause.type == "REQUEST_LINK") {
            val stableKey = message.requestTrackStableKey
                ?: message.track?.stableKey
                ?: state.track?.stableKey
                ?: return
            NPLogger.d(
                TAG,
                "maybePublishControllerRecoveryHeartbeat(): respond with LINK_READY, requester=${cause.userUuid}, stableKey=$stableKey"
            )
            val published = publishControllerLinkReadyIfPossible(
                stableKey = stableKey,
                reason = "recovery:REQUEST_LINK"
            )
            if (!published) {
                resolveAndPublishControllerLink(
                    stableKey = stableKey,
                    reason = "recovery:REQUEST_LINK"
                )
            }
            return
        }
        if (cause.type !in CONTROLLER_HEARTBEAT_RECOVERY_TYPES) return
        NPLogger.d(
            TAG,
            "maybePublishControllerRecoveryHeartbeat(): respond with HEARTBEAT, requester=${cause.userUuid}, causeType=${cause.type}"
        )
        publishControllerHeartbeatIfNeeded(force = true, reason = "recovery:${cause.type}")
    }

    private fun isLocalPlaybackTransportActive(): Boolean {
        return runCatching { PlayerManager.isTransportActive() }
            .getOrDefault(PlayerManager.isPlayingFlow.value || PlayerManager.playWhenReadyFlow.value)
    }

    private fun currentLocalPlaybackStateName(): String {
        return if (isLocalPlaybackTransportActive() || PlayerManager.isPlayingFlow.value) {
            "playing"
        } else {
            "paused"
        }
    }

    private fun shouldReloadForAuthoritativeStreamUrl(
        targetSong: SongItem,
        currentSong: SongItem?
    ): Boolean {
        if (isCurrentUserController()) return false
        if (!_roomState.value?.settings.normalized().shareAudioLinks) return false
        if (currentSong?.sameTrackAs(targetSong) != true) return false
        return shouldReloadListenTogetherAuthoritativeStream(
            remoteStreamUrl = targetSong.streamUrl,
            localTrackStreamUrl = currentSong.streamUrl,
            localResolvedStreamUrl = PlayerManager.currentMediaUrlFlow.value
        )
    }

    private fun hasUsableLocalDirectStreamForListenTogetherTrack(track: ListenTogetherTrack): Boolean {
        val currentSong = PlayerManager.currentSongFlow.value ?: return false
        if (!currentSong.sameTrackAs(track.toSongItem())) return false
        return normalizedDirectStreamUrl(currentSong.streamUrl) != null ||
            normalizedDirectStreamUrl(PlayerManager.currentMediaUrlFlow.value) != null
    }

    companion object {
        private const val TAG = "NERI-ListenTogether"
        private const val PLAYING_DRIFT_FORCE_SYNC_MS = 2_500L
        private const val HEARTBEAT_DRIFT_FORCE_SYNC_MS = 5_000L
        private const val PAUSED_DRIFT_FORCE_SYNC_MS = 800L
        private const val TRACK_SWITCH_FORCE_SYNC_MS = 500L
        private const val MAX_RECENT_EVENT_IDS = 128
        private const val CONTROLLER_GRACE_PERIOD_MS = 10 * 60 * 1000L
        private const val HEARTBEAT_POLL_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_IDLE_THRESHOLD_MS = 12_000L
        private const val LINK_REQUEST_THROTTLE_MS = 4_000L
        private const val CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS = 1_200L
        private const val CONTROLLER_WATCHDOG_HEARTBEAT_INTERVAL_MS = 4_000L
        private const val SYNC_WATCHDOG_INTERVAL_MS = 8_000L
        private const val LISTENER_STATE_REFRESH_INTERVAL_MS = 7_500L
        private const val LISTENER_PLAYBACK_STALL_TIMEOUT_MS = 8_000L
        private const val LISTENER_PLAYBACK_STALL_RECOVERY_COOLDOWN_MS = 12_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_RETRY_INTERVAL_MS = 3_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_TTL_MS = 18_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_MAX_ATTEMPTS = 4
        private const val PENDING_MEMBER_SEEK_SATISFIED_DRIFT_MS = 1_500L
        private const val TRACK_FINISHED_LEGACY_FALLBACK_TTL_MS = 15_000L
        private const val SOFT_SYNC_MIN_DRIFT_MS = 600L
        private const val SOFT_SYNC_FAST_DRIFT_MS = 1_500L
        private const val UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS = 2_000L
        private val CONTROLLER_HEARTBEAT_RECOVERY_TYPES = setOf(
            "MEMBER_JOINED",
            "PLAY",
            "PAUSE",
            "SEEK",
            "PLAYBACK_MODE",
            "SET_TRACK",
            "SET_QUEUE",
            "REQUEST_PLAY",
            "REQUEST_PAUSE",
            "REQUEST_SEEK",
            "REQUEST_PLAYBACK_MODE",
            "REQUEST_SET_TRACK"
        )
        private val PASSIVE_POSITION_UPDATE_TYPES = setOf(
            "HEARTBEAT",
            "WATCHDOG",
            "WATCHDOG_STALL",
            "LINK_READY",
            "MEMBER_JOINED",
            "MEMBER_LEFT"
        )
        private val CONTROLLED_PLAYBACK_COMMAND_TYPES = setOf(
            "PLAY_PLAYLIST",
            "PLAY_FROM_QUEUE",
            "NEXT",
            "PREVIOUS",
            "PLAY",
            "PAUSE",
            "PLAYBACK_MODE",
            "SEEK"
        )
        private val REQUEST_CONTROL_EVENT_TYPES = setOf(
            "REQUEST_PLAY",
            "REQUEST_PAUSE",
            "REQUEST_SEEK",
            "REQUEST_PLAYBACK_MODE",
            "REQUEST_SET_TRACK"
        )
        private val TRACK_BOUND_REQUEST_CONTROL_EVENT_TYPES = setOf(
            "REQUEST_PLAY",
            "REQUEST_PAUSE",
            "REQUEST_SEEK"
        )
        private fun isUnauthorizedReconnectError(normalized: String): Boolean {
            return "unauthorized" in normalized
        }

        private fun isClosedRoomReconnectError(normalized: String): Boolean {
            return "room closed" in normalized || "http=410" in normalized || "(410)" in normalized
        }

        private fun isMissingRoomReconnectError(normalized: String): Boolean {
            return "room not initialized" in normalized ||
                "\"error\":\"room not initialized\"" in normalized ||
                "not found in do" in normalized ||
                "\"error\":\"not found in do\"" in normalized
        }

        private const val MAX_RECONNECT_ATTEMPTS = 15

        private fun reconnectDelayMs(attempt: Int): Long {
            val baseMs = when (attempt) {
                1 -> 1_500L
                2 -> 3_000L
                3 -> 5_000L
                4 -> 8_000L
                else -> 12_000L
            }
            val jitter = (baseMs * 0.2 * (Math.random() * 2.0 - 1.0)).toLong()
            return baseMs + jitter
        }
    }
}

private fun ListenTogetherPlaybackState.expectedPositionMs(
    nowMs: Long = System.currentTimeMillis(),
    serverClockOffsetMs: Long = 0L
): Long {
    val correctedNowMs = nowMs + serverClockOffsetMs
    return if (state == "playing") {
        (basePositionMs + ((correctedNowMs - baseTimestampMs) * playbackRate)).toLong().coerceAtLeast(0L)
    } else {
        basePositionMs.coerceAtLeast(0L)
    }
}

private fun String?.redactListenTogetherWsUrlForLog(): String? {
    val raw = this ?: return null
    val parsed = raw.toHttpUrlOrNull() ?: return raw.substringBefore('?') + "?token=<redacted>"
    val redacted = parsed.newBuilder()
        .setQueryParameter("token", "<redacted>")
        .build()
    return redacted.toString()
}

internal fun isListenTogetherSeekControlSatisfied(
    playback: ListenTogetherPlaybackState,
    requestedPositionMs: Long,
    satisfiedDriftMs: Long = 1_500L
): Boolean {
    return abs(playback.basePositionMs.coerceAtLeast(0L) - requestedPositionMs.coerceAtLeast(0L)) <= satisfiedDriftMs
}

private fun ListenTogetherRoomState.currentStableKey(): String? {
    return track?.stableKey ?: queue.getOrNull(currentIndex)?.stableKey
}

private fun ListenTogetherEvent.requestedStableKey(): String? {
    return requestTrackStableKey
        ?: track?.stableKey
        ?: queue?.getOrNull(currentIndex ?: -1)?.stableKey
}

private fun ListenTogetherRoomState.targetSongItem(): SongItem? {
    return (track ?: queue.getOrNull(currentIndex))?.toSongItem()
}

internal fun resolveListenTogetherJoinAutoPauseCause(
    autoPauseOnJoin: Boolean,
    role: String?,
    state: ListenTogetherRoomState
): String? {
    if (!autoPauseOnJoin || role != "listener") return null
    return "JOIN_AUTO_PAUSE".takeIf { state.playback.state == "paused" }
}

private fun SongItem.sameTrackAs(other: SongItem): Boolean {
    return resolvedChannelId() == other.resolvedChannelId() &&
        resolvedAudioId() == other.resolvedAudioId() &&
        resolvedSubAudioId() == other.resolvedSubAudioId() &&
        resolvedPlaylistContextId() == other.resolvedPlaylistContextId()
}

private fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean {
    if (size != other.size) return false
    return indices.all { index -> this[index].sameTrackAs(other[index]) }
}

private fun List<SongItem>.indexOfTrack(track: SongItem?): Int {
    track ?: return -1
    return indexOfFirst { candidate -> candidate.sameTrackAs(track) }
}

private fun List<SongItem>.toShareableQueueSnapshot(
    currentIndex: Int,
    roomSettings: ListenTogetherRoomSettings? = null,
    includeResolvedStreamUrl: Boolean = true
): Pair<List<ListenTogetherTrack>, Int> {
    if (isEmpty()) return emptyList<ListenTogetherTrack>() to 0

    val targetSong = getOrNull(currentIndex.coerceIn(0, lastIndex))
    val targetStableKey = targetSong?.toListenTogetherTrackOrNull()?.stableKey
    val currentStreamUrl = currentResolvedStreamUrl().takeIf { includeResolvedStreamUrl }
    val shareableQueue = mapNotNull { song ->
        song.toListenTogetherTrackOrNull()?.let { track ->
            if (roomSettings.normalized().shareAudioLinks && track.stableKey == targetStableKey) {
                track.withStreamUrl(currentStreamUrl)
            } else {
                track
            }
        }
    }.boundedAroundStableKey(targetStableKey)
    if (shareableQueue.isEmpty()) return shareableQueue to 0

    val resolvedCurrentIndex = targetStableKey?.let { stableKey ->
        shareableQueue.indexOfFirst { it.stableKey == stableKey }.takeIf { it >= 0 }
    } ?: 0

    return shareableQueue to resolvedCurrentIndex
}

internal fun List<ListenTogetherTrack>.boundedAroundStableKey(
    targetStableKey: String?
): List<ListenTogetherTrack> {
    if (size <= LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE) return this
    val targetIndex = targetStableKey
        ?.let { stableKey -> indexOfFirst { it.stableKey == stableKey } }
        ?.takeIf { it >= 0 }
        ?: 0
    val maxSize = LISTEN_TOGETHER_MAX_SHAREABLE_QUEUE_SIZE
    val halfWindow = maxSize / 2
    val start = (targetIndex - halfWindow).coerceIn(0, size - maxSize)
    return subList(start, start + maxSize)
}

private fun ListenTogetherRoomSettings?.normalized(): ListenTogetherRoomSettings {
    return this ?: ListenTogetherRoomSettings()
}

private fun currentResolvedStreamUrl(): String? {
    val candidate = PlayerManager.currentMediaUrlFlow.value?.trim().orEmpty()
    if (candidate.isBlank()) return null
    if (candidate.startsWith("https://", ignoreCase = true) || candidate.startsWith("http://", ignoreCase = true)) {
        return candidate
    }
    return null
}

private fun List<ListenTogetherTrack>.mergeCurrentTrack(
    currentIndex: Int,
    currentTrack: ListenTogetherTrack?
): List<ListenTogetherTrack> {
    val replacement = currentTrack ?: return this
    if (currentIndex !in indices) return this
    if (this[currentIndex] == replacement) return this
    return toMutableList().also { it[currentIndex] = replacement }
}

private fun normalizedDirectStreamUrl(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isBlank()) return null
    return if (
        candidate.startsWith("https://", ignoreCase = true) ||
        candidate.startsWith("http://", ignoreCase = true)
    ) {
        candidate
    } else {
        null
    }
}

internal fun shouldReloadListenTogetherAuthoritativeStream(
    remoteStreamUrl: String?,
    localTrackStreamUrl: String?,
    localResolvedStreamUrl: String?
): Boolean {
    val remote = normalizedDirectStreamUrl(remoteStreamUrl) ?: return false
    if (normalizedDirectStreamUrl(localTrackStreamUrl) != null) return false
    return remote != normalizedDirectStreamUrl(localResolvedStreamUrl)
}

internal fun shouldWaitForListenTogetherAuthoritativeStreamPlayback(
    playerWaitingForAuthoritativeStream: Boolean,
    localTrackMatchesTarget: Boolean,
    localTrackStreamUrl: String?,
    localResolvedStreamUrl: String?
): Boolean {
    if (!playerWaitingForAuthoritativeStream) return false
    if (!localTrackMatchesTarget) return true
    return normalizedDirectStreamUrl(localTrackStreamUrl) == null &&
        normalizedDirectStreamUrl(localResolvedStreamUrl) == null
}

internal fun cancelListenTogetherBackgroundJobs(vararg jobs: Job?) {
    jobs.forEach { it?.cancel() }
}
