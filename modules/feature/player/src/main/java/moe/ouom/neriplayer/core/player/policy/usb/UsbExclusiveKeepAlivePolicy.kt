package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.policy.usb.keepalive.UsbKeepAliveCounters
import moe.ouom.neriplayer.core.player.policy.usb.keepalive.UsbKeepAliveQueue
import moe.ouom.neriplayer.core.player.policy.usb.keepalive.hasSameUsbKeepAliveBaseline
import moe.ouom.neriplayer.core.player.policy.usb.keepalive.classifyAdvancedUsbKeepAliveProgress

internal enum class UsbExclusiveKeepAliveProgress {
    BASELINE,
    ADVANCED,
    COUNTER_RESET,
    PCM_STARVATION,
    FAKE_PROGRESS,
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
    previousSignalBytes: Long = -1L,
    currentSignalBytes: Long = -1L,
    previousZeroFillBytes: Long = -1L,
    currentZeroFillBytes: Long = -1L,
    previousOutputPeak: Float = Float.NaN,
    currentOutputPeak: Float = Float.NaN,
    outputSampleRate: Int = 0,
    outputFrameBytes: Int = 0,
    currentPcmLevelBytes: Long = -1L,
    previousStallTicks: Int,
    recoveryTicks: Int
): UsbExclusiveKeepAliveDecision {
    val previous = UsbKeepAliveCounters(
        previousHandle, previousCompletedFrames, previousSignalBytes, previousZeroFillBytes, previousOutputPeak
    )
    val current = UsbKeepAliveCounters(
        currentHandle, currentCompletedFrames, currentSignalBytes, currentZeroFillBytes, currentOutputPeak
    )
    if (!hasSameUsbKeepAliveBaseline(previous, current)) {
        return healthyUsbKeepAliveDecision(UsbExclusiveKeepAliveProgress.BASELINE)
    }
    if (currentCompletedFrames < previousCompletedFrames) {
        return healthyUsbKeepAliveDecision(UsbExclusiveKeepAliveProgress.COUNTER_RESET)
    }
    if (currentCompletedFrames > previousCompletedFrames) {
        val queue = UsbKeepAliveQueue(outputSampleRate, outputFrameBytes, currentPcmLevelBytes)
        val progress = classifyAdvancedUsbKeepAliveProgress(previous, current, queue)
        if (progress == UsbExclusiveKeepAliveProgress.ADVANCED) return healthyUsbKeepAliveDecision(progress)
        return stalledUsbKeepAliveDecision(progress, previousStallTicks, recoveryTicks)
    }
    return stalledUsbKeepAliveDecision(UsbExclusiveKeepAliveProgress.STALLED, previousStallTicks, recoveryTicks)
}

private fun healthyUsbKeepAliveDecision(progress: UsbExclusiveKeepAliveProgress): UsbExclusiveKeepAliveDecision =
    UsbExclusiveKeepAliveDecision(progress = progress, stallTicks = 0, shouldRecover = false)

private fun stalledUsbKeepAliveDecision(
    progress: UsbExclusiveKeepAliveProgress,
    previousStallTicks: Int,
    recoveryTicks: Int
): UsbExclusiveKeepAliveDecision {
    val requiredTicks = recoveryTicks.coerceAtLeast(1)
    val stallTicks = (previousStallTicks + 1).coerceAtMost(requiredTicks)
    return UsbExclusiveKeepAliveDecision(progress, stallTicks, stallTicks >= requiredTicks)
}
