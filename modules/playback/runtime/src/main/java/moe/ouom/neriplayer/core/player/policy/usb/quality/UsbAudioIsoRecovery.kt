package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryDecision
import kotlin.math.max

private const val ISO_PACKET_ERROR_RECOVERY_SCORE = 4
private const val ISO_PACKET_ERROR_RECOVERY_PACKETS = 4L
private const val ISO_PACKET_ERROR_RECOVERY_TRANSFERS = 3L

internal fun evaluateUsbIsoPacketErrors(
    previous: UsbExclusiveAudioQualityRecoveryState,
    snapshot: UsbExclusiveAudioQualityRecoveryState,
    stablePcmWindow: Boolean
): UsbExclusiveAudioQualityRecoveryDecision? {
    val packetDelta = max(0L, snapshot.isoPacketErrors - previous.isoPacketErrors)
    val transferDelta = max(0L, snapshot.isoPacketErrorTransfers - previous.isoPacketErrorTransfers)
    val scoreDelta = max(0, snapshot.isoPacketErrorScore - previous.isoPacketErrorScore)
    if (!hasIsoErrorDelta(packetDelta, transferDelta, scoreDelta)) return null
    val burst = hasIsoErrorBurst(snapshot.isoPacketErrorScore, packetDelta, transferDelta)
    val debug = "isoDelta=$packetDelta transferDelta=$transferDelta " +
        "score=${snapshot.isoPacketErrorScore} scoreDelta=$scoreDelta"
    if (stablePcmWindow && burst) return recoverUsbQuality(snapshot, "iso_packet_error", debug)
    val reason = if (stablePcmWindow) "minor_iso_packet_error" else "startup_iso_packet_error"
    return ignoreUsbQuality(snapshot, reason, debug)
}

private fun hasIsoErrorDelta(packetDelta: Long, transferDelta: Long, scoreDelta: Int): Boolean =
    packetDelta > 0L || transferDelta > 0L || scoreDelta > 0

private fun hasIsoErrorBurst(score: Int, packetDelta: Long, transferDelta: Long): Boolean =
    score >= ISO_PACKET_ERROR_RECOVERY_SCORE ||
        packetDelta >= ISO_PACKET_ERROR_RECOVERY_PACKETS ||
        transferDelta >= ISO_PACKET_ERROR_RECOVERY_TRANSFERS
