package moe.ouom.neriplayer.listentogether

import android.os.Looper
import android.os.SystemClock
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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.url.resolveShareableListenTogetherStreamUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.compat.buildTrackFinishedLegacyFallbackEvent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherMemberControlTargetCurrent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherPendingMemberControlSatisfied
import moe.ouom.neriplayer.listentogether.compat.isUnsupportedTrackFinishedEventError
import moe.ouom.neriplayer.listentogether.compat.shouldSuppressListenerControlWhileAwaitingStream
import moe.ouom.neriplayer.listentogether.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.listentogether.control.buildListenTogetherForwardedControlSyntheticState
import moe.ouom.neriplayer.listentogether.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.listentogether.control.controllerHeartbeatRecoveryTypes
import moe.ouom.neriplayer.listentogether.control.nextListenTogetherEventId
import moe.ouom.neriplayer.listentogether.control.passivePositionUpdateTypes
import moe.ouom.neriplayer.listentogether.control.requestControlEventTypes
import moe.ouom.neriplayer.listentogether.control.trackBoundRequestControlEventTypes
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.lifecycle.cancelListenTogetherBackgroundJobs
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrl
import moe.ouom.neriplayer.listentogether.network.http.ListenTogetherApi
import moe.ouom.neriplayer.listentogether.network.reconnect.LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS
import moe.ouom.neriplayer.listentogether.network.reconnect.isTerminalListenTogetherReconnectError
import moe.ouom.neriplayer.listentogether.network.reconnect.listenTogetherReconnectDelayMs
import moe.ouom.neriplayer.listentogether.network.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.listentogether.network.ws.buildListenTogetherWsUrl
import moe.ouom.neriplayer.listentogether.network.ws.redactListenTogetherWsUrlForLog
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherListenerStallRecovery
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherPlayerStateApplier
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherPlayerStateApplierConfig
import moe.ouom.neriplayer.listentogether.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.listentogether.playback.requestedStableKey
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.playback.shouldWaitForListenTogetherAuthoritativeStreamPlayback
import moe.ouom.neriplayer.listentogether.playback.targetSongItem
import moe.ouom.neriplayer.listentogether.playback.toShareableQueueSnapshot
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherControlResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherInitialSnapshot
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherStateResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.listentogether.session.AcceptedRoomState
import moe.ouom.neriplayer.listentogether.session.LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.listentogether.session.PendingMemberControlRequest
import moe.ouom.neriplayer.listentogether.session.PendingTrackFinishedLegacyFallback
import moe.ouom.neriplayer.listentogether.session.RoomStateSource
import moe.ouom.neriplayer.listentogether.session.latestListenTogetherAcceptedRoomVersion
import moe.ouom.neriplayer.listentogether.session.normalized
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherControlBlockReason
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherHeartbeatIntervalMs
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherRoomNotice
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.listentogether.session.retriedAt
import moe.ouom.neriplayer.listentogether.session.shouldApplyListenTogetherRoomStateToPlayer
import moe.ouom.neriplayer.listentogether.session.shouldDropListenTogetherControllerLocalEcho
import moe.ouom.neriplayer.listentogether.session.shouldDeferListenTogetherIncomingStateForLocalTrackFinish
import moe.ouom.neriplayer.listentogether.session.shouldIgnoreListenTogetherIncomingState
import moe.ouom.neriplayer.listentogether.session.shouldRepairListenTogetherListenerState
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherNickname
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherRoomId
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherUserUuid
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
    private val roomStateLock = Any()

    @Volatile
    private var started = false

    private val recentEventTracker = ListenTogetherRecentEventTracker()
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
    private var lastWebSocketMessageAtElapsedMs: Long = 0L
    @Volatile
    private var pendingRoomRepairVersion: Long = -1L
    @Volatile
    private var activeRoomIdForStateAcceptance: String? = null
    @Volatile
    private var controllerLinkResolveStableKey: String? = null
    @Volatile
    private var controllerLinkResolveJob: Job? = null
    @Volatile
    private var pingSentAtWallMs: Long = 0L
    @Volatile
    private var pingSentAtElapsedMs: Long = 0L
    @Volatile
    private var estimatedServerClockOffsetMs: Long = 0L

    private val clientInstanceId = UUID.randomUUID().toString()
    private val clientSequence = AtomicLong(0L)

    private val _sessionState = MutableStateFlow(ListenTogetherSessionState())
    val sessionState: StateFlow<ListenTogetherSessionState> = _sessionState.asStateFlow()

    private val _roomState = MutableStateFlow<ListenTogetherRoomState?>(null)
    val roomState: StateFlow<ListenTogetherRoomState?> = _roomState.asStateFlow()

    private val listenerStallRecovery = ListenTogetherListenerStallRecovery(
        stallTimeoutMs = LISTENER_PLAYBACK_STALL_TIMEOUT_MS,
        recoveryCooldownMs = LISTENER_PLAYBACK_STALL_RECOVERY_COOLDOWN_MS
    )

    private val eventFactory = ListenTogetherEventFactory(
        roomStateProvider = { _roomState.value },
        isControllerProvider = { isCurrentUserController() },
        eventIdFactory = ::nextEventId,
        clientInstanceIdProvider = { clientInstanceId },
        clientSequenceFactory = ::nextClientSequence,
        localPlaybackStateNameProvider = ::currentLocalPlaybackStateName,
        localTransportActiveProvider = ::isLocalPlaybackTransportActive
    )

    private val playerStateApplier = ListenTogetherPlayerStateApplier(
        config = ListenTogetherPlayerStateApplierConfig(
            tag = TAG,
            trackSwitchForceSyncMs = TRACK_SWITCH_FORCE_SYNC_MS,
            heartbeatDriftForceSyncMs = HEARTBEAT_DRIFT_FORCE_SYNC_MS,
            playingDriftForceSyncMs = PLAYING_DRIFT_FORCE_SYNC_MS,
            pausedDriftForceSyncMs = PAUSED_DRIFT_FORCE_SYNC_MS,
            softSyncMinDriftMs = SOFT_SYNC_MIN_DRIFT_MS,
            softSyncFastDriftMs = SOFT_SYNC_FAST_DRIFT_MS,
            trackSwitchGracePeriodMs = TRACK_SWITCH_GRACE_PERIOD_MS,
            zeroPositionRollbackGuardMs = UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS
        ),
        roomStateProvider = { _roomState.value },
        isControllerProvider = { isCurrentUserController() },
        serverClockOffsetProvider = { estimatedServerClockOffsetMs }
    )

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
        val initialSnapshot = ListenTogetherInitialSnapshot(
            queue = queueTracks,
            currentIndex = resolvedCurrentIndex,
            track = queueTracks.getOrNull(resolvedCurrentIndex),
            settings = roomSettings.normalized(),
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            repeatMode = PlayerManager.repeatModeFlow.value,
            shuffleEnabled = PlayerManager.shuffleModeFlow.value
        )
        val response = api.createRoom(
            baseUrl = baseUrl,
            userUuid = validatedUserUuid,
            nickname = validatedNickname,
            initialSnapshot = initialSnapshot
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
        val sentAtElapsedMs = SystemClock.elapsedRealtime()
        val sentAtWallMs = System.currentTimeMillis()
        val response = api.getRoomState(baseUrl, validatedRoomId)
        updateServerClockOffsetFromRoundTrip(
            serverNowMs = response.serverNowMs,
            sentAtWallMs = sentAtWallMs,
            sentAtElapsedMs = sentAtElapsedMs,
            reason = "http_state"
        )
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
                    if (!reconnectEnabled || activeRoomIdForStateAcceptance.isNullOrBlank()) {
                        NPLogger.d(TAG, "websocket.onOpen(): drop inactive session")
                        webSocketClient.disconnect(code = 1000, reason = "inactive_session")
                        return
                    }
                    lastWebSocketMessageAtElapsedMs = SystemClock.elapsedRealtime()
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
                    if (!recordWebSocketMessage(message)) {
                        NPLogger.d(
                            TAG,
                            "websocket.onMessage(): drop inactive room, roomId=${message.roomId ?: message.state?.roomId}"
                        )
                        return
                    }
                    NPLogger.d(
                        TAG,
                        "websocket.onMessage(): type=${message.type}, roomId=${message.roomId ?: message.state?.roomId}, version=${message.version ?: message.state?.version}, causedBy=${message.causedBy?.type}:${message.causedBy?.eventId}, ok=${message.ok}, message=${message.message}, resultError=${message.result?.error}"
                    )
                    if (message.type != "pong" && message.type != "np_pong") {
                        updateServerClockOffsetFromServerMessage(
                            serverNowMs = message.nowMs ?: message.result?.applied?.nowMs,
                            reason = message.type
                        )
                    }
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
                                if (isUnsupportedClockSyncPingError(resolvedError)) {
                                    NPLogger.d(TAG, "websocket.controlResult(): np_ping unsupported, fallback to legacy ping")
                                    pingSentAtElapsedMs = SystemClock.elapsedRealtime()
                                    pingSentAtWallMs = System.currentTimeMillis()
                                    webSocketClient.sendLegacyPing()
                                    return
                                }
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

                        "pong",
                        "np_pong" -> {
                            _sessionState.value = _sessionState.value.copy(lastError = null)
                            val serverNowMs = message.nowMs
                            updateServerClockOffsetFromPong(
                                serverNowMs = serverNowMs,
                                echoedSentAtElapsedMs = message.t
                            )
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
        synchronized(roomStateLock) {
            lastAppliedRoomVersion = -1L
            pendingRoomRepairVersion = -1L
        }
        lastControllerLocalControlAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        awaitingTrackFinishStableKey = null
        pendingTrackFinishedLegacyFallback = null
        pendingMemberControlRequest = null
        lastListenerStateRefreshAtElapsedMs = 0L
        lastWebSocketMessageAtElapsedMs = 0L
        pingSentAtWallMs = 0L
        pingSentAtElapsedMs = 0L
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
        synchronized(roomStateLock) {
            lastAppliedRoomVersion = -1L
            pendingRoomRepairVersion = -1L
            activeRoomIdForStateAcceptance = null
            _roomState.value = null
        }
        lastControllerLocalControlAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        awaitingTrackFinishStableKey = null
        pendingTrackFinishedLegacyFallback = null
        pendingMemberControlRequest = null
        lastListenerStateRefreshAtElapsedMs = 0L
        lastWebSocketMessageAtElapsedMs = 0L
        resetListenerRecoveryState()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "leaveRoom(): roomId=${_sessionState.value.roomId}, role=${_sessionState.value.role}")
        webSocketClient.disconnect()
        recentEventTracker.clear()
        val snapshot = _sessionState.value
        _sessionState.value = ListenTogetherSessionState(
            baseUrl = snapshot.baseUrl,
            userUuid = snapshot.userUuid,
            nickname = snapshot.nickname,
            connectionState = ListenTogetherConnectionState.DISCONNECTED
        )
    }

    fun sendPing(): Boolean {
        val sentAtElapsedMs = SystemClock.elapsedRealtime()
        pingSentAtElapsedMs = sentAtElapsedMs
        pingSentAtWallMs = System.currentTimeMillis()
        return webSocketClient.sendPing(sentAtElapsedMs)
    }

    private fun updateServerClockOffsetFromPong(
        serverNowMs: Long?,
        echoedSentAtElapsedMs: Long?
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallMs = System.currentTimeMillis()
        val echoedElapsedMs = echoedSentAtElapsedMs?.takeIf { it > 0L }
        val sentAtElapsedMs = echoedElapsedMs ?: pingSentAtElapsedMs
        val sentAtWallMs = when {
            echoedElapsedMs != null -> nowWallMs - (nowElapsedMs - echoedElapsedMs)
            else -> pingSentAtWallMs
        }
        updateServerClockOffsetFromRoundTrip(
            serverNowMs = serverNowMs,
            sentAtWallMs = sentAtWallMs,
            sentAtElapsedMs = sentAtElapsedMs,
            nowWallMs = nowWallMs,
            nowElapsedMs = nowElapsedMs,
            reason = "pong"
        )
    }

    private fun updateServerClockOffsetFromServerMessage(
        serverNowMs: Long?,
        reason: String
    ) {
        updateServerClockOffsetFromRoundTrip(
            serverNowMs = serverNowMs,
            sentAtWallMs = 0L,
            sentAtElapsedMs = 0L,
            reason = reason
        )
    }

    private fun updateServerClockOffsetFromRoundTrip(
        serverNowMs: Long?,
        sentAtWallMs: Long,
        sentAtElapsedMs: Long,
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        reason: String
    ) {
        if (serverNowMs == null || serverNowMs <= 0L) return
        val hasRoundTrip = sentAtWallMs > 0L && sentAtElapsedMs > 0L
        val rtt = if (hasRoundTrip) nowElapsedMs - sentAtElapsedMs else 0L
        if (rtt < 0L) return
        if (rtt > CLOCK_SYNC_MAX_RTT_MS) {
            NPLogger.w(TAG, "updateServerClockOffsetFromRoundTrip(): ignore stale sample, reason=$reason, rtt=$rtt")
            return
        }
        val clientReferenceWallMs = if (hasRoundTrip) {
            sentAtWallMs + rtt / 2
        } else {
            nowWallMs
        }
        val newOffset = serverNowMs - clientReferenceWallMs
        val prev = estimatedServerClockOffsetMs
        estimatedServerClockOffsetMs = when (prev) {
            0L -> newOffset
            else -> (prev * 7 + newOffset * 3) / 10
        }
        NPLogger.d(
            TAG,
            "updateServerClockOffsetFromRoundTrip(): reason=$reason, offset=$estimatedServerClockOffsetMs, sample=$newOffset, rtt=$rtt"
        )
    }

    suspend fun sendControlEvent(event: ListenTogetherEvent): ListenTogetherControlResponse {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl
        if (baseUrl.isNullOrBlank()) {
            return ListenTogetherControlResponse(ok = false, error = "baseUrl missing")
        }
        val roomId = snapshot.roomId
        if (roomId.isNullOrBlank()) {
            return ListenTogetherControlResponse(ok = false, error = "roomId missing")
        }
        val token = snapshot.token
        if (token.isNullOrBlank()) {
            return ListenTogetherControlResponse(ok = false, error = "token missing")
        }
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
            clientInstanceId = clientInstanceId,
            clientSequence = nextClientSequence(),
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
        val currentState = _roomState.value
        if (!shouldApplyListenTogetherRoomStateToPlayer(state, currentState)) {
            NPLogger.d(
                TAG,
                "applyRoomStateToPlayer(): skip inactive or stale state, roomId=${state.roomId}, version=${state.version}, currentRoomId=${currentState?.roomId}, currentVersion=${currentState?.version}, causeType=$causeType"
            )
            return
        }
        playerStateApplier.apply(
            state = state,
            causeType = causeType,
            expectedPositionMs = expectedPositionMs
        )
    }

    fun buildSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        return eventFactory.buildSetTrackEvent(
            queue = queue,
            currentIndex = currentIndex,
            positionMs = positionMs,
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
        return eventFactory.buildPlaybackModeEvent(
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
    }

    fun buildHeartbeatEvent(
        state: String,
        positionMs: Long,
        includeQueue: Boolean = true
    ): ListenTogetherEvent {
        return eventFactory.buildHeartbeatEvent(
            state = state,
            positionMs = positionMs,
            includeQueue = includeQueue
        )
    }

    fun buildRequestLinkEvent(
        stableKey: String,
        currentIndex: Int? = null,
        track: ListenTogetherTrack? = null
    ): ListenTogetherEvent {
        return eventFactory.buildRequestLinkEvent(
            stableKey = stableKey,
            currentIndex = currentIndex,
            track = track
        )
    }

    fun buildLinkReadyEvent(
        stableKey: String,
        positionMs: Long,
        streamUrlOverride: String? = null
    ): ListenTogetherEvent? {
        return eventFactory.buildLinkReadyEvent(
            stableKey = stableKey,
            positionMs = positionMs,
            streamUrlOverride = streamUrlOverride
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
        return when (type) {
            "PLAY" -> eventFactory.buildPlayEvent(positionMs)
            "PAUSE" -> eventFactory.buildPauseEvent(positionMs)
            "SEEK" -> eventFactory.buildSeekEvent(positionMs)
            "REQUEST_PLAY" -> eventFactory.buildRequestPlayEvent(positionMs)
            "REQUEST_PAUSE" -> eventFactory.buildRequestPauseEvent(positionMs)
            "REQUEST_SEEK" -> eventFactory.buildRequestSeekEvent(positionMs)
            else -> error("Unsupported playback snapshot event type: $type")
        }
    }

    private fun buildTrackFinishedEvent(
        command: PlaybackCommand,
        queue: List<SongItem>,
        currentSong: SongItem?,
        positionMs: Long
    ): ListenTogetherEvent? {
        return eventFactory.buildTrackFinishedEvent(
            command = command,
            queue = queue,
            currentSong = currentSong,
            positionMs = positionMs
        )
    }

    private fun updateSession(baseUrl: String, response: ListenTogetherRoomResponse) {
        val normalizedBaseUrl = resolveListenTogetherBaseUrl(baseUrl)
        val nextRoomId = response.roomId
        if (!nextRoomId.isNullOrBlank()) {
            synchronized(roomStateLock) {
                if (nextRoomId != activeRoomIdForStateAcceptance) {
                    lastAppliedRoomVersion = -1L
                    pendingRoomRepairVersion = -1L
                    _roomState.value = null
                }
                activeRoomIdForStateAcceptance = nextRoomId
            }
            lastListenerStateRefreshAtElapsedMs = 0L
            lastWebSocketMessageAtElapsedMs = 0L
        }
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
            role = resolveListenTogetherSessionRole(
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
        return synchronized(roomStateLock) {
            val activeRoomId = activeRoomIdForStateAcceptance
            if (activeRoomId.isNullOrBlank() || activeRoomId != state.roomId) {
                NPLogger.d(
                    TAG,
                    "acceptRoomState(): drop inactive room source=${source.logName}, roomId=${state.roomId}, activeRoomId=$activeRoomId, version=${state.version}"
                )
                return@synchronized null
            }
            val currentState = _roomState.value
            val latestVersion = latestAcceptedRoomVersion(currentState)
            if (state.version < latestVersion) {
                NPLogger.d(
                    TAG,
                    "acceptRoomState(): drop stale source=${source.logName}, roomId=${state.roomId}, version=${state.version}, latest=$latestVersion"
                )
                return@synchronized null
            }
            if (shouldDropControllerLocalEcho(state, cause, latestVersion)) {
                NPLogger.d(
                    TAG,
                    "acceptRoomState(): drop controller echo source=${source.logName}, roomId=${state.roomId}, version=${state.version}, latest=$latestVersion, causedBy=${cause?.type}:${cause?.eventId}"
                )
                return@synchronized null
            }
            if (currentState != null && state.version == latestVersion) {
                lastAppliedRoomVersion = maxOf(lastAppliedRoomVersion, currentState.version)
                clearRoomRepairIfSatisfied(state.version, source)
                updateRoomPositionSupplement(currentState, expectedPositionMs, source)
                return@synchronized AcceptedRoomState(
                    state = currentState,
                    expectedPositionMs = expectedPositionMs
                )
            }
            commitRoomState(state, expectedPositionMs, source)
            clearRoomRepairIfSatisfied(state.version, source)
            AcceptedRoomState(
                state = state,
                expectedPositionMs = expectedPositionMs
            )
        }
    }

    private fun clearRoomRepairIfSatisfied(version: Long, source: RoomStateSource) {
        val isHttpState = when (source) {
            RoomStateSource.HTTP_REFRESH,
            RoomStateSource.HTTP_CONTROL_FALLBACK,
            RoomStateSource.HTTP_SESSION_UPDATE -> true
            else -> false
        }
        if (isHttpState && pendingRoomRepairVersion >= 0L && version >= pendingRoomRepairVersion) {
            pendingRoomRepairVersion = -1L
        }
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
            role = resolveListenTogetherSessionRole(
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
        return latestListenTogetherAcceptedRoomVersion(lastAppliedRoomVersion, currentState)
    }

    private fun recordWebSocketMessage(message: ListenTogetherSocketEnvelope): Boolean {
        val incomingVersion = message.state?.version ?: message.version
        val incomingRoomId = message.state?.roomId ?: message.roomId
        return synchronized(roomStateLock) {
            val activeRoomId = activeRoomIdForStateAcceptance ?: return@synchronized false
            if (!incomingRoomId.isNullOrBlank() && incomingRoomId != activeRoomId) {
                return@synchronized false
            }
            lastWebSocketMessageAtElapsedMs = SystemClock.elapsedRealtime()
            if (incomingVersion == null) return@synchronized true
            val latestVersion = latestAcceptedRoomVersion(_roomState.value)
            if (latestVersion < 0L || incomingVersion <= latestVersion + 1L) {
                return@synchronized true
            }
            pendingRoomRepairVersion = maxOf(pendingRoomRepairVersion, incomingVersion)
            NPLogger.w(
                TAG,
                "recordWebSocketMessage(): version gap detected, roomId=$activeRoomId, incoming=$incomingVersion, latest=$latestVersion, repairTarget=$pendingRoomRepairVersion"
            )
            true
        }
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

    // 旧自建 Worker 可能仍依赖这条中转路径，当前内置 Worker 已直接仲裁听众请求
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
        return eventFactory.buildEventForPlaybackCommand(command)
    }

    private fun buildControllerCommitEventFromForwardedRequest(
        message: ListenTogetherSocketEnvelope
    ): ListenTogetherEvent? {
        return eventFactory.buildControllerCommitEventFromForwardedRequest(message)
    }

    private fun shouldSuppressLocalListenerControlEvent(event: ListenTogetherEvent): Boolean {
        if (isCurrentUserController()) return false
        if (event.type !in requestControlEventTypes) return false
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
        if (requestType !in trackBoundRequestControlEventTypes) return false
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
        val syntheticState = synchronized(roomStateLock) {
            val currentState = _roomState.value ?: return
            val nextState = buildListenTogetherForwardedControlSyntheticState(
                currentState = currentState,
                message = message,
                committedEvent = committedEvent
            )
            commitRoomState(
                state = nextState,
                expectedPositionMs = committedEvent.positionMs ?: message.expectedPositionMs,
                source = RoomStateSource.LOCAL_SYNTHETIC
            )
            nextState
        }
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
        return resolveListenTogetherControlBlockReason(
            context = AppContainer.applicationContext,
            sessionRole = currentRole(sessionState),
            roomState = roomState,
            commandType = command.type
        )
    }

    private fun shouldIgnoreIncomingState(cause: ListenTogetherCause?): Boolean {
        return shouldIgnoreListenTogetherIncomingState(
            cause = cause,
            currentUserId = _sessionState.value.userUuid,
            hasRecentOutboundEvent = ::hasRecentOutboundEvent,
            hasRecentInboundEvent = ::hasRecentInboundEvent
        )
    }

    private fun shouldDeferIncomingStateForLocalTrackFinish(
        state: ListenTogetherRoomState,
        cause: ListenTogetherCause?
    ): Boolean {
        return shouldDeferListenTogetherIncomingStateForLocalTrackFinish(
            state = state,
            cause = cause,
            awaitingTrackFinishStableKey = awaitingTrackFinishStableKey
        )
    }

    private fun shouldDropControllerLocalEcho(
        state: ListenTogetherRoomState,
        cause: ListenTogetherCause?,
        latestVersion: Long
    ): Boolean {
        return shouldDropListenTogetherControllerLocalEcho(
            state = state,
            cause = cause,
            latestVersion = latestVersion,
            currentUserId = _sessionState.value.userUuid,
            lastControllerLocalControlAtElapsedMs = lastControllerLocalControlAtElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            controllerLocalControlCooldownMs = CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS
        )
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        NPLogger.d(TAG, "startHeartbeat()")
        if (lastOutboundSyncAtMs == 0L) {
            lastOutboundSyncAtMs = SystemClock.elapsedRealtime()
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                val snapshot = _sessionState.value
                if (
                    snapshot.connectionState != ListenTogetherConnectionState.CONNECTED ||
                    !isCurrentUserController(snapshot)
                ) {
                    delay(HEARTBEAT_STATE_RECHECK_INTERVAL_MS)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val idleMs = now - lastOutboundSyncAtMs
                val playbackState = currentLocalPlaybackStateName()
                val heartbeatIntervalMs = resolveListenTogetherHeartbeatIntervalMs(
                    isPlaying = playbackState == "playing",
                    playingIntervalMs = LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS,
                    pausedIntervalMs = LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS
                )
                val remainingMs = heartbeatIntervalMs - idleMs
                if (remainingMs > 0L) {
                    delay(minOf(remainingMs, HEARTBEAT_STATE_RECHECK_INTERVAL_MS))
                    continue
                }
                val heartbeat = buildHeartbeatEvent(
                    state = playbackState,
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

    private fun buildPendingMemberControlRequest(event: ListenTogetherEvent): PendingMemberControlRequest? {
        if (isCurrentUserController()) return null
        if (event.type !in requestControlEventTypes) return null
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
        val retryEvent = pending.event
        pendingMemberControlRequest = pending.retriedAt(nowElapsedMs)
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
        val baseUrl = snapshot.baseUrl
        val roomId = snapshot.roomId
        if (baseUrl.isNullOrBlank() || roomId.isNullOrBlank()) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val shouldRepair = shouldRepairListenTogetherListenerState(
            nowElapsedMs = nowElapsedMs,
            lastWebSocketMessageAtElapsedMs = lastWebSocketMessageAtElapsedMs,
            lastRefreshAtElapsedMs = lastListenerStateRefreshAtElapsedMs,
            pendingVersionGap = pendingRoomRepairVersion,
            webSocketSilenceTimeoutMs = LISTENER_WEB_SOCKET_SILENCE_TIMEOUT_MS,
            repairMinIntervalMs = LISTENER_STATE_REPAIR_MIN_INTERVAL_MS
        )
        if (!shouldRepair) return
        lastListenerStateRefreshAtElapsedMs = nowElapsedMs
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
        val needsStallRecovery = listenerStallRecovery.shouldRecover(
            state = state,
            nowElapsedMs = SystemClock.elapsedRealtime()
        )
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

    private fun resetListenerRecoveryState() {
        lastListenerStateRefreshAtElapsedMs = 0L
        listenerStallRecovery.reset()
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
        if (attempt > LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS) {
            NPLogger.w(
                TAG,
                "scheduleReconnect(): max attempts reached ($LISTEN_TOGETHER_MAX_RECONNECT_ATTEMPTS), giving up, reason=$reason"
            )
            closeRoomLocally("reconnect_max_attempts_exceeded")
            return
        }
        val delayMs = listenTogetherReconnectDelayMs(attempt)
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

    private fun isUnsupportedClockSyncPingError(errorMessage: String): Boolean {
        return errorMessage.contains("unsupported event type: np_ping", ignoreCase = true)
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
        val resolvedMessage = AppContainer.applicationContext.getString(R.string.listen_together_error_reconnecting)
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
            lastError = AppContainer.applicationContext.getString(R.string.listen_together_error_rejoining)
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
        if (!isTerminalListenTogetherReconnectError(errorMessage)) {
            return false
        }
        NPLogger.w(
            TAG,
            "handleTerminalReconnectFailure(): stop reconnect, reason=$reason, error=$errorMessage"
        )
        closeRoomLocally(errorMessage ?: "listen_together_unavailable")
        return true
    }

    private fun noteOutboundSync() {
        lastOutboundSyncAtMs = SystemClock.elapsedRealtime()
    }

    private fun currentRole(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): String? {
        return resolveListenTogetherSessionRole(
            sessionUserId = sessionState.userUuid,
            fallbackRole = sessionState.role,
            state = _roomState.value
        )
    }

    private fun isCurrentUserController(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): Boolean = currentRole(sessionState) == "controller"

    private fun hasRecentOutboundEvent(eventId: String): Boolean = recentEventTracker.hasOutbound(eventId)

    private fun hasRecentInboundEvent(eventId: String): Boolean = recentEventTracker.hasInbound(eventId)

    private fun markOutboundEvent(eventId: String?) = recentEventTracker.markOutbound(eventId)

    private fun markInboundEvent(eventId: String?) = recentEventTracker.markInbound(eventId)

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
        synchronized(roomStateLock) {
            lastAppliedRoomVersion = -1L
            pendingRoomRepairVersion = -1L
            activeRoomIdForStateAcceptance = null
            _roomState.value = null
        }
        lastControllerLocalControlAtElapsedMs = 0L
        lastHandledForwardedRequestSequence = 0L
        pendingMemberControlRequest = null
        lastListenerStateRefreshAtElapsedMs = 0L
        lastWebSocketMessageAtElapsedMs = 0L
        resetListenerRecoveryState()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        webSocketClient.disconnect(code = 1000, reason = "room_closed")
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
        return resolveListenTogetherRoomNotice(
            state = state,
            fallbackMessage = fallbackMessage,
            controllerGracePeriodMs = CONTROLLER_GRACE_PERIOD_MS
        )
    }

    private fun nextEventId(): String = nextListenTogetherEventId()

    private fun nextClientSequence(): Long = clientSequence.incrementAndGet()

    private fun noteControllerLocalControl(command: PlaybackCommand) {
        if (command.source != PlaybackCommandSource.LOCAL) return
        if (!isCurrentUserController()) return
        if (command.type !in controlledPlaybackCommandTypes) return
        lastControllerLocalControlAtElapsedMs = SystemClock.elapsedRealtime()
        NPLogger.d(
            TAG,
            "noteControllerLocalControl(): type=${command.type}, positionMs=${command.positionMs}, currentIndex=${command.currentIndex}"
        )
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
        val song = PlayerManager.currentSongFlow.value
        if (song == null) {
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
                val streamUrl = normalizedDirectStreamUrl((result as? SongUrlResult.Success)?.url)
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
        if (cause.type !in controllerHeartbeatRecoveryTypes) return
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
        private const val TRACK_SWITCH_GRACE_PERIOD_MS = 800L
        private const val CONTROLLER_GRACE_PERIOD_MS = 10 * 60 * 1000L
        private const val HEARTBEAT_STATE_RECHECK_INTERVAL_MS = 10_000L
        private const val LINK_REQUEST_THROTTLE_MS = 4_000L
        private const val CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS = 1_200L
        private const val SYNC_WATCHDOG_INTERVAL_MS = 8_000L
        private const val LISTENER_WEB_SOCKET_SILENCE_TIMEOUT_MS = 45_000L
        private const val LISTENER_STATE_REPAIR_MIN_INTERVAL_MS = 30_000L
        private const val LISTENER_PLAYBACK_STALL_TIMEOUT_MS = 8_000L
        private const val LISTENER_PLAYBACK_STALL_RECOVERY_COOLDOWN_MS = 12_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_RETRY_INTERVAL_MS = 3_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_TTL_MS = 18_000L
        private const val PENDING_MEMBER_CONTROL_REQUEST_MAX_ATTEMPTS = 4
        private const val PENDING_MEMBER_SEEK_SATISFIED_DRIFT_MS = 1_500L
        private const val TRACK_FINISHED_LEGACY_FALLBACK_TTL_MS = 15_000L
        private const val SOFT_SYNC_MIN_DRIFT_MS = 600L
        private const val SOFT_SYNC_FAST_DRIFT_MS = 1_500L
        private const val CLOCK_SYNC_MAX_RTT_MS = 30_000L
        private const val UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS = 2_000L
    }
}
