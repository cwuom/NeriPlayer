#include "usb/feedback/usb_feedback_stream_gate.h"

namespace neri::usb::feedback {

bool FeedbackStreamGate::configure(
    const FeedbackClockConfig& clockConfig,
    FeedbackRateQ32 nominalRateQ32,
    uint32_t frameBytes,
    uint32_t endpointCapacityBytes,
    uint32_t bootstrapPacketLimit
) {
    configured_ = false;
    running_ = false;
    terminalFailure_ = false;
    realPcmReleased_ = false;
    bootstrapPacketsSubmitted_ = 0;
    bootstrapPacketLimit_ = 0;
    if (bootstrapPacketLimit == 0 ||
        !clock_.configure(clockConfig, nominalRateQ32) ||
        scheduler_.configure(
            nominalRateQ32,
            frameBytes,
            endpointCapacityBytes
        ) != FeedbackMathStatus::Ok) {
        clock_.disable();
        return false;
    }
    bootstrapPacketLimit_ = bootstrapPacketLimit;
    configured_ = true;
    return true;
}

FeedbackMathStatus FeedbackStreamGate::start(int64_t startedAtNs) {
    if (!configured_ || running_ || terminalFailure_) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = clock_.start(startedAtNs);
    if (status != FeedbackMathStatus::Ok) {
        return status;
    }
    running_ = true;
    realPcmReleased_ = false;
    bootstrapPacketsSubmitted_ = 0;
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus FeedbackStreamGate::updateTrustedSchedulerRate() {
    const FeedbackClockSnapshot clock = clock_.snapshot();
    if (!clock.hasTrustedRate || clock.trustedRateQ32 == 0) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = scheduler_.updateRate(clock.trustedRateQ32);
    if (status != FeedbackMathStatus::Ok) {
        terminalFailure_ = true;
    }
    return status;
}

void FeedbackStreamGate::markTerminalIfClockFailed() {
    if (clock_.snapshot().state == FeedbackClockState::Failed) {
        terminalFailure_ = true;
    }
}

FeedbackMathStatus FeedbackStreamGate::onEstimate(
    const FeedbackEstimateResult& estimate,
    int64_t receivedAtNs
) {
    if (!running_ || terminalFailure_) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = clock_.onEstimate(estimate, receivedAtNs);
    markTerminalIfClockFailed();
    if (status != FeedbackMathStatus::Ok || terminalFailure_) {
        return status;
    }
    if (clock_.snapshot().state == FeedbackClockState::Locked) {
        return updateTrustedSchedulerRate();
    }
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus FeedbackStreamGate::onRejectedSample(int64_t receivedAtNs) {
    if (!running_ || terminalFailure_) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = clock_.onRejectedSample(receivedAtNs);
    markTerminalIfClockFailed();
    return status;
}

FeedbackMathStatus FeedbackStreamGate::onTick(int64_t nowNs) {
    if (!running_ || terminalFailure_) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = clock_.onTick(nowNs);
    markTerminalIfClockFailed();
    return status;
}

StreamGatePacket FeedbackStreamGate::next(bool playerPcmAvailable) {
    StreamGatePacket result;
    result.clockState = clock_.snapshot().state;
    result.realPcmReleased = realPcmReleased_;
    if (!running_) {
        result.status = StreamGatePacketStatus::NotRunning;
        return result;
    }
    if (terminalFailure_ || result.clockState == FeedbackClockState::Failed) {
        result.status = StreamGatePacketStatus::TerminalFailure;
        result.allZero = true;
        return result;
    }
    const bool shortHoldover = realPcmReleased_ &&
        (result.clockState == FeedbackClockState::Holdover ||
         result.clockState == FeedbackClockState::Relocking);
    if (result.clockState != FeedbackClockState::Locked && !shortHoldover) {
        if (bootstrapPacketsSubmitted_ >= bootstrapPacketLimit_) {
            result.status = StreamGatePacketStatus::PlayerBlockedUntilLock;
            result.packet.status = FeedbackMathStatus::NotReady;
            return result;
        }
        result.packet = scheduler_.next();
        if (result.packet.status != FeedbackMathStatus::Ok) {
            terminalFailure_ = true;
            result.status = StreamGatePacketStatus::TerminalFailure;
            return result;
        }
        ++bootstrapPacketsSubmitted_;
        result.status = StreamGatePacketStatus::ZeroBootstrap;
        result.allZero = true;
        result.realPcmReleased = false;
        return result;
    }

    if (!realPcmReleased_) {
        realPcmReleased_ = true;
        result.realPcmReleased = true;
    }
    if (shortHoldover && updateTrustedSchedulerRate() != FeedbackMathStatus::Ok) {
        result.status = StreamGatePacketStatus::TerminalFailure;
        result.allZero = true;
        return result;
    }
    result.packet = scheduler_.next();
    if (result.packet.status != FeedbackMathStatus::Ok) {
        terminalFailure_ = true;
        result.status = StreamGatePacketStatus::TerminalFailure;
        result.allZero = true;
        return result;
    }
    result.status = playerPcmAvailable
        ? StreamGatePacketStatus::PlayerPacket
        : StreamGatePacketStatus::ZeroBootstrap;
    result.allZero = !playerPcmAvailable;
    return result;
}

void FeedbackStreamGate::stop() {
    configured_ = false;
    running_ = false;
    terminalFailure_ = false;
    realPcmReleased_ = false;
    bootstrapPacketsSubmitted_ = 0;
    bootstrapPacketLimit_ = 0;
    clock_.disable();
}

FeedbackStreamGateSnapshot FeedbackStreamGate::snapshot() const {
    return FeedbackStreamGateSnapshot {
        configured_,
        running_,
        terminalFailure_,
        realPcmReleased_,
        bootstrapPacketsSubmitted_,
        bootstrapPacketLimit_,
        clock_.snapshot(),
        scheduler_.snapshot()
    };
}

const char* streamGatePacketStatusName(StreamGatePacketStatus status) {
    switch (status) {
        case StreamGatePacketStatus::NotRunning:
            return "not_running";
        case StreamGatePacketStatus::ZeroBootstrap:
            return "zero_bootstrap";
        case StreamGatePacketStatus::PlayerBlockedUntilLock:
            return "player_blocked_until_lock";
        case StreamGatePacketStatus::PlayerPacket:
            return "player_packet";
        case StreamGatePacketStatus::TerminalFailure:
            return "terminal_failure";
        case StreamGatePacketStatus::Invalid:
            return "invalid";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
