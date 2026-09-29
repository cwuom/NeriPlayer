package moe.ouom.neriplayer.listentogether.session.control

import moe.ouom.neriplayer.data.model.ltw.session.PendingMemberControlRequest
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause

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
