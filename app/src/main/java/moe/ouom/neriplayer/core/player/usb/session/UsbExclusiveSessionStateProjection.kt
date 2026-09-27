package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.core.player.usb.sink.ResolvedUsbOutputFormat
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics
import moe.ouom.neriplayer.core.player.policy.usb.isUsbDeviceDetachOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.isNativeCloseInFlightUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics

internal fun UsbExclusiveNativeState.withRuntimeReport(
    runtimeReport: String
): UsbExclusiveNativeState {
    val metrics = runtimeReport.usbRuntimeMetrics()
    return withRuntimeMetadata(runtimeReport, metrics)
        .withPcmRuntimeMetrics(metrics)
        .withPlayerRuntimeMetrics(metrics)
        .withOutputRuntimeMetrics(metrics)
}

private fun UsbExclusiveNativeState.withRuntimeMetadata(
    runtimeReport: String,
    metrics: UsbExclusiveRuntimeMetrics
): UsbExclusiveNativeState = copy(
    runtimeReport = runtimeReport,
    runtimeReportVersion = metrics.reportVersion,
    runtimeReportValid = metrics.reportValid,
    runtimeReportInvalidReason = metrics.reportInvalidReason,
    feedbackMode = metrics.feedbackMode,
    feedbackState = metrics.feedbackState,
    playbackReady = metrics.playbackReady,
    feedbackReusable = metrics.feedbackReusable,
    terminalFailure = metrics.terminalFailure,
    recommendedAction = metrics.recommendedAction,
    actionId = metrics.actionId,
    actionGeneration = metrics.actionGeneration,
    actionOwner = metrics.actionOwner,
    actionLatched = metrics.actionLatched,
    nativeStreamGeneration = metrics.nativeStreamGeneration,
    recoveryEpoch = metrics.recoveryEpoch,
    candidateId = metrics.candidateId
)

private fun UsbExclusiveNativeState.withPcmRuntimeMetrics(
    metrics: UsbExclusiveRuntimeMetrics
): UsbExclusiveNativeState = copy(
    pcmLevelBytes = metrics.pcmLevelBytes ?: pcmLevelBytes,
    pcmCapacityBytes = metrics.pcmCapacityBytes ?: pcmCapacityBytes,
    pcmFreeBytes = metrics.pcmFreeBytes ?: pcmFreeBytes,
    pcmBackpressureEvents = metrics.pcmBackpressureEvents ?: pcmBackpressureEvents,
    pcmBackpressureTotalMs = metrics.pcmBackpressureTotalMs ?: pcmBackpressureTotalMs,
    pcmBackpressureCurrentMs = metrics.pcmBackpressureCurrentMs ?: pcmBackpressureCurrentMs,
    pcmBackpressureMaxMs = metrics.pcmBackpressureMaxMs ?: pcmBackpressureMaxMs
)

private fun UsbExclusiveNativeState.withPlayerRuntimeMetrics(
    metrics: UsbExclusiveRuntimeMetrics
): UsbExclusiveNativeState = copy(
    playerSignalFrames = metrics.playerSignalFrames ?: playerSignalFrames,
    playerSilentFrames = metrics.playerSilentFrames ?: playerSilentFrames,
    playerSignalBytes = metrics.playerSignalBytes ?: playerSignalBytes,
    playerDroppedBytes = metrics.playerDroppedBytes ?: playerDroppedBytes,
    playerUnderrunBytes = metrics.playerUnderrunBytes ?: playerUnderrunBytes,
    playerZeroFillBytes = metrics.playerZeroFillBytes ?: playerZeroFillBytes,
    playerPausedZeroFillBytes = metrics.playerPausedZeroFillBytes ?: playerPausedZeroFillBytes
)

private fun UsbExclusiveNativeState.withOutputRuntimeMetrics(
    metrics: UsbExclusiveRuntimeMetrics
): UsbExclusiveNativeState = copy(
    outputPeak = metrics.outputPeak ?: outputPeak,
    lastOutputPeak = metrics.lastOutputPeak ?: lastOutputPeak,
    channel0OutputPeak = metrics.channel0OutputPeak ?: channel0OutputPeak,
    channel1OutputPeak = metrics.channel1OutputPeak ?: channel1OutputPeak,
    lastChannel0OutputPeak = metrics.lastChannel0OutputPeak ?: lastChannel0OutputPeak,
    lastChannel1OutputPeak = metrics.lastChannel1OutputPeak ?: lastChannel1OutputPeak
)

internal fun UsbExclusiveNativeState.withLivePlayerPcmFreeBytes(
    liveFreeBytes: Long?
): UsbExclusiveNativeState {
    val freeBytes = liveFreeBytes ?: return this
    val capacity = pcmCapacityBytes.takeIf { it > 0L }
    val normalizedFreeBytes = capacity?.let { freeBytes.coerceIn(0L, it) }
        ?: freeBytes.coerceAtLeast(0L)
    return copy(
        pcmFreeBytes = normalizedFreeBytes,
        pcmLevelBytes = capacity?.let { it - normalizedFreeBytes } ?: pcmLevelBytes
    )
}

internal fun UsbExclusiveNativeState.matchesPlayerSession(expectedHandle: Long): Boolean {
    return handle == expectedHandle && source == "player_pcm" && opened
}

internal fun UsbExclusiveNativeState.afterNativeStop(terminalError: String?): UsbExclusiveNativeState =
    copy(
        opened = false,
        streaming = false,
        paused = false,
        transitioning = false,
        source = "idle",
        handle = 0L,
        inputFormat = "none",
        outputFormat = "none",
        requestedOutputFormat = "none",
        outputSampleRate = 0,
        completedAudioFrames = 0L,
        queuedAudioFrames = 0L,
        runtimeReport = terminalError ?: "idle",
        lastError = terminalError
    )

internal fun UsbExclusiveNativeState.withReconfiguredOutput(
    output: ResolvedUsbOutputFormat,
    requestedOutput: ResolvedUsbOutputFormat,
    inputFormat: String,
    report: String
): UsbExclusiveNativeState = copy(
    streaming = false,
    paused = false,
    source = "player_pcm",
    inputFormat = inputFormat,
    outputFormat = output.description,
    requestedOutputFormat = requestedOutput.description,
    outputSampleRate = output.sampleRate,
    bufferDurationMs = output.bufferDurationMs,
    lastError = null,
    completedAudioFrames = 0L,
    queuedAudioFrames = 0L
).withRuntimeReport(report)

internal fun UsbExclusiveNativeState.withReusedOutput(
    inputFormat: String,
    requestedOutput: ResolvedUsbOutputFormat,
    report: String
): UsbExclusiveNativeState = copy(
    streaming = false,
    paused = false,
    source = "player_pcm",
    inputFormat = inputFormat,
    requestedOutputFormat = requestedOutput.description,
    bufferDurationMs = requestedOutput.bufferDurationMs,
    lastError = null,
    completedAudioFrames = 0L,
    queuedAudioFrames = 0L
).withRuntimeReport(report)

internal fun UsbExclusiveNativeState.withUnresolvedOutput(
    inputFormat: String,
    error: String
): UsbExclusiveNativeState = copy(
    opened = false,
    streaming = false,
    paused = false,
    source = "idle",
    handle = 0L,
    inputFormat = inputFormat,
    outputFormat = "unresolved",
    requestedOutputFormat = "none",
    runtimeReport = error,
    lastError = error
)

internal fun UsbExclusiveNativeState.withDeferredOutput(
    inputFormat: String,
    requestedOutput: String,
    error: String
): UsbExclusiveNativeState = copy(
    opened = false,
    streaming = false,
    paused = false,
    source = "idle",
    handle = 0L,
    inputFormat = inputFormat,
    outputFormat = "deferred",
    requestedOutputFormat = requestedOutput,
    runtimeReport = error,
    lastError = error
)

internal fun UsbExclusiveNativeState.isIdleOpenDeferred(): Boolean {
    if (handle != 0L) return false
    return listOf(lastError.orEmpty(), runtimeReport).any {
        it.startsWith("native_open_deferred")
    }
}

internal fun UsbExclusiveNativeState.canPublishIdleOpenGateError(): Boolean =
    handle == 0L || source == "idle"

internal fun UsbExclusiveNativeState.isDetachedIdleState(): Boolean {
    if (handle != 0L) return false
    return hasDeviceDetachOpenGate()
}

private fun UsbExclusiveNativeState.hasDeviceDetachOpenGate(): Boolean =
    isUsbDeviceDetachOpenGate(runtimeReport) ||
        isUsbDeviceDetachOpenGate(lastError.orEmpty())

internal fun UsbExclusiveNativeState.isWaitingForNativeCloseGate(): Boolean {
    if (handle != 0L) return false
    return hasNativeCloseOpenGate()
}

private fun UsbExclusiveNativeState.hasNativeCloseOpenGate(): Boolean =
    isNativeCloseInFlightUsbExclusiveOpenGate(runtimeReport) ||
        isNativeCloseInFlightUsbExclusiveOpenGate(lastError.orEmpty())

internal fun attachedIdleRuntimeReport(nativeCloseCount: Int): String =
    if (nativeCloseCount > 0) {
        "native_open_deferred:native_close_in_flight count=$nativeCloseCount"
    } else {
        "native_idle"
    }

internal fun openedPlayerPcmState(
    handle: Long,
    deviceName: String,
    inputFormat: String,
    activeOutput: ResolvedUsbOutputFormat,
    requestedOutput: ResolvedUsbOutputFormat,
    report: String
): UsbExclusiveNativeState = UsbExclusiveNativeState(
    available = true,
    opened = true,
    streaming = false,
    paused = false,
    transitioning = false,
    source = "player_pcm",
    handle = handle,
    selectedDeviceName = deviceName,
    inputFormat = inputFormat,
    outputFormat = activeOutput.description,
    requestedOutputFormat = requestedOutput.description,
    outputSampleRate = activeOutput.sampleRate,
    bufferDurationMs = activeOutput.bufferDurationMs,
    lastError = null
).withRuntimeReport(report)

internal fun openedToneState(
    handle: Long,
    deviceName: String,
    report: String
): UsbExclusiveNativeState = UsbExclusiveNativeState(
    available = true,
    opened = true,
    streaming = true,
    transitioning = false,
    source = "tone",
    handle = handle,
    selectedDeviceName = deviceName,
    lastError = null
).withRuntimeReport(report)

internal fun UsbExclusiveNativeState.withRearmedOutput(
    output: ResolvedUsbOutputFormat,
    inputFormat: String,
    result: UsbExclusiveSessionResources.RearmResult
): UsbExclusiveNativeState = copy(
    streaming = false,
    paused = false,
    transitioning = false,
    inputFormat = inputFormat,
    outputFormat = output.description,
    requestedOutputFormat = output.description,
    outputSampleRate = output.sampleRate,
    bufferDurationMs = output.bufferDurationMs,
    lastError = if (result.prepared) null else result.report,
    completedAudioFrames = result.completedFrames,
    queuedAudioFrames = result.queuedFrames
).withRuntimeReport(result.report)

internal fun buildNativeIdleRuntimeReport(snapshot: UsbExclusiveDiagnosticsSnapshot): String {
    return "native_idle usbHostDevices=${snapshot.usbHostDevices.size} " +
        "usbOutputs=${snapshot.audioOutputs.count { it.isUsbOutput }}"
}

internal fun String?.isPersistentIdleNativeError(): Boolean {
    val normalized = this.orEmpty().trim()
    if (normalized.isBlank()) return false
    return normalized.isPersistentNonblankIdleError()
}

private fun String.isPersistentNonblankIdleError(): Boolean {
    return this !in NONPERSISTENT_IDLE_EXACT &&
        !startsWith("native_idle") &&
        NONPERSISTENT_IDLE_PREFIXES.none { startsWith(it) } &&
        !contains("usb_exclusive_disabled", ignoreCase = true)
}

private val NONPERSISTENT_IDLE_EXACT = setOf("none", "idle")

private val NONPERSISTENT_IDLE_PREFIXES = listOf(
    "native_open_deferred", "native_reopen_cooling_down", "native_refresh_deferred",
    "native_transition_in_flight", "stop_deferred", "stop_applied"
)
