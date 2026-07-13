package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent

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
