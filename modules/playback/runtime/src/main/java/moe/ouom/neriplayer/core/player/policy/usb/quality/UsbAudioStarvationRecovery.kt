package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryDecision
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import kotlin.math.max

private const val PCM_STARVATION_RECOVERY_TICKS = 2
private const val PCM_STARVATION_LARGE_GAP_MS = 80L

internal fun evaluateUsbPcmStarvation(
    previous: UsbExclusiveAudioQualityRecoveryState,
    snapshot: UsbExclusiveAudioQualityRecoveryState,
    metrics: UsbExclusiveRuntimeMetrics,
    stablePcmWindow: Boolean
): UsbExclusiveAudioQualityRecoveryDecision {
    val underrunDelta = max(0L, snapshot.playerUnderrunBytes - previous.playerUnderrunBytes)
    val zeroFillDelta = max(0L, snapshot.playerZeroFillBytes - previous.playerZeroFillBytes)
    val starvationDelta = max(underrunDelta, zeroFillDelta)
    if (starvationDelta <= 0L) return evaluateUnchangedUsbQualitySample(previous, snapshot)
    if (!stablePcmWindow) {
        return ignoreUsbQuality(
            snapshot = snapshot,
            reason = "startup_pcm_starvation",
            debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                "completedTransfers=${snapshot.completedTransfers}"
        )
    }

    val nextTicks = (previous.consecutivePcmStarvationTicks + 1)
        .coerceAtMost(PCM_STARVATION_RECOVERY_TICKS)
    val armedSnapshot = snapshot.copy(consecutivePcmStarvationTicks = nextTicks)
    val largeGapBytes = largeStarvationGapBytes(metrics)
    val largeGap = largeGapBytes > 0L && starvationDelta >= largeGapBytes
    val signalDelta = max(0L, snapshot.playerSignalBytes - previous.playerSignalBytes)
    if (isMinorUsbQualityGap(largeGap, nextTicks, PCM_STARVATION_RECOVERY_TICKS, signalDelta, metrics)) {
        return ignoreUsbQuality(
            snapshot = armedSnapshot,
            reason = "minor_pcm_starvation_with_signal",
            debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                "signalDelta=$signalDelta peak=${metrics.bestOutputPeak()} " +
                "ticks=$nextTicks threshold=$largeGapBytes"
        )
    }
    if (largeGap) {
        return ignoreUsbQuality(
            snapshot = armedSnapshot,
            reason = "large_pcm_starvation",
            debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                "ticks=$nextTicks largeGap=$largeGap threshold=$largeGapBytes " +
                "reopenSuppressed=true"
        )
    }
    if (nextTicks >= PCM_STARVATION_RECOVERY_TICKS) {
        return ignoreUsbQuality(
            snapshot = armedSnapshot,
            reason = "persistent_pcm_starvation",
            debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta " +
                "ticks=$nextTicks threshold=$largeGapBytes reopenSuppressed=true"
        )
    }
    return ignoreUsbQuality(
        snapshot = armedSnapshot,
        reason = "armed_pcm_starvation",
        debug = "underrunDelta=$underrunDelta zeroFillDelta=$zeroFillDelta ticks=$nextTicks"
    )
}

private fun largeStarvationGapBytes(metrics: UsbExclusiveRuntimeMetrics): Long =
    usbQualityGapBytes(metrics, PCM_STARVATION_LARGE_GAP_MS)

private fun evaluateUnchangedUsbQualitySample(
    previous: UsbExclusiveAudioQualityRecoveryState,
    snapshot: UsbExclusiveAudioQualityRecoveryState
): UsbExclusiveAudioQualityRecoveryDecision {
    if (
        previous.consecutivePlayerDropTicks > 0 &&
        snapshot.isSameRuntimeCounterSampleAs(previous)
    ) {
        return ignoreUsbQuality(
            snapshot = snapshot.copy(
                consecutivePlayerDropTicks = previous.consecutivePlayerDropTicks
            ),
            reason = "awaiting_player_drop_sample",
            debug = "ticks=${previous.consecutivePlayerDropTicks} " +
                "completedTransfers=${snapshot.completedTransfers}"
        )
    }
    if (
        previous.consecutivePcmStarvationTicks > 0 &&
        snapshot.isSameRuntimeCounterSampleAs(previous)
    ) {
        return ignoreUsbQuality(
            snapshot = snapshot.copy(
                consecutivePcmStarvationTicks = previous.consecutivePcmStarvationTicks
            ),
            reason = "awaiting_pcm_starvation_sample",
            debug = "ticks=${previous.consecutivePcmStarvationTicks} " +
                "completedTransfers=${snapshot.completedTransfers}"
        )
    }
    return ignoreUsbQuality(snapshot, "healthy", "completedTransfers=${snapshot.completedTransfers}")
}
