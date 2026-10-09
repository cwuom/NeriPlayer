package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherListenerControlSuppression
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherListenerSuppressionReason
import moe.ouom.neriplayer.data.ltw.session.control.forwardedListenTogetherRejectionReason
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherSafetyPauseResumeOwner
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherSafetyPauseResumePort

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherPlatformHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.ltw.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.data.ltw.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.data.ltw.control.nextListenTogetherEventId
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.ws.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.api.ltw.ws.redactListenTogetherWsUrlForLog
import moe.ouom.neriplayer.data.ltw.playback.currentStableKey
import moe.ouom.neriplayer.data.ltw.playback.expectedPositionMs
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlayerStateApplier
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlayerStateApplierConfig
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherControlResponse
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.message.http.ListenTogetherStateResponse
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.ltw.session.AcceptedRoomState
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherBackgroundKeepAlive
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherForwardedRequestDeduper
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherLocalControlOwner
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherHttpControlFallbackOwner
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherHttpControlFallbackPort
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherLocalControlPort
import moe.ouom.neriplayer.data.ltw.session.liveness.LISTEN_TOGETHER_SOFT_SYNC_RECHECK_INTERVAL_MS
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherSoftSyncRateRecheckOwner
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherSoftSyncRecheckConfig
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherHeartbeatOwner
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherHeartbeatPort
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherListenerWatchdogOwner
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherListenerWatchdogPort
import moe.ouom.neriplayer.data.ltw.session.liveness.ListenTogetherListenerWatchdogSnapshot
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherSocketHealthOwner
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherSocketHealthPort
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherConnectionRecoveryOwner
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherConnectionRecoveryPort
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherRejoinIdentity
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherControllerLinkOwner
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkEventPort
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkSessionPort
import moe.ouom.neriplayer.data.ltw.session.state.ListenTogetherRoomStateObserver
import moe.ouom.neriplayer.data.ltw.session.state.ListenTogetherRoomStateOwner
import moe.ouom.neriplayer.data.ltw.session.socket.ListenTogetherRoomSocketEventOwner
import moe.ouom.neriplayer.data.ltw.session.socket.ListenTogetherRoomSocketEventPort
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherRoomMembershipOwner
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherRoomMembershipPort
import moe.ouom.neriplayer.data.ltw.session.membership.ListenTogetherApiMembershipTransport
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherSocketControlResultOwner
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherSocketControlResultPort
import moe.ouom.neriplayer.data.ltw.session.connection.ListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.data.ltw.session.control.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.data.model.ltw.session.RoomStateSource
import moe.ouom.neriplayer.data.ltw.session.state.normalized
import moe.ouom.neriplayer.data.ltw.session.control.resolveListenTogetherControlBlockReason
import moe.ouom.neriplayer.data.ltw.session.connection.resolveListenTogetherForegroundRecoveryAction
import moe.ouom.neriplayer.data.ltw.session.membership.resolveListenTogetherRoomNotice
import moe.ouom.neriplayer.data.ltw.session.connection.shouldHoldListenTogetherBackgroundKeepAlive
import moe.ouom.neriplayer.data.ltw.session.connection.shouldHoldListenTogetherBackgroundWakeLock
import moe.ouom.neriplayer.data.ltw.session.membership.isNormalListenTogetherRoomClosureReason
import moe.ouom.neriplayer.data.ltw.session.membership.normalizeListenTogetherRoomClosureReason
import moe.ouom.neriplayer.data.ltw.session.membership.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.data.ltw.session.state.shouldApplyListenTogetherRoomStateToPlayer
import moe.ouom.neriplayer.data.ltw.session.state.prepareListenTogetherSessionUpdate
import moe.ouom.neriplayer.data.ltw.validation.requireValidListenTogetherRoomId
import moe.ouom.neriplayer.common.units.MINUTE_MS
import moe.ouom.neriplayer.common.units.SECOND_MS
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ListenTogetherSessionManager(
    private val api: ListenTogetherApi,
    private val webSocketClient: ListenTogetherWebSocketClient,
    private val playback: ListenTogetherPlaybackHost,
    private val platform: ListenTogetherPlatformHost,
    private val songMapper: ListenTogetherSongMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
) : ListenTogetherSongMapper by songMapper {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    @Volatile
    private var started = false

    private val backgroundKeepAlive = ListenTogetherBackgroundKeepAlive()
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
        songMapper = songMapper,
        formatValidationError = platform::validationMessage,
        port = object : ListenTogetherRoomMembershipPort {
            override fun currentSession(): ListenTogetherSessionState = _sessionState.value
            override fun repeatMode(): Int = playback.repeatModeFlow.value
            override fun shuffleEnabled(): Boolean = playback.shuffleModeFlow.value
            override fun shuffleRestoreSongs(): List<SongItem>? = playback.shuffleRestorePlaylistReference
            override fun applyRoomResponse(baseUrl: String, response: ListenTogetherRoomResponse) =
                updateSession(baseUrl, response)
            override fun pauseForDeparture() {
                playback.pauseImpl(
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
                    lastError = platform.message(CoreCommonR.string.listen_together_error_rejoining)
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
        },
        networkMonitor = platform.networkMonitor
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
        elapsedRealtimeMs = elapsedRealtimeMs,
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
        elapsedRealtimeMs = elapsedRealtimeMs
    )
    val roomState: StateFlow<ListenTogetherRoomState?> = roomStateOwner.roomState

    private val heartbeatOwner = ListenTogetherHeartbeatOwner(
        scope = scope,
        port = object : ListenTogetherHeartbeatPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override fun isController(session: ListenTogetherSessionState): Boolean =
                isCurrentUserController(session)
            override fun currentTrackShareable(): Boolean =
                playback.currentSongFlow.value.isShareableForListenTogether()
            override fun playbackStateName(): String = currentLocalPlaybackStateName()
            override fun playbackPositionMs(): Long = playback.playbackPositionFlow.value
            override fun buildHeartbeat(state: String, positionMs: Long): ListenTogetherEvent =
                buildHeartbeatEvent(state, positionMs, includeQueue = false)
            override fun sendHeartbeat(event: ListenTogetherEvent) {
                markOutboundEvent(event.eventId)
                sendControlEventPureWebSocket(event, "heartbeat")
            }
        },
        elapsedRealtimeMs = elapsedRealtimeMs
    )

    private val listenerWatchdogOwner = ListenTogetherListenerWatchdogOwner(
        scope = scope,
        playback = playback,
        songMapper = songMapper,
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
        inSyncDriftMs = SOFT_SYNC_MIN_DRIFT_MS,
        elapsedRealtimeMs = elapsedRealtimeMs
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
        elapsedRealtimeMs = elapsedRealtimeMs,
        wallTimeMs = System::currentTimeMillis
    )


    private val eventFactory = ListenTogetherEventFactory(
        playback = playback,
        songMapper = songMapper,
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
                publishControllerHeartbeatIfNeeded(reason)
        },
        playback = playback,
        elapsedRealtimeMs = elapsedRealtimeMs,
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
        playback = playback,
        songMapper = songMapper,
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
        serverClockOffsetProvider = { socketHealthOwner.serverClockOffsetMs },
        elapsedRealtimeMs = elapsedRealtimeMs
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
                publishControllerHeartbeatIfNeeded(reason)
            override fun pauseClosedRoomPlayback() {
                mainScope.launch {
                    playback.pauseImpl(
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

    private val httpControlFallbackOwner = ListenTogetherHttpControlFallbackOwner(
        scope = scope,
        port = object : ListenTogetherHttpControlFallbackPort {
            override fun session(): ListenTogetherSessionState = _sessionState.value
            override suspend fun send(baseUrl: String, roomId: String, token: String, event: ListenTogetherEvent) =
                api.sendControlEvent(baseUrl, roomId, token, event)
            override fun setLastError(error: String?) {
                _sessionState.value = _sessionState.value.copy(lastError = error)
            }
            override fun acknowledge(eventId: String?) = localControlOwner.acknowledge(eventId)
            override fun tryQueueMutationLegacyFallback(error: String, eventId: String?): Boolean =
                localControlOwner.tryQueueMutationLegacyFallback(error, eventId)
            override fun tryTrackFinishedLegacyFallback(error: String): Boolean =
                trySendTrackFinishedLegacyFallback(error)
            override fun handleTerminalFailure(error: String, reason: String): Boolean =
                connectionRecoveryOwner.handleTerminalFailure(error, reason)
            override fun recoverFromMembershipError(error: String, reason: String) {
                connectionRecoveryOwner.recoverFromMembershipError(error, reason)
            }
            override fun accept(state: ListenTogetherRoomState, expectedPositionMs: Long?, cause: ListenTogetherCause?): AcceptedRoomState? =
                acceptRoomState(state, expectedPositionMs, RoomStateSource.HTTP_CONTROL_FALLBACK, cause)
            override fun isController(): Boolean = isCurrentUserController()
            override fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?) =
                applyRoomStateToPlayer(state, causeType, expectedPositionMs)
            override fun requestControllerLink(state: ListenTogetherRoomState, causeType: String?) =
                controllerLinkOwner.maybeRequest(state, causeType)
        }
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


    fun close() {
        disconnectWebSocket()
        scope.cancel()
        mainScope.cancel()
    }

    fun start() {
        if (started) return
        started = true
        NPLogger.d(TAG, "start(): subscribe playbackCommandFlow")
        scope.launch {
            playback.playbackCommandFlow.collectLatest(::handleLocalPlaybackCommand)
        }
        scope.launch {
            playback.currentMediaUrlFlow.collectLatest(controllerLinkOwner::onResolvedStreamUrlChanged)
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
        val validatedRoomId = requireValidListenTogetherRoomId(roomId, platform::validationMessage)
        NPLogger.d(TAG, "refreshRoomState(): baseUrl=$baseUrl, roomId=$validatedRoomId")
        val sentAtElapsedMs = elapsedRealtimeMs()
        val sentAtWallMs = System.currentTimeMillis()
        val session = _sessionState.value
        val bearerToken = httpBearerToken(session, baseUrl, validatedRoomId)
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
        applyHttpRoomState(response, causeType)
        NPLogger.d(
            TAG,
            "refreshRoomState(): ok=${response.ok}, version=${response.state?.version}, expectedPositionMs=${response.expectedPositionMs}"
        )
        return response
    }

    private fun httpBearerToken(session: ListenTogetherSessionState, baseUrl: String, roomId: String): String? {
        if (!session.baseUrl.equals(baseUrl, ignoreCase = true) || !session.roomId.equals(roomId, ignoreCase = true)) return null
        return session.token
    }

    private fun applyHttpRoomState(response: ListenTogetherStateResponse, causeType: String?) {
        val state = response.state ?: return
        val accepted = acceptRoomState(state, response.expectedPositionMs, RoomStateSource.HTTP_REFRESH) ?: return
        if (isCurrentUserController()) return
        applyRoomStateToPlayer(accepted.state, causeType, accepted.expectedPositionMs)
        controllerLinkOwner.maybeRequest(accepted.state, "refresh_room_state")
    }

    private val safetyPauseResumeOwner = ListenTogetherSafetyPauseResumeOwner(scope, mainScope, playback,
        object : ListenTogetherSafetyPauseResumePort {
            override fun session() = _sessionState.value
            override fun isController(session: ListenTogetherSessionState) = isCurrentUserController(session)
            override fun room() = roomState.value
            override suspend fun refresh(baseUrl: String, roomId: String) = refreshRoomState(baseUrl, roomId)
            override fun apply(state: ListenTogetherRoomState, cause: String, expectedPositionMs: Long?): Boolean {
                val applied = playerStateApplier.apply(state, cause, expectedPositionMs)
                reconcileListenTogetherSoftSyncRateRecheck()
                return applied
            }
            override fun setError(error: String) { _sessionState.value = _sessionState.value.copy(lastError = error) }
        }
    )

    fun resumeListenerAfterSafetyPause() = safetyPauseResumeOwner.resume()

    fun connectWebSocket() {
        connectionRecoveryOwner.beginConnect()
        val wsUrl = _sessionState.value.wsUrl ?: return
        webSocketConnectingAtElapsedMs = elapsedRealtimeMs()
        ensureListenTogetherForegroundService("connect_websocket")
        NPLogger.d(TAG, "connectWebSocket(): wsUrl=${wsUrl.redactListenTogetherWsUrlForLog()}")
        _sessionState.value = _sessionState.value.copy(
            connectionState = ListenTogetherConnectionState.CONNECTING,
            lastError = null
        )
        connectionRecoveryOwner.watchNetwork()
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
                    publishControllerHeartbeatIfNeeded("socket_open")
                }

                override fun onMessage(message: ListenTogetherSocketEnvelope) {
                    receiveSocketEnvelope(message)
                }

                override fun onClosed(code: Int, reason: String) {
                    webSocketConnectingAtElapsedMs = 0L
                    heartbeatOwner.stop()
                    listenerWatchdogOwner.stop()
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
                    listenerWatchdogOwner.stop()
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
        httpControlFallbackOwner.reset()
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
        backgroundKeepAlive.release("disconnect")
        playback.resetListenTogetherSyncPlaybackRate()
        NPLogger.d(TAG, "disconnectWebSocket()")
        webSocketClient.disconnect()
        _sessionState.value = _sessionState.value.copy(
            connectionState = ListenTogetherConnectionState.DISCONNECTED,
            roomNotice = null
        )
    }

    fun onApplicationForegrounded() {
        applicationInForeground = true
        backgroundKeepAlive.release("foreground")
        val snapshot = _sessionState.value
        when (
            resolveListenTogetherForegroundRecoveryAction(
                connectionState = snapshot.connectionState,
                roomId = snapshot.roomId,
                wsUrl = snapshot.wsUrl,
                reconnectEnabled = connectionRecoveryOwner.enabled,
                connectingSinceElapsedMs = webSocketConnectingAtElapsedMs,
                nowElapsedMs = elapsedRealtimeMs()
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
                val probeStartedAtElapsedMs = elapsedRealtimeMs()
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
        httpControlFallbackOwner.reset()
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
        backgroundKeepAlive.release("leave")
        playback.clearListenTogetherSafetyPause()
        playback.resetListenTogetherSyncPlaybackRate()
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
        val baseUrl = controlField(snapshot.baseUrl)
        if (baseUrl == null) {
            return ListenTogetherControlResponse(ok = false, error = "baseUrl missing")
        }
        val roomId = controlField(snapshot.roomId)
        if (roomId == null) {
            return ListenTogetherControlResponse(ok = false, error = "roomId missing")
        }
        val token = controlField(snapshot.token)
        if (token == null) {
            return ListenTogetherControlResponse(ok = false, error = "token missing")
        }
        return api.sendControlEvent(baseUrl, roomId, token, event)
    }

    private fun controlField(value: String?): String? = if (value.isNullOrBlank()) null else value

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
        if (!isMainThread()) {
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

    fun isControllerAudioLinkUnavailable(
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
        httpControlFallbackOwner.reset()
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
        if (platform.isPlaybackServiceReady()) {
            return
        }
        runCatching {
            platform.startForegroundSync(reason)
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
        if (!shouldProcessForwardedRequest(message)) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): ignore duplicate/outdated requester=${message.causedBy}, requestSequence=${message.requestSequence}, cause=${message.causedBy}"
            )
            return
        }
        val forwardedEvent = buildControllerCommitEventFromForwardedRequest(message) ?: run {
            NPLogger.w(
                TAG,
                "handleMemberControlRequested(): invalid forwarded request type=${message.causedBy}, requester=${message.causedBy}"
            )
            return
        }
        if (shouldRejectForwardedMemberControl(message, forwardedEvent)) {
            return
        }
        if (controllerLocalControlHasPriority()) {
            NPLogger.d(
                TAG,
                "handleMemberControlRequested(): controller local action wins, skip requester=${message.causedBy}"
            )
            publishControllerHeartbeatIfNeeded("controller_priority")
            return
        }
        NPLogger.d(
            TAG,
            "handleMemberControlRequested(): requester=${message.causedBy}, type=${message.causedBy}, commitType=${forwardedEvent.type}"
        )
        applyForwardedControllerRequestLocally(message, forwardedEvent)
        markOutboundEvent(forwardedEvent.eventId)
        noteOutboundSync()
        if (!sendControlEventPureWebSocket(forwardedEvent, "forwarded_member_control")) {
            NPLogger.w(
                TAG,
                "handleMemberControlRequested(): websocket unavailable, requester=${message.causedBy}"
            )
        }
    }

    private fun shouldProcessForwardedRequest(message: ListenTogetherSocketEnvelope): Boolean =
        forwardedRequestDeduper.shouldProcess(message.causedBy?.userUuid, message.requestSequence, message.causedBy?.eventId)

    private fun controllerLocalControlHasPriority(): Boolean =
        elapsedRealtimeMs() - lastControllerLocalControlAtElapsedMs < CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS

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

    private val listenerControlSuppression = ListenTogetherListenerControlSuppression(playback, songMapper)

    private fun shouldSuppressLocalListenerControlEvent(event: ListenTogetherEvent): Boolean {
        val state = roomState.value
        val reason = listenerControlSuppression.reason(event, isCurrentUserController(), state) ?: return false
        NPLogger.w(TAG, "listener control suppressed: type=${event.type}, reason=$reason")
        if (reason == ListenTogetherListenerSuppressionReason.AWAITING_STREAM && state != null) {
            controllerLinkOwner.maybeRequest(state, "suppress_local_control:${event.type}", force = true)
        }
        return true
    }

    private fun shouldRejectForwardedMemberControl(message: ListenTogetherSocketEnvelope, event: ListenTogetherEvent): Boolean {
        val reason = forwardedListenTogetherRejectionReason(roomState.value, message, event) ?: return false
        NPLogger.w(TAG, "forwarded member control rejected: type=${event.type}, reason=$reason")
        publishControllerHeartbeatIfNeeded(reason)
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
            context = platform.applicationContext,
            sessionRole = currentRole(sessionState),
            roomState = roomState,
            commandType = command.type
        )
    }

    private fun updateBackgroundKeepAlive(reason: String) {
        if (shouldHoldBackgroundWakeLock()) {
            if (!platform.isInitialized()) return
            backgroundKeepAlive.renew(
                context = platform.applicationContext,
                reason = reason
            )
        } else {
            backgroundKeepAlive.release(reason)
        }
    }

    private fun shouldHoldBackgroundWakeLock(): Boolean {
        val snapshot = _sessionState.value
        return shouldHoldListenTogetherBackgroundWakeLock(
            keepAliveNeeded = shouldHoldListenTogetherBackgroundKeepAlive(
                sessionActive = !snapshot.roomId.isNullOrBlank(),
                reconnectEnabled = connectionRecoveryOwner.enabled,
                applicationInForeground = applicationInForeground
            ),
            isController = isCurrentUserController(snapshot),
            playbackServiceForeground = platform.isPlaybackServiceReady(),
            reconnecting = snapshot.connectionState != ListenTogetherConnectionState.CONNECTED
        )
    }

    private val softSyncRateRecheckOwner = ListenTogetherSoftSyncRateRecheckOwner(
        scope = mainScope,
        playback = playback,
        songMapper = songMapper,
        config = ListenTogetherSoftSyncRecheckConfig(LISTEN_TOGETHER_SOFT_SYNC_RECHECK_INTERVAL_MS, SOFT_SYNC_MIN_DRIFT_MS, SOFT_SYNC_FAST_DRIFT_MS, PLAYING_DRIFT_FORCE_SYNC_MS),
        session = { _sessionState.value },
        room = { roomState.value },
        isController = ::isCurrentUserController,
        serverClockOffset = { socketHealthOwner.serverClockOffsetMs },
        applyRoom = { state, cause, position -> applyRoomStateToPlayer(state, cause, position) }
    )

    private fun reconcileListenTogetherSoftSyncRateRecheck() = softSyncRateRecheckOwner.reconcile()

    private fun stopListenTogetherSoftSyncRateRecheck() = softSyncRateRecheckOwner.stop()

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
        val resolvedMessage = platform.message(CoreCommonR.string.listen_together_error_reconnecting)
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
    ) = httpControlFallbackOwner.send(event, reason, expectedRoomId)

    private suspend fun refreshRoomStateAfterReconnect(reason: String) {
        val snapshot = _sessionState.value
        val baseUrl = controlField(snapshot.baseUrl) ?: return
        val roomId = controlField(snapshot.roomId) ?: return
        runCatching { refreshRoomState(baseUrl, roomId) }
            .onFailure { onReconnectRefreshFailure(it, reason) }
    }

    private fun onReconnectRefreshFailure(error: Throwable, reason: String) {
        NPLogger.w(TAG, "refreshRoomStateAfterReconnect(): reason=$reason, error=${error.message}")
        val message = error.message ?: error.javaClass.simpleName
        _sessionState.value = _sessionState.value.copy(lastError = message)
        if (connectionRecoveryOwner.handleTerminalFailure(message, "refresh_after_reconnect")) return
        if (!connectionRecoveryOwner.recoverFromMembershipError(message, "refresh_after_reconnect")) {
            connectionRecoveryOwner.scheduleReconnect("refresh_state_failed:$reason")
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
        httpControlFallbackOwner.reset()
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
        backgroundKeepAlive.release("room_closed")
        playback.clearListenTogetherSafetyPause()
        playback.resetListenTogetherSyncPlaybackRate()
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
        lastControllerLocalControlAtElapsedMs = elapsedRealtimeMs()
        NPLogger.d(
            TAG,
            "noteControllerLocalControl(): type=${command.type}, positionMs=${command.positionMs}, currentIndex=${command.currentIndex}"
        )
    }

    private fun publishControllerHeartbeatIfNeeded(reason: String) {
        val snapshot = _sessionState.value
        if (!mayPublishControllerHeartbeat(snapshot)) return
        if (!playback.currentSongFlow.value.isShareableForListenTogether()) {
            NPLogger.d(TAG, "publishControllerHeartbeatIfNeeded(): skip non-shareable current track, reason=$reason")
            return
        }
        val heartbeat = buildHeartbeatEvent(
            state = currentLocalPlaybackStateName(),
            positionMs = playback.playbackPositionFlow.value.coerceAtLeast(0L),
            includeQueue = true
        )
        markOutboundEvent(heartbeat.eventId)
        noteOutboundSync()
        NPLogger.d(
            TAG,
            "publishControllerHeartbeatIfNeeded(): reason=$reason, eventId=${heartbeat.eventId}, track=${heartbeat.track?.stableKey}, positionMs=${heartbeat.positionMs}"
        )
        sendControlEventPureWebSocket(heartbeat, "publish_controller_heartbeat:$reason")
    }

    private fun mayPublishControllerHeartbeat(session: ListenTogetherSessionState): Boolean {
        if (session.connectionState != ListenTogetherConnectionState.CONNECTED || !isCurrentUserController(session)) return false
        val state = roomState.value ?: return false
        return state.roomStatus != ListenTogetherRoomStatuses.CLOSED
    }

    private fun isLocalPlaybackTransportActive(): Boolean {
        return runCatching { playback.isTransportActive() }
            .getOrDefault(playback.isPlayingFlow.value || playback.playWhenReadyFlow.value)
    }

    private fun currentLocalPlaybackStateName(): String {
        return if (isLocalPlaybackTransportActive() || playback.isPlayingFlow.value) {
            "playing"
        } else {
            "paused"
        }
    }

    init { start() }

    companion object {
        private const val TAG = "NERI-ListenTogether"
        private const val PLAYING_DRIFT_FORCE_SYNC_MS = 2_500L
        private const val HEARTBEAT_DRIFT_FORCE_SYNC_MS = 5 * SECOND_MS
        private const val PAUSED_DRIFT_FORCE_SYNC_MS = 800L
        private const val TRACK_SWITCH_FORCE_SYNC_MS = 500L
        private const val TRACK_SWITCH_GRACE_PERIOD_MS = 800L
        private const val CONTROLLER_GRACE_PERIOD_MS = 10 * MINUTE_MS
        private const val CONTROLLER_LOCAL_CONTROL_COOLDOWN_MS = 1_200L
        private const val SOFT_SYNC_MIN_DRIFT_MS = 600L
        private const val SOFT_SYNC_FAST_DRIFT_MS = 1_500L
        private const val UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS = 2 * SECOND_MS
    }
}
