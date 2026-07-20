#include "usb/feedback/usb_feedback_stream_gate.h"

#include "usb/feedback/usb_feedback_rate_math.h"

#include <cassert>
#include <cstdint>
#include <string>

namespace {

using neri::usb::feedback::FeedbackClockConfig;
using neri::usb::feedback::FeedbackClockState;
using neri::usb::feedback::FeedbackEstimateResult;
using neri::usb::feedback::FeedbackEstimateStatus;
using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackStreamGate;
using neri::usb::feedback::StreamGatePacketStatus;

constexpr uint64_t kNominalRate = UINT64_C(48000) * (UINT64_C(1) << 32) /
    UINT64_C(1000);

FeedbackClockConfig clockConfig() {
    return FeedbackClockConfig {
        1'000'000,
        5,
        2,
        5,
        2,
        100'000,
        2'000
    };
}

FeedbackEstimateResult stableEstimate(uint64_t rate = kNominalRate) {
    return FeedbackEstimateResult {
        FeedbackEstimateStatus::Accepted,
        true,
        true,
        true,
        rate,
        0
    };
}

FeedbackStreamGate configuredGate(uint32_t bootstrapLimit = 4) {
    FeedbackStreamGate gate;
    assert(gate.configure(
        clockConfig(),
        kNominalRate,
        8,
        512,
        bootstrapLimit
    ));
    return gate;
}

void blocksRealPcmUntilFirstLock() {
    FeedbackStreamGate gate = configuredGate();
    assert(gate.start(0) == FeedbackMathStatus::Ok);

    auto packet = gate.next(true);
    assert(packet.status == StreamGatePacketStatus::ZeroBootstrap);
    assert(packet.packet.status == FeedbackMathStatus::Ok);
    assert(packet.allZero);
    assert(!packet.realPcmReleased);

    packet = gate.next(false);
    assert(packet.status == StreamGatePacketStatus::ZeroBootstrap);
    assert(packet.packet.status == FeedbackMathStatus::Ok);
    assert(packet.allZero);
    assert(packet.packet.bytes > 0U);
    assert(gate.snapshot().bootstrapPacketsSubmitted == 2U);
}

void releasesPlayerOnlyAfterTrustedFeedback() {
    FeedbackStreamGate gate = configuredGate();
    assert(gate.start(0) == FeedbackMathStatus::Ok);
    assert(gate.onEstimate(stableEstimate(), 1'000'000) == FeedbackMathStatus::Ok);

    auto packet = gate.next(true);
    assert(packet.status == StreamGatePacketStatus::PlayerPacket);
    assert(!packet.allZero);
    assert(packet.realPcmReleased);
    assert(packet.clockState == FeedbackClockState::Locked);
    assert(gate.snapshot().realPcmReleased);
}

void shortHoldoverUsesLastTrustedRateButFailureStops() {
    FeedbackStreamGate gate = configuredGate();
    assert(gate.start(0) == FeedbackMathStatus::Ok);
    assert(gate.onEstimate(stableEstimate(), 1'000'000) == FeedbackMathStatus::Ok);
    assert(gate.next(true).status == StreamGatePacketStatus::PlayerPacket);

    assert(gate.onTick(3'000'000) == FeedbackMathStatus::Ok);
    auto packet = gate.next(true);
    assert(packet.clockState == FeedbackClockState::Holdover);
    assert(packet.status == StreamGatePacketStatus::PlayerPacket);
    assert(!packet.allZero);

    assert(gate.onTick(8'000'000) == FeedbackMathStatus::NotReady);
    packet = gate.next(true);
    assert(packet.status == StreamGatePacketStatus::TerminalFailure);
    assert(gate.snapshot().terminalFailure);
}

void bootstrapLimitAndStopAreObservable() {
    FeedbackStreamGate gate = configuredGate(1);
    assert(gate.start(0) == FeedbackMathStatus::Ok);
    assert(gate.next(false).status == StreamGatePacketStatus::ZeroBootstrap);
    assert(gate.next(false).status == StreamGatePacketStatus::PlayerBlockedUntilLock);
    gate.stop();
    assert(gate.next(false).status == StreamGatePacketStatus::NotRunning);
    assert(gate.start(0) == FeedbackMathStatus::NotReady);
    assert(!gate.snapshot().configured);
    assert(gate.snapshot().clock.state == FeedbackClockState::Disabled);
}

void rejectsInvalidConfigurationAndTimeout() {
    FeedbackStreamGate invalid;
    assert(!invalid.configure(clockConfig(), kNominalRate, 8, 512, 0));
    assert(invalid.start(0) == FeedbackMathStatus::NotReady);

    FeedbackStreamGate gate = configuredGate();
    assert(gate.start(0) == FeedbackMathStatus::Ok);
    assert(gate.onTick(5'000'000) == FeedbackMathStatus::NotReady);
    const auto packet = gate.next(true);
    assert(packet.status == StreamGatePacketStatus::TerminalFailure);
    assert(gate.snapshot().clock.failureReason ==
        neri::usb::feedback::FeedbackClockFailureReason::AcquireTimeout);
}

void exposesStableName() {
    assert(std::string(neri::usb::feedback::streamGatePacketStatusName(
        StreamGatePacketStatus::PlayerBlockedUntilLock
    )) == "player_blocked_until_lock");
}

} // namespace

int main() {
    blocksRealPcmUntilFirstLock();
    releasesPlayerOnlyAfterTrustedFeedback();
    shortHoldoverUsesLastTrustedRateButFailureStops();
    bootstrapLimitAndStopAreObservable();
    rejectsInvalidConfigurationAndTimeout();
    exposesStableName();
    return 0;
}
