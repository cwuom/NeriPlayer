package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval

private const val BILI_VIDEO_SKIP_REWIND_TOLERANCE_MS = 1_000L

internal class BiliVideoSkipTracker {
    private val skippedIntervals = mutableSetOf<BiliVideoSkipInterval>()
    private var lastPositionMs: Long? = null

    fun reset() {
        skippedIntervals.clear()
        lastPositionMs = null
    }

    fun nextSkipPosition(
        intervals: List<BiliVideoSkipInterval>,
        currentPositionMs: Long,
        durationMs: Long
    ): Long? {
        val positionMs = currentPositionMs.coerceAtLeast(0L)
        val lastPosition = lastPositionMs
        if (lastPosition != null && positionMs + BILI_VIDEO_SKIP_REWIND_TOLERANCE_MS < lastPosition) {
            skippedIntervals.clear()
        }
        lastPositionMs = positionMs

        var skipEndMs = positionMs
        val skippedInCurrentJump = mutableSetOf<BiliVideoSkipInterval>()
        var extendedRange: Boolean
        do {
            extendedRange = false
            intervals.forEach { interval ->
                val endMs = if (durationMs > 0L) {
                    interval.endMs.coerceAtMost(durationMs)
                } else {
                    interval.endMs
                }
                if (
                    interval in skippedIntervals ||
                    interval in skippedInCurrentJump ||
                    interval.startMs > skipEndMs ||
                    endMs <= positionMs ||
                    endMs <= interval.startMs
                ) {
                    return@forEach
                }
                skippedInCurrentJump += interval
                if (endMs > skipEndMs) {
                    skipEndMs = endMs
                    extendedRange = true
                }
            }
        } while (extendedRange)

        if (skipEndMs <= positionMs) return null
        skippedIntervals += skippedInCurrentJump
        return skipEndMs
    }
}
