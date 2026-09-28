package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.isListenTogetherQueueUpdateCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope

internal interface ListenTogetherRoomSocketEventPort {
    fun session(): ListenTogetherSessionState
    fun room(): ListenTogetherRoomState?
    fun accept(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource,
        cause: ListenTogetherCause?
    ): AcceptedRoomState?
    fun updateNotice(notice: String?, clearError: Boolean = false)
    fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?)
    fun publishControllerHeartbeat(reason: String)
    fun pauseClosedRoomPlayback()
    fun closeRoomLocally(reason: String?)
}

internal class ListenTogetherRoomSocketEventOwner(
    private val port: ListenTogetherRoomSocketEventPort,
    private val localControl: ListenTogetherLocalControlOwner,
    private val controllerLink: ListenTogetherControllerLinkOwner,
    private val recentEvents: ListenTogetherRecentEventTracker
) {
    @Volatile
    private var observedControllerOffline = false

    fun reset() {
        observedControllerOffline = false
    }

    fun onRoomState(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        if (shouldIgnore(message)) return
        if (shouldDefer(state, message.causedBy)) {
            recentEvents.markInbound(message.causedBy?.eventId)
            return
        }
        val previous = port.room()
        val accepted = port.accept(
            state, message.expectedPositionMs, RoomStateSource.WEB_SOCKET_STATE, message.causedBy
        ) ?: return
        onAcceptedRoomState(message, previous, accepted)
    }

    private fun onAcceptedRoomState(
        message: ListenTogetherSocketEnvelope,
        previous: ListenTogetherRoomState?,
        accepted: AcceptedRoomState
    ) {
        acknowledgeCause(message)
        val confirmUnavailable = confirmUnavailableLink(message, accepted.state)
        updateNoticeFromState(message, accepted.state)
        controllerLink.maybePublishAfterAudioSharingEnabled(
            previous, accepted.state, "room_state:${causeType(message)}"
        )
        publishChangedTrack(previous, accepted.state, message)
        clearTrackFinishBarrier(message)
        recordInboundCause(message)
        applyIncomingState(message, accepted, confirmUnavailable)
        maybePublishControllerRecoveryHeartbeat(message)
    }

    private fun acknowledgeCause(message: ListenTogetherSocketEnvelope) =
        localControl.acknowledge(message.causedBy?.eventId)

    private fun causeType(message: ListenTogetherSocketEnvelope): String =
        message.causedBy?.type ?: message.type

    private fun clearTrackFinishBarrier(message: ListenTogetherSocketEnvelope) {
        if (message.causedBy?.type == "TRACK_FINISHED") localControl.clearTrackFinishBarrier()
    }

    private fun recordInboundCause(message: ListenTogetherSocketEnvelope) =
        recentEvents.markInbound(message.causedBy?.eventId)

    private fun updateNoticeFromState(message: ListenTogetherSocketEnvelope, state: ListenTogetherRoomState) {
        val notice = message.message?.takeIf(String::isNotBlank) ?: return
        port.updateNotice(resolveListenTogetherRoomNotice(state, notice))
    }

    private fun applyIncomingState(
        message: ListenTogetherSocketEnvelope,
        accepted: AcceptedRoomState,
        confirmUnavailable: Boolean
    ) {
        if (isLocalControllerEcho(message)) return
        port.applyToPlayer(accepted.state, message.causedBy?.type, accepted.expectedPositionMs)
        controllerLink.maybeRequest(
            accepted.state, message.causedBy?.type,
            force = confirmUnavailable, bypassThrottle = confirmUnavailable
        )
    }

    private fun shouldIgnore(message: ListenTogetherSocketEnvelope): Boolean {
        if (shouldAcceptListenTogetherAuthoritativeQueueUpdate(
                cause = message.causedBy,
                candidateState = message.state,
                currentState = port.room()
            )
        ) return false
        return shouldIgnoreListenTogetherIncomingState(
            cause = message.causedBy,
            currentUserId = port.session().userUuid,
            hasRecentOutboundEvent = recentEvents::hasOutbound,
            hasRecentInboundEvent = recentEvents::hasInbound
        )
    }

    private fun shouldDefer(state: ListenTogetherRoomState, cause: ListenTogetherCause?): Boolean =
        shouldDeferListenTogetherIncomingStateForLocalTrackFinish(
            state = state,
            cause = cause,
            awaitingTrackFinishStableKey = localControl.awaitingTrackFinishStableKey()
        )

    private fun confirmUnavailableLink(message: ListenTogetherSocketEnvelope, accepted: ListenTogetherRoomState): Boolean {
        val cause = message.causedBy
        if (cause?.type != "LINK_UNAVAILABLE") return false
        val pending = controllerLink.markUnavailable(
            state = accepted,
            requestedStableKey = message.requestTrackStableKey,
            signalId = cause.eventId ?: "room-state:${accepted.version}"
        )
        NPLogger.d(TAG, "link unavailable confirmation pending=$pending, stableKey=${accepted.currentStableKey()}")
        return pending
    }

    private fun publishChangedTrack(
        previous: ListenTogetherRoomState?,
        accepted: ListenTogetherRoomState,
        message: ListenTogetherSocketEnvelope
    ) {
        if (message.causedBy?.type == "LINK_UNAVAILABLE") return
        if (previous?.currentStableKey() == accepted.currentStableKey()) return
        controllerLink.maybePublishCurrentLink("track_changed:${causeType(message)}")
    }

    private fun isLocalControllerEcho(message: ListenTogetherSocketEnvelope): Boolean {
        val session = port.session()
        val cause = message.causedBy ?: return false
        return isController(session) && cause.userUuid == session.userUuid &&
            cause.type != "TRACK_FINISHED" && !isListenTogetherQueueUpdateCause(cause.type)
    }

    fun onRoomSuspended(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        val accepted = port.accept(
            state, message.expectedPositionMs, RoomStateSource.WEB_SOCKET_ROOM_STATUS, message.causedBy
        ) ?: return
        if (!isController(port.session())) observedControllerOffline = true
        port.updateNotice(resolveListenTogetherRoomNotice(accepted.state, message.message))
    }

    fun onRoomResumed(message: ListenTogetherSocketEnvelope) {
        val state = message.state ?: return
        val accepted = port.accept(
            state, message.expectedPositionMs, RoomStateSource.WEB_SOCKET_ROOM_STATUS, message.causedBy
        ) ?: return
        val controller = isController(port.session())
        val showReconnected = shouldShowListenTogetherControllerReconnectedNotice(controller, observedControllerOffline)
        if (shouldApplyResumedState(controller, message.causedBy?.userUuid)) {
            port.applyToPlayer(accepted.state, message.message, accepted.expectedPositionMs)
        }
        observedControllerOffline = false
        port.updateNotice(
            resolveListenTogetherRoomNotice(accepted.state, message.message, showControllerReconnected = showReconnected),
            clearError = true
        )
    }

    private fun shouldApplyResumedState(controller: Boolean, causedByUserUuid: String?): Boolean =
        !controller || causedByUserUuid != port.session().userUuid

    fun onRoomClosed(message: ListenTogetherSocketEnvelope) {
        val state = message.state
        val accepted = state?.let {
            port.accept(it, message.expectedPositionMs, RoomStateSource.WEB_SOCKET_ROOM_CLOSED, message.causedBy)
        }
        if (accepted != null && shouldApplyListenTogetherClosedRoomPause(accepted.state)) {
            port.pauseClosedRoomPlayback()
        }
        port.closeRoomLocally(closureReason(state, message.message))
    }

    private fun closureReason(state: ListenTogetherRoomState?, message: String?): String? {
        val closedReason = state?.closedReason?.trim()
        if (!closedReason.isNullOrEmpty()) return closedReason
        return message ?: resolveListenTogetherRoomNotice(state)
    }

    private fun maybePublishControllerRecoveryHeartbeat(message: ListenTogetherSocketEnvelope) {
        val cause = recoveryCause(message) ?: return
        if (cause.type == "REQUEST_LINK") {
            publishRecoveryLink(message)
            return
        }
        if (cause.type in moe.ouom.neriplayer.listentogether.control.controllerHeartbeatRecoveryTypes) {
            port.publishControllerHeartbeat("recovery:${cause.type}")
        }
    }

    private fun recoveryCause(message: ListenTogetherSocketEnvelope): ListenTogetherCause? {
        val session = port.session()
        if (!isController(session)) return null
        val state = message.state ?: return null
        if (!state.settings.normalized().shareAudioLinks) return null
        val cause = message.causedBy ?: return null
        if (cause.userUuid == session.userUuid) return null
        return cause
    }

    private fun publishRecoveryLink(message: ListenTogetherSocketEnvelope) {
        val stableKey = requestedRecoveryStableKey(message) ?: return
        controllerLink.resolveAndPublish(stableKey, "recovery:REQUEST_LINK")
    }

    private fun requestedRecoveryStableKey(message: ListenTogetherSocketEnvelope): String? =
        listOfNotNull(message.requestTrackStableKey, message.state?.currentStableKey(), message.track?.stableKey)
            .firstOrNull()

    private fun isController(session: ListenTogetherSessionState): Boolean =
        resolveListenTogetherSessionRole(session.userUuid, session.role, port.room()) == "controller"

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
