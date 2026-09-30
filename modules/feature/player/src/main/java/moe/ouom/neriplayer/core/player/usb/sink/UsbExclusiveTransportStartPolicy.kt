package moe.ouom.neriplayer.core.player.usb.sink

import kotlin.math.min

internal enum class UsbExclusivePreWriteResult {
    Ready,
    RecoveryScheduled,
    TransportFailed
}

internal fun prepareUsbExclusiveNativeWrite(
    executePendingRecovery: () -> Boolean,
    resumeTransport: () -> Boolean
): UsbExclusivePreWriteResult {
    if (executePendingRecovery()) {
        return UsbExclusivePreWriteResult.RecoveryScheduled
    }
    return if (resumeTransport()) {
        UsbExclusivePreWriteResult.Ready
    } else {
        UsbExclusivePreWriteResult.TransportFailed
    }
}

internal fun shouldStartUsbExclusiveNativeTransport(
    hasQueuedPcm: Boolean,
    queuedFrames: Long,
    requiredPrerollFrames: Long,
    pcmCapacityFrames: Long,
    allowShortPreroll: Boolean,
    resumingPausedTransport: Boolean
): Boolean {
    if (!hasQueuedPcm || queuedFrames <= 0L) return false
    val effectivePrerollFrames = effectiveUsbExclusivePrerollFrames(
        requiredPrerollFrames = requiredPrerollFrames,
        pcmCapacityFrames = pcmCapacityFrames
    )
    return allowShortPreroll ||
        resumingPausedTransport ||
        queuedFrames >= effectivePrerollFrames
}

internal fun effectiveUsbExclusivePrerollFrames(
    requiredPrerollFrames: Long,
    pcmCapacityFrames: Long
): Long {
    val requestedFrames = requiredPrerollFrames.coerceAtLeast(1L)
    if (pcmCapacityFrames <= 0L) return requestedFrames
    val capacityWatermarkFrames = (pcmCapacityFrames - pcmCapacityFrames / 4L)
        .coerceAtLeast(1L)
    return min(requestedFrames, capacityWatermarkFrames)
}
