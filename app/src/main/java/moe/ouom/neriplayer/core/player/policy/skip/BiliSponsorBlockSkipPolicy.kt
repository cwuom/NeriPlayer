package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockSegment

internal const val BILI_SPONSOR_BLOCK_REWIND_TOLERANCE_MS = 1_000L

internal class BiliSponsorBlockSkipTracker {
    private val skippedSegmentIds = mutableSetOf<String>()
    private var lastPositionMs: Long? = null

    fun reset() {
        skippedSegmentIds.clear()
        lastPositionMs = null
    }

    fun nextSkipPosition(
        segments: List<BiliSponsorBlockSegment>,
        currentPositionMs: Long,
        durationMs: Long
    ): Long? {
        val positionMs = currentPositionMs.coerceAtLeast(0L)
        val lastPosition = lastPositionMs
        if (lastPosition != null && positionMs + BILI_SPONSOR_BLOCK_REWIND_TOLERANCE_MS < lastPosition) {
            skippedSegmentIds.clear()
        }
        lastPositionMs = positionMs

        var skipEndMs = positionMs
        val skippedInCurrentJump = mutableSetOf<String>()
        var extendedRange: Boolean
        do {
            extendedRange = false
            segments.forEach { candidate ->
                if (
                    candidate.uuid in skippedSegmentIds ||
                    candidate.uuid in skippedInCurrentJump ||
                    candidate.startMs > skipEndMs ||
                    candidate.endMs <= positionMs ||
                    (durationMs > 0L && candidate.endMs > durationMs)
                ) {
                    return@forEach
                }
                skippedInCurrentJump += candidate.uuid
                if (candidate.endMs > skipEndMs) {
                    skipEndMs = candidate.endMs
                    extendedRange = true
                }
            }
        } while (extendedRange)

        if (skipEndMs <= positionMs) return null
        skippedSegmentIds += skippedInCurrentJump
        return skipEndMs
    }
}
