package moe.ouom.neriplayer.listentogether.session.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.listentogether.compat.buildListenTogetherLegacyQueueMutationFallback
import moe.ouom.neriplayer.listentogether.compat.buildTrackFinishedLegacyFallbackEvent
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherPendingMemberControlSatisfied
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherQueueMutationCompatibilityError
import moe.ouom.neriplayer.listentogether.compat.isUnsupportedTrackFinishedEventError
import moe.ouom.neriplayer.listentogether.control.requestControlEventTypes
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import kotlin.time.Duration.Companion.milliseconds

internal interface ListenTogetherLocalControlPort {
    fun currentRoomId(): String?
    fun isController(): Boolean
    fun nextEventId(): String
    fun markOutbound(eventId: String?)
    fun noteOutboundSync()
    fun send(event: ListenTogetherEvent, reason: String): Boolean
}

internal class ListenTogetherLocalControlOwner(
    private val scope: CoroutineScope,
    private val port: ListenTogetherLocalControlPort,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long
) {
    private val outbox = ListenTogetherControlOutbox()
    private val coalescedLock = Any()
    private val pendingCoalescedEvents = mutableMapOf<String, PendingCoalescedControlEvent>()
    private val coalescedJobs = mutableMapOf<String, Job>()
    @Volatile
    private var awaitingTrackFinishStableKey: String? = null
    @Volatile
    private var pendingTrackFinishedFallback: PendingTrackFinishedLegacyFallback? = null
    @Volatile
    private var pendingMemberRequest: PendingMemberControlRequest? = null

    private data class PendingCoalescedControlEvent(
        val event: ListenTogetherEvent,
        val roomId: String?
    )

    fun enqueueOrDispatch(event: ListenTogetherEvent, roomId: String?) {
        if (event.type !in COALESCED_EVENT_TYPES) {
            dispatch(event, roomId)
            return
        }
        synchronized(coalescedLock) {
            pendingCoalescedEvents[event.type] = PendingCoalescedControlEvent(event, roomId)
            coalescedJobs.remove(event.type)?.cancel()
            coalescedJobs[event.type] = scope.launch {
                delay(COALESCING_WINDOW_MS.milliseconds)
                val pending = coroutineContext[Job]?.let { takeCoalesced(event.type, it) }
                pending?.let { dispatch(it.event, it.roomId) }
            }
        }
    }

    private fun takeCoalesced(eventType: String, completingJob: Job): PendingCoalescedControlEvent? =
        synchronized(coalescedLock) {
            if (!isCurrentListenTogetherCoalescedControlJob(coalescedJobs[eventType], completingJob)) {
                return@synchronized null
            }
            coalescedJobs.remove(eventType)
            pendingCoalescedEvents.remove(eventType)
        }

    private fun dispatch(event: ListenTogetherEvent, expectedRoomId: String?) {
        val roomId = port.currentRoomId()
        if (roomId.isNullOrBlank() || roomId != expectedRoomId) {
            NPLogger.d(TAG, "dispatchLocalControlEvent(): discard stale event type=${event.type}, expectedRoomId=$expectedRoomId, currentRoomId=$roomId")
            return
        }
        outbox.offer(
            event = event,
            roomId = roomId,
            legacyFallbackEvent = buildListenTogetherLegacyQueueMutationFallback(event, port.nextEventId())
        )
        updateTrackFinishRequest(event)
        pendingMemberRequest = buildPendingMemberRequest(event)
        publish(event, "local_playback_command", updateSyncClock = true)
    }

    private fun updateTrackFinishRequest(event: ListenTogetherEvent) {
        if (event.type != "TRACK_FINISHED") {
            clearTrackFinishBarrier()
            clearTrackFinishedFallback()
            return
        }
        awaitingTrackFinishStableKey = event.finishedTrackStableKey
        pendingTrackFinishedFallback = buildTrackFinishedLegacyFallbackEvent(
            event = event,
            isController = port.isController(),
            nowMs = wallTimeMs(),
            eventIdFactory = port::nextEventId
        )?.let { PendingTrackFinishedLegacyFallback(it, elapsedRealtimeMs()) }
    }

    fun replayPending() {
        val roomId = port.currentRoomId() ?: return
        val pending = outbox.pendingForRoom(roomId)
        if (pending.isEmpty()) return
        NPLogger.d(TAG, "replayPendingLocalControlEvents(): roomId=$roomId, count=${pending.size}")
        pending.forEach { item ->
            if (port.currentRoomId() != item.roomId) return@forEach
            publish(item.event, "replay_local_control", updateSyncClock = true)
        }
    }

    fun clearCoalesced(reason: String) {
        val count = synchronized(coalescedLock) {
            val pendingCount = pendingCoalescedEvents.size
            pendingCoalescedEvents.clear()
            coalescedJobs.values.forEach(Job::cancel)
            coalescedJobs.clear()
            pendingCount
        }
        if (count > 0) NPLogger.d(TAG, "clearCoalescedLocalControlEvents(): reason=$reason, count=$count")
    }

    fun clearOutbox() = outbox.clear()

    fun clearPendingMemberRequest() {
        pendingMemberRequest = null
    }

    fun resetTransientRequests() {
        awaitingTrackFinishStableKey = null
        pendingTrackFinishedFallback = null
        pendingMemberRequest = null
    }

    fun acknowledge(eventId: String?) = outbox.acknowledge(eventId)

    fun acknowledgeMember(cause: ListenTogetherCause?) {
        pendingMemberRequest = pendingMemberRequest.acknowledgedBy(cause)
    }

    fun clearTrackFinishedFallback() {
        pendingTrackFinishedFallback = null
    }

    fun clearTrackFinishBarrier() {
        awaitingTrackFinishStableKey = null
    }

    fun awaitingTrackFinishStableKey(): String? = awaitingTrackFinishStableKey

    fun onRoomStateCommitted(currentStableKey: String?) {
        if (awaitingTrackFinishStableKey != currentStableKey) awaitingTrackFinishStableKey = null
    }

    fun tryQueueMutationLegacyFallback(errorMessage: String?, eventId: String?): Boolean {
        if (!isListenTogetherQueueMutationCompatibilityError(errorMessage)) return false
        val candidate = queueFallbackCandidate(eventId) ?: return false
        val (pending, fallback) = candidate
        if (outbox.replace(pending.event.eventId, fallback) == null) return false
        pendingMemberRequest = buildPendingMemberRequest(fallback)
        NPLogger.w(TAG, "trySendQueueMutationLegacyFallback(): type=${fallback.type}, eventId=${fallback.eventId}, originalEventId=${pending.event.eventId}")
        publish(fallback, "queue_mutation_legacy_fallback", updateSyncClock = true)
        return true
    }

    private fun queueFallbackCandidate(eventId: String?): Pair<PendingListenTogetherControlEvent, ListenTogetherEvent>? {
        val pending = outbox.pendingByEventId(eventId)
            ?: outbox.singlePendingWithLegacyFallback()
            ?: return null
        val fallback = pending.legacyFallbackEvent ?: return null
        if (port.currentRoomId() != pending.roomId) return null
        return pending to fallback
    }

    fun tryTrackFinishedLegacyFallback(errorMessage: String): Boolean {
        if (!isUnsupportedTrackFinishedEventError(errorMessage)) return false
        val pending = pendingTrackFinishedFallback ?: return false
        if (pending.attempted) return false
        val elapsedMs = elapsedRealtimeMs() - pending.createdAtElapsedMs
        if (elapsedMs > TRACK_FINISHED_FALLBACK_TTL_MS) {
            pendingTrackFinishedFallback = null
            return false
        }
        pendingTrackFinishedFallback = pending.copy(attempted = true)
        awaitingTrackFinishStableKey = null
        NPLogger.w(TAG, "trySendTrackFinishedLegacyFallback(): fallbackType=${pending.event.type}, eventId=${pending.event.eventId}, elapsedMs=$elapsedMs")
        return publish(pending.event, "track_finished_legacy_fallback", updateSyncClock = true)
    }

    fun retryPendingMemberRequest(roomState: ListenTogetherRoomState?) {
        val pending = pendingMemberRequest ?: return
        val now = elapsedRealtimeMs()
        if (isListenTogetherPendingMemberControlSatisfied(
                event = pending.event,
                state = roomState,
                seekSatisfiedDriftMs = PENDING_MEMBER_SEEK_SATISFIED_DRIFT_MS
            )
        ) {
            pendingMemberRequest = null
            return
        }
        if (now - pending.createdAtElapsedMs > PENDING_MEMBER_REQUEST_TTL_MS ||
            pending.attempts >= PENDING_MEMBER_REQUEST_MAX_ATTEMPTS
        ) {
            pendingMemberRequest = null
            return
        }
        if (now - pending.lastSentAtElapsedMs < PENDING_MEMBER_REQUEST_RETRY_INTERVAL_MS) return
        pendingMemberRequest = pending.retriedAt(now)
        NPLogger.w(TAG, "retryPendingMemberControlRequestIfNeeded(): retry type=${pending.event.type}, attempt=${pending.attempts + 1}")
        publish(pending.event, "pending_member_control_retry", updateSyncClock = false)
    }

    private fun buildPendingMemberRequest(event: ListenTogetherEvent): PendingMemberControlRequest? {
        if (port.isController() || event.type !in requestControlEventTypes) return null
        val now = elapsedRealtimeMs()
        return PendingMemberControlRequest(event, now, now, 1)
    }

    private fun publish(event: ListenTogetherEvent, reason: String, updateSyncClock: Boolean): Boolean {
        port.markOutbound(event.eventId)
        if (updateSyncClock) port.noteOutboundSync()
        return port.send(event, reason)
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
        const val COALESCING_WINDOW_MS = 100L
        val COALESCED_EVENT_TYPES = setOf("SET_QUEUE", "REQUEST_SET_QUEUE", "PLAYBACK_MODE", "REQUEST_PLAYBACK_MODE")
        const val PENDING_MEMBER_REQUEST_RETRY_INTERVAL_MS = 3_000L
        const val PENDING_MEMBER_REQUEST_TTL_MS = 18_000L
        const val PENDING_MEMBER_REQUEST_MAX_ATTEMPTS = 4
        const val PENDING_MEMBER_SEEK_SATISFIED_DRIFT_MS = 1_500L
        const val TRACK_FINISHED_FALLBACK_TTL_MS = 15_000L
    }
}
