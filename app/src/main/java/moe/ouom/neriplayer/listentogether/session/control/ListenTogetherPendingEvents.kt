package moe.ouom.neriplayer.listentogether.session.control

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause

internal data class PendingTrackFinishedLegacyFallback(
    val event: ListenTogetherEvent,
    val createdAtElapsedMs: Long,
    val attempted: Boolean = false
)

internal data class PendingMemberControlRequest(
    val event: ListenTogetherEvent,
    val createdAtElapsedMs: Long,
    val lastSentAtElapsedMs: Long,
    val attempts: Int
)

internal fun PendingMemberControlRequest.retriedAt(
    nowElapsedMs: Long
): PendingMemberControlRequest {
    return copy(
        lastSentAtElapsedMs = nowElapsedMs,
        attempts = attempts + 1
    )
}

internal fun PendingMemberControlRequest?.acknowledgedBy(
    cause: ListenTogetherCause?
): PendingMemberControlRequest? {
    val eventId = cause?.eventId?.takeIf { it.isNotBlank() } ?: return this
    return takeUnless { it?.event?.eventId == eventId }
}
