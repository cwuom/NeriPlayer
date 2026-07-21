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
    FeedbackEndpointInvalid,
    FeedbackInitialLockTimeout,
    FeedbackPayloadInvalid,
    FeedbackTransferFailed,
    FeedbackLost,
    FeedbackPacketCapacityExceeded,
    ImplicitFeedbackTopologyUnsupported,
    ImplicitFeedbackTransferFailed,
    FeedbackQuirkRequired,
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

enum class UsbExclusiveFeedbackMode {
    Disabled,
    Explicit,
    Implicit
}

enum class UsbExclusiveFeedbackState {
    Disabled,
    Priming,
    Acquiring,
    Locked,
    Holdover,
    Relocking,
    Failed
}

enum class UsbExclusiveFeedbackClockFailure {
    None,
    AcquireTimeout,
    HoldoverTimeout,
    NonMonotonicTime
}

enum class UsbExclusiveRecoveryAction {
    None,
    Holdover,
    Relock,
    SameHandleRearm,
    SwitchNativeCandidate,
    FreshOpen,
    StopPreserveIntent
}

enum class UsbExclusiveRecoveryActionOwner {
    None,
    Native,
    Kotlin
}

enum class UsbExclusiveRecoveryActionAckStatus {
    Acked,
    AlreadyAcked,
    GenerationMismatch,
    HandleClosing,
    NoPending
}

data class UsbExclusiveRuntimeMetrics(
    val reportVersion: Int = 1,
    val reportValid: Boolean = true,
    val reportInvalidReason: String? = null,
    val source: String? = null,
    val uacVersion: String? = null,
    val syncType: String? = null,
    val feedback: String? = null,
    val feedbackMode: UsbExclusiveFeedbackMode = UsbExclusiveFeedbackMode.Disabled,
    val feedbackEndpointAddress: Int? = null,
    val feedbackState: UsbExclusiveFeedbackState = UsbExclusiveFeedbackState.Disabled,
    val feedbackPayloadBytes: Int? = null,
    val feedbackExpectedPeriodUs: Long? = null,
    val feedbackRawValue: String? = null,
    val feedbackRateQ32: String? = null,
    val feedbackRateHz: Double? = null,
    val feedbackRatePpm: Long? = null,
    val feedbackValidSamples: Long? = null,
    val feedbackInvalidSamples: Long? = null,
    val feedbackOutliers: Long? = null,
    val feedbackTimeouts: Long? = null,
    val feedbackLockCount: Long? = null,
    val feedbackRelockCount: Long? = null,
    val feedbackHoldoverCount: Long? = null,
    val feedbackHoldoverTotalMs: Long? = null,
    val feedbackLongGapReacquisitions: Long? = null,
    val feedbackLastAgeMs: Long? = null,
    val feedbackClockFailure: UsbExclusiveFeedbackClockFailure =
        UsbExclusiveFeedbackClockFailure.None,
    val feedbackInFlight: Int? = null,
    val feedbackTransferErrors: Long? = null,
    val feedbackPacketErrors: Long? = null,
    val packetLengthClampCount: Long? = null,
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
    val channel0OutputPeak: Float? = null,
    val channel1OutputPeak: Float? = null,
    val lastChannel0OutputPeak: Float? = null,
    val lastChannel1OutputPeak: Float? = null,
    val transportFailed: Boolean? = null,
    val deviceOnline: Boolean? = null,
    val running: Boolean? = null,
    val paused: Boolean? = null,
    val transportRunning: Boolean? = null,
    val feedbackReady: Boolean? = null,
    val realPcmReleased: Boolean? = null,
    val canAcceptPcm: Boolean? = null,
    val playbackReady: Boolean? = null,
    val feedbackReusable: Boolean? = null,
    val terminalFailure: Boolean? = null,
    val nativeStreamGeneration: Long? = null,
    val candidateId: String? = null,
    val recoveryEpoch: Long? = null,
    val recommendedAction: UsbExclusiveRecoveryAction = UsbExclusiveRecoveryAction.None,
    val actionId: Long? = null,
    val actionGeneration: Long? = null,
    val actionOwner: UsbExclusiveRecoveryActionOwner = UsbExclusiveRecoveryActionOwner.None,
    val actionLatched: Boolean? = null,
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
        get() {
            if (!reportValid) return false
            if (reportVersion >= 2) {
                return transportFailed != true &&
                    terminalFailure != true &&
                    errorCode == UsbExclusiveErrorCode.None &&
                    lastError == "none" &&
                    playbackReady == true
            }
            return transportFailed != true &&
                errorCode == UsbExclusiveErrorCode.None &&
                lastError == "none"
        }

    val isQueueFull: Boolean
        get() {
            pcmFreeBytes?.let { return it <= 0L && (pcmCapacityBytes ?: 0L) > 0L }
            val level = pcmLevelBytes ?: return false
            val capacity = pcmCapacityBytes ?: return false
            return capacity in 1..level
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

    val hasKotlinTerminalRecoveryAction: Boolean
        get() = actionOwner == UsbExclusiveRecoveryActionOwner.Kotlin &&
            recommendedAction.isKotlinTerminalAction

    val canReuseNativePlayerSession: Boolean
        get() {
            if (!reportValid) return false
            if (deviceOnline == false) return false
            if (transportFailed == true) return false
            if (terminalFailure == true) return false
            if (errorCode != UsbExclusiveErrorCode.None) return false
            if (lastError != "none") return false
            if (hasKotlinTerminalRecoveryAction) return false
            if (actionOwner == UsbExclusiveRecoveryActionOwner.Kotlin &&
                actionLatched == true
            ) {
                return false
            }
            if (reportVersion < 2) {
                return !isLegacyAsyncFeedbackReport
            }
            if (feedbackMode == UsbExclusiveFeedbackMode.Disabled) return true
            val reusableState = feedbackState == UsbExclusiveFeedbackState.Locked ||
                feedbackState == UsbExclusiveFeedbackState.Holdover
            return reusableState &&
                canAcceptPcm == true &&
                feedbackReusable == true
        }

    private val isLegacyAsyncFeedbackReport: Boolean
        get() = syncType.isAsynchronousUsbSyncType() ||
            feedback?.let { value ->
                !value.equals("none", ignoreCase = true) &&
                    !value.equals("disabled", ignoreCase = true)
            } == true
}

internal fun String.usbRuntimeMetrics(): UsbExclusiveRuntimeMetrics {
    val fields = runtimeReportFields()
    val explicitReportVersion = fields.valueAfter("reportVersion")
    val parsedReportVersion = explicitReportVersion?.toIntOrNull()
    val reportVersion = parsedReportVersion ?: 1
    var reportValid = true
    var reportInvalidReason: String? = null

    fun failClosed(reason: String) {
        if (reportValid) {
            reportValid = false
            reportInvalidReason = reason
        }
    }

    if (explicitReportVersion != null && parsedReportVersion == null) {
        failClosed("malformed_report_version")
    }
    if (parsedReportVersion != null && parsedReportVersion !in 1..2) {
        failClosed("unsupported_report_version")
    }
    if (reportVersion >= 2 && fields.duplicateKey != null) {
        failClosed("duplicate_key:${fields.duplicateKey}")
    }

    fun field(key: String): String? = fields.valueAfter(key) ?: valueAfter(key)

    fun intField(key: String, required: Boolean = false): Int? {
        val raw = field(key)
        if (raw == null) {
            if (required) failClosed("missing_$key")
            return null
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < 0) failClosed("invalid_$key")
        return parsed
    }

    fun longField(key: String, required: Boolean = false): Long? {
        val raw = field(key)
        if (raw == null) {
            if (required) failClosed("missing_$key")
            return null
        }
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed < 0L) failClosed("invalid_$key")
        return parsed
    }

    fun signedLongField(key: String, required: Boolean = false): Long? {
        val raw = field(key)
        if (raw == null) {
            if (required) failClosed("missing_$key")
            return null
        }
        val parsed = raw.toLongOrNull()
        if (parsed == null) failClosed("invalid_$key")
        return parsed
    }

    fun doubleField(key: String): Double? {
        val raw = field(key) ?: return null
        return raw.toDoubleOrNull().also { parsed ->
            if (parsed == null || !parsed.isFinite()) failClosed("invalid_$key")
        }
    }

    fun floatField(key: String, nonNegative: Boolean = false): Float? {
        val raw = field(key) ?: return null
        return raw.toFloatOrNull().also { parsed ->
            if (
                parsed == null ||
                !parsed.isFinite() ||
                (nonNegative && parsed < 0.0f)
            ) {
                failClosed("invalid_$key")
            }
        }
    }

    fun booleanV2Field(key: String, required: Boolean = false): Boolean? {
        val raw = field(key)
        if (raw == null) {
            if (required) failClosed("missing_$key")
            return null
        }
        val parsed = raw.toReportBooleanOrNull()
        if (parsed == null) failClosed("invalid_$key")
        return parsed
    }

    fun stringField(key: String, required: Boolean = false): String? {
        val raw = field(key)
        if (raw == null && required) failClosed("missing_$key")
        return raw
    }

    fun <T : Enum<T>> enumField(
        key: String,
        values: Array<T>,
        default: T,
        required: Boolean = false
    ): T {
        val raw = field(key)
        if (raw == null) {
            if (required) failClosed("missing_$key")
            return default
        }
        val parsed = values.firstOrNull { it.reportEnumEquals(raw) }
        if (parsed == null) failClosed("invalid_$key")
        return parsed ?: default
    }

    val feedbackMode = enumField(
        key = "feedbackMode",
        values = UsbExclusiveFeedbackMode.entries.toTypedArray(),
        default = UsbExclusiveFeedbackMode.Disabled,
        required = reportVersion >= 2
    )
    val asyncFeedbackReport = feedbackMode != UsbExclusiveFeedbackMode.Disabled ||
        field("syncType").isAsynchronousUsbSyncType()
    val feedbackState = enumField(
        key = "feedbackState",
        values = UsbExclusiveFeedbackState.entries.toTypedArray(),
        default = UsbExclusiveFeedbackState.Disabled,
        required = reportVersion >= 2 && asyncFeedbackReport
    )
    val recommendedAction = enumField(
        key = "recommendedAction",
        values = UsbExclusiveRecoveryAction.entries.toTypedArray(),
        default = UsbExclusiveRecoveryAction.None,
        required = reportVersion >= 2
    )
    val actionOwner = enumField(
        key = "actionOwner",
        values = UsbExclusiveRecoveryActionOwner.entries.toTypedArray(),
        default = UsbExclusiveRecoveryActionOwner.None,
        required = reportVersion >= 2
    )

    val feedbackEndpointAddress = stringField(
        "feedbackEndpoint",
        required = reportVersion >= 2 && asyncFeedbackReport
    )?.toEndpointAddressOrNull().also { endpoint ->
        if (reportVersion >= 2 && asyncFeedbackReport && endpoint == null) {
            failClosed("invalid_feedbackEndpoint")
        }
    }

    val transportRunning = booleanV2Field(
        "transportRunning",
        required = reportVersion >= 2
    )
    val feedbackReady = booleanV2Field(
        "feedbackReady",
        required = reportVersion >= 2
    )
    val realPcmReleased = booleanV2Field(
        "realPcmReleased",
        required = reportVersion >= 2
    )
    val canAcceptPcm = booleanV2Field(
        "canAcceptPcm",
        required = reportVersion >= 2
    )
    val playbackReady = booleanV2Field(
        "playbackReady",
        required = reportVersion >= 2
    )
    val feedbackReusable = booleanV2Field(
        "feedbackReusable",
        required = reportVersion >= 2
    )
    val terminalFailure = booleanV2Field(
        "terminalFailure",
        required = reportVersion >= 2
    )
    val actionLatched = booleanV2Field(
        "actionLatched",
        required = reportVersion >= 2
    )

    val feedbackPayloadBytes = intField("feedbackPayloadBytes")
    val feedbackExpectedPeriodUs = longField("feedbackExpectedPeriodUs")
    val feedbackRateQ32 = field("feedbackRateQ32")?.takeIf { raw ->
        raw.isUnsignedReportNumber().also { valid ->
            if (!valid && reportVersion >= 2) failClosed("invalid_feedbackRateQ32")
        }
    }
    val feedbackRateHz = doubleField("feedbackRateHz")
    val feedbackRatePpm = signedLongField("feedbackRatePpm")
    val feedbackValidSamples = longField("feedbackValidSamples")
    val feedbackInvalidSamples = longField("feedbackInvalidSamples")
    val feedbackOutliers = longField("feedbackOutliers")
    val feedbackTimeouts = longField("feedbackTimeouts")
    val feedbackLockCount = longField("feedbackLockCount")
    val feedbackRelockCount = longField("feedbackRelockCount")
    val feedbackHoldoverCount = longField("feedbackHoldoverCount")
    val feedbackHoldoverTotalMs = longField("feedbackHoldoverTotalMs")
    val feedbackLongGapReacquisitions = longField("feedbackLongGapReacquisitions")
    val feedbackLastAgeMs = longField("feedbackLastAgeMs")
    val feedbackClockFailure = field("feedbackClockFailure")?.let { raw ->
        raw.toUsbExclusiveFeedbackClockFailureOrNull().also { parsed ->
            if (parsed == null && reportVersion >= 2) {
                failClosed("invalid_feedbackClockFailure")
            }
        }
    } ?: UsbExclusiveFeedbackClockFailure.None
    val feedbackInFlight = intField("feedbackInFlight")
    val feedbackTransferErrors = longField("feedbackTransferErrors")
    val feedbackPacketErrors = longField("feedbackPacketErrors")
    val packetLengthClampCount = longField("packetLengthClampCount")
    val nativeStreamGeneration = longField(
        "nativeStreamGeneration",
        required = reportVersion >= 2
    )
    val candidateId = stringField("candidateId", required = reportVersion >= 2)
    val recoveryEpoch = longField("recoveryEpoch", required = reportVersion >= 2)
    val actionId = longField("actionId", required = reportVersion >= 2)
    val actionGeneration = longField("actionGeneration", required = reportVersion >= 2)
    val sampleRate = intField("sampleRate")
    val channelCount = intField("channels")
    val subslotBytes = intField("subslotBytes")
    val transferBytes = longField("transferBytes")
    val lastTransferBytes = longField("lastTransferBytes")
    val completedTransfers = longField("completedTransfers")
    val inFlightTransfers = intField("inFlight")
    val isoPacketErrors = longField("isoPacketErrors")
    val isoPacketErrorTransfers = longField("isoPacketErrorTransfers")
    val isoPacketErrorScore = intField("isoPacketErrorScore")
    val pcmFreeBytes = longField("pcmFreeBytes")
    val pcmMaxLevelBytes = longField("pcmMaxLevelBytes")
    val pcmBackpressureEvents = longField("pcmBackpressureEvents")
    val pcmBackpressureTotalMs = longField("pcmBackpressureTotalMs")
    val pcmBackpressureCurrentMs = longField("pcmBackpressureCurrentMs")
    val pcmBackpressureMaxMs = longField("pcmBackpressureMaxMs")
    val playerSignalFrames = longField("playerSignalFrames")
    val playerSilentFrames = longField("playerSilentFrames")
    val playerSignalBytes = longField("playerSignalBytes")
    val playerDroppedBytes = longField("playerDroppedBytes")
    val playerUnderrunBytes = longField("playerUnderrunBytes")
    val playerZeroFillBytes = longField("playerZeroFillBytes")
    val playerPausedZeroFillBytes = longField("playerPausedZeroFillBytes")
    val outputPeak = floatField("outputPeak", nonNegative = true)
    val lastOutputPeak = floatField("lastOutputPeak", nonNegative = true)
    val channel0OutputPeak = floatField("channel0OutputPeak", nonNegative = true)
    val channel1OutputPeak = floatField("channel1OutputPeak", nonNegative = true)
    val lastChannel0OutputPeak = floatField("lastChannel0OutputPeak", nonNegative = true)
    val lastChannel1OutputPeak = floatField("lastChannel1OutputPeak", nonNegative = true)
    val transportFailed = booleanV2Field("transportFailed")
    val deviceOnline = booleanV2Field("deviceOnline")
    val running = booleanV2Field("running")
    val paused = booleanV2Field("paused")
    val terminalErrorRaw = field("terminalError")
    if (reportVersion >= 2 &&
        terminalErrorRaw != null &&
        terminalErrorRaw.toUsbExclusiveErrorCodeOrNull() == null
    ) {
        failClosed("invalid_terminalError")
    }
    val explicitErrorRaw = field("errorCode")
    if (reportVersion >= 2 &&
        explicitErrorRaw != null &&
        explicitErrorRaw.toUsbExclusiveErrorCodeOrNull() == null
    ) {
        failClosed("invalid_errorCode")
    }
    val pcmLevel = field("pcmLevel")?.let { raw ->
        val separator = raw.indexOf('/')
        val hasSingleSeparator = separator > 0 && separator == raw.lastIndexOf('/')
        val level = if (hasSingleSeparator) raw.substring(0, separator).toLongOrNull() else null
        val capacity = if (hasSingleSeparator) {
            raw.substring(separator + 1).toLongOrNull()
        } else {
            null
        }
        if (
            level == null ||
            capacity == null ||
            level < 0L ||
            capacity < 0L ||
            level > capacity
        ) {
            if (reportVersion >= 2) failClosed("invalid_pcmLevel")
            null
        } else {
            level to capacity
        }
    }
    if (reportVersion >= 2) {
        val expectedPlaybackReady = transportRunning == true &&
            feedbackReady == true &&
            realPcmReleased == true &&
            canAcceptPcm == true &&
            terminalFailure == false
        if (playbackReady != expectedPlaybackReady) {
            failClosed("inconsistent_playbackReady")
        }
        if (terminalFailure == true && canAcceptPcm == true) {
            failClosed("terminal_accepts_pcm")
        }
        if (transportFailed == true && terminalFailure != true) {
            failClosed("transport_failure_not_terminal")
        }
        if (feedbackMode == UsbExclusiveFeedbackMode.Disabled) {
            if (feedbackState != UsbExclusiveFeedbackState.Disabled) {
                failClosed("disabled_feedback_state")
            }
            if (feedbackReady != true || feedbackReusable != true) {
                failClosed("disabled_feedback_not_ready")
            }
        }
        if (nativeStreamGeneration == 0L) {
            failClosed("invalid_nativeStreamGeneration")
        }
        if (actionGeneration != nativeStreamGeneration) {
            failClosed("action_generation_mismatch")
        }
        when (recommendedAction) {
            UsbExclusiveRecoveryAction.None -> {
                if (
                    actionId != 0L ||
                    actionOwner != UsbExclusiveRecoveryActionOwner.None ||
                    actionLatched != false ||
                    terminalFailure != false
                ) {
                    failClosed("invalid_none_action_state")
                }
            }
            UsbExclusiveRecoveryAction.FreshOpen,
            UsbExclusiveRecoveryAction.StopPreserveIntent -> {
                if (
                    actionId == null || actionId <= 0L ||
                    actionOwner != UsbExclusiveRecoveryActionOwner.Kotlin ||
                    actionLatched != true ||
                    terminalFailure != true
                ) {
                    failClosed("invalid_kotlin_action_state")
                }
            }
            else -> {
                if (
                    actionId == null || actionId <= 0L ||
                    actionOwner != UsbExclusiveRecoveryActionOwner.Native ||
                    actionLatched != false ||
                    terminalFailure != false
                ) {
                    failClosed("invalid_native_action_state")
                }
            }
        }
        if (pcmLevel != null && pcmFreeBytes != null) {
            val (level, capacity) = pcmLevel
            if (pcmFreeBytes != capacity - level) {
                failClosed("inconsistent_pcmFreeBytes")
            }
        }
        if (
            pcmLevel != null &&
            pcmMaxLevelBytes != null &&
            pcmMaxLevelBytes > pcmLevel.second
        ) {
            failClosed("invalid_pcmMaxLevelBytes")
        }
    }
    val baseErrorCode = usbExclusiveErrorCode()
    val errorCode = if (reportValid) {
        baseErrorCode
    } else {
        UsbExclusiveErrorCode.NativeInternalError
    }
    return UsbExclusiveRuntimeMetrics(
        reportVersion = reportVersion,
        reportValid = reportValid,
        reportInvalidReason = reportInvalidReason,
        source = field("source"),
        uacVersion = field("uacVersion"),
        syncType = field("syncType"),
        feedback = field("feedback"),
        feedbackMode = feedbackMode,
        feedbackEndpointAddress = feedbackEndpointAddress,
        feedbackState = feedbackState,
        feedbackPayloadBytes = feedbackPayloadBytes,
        feedbackExpectedPeriodUs = feedbackExpectedPeriodUs,
        feedbackRawValue = field("feedbackRawValue"),
        feedbackRateQ32 = feedbackRateQ32,
        feedbackRateHz = feedbackRateHz,
        feedbackRatePpm = feedbackRatePpm,
        feedbackValidSamples = feedbackValidSamples,
        feedbackInvalidSamples = feedbackInvalidSamples,
        feedbackOutliers = feedbackOutliers,
        feedbackTimeouts = feedbackTimeouts,
        feedbackLockCount = feedbackLockCount,
        feedbackRelockCount = feedbackRelockCount,
        feedbackHoldoverCount = feedbackHoldoverCount,
        feedbackHoldoverTotalMs = feedbackHoldoverTotalMs,
        feedbackLongGapReacquisitions = feedbackLongGapReacquisitions,
        feedbackLastAgeMs = feedbackLastAgeMs,
        feedbackClockFailure = feedbackClockFailure,
        feedbackInFlight = feedbackInFlight,
        feedbackTransferErrors = feedbackTransferErrors,
        feedbackPacketErrors = feedbackPacketErrors,
        packetLengthClampCount = packetLengthClampCount,
        sampleRate = sampleRate,
        channelCount = channelCount,
        subslotBytes = subslotBytes,
        transferBytes = transferBytes,
        lastTransferBytes = lastTransferBytes,
        completedTransfers = completedTransfers,
        inFlightTransfers = inFlightTransfers,
        isoPacketErrors = isoPacketErrors,
        isoPacketErrorTransfers = isoPacketErrorTransfers,
        isoPacketErrorScore = isoPacketErrorScore,
        pcmLevelBytes = pcmLevel?.first,
        pcmCapacityBytes = pcmLevel?.second,
        pcmFreeBytes = pcmFreeBytes,
        pcmMaxLevelBytes = pcmMaxLevelBytes,
        pcmBackpressureEvents = pcmBackpressureEvents,
        pcmBackpressureTotalMs = pcmBackpressureTotalMs,
        pcmBackpressureCurrentMs = pcmBackpressureCurrentMs,
        pcmBackpressureMaxMs = pcmBackpressureMaxMs,
        playerSignalFrames = playerSignalFrames,
        playerSilentFrames = playerSilentFrames,
        playerSignalBytes = playerSignalBytes,
        playerDroppedBytes = playerDroppedBytes,
        playerUnderrunBytes = playerUnderrunBytes,
        playerZeroFillBytes = playerZeroFillBytes,
        playerPausedZeroFillBytes = playerPausedZeroFillBytes,
        outputPeak = outputPeak,
        lastOutputPeak = lastOutputPeak,
        channel0OutputPeak = channel0OutputPeak,
        channel1OutputPeak = channel1OutputPeak,
        lastChannel0OutputPeak = lastChannel0OutputPeak,
        lastChannel1OutputPeak = lastChannel1OutputPeak,
        transportFailed = transportFailed,
        deviceOnline = deviceOnline,
        running = running,
        paused = paused,
        transportRunning = transportRunning,
        feedbackReady = feedbackReady,
        realPcmReleased = realPcmReleased,
        canAcceptPcm = canAcceptPcm,
        playbackReady = playbackReady,
        feedbackReusable = feedbackReusable,
        terminalFailure = terminalFailure,
        nativeStreamGeneration = nativeStreamGeneration,
        candidateId = candidateId,
        recoveryEpoch = recoveryEpoch,
        recommendedAction = recommendedAction,
        actionId = actionId,
        actionGeneration = actionGeneration,
        actionOwner = actionOwner,
        actionLatched = actionLatched,
        errorCode = errorCode,
        lastError = if (reportValid) field("lastError") ?: "none" else {
            "runtime_report_v2_invalid"
        }
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
    val regex = Regex("(?:^|\\s)${Regex.escape(key)}=(\\S+)")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

private data class RuntimeReportFields(
    val values: Map<String, String>,
    val duplicateKey: String?
) {
    fun valueAfter(key: String): String? = values[key]
}

private val runtimeReportFieldRegex = Regex("(?:^|\\s)([^\\s=]+)=(\\S+)")

private fun String.runtimeReportFields(): RuntimeReportFields {
    val values = linkedMapOf<String, String>()
    var duplicateKey: String? = null
    runtimeReportFieldRegex.findAll(this).forEach { match ->
        val key = match.groupValues[1]
        val value = match.groupValues[2]
        if (values.containsKey(key) && duplicateKey == null) {
            duplicateKey = key
        } else {
            values[key] = value
        }
    }
    return RuntimeReportFields(values = values, duplicateKey = duplicateKey)
}

private fun String.toReportBooleanOrNull(): Boolean? {
    return when (this) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

private fun Enum<*>.reportEnumEquals(raw: String): Boolean {
    return name.toReportEnumToken() == raw.toReportEnumToken()
}

private fun String.toReportEnumToken(): String {
    return filter { it.isLetterOrDigit() }.lowercase()
}

private fun String.toEndpointAddressOrNull(): Int? {
    val parsed = if (startsWith("0x", ignoreCase = true)) {
        substring(2).toIntOrNull(radix = 16)
    } else {
        toIntOrNull()
    }
    return parsed?.takeIf { it in 0x01..0xFF }
}

private fun String.toUsbExclusiveFeedbackClockFailureOrNull():
    UsbExclusiveFeedbackClockFailure? {
    return when (toReportEnumToken()) {
        "none" -> UsbExclusiveFeedbackClockFailure.None
        "acquiretimeout" -> UsbExclusiveFeedbackClockFailure.AcquireTimeout
        "holdovertimeout" -> UsbExclusiveFeedbackClockFailure.HoldoverTimeout
        "nonmonotonictime" -> UsbExclusiveFeedbackClockFailure.NonMonotonicTime
        else -> null
    }
}

private fun String.isUnsignedReportNumber(): Boolean {
    if (isBlank()) return false
    return all { it.isDigit() }
}

private fun String?.isAsynchronousUsbSyncType(): Boolean {
    return equals("async", ignoreCase = true) ||
        equals("asynchronous", ignoreCase = true)
}

private fun String.toUsbExclusiveErrorCodeOrNull(): UsbExclusiveErrorCode? {
    return UsbExclusiveErrorCode.entries.firstOrNull { it.reportEnumEquals(this) }
}

internal fun String.toUsbExclusiveRecoveryActionAckStatusOrNull(): UsbExclusiveRecoveryActionAckStatus? {
    return UsbExclusiveRecoveryActionAckStatus.entries
        .firstOrNull { it.reportEnumEquals(this) }
}

internal val UsbExclusiveRecoveryAction.isKotlinTerminalAction: Boolean
    get() = when (this) {
        UsbExclusiveRecoveryAction.FreshOpen,
        UsbExclusiveRecoveryAction.StopPreserveIntent -> true
        else -> false
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

    val fields = runtimeReportFields()
    val reportVersion = fields.valueAfter("reportVersion")
    if (reportVersion != null && reportVersion != "1" && reportVersion != "2") {
        return UsbExclusiveErrorCode.NativeInternalError
    }
    if (reportVersion == "2" && fields.duplicateKey != null) {
        return UsbExclusiveErrorCode.NativeInternalError
    }
    val terminalError = fields.valueAfter("terminalError")
    val explicitErrorCode = fields.valueAfter("errorCode")
    terminalError?.let { raw ->
        val typedCode = raw.toUsbExclusiveErrorCodeOrNull()
            ?: UsbExclusiveErrorCode.NativeInternalError
        if (typedCode != UsbExclusiveErrorCode.None) return typedCode
    }
    explicitErrorCode?.let { raw ->
        val typedCode = raw.toUsbExclusiveErrorCodeOrNull()
            ?: return UsbExclusiveErrorCode.NativeInternalError
        if (typedCode != UsbExclusiveErrorCode.None) return typedCode
    }

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
        errorProbe.contains("feedback_endpoint_invalid") ->
            UsbExclusiveErrorCode.FeedbackEndpointInvalid
        errorProbe.contains("feedback_initial_lock_timeout") ->
            UsbExclusiveErrorCode.FeedbackInitialLockTimeout
        errorProbe.contains("feedback_payload_invalid") ->
            UsbExclusiveErrorCode.FeedbackPayloadInvalid
        errorProbe.contains("feedback_transfer_failed") ->
            UsbExclusiveErrorCode.FeedbackTransferFailed
        errorProbe.contains("feedback_lost") ->
            UsbExclusiveErrorCode.FeedbackLost
        errorProbe.contains("feedback_packet_capacity_exceeded") ->
            UsbExclusiveErrorCode.FeedbackPacketCapacityExceeded
        errorProbe.contains("implicit_feedback_topology_unsupported") ->
            UsbExclusiveErrorCode.ImplicitFeedbackTopologyUnsupported
        errorProbe.contains("implicit_feedback_transfer_failed") ->
            UsbExclusiveErrorCode.ImplicitFeedbackTransferFailed
        errorProbe.contains("feedback_quirk_required") ->
            UsbExclusiveErrorCode.FeedbackQuirkRequired
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
        UsbExclusiveErrorCode.TransportFailed,
        UsbExclusiveErrorCode.FeedbackInitialLockTimeout,
        UsbExclusiveErrorCode.FeedbackTransferFailed,
        UsbExclusiveErrorCode.FeedbackLost,
        UsbExclusiveErrorCode.ImplicitFeedbackTransferFailed -> true
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
        UsbExclusiveErrorCode.FeedbackInitialLockTimeout,
        UsbExclusiveErrorCode.FeedbackTransferFailed,
        UsbExclusiveErrorCode.FeedbackLost,
        UsbExclusiveErrorCode.ImplicitFeedbackTransferFailed,
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
        UsbExclusiveErrorCode.FeedbackEndpointInvalid,
        UsbExclusiveErrorCode.FeedbackInitialLockTimeout,
        UsbExclusiveErrorCode.FeedbackPayloadInvalid,
        UsbExclusiveErrorCode.FeedbackTransferFailed,
        UsbExclusiveErrorCode.FeedbackLost,
        UsbExclusiveErrorCode.FeedbackPacketCapacityExceeded,
        UsbExclusiveErrorCode.ImplicitFeedbackTopologyUnsupported,
        UsbExclusiveErrorCode.ImplicitFeedbackTransferFailed,
        UsbExclusiveErrorCode.FeedbackQuirkRequired,
        UsbExclusiveErrorCode.TransferFirstCompletionTimeout,
        UsbExclusiveErrorCode.TransferCompletionStalled,
        UsbExclusiveErrorCode.IsoPacketErrorBurst,
        UsbExclusiveErrorCode.TransportFailed,
        UsbExclusiveErrorCode.CancelDrainTimeout,
        UsbExclusiveErrorCode.Quarantined,
        UsbExclusiveErrorCode.NativeInternalError -> true
        else -> false
    }
