package moe.ouom.neriplayer.core.player.policy

internal enum class UsbExclusiveKeepAliveProgress {
    BASELINE,
    ADVANCED,
    COUNTER_RESET,
    STALLED
}

internal data class UsbExclusiveKeepAliveDecision(
    val progress: UsbExclusiveKeepAliveProgress,
    val stallTicks: Int,
    val shouldRecover: Boolean
)

internal fun evaluateUsbExclusiveKeepAliveProgress(
    previousHandle: Long,
    currentHandle: Long,
    previousCompletedFrames: Long,
    currentCompletedFrames: Long,
    previousStallTicks: Int,
    recoveryTicks: Int
): UsbExclusiveKeepAliveDecision {
    val baselineChanged = previousHandle <= 0L ||
        currentHandle <= 0L ||
        currentHandle != previousHandle ||
        previousCompletedFrames < 0L
    if (baselineChanged) {
        return UsbExclusiveKeepAliveDecision(
            progress = UsbExclusiveKeepAliveProgress.BASELINE,
            stallTicks = 0,
            shouldRecover = false
        )
    }

    if (currentCompletedFrames < previousCompletedFrames) {
        return UsbExclusiveKeepAliveDecision(
            progress = UsbExclusiveKeepAliveProgress.COUNTER_RESET,
            stallTicks = 0,
            shouldRecover = false
        )
    }

    if (currentCompletedFrames > previousCompletedFrames) {
        return UsbExclusiveKeepAliveDecision(
            progress = UsbExclusiveKeepAliveProgress.ADVANCED,
            stallTicks = 0,
            shouldRecover = false
        )
    }

    val requiredTicks = recoveryTicks.coerceAtLeast(1)
    val stallTicks = (previousStallTicks + 1).coerceAtMost(requiredTicks)
    return UsbExclusiveKeepAliveDecision(
        progress = UsbExclusiveKeepAliveProgress.STALLED,
        stallTicks = stallTicks,
        shouldRecover = stallTicks >= requiredTicks
    )
}
