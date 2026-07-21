#include "usb/feedback/usb_explicit_feedback_runtime.h"

#include <array>
#include <cassert>
#include <cstdint>

namespace {

constexpr int64_t kExpectedReportPeriodNs = 500'000;
constexpr int64_t kSoftMissNs = 32'000'000;

using neri::usb::feedback::ExplicitFeedbackRuntime;
using neri::usb::feedback::ExplicitFeedbackRuntimeConfig;
using neri::usb::feedback::ExplicitFeedbackRuntimeFailure;
using neri::usb::feedback::ExplicitFeedbackRuntimeState;
using neri::usb::feedback::FeedbackInCompletion;
using neri::usb::feedback::FeedbackInCompletionStatus;
using neri::usb::feedback::FeedbackClockFailureReason;
using neri::usb::feedback::FeedbackClockState;
using neri::usb::feedback::FeedbackRawUnit;
using neri::usb::feedback::StreamGatePacketStatus;

constexpr uint64_t q32(uint64_t value) {
    return value << 32U;
}

void configureRuntime(
    ExplicitFeedbackRuntime* runtime,
    uint64_t generation = 7
) {
    assert(runtime != nullptr);
    const bool configured = runtime->configure(ExplicitFeedbackRuntimeConfig {
        generation,
        {
            4,
            16,
            FeedbackRawUnit::FramesPerMicroframe,
            8000,
            8000,
            1,
            1,
            UINT64_C(0xF0000000)
        },
        q32(12),
        kExpectedReportPeriodNs,
        8,
        128,
        1024,
        true
    });
    assert(configured);
}

FeedbackInCompletion completion(
    uint64_t generation,
    uint64_t sequence,
    int64_t receivedAtNs,
    const uint8_t* payload,
    size_t payloadBytes,
    FeedbackInCompletionStatus status = FeedbackInCompletionStatus::Completed
) {
    return FeedbackInCompletion {
        generation,
        0,
        status,
        payload,
        payloadBytes,
        receivedAtNs,
        sequence
    };
}

void blocksPlayerUntilFeedbackLocks() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));

    const auto bootstrap = runtime.nextPacket(true);
    assert(bootstrap.status == StreamGatePacketStatus::ZeroBootstrap);
    assert(bootstrap.allZero);

    constexpr std::array<uint8_t, 4> twelveFrames { 0x00, 0x00, 0x0C, 0x00 };
    assert(runtime.onFeedbackInCompletion(completion(
        7,
        1,
        500'000,
        twelveFrames.data(),
        twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7,
        2,
        1'000'000,
        twelveFrames.data(),
        twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7,
        3,
        1'500'000,
        twelveFrames.data(),
        twelveFrames.size()
    )));

    const auto player = runtime.nextPacket(true);
    assert(player.status == StreamGatePacketStatus::PlayerPacket);
    assert(!player.allZero);
    assert(player.packet.frames == 12U);
    const auto snapshot = runtime.snapshot();
    assert(snapshot.feedbackReady);
    assert(snapshot.state == ExplicitFeedbackRuntimeState::Streaming);
    assert(snapshot.validPackets == 3U);
    assert(snapshot.lastRawValue == UINT64_C(0x000C0000));
    assert(snapshot.lastPayloadBytes == 4U);

    runtime.stop();
    const auto stopped = runtime.snapshot();
    assert(stopped.state == ExplicitFeedbackRuntimeState::Stopped);
    assert(stopped.reusableAfterStop);
    runtime.stop();
    assert(runtime.snapshot().reusableAfterStop);
}

void stoppingBeforeFeedbackLockIsNotReusable() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));
    runtime.stop();
    const auto snapshot = runtime.snapshot();
    assert(snapshot.state == ExplicitFeedbackRuntimeState::Stopped);
    assert(!snapshot.reusableAfterStop);
}

void toleratesShortErrorsThenFailsClosed() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));
    for (uint64_t index = 0; index < 7; ++index) {
        assert(runtime.onFeedbackInCompletion(completion(
            7,
            index + 1,
            static_cast<int64_t>((index + 1) * 100'000),
            nullptr,
            0,
            FeedbackInCompletionStatus::PacketError
        )));
    }
    assert(!runtime.onFeedbackInCompletion(completion(
        7,
        8,
        800'000,
        nullptr,
        0,
        FeedbackInCompletionStatus::PacketError
    )));
    const auto snapshot = runtime.snapshot();
    assert(snapshot.terminalFailure);
    assert(snapshot.failure == ExplicitFeedbackRuntimeFailure::TransferFailed);
}

void ignoresStaleGenerationWithoutPoisoningCurrentStream() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime, 12);
    assert(runtime.start(0));
    assert(!runtime.onFeedbackInCompletion(completion(
        11,
        1,
        100'000,
        nullptr,
        0,
        FeedbackInCompletionStatus::StaleGeneration
    )));
    const auto snapshot = runtime.snapshot();
    assert(!snapshot.terminalFailure);
    assert(snapshot.staleCallbacks == 1U);
    assert(snapshot.state == ExplicitFeedbackRuntimeState::Acquiring);
}

void acquireTimeoutFailsClosed() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));
    assert(runtime.tick(249'999'999));
    assert(!runtime.tick(250'000'000));
    const auto snapshot = runtime.snapshot();
    assert(snapshot.terminalFailure);
    assert(snapshot.failure == ExplicitFeedbackRuntimeFailure::FeedbackClock);
    assert(snapshot.gate.clock.state == FeedbackClockState::Failed);
    assert(snapshot.gate.clock.failureReason == FeedbackClockFailureReason::AcquireTimeout);
}

void ignoresShortAndroidCallbackJitterWhileLocked() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));

    constexpr std::array<uint8_t, 4> twelveFrames { 0x00, 0x00, 0x0C, 0x00 };
    assert(runtime.onFeedbackInCompletion(completion(
        7, 1, 500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 2, 1'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 3, 1'500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.snapshot().gate.clock.state == FeedbackClockState::Locked);

    assert(runtime.tick(33'499'999));
    assert(runtime.snapshot().gate.clock.state == FeedbackClockState::Locked);
    assert(runtime.tick(33'500'000));
    assert(runtime.snapshot().gate.clock.state == FeedbackClockState::Holdover);
}

void toleratesAndroidSchedulingGapAndRelocks() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));

    constexpr std::array<uint8_t, 4> twelveFrames { 0x00, 0x00, 0x0C, 0x00 };
    assert(runtime.onFeedbackInCompletion(completion(
        7, 1, 500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 2, 1'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 3, 1'500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.nextPacket(true).status == StreamGatePacketStatus::PlayerPacket);

    assert(runtime.tick(125'000'000));
    auto snapshot = runtime.snapshot();
    assert(!snapshot.terminalFailure);
    assert(snapshot.gate.clock.state == FeedbackClockState::Holdover);

    assert(runtime.onFeedbackInCompletion(completion(
        7, 4, 125'500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 5, 126'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 6, 126'500'000, twelveFrames.data(), twelveFrames.size()
    )));
    snapshot = runtime.snapshot();
    assert(!snapshot.terminalFailure);
    assert(snapshot.gate.clock.state == FeedbackClockState::Locked);
    assert(snapshot.gate.clock.relockCount == 1U);
    constexpr int64_t lastLockedSampleNs = 1'500'000;
    constexpr int64_t relockedAtNs = 126'500'000;
    const uint64_t expectedHoldoverNs = static_cast<uint64_t>(
        relockedAtNs -
            (lastLockedSampleNs + kSoftMissNs)
    );
    assert(snapshot.gate.clock.holdoverTotalNs == expectedHoldoverNs);
}

void reacquiresAfterLongSchedulingGapWithValidFeedback() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));

    constexpr std::array<uint8_t, 4> twelveFrames { 0x00, 0x00, 0x0C, 0x00 };
    assert(runtime.onFeedbackInCompletion(completion(
        7, 1, 500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 2, 1'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 3, 1'500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.nextPacket(true).status == StreamGatePacketStatus::PlayerPacket);

    constexpr int64_t resumedAtNs = 2'000'000'000;
    assert(runtime.onFeedbackInCompletion(completion(
        7, 4, resumedAtNs, twelveFrames.data(), twelveFrames.size()
    )));
    auto snapshot = runtime.snapshot();
    assert(!snapshot.terminalFailure);
    assert(snapshot.state == ExplicitFeedbackRuntimeState::Acquiring);
    assert(!snapshot.realPcmReleased);
    assert(snapshot.longGapReacquisitions == 1U);
    const auto bootstrap = runtime.nextPacket(true);
    assert(bootstrap.status == StreamGatePacketStatus::ZeroBootstrap);
    assert(bootstrap.allZero);

    assert(runtime.onFeedbackInCompletion(completion(
        7, 5, resumedAtNs + 500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 6, resumedAtNs + 1'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    snapshot = runtime.snapshot();
    assert(!snapshot.terminalFailure);
    assert(snapshot.gate.clock.state == FeedbackClockState::Locked);
    assert(snapshot.longGapReacquisitions == 1U);
    assert(runtime.nextPacket(true).status == StreamGatePacketStatus::PlayerPacket);
}

void longSchedulingGapWithoutValidFeedbackStillFailsClosed() {
    ExplicitFeedbackRuntime runtime;
    configureRuntime(&runtime);
    assert(runtime.start(0));

    constexpr std::array<uint8_t, 4> twelveFrames { 0x00, 0x00, 0x0C, 0x00 };
    assert(runtime.onFeedbackInCompletion(completion(
        7, 1, 500'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 2, 1'000'000, twelveFrames.data(), twelveFrames.size()
    )));
    assert(runtime.onFeedbackInCompletion(completion(
        7, 3, 1'500'000, twelveFrames.data(), twelveFrames.size()
    )));

    assert(!runtime.onFeedbackInCompletion(completion(
        7, 4, 2'000'000'000, nullptr, 0
    )));
    const auto snapshot = runtime.snapshot();
    assert(snapshot.terminalFailure);
    assert(snapshot.failure == ExplicitFeedbackRuntimeFailure::FeedbackClock);
    assert(snapshot.longGapReacquisitions == 0U);
}

} // namespace

int main() {
    blocksPlayerUntilFeedbackLocks();
    stoppingBeforeFeedbackLockIsNotReusable();
    toleratesShortErrorsThenFailsClosed();
    ignoresStaleGenerationWithoutPoisoningCurrentStream();
    acquireTimeoutFailsClosed();
    ignoresShortAndroidCallbackJitterWhileLocked();
    toleratesAndroidSchedulingGapAndRelocks();
    reacquiresAfterLongSchedulingGapWithValidFeedback();
    longSchedulingGapWithoutValidFeedbackStillFailsClosed();
    return 0;
}
