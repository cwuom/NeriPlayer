package moe.ouom.neriplayer.data.model.playback.usb

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
)
