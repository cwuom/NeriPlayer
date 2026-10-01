package moe.ouom.neriplayer.core.player.policy.usb

import kotlin.math.max
import kotlin.math.min

internal fun resolveUsbExclusiveCompletedPositionUs(
    startMediaTimeUs: Long,
    completedFrames: Long,
    completedFramesAtTimelineStart: Long,
    outputSampleRate: Int,
    clockPositionUs: Long,
    lastPositionUs: Long,
    extrapolationWindowUs: Long,
    canExtrapolate: Boolean
): Long {
    if (outputSampleRate <= 0) return clockPositionUs
    if (completedFrames < completedFramesAtTimelineStart) return lastPositionUs
    val completedPositionUs = startMediaTimeUs +
        (completedFrames - completedFramesAtTimelineStart) * 1_000_000L /
        outputSampleRate
    val extrapolatedPositionUs = if (canExtrapolate) {
        min(clockPositionUs, completedPositionUs + extrapolationWindowUs.coerceAtLeast(0L))
    } else {
        completedPositionUs
    }
    return max(completedPositionUs, extrapolatedPositionUs)
}
