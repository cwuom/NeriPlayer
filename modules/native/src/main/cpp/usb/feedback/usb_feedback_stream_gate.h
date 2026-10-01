#pragma once

#include "usb/feedback/usb_feedback_clock.h"
#include "usb/feedback/usb_feedback_packet_scheduler.h"

#include <cstdint>

namespace neri::usb::feedback {

enum class StreamGatePacketStatus {
    NotRunning,
    ZeroBootstrap,
    PlayerBlockedUntilLock,
    PlayerPacket,
    TerminalFailure,
    Invalid
};

struct StreamGatePacket {
    StreamGatePacketStatus status = StreamGatePacketStatus::Invalid;
    FeedbackPacketPlan packet;
    bool allZero = true;
    bool realPcmReleased = false;
    FeedbackClockState clockState = FeedbackClockState::Disabled;
};

struct FeedbackStreamGateSnapshot {
    bool configured = false;
    bool running = false;
    bool terminalFailure = false;
    bool realPcmReleased = false;
    uint32_t bootstrapPacketsSubmitted = 0;
    uint32_t bootstrapPacketLimit = 0;
    FeedbackClockSnapshot clock;
    FeedbackPacketSchedulerSnapshot scheduler;
};

class FeedbackStreamGate {
public:
    bool configure(
        const FeedbackClockConfig& clockConfig,
        FeedbackRateQ32 nominalRateQ32,
        uint32_t frameBytes,
        uint32_t endpointCapacityBytes,
        uint32_t bootstrapPacketLimit
    );

    FeedbackMathStatus start(int64_t startedAtNs);

    FeedbackMathStatus onEstimate(
        const FeedbackEstimateResult& estimate,
        int64_t receivedAtNs
    );

    FeedbackMathStatus onRejectedSample(int64_t receivedAtNs);
    FeedbackMathStatus onTick(int64_t nowNs);

    StreamGatePacket next(bool playerPcmAvailable);
    void stop();

    [[nodiscard]] FeedbackStreamGateSnapshot snapshot() const;

private:
    FeedbackMathStatus updateTrustedSchedulerRate();
    void markTerminalIfClockFailed();

    FeedbackClock clock_;
    FeedbackPacketScheduler scheduler_;
    bool configured_ = false;
    bool running_ = false;
    bool terminalFailure_ = false;
    bool realPcmReleased_ = false;
    uint32_t bootstrapPacketsSubmitted_ = 0;
    uint32_t bootstrapPacketLimit_ = 0;
};

const char* streamGatePacketStatusName(StreamGatePacketStatus status);

} // namespace neri::usb::feedback
