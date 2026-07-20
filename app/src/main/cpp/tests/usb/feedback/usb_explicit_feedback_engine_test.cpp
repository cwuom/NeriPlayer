#include "usb/feedback/usb_explicit_feedback_engine.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace {

using neri::usb::feedback::CancelResult;
using neri::usb::feedback::ExplicitFeedbackBackend;
using neri::usb::feedback::ExplicitFeedbackEngine;
using neri::usb::feedback::ExplicitFeedbackEngineConfig;
using neri::usb::feedback::ExplicitFeedbackEngineResult;
using neri::usb::feedback::ExplicitFeedbackEngineState;
using neri::usb::feedback::ExplicitFeedbackFailureReason;
using neri::usb::feedback::FeedbackPacketPlan;
using neri::usb::feedback::FeedbackRawUnit;
using neri::usb::feedback::TransferCompletionStatus;
using neri::usb::feedback::TransferKind;
using neri::usb::feedback::kQ32One;

constexpr uint64_t kGeneration = 41;
constexpr uint64_t kNominalRateQ32 = UINT64_C(6) * kQ32One;

const char* kindSuffix(TransferKind kind) {
    return kind == TransferKind::AudioOut ? "audio" : "feedback";
}

class FakeBackend final : public ExplicitFeedbackBackend {
public:
    bool claimInterfaces() override {
        calls.emplace_back("claim");
        interfacesClaimed = succeeds("claim");
        return interfacesClaimed;
    }

    bool activateAlternateSetting() override {
        calls.emplace_back("activate");
        alternateActive = succeeds("activate");
        return alternateActive;
    }

    bool allocateTransfers(TransferKind kind, uint32_t count) override {
        const std::string operation = std::string("allocate_") + kindSuffix(kind);
        calls.push_back(operation);
        if (!succeeds(operation) || count == 0) {
            return false;
        }
        if (kind == TransferKind::AudioOut) {
            audioAllocated = true;
        } else {
            feedbackAllocated = true;
        }
        return true;
    }

    bool startEventLoop() override {
        calls.emplace_back("start_event_loop");
        eventLoopStarted = succeeds("start_event_loop");
        return eventLoopStarted;
    }

    bool submitFeedbackTransfers(uint32_t count) override {
        calls.emplace_back("submit_feedback");
        feedbackSubmitCalls += count;
        return count > 0 && succeeds("submit_feedback");
    }

    bool submitAudioTransfer(const FeedbackPacketPlan& packet, bool allZero) override {
        calls.emplace_back("submit_audio");
        audioPlans.push_back(packet);
        audioAllZero.push_back(allZero);
        return packet.bytes > 0 && succeeds("submit_audio");
    }

    CancelResult cancelTransfers(TransferKind kind) override {
        calls.push_back(std::string("cancel_") + kindSuffix(kind));
        return kind == TransferKind::AudioOut ? audioCancelResult : feedbackCancelResult;
    }

    bool stopEventLoop() override {
        calls.emplace_back("stop_event_loop");
        if (!succeeds("stop_event_loop")) {
            return false;
        }
        eventLoopStarted = false;
        return true;
    }

    bool freeTransfers(TransferKind kind) override {
        const std::string operation = std::string("free_") + kindSuffix(kind);
        calls.push_back(operation);
        if (!succeeds(operation)) {
            return false;
        }
        if (kind == TransferKind::AudioOut) {
            audioAllocated = false;
        } else {
            feedbackAllocated = false;
        }
        return true;
    }

    bool deactivateAlternateSetting() override {
        calls.emplace_back("deactivate");
        if (!succeeds("deactivate")) {
            return false;
        }
        alternateActive = false;
        return true;
    }

    bool releaseInterfaces() override {
        calls.emplace_back("release");
        if (!succeeds("release")) {
            return false;
        }
        interfacesClaimed = false;
        return true;
    }

    bool succeeds(const std::string& operation) const {
        return failOperation != operation;
    }

    bool resourcesEmpty() const {
        return !interfacesClaimed && !alternateActive && !feedbackAllocated &&
            !audioAllocated && !eventLoopStarted;
    }

    std::string failOperation;
    std::vector<std::string> calls;
    std::vector<FeedbackPacketPlan> audioPlans;
    std::vector<bool> audioAllZero;
    CancelResult audioCancelResult = CancelResult::Accepted;
    CancelResult feedbackCancelResult = CancelResult::Accepted;
    uint32_t feedbackSubmitCalls = 0;
    bool interfacesClaimed = false;
    bool alternateActive = false;
    bool feedbackAllocated = false;
    bool audioAllocated = false;
    bool eventLoopStarted = false;
};

ExplicitFeedbackEngineConfig config(bool enabled = true) {
    ExplicitFeedbackEngineConfig value;
    value.enabled = enabled;
    value.generation = kGeneration;
    value.feedbackTransferCount = 2;
    value.audioTransferCount = 2;
    value.decodeProfile = {
        4,
        16,
        FeedbackRawUnit::FramesPerMicroframe,
        8000,
        8000,
        1,
        1,
        UINT64_C(0xF0000000)
    };
    value.estimatorConfig = { 100000, 5000, 200, 3, 0, 1 };
    value.clockConfig = { 125000, 8, 3, 16, 2, 500, 200 };
    value.nominalRateQ32 = kNominalRateQ32;
    value.frameBytes = 8;
    value.endpointCapacityBytes = 56;
    value.bootstrapPacketLimit = 4;
    value.maxAheadIntervals = 4;
    value.zeroLengthFeedbackPermitted = true;
    return value;
}

std::array<uint8_t, 4> nominalPayload() {
    const uint32_t raw = 6U << 16U;
    return {
        static_cast<uint8_t>(raw),
        static_cast<uint8_t>(raw >> 8U),
        static_cast<uint8_t>(raw >> 16U),
        static_cast<uint8_t>(raw >> 24U)
    };
}

void drainCancelledTransfers(ExplicitFeedbackEngine* engine) {
    assert(engine != nullptr);
    while (engine->snapshot().audioOut.inFlight > 0) {
        engine->onAudioCompletion(
            kGeneration,
            TransferCompletionStatus::Cancelled,
            false
        );
    }
    while (engine->snapshot().feedbackIn.inFlight > 0) {
        engine->onFeedbackCompletion(
            kGeneration,
            TransferCompletionStatus::Cancelled,
            nullptr,
            0,
            1,
            1,
            false
        );
    }
}

void drainFeedbackFirst(ExplicitFeedbackEngine* engine) {
    assert(engine != nullptr);
    while (engine->snapshot().feedbackIn.inFlight > 0) {
        engine->onFeedbackCompletion(
            kGeneration,
            TransferCompletionStatus::Cancelled,
            nullptr,
            0,
            1,
            1,
            false
        );
    }
    while (engine->snapshot().audioOut.inFlight > 0) {
        engine->onAudioCompletion(
            kGeneration,
            TransferCompletionStatus::Cancelled,
            false
        );
    }
}

void featureOffTouchesNoBackend() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(false), &backend) ==
        ExplicitFeedbackEngineResult::FeatureDisabled);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::InvalidState);
    assert(backend.calls.empty());
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Disabled);
}

void startsFeedbackBeforeBoundedZeroAudio() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    const auto state = engine.snapshot();
    assert(state.state == ExplicitFeedbackEngineState::Acquiring);
    assert(state.feedbackIn.inFlight == 2U);
    assert(state.audioOut.inFlight == 2U);
    assert(state.zeroPacketsSubmitted == 2U);
    assert(state.playerPacketsSubmitted == 0U);
    assert(backend.audioAllZero.size() == 2U);
    assert(backend.audioAllZero[0] && backend.audioAllZero[1]);
    assert(backend.calls[5] == "submit_feedback");
    assert(backend.calls[6] == "submit_audio");
}

void queuedPlayerPcmRemainsZeroUntilFeedbackLock() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.onAudioCompletion(
        kGeneration,
        TransferCompletionStatus::Completed,
        true
    ) == ExplicitFeedbackEngineResult::Ok);
    assert(backend.audioAllZero.back());
    assert(engine.snapshot().playerPacketsSubmitted == 0U);

    const auto payload = nominalPayload();
    assert(engine.onFeedbackCompletion(
        kGeneration,
        TransferCompletionStatus::Completed,
        payload.data(),
        payload.size(),
        125000,
        1,
        true
    ) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.onAudioCompletion(
        kGeneration,
        TransferCompletionStatus::Completed,
        true
    ) == ExplicitFeedbackEngineResult::Ok);
    assert(!backend.audioAllZero.back());
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Streaming);
    assert(engine.snapshot().playerPacketsSubmitted == 1U);
}

void startFailuresReleaseInReverseOrder() {
    const std::vector<std::string> failures {
        "claim",
        "activate",
        "allocate_feedback",
        "allocate_audio",
        "start_event_loop",
        "submit_feedback",
        "submit_audio"
    };
    for (const std::string& failure : failures) {
        FakeBackend backend;
        backend.failOperation = failure;
        ExplicitFeedbackEngine engine;
        assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
        assert(engine.start(0) != ExplicitFeedbackEngineResult::Ok);
        drainCancelledTransfers(&engine);
        assert(backend.resourcesEmpty());
        assert(engine.snapshot().resources.empty());
        assert(engine.snapshot().state == ExplicitFeedbackEngineState::Failed);
    }
}

void rejectsUnreachableAcquireConfiguration() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    ExplicitFeedbackEngineConfig value = config();
    value.estimatorConfig.stableSampleCount = 3;
    value.clockConfig.acquireTimeoutPeriods = 3;
    assert(engine.configure(value, &backend) ==
        ExplicitFeedbackEngineResult::InvalidArgument);
    assert(backend.calls.empty());

    value = config();
    value.maxAheadIntervals = value.audioTransferCount - 1U;
    assert(engine.configure(value, &backend) ==
        ExplicitFeedbackEngineResult::InvalidArgument);
    assert(backend.calls.empty());

    value = config();
    value.decodeProfile = {
        1,
        0,
        FeedbackRawUnit::FramesPerMicroframe,
        8000,
        1,
        UINT32_MAX,
        1,
        0
    };
    assert(engine.configure(value, &backend) ==
        ExplicitFeedbackEngineResult::InvalidArgument);
    assert(backend.calls.empty());
}

void acceptsReducibleWideProfileAtNominalRate() {
    constexpr uint64_t multiplier = UINT64_C(1000000);
    constexpr uint64_t scaleNumerator = UINT64_C(3221225469);
    constexpr uint64_t scaleDenominator = UINT64_C(4294967291);
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    ExplicitFeedbackEngineConfig value = config();
    value.decodeProfile = {
        8,
        32,
        FeedbackRawUnit::FramesPerServiceInterval,
        1,
        1,
        static_cast<uint32_t>(scaleNumerator),
        static_cast<uint32_t>(scaleDenominator),
        0
    };
    value.nominalRateQ32 = multiplier * scaleNumerator - 1U;
    value.endpointCapacityBytes = 6'144'000;
    assert(engine.configure(value, &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.snapshot().configured);
    assert(backend.calls.empty());
}

void acceptsWideDenominatorAndMaskedRawWitnesses() {
    FakeBackend denominatorBackend;
    ExplicitFeedbackEngine denominatorEngine;
    ExplicitFeedbackEngineConfig denominator = config();
    denominator.decodeProfile = {
        5,
        0,
        FeedbackRawUnit::FramesPerServiceInterval,
        1,
        1,
        1,
        UINT32_C(4294967291),
        0
    };
    denominator.nominalRateQ32 = UINT64_C(23676007219);
    assert(denominatorEngine.configure(denominator, &denominatorBackend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(denominatorBackend.calls.empty());

    FakeBackend bit63Backend;
    ExplicitFeedbackEngine bit63Engine;
    ExplicitFeedbackEngineConfig bit63 = config();
    bit63.decodeProfile = {
        8,
        63,
        FeedbackRawUnit::FramesPerServiceInterval,
        8000,
        8000,
        UINT32_MAX,
        UINT32_MAX,
        UINT64_C(0x7FFFFFFFFFFFFFFF)
    };
    bit63.nominalRateQ32 = kQ32One;
    assert(bit63Engine.configure(bit63, &bit63Backend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(bit63Backend.calls.empty());

    FakeBackend predecessorBackend;
    ExplicitFeedbackEngine predecessorEngine;
    ExplicitFeedbackEngineConfig predecessor = config();
    predecessor.decodeProfile = {
        1,
        0,
        FeedbackRawUnit::FramesPerServiceInterval,
        1,
        1,
        1,
        1,
        UINT64_C(0x02)
    };
    predecessor.nominalRateQ32 = UINT64_C(10) * kQ32One;
    predecessor.endpointCapacityBytes = 80;
    assert(predecessorEngine.configure(predecessor, &predecessorBackend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(predecessorBackend.calls.empty());

    FakeBackend successorBackend;
    ExplicitFeedbackEngine successorEngine;
    ExplicitFeedbackEngineConfig successor = config();
    successor.decodeProfile = {
        1,
        0,
        FeedbackRawUnit::FramesPerServiceInterval,
        1,
        1,
        1,
        1,
        UINT64_C(0x7E)
    };
    successor.nominalRateQ32 = UINT64_C(100) * kQ32One;
    successor.endpointCapacityBytes = 1024;
    successor.estimatorConfig.hardDeviationPpm = 300000;
    assert(successorEngine.configure(successor, &successorBackend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(successorBackend.calls.empty());
}

void stopDrainsBothSetsAndAcceptsCancelNotFound() {
    FakeBackend backend;
    backend.feedbackCancelResult = CancelResult::NotFound;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.stop() == ExplicitFeedbackEngineResult::Draining);
    assert(engine.snapshot().feedbackIn.cancelNotFound == 1U);
    assert(engine.snapshot().feedbackIn.inFlight == 0U);
    drainCancelledTransfers(&engine);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Stopped);
    assert(backend.resourcesEmpty());

    FakeBackend reverseBackend;
    ExplicitFeedbackEngine reverse;
    assert(reverse.configure(config(), &reverseBackend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(reverse.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(reverse.stop() == ExplicitFeedbackEngineResult::Draining);
    drainFeedbackFirst(&reverse);
    assert(reverse.snapshot().state == ExplicitFeedbackEngineState::Stopped);
    assert(reverseBackend.resourcesEmpty());
}

void initialLockTimeoutIsTerminalAndDrains() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.tick(1'000'000, true) ==
        ExplicitFeedbackEngineResult::TerminalFailure);
    assert(engine.snapshot().failure == ExplicitFeedbackFailureReason::FeedbackClock);
    drainCancelledTransfers(&engine);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Failed);
    assert(backend.resourcesEmpty());
}

void staleGenerationFailsClosedWithoutResubmit() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    const uint32_t submitCalls = backend.feedbackSubmitCalls;
    const auto result = engine.onFeedbackCompletion(
        kGeneration - 1U,
        TransferCompletionStatus::Completed,
        nullptr,
        0,
        125000,
        1,
        false
    );
    assert(result == ExplicitFeedbackEngineResult::TerminalFailure);
    assert(backend.feedbackSubmitCalls == submitCalls);
    assert(engine.snapshot().failure == ExplicitFeedbackFailureReason::InternalInvariant);
    assert(engine.snapshot().feedbackIn.inFlight == 2U);
    assert(engine.snapshot().audioOut.inFlight == 2U);
    drainCancelledTransfers(&engine);
    assert(backend.resourcesEmpty());
}

void noDeviceAndDrainTimeoutRetainThenReleaseOwner() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.stop() == ExplicitFeedbackEngineResult::Draining);
    assert(engine.onDrainTimeout() == ExplicitFeedbackEngineResult::TerminalFailure);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Quarantined);
    assert(!backend.resourcesEmpty());
    drainCancelledTransfers(&engine);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Failed);
    assert(engine.snapshot().failure == ExplicitFeedbackFailureReason::DrainTimeout);
    assert(backend.resourcesEmpty());

    FakeBackend detachedBackend;
    ExplicitFeedbackEngine detached;
    assert(detached.configure(config(), &detachedBackend) ==
        ExplicitFeedbackEngineResult::Ok);
    assert(detached.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(detached.onFeedbackCompletion(
        kGeneration,
        TransferCompletionStatus::NoDevice,
        nullptr,
        0,
        125000,
        1,
        false
    ) == ExplicitFeedbackEngineResult::TerminalFailure);
    drainCancelledTransfers(&detached);
    assert(detached.snapshot().failure == ExplicitFeedbackFailureReason::DeviceDetached);
    assert(detachedBackend.resourcesEmpty());
}

void zeroLengthAndMalformedFeedbackRemainBounded() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.onFeedbackCompletion(
        kGeneration,
        TransferCompletionStatus::Completed,
        nullptr,
        0,
        125000,
        1,
        false
    ) == ExplicitFeedbackEngineResult::Ok);
    const std::array<uint8_t, 3> malformed { 0, 0, 0 };
    assert(engine.onFeedbackCompletion(
        kGeneration,
        TransferCompletionStatus::Completed,
        malformed.data(),
        malformed.size(),
        250000,
        2,
        false
    ) == ExplicitFeedbackEngineResult::RejectedFeedback);
    assert(engine.snapshot().feedbackPacketsZeroLength == 1U);
    assert(engine.snapshot().feedbackPacketsInvalid == 1U);
    assert(engine.snapshot().feedbackIn.inFlight == 2U);
}

void cleanupFailureStaysQuarantinedUntilRetry() {
    FakeBackend backend;
    ExplicitFeedbackEngine engine;
    assert(engine.configure(config(), &backend) == ExplicitFeedbackEngineResult::Ok);
    assert(engine.start(0) == ExplicitFeedbackEngineResult::Ok);
    backend.failOperation = "stop_event_loop";
    assert(engine.stop() == ExplicitFeedbackEngineResult::Draining);
    drainCancelledTransfers(&engine);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Quarantined);
    assert(!backend.resourcesEmpty());
    backend.failOperation.clear();
    assert(engine.retryCleanup() == ExplicitFeedbackEngineResult::TerminalFailure);
    assert(engine.snapshot().state == ExplicitFeedbackEngineState::Failed);
    assert(backend.resourcesEmpty());
}

void exposesStableNames() {
    assert(std::string(neri::usb::feedback::explicitFeedbackEngineStateName(
        ExplicitFeedbackEngineState::Quarantined
    )) == "quarantined");
    assert(std::string(neri::usb::feedback::explicitFeedbackEngineResultName(
        ExplicitFeedbackEngineResult::Draining
    )) == "draining");
    assert(std::string(neri::usb::feedback::explicitFeedbackFailureReasonName(
        ExplicitFeedbackFailureReason::SubmitZeroBootstrap
    )) == "submit_zero_bootstrap");
}

} // namespace

int main() {
    featureOffTouchesNoBackend();
    startsFeedbackBeforeBoundedZeroAudio();
    queuedPlayerPcmRemainsZeroUntilFeedbackLock();
    startFailuresReleaseInReverseOrder();
    rejectsUnreachableAcquireConfiguration();
    acceptsReducibleWideProfileAtNominalRate();
    acceptsWideDenominatorAndMaskedRawWitnesses();
    stopDrainsBothSetsAndAcceptsCancelNotFound();
    initialLockTimeoutIsTerminalAndDrains();
    staleGenerationFailsClosedWithoutResubmit();
    noDeviceAndDrainTimeoutRetainThenReleaseOwner();
    zeroLengthAndMalformedFeedbackRemainBounded();
    cleanupFailureStaysQuarantinedUntilRetry();
    exposesStableNames();
    return 0;
}
