package moe.ouom.neriplayer.listentogether

import android.os.Looper
import android.os.SystemClock
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
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherMemberControlTargetCurrent
import moe.ouom.neriplayer.listentogether.compat.shouldSuppressListenerControlWhileAwaitingStream
import moe.ouom.neriplayer.listentogether.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.listentogether.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.listentogether.control.nextListenTogetherEventId
import moe.ouom.neriplayer.listentogether.control.requestControlEventTypes
import moe.ouom.neriplayer.listentogether.control.trackBoundRequestControlEventTypes
import moe.ouom.neriplayer.listentogether.network.http.ListenTogetherApi
import moe.ouom.neriplayer.listentogether.network.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.listentogether.network.ws.redactListenTogetherWsUrlForLog
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.playback.isShareableForListenTogether
import moe.ouom.neriplayer.listentogether.playback.LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherPlayerStateApplier
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherPlayerStateApplierConfig
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherSoftSyncRecheckAction
import moe.ouom.neriplayer.listentogether.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.listentogether.playback.requestedStableKey
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherSoftSyncPlaybackRate
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherSoftSyncRecheckAction
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.playback.shouldWaitForListenTogetherAuthoritativeStreamPlayback
import moe.ouom.neriplayer.listentogether.playback.targetSongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherControlResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherStateResponse
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.listentogether.session.AcceptedRoomState
import moe.ouom.neriplayer.listentogether.session.ListenTogetherBackgroundKeepAlive
import moe.ouom.neriplayer.listentogether.session.ListenTogetherForwardedRequestDeduper
import moe.ouom.neriplayer.listentogether.session.ListenTogetherLocalControlOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherLocalControlPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherHeartbeatOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherHeartbeatPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherListenerWatchdogOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherListenerWatchdogPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherListenerWatchdogSnapshot
import moe.ouom.neriplayer.listentogether.session.ListenTogetherSocketHealthOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherSocketHealthPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherConnectionRecoveryOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherConnectionRecoveryPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRejoinIdentity
import moe.ouom.neriplayer.listentogether.session.ListenTogetherControllerLinkOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherLinkEventPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherLinkSessionPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomStateObserver
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomStateOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomSocketEventOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomSocketEventPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomMembershipOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRoomMembershipPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherApiMembershipTransport
import moe.ouom.neriplayer.listentogether.session.ListenTogetherSocketControlResultOwner
import moe.ouom.neriplayer.listentogether.session.ListenTogetherSocketControlResultPort
import moe.ouom.neriplayer.listentogether.session.PlayerManagerListenTogetherLinkPlaybackPort
import moe.ouom.neriplayer.listentogether.session.ListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.listentogether.session.RoomStateSource
import moe.ouom.neriplayer.listentogether.session.normalized
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherControlBlockReason
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherRoomNotice
import moe.ouom.neriplayer.listentogether.session.shouldHoldListenTogetherBackgroundKeepAlive
import moe.ouom.neriplayer.listentogether.session.isNormalListenTogetherRoomClosureReason
import moe.ouom.neriplayer.listentogether.session.normalizeListenTogetherRoomClosureReason
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.listentogether.session.shouldApplyListenTogetherRoomStateToPlayer
import moe.ouom.neriplayer.listentogether.session.shouldRejectForwardedListenTogetherMemberControl
import moe.ouom.neriplayer.listentogether.session.prepareListenTogetherSessionUpdate
import moe.ouom.neriplayer.listentogether.validation.requireValidListenTogetherRoomId
import moe.ouom.neriplayer.util.units.MINUTE_MS
import moe.ouom.neriplayer.util.units.SECOND_MS
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

class ListenTogetherSessionManager(
    private val api: ListenTogetherApi,
    private val webSocketClient: ListenTogetherWebSocketClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var softSyncRateRecheckJob: Job? = null

    @Volatile
    private var started = false

    private val recentEventTracker = ListenTogetherRecentEventTracker()
    @Volatile
    private var lastControllerLocalControlAtElapsedMs: Long = 0L
    @Volatile
    private var applicationInForeground = true
    private val forwardedRequestDeduper = ListenTogetherForwardedRequestDeduper()
    @Volatile
    private var webSocketConnectingAtElapsedMs: Long = 0L

    private val clientInstanceId = UUID.randomUUID().toString()
    private val clientSequence = AtomicLong(0L)

    private val _sessionState = MutableStateFlow(ListenTogetherSessionState())
    val sessionState: StateFlow<ListenTogetherSessionState> = _sessionState.asStateFlow()

    private val membershipOwner = ListenTogetherRoomMembershipOwner(
        scope = scope,
        transport = ListenTogetherApiMembershipTransport(api),
        port = object : ListenTogetherRoomMembershipPort {
            override fun currentSession(): ListenTogetherSessionState = _sessionState.value
            override fun repeatMode(): Int = PlayerManager.repeatModeFlow.value
            override fun shuffleEnabled(): Boolean = PlayerManager.shuffleModeFlow.value
            override fun shuffleRestoreSongs(): List<SongItem>? = PlayerManager.shuffleRestorePlaylistReference
            override fun applyRoomResponse(baseUrl: String, response: ListenTogetherRoomResponse) =
                updateSession(baseUrl, response)
            override fun pauseForDeparture() {
                PlayerManager.pauseImpl(
                    forcePersist = true,
                    commandSource = PlaybackCommandSource.REMOTE_SYNC,
                    allowFadeOut = false,
                    debugReason = "listen_together_leave_room_auto_pause"
                )
            }
        }
    )

    private val connectionRecoveryOwner: ListenTogetherConnectionRecoveryOwner = ListenTogetherConnectionRecoveryOwner(
        scope = scope,
        port = object : ListenTogetherConnectionRecoveryPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override fun isController(session: ListenTogetherSessionState): Boolean =
                isCurrentUserController(session)
            override fun updateBackgroundKeepAlive(reason: String) =
                this@ListenTogetherSessionManager.updateBackgroundKeepAlive(reason)
            override fun connectWebSocket() = this@ListenTogetherSessionManager.connectWebSocket()
            override fun closeRoomLocally(reason: String) =
                this@ListenTogetherSessionManager.closeRoomLocally(reason)
            override fun beginMembershipRecovery(session: ListenTogetherSessionState) {
                socketHealthOwner.pendingRefreshAfterReconnect = true
                heartbeatOwner.stop()
                socketHealthOwner.stopKeepAlive()
                _sessionState.value = session.copy(
                    connectionState = ListenTogetherConnectionState.CONNECTING,
                    lastError = AppContainer.applicationContext.getString(R.string.listen_together_error_rejoining)
                )
                webSocketClient.disconnect(code = 1000, reason = "listener_recovering")
            }
            override suspend fun rejoinRoom(identity: ListenTogetherRejoinIdentity) {
                joinRoom(
                    baseUrl = identity.baseUrl,
                    roomId = identity.roomId,
                    userUuid = identity.userUuid,
                    nickname = identity.nickname
                )
            }
            override fun membershipRecoveryFailed(errorMessage: String) {
                _sessionState.value = _sessionState.value.copy(
                    connectionState = ListenTogetherConnectionState.DISCONNECTED,
                    lastError = errorMessage
                )
            }
        }
    )

    private val socketHealthOwner = ListenTogetherSocketHealthOwner(
        scope = scope,
        port = object : ListenTogetherSocketHealthPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override fun reconnectEnabled(): Boolean = connectionRecoveryOwner.enabled
            override fun sendPing(sentAtElapsedMs: Long): Boolean = webSocketClient.sendPing(sentAtElapsedMs)
            override fun sendLegacyPing(): Boolean = webSocketClient.sendLegacyPing()
            override fun scheduleReconnect(reason: String) = connectionRecoveryOwner.scheduleReconnect(reason)
            override fun connectWebSocket() = this@ListenTogetherSessionManager.connectWebSocket()
            override fun updateBackgroundKeepAlive(reason: String) =
                this@ListenTogetherSessionManager.updateBackgroundKeepAlive(reason)
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        wallTimeMs = System::currentTimeMillis
    )

    private val roomStateOwner = ListenTogetherRoomStateOwner(
        observer = object : ListenTogetherRoomStateObserver {
            override fun onRoomActivated() {
                controllerLinkOwner.clearAvailability()
            }

            override fun onCommitted(
                state: ListenTogetherRoomState,
                expectedPositionMs: Long?,
                source: RoomStateSource
            ) = onRoomStateCommitted(state, expectedPositionMs, source)

            override fun onPositionSupplement(expectedPositionMs: Long) {
                _sessionState.value = _sessionState.value.copy(expectedPositionMs = expectedPositionMs)
            }

            override fun onSocketMessageAccepted() {
                socketHealthOwner.noteMessage()
            }
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime
    )
    val roomState: StateFlow<ListenTogetherRoomState?> = roomStateOwner.roomState

    private val heartbeatOwner = ListenTogetherHeartbeatOwner(
        scope = scope,
        port = object : ListenTogetherHeartbeatPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override fun isController(session: ListenTogetherSessionState): Boolean =
                isCurrentUserController(session)
            override fun currentTrackShareable(): Boolean =
                PlayerManager.currentSongFlow.value.isShareableForListenTogether()
            override fun playbackStateName(): String = currentLocalPlaybackStateName()
            override fun playbackPositionMs(): Long = PlayerManager.playbackPositionFlow.value
            override fun buildHeartbeat(state: String, positionMs: Long): ListenTogetherEvent =
                buildHeartbeatEvent(state, positionMs, includeQueue = false)
            override fun sendHeartbeat(event: ListenTogetherEvent) {
                markOutboundEvent(event.eventId)
                sendControlEventPureWebSocket(event, "heartbeat")
            }
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime
    )

    private val listenerWatchdogOwner = ListenTogetherListenerWatchdogOwner(
        scope = scope,
        port = object : ListenTogetherListenerWatchdogPort {
            override fun snapshot(): ListenTogetherListenerWatchdogSnapshot {
                val session = _sessionState.value
                return ListenTogetherListenerWatchdogSnapshot(
                    session = session,
                    room = roomState.value,
                    isController = isCurrentUserController(session),
                    pendingRepairVersion = roomStateOwner.pendingRepairVersion(),
                    lastSocketMessageAtElapsedMs = socketHealthOwner.lastMessageAtElapsedMs,
                    serverClockOffsetMs = socketHealthOwner.serverClockOffsetMs
                )
            }
            override fun retryPendingMemberRequest(room: ListenTogetherRoomState?) =
                localControlOwner.retryPendingMemberRequest(room)
            override fun isControllerNow(): Boolean = isCurrentUserController()
            override fun applyRoomStateToPlayer(
                room: ListenTogetherRoomState,
                cause: String,
                expectedPositionMs: Long
            ) = this@ListenTogetherSessionManager.applyRoomStateToPlayer(room, cause, expectedPositionMs)
            override fun requestControllerLink(room: ListenTogetherRoomState, cause: String, force: Boolean) =
                controllerLinkOwner.maybeRequest(room, cause, force)
            override suspend fun refreshRoomState(baseUrl: String, roomId: String) {
                this@ListenTogetherSessionManager.refreshRoomState(baseUrl, roomId)
            }
            override fun onRefreshFailure(error: Throwable, reason: String) {
                val resolvedError = error.message ?: error.javaClass.simpleName
                NPLogger.w(TAG, "refreshListenerRoomStateIfDue(): failed, reason=$reason, error=$resolvedError", error)
                _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
                if (connectionRecoveryOwner.handleTerminalFailure(resolvedError, "listener_watchdog_refresh")) return
                if (!connectionRecoveryOwner.recoverFromMembershipError(resolvedError, "listener_watchdog_refresh")) {
                    connectionRecoveryOwner.scheduleReconnect("listener_watchdog_refresh_failed:$reason")
                }
            }
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime
    )

    private val localControlOwner = ListenTogetherLocalControlOwner(
        scope = scope,
        port = object : ListenTogetherLocalControlPort {
            override fun currentRoomId(): String? = _sessionState.value.roomId
            override fun isController(): Boolean = isCurrentUserController()
            override fun nextEventId(): String = this@ListenTogetherSessionManager.nextEventId()
            override fun markOutbound(eventId: String?) = markOutboundEvent(eventId)
            override fun noteOutboundSync() = this@ListenTogetherSessionManager.noteOutboundSync()
            override fun send(event: ListenTogetherEvent, reason: String): Boolean =
                sendControlEventPureWebSocket(event, reason)
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        wallTimeMs = System::currentTimeMillis
    )


    private val eventFactory = ListenTogetherEventFactory(
        roomStateProvider = { roomState.value },
        isControllerProvider = { isCurrentUserController() },
        eventIdFactory = ::nextEventId,
        clientInstanceIdProvider = { clientInstanceId },
        clientSequenceFactory = ::nextClientSequence,
        localPlaybackStateNameProvider = ::currentLocalPlaybackStateName,
        localTransportActiveProvider = ::isLocalPlaybackTransportActive
    )

    private val controllerLinkOwner = ListenTogetherControllerLinkOwner(
        scope = scope,
        session = object : ListenTogetherLinkSessionPort {
            override fun sessionState(): ListenTogetherSessionState = _sessionState.value

            override fun roomState(): ListenTogetherRoomState? = roomState.value

            override fun publish(
                event: ListenTogetherEvent,
                reason: String,
                noteSync: Boolean
            ): Boolean {
                markOutboundEvent(event.eventId)
                if (noteSync) noteOutboundSync()
                return sendControlEventPureWebSocket(event, reason)
            }

            override fun publishControllerHeartbeat(reason: String) =
                publishControllerHeartbeatIfNeeded(force = true, reason = reason)
        },
        playback = PlayerManagerListenTogetherLinkPlaybackPort,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        events = object : ListenTogetherLinkEventPort {
            override fun requestLink(
                stableKey: String,
                currentIndex: Int,
                track: ListenTogetherTrack,
                forceRefresh: Boolean
            ): ListenTogetherEvent = eventFactory.buildRequestLinkEvent(
                stableKey, currentIndex, track, forceRefresh
            )

            override fun linkReady(
                stableKey: String,
                positionMs: Long,
                streamUrlsOverride: List<String>
            ): ListenTogetherEvent? = eventFactory.buildLinkReadyEvent(
                stableKey = stableKey,
                positionMs = positionMs,
                streamUrlsOverride = streamUrlsOverride
            )

            override fun linkUnavailable(stableKey: String): ListenTogetherEvent? =
                eventFactory.buildLinkUnavailableEvent(stableKey)
        }
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
        roomStateProvider = { roomState.value },
        isControllerProvider = { isCurrentUserController() },
        serverClockOffsetProvider = { socketHealthOwner.serverClockOffsetMs }
    )

    private val roomSocketEventOwner = ListenTogetherRoomSocketEventOwner(
        port = object : ListenTogetherRoomSocketEventPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override fun room(): ListenTogetherRoomState? = roomState.value
            override fun accept(
                state: ListenTogetherRoomState,
                expectedPositionMs: Long?,
                source: RoomStateSource,
                cause: ListenTogetherCause?
            ): AcceptedRoomState? = acceptRoomState(state, expectedPositionMs, source, cause)
            override fun updateNotice(notice: String?, clearError: Boolean) {
                _sessionState.value = _sessionState.value.copy(
                    roomNotice = notice,
                    lastError = if (clearError) null else _sessionState.value.lastError
                )
            }
            override fun applyToPlayer(
                state: ListenTogetherRoomState,
                causeType: String?,
                expectedPositionMs: Long?
            ) = applyRoomStateToPlayer(state, causeType, expectedPositionMs)
            override fun publishControllerHeartbeat(reason: String) =
                publishControllerHeartbeatIfNeeded(force = true, reason = reason)
            override fun pauseClosedRoomPlayback() {
                mainScope.launch {
                    PlayerManager.pauseImpl(
                        forcePersist = true,
                        commandSource = PlaybackCommandSource.REMOTE_SYNC,
                        allowFadeOut = false,
                        debugReason = "listen_together_closed_room_auto_pause"
                    )
                }
            }
            override fun closeRoomLocally(reason: String?) =
                this@ListenTogetherSessionManager.closeRoomLocally(reason)
        },
        localControl = localControlOwner,
        controllerLink = controllerLinkOwner,
        recentEvents = recentEventTracker
    )

    private val socketControlResultOwner = ListenTogetherSocketControlResultOwner(
        port = object : ListenTogetherSocketControlResultPort {
            override fun currentUserUuid(): String? = _sessionState.value.userUuid
            override fun currentRoom(): ListenTogetherRoomState? = roomState.value
            override fun accept(
                state: ListenTogetherRoomState,
                expectedPositionMs: Long?,
                cause: ListenTogetherCause
            ): AcceptedRoomState? = acceptRoomState(
                state, expectedPositionMs, RoomStateSource.WEB_SOCKET_CONTROL_RESULT, cause
            )
            override fun isController(): Boolean = isCurrentUserController()
            override fun applyToPlayer(
                state: ListenTogetherRoomState,
                causeType: String?,
                expectedPositionMs: Long?
            ) = applyRoomStateToPlayer(state, causeType, expectedPositionMs)
            override fun setLastError(error: String?) {
                _sessionState.value = _sessionState.value.copy(lastError = error)
            }
            override fun trySendTrackFinishedLegacyFallback(error: String): Boolean =
                this@ListenTogetherSessionManager.trySendTrackFinishedLegacyFallback(error)
        },
        localControl = localControlOwner,
        controllerLink = controllerLinkOwner,
        socketHealth = socketHealthOwner,
        recovery = connectionRecoveryOwner
    )

    private val socketMessageHandlers: Map<String, (ListenTogetherSocketEnvelope) -> Unit> = mapOf(
        "welcome" to roomSocketEventOwner::onRoomState,
        "room_state_updated" to roomSocketEventOwner::onRoomState,
        "link_requested" to controllerLinkOwner::onLinkRequested,
        "member_control_requested" to ::handleMemberControlRequested,
        "room_suspended" to roomSocketEventOwner::onRoomSuspended,
        "room_resumed" to roomSocketEventOwner::onRoomResumed,
        "room_closed" to roomSocketEventOwner::onRoomClosed,
        "control_result" to socketControlResultOwner::onControlResult,
        "ack" to socketControlResultOwner::onControlResult,
        "error" to socketControlResultOwner::onSocketError,
        "pong" to socketControlResultOwner::onPong,
        "np_pong" to socketControlResultOwner::onPong
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
            PlayerManager.currentMediaUrlFlow.collectLatest(controllerLinkOwner::onResolvedStreamUrlChanged)
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
        roomSettings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
        currentSong: SongItem? = queue.getOrNull(currentIndex)
    ): ListenTogetherRoomResponse {
        return membershipOwner.createRoom(
            baseUrl, userUuid, nickname, queue, currentIndex, positionMs, isPlaying, roomSettings, currentSong
        )
    }

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        userUuid: String,
        nickname: String,
        memberSecret: String? = null,
        joinSecret: String? = null
    ): ListenTogetherRoomResponse {
        return membershipOwner.joinRoom(baseUrl, roomId, userUuid, nickname, memberSecret, joinSecret)
    }

    suspend fun refreshRoomState(
        baseUrl: String,
        roomId: String,
        causeType: String? = null
    ): ListenTogetherStateResponse {
        val validatedRoomId = requireValidListenTogetherRoomId(roomId)
        NPLogger.d(TAG, "refreshRoomState(): baseUrl=$baseUrl, roomId=$validatedRoomId")
        val sentAtElapsedMs = SystemClock.elapsedRealtime()
        val sentAtWallMs = System.currentTimeMillis()
        val session = _sessionState.value
        val bearerToken = session.takeIf {
            it.baseUrl.equals(baseUrl, ignoreCase = true) &&
                it.roomId.equals(validatedRoomId, ignoreCase = true)
        }?.token
        val response = api.getRoomState(
            baseUrl = baseUrl,
            roomId = validatedRoomId,
            bearerToken = bearerToken
        )
        socketHealthOwner.onRoundTrip(
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
                    causeType = causeType,
                    expectedPositionMs = accepted.expectedPositionMs
                )
                controllerLinkOwner.maybeRequest(accepted.state, "refresh_room_state")
            }
        }
        NPLogger.d(
            TAG,
            "refreshRoomState(): ok=${response.ok}, version=${response.state?.version}, expectedPositionMs=${response.expectedPositionMs}"
        )
        return response
    }

    fun resumeListenerAfterSafetyPause() {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl
        val roomId = snapshot.roomId
        if (
            baseUrl.isNullOrBlank() ||
            roomId.isNullOrBlank() ||
            isCurrentUserController(snapshot)
        ) {
            PlayerManager.retryListenTogetherSafetyPauseResume()
            return
        }
        scope.launch {
            runCatching {
                refreshRoomState(
                    baseUrl = baseUrl,
                    roomId = roomId
                )
            }.onSuccess { response ->
                val responseState = response.state
                if (response.ok && responseState != null) {
                    mainScope.launch {
                        val currentSession = _sessionState.value
                        if (
                            currentSession.roomId != roomId ||
                            isCurrentUserController(currentSession)
                        ) {
                            PlayerManager.clearListenTogetherSafetyPause()
                            return@launch
                        }
                        val latestRoomState = roomState.value
                        val stateToApply = latestRoomState
                            ?.takeIf { it.roomId == roomId }
                            ?: responseState
                        val expectedPositionMs = response.expectedPositionMs
                            .takeIf { stateToApply.version == responseState.version }
                        val applied = playerStateApplier.apply(
                            state = stateToApply,
                            causeType = LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE,
                            expectedPositionMs = expectedPositionMs
                        )
                        reconcileListenTogetherSoftSyncRateRecheck()
                        if (applied) {
                            PlayerManager.completeListenTogetherSafetyPauseResume()
                            NPLogger.d(
                                TAG,
                                "resumeListenerAfterSafetyPause(): synchronized roomId=$roomId, version=${stateToApply.version}"
                            )
                        } else {
                            PlayerManager.retryListenTogetherSafetyPauseResume()
                        }
                    }
                    return@onSuccess
                }
                PlayerManager.retryListenTogetherSafetyPauseResume()
                _sessionState.value = _sessionState.value.copy(
                    lastError = response.error ?: "listener_safety_resume_state_unavailable"
                )
            }.onFailure { error ->
                PlayerManager.retryListenTogetherSafetyPauseResume()
                val message = error.message ?: error.javaClass.simpleName
                NPLogger.w(
                    TAG,
                    "resumeListenerAfterSafetyPause(): state refresh failed, roomId=$roomId, error=$message",
                    error
                )
                _sessionState.value = _sessionState.value.copy(lastError = message)
            }
        }
    }

    fun connectWebSocket() {
        connectionRecoveryOwner.beginConnect()
        val wsUrl = _sessionState.value.wsUrl ?: return
        webSocketConnectingAtElapsedMs = SystemClock.elapsedRealtime()
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
                    if (!connectionRecoveryOwner.enabled || !roomStateOwner.hasActiveRoom()) {
                        webSocketConnectingAtElapsedMs = 0L
                        NPLogger.d(TAG, "websocket.onOpen(): drop inactive session")
                        webSocketClient.disconnect(code = 1000, reason = "inactive_session")
                        return
                    }
                    webSocketConnectingAtElapsedMs = 0L
                    socketHealthOwner.noteMessage()
                    val shouldRefreshState = socketHealthOwner.pendingRefreshAfterReconnect
                    connectionRecoveryOwner.socketOpened()
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.CONNECTED,
                        lastError = null
                    )
                    updateBackgroundKeepAlive("socket_open")
                    socketHealthOwner.startKeepAlive()
                    heartbeatOwner.start()
                    listenerWatchdogOwner.start()
                    socketHealthOwner.cancelForegroundProbe()
                    socketHealthOwner.pendingRefreshAfterReconnect = false
                    localControlOwner.replayPending()
                    if (shouldRefreshState) {
                        scope.launch {
                            refreshRoomStateAfterReconnect("socket_open")
                        }
                    }
                    roomState.value?.let { currentState ->
                        controllerLinkOwner.maybeRequest(currentState, "socket_open")
                    }
                    controllerLinkOwner.maybePublishCurrentLink("socket_open")
                    publishControllerHeartbeatIfNeeded(force = true, reason = "socket_open")
                }

                override fun onMessage(message: ListenTogetherSocketEnvelope) {
                    receiveSocketEnvelope(message)
                }

                override fun onClosed(code: Int, reason: String) {
                    webSocketConnectingAtElapsedMs = 0L
                    heartbeatOwner.stop()
                    socketHealthOwner.stopKeepAlive()
                    NPLogger.w(TAG, "websocket.onClosed(): code=$code, reason=$reason")
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.DISCONNECTED,
                        lastError = reason.takeIf { it.isNotBlank() }
                    )
                    if (connectionRecoveryOwner.handleTerminalFailure(reason, "socket_closed:$code")) {
                        return
                    }
                    connectionRecoveryOwner.scheduleReconnect("closed:$code:${reason.ifBlank { "unknown" }}")
                }

                override fun onFailure(error: Throwable) {
                    webSocketConnectingAtElapsedMs = 0L
                    heartbeatOwner.stop()
                    socketHealthOwner.stopKeepAlive()
                    NPLogger.e(TAG, "websocket.onFailure(): ${error.message}", error)
                    _sessionState.value = _sessionState.value.copy(
                        connectionState = ListenTogetherConnectionState.DISCONNECTED,
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                    if (connectionRecoveryOwner.handleTerminalFailure(error.message, "socket_failure")) {
                        return
                    }
                    connectionRecoveryOwner.scheduleReconnect("failure:${error.message ?: error.javaClass.simpleName}")
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

    internal fun receiveSocketEnvelope(message: ListenTogetherSocketEnvelope) {
        if (!recordWebSocketMessage(message)) return
        NPLogger.d(TAG, "websocket.onMessage(): type=${message.type}, roomId=${message.roomId}, version=${message.version}, ok=${message.ok}")
        noteSocketMessageClock(message)
        socketMessageHandlers[message.type]?.invoke(message)
    }

    private fun noteSocketMessageClock(message: ListenTogetherSocketEnvelope) {
        if (message.type == "pong" || message.type == "np_pong") return
        socketHealthOwner.onServerMessage(message.nowMs ?: message.result?.applied?.nowMs, message.type)
    }

    fun disconnectWebSocket() {
        localControlOwner.clearCoalesced("disconnect")
        connectionRecoveryOwner.stop()
        socketHealthOwner.pendingRefreshAfterReconnect = false
        controllerLinkOwner.clear()
        socketHealthOwner.cancelForegroundProbe()
        stopListenTogetherSoftSyncRateRecheck()
        socketHealthOwner.stopKeepAlive()
        listenerWatchdogOwner.stop()
        heartbeatOwner.reset()
        roomStateOwner.resetVersions()
        lastControllerLocalControlAtElapsedMs = 0L
        forwardedRequestDeduper.clear()
        localControlOwner.resetTransientRequests()
        listenerWatchdogOwner.resetRefreshTime()
        socketHealthOwner.resetConnectionTiming()
        webSocketConnectingAtElapsedMs = 0L
        listenerWatchdogOwner.resetRecovery()
        ListenTogetherBackgroundKeepAlive.release("disconnect")
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "disconnectWebSocket()")
        webSocketClient.disconnect()
        _sessionState.value = _sessionState.value.copy(
            connectionState = ListenTogetherConnectionState.DISCONNECTED,
            roomNotice = null
        )
    }

    fun onApplicationForegrounded() {
        applicationInForeground = true
        ListenTogetherBackgroundKeepAlive.release("foreground")
        val snapshot = _sessionState.value
        when (
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = snapshot.connectionState,
                roomId = snapshot.roomId,
                wsUrl = snapshot.wsUrl,
                reconnectEnabled = connectionRecoveryOwner.enabled,
                connectingSinceElapsedMs = webSocketConnectingAtElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        ) {
            ListenTogetherForegroundRecoveryAction.NONE -> Unit
            ListenTogetherForegroundRecoveryAction.CONNECT -> {
                socketHealthOwner.pendingRefreshAfterReconnect = true
                ensureListenTogetherForegroundService("foreground_connect")
                connectWebSocket()
            }

            ListenTogetherForegroundRecoveryAction.REFRESH_ROOM_STATE -> {
                ensureListenTogetherForegroundService("foreground_refresh")
                val probeStartedAtElapsedMs = SystemClock.elapsedRealtime()
                if (!socketHealthOwner.sendPing()) {
                    socketHealthOwner.pendingRefreshAfterReconnect = true
                    connectionRecoveryOwner.scheduleReconnect("foreground_ping_send_failed")
                    return
                }
                socketHealthOwner.scheduleForegroundProbe(
                    roomId = snapshot.roomId,
                    probeStartedAtElapsedMs = probeStartedAtElapsedMs
                )
                if (isCurrentUserController(snapshot)) {
                    publishControllerHeartbeatIfNeeded(
                        force = true,
                        reason = "foreground_refresh"
                    )
                }
                scope.launch {
                    refreshRoomStateAfterReconnect("foreground_refresh")
                }
            }
        }
    }

    fun onApplicationBackgrounded() {
        applicationInForeground = false
        if (
            shouldHoldListenTogetherBackgroundKeepAlive(
                sessionActive = !_sessionState.value.roomId.isNullOrBlank(),
                reconnectEnabled = connectionRecoveryOwner.enabled,
                applicationInForeground = applicationInForeground
            )
        ) {
            ensureListenTogetherForegroundService("application_backgrounded")
        }
        updateBackgroundKeepAlive("application_backgrounded")
    }

    fun leaveRoom() {
        localControlOwner.clearCoalesced("leave")
        localControlOwner.clearOutbox()
        val snapshot = _sessionState.value
        membershipOwner.pauseBeforeLeave(snapshot, roomState.value)
        connectionRecoveryOwner.stop()
        socketHealthOwner.pendingRefreshAfterReconnect = false
        controllerLinkOwner.clear()
        socketHealthOwner.cancelForegroundProbe()
        stopListenTogetherSoftSyncRateRecheck()
        membershipOwner.notifyAndClearCredential(snapshot)
        socketHealthOwner.stopKeepAlive()
        listenerWatchdogOwner.stop()
        heartbeatOwner.reset()
        roomStateOwner.close()
        lastControllerLocalControlAtElapsedMs = 0L
        forwardedRequestDeduper.clear()
        localControlOwner.resetTransientRequests()
        listenerWatchdogOwner.resetRefreshTime()
        socketHealthOwner.clearLastMessage()
        webSocketConnectingAtElapsedMs = 0L
        socketHealthOwner.resetProtocolSupport()
        roomSocketEventOwner.reset()
        listenerWatchdogOwner.resetRecovery()
        ListenTogetherBackgroundKeepAlive.release("leave")
        PlayerManager.clearListenTogetherSafetyPause()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "leaveRoom(): roomId=${snapshot.roomId}, role=${snapshot.role}")
        webSocketClient.disconnect()
        recentEventTracker.clear()
        _sessionState.value = ListenTogetherSessionState(
            baseUrl = snapshot.baseUrl,
            userUuid = snapshot.userUuid,
            nickname = snapshot.nickname,
            connectionState = ListenTogetherConnectionState.DISCONNECTED
        )
    }

    fun sendPing(): Boolean = socketHealthOwner.sendPing()

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
        val currentState = roomState.value
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
        reconcileListenTogetherSoftSyncRateRecheck()
    }

    internal fun isControllerAudioLinkUnavailable(
        roomId: String?,
        stableKey: String?
    ): Boolean {
        return controllerLinkOwner.isUnavailable(roomId, stableKey)
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
        track: ListenTogetherTrack? = null,
        forceRefresh: Boolean = false
    ): ListenTogetherEvent {
        return eventFactory.buildRequestLinkEvent(
            stableKey = stableKey,
            currentIndex = currentIndex,
            track = track,
            forceRefresh = forceRefresh
        )
    }

    fun buildLinkReadyEvent(
        stableKey: String,
        positionMs: Long,
        streamUrlOverride: String? = null,
        streamUrlsOverride: List<String> = emptyList()
    ): ListenTogetherEvent? {
        return eventFactory.buildLinkReadyEvent(
            stableKey = stableKey,
            positionMs = positionMs,
            streamUrlOverride = streamUrlOverride,
            streamUrlsOverride = streamUrlsOverride
        )
    }

    fun buildLinkUnavailableEvent(stableKey: String): ListenTogetherEvent? {
        return eventFactory.buildLinkUnavailableEvent(stableKey)
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

    private fun updateSession(baseUrl: String, response: ListenTogetherRoomResponse) {
        val prepared = prepareListenTogetherSessionUpdate(
            baseUrl = baseUrl,
            response = response,
            previous = _sessionState.value
        )
        resetForSessionChange(prepared.sessionChanged)
        openSessionRoom(response.roomId)
        logSessionUpdate(response, prepared.resolvedWsUrl)
        _sessionState.value = prepared.applyTo(_sessionState.value)
        membershipOwner.retainCurrentCredential()
        applySessionRoomState(response)
    }

    private fun resetForSessionChange(changed: Boolean) {
        if (!changed) return
        localControlOwner.clearCoalesced("session_changed")
        localControlOwner.clearOutbox()
        socketHealthOwner.cancelForegroundProbe()
        stopListenTogetherSoftSyncRateRecheck()
        roomSocketEventOwner.reset()
        webSocketConnectingAtElapsedMs = 0L
        socketHealthOwner.resetProtocolSupport()
    }

    private fun openSessionRoom(roomId: String?) {
        if (roomStateOwner.activateIfPresent(roomId) != null) finishSessionRoomActivation()
    }

    private fun finishSessionRoomActivation() {
        listenerWatchdogOwner.resetRefreshTime()
        socketHealthOwner.clearLastMessage()
    }

    private fun logSessionUpdate(response: ListenTogetherRoomResponse, wsUrl: String?) {
        NPLogger.d(
            TAG,
            "updateSession(): roomId=${response.roomId}, role=${response.role}, wsUrl=${wsUrl.redactListenTogetherWsUrlForLog()}"
        )
    }

    private fun applySessionRoomState(response: ListenTogetherRoomResponse) {
        val state = response.state ?: return
        val accepted = acceptRoomState(
            state = state,
            expectedPositionMs = null,
            source = RoomStateSource.HTTP_SESSION_UPDATE
        ) ?: return
        applyRoomStateToPlayer(
            accepted.state,
            causeType = resolveListenTogetherJoinAutoPauseCause(
                autoPauseOnJoin = response.autoPauseOnJoin,
                role = _sessionState.value.role,
                state = accepted.state
            )
        )
    }

    private fun acceptRoomState(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource,
        cause: ListenTogetherCause? = null
    ): AcceptedRoomState? {
        val accepted = roomStateOwner.accept(
            state = state,
            expectedPositionMs = expectedPositionMs,
            source = source,
            cause = cause,
            currentUserId = _sessionState.value.userUuid,
            lastControllerLocalControlAtElapsedMs = lastControllerLocalControlAtElapsedMs,
            controllerLocalControlCooldownMs = CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS
        )
        localControlOwner.acknowledgeMember(cause)
        return accepted
    }

    private fun onRoomStateCommitted(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource
    ) {
        NPLogger.d(
            TAG,
            "commitRoomState(): source=${source.logName}, roomId=${state.roomId}, version=${state.version}, members=${state.members.size}, expectedPositionMs=$expectedPositionMs"
        )
        controllerLinkOwner.reconcileAvailability(state)
        ensureListenTogetherForegroundService("room_state:${state.version}")
        localControlOwner.onRoomStateCommitted(state.currentStableKey())
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
        connectionRecoveryOwner.recoverMissingListenerMembership(state, reason = "apply_room_state")
    }

    private fun recordWebSocketMessage(message: ListenTogetherSocketEnvelope): Boolean =
        roomStateOwner.recordSocketMessage(message)

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

    // 旧自建 Worker 可能仍依赖这条中转路径, 当前内置 Worker 已直接仲裁听众请求
    private fun handleMemberControlRequested(message: ListenTogetherSocketEnvelope) {
        val snapshot = _sessionState.value
        if (!isCurrentUserController(snapshot)) return
        if (
            !forwardedRequestDeduper.shouldProcess(
                requesterUuid = message.causedBy?.userUuid,
                sequence = message.requestSequence,
                eventId = message.causedBy?.eventId
            )
        ) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): ignore duplicate/outdated requester=${message.causedBy?.userUuid}, requestSequence=${message.requestSequence}, eventId=${message.causedBy?.eventId}"
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
        if (
            SystemClock.elapsedRealtime() - lastControllerLocalControlAtElapsedMs <
            CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS
        ) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): controller local action wins, skip requester=${message.causedBy?.userUuid}"
            )
            publishControllerHeartbeatIfNeeded(force = true, reason = "controller_priority")
            return
        }
        NPLogger.d(
            TAG,
            "handleMemberControlRequested(): requester=${message.causedBy?.userUuid}, type=${message.causedBy?.type}, commitType=${forwardedEvent.type}"
        )
        applyForwardedControllerRequestLocally(message, forwardedEvent)
        markOutboundEvent(forwardedEvent.eventId)
        noteOutboundSync()
        if (!sendControlEventPureWebSocket(forwardedEvent, "forwarded_member_control")) {
            NPLogger.w(
                TAG,
                "handleMemberControlRequested(): websocket unavailable, requester=${message.causedBy?.userUuid}"
            )
        }
    }

    private suspend fun handleLocalPlaybackCommand(command: PlaybackCommand) {
        val snapshot = _sessionState.value
        NPLogger.d(
            TAG,
            "handleLocalPlaybackCommand(): type=${command.type}, source=${command.source}, connection=${snapshot.connectionState}, role=${currentRole(snapshot)}, roomId=${snapshot.roomId}"
        )
        if (command.source != PlaybackCommandSource.LOCAL) return
        if (snapshot.roomId.isNullOrBlank()) return
        resolveControlBlockReason(snapshot, roomState.value, command)?.let { reason ->
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
        noteControllerLocalControl(command)
        localControlOwner.enqueueOrDispatch(event, snapshot.roomId)
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
        val currentState = roomState.value
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
            currentState?.let { controllerLinkOwner.maybeRequest(it, "suppress_local_control:${event.type}", force = true) }
            return true
        }
        return false
    }

    private fun shouldRejectForwardedMemberControl(
        message: ListenTogetherSocketEnvelope,
        forwardedEvent: ListenTogetherEvent
    ): Boolean {
        val roomState = roomState.value
        // 房态未落地(未知)时对转发成员控制一律 fail-closed
        // 否则 settings.normalized() 回退默认 allowMemberControl=true 会造成安全门误放行
        if (roomState == null) {
            NPLogger.w(
                TAG,
                "shouldRejectForwardedMemberControl(): room state unknown, reject forwarded control, requester=${message.causedBy?.userUuid}, type=${message.causedBy?.type}"
            )
            publishControllerHeartbeatIfNeeded(force = true, reason = "reject_member_control_room_unknown")
            return true
        }
        // 服务端侧鉴权: 成员控制关闭时拒绝一切非控制端发起的转发请求, 防止改造端越权
        if (
            shouldRejectForwardedListenTogetherMemberControl(
                requesterUuid = message.causedBy?.userUuid,
                controllerUserUuid = roomState.controllerUserUuid,
                allowMemberControl = roomState.settings.normalized().allowMemberControl
            )
        ) {
            NPLogger.w(
                TAG,
                "shouldRejectForwardedMemberControl(): member control disabled, requester=${message.causedBy?.userUuid}, type=${message.causedBy?.type}"
            )
            publishControllerHeartbeatIfNeeded(force = true, reason = "reject_member_control_disabled")
            return true
        }
        val cause = message.causedBy ?: return false
        val requestType = cause.type ?: return false
        if (requestType !in trackBoundRequestControlEventTypes) return false
        val currentStableKey = roomState.currentStableKey()
        val requestedStableKey = forwardedEvent.requestedStableKey()
        if (isListenTogetherMemberControlTargetCurrent(requestType, requestedStableKey, currentStableKey)) {
            return false
        }
        NPLogger.w(
            TAG,
            "shouldRejectForwardedMemberControl(): stale target, requestType=$requestType, requested=$requestedStableKey, current=$currentStableKey, requester=${cause.userUuid}"
        )
        publishControllerHeartbeatIfNeeded(force = true, reason = "reject_stale_member_control")
        return true
    }

    private fun applyForwardedControllerRequestLocally(
        message: ListenTogetherSocketEnvelope,
        committedEvent: ListenTogetherEvent
    ) {
        val committed = roomStateOwner.commitForwarded(message, committedEvent) ?: return
        applyRoomStateToPlayer(
            committed.state,
            committed.causeType,
            committed.expectedPositionMs
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

    private fun updateBackgroundKeepAlive(reason: String) {
        val snapshot = _sessionState.value
        val shouldHold = shouldHoldListenTogetherBackgroundKeepAlive(
            sessionActive = !snapshot.roomId.isNullOrBlank(),
            reconnectEnabled = connectionRecoveryOwner.enabled,
            applicationInForeground = applicationInForeground
        )
        if (shouldHold) {
            if (!AppContainer.isInitialized()) return
            ListenTogetherBackgroundKeepAlive.renew(
                context = AppContainer.applicationContext,
                reason = reason
            )
        } else {
            ListenTogetherBackgroundKeepAlive.release(reason)
        }
    }

    private fun reconcileListenTogetherSoftSyncRateRecheck() {
        if (abs(PlayerManager.listenTogetherSyncPlaybackRate - 1f) < 0.001f) {
            stopListenTogetherSoftSyncRateRecheck()
            return
        }
        if (softSyncRateRecheckJob?.isActive == true) return
        softSyncRateRecheckJob = mainScope.launch {
            try {
                while (isActive) {
                    delay(SOFT_SYNC_RECHECK_INTERVAL_MS.milliseconds)
                    val snapshot = _sessionState.value
                    val state = roomState.value
                    val targetSong = state?.targetSongItem()
                    val currentSong = PlayerManager.currentSongFlow.value
                    val expectedPositionMs = if (state != null && targetSong != null) {
                        state.playback.expectedPositionMs(
                            serverClockOffsetMs = socketHealthOwner.serverClockOffsetMs,
                            durationMs = targetSong.durationMs
                        )
                    } else {
                        0L
                    }
                    val localPositionMs = PlayerManager.playbackPositionFlow.value
                        .coerceAtLeast(0L)
                    val signedDriftMs = expectedPositionMs - localPositionMs
                    val action = resolveListenTogetherSoftSyncRecheckAction(
                        currentRate = PlayerManager.listenTogetherSyncPlaybackRate,
                        sessionConnected =
                            snapshot.connectionState == ListenTogetherConnectionState.CONNECTED,
                        isController = isCurrentUserController(snapshot),
                        desiredPlaying = state?.playback?.state == "playing",
                        localPlaying = PlayerManager.isPlayingFlow.value,
                        currentTrackMatchesRoom =
                            targetSong != null && currentSong?.sameTrackAs(targetSong) == true,
                        signedDriftMs = signedDriftMs,
                        softSyncMinDriftMs = SOFT_SYNC_MIN_DRIFT_MS,
                        forcePositionSyncDriftMs = PLAYING_DRIFT_FORCE_SYNC_MS
                    )
                    when (action) {
                        ListenTogetherSoftSyncRecheckAction.NONE -> return@launch
                        ListenTogetherSoftSyncRecheckAction.RESET_RATE -> {
                            PlayerManager.resetListenTogetherSyncPlaybackRate()
                            return@launch
                        }

                        ListenTogetherSoftSyncRecheckAction.FORCE_POSITION_SYNC -> {
                            state?.let {
                                applyRoomStateToPlayer(
                                    state = it,
                                    causeType = "SOFT_SYNC_RECHECK",
                                    expectedPositionMs = expectedPositionMs
                                )
                            } ?: PlayerManager.resetListenTogetherSyncPlaybackRate()
                            return@launch
                        }

                        ListenTogetherSoftSyncRecheckAction.KEEP_RATE -> {
                            val rate = resolveListenTogetherSoftSyncPlaybackRate(
                                driftMs = abs(signedDriftMs),
                                signedDriftMs = signedDriftMs,
                                allowSoftSync = true,
                                isController = false,
                                softSyncMinDriftMs = SOFT_SYNC_MIN_DRIFT_MS,
                                softSyncFastDriftMs = SOFT_SYNC_FAST_DRIFT_MS,
                                playingDriftForceSyncMs = PLAYING_DRIFT_FORCE_SYNC_MS
                            )
                            if (rate == null) {
                                PlayerManager.resetListenTogetherSyncPlaybackRate()
                                return@launch
                            }
                            PlayerManager.setListenTogetherSyncPlaybackRate(rate)
                        }
                    }
                }
            } finally {
                if (softSyncRateRecheckJob === coroutineContext[Job]) {
                    softSyncRateRecheckJob = null
                }
            }
        }
    }

    private fun stopListenTogetherSoftSyncRateRecheck() {
        softSyncRateRecheckJob?.cancel()
        softSyncRateRecheckJob = null
    }

    private fun sendControlEventPureWebSocket(
        event: ListenTogetherEvent,
        reason: String
    ): Boolean {
        val snapshot = _sessionState.value
        val expectedRoomId = snapshot.roomId
        if (snapshot.connectionState != ListenTogetherConnectionState.CONNECTED) {
            handleWebSocketControlSendFailure(
                event = event,
                reason = "$reason:not_connected"
            )
            sendControlEventOverHttpFallback(
                event = event,
                reason = "$reason:not_connected",
                expectedRoomId = expectedRoomId
            )
            return false
        }
        val sent = sendControlEventOverWebSocket(event)
        if (!sent) {
            handleWebSocketControlSendFailure(
                event = event,
                reason = "$reason:send_failed"
            )
            sendControlEventOverHttpFallback(
                event = event,
                reason = "$reason:send_failed",
                expectedRoomId = expectedRoomId
            )
        }
        return sent
    }

    private fun trySendTrackFinishedLegacyFallback(errorMessage: String): Boolean {
        val sent = localControlOwner.tryTrackFinishedLegacyFallback(errorMessage)
        if (sent) _sessionState.value = _sessionState.value.copy(lastError = null)
        return sent
    }

    private fun handleWebSocketControlSendFailure(
        event: ListenTogetherEvent,
        reason: String
    ) {
        socketHealthOwner.pendingRefreshAfterReconnect = true
        val resolvedMessage = AppContainer.applicationContext.getString(R.string.listen_together_error_reconnecting)
        NPLogger.w(
            TAG,
            "handleWebSocketControlSendFailure(): type=${event.type}, eventId=${event.eventId}, reason=$reason"
        )
        _sessionState.value = _sessionState.value.copy(lastError = resolvedMessage)
        connectionRecoveryOwner.scheduleReconnect("control_send_failed:${event.type}:$reason")
    }

    private fun sendControlEventOverHttpFallback(
        event: ListenTogetherEvent,
        reason: String,
        expectedRoomId: String?
    ) {
        val snapshot = _sessionState.value
        val baseUrl = snapshot.baseUrl
        val token = snapshot.token
        if (
            baseUrl.isNullOrBlank() ||
            snapshot.roomId.isNullOrBlank() ||
            token.isNullOrBlank() ||
            expectedRoomId.isNullOrBlank() ||
            snapshot.roomId != expectedRoomId
        ) {
            NPLogger.d(TAG, "sendControlEventOverHttpFallback(): skipped, missing session, reason=$reason")
            return
        }
        scope.launch {
            if (_sessionState.value.roomId != expectedRoomId) {
                NPLogger.d(
                    TAG,
                    "sendControlEventOverHttpFallback(): skip inactive room, roomId=$expectedRoomId"
                )
                return@launch
            }
            runCatching {
                api.sendControlEvent(baseUrl, expectedRoomId, token, event)
            }.onSuccess { response ->
                handleHttpFallbackControlResponse(
                    response = response,
                    event = event,
                    reason = reason,
                    expectedRoomId = expectedRoomId
                )
            }.onFailure { error ->
                if (_sessionState.value.roomId != expectedRoomId) return@onFailure
                val resolvedError = error.message ?: error.javaClass.simpleName
                NPLogger.w(
                    TAG,
                    "sendControlEventOverHttpFallback(): failed, type=${event.type}, reason=$reason, error=$resolvedError",
                    error
                )
                _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
                if (connectionRecoveryOwner.handleTerminalFailure(resolvedError, "http_control_fallback")) {
                    return@onFailure
                }
                connectionRecoveryOwner.recoverFromMembershipError(resolvedError, "http_control_fallback")
            }
        }
    }

    private fun handleHttpFallbackControlResponse(
        response: ListenTogetherControlResponse,
        event: ListenTogetherEvent,
        reason: String,
        expectedRoomId: String
    ) {
        if (_sessionState.value.roomId != expectedRoomId) {
            NPLogger.d(
                TAG,
                "handleHttpFallbackControlResponse(): skip inactive room, roomId=$expectedRoomId"
            )
            return
        }
        if (!response.ok || !response.error.isNullOrBlank()) {
            val resolvedError = response.error ?: "control event rejected"
            NPLogger.w(
                TAG,
                "handleHttpFallbackControlResponse(): rejected, type=${event.type}, reason=$reason, error=$resolvedError"
            )
            _sessionState.value = _sessionState.value.copy(lastError = resolvedError)
            if (localControlOwner.tryQueueMutationLegacyFallback(resolvedError, event.eventId)) {
                return
            }
            if (trySendTrackFinishedLegacyFallback(resolvedError)) {
                return
            }
            if (connectionRecoveryOwner.handleTerminalFailure(resolvedError, "http_control_fallback_response")) {
                return
            }
            connectionRecoveryOwner.recoverFromMembershipError(resolvedError, "http_control_fallback_response")
            return
        }
        _sessionState.value = _sessionState.value.copy(lastError = null)
        localControlOwner.acknowledge(event.eventId)
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
            controllerLinkOwner.maybeRequest(accepted.state, applied.causedBy?.type)
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
            if (connectionRecoveryOwner.handleTerminalFailure(resolvedError, "refresh_after_reconnect")) {
                return@onFailure
            }
            if (!connectionRecoveryOwner.recoverFromMembershipError(resolvedError, "refresh_after_reconnect")) {
                connectionRecoveryOwner.scheduleReconnect("refresh_state_failed:$reason")
            }
        }
    }

    private fun noteOutboundSync() = heartbeatOwner.noteOutboundSync()

    private fun currentRole(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): String? {
        return resolveListenTogetherSessionRole(
            sessionUserId = sessionState.userUuid,
            fallbackRole = sessionState.role,
            state = roomState.value
        )
    }

    private fun isCurrentUserController(
        sessionState: ListenTogetherSessionState = _sessionState.value
    ): Boolean = currentRole(sessionState) == "controller"

    private fun markOutboundEvent(eventId: String?) = recentEventTracker.markOutbound(eventId)

    private fun closeRoomLocally(reason: String?) {
        val snapshot = _sessionState.value
        NPLogger.w(
            TAG,
            "closeRoomLocally(): roomId=${snapshot.roomId}, role=${snapshot.role}, reason=$reason, lastAppliedVersion=${roomStateOwner.lastAppliedVersion()}"
        )
        connectionRecoveryOwner.stop()
        localControlOwner.clearOutbox()
        socketHealthOwner.pendingRefreshAfterReconnect = false
        controllerLinkOwner.clear()
        socketHealthOwner.cancelForegroundProbe()
        stopListenTogetherSoftSyncRateRecheck()
        socketHealthOwner.stopKeepAlive()
        listenerWatchdogOwner.stop()
        heartbeatOwner.reset()
        roomStateOwner.close()
        lastControllerLocalControlAtElapsedMs = 0L
        forwardedRequestDeduper.clear()
        localControlOwner.clearPendingMemberRequest()
        listenerWatchdogOwner.resetRefreshTime()
        socketHealthOwner.clearLastMessage()
        webSocketConnectingAtElapsedMs = 0L
        socketHealthOwner.resetProtocolSupport()
        roomSocketEventOwner.reset()
        listenerWatchdogOwner.resetRecovery()
        ListenTogetherBackgroundKeepAlive.release("room_closed")
        PlayerManager.clearListenTogetherSafetyPause()
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        webSocketClient.disconnect(code = 1000, reason = "room_closed")
        val closureReason = normalizeListenTogetherRoomClosureReason(reason)
        val normalClosure = isNormalListenTogetherRoomClosureReason(closureReason)
        _sessionState.value = ListenTogetherSessionState(
            baseUrl = snapshot.baseUrl,
            userUuid = snapshot.userUuid,
            nickname = snapshot.nickname,
            connectionState = ListenTogetherConnectionState.DISCONNECTED,
            lastError = closureReason.takeUnless { normalClosure },
            roomNotice = closureReason ?: "room_closed"
        )
    }

    private fun roomNoticeForState(
        state: ListenTogetherRoomState?,
        fallbackMessage: String? = null,
        showControllerReconnected: Boolean = false
    ): String? {
        return resolveListenTogetherRoomNotice(
            state = state,
            fallbackMessage = fallbackMessage,
            controllerGracePeriodMs = CONTROLLER_GRACE_PERIOD_MS,
            showControllerReconnected = showControllerReconnected
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
        val state = roomState.value ?: return
        if (state.roomStatus == ListenTogetherRoomStatuses.CLOSED) return
        if (!PlayerManager.currentSongFlow.value.isShareableForListenTogether()) {
            NPLogger.d(TAG, "publishControllerHeartbeatIfNeeded(): skip non-shareable current track, reason=$reason")
            return
        }
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

    companion object {
        private const val TAG = "NERI-ListenTogether"
        private const val PLAYING_DRIFT_FORCE_SYNC_MS = 2_500L
        private const val HEARTBEAT_DRIFT_FORCE_SYNC_MS = 5 * SECOND_MS
        private const val PAUSED_DRIFT_FORCE_SYNC_MS = 800L
        private const val TRACK_SWITCH_FORCE_SYNC_MS = 500L
        private const val TRACK_SWITCH_GRACE_PERIOD_MS = 800L
        private const val CONTROLLER_GRACE_PERIOD_MS = 10 * MINUTE_MS
        internal const val LISTEN_TOGETHER_SOCKET_KEEP_ALIVE_INTERVAL_MS = 20 * SECOND_MS
        private const val CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS = 1_200L
        private const val SOFT_SYNC_MIN_DRIFT_MS = 600L
        private const val SOFT_SYNC_FAST_DRIFT_MS = 1_500L
        private const val SOFT_SYNC_RECHECK_INTERVAL_MS = 500L
        private const val UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS = 2 * SECOND_MS
    }
}
