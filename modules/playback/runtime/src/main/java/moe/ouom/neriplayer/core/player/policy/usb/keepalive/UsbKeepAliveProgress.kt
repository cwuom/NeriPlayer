package moe.ouom.neriplayer.core.player.policy.usb.keepalive

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveKeepAliveProgress

internal data class UsbKeepAliveCounters(
    val handle: Long,
    val completedFrames: Long,
    val signalBytes: Long,
    val zeroFillBytes: Long,
    val outputPeak: Float
)

internal data class UsbKeepAliveQueue(
    val sampleRate: Int,
    val frameBytes: Int,
    val pcmLevelBytes: Long
)

internal fun hasSameUsbKeepAliveBaseline(previous: UsbKeepAliveCounters, current: UsbKeepAliveCounters): Boolean =
    previous.handle > 0L && current.handle > 0L && current.handle == previous.handle && previous.completedFrames >= 0L

internal fun classifyAdvancedUsbKeepAliveProgress(
    previous: UsbKeepAliveCounters,
    current: UsbKeepAliveCounters,
    queue: UsbKeepAliveQueue
): UsbExclusiveKeepAliveProgress {
    val zeroFillAdvanced = counterAdvanced(previous.zeroFillBytes, current.zeroFillBytes)
    if (hasSeverePcmStarvation(zeroFillAdvanced, previous.zeroFillBytes, current.zeroFillBytes, queue)) {
        return UsbExclusiveKeepAliveProgress.PCM_STARVATION
    }
    val signalAdvanced = counterAdvanced(previous.signalBytes, current.signalBytes)
    if (!signalAdvanced && zeroFillAdvanced && !outputIsAudible(previous.outputPeak, current.outputPeak)) {
        return UsbExclusiveKeepAliveProgress.FAKE_PROGRESS
    }
    return UsbExclusiveKeepAliveProgress.ADVANCED
}

private fun counterAdvanced(previous: Long, current: Long): Boolean = previous >= 0L && current > previous

private fun outputIsAudible(previous: Float, current: Float): Boolean {
    if (previous.isNaN()) return true
    if (current.isNaN()) return true
    return current > USB_EXCLUSIVE_SILENT_OUTPUT_PEAK_MAX
}

private fun hasSeverePcmStarvation(
    zeroFillAdvanced: Boolean,
    previousZeroFillBytes: Long,
    currentZeroFillBytes: Long,
    queue: UsbKeepAliveQueue
): Boolean {
    if (!zeroFillAdvanced || !queue.hasUsableQueueMeasurements()) return false
    val bytesPerSecond = queue.sampleRate.toLong() * queue.frameBytes
    val zeroFillMs = bytesToDurationMs(currentZeroFillBytes - previousZeroFillBytes, bytesPerSecond)
    if (zeroFillMs < USB_EXCLUSIVE_SEVERE_ZERO_FILL_MS) return false
    return bytesToDurationMs(queue.pcmLevelBytes, bytesPerSecond) <= USB_EXCLUSIVE_RECOVERY_QUEUE_MAX_MS
}

private fun UsbKeepAliveQueue.hasUsableQueueMeasurements(): Boolean =
    sampleRate > 0 && frameBytes > 0 && pcmLevelBytes >= 0L

private fun bytesToDurationMs(bytes: Long, bytesPerSecond: Long): Long {
    if (bytes <= 0L || bytesPerSecond <= 0L) return 0L
    return bytes / bytesPerSecond * 1_000L + (bytes % bytesPerSecond) * 1_000L / bytesPerSecond
}

private const val USB_EXCLUSIVE_SILENT_OUTPUT_PEAK_MAX = 0.0001f
private const val USB_EXCLUSIVE_SEVERE_ZERO_FILL_MS = 750L
private const val USB_EXCLUSIVE_RECOVERY_QUEUE_MAX_MS = 100L
