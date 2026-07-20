#pragma once

#include "usb/feedback/usb_feedback_decoder.h"
#include "usb/feedback/usb_feedback_ahead_window.h"
#include "usb/feedback/usb_feedback_estimator.h"
#include "usb/feedback/usb_feedback_stream_gate.h"
#include "usb/feedback/usb_feedback_transfer_lifecycle.h"

#include <cstddef>
#include <cstdint>

namespace neri::usb::feedback {

enum class ExplicitFeedbackEngineState {
    Disabled,
    Ready,
    Starting,
    Acquiring,
    Streaming,
    Draining,
    Quarantined,
    Stopped,
    Failed
};

enum class ExplicitFeedbackEngineResult {
    Ok,
    FeatureDisabled,
    InvalidArgument,
    InvalidState,
    Draining,
    AwaitingFeedback,
    RejectedFeedback,
    StaleGeneration,
    BackendFailure,
    TerminalFailure
};

enum class ExplicitFeedbackFailureReason {
    None,
    FeatureDisabled,
    InvalidConfiguration,
    ClaimInterfaces,
    ActivateAlternateSetting,
    AllocateFeedbackTransfers,
    AllocateAudioTransfers,
    StartEventLoop,
    SubmitFeedbackTransfers,
    SubmitZeroBootstrap,
    FeedbackTransfer,
    AudioTransfer,
    FeedbackClock,
    FeedbackDecode,
    DeviceDetached,
    DrainTimeout,
    Cleanup,
    InternalInvariant
};

struct ExplicitFeedbackEngineConfig {
    bool enabled = false;
    uint64_t generation = 0;
    uint32_t feedbackTransferCount = 0;
    uint32_t audioTransferCount = 0;
    uint32_t maxAheadIntervals = 0;
    FeedbackDecodeProfile decodeProfile;
    FeedbackEstimatorConfig estimatorConfig;
    FeedbackClockConfig clockConfig;
    FeedbackRateQ32 nominalRateQ32 = 0;
    uint32_t frameBytes = 0;
    uint32_t endpointCapacityBytes = 0;
    uint32_t bootstrapPacketLimit = 0;
    bool zeroLengthFeedbackPermitted = true;
};

struct ExplicitFeedbackEngineResources {
    bool interfacesClaimed = false;
    bool alternateSettingActive = false;
    bool feedbackTransfersAllocated = false;
    bool audioTransfersAllocated = false;
    bool eventLoopStarted = false;

    [[nodiscard]] bool empty() const;
};

struct ExplicitFeedbackEngineSnapshot {
    ExplicitFeedbackEngineState state = ExplicitFeedbackEngineState::Disabled;
    ExplicitFeedbackFailureReason failure = ExplicitFeedbackFailureReason::None;
    uint64_t generation = 0;
    bool configured = false;
    bool playerPcmAvailable = false;
    bool cancellationRequested = false;
    uint64_t feedbackPacketsValid = 0;
    uint64_t feedbackPacketsInvalid = 0;
    uint64_t feedbackPacketsZeroLength = 0;
    uint64_t audioPacketsSubmitted = 0;
    uint64_t zeroPacketsSubmitted = 0;
    uint64_t playerPacketsSubmitted = 0;
    uint64_t cleanupErrors = 0;
    TransferSetLifecycle audioOut;
    TransferSetLifecycle feedbackIn;
    FeedbackEstimatorSnapshot estimator;
    FeedbackStreamGateSnapshot gate;
    ExplicitFeedbackEngineResources resources;
};

class ExplicitFeedbackBackend {
public:
    virtual ~ExplicitFeedbackBackend() = default;

    virtual bool claimInterfaces() = 0;
    virtual bool activateAlternateSetting() = 0;
    virtual bool allocateTransfers(TransferKind kind, uint32_t count) = 0;
    virtual bool startEventLoop() = 0;
    virtual bool submitFeedbackTransfers(uint32_t count) = 0;
    virtual bool submitAudioTransfer(
        const FeedbackPacketPlan& packet,
        bool allZero
    ) = 0;
    // NotFound is valid only when no transfer in this set can still callback
    virtual CancelResult cancelTransfers(TransferKind kind) = 0;
    virtual bool stopEventLoop() = 0;
    virtual bool freeTransfers(TransferKind kind) = 0;
    virtual bool deactivateAlternateSetting() = 0;
    virtual bool releaseInterfaces() = 0;
};

class ExplicitFeedbackEngine {
public:
    // The owner serializes API calls and backend callbacks on the same lock.
    ExplicitFeedbackEngineResult configure(
        const ExplicitFeedbackEngineConfig& config,
        ExplicitFeedbackBackend* backend
    );

    ExplicitFeedbackEngineResult start(int64_t startedAtNs);

    ExplicitFeedbackEngineResult onFeedbackCompletion(
        uint64_t callbackGeneration,
        TransferCompletionStatus status,
        const uint8_t* payload,
        size_t payloadBytes,
        int64_t receivedAtNs,
        uint64_t sequence,
        bool playerPcmAvailable
    );

    ExplicitFeedbackEngineResult onAudioCompletion(
        uint64_t callbackGeneration,
        TransferCompletionStatus status,
        bool playerPcmAvailable
    );

    ExplicitFeedbackEngineResult tick(int64_t nowNs, bool playerPcmAvailable);
    ExplicitFeedbackEngineResult stop();
    ExplicitFeedbackEngineResult onDrainTimeout();
    ExplicitFeedbackEngineResult retryCleanup();

    [[nodiscard]] ExplicitFeedbackEngineSnapshot snapshot() const;

private:
    bool configurationValid(const ExplicitFeedbackEngineConfig& config) const;
    ExplicitFeedbackEngineResult failStart(ExplicitFeedbackFailureReason reason);
    ExplicitFeedbackEngineResult enterTerminal(ExplicitFeedbackFailureReason reason);
    ExplicitFeedbackEngineResult beginDrain();
    ExplicitFeedbackEngineResult releaseDrainedResources();
    ExplicitFeedbackEngineResult submitFeedback(uint32_t count);
    ExplicitFeedbackEngineResult submitNextAudio();
    ExplicitFeedbackEngineResult fillAudioAheadWindow();
    ExplicitFeedbackEngineResult settleWhileDraining();
    void requestCancellation();
    void updateStreamingState();
    void increment(uint64_t* value);

    ExplicitFeedbackEngineConfig config_;
    ExplicitFeedbackBackend* backend_ = nullptr;
    StreamGenerationLifecycle lifecycle_ { 0 };
    FeedbackEstimator estimator_;
    FeedbackStreamGate gate_;
    ExplicitFeedbackEngineResources resources_;
    ExplicitFeedbackEngineState state_ = ExplicitFeedbackEngineState::Disabled;
    ExplicitFeedbackFailureReason failure_ = ExplicitFeedbackFailureReason::None;
    bool configured_ = false;
    bool playerPcmAvailable_ = false;
    bool cancellationRequested_ = false;
    uint64_t feedbackPacketsValid_ = 0;
    uint64_t feedbackPacketsInvalid_ = 0;
    uint64_t feedbackPacketsZeroLength_ = 0;
    uint64_t audioPacketsSubmitted_ = 0;
    uint64_t zeroPacketsSubmitted_ = 0;
    uint64_t playerPacketsSubmitted_ = 0;
    uint64_t cleanupErrors_ = 0;
};

const char* explicitFeedbackEngineStateName(ExplicitFeedbackEngineState state);
const char* explicitFeedbackEngineResultName(ExplicitFeedbackEngineResult result);
const char* explicitFeedbackFailureReasonName(ExplicitFeedbackFailureReason reason);

} // namespace neri::usb::feedback
