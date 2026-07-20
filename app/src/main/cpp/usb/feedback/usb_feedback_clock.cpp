#include "usb/feedback/usb_feedback_clock.h"

#include "usb/feedback/usb_feedback_rate_math.h"

#include <algorithm>
#include <limits>

namespace neri::usb::feedback {
namespace {

bool checkedPeriodProduct(int64_t periodNs, uint16_t periods, int64_t* output) {
    if (output == nullptr || periodNs <= 0 || periods == 0) {
        return false;
    }
    if (periodNs > std::numeric_limits<int64_t>::max() / periods) {
        return false;
    }
    *output = periodNs * periods;
    return true;
}

void incrementSaturated(uint64_t* value) {
    if (value != nullptr && *value != std::numeric_limits<uint64_t>::max()) {
        ++(*value);
    }
}

FeedbackRateQ32 slewStep(
    FeedbackRateQ32 current,
    FeedbackRateQ32 target,
    uint32_t maxPpm
) {
    if (current == target) {
        return current;
    }
    const FeedbackRateQ32 scaledWhole =
        (current / 1000000U) * maxPpm;
    const FeedbackRateQ32 scaledRemainder =
        ((current % 1000000U) * maxPpm + 500000U) / 1000000U;
    const FeedbackRateQ32 maxStep = std::max<FeedbackRateQ32>(
        1,
        scaledWhole + scaledRemainder
    );
    if (target > current) {
        return current + std::min(target - current, maxStep);
    }
    return current - std::min(current - target, maxStep);
}

} // namespace

bool FeedbackClock::configure(
    const FeedbackClockConfig& config,
    FeedbackRateQ32 nominalRateQ32
) {
    int64_t acquireTimeoutNs = 0;
    int64_t softMissNs = 0;
    int64_t hardHoldoverNs = 0;
    if (nominalRateQ32 == 0 || nominalRateQ32 > kMaxSupportedRateQ32 ||
        config.relockStableSamples == 0 ||
        config.relockStableSamples > config.hardHoldoverPeriods ||
        config.maxRelockSlewPpmPerSample == 0 ||
        config.maxRelockSlewPpmPerSample > 1000000U ||
        config.relockTolerancePpm > 1000000U ||
        !checkedPeriodProduct(
            config.expectedReportPeriodNs,
            config.acquireTimeoutPeriods,
            &acquireTimeoutNs
        ) ||
        !checkedPeriodProduct(
            config.expectedReportPeriodNs,
            config.softMissPeriods,
            &softMissNs
        ) ||
        !checkedPeriodProduct(
            config.expectedReportPeriodNs,
            config.hardHoldoverPeriods,
            &hardHoldoverNs
        ) || hardHoldoverNs <= softMissNs) {
        configured_ = false;
        nominalRateQ32_ = 0;
        clearRuntimeState();
        return false;
    }

    config_ = config;
    nominalRateQ32_ = nominalRateQ32;
    acquireTimeoutNs_ = acquireTimeoutNs;
    softMissNs_ = softMissNs;
    hardHoldoverNs_ = hardHoldoverNs;
    configured_ = true;
    clearRuntimeState();
    return true;
}

FeedbackMathStatus FeedbackClock::start(int64_t startedAtNs) {
    if (!configured_ || startedAtNs < 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    state_ = FeedbackClockState::Acquiring;
    trustedRateQ32_ = nominalRateQ32_;
    startedAtNs_ = startedAtNs;
    lastEventNs_ = startedAtNs;
    lastValidSampleNs_ = -1;
    holdoverStartedNs_ = -1;
    hasTrustedRate_ = false;
    consecutiveRelockStable_ = 0;
    lockCount_ = 0;
    relockCount_ = 0;
    holdoverCount_ = 0;
    rejectedSamples_ = 0;
    nonMonotonicEvents_ = 0;
    failureReason_ = FeedbackClockFailureReason::None;
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus FeedbackClock::onEstimate(
    const FeedbackEstimateResult& estimate,
    int64_t receivedAtNs
) {
    if (!configured_ || state_ == FeedbackClockState::Disabled ||
        state_ == FeedbackClockState::Failed) {
        return FeedbackMathStatus::NotReady;
    }
    if (!acceptTime(receivedAtNs)) {
        return FeedbackMathStatus::NonMonotonicInput;
    }
    const FeedbackMathStatus deadlineStatus = evaluateDeadlines(receivedAtNs);
    if (deadlineStatus != FeedbackMathStatus::Ok) {
        return deadlineStatus;
    }
    if (estimate.status != FeedbackEstimateStatus::Accepted ||
        !estimate.accepted || !estimate.hasTrustedRate ||
        estimate.trustedRateQ32 == 0 ||
        estimate.trustedRateQ32 > kMaxSupportedRateQ32) {
        return recordRejected(receivedAtNs);
    }

    lastValidSampleNs_ = receivedAtNs;
    switch (state_) {
        case FeedbackClockState::Acquiring:
            trustedRateQ32_ = estimate.trustedRateQ32;
            if (estimate.stable) {
                state_ = FeedbackClockState::Locked;
                hasTrustedRate_ = true;
                incrementSaturated(&lockCount_);
            }
            break;
        case FeedbackClockState::Locked:
            trustedRateQ32_ = estimate.trustedRateQ32;
            hasTrustedRate_ = true;
            break;
        case FeedbackClockState::Holdover:
            state_ = FeedbackClockState::Relocking;
            consecutiveRelockStable_ = 0;
            updateRelocking(estimate.trustedRateQ32, estimate.stable);
            break;
        case FeedbackClockState::Relocking:
            updateRelocking(estimate.trustedRateQ32, estimate.stable);
            break;
        case FeedbackClockState::Disabled:
        case FeedbackClockState::Failed:
            return FeedbackMathStatus::NotReady;
    }
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus FeedbackClock::onRejectedSample(int64_t receivedAtNs) {
    if (!configured_ || state_ == FeedbackClockState::Disabled ||
        state_ == FeedbackClockState::Failed) {
        return FeedbackMathStatus::NotReady;
    }
    if (!acceptTime(receivedAtNs)) {
        return FeedbackMathStatus::NonMonotonicInput;
    }
    return recordRejected(receivedAtNs);
}

FeedbackMathStatus FeedbackClock::onTick(int64_t nowNs) {
    if (!configured_ || state_ == FeedbackClockState::Disabled ||
        state_ == FeedbackClockState::Failed) {
        return FeedbackMathStatus::NotReady;
    }
    if (!acceptTime(nowNs)) {
        return FeedbackMathStatus::NonMonotonicInput;
    }
    return evaluateDeadlines(nowNs);
}

void FeedbackClock::disable() {
    clearRuntimeState();
}

void FeedbackClock::clearRuntimeState() {
    state_ = FeedbackClockState::Disabled;
    trustedRateQ32_ = nominalRateQ32_;
    startedAtNs_ = -1;
    lastEventNs_ = -1;
    lastValidSampleNs_ = -1;
    holdoverStartedNs_ = -1;
    hasTrustedRate_ = false;
    consecutiveRelockStable_ = 0;
    failureReason_ = FeedbackClockFailureReason::None;
    lockCount_ = 0;
    relockCount_ = 0;
    holdoverCount_ = 0;
    rejectedSamples_ = 0;
    nonMonotonicEvents_ = 0;
}

FeedbackClockSnapshot FeedbackClock::snapshot() const {
    return FeedbackClockSnapshot {
        state_,
        failureReason_,
        configured_,
        hasTrustedRate_,
        trustedRateQ32_,
        lastValidSampleNs_,
        holdoverStartedNs_,
        lockCount_,
        relockCount_,
        holdoverCount_,
        rejectedSamples_,
        nonMonotonicEvents_
    };
}

bool FeedbackClock::acceptTime(int64_t nowNs) {
    if (nowNs < 0 || (lastEventNs_ >= 0 && nowNs < lastEventNs_)) {
        incrementSaturated(&nonMonotonicEvents_);
        fail(FeedbackClockFailureReason::NonMonotonicTime);
        return false;
    }
    lastEventNs_ = nowNs;
    return true;
}

FeedbackMathStatus FeedbackClock::evaluateDeadlines(int64_t nowNs) {
    if (state_ == FeedbackClockState::Acquiring &&
        nowNs - startedAtNs_ >= acquireTimeoutNs_) {
        fail(FeedbackClockFailureReason::AcquireTimeout);
        return FeedbackMathStatus::NotReady;
    }
    if (state_ == FeedbackClockState::Locked && lastValidSampleNs_ >= 0 &&
        nowNs - lastValidSampleNs_ >= softMissNs_) {
        enterHoldover(lastValidSampleNs_ + softMissNs_);
    }
    if ((state_ == FeedbackClockState::Holdover ||
         state_ == FeedbackClockState::Relocking) &&
        holdoverStartedNs_ >= 0 &&
        nowNs - holdoverStartedNs_ >= hardHoldoverNs_) {
        fail(FeedbackClockFailureReason::HoldoverTimeout);
        return FeedbackMathStatus::NotReady;
    }
    return FeedbackMathStatus::Ok;
}

void FeedbackClock::enterHoldover(int64_t transitionAtNs) {
    state_ = FeedbackClockState::Holdover;
    holdoverStartedNs_ = transitionAtNs;
    consecutiveRelockStable_ = 0;
    incrementSaturated(&holdoverCount_);
}

void FeedbackClock::updateRelocking(
    FeedbackRateQ32 targetRateQ32,
    bool estimatorStable
) {
    trustedRateQ32_ = slewStep(
        trustedRateQ32_,
        targetRateQ32,
        config_.maxRelockSlewPpmPerSample
    );
    hasTrustedRate_ = true;

    uint32_t deviationPpm = 0;
    const bool withinTolerance = computeRateDeltaPpm(
        trustedRateQ32_,
        targetRateQ32,
        &deviationPpm
    ) == FeedbackMathStatus::Ok &&
        deviationPpm <= config_.relockTolerancePpm;
    consecutiveRelockStable_ = estimatorStable && withinTolerance
        ? static_cast<uint16_t>(consecutiveRelockStable_ + 1U)
        : 0;
    if (consecutiveRelockStable_ >= config_.relockStableSamples) {
        state_ = FeedbackClockState::Locked;
        holdoverStartedNs_ = -1;
        consecutiveRelockStable_ = 0;
        incrementSaturated(&lockCount_);
        incrementSaturated(&relockCount_);
    }
}

FeedbackMathStatus FeedbackClock::recordRejected(int64_t receivedAtNs) {
    incrementSaturated(&rejectedSamples_);
    if (state_ == FeedbackClockState::Relocking) {
        state_ = FeedbackClockState::Holdover;
        consecutiveRelockStable_ = 0;
    }
    return evaluateDeadlines(receivedAtNs);
}

void FeedbackClock::fail(FeedbackClockFailureReason reason) {
    state_ = FeedbackClockState::Failed;
    failureReason_ = reason;
    hasTrustedRate_ = false;
    consecutiveRelockStable_ = 0;
}

} // namespace neri::usb::feedback
