package moe.ouom.neriplayer.listentogether.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.control.buildListenTogetherForwardedControlSyntheticState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope

internal interface ListenTogetherRoomStateObserver {
    fun onRoomActivated()

    fun onCommitted(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource
    )

    fun onPositionSupplement(expectedPositionMs: Long)

    fun onSocketMessageAccepted()
}

internal data class CommittedForwardedRoomState(
    val state: ListenTogetherRoomState,
    val causeType: String?,
    val expectedPositionMs: Long?
)

internal class ListenTogetherRoomStateOwner(
    private val observer: ListenTogetherRoomStateObserver,
    private val elapsedRealtimeMs: () -> Long
) {
    private val lock = Any()
    private val mutableRoomState = MutableStateFlow<ListenTogetherRoomState?>(null)
    val roomState: StateFlow<ListenTogetherRoomState?> = mutableRoomState.asStateFlow()

    private var activeRoomId: String? = null
    private var lastAppliedVersion = -1L
    private var repairVersion = -1L

    fun hasActiveRoom(): Boolean = synchronized(lock) { !activeRoomId.isNullOrBlank() }

    fun lastAppliedVersion(): Long = synchronized(lock) { lastAppliedVersion }

    fun pendingRepairVersion(): Long = synchronized(lock) { repairVersion }

    fun activateIfPresent(roomId: String?): Boolean? {
        if (roomId.isNullOrBlank()) return null
        return activate(roomId)
    }

    fun activate(roomId: String): Boolean = synchronized(lock) {
        if (activeRoomId == roomId) return@synchronized false
        lastAppliedVersion = -1L
        repairVersion = -1L
        mutableRoomState.value = null
        observer.onRoomActivated()
        activeRoomId = roomId
        true
    }

    fun resetVersions() = synchronized(lock) {
        lastAppliedVersion = -1L
        repairVersion = -1L
    }

    fun close() = synchronized(lock) {
        activeRoomId = null
        lastAppliedVersion = -1L
        repairVersion = -1L
        mutableRoomState.value = null
    }

    fun accept(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource,
        cause: ListenTogetherCause? = null,
        currentUserId: String? = null,
        lastControllerLocalControlAtElapsedMs: Long = 0L,
        controllerLocalControlCooldownMs: Long = 0L
    ): AcceptedRoomState? = synchronized(lock) {
        if (activeRoomId != state.roomId) return@synchronized null
        val current = mutableRoomState.value
        val latest = latestListenTogetherAcceptedRoomVersion(lastAppliedVersion, current)
        if (state.version < latest) return@synchronized null
        if (isSuppressedLocalEcho(
                state, cause, current, latest, currentUserId,
                lastControllerLocalControlAtElapsedMs, controllerLocalControlCooldownMs
            )
        ) return@synchronized null
        if (current != null && state.version == latest) {
            lastAppliedVersion = maxOf(lastAppliedVersion, current.version)
            clearRepairIfSatisfied(state.version, source)
            if (expectedPositionMs != null) observer.onPositionSupplement(expectedPositionMs)
            return@synchronized AcceptedRoomState(current, expectedPositionMs)
        }
        commit(state, expectedPositionMs, source)
        clearRepairIfSatisfied(state.version, source)
        AcceptedRoomState(state, expectedPositionMs)
    }

    private fun isSuppressedLocalEcho(
        state: ListenTogetherRoomState,
        cause: ListenTogetherCause?,
        current: ListenTogetherRoomState?,
        latest: Long,
        currentUserId: String?,
        lastControlAtMs: Long,
        cooldownMs: Long
    ): Boolean {
        val authoritativeQueueUpdate = shouldAcceptListenTogetherAuthoritativeQueueUpdate(
            cause = cause, candidateState = state, currentState = current
        )
        return !authoritativeQueueUpdate && shouldDropListenTogetherControllerLocalEcho(
            state = state,
            cause = cause,
            latestVersion = latest,
            currentUserId = currentUserId,
            lastControllerLocalControlAtElapsedMs = lastControlAtMs,
            nowElapsedMs = elapsedRealtimeMs(),
            controllerLocalControlCooldownMs = cooldownMs
        )
    }

    private fun commit(
        state: ListenTogetherRoomState,
        expectedPositionMs: Long?,
        source: RoomStateSource
    ) {
        lastAppliedVersion = maxOf(lastAppliedVersion, state.version)
        mutableRoomState.value = state
        observer.onCommitted(state, expectedPositionMs, source)
    }

    fun commitSynthetic(
        expectedPositionMs: Long?,
        transform: (ListenTogetherRoomState) -> ListenTogetherRoomState
    ): ListenTogetherRoomState? = synchronized(lock) {
        val current = mutableRoomState.value ?: return@synchronized null
        val next = transform(current)
        commit(next, expectedPositionMs, RoomStateSource.LOCAL_SYNTHETIC)
        next
    }

    fun commitForwarded(
        message: ListenTogetherSocketEnvelope,
        committedEvent: ListenTogetherEvent
    ): CommittedForwardedRoomState? {
        val positionMs = committedEvent.positionMs ?: message.expectedPositionMs
        val next = commitSynthetic(positionMs) { current ->
            buildListenTogetherForwardedControlSyntheticState(current, message, committedEvent)
        } ?: return null
        return CommittedForwardedRoomState(
            state = next,
            causeType = message.causedBy?.type ?: committedEvent.type,
            expectedPositionMs = positionMs
        )
    }

    fun recordSocketMessage(message: ListenTogetherSocketEnvelope): Boolean = synchronized(lock) {
        val roomId = activeRoomId ?: return@synchronized false
        if (isForeignRoomMessage(message, roomId)) return@synchronized false
        observer.onSocketMessageAccepted()
        recordVersionGap(message.state?.version ?: message.version, roomId)
        true
    }

    private fun isForeignRoomMessage(message: ListenTogetherSocketEnvelope, roomId: String): Boolean {
        val incomingRoomId = message.state?.roomId ?: message.roomId
        return !incomingRoomId.isNullOrBlank() && incomingRoomId != roomId
    }

    private fun recordVersionGap(incomingVersion: Long?, roomId: String) {
        if (incomingVersion == null) return
        val latest = latestListenTogetherAcceptedRoomVersion(lastAppliedVersion, mutableRoomState.value)
        if (latest < 0L || incomingVersion <= latest + 1L) return
        repairVersion = maxOf(repairVersion, incomingVersion)
        NPLogger.w(
            TAG,
            "recordSocketMessage(): version gap detected, roomId=$roomId, incoming=$incomingVersion, latest=$latest, repairTarget=$repairVersion"
        )
    }

    private fun clearRepairIfSatisfied(version: Long, source: RoomStateSource) {
        if (source !in HTTP_STATE_SOURCES) return
        if (repairVersion >= 0L && version >= repairVersion) repairVersion = -1L
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
        val HTTP_STATE_SOURCES = setOf(
            RoomStateSource.HTTP_REFRESH,
            RoomStateSource.HTTP_CONTROL_FALLBACK,
            RoomStateSource.HTTP_SESSION_UPDATE
        )
    }
}
