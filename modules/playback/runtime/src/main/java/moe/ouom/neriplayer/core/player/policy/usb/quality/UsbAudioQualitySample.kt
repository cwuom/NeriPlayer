package moe.ouom.neriplayer.core.player.policy.usb.quality

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveAudioQualityRecoveryState
import moe.ouom.neriplayer.core.player.usb.transport.outputFrameBytes
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics

private const val AUDIBLE_OUTPUT_PEAK_MIN = 0.0001f

internal fun UsbExclusiveRuntimeMetrics.isActiveUsbQualityOutput(handle: Long): Boolean =
    handle > 0L && source == "player_pcm" && running == true

internal fun UsbExclusiveRuntimeMetrics.toQualityState(handle: Long): UsbExclusiveAudioQualityRecoveryState =
    UsbExclusiveAudioQualityRecoveryState(
        handle = handle,
        completedTransfers = completedTransfers ?: -1L,
        isoPacketErrors = isoPacketErrors.counterOrZero(),
        isoPacketErrorTransfers = isoPacketErrorTransfers.counterOrZero(),
        isoPacketErrorScore = isoPacketErrorScore.counterOrZero(),
        playerSignalBytes = playerSignalBytes.counterOrZero(),
        playerDroppedBytes = playerDroppedBytes.counterOrZero(),
        playerUnderrunBytes = playerUnderrunBytes.counterOrZero(),
        playerZeroFillBytes = playerZeroFillBytes.counterOrZero()
    )

private fun Long?.counterOrZero(): Long = this ?: 0L
private fun Int?.counterOrZero(): Int = this ?: 0

private fun UsbExclusiveAudioQualityRecoveryState.runtimeCounters(): LongArray = longArrayOf(
    completedTransfers, isoPacketErrors, isoPacketErrorTransfers, isoPacketErrorScore.toLong(),
    playerSignalBytes, playerDroppedBytes, playerUnderrunBytes, playerZeroFillBytes
)

internal fun UsbExclusiveAudioQualityRecoveryState.hasCounterResetSince(
    previous: UsbExclusiveAudioQualityRecoveryState
): Boolean {
    val currentCounters = runtimeCounters()
    val previousCounters = previous.runtimeCounters()
    return currentCounters.indices.any { index -> currentCounters[index] < previousCounters[index] }
}

internal fun UsbExclusiveAudioQualityRecoveryState.isSameRuntimeCounterSampleAs(
    previous: UsbExclusiveAudioQualityRecoveryState
): Boolean = runtimeCounters().contentEquals(previous.runtimeCounters())

internal fun usbQualityGapBytes(metrics: UsbExclusiveRuntimeMetrics, gapMs: Long): Long {
    val sampleRate = positiveFormatValue(metrics.sampleRate) ?: return Long.MAX_VALUE
    val frameBytes = positiveFormatValue(metrics.outputFrameBytes) ?: return Long.MAX_VALUE
    return sampleRate.toLong() * frameBytes * gapMs / 1_000L
}

private fun positiveFormatValue(value: Int?): Int? {
    if (value == null) return null
    return value.takeIf { it > 0 }
}

internal fun UsbExclusiveRuntimeMetrics.bestOutputPeak(): Float? =
    lastOutputPeak.nonNaNPeak() ?: outputPeak.nonNaNPeak()

private fun Float?.nonNaNPeak(): Float? {
    if (this == null) return null
    if (isNaN()) return null
    return this
}

internal fun isMinorUsbQualityGap(
    largeGap: Boolean,
    ticks: Int,
    recoveryTicks: Int,
    signalDelta: Long,
    metrics: UsbExclusiveRuntimeMetrics
): Boolean = !largeGap && ticks < recoveryTicks && signalDelta > 0L && metrics.hasAudibleOutputPeak()

private fun UsbExclusiveRuntimeMetrics.hasAudibleOutputPeak(): Boolean {
    val peak = bestOutputPeak() ?: return false
    return peak > AUDIBLE_OUTPUT_PEAK_MIN
}
