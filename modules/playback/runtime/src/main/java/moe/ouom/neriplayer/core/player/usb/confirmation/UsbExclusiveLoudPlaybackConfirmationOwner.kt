package moe.ouom.neriplayer.core.player.usb.confirmation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveLoudPlaybackRisk
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveLoudnessPeakSource
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveOutputDeviceClass
import moe.ouom.neriplayer.core.player.policy.usb.shouldRequestUsbExclusiveLoudPlaybackWarning

data class UsbExclusiveLoudPlaybackConfirmation(
    val id: Long,
    val systemVolumePercent: Int,
    val deviceClass: UsbExclusiveOutputDeviceClass,
    val deviceName: String,
    val estimatedPeakDbfs: Double,
    val peakSource: UsbExclusiveLoudnessPeakSource,
    val riskThresholdDbfs: Int,
    val risk: UsbExclusiveLoudPlaybackRisk
)

internal class UsbExclusiveLoudPlaybackConfirmationOwner {
    private data class PendingConfirmation(
        val confirmation: UsbExclusiveLoudPlaybackConfirmation,
        val continuePlayback: () -> Unit,
        val cancelPlayback: (() -> Unit)?
    )

    private val _confirmationFlow = MutableStateFlow<UsbExclusiveLoudPlaybackConfirmation?>(null)
    val confirmationFlow: StateFlow<UsbExclusiveLoudPlaybackConfirmation?> = _confirmationFlow.asStateFlow()
    private var pending: PendingConfirmation? = null
    private var nextConfirmationId = 0L

    fun request(
        commandSource: PlaybackCommandSource,
        bypassWarning: Boolean,
        snapshot: () -> UsbExclusiveLoudPlaybackSnapshot,
        continuePlayback: () -> Unit,
        cancelPlayback: (() -> Unit)?
    ): Boolean {
        if (bypassWarning) return false
        return requestFromSnapshot(commandSource, snapshot(), continuePlayback, cancelPlayback)
    }

    private fun requestFromSnapshot(
        commandSource: PlaybackCommandSource,
        snapshot: UsbExclusiveLoudPlaybackSnapshot,
        continuePlayback: () -> Unit,
        cancelPlayback: (() -> Unit)?
    ): Boolean {
        val volumePercent = snapshot.systemVolumePercent ?: conservativeVolumePercent()
        val estimate = snapshot.estimate(volumePercent)
        if (!shouldRequestUsbExclusiveLoudPlaybackWarning(
                usbExclusiveEnabled = snapshot.usbExclusiveEnabled,
                appInForeground = snapshot.appInForeground,
                commandSource = commandSource,
                playbackAlreadyAudible = snapshot.playbackAlreadyAudible,
                loudnessEstimate = estimate
            )
        ) return false

        val confirmation = UsbExclusiveLoudPlaybackConfirmation(
            id = ++nextConfirmationId,
            systemVolumePercent = volumePercent,
            deviceClass = estimate.deviceClass,
            deviceName = snapshot.deviceName,
            estimatedPeakDbfs = estimate.estimatedPeakDbfs,
            peakSource = estimate.peakSource,
            riskThresholdDbfs = estimate.riskThresholdDbfs,
            risk = estimate.risk
        )
        pending = PendingConfirmation(confirmation, continuePlayback, cancelPlayback)
        _confirmationFlow.value = confirmation
        logDeferredConfirmation(confirmation, snapshot.outputRouteKey)
        return true
    }

    fun confirm(confirmationId: Long) {
        val matching = consumeMatching(confirmationId) ?: return
        NPLogger.i(
            "NERI-PlayerManager",
            "confirmed manual USB playback at peakDbfs=" + matching.confirmation.estimatedPeakDbfs
        )
        matching.continuePlayback()
    }

    fun cancel(confirmationId: Long) {
        val matching = consumeMatching(confirmationId) ?: return
        matching.cancelPlayback?.invoke()
        NPLogger.i("NERI-PlayerManager", "cancelled manual USB playback loud-volume confirmation")
    }

    private fun consumeMatching(confirmationId: Long): PendingConfirmation? {
        val matching = pending ?: return null
        if (matching.confirmation.id != confirmationId) return null
        pending = null
        _confirmationFlow.value = null
        return matching
    }

    private fun conservativeVolumePercent(): Int {
        NPLogger.w(
            "NERI-PlayerManager",
            "cannot read system media volume for USB loudness warning; use conservative full scale"
        )
        return 100
    }

    private fun logDeferredConfirmation(
        confirmation: UsbExclusiveLoudPlaybackConfirmation,
        routeKey: String
    ) {
        NPLogger.i(
            "NERI-PlayerManager",
            "defer manual USB playback for loud-volume confirmation: " +
                "volumePercent=${confirmation.systemVolumePercent} " +
                "peakDbfs=${confirmation.estimatedPeakDbfs} source=${confirmation.peakSource} " +
                "thresholdDbfs=${confirmation.riskThresholdDbfs} " +
                "risk=${confirmation.risk} route=$routeKey"
        )
    }
}
