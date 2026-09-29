package moe.ouom.neriplayer.listentogether.session.control

import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherConnectionRecoveryOwner
import moe.ouom.neriplayer.listentogether.session.connection.ListenTogetherSocketHealthOwner
import moe.ouom.neriplayer.listentogether.session.link.ListenTogetherControllerLinkOwner
import moe.ouom.neriplayer.listentogether.session.state.AcceptedRoomState
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherAppliedEvent
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.message.socket.ListenTogetherSocketEnvelope

internal interface ListenTogetherSocketControlResultPort {
    fun currentUserUuid(): String?
    fun currentRoom(): ListenTogetherRoomState?
    fun accept(state: ListenTogetherRoomState, expectedPositionMs: Long?, cause: ListenTogetherCause): AcceptedRoomState?
    fun isController(): Boolean
    fun applyToPlayer(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?)
    fun setLastError(error: String?)
    fun trySendTrackFinishedLegacyFallback(error: String): Boolean
}

internal class ListenTogetherSocketControlResultOwner(
    private val port: ListenTogetherSocketControlResultPort,
    private val localControl: ListenTogetherLocalControlOwner,
    private val controllerLink: ListenTogetherControllerLinkOwner,
    private val socketHealth: ListenTogetherSocketHealthOwner,
    private val recovery: ListenTogetherConnectionRecoveryOwner
) {
    fun onControlResult(message: ListenTogetherSocketEnvelope) {
        acknowledgeSuccessfulResult(message)
        applyCommittedResult(message)
        if (isRejectedListenTogetherControlResult(message)) {
            onRejected(resolvedControlError(message), acknowledgedEventId(message))
        }
    }

    private fun acknowledgeSuccessfulResult(message: ListenTogetherSocketEnvelope) {
        if (!isSuccessfulListenTogetherControlResult(message)) return
        localControl.acknowledge(acknowledgedEventId(message))
        if (message.result?.applied?.causedBy?.type == "TRACK_FINISHED") {
            localControl.clearTrackFinishedFallback()
        }
    }

    private fun applyCommittedResult(message: ListenTogetherSocketEnvelope) {
        if (!message.result?.error.isNullOrBlank()) return
        message.result?.applied?.let(::applyCommittedState)
    }

    private fun acknowledgedEventId(message: ListenTogetherSocketEnvelope): String? =
        message.result?.applied?.causedBy?.eventId ?: message.causedBy?.eventId

    private fun resolvedControlError(message: ListenTogetherSocketEnvelope): String =
        message.result?.error ?: message.message ?: "control event rejected"

    private fun applyCommittedState(applied: ListenTogetherAppliedEvent) {
        val cause = applied.causedBy ?: return
        val state = applied.state ?: return
        if (!shouldApplyListenTogetherCommittedControlState(cause, port.currentUserUuid())) return
        val previous = port.currentRoom()
        val accepted = port.accept(state, applied.expectedPositionMs, cause) ?: return
        controllerLink.maybePublishAfterAudioSharingEnabled(
            previous, accepted.state, "control_result:${cause.type}"
        )
        if (cause.type == "TRACK_FINISHED") localControl.clearTrackFinishBarrier()
        if (!port.isController()) port.applyToPlayer(accepted.state, cause.type, accepted.expectedPositionMs)
    }

    private fun onRejected(error: String, eventId: String?) {
        if (localControl.tryQueueMutationLegacyFallback(error, eventId)) return
        if (socketHealth.handleUnsupportedPing(error)) return
        if (port.trySendTrackFinishedLegacyFallback(error)) return
        NPLogger.w(TAG, "websocket.controlResult(): $error")
        port.setLastError(error)
        if (recovery.handleTerminalFailure(error, "control_result")) return
        recovery.recoverFromMembershipError(error, "control_result")
    }

    fun onSocketError(message: ListenTogetherSocketEnvelope) {
        val error = message.message ?: "socket error"
        if (socketHealth.handleUnsupportedPing(error)) return
        NPLogger.w(TAG, "websocket.error(): $error")
        port.setLastError(error)
        if (recovery.handleTerminalFailure(error, "socket_error")) return
        recovery.recoverFromMembershipError(error, "socket_error")
    }

    fun onPong(message: ListenTogetherSocketEnvelope) {
        if (message.type == "np_pong") socketHealth.markPongSupported()
        port.setLastError(null)
        socketHealth.onPong(message.nowMs, message.t)
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}

internal fun shouldApplyListenTogetherCommittedControlState(cause: ListenTogetherCause, currentUserUuid: String?): Boolean =
    cause.type == "UPDATE_SETTINGS" ||
        (cause.type == "TRACK_FINISHED" && cause.userUuid == currentUserUuid) ||
        (cause.type?.startsWith("REQUEST_") == true && cause.userUuid == currentUserUuid)

internal fun isSuccessfulListenTogetherControlResult(message: ListenTogetherSocketEnvelope): Boolean =
    message.result?.error.isNullOrBlank() && message.ok != false

internal fun isRejectedListenTogetherControlResult(message: ListenTogetherSocketEnvelope): Boolean =
    !message.result?.error.isNullOrBlank() || message.ok == false
