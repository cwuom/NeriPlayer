package moe.ouom.neriplayer.core.player.usb.transport

enum class UsbExclusiveErrorCode {
    None,
    OpenDeferred,
    PermissionDenied,
    DeviceDetached,
    NoSelectedDevice,
    NoCompatibleFormat,
    SampleRateUnsupported,
    BitDepthUnsupported,
    ChannelCountUnsupported,
    ClaimInterfaceFailed,
    SetAltFailed,
    SampleRateNegotiationFailed,
    AsyncFeedbackUnsupported,
    TransferFirstCompletionTimeout,
    TransferCompletionStalled,
    IsoPacketErrorBurst,
    TransportFailed,
    StaleHandle,
    InvalidBuffer,
    CancelDrainTimeout,
    Quarantined,
    NativeInternalError
}

data class UsbExclusiveRuntimeMetrics(
    val source: String? = null,
    val uacVersion: String? = null,
    val syncType: String? = null,
    val feedback: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val subslotBytes: Int? = null,
    val transferBytes: Long? = null,
    val lastTransferBytes: Long? = null,
    val completedTransfers: Long? = null,
    val inFlightTransfers: Int? = null,
    val isoPacketErrors: Long? = null,
    val isoPacketErrorTransfers: Long? = null,
    val isoPacketErrorScore: Int? = null,
    val pcmLevelBytes: Long? = null,
    val pcmCapacityBytes: Long? = null,
    val pcmFreeBytes: Long? = null,
    val pcmMaxLevelBytes: Long? = null,
    val pcmBackpressureEvents: Long? = null,
    val pcmBackpressureTotalMs: Long? = null,
    val pcmBackpressureCurrentMs: Long? = null,
    val pcmBackpressureMaxMs: Long? = null,
    val playerSignalFrames: Long? = null,
    val playerSilentFrames: Long? = null,
    val playerSignalBytes: Long? = null,
    val playerDroppedBytes: Long? = null,
    val playerUnderrunBytes: Long? = null,
    val playerZeroFillBytes: Long? = null,
    val playerPausedZeroFillBytes: Long? = null,
    val outputPeak: Float? = null,
    val lastOutputPeak: Float? = null,
    val transportFailed: Boolean? = null,
    val deviceOnline: Boolean? = null,
    val running: Boolean? = null,
    val paused: Boolean? = null,
    val errorCode: UsbExclusiveErrorCode = UsbExclusiveErrorCode.None,
    val lastError: String = "none"
) {
    val outputFrameBytes: Int?
        get() {
            val channels = channelCount ?: return null
            val bytes = subslotBytes ?: return null
            val frameBytes = channels * bytes
            return frameBytes.takeIf { it > 0 }
        }

    val hasPcmQueue: Boolean
        get() = (pcmCapacityBytes ?: 0L) > 0L

    val hasHealthyTransport: Boolean
        get() = transportFailed != true &&
            errorCode == UsbExclusiveErrorCode.None &&
            lastError == "none"

    val isQueueFull: Boolean
        get() {
            pcmFreeBytes?.let { return it <= 0L && (pcmCapacityBytes ?: 0L) > 0L }
            val level = pcmLevelBytes ?: return false
            val capacity = pcmCapacityBytes ?: return false
            return capacity > 0L && level >= capacity
        }

    val isBenignBackpressure: Boolean
        get() = isQueueFull && hasHealthyTransport

    val hasPlayerPcmAudioQualityDegradation: Boolean
        get() {
            if (source != "player_pcm" || running != true || paused == true) return false
            if ((isoPacketErrorScore ?: 0) > 0) return true
            if ((isoPacketErrorTransfers ?: 0L) > 0L) return true
            if ((isoPacketErrors ?: 0L) > 0L) return true
            return (playerDroppedBytes ?: 0L) > 0L
        }

    val hasPlayerPcmBufferStarvationCounters: Boolean
        get() {
            if (source != "player_pcm" || running != true || paused == true) return false
            if ((playerUnderrunBytes ?: 0L) > 0L) return true
            return (playerZeroFillBytes ?: 0L) > 0L
        }
}

internal fun String.usbRuntimeMetrics(): UsbExclusiveRuntimeMetrics {
    val levelPart = valueAfter("pcmLevel")
    val errorCode = usbExclusiveErrorCode()
    return UsbExclusiveRuntimeMetrics(
        source = valueAfter("source"),
        uacVersion = valueAfter("uacVersion"),
        syncType = valueAfter("syncType"),
        feedback = valueAfter("feedback"),
        sampleRate = valueAfter("sampleRate")?.toIntOrNull(),
        channelCount = valueAfter("channels")?.toIntOrNull(),
        subslotBytes = valueAfter("subslotBytes")?.toIntOrNull(),
        transferBytes = valueAfter("transferBytes")?.toLongOrNull(),
        lastTransferBytes = valueAfter("lastTransferBytes")?.toLongOrNull(),
        completedTransfers = valueAfter("completedTransfers")?.toLongOrNull(),
        inFlightTransfers = valueAfter("inFlight")?.toIntOrNull(),
        isoPacketErrors = valueAfter("isoPacketErrors")?.toLongOrNull(),
        isoPacketErrorTransfers = valueAfter("isoPacketErrorTransfers")?.toLongOrNull(),
        isoPacketErrorScore = valueAfter("isoPacketErrorScore")?.toIntOrNull(),
        pcmLevelBytes = levelPart?.substringBefore('/')?.toLongOrNull(),
        pcmCapacityBytes = levelPart
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.toLongOrNull(),
        pcmFreeBytes = valueAfter("pcmFreeBytes")?.toLongOrNull(),
        pcmMaxLevelBytes = valueAfter("pcmMaxLevelBytes")?.toLongOrNull(),
        pcmBackpressureEvents = valueAfter("pcmBackpressureEvents")?.toLongOrNull(),
        pcmBackpressureTotalMs = valueAfter("pcmBackpressureTotalMs")?.toLongOrNull(),
        pcmBackpressureCurrentMs = valueAfter("pcmBackpressureCurrentMs")?.toLongOrNull(),
        pcmBackpressureMaxMs = valueAfter("pcmBackpressureMaxMs")?.toLongOrNull(),
        playerSignalFrames = valueAfter("playerSignalFrames")?.toLongOrNull(),
        playerSilentFrames = valueAfter("playerSilentFrames")?.toLongOrNull(),
        playerSignalBytes = valueAfter("playerSignalBytes")?.toLongOrNull(),
        playerDroppedBytes = valueAfter("playerDroppedBytes")?.toLongOrNull(),
        playerUnderrunBytes = valueAfter("playerUnderrunBytes")?.toLongOrNull(),
        playerZeroFillBytes = valueAfter("playerZeroFillBytes")?.toLongOrNull(),
        playerPausedZeroFillBytes = valueAfter("playerPausedZeroFillBytes")?.toLongOrNull(),
        outputPeak = valueAfter("outputPeak")?.toFloatOrNull(),
        lastOutputPeak = valueAfter("lastOutputPeak")?.toFloatOrNull(),
        transportFailed = booleanField("transportFailed"),
        deviceOnline = booleanField("deviceOnline"),
        running = booleanField("running"),
        paused = booleanField("paused"),
        errorCode = errorCode,
        lastError = valueAfter("lastError") ?: "none"
    )
}

internal fun UsbExclusiveRuntimeMetrics.withLivePcmFreeBytes(
    liveFreeBytes: Long
): UsbExclusiveRuntimeMetrics {
    val capacity = pcmCapacityBytes?.takeIf { it > 0L }
    val normalizedFreeBytes = if (capacity != null) {
        liveFreeBytes.coerceIn(0L, capacity)
    } else {
        liveFreeBytes.coerceAtLeast(0L)
    }
    return copy(
        pcmLevelBytes = capacity?.let { it - normalizedFreeBytes } ?: pcmLevelBytes,
        pcmFreeBytes = normalizedFreeBytes
    )
}

internal fun String.valueAfter(key: String): String? {
    val regex = Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

internal fun String.booleanField(name: String): Boolean? {
    return when (valueAfter(name)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun String.usbExclusiveErrorCode(): UsbExclusiveErrorCode {
    val normalized = trim()
    if (normalized.isBlank() || normalized == "none" || normalized == "idle") {
        return UsbExclusiveErrorCode.None
    }
    if (normalized.startsWith("native_idle")) return UsbExclusiveErrorCode.None

    val lastError = valueAfter("lastError")
    val lower = normalized.lowercase()
    val errorProbe = listOfNotNull(lower, lastError?.lowercase()).joinToString(separator = " ")
    val hasExplicitLastError = !lastError.isNullOrBlank() && lastError != "none"

    return when {
        lower.startsWith("native_open_deferred") ||
            lower.startsWith("native_reopen_cooling_down") ||
            lower.startsWith("native_transition_in_flight") ||
            lower.startsWith("native_refresh_deferred") ->
            UsbExclusiveErrorCode.OpenDeferred
        errorProbe.contains("permission") ||
            errorProbe.contains("securityexception") ->
            UsbExclusiveErrorCode.PermissionDenied
        errorProbe.contains("usb_device_detached") ||
            errorProbe.contains("libusb_error_no_device") ||
            errorProbe.contains("deviceonline=false") ||
            errorProbe.contains("no_device") ->
            UsbExclusiveErrorCode.DeviceDetached
        errorProbe.contains("no permitted usb audio streaming device") ||
            errorProbe.contains("no_selected_system_usb_audio_output") ||
            errorProbe.contains("no usb") ->
            UsbExclusiveErrorCode.NoSelectedDevice
        errorProbe.contains("async_feedback_scheduler_unavailable") ||
            errorProbe.contains("feedback_scheduler_unavailable") ->
            UsbExclusiveErrorCode.AsyncFeedbackUnsupported
        errorProbe.contains("sample_rate_negotiation_failed") ->
            UsbExclusiveErrorCode.SampleRateNegotiationFailed
        errorProbe.contains("sample_rate_unsupported") ->
            UsbExclusiveErrorCode.SampleRateUnsupported
        errorProbe.contains("bit_depth_unsupported") ||
            errorProbe.contains("native_bit_depth_unsupported") ||
            errorProbe.contains("subslot_unsupported") ->
            UsbExclusiveErrorCode.BitDepthUnsupported
        errorProbe.contains("channel_count_unsupported") ->
            UsbExclusiveErrorCode.ChannelCountUnsupported
        errorProbe.contains("no_compatible_usb_audio_format") ->
            UsbExclusiveErrorCode.NoCompatibleFormat
        errorProbe.contains("claim_audio_function_failed") ||
            errorProbe.contains("claim_interface") ->
            UsbExclusiveErrorCode.ClaimInterfaceFailed
        errorProbe.contains("set_alt_failed") ||
            errorProbe.contains("set_interface_alt") ->
            UsbExclusiveErrorCode.SetAltFailed
        errorProbe.contains("event_loop_first_completion_timeout") ||
            errorProbe.contains("first_completion_timeout") ->
            UsbExclusiveErrorCode.TransferFirstCompletionTimeout
        errorProbe.contains("completion_stalled") ||
            errorProbe.contains("transfer_completion_stall") ->
            UsbExclusiveErrorCode.TransferCompletionStalled
        errorProbe.contains("iso_packet_error") ||
            errorProbe.contains("isochronous_packet_error") ->
            UsbExclusiveErrorCode.IsoPacketErrorBurst
        errorProbe.contains("quarantine_drain_timeout") ||
            errorProbe.contains("cancel_drain_timeout") ->
            UsbExclusiveErrorCode.CancelDrainTimeout
        errorProbe.contains("quarantine") ->
            UsbExclusiveErrorCode.Quarantined
        errorProbe.contains("stale_handle") ->
            UsbExclusiveErrorCode.StaleHandle
        errorProbe.contains("invalid_buffer") ->
            UsbExclusiveErrorCode.InvalidBuffer
        errorProbe.contains("libusb_error_io") ||
            errorProbe.contains("transfer_status=5") ||
            errorProbe.contains("resubmit_failed") ||
            errorProbe.contains("submit_failed") ||
            errorProbe.contains("transportfailed=true") ||
            errorProbe.contains("transport_failed") ->
            UsbExclusiveErrorCode.TransportFailed
        errorProbe.contains("jni_bridge") ||
            errorProbe.contains("native_unavailable") ||
            errorProbe.contains("nativeopen failed") ->
            UsbExclusiveErrorCode.NativeInternalError
        hasExplicitLastError -> UsbExclusiveErrorCode.NativeInternalError
        else -> UsbExclusiveErrorCode.None
    }
}

internal val UsbExclusiveErrorCode.isRecoverableTransportFailure: Boolean
    get() = when (this) {
        UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
        UsbExclusiveErrorCode.TransferCompletionStalled,
        UsbExclusiveErrorCode.IsoPacketErrorBurst,
        UsbExclusiveErrorCode.TransportFailed -> true
        else -> false
    }

internal val UsbExclusiveErrorCode.requiresFreshNativeOpen: Boolean
    get() = when (this) {
        UsbExclusiveErrorCode.DeviceDetached,
        UsbExclusiveErrorCode.ClaimInterfaceFailed,
        UsbExclusiveErrorCode.SetAltFailed,
        UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
        UsbExclusiveErrorCode.TransferCompletionStalled,
        UsbExclusiveErrorCode.IsoPacketErrorBurst,
        UsbExclusiveErrorCode.TransportFailed,
        UsbExclusiveErrorCode.CancelDrainTimeout,
        UsbExclusiveErrorCode.Quarantined,
        UsbExclusiveErrorCode.NativeInternalError -> true
        else -> false
    }

internal val UsbExclusiveErrorCode.allowsAlternativeOutputRetry: Boolean
    get() = when (this) {
        UsbExclusiveErrorCode.NoCompatibleFormat,
        UsbExclusiveErrorCode.SampleRateNegotiationFailed -> true
        else -> false
    }

internal val UsbExclusiveErrorCode.suppressesSystemFallbackPlayback: Boolean
    get() = when (this) {
        UsbExclusiveErrorCode.OpenDeferred,
        UsbExclusiveErrorCode.PermissionDenied,
        UsbExclusiveErrorCode.DeviceDetached,
        UsbExclusiveErrorCode.NoSelectedDevice,
        UsbExclusiveErrorCode.ClaimInterfaceFailed,
        UsbExclusiveErrorCode.SetAltFailed,
        UsbExclusiveErrorCode.AsyncFeedbackUnsupported,
        UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
        UsbExclusiveErrorCode.TransferCompletionStalled,
        UsbExclusiveErrorCode.IsoPacketErrorBurst,
        UsbExclusiveErrorCode.TransportFailed,
        UsbExclusiveErrorCode.CancelDrainTimeout,
        UsbExclusiveErrorCode.Quarantined,
        UsbExclusiveErrorCode.NativeInternalError -> true
        else -> false
    }
