package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.policy.usb.quality.evaluateUsbIsoPacketErrors
import moe.ouom.neriplayer.core.player.policy.usb.quality.evaluateUsbPlayerDrop
import moe.ouom.neriplayer.core.player.policy.usb.quality.evaluateUsbPcmStarvation
import moe.ouom.neriplayer.core.player.policy.usb.quality.toQualityState
import moe.ouom.neriplayer.core.player.policy.usb.quality.isActiveUsbQualityOutput
import moe.ouom.neriplayer.core.player.policy.usb.quality.hasCounterResetSince
import moe.ouom.neriplayer.core.player.policy.usb.quality.ignoreUsbQuality

internal data class UsbExclusiveAudioQualityRecoveryState(
    val handle: Long = 0L,
    val completedTransfers: Long = -1L,
    val isoPacketErrors: Long = 0L,
    val isoPacketErrorTransfers: Long = 0L,
    val isoPacketErrorScore: Int = 0,
    val playerSignalBytes: Long = 0L,
    val playerDroppedBytes: Long = 0L,
    val playerUnderrunBytes: Long = 0L,
    val playerZeroFillBytes: Long = 0L,
    val consecutivePlayerDropTicks: Int = 0,
    val consecutivePcmStarvationTicks: Int = 0
)

internal data class UsbExclusiveAudioQualityRecoveryDecision(
    val shouldRecover: Boolean,
    val state: UsbExclusiveAudioQualityRecoveryState,
    val reason: String,
    val debug: String
)

internal object UsbExclusiveAudioQualityRecoveryPolicy {
    private const val STARTUP_PCM_COUNTER_GRACE_MS = 1_000L

    fun reset(handle: Long = 0L): UsbExclusiveAudioQualityRecoveryState {
        return UsbExclusiveAudioQualityRecoveryState(handle = handle)
    }

    fun evaluate(
        previous: UsbExclusiveAudioQualityRecoveryState,
        handle: Long,
        metrics: UsbExclusiveRuntimeMetrics,
        nowMs: Long,
        transportStartedAtMs: Long
    ): UsbExclusiveAudioQualityRecoveryDecision {
        val snapshot = metrics.toQualityState(handle)
        if (!metrics.isActiveUsbQualityOutput(handle)) {
            return ignoreUsbQuality(snapshot, "inactive", "source=${metrics.source} running=${metrics.running}")
        }
        if (metrics.paused == true) {
            return ignoreUsbQuality(snapshot, "paused", "paused=true")
        }
        if (previous.handle != handle || snapshot.hasCounterResetSince(previous)) {
            return ignoreUsbQuality(snapshot, "baseline", "handle=$handle")
        }

        val stablePcmWindow = isStablePcmQualityWindow(
            nowMs = nowMs,
            transportStartedAtMs = transportStartedAtMs,
            completedTransfers = snapshot.completedTransfers
        )
        return evaluateUsbIsoPacketErrors(previous, snapshot, stablePcmWindow)
            ?: evaluateUsbPlayerDrop(previous, snapshot, metrics, stablePcmWindow)
            ?: evaluateUsbPcmStarvation(previous, snapshot, metrics, stablePcmWindow)
    }

    private fun isStablePcmQualityWindow(
        nowMs: Long,
        transportStartedAtMs: Long,
        completedTransfers: Long
    ): Boolean {
        if (transportStartedAtMs <= 0L) return false
        if (nowMs - transportStartedAtMs < STARTUP_PCM_COUNTER_GRACE_MS) return false
        return completedTransfers > 0L
    }
}
