package moe.ouom.neriplayer.core.player.policy.usb

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
        val signalKnown = previousSignalBytes >= 0L && currentSignalBytes >= previousSignalBytes
        val zeroFillKnown =
            previousZeroFillBytes >= 0L && currentZeroFillBytes >= previousZeroFillBytes
        val signalAdvanced = signalKnown && currentSignalBytes > previousSignalBytes
        val zeroFillAdvanced = zeroFillKnown && currentZeroFillBytes > previousZeroFillBytes
        if (
            hasSeverePcmStarvation(
                zeroFillAdvanced = zeroFillAdvanced,
                previousZeroFillBytes = previousZeroFillBytes,
                currentZeroFillBytes = currentZeroFillBytes,
                outputSampleRate = outputSampleRate,
                outputFrameBytes = outputFrameBytes,
                currentPcmLevelBytes = currentPcmLevelBytes
            )
        ) {
            val requiredTicks = recoveryTicks.coerceAtLeast(1)
            val stallTicks = (previousStallTicks + 1).coerceAtMost(requiredTicks)
            return UsbExclusiveKeepAliveDecision(
                progress = UsbExclusiveKeepAliveProgress.PCM_STARVATION,
                stallTicks = stallTicks,
                shouldRecover = stallTicks >= requiredTicks
            )
        }
        val peakKnown = !previousOutputPeak.isNaN() && !currentOutputPeak.isNaN()
        val outputAudible = !peakKnown || currentOutputPeak > USB_EXCLUSIVE_SILENT_OUTPUT_PEAK_MAX
        if (!signalAdvanced && zeroFillAdvanced && !outputAudible) {
            val requiredTicks = recoveryTicks.coerceAtLeast(1)
            val stallTicks = (previousStallTicks + 1).coerceAtMost(requiredTicks)
            return UsbExclusiveKeepAliveDecision(
                progress = UsbExclusiveKeepAliveProgress.FAKE_PROGRESS,
                stallTicks = stallTicks,
                shouldRecover = stallTicks >= requiredTicks
            )
        }
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

private fun hasSeverePcmStarvation(
    zeroFillAdvanced: Boolean,
    previousZeroFillBytes: Long,
    currentZeroFillBytes: Long,
    outputSampleRate: Int,
    outputFrameBytes: Int,
    currentPcmLevelBytes: Long
): Boolean {
    if (!zeroFillAdvanced || outputSampleRate <= 0 || outputFrameBytes <= 0) return false
    if (currentPcmLevelBytes < 0L) return false
    val bytesPerSecond = outputSampleRate.toLong() * outputFrameBytes
    if (bytesPerSecond <= 0L) return false
    val zeroFillDeltaBytes = currentZeroFillBytes - previousZeroFillBytes
    val zeroFillMs = bytesToDurationMs(zeroFillDeltaBytes, bytesPerSecond)
    if (zeroFillMs < USB_EXCLUSIVE_SEVERE_ZERO_FILL_MS) return false
    val queueMs = bytesToDurationMs(currentPcmLevelBytes, bytesPerSecond)
    return queueMs <= USB_EXCLUSIVE_RECOVERY_QUEUE_MAX_MS
}

private fun bytesToDurationMs(bytes: Long, bytesPerSecond: Long): Long {
    if (bytes <= 0L || bytesPerSecond <= 0L) return 0L
    return bytes / bytesPerSecond * 1_000L +
        (bytes % bytesPerSecond) * 1_000L / bytesPerSecond
}

private const val USB_EXCLUSIVE_SILENT_OUTPUT_PEAK_MAX = 0.0001f
private const val USB_EXCLUSIVE_SEVERE_ZERO_FILL_MS = 750L
private const val USB_EXCLUSIVE_RECOVERY_QUEUE_MAX_MS = 100L
