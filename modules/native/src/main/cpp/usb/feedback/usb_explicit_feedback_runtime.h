#pragma once

#include "usb/feedback/usb_feedback_decoder.h"
#include "usb/feedback/usb_feedback_in_transfer_set.h"
#include "usb/feedback/usb_feedback_stream_gate.h"

#include <cstdint>
#include <mutex>

namespace neri::usb::feedback {

enum class ExplicitFeedbackRuntimeState {
    Disabled,
    Ready,
    Acquiring,
    Streaming,
    Stopped,
    Failed
};

enum class ExplicitFeedbackRuntimeFailure {
    None,
    InvalidConfiguration,
    TransferCancelled,
    TransferFailed,
    DeviceDetached,
    FeedbackClock,
    PacketCapacity,
    InternalInvariant
};

struct ExplicitFeedbackRuntimeConfig {
    uint64_t generation = 0;
    FeedbackDecodeProfile decodeProfile;
    FeedbackRateQ32 nominalRateQ32 = 0;
    int64_t expectedReportPeriodNs = 0;
    uint32_t frameBytes = 0;
    uint32_t endpointCapacityBytes = 0;
    uint32_t bootstrapPacketLimit = 0;
    bool zeroLengthFeedbackPermitted = true;
};

struct ExplicitFeedbackRuntimeSnapshot {
    ExplicitFeedbackRuntimeState state = ExplicitFeedbackRuntimeState::Disabled;
    ExplicitFeedbackRuntimeFailure failure = ExplicitFeedbackRuntimeFailure::None;
    uint64_t generation = 0;
    bool configured = false;
    bool running = false;
    bool feedbackReady = false;
    bool realPcmReleased = false;
    bool reusableAfterStop = false;
    bool terminalFailure = false;
    uint64_t validPackets = 0;
    uint64_t invalidPackets = 0;
    uint64_t zeroLengthPackets = 0;
    uint64_t transferErrors = 0;
    uint64_t staleCallbacks = 0;
    uint32_t consecutiveTransferErrors = 0;
    uint64_t longGapReacquisitions = 0;
    uint64_t lastRawValue = 0;
    uint32_t lastPayloadBytes = 0;
    FeedbackEstimatorSnapshot estimator;
    FeedbackStreamGateSnapshot gate;
};

class ExplicitFeedbackRuntime final : public FeedbackInCompletionConsumer {
public:
    bool configure(const ExplicitFeedbackRuntimeConfig& config);
    bool start(int64_t startedAtNs);
    bool tick(int64_t nowNs);
    StreamGatePacket nextPacket(bool playerPcmAvailable);
    void stop();

    bool onFeedbackInCompletion(
        const FeedbackInCompletion& completion
    ) override;

    [[nodiscard]] ExplicitFeedbackRuntimeSnapshot snapshot() const;

private:
    bool failLocked(ExplicitFeedbackRuntimeFailure failure);
    bool shouldReacquireAfterLongGapLocked(int64_t receivedAtNs) const;
    bool restartAcquisitionLocked(int64_t startedAtNs);
    void updateStateLocked();
    bool handleRejectedSampleLocked(int64_t receivedAtNs);
    static void incrementSaturated(uint64_t* value);

    mutable std::mutex mutex_;
    ExplicitFeedbackRuntimeConfig config_;
    FeedbackEstimator estimator_;
    FeedbackStreamGate gate_;
    ExplicitFeedbackRuntimeState state_ = ExplicitFeedbackRuntimeState::Disabled;
    ExplicitFeedbackRuntimeFailure failure_ = ExplicitFeedbackRuntimeFailure::None;
    bool configured_ = false;
    bool running_ = false;
    bool reusableAfterStop_ = false;
    uint64_t validPackets_ = 0;
    uint64_t invalidPackets_ = 0;
    uint64_t zeroLengthPackets_ = 0;
    uint64_t transferErrors_ = 0;
    uint64_t staleCallbacks_ = 0;
    uint32_t consecutiveTransferErrors_ = 0;
    uint64_t longGapReacquisitions_ = 0;
    uint64_t lastRawValue_ = 0;
    uint32_t lastPayloadBytes_ = 0;
    bool hasTerminalGateSnapshot_ = false;
    FeedbackStreamGateSnapshot terminalGateSnapshot_;
};

const char* explicitFeedbackRuntimeStateName(ExplicitFeedbackRuntimeState state);
const char* explicitFeedbackRuntimeFailureName(ExplicitFeedbackRuntimeFailure failure);

} // namespace neri::usb::feedback
