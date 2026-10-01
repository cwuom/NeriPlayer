#include "usb/feedback/usb_feedback_clock.h"

#include <cassert>
#include <cstdint>
#include <string>

namespace {

using neri::usb::feedback::FeedbackClock;
using neri::usb::feedback::FeedbackClockConfig;
using neri::usb::feedback::FeedbackClockFailureReason;
using neri::usb::feedback::FeedbackClockState;
using neri::usb::feedback::FeedbackEstimateResult;
using neri::usb::feedback::FeedbackEstimateStatus;
using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackRateQ32;
using neri::usb::feedback::kQ32One;

constexpr int64_t kPeriodNs = 1000000;

FeedbackRateQ32 rateWithPpm(FeedbackRateQ32 nominal, uint32_t ppm) {
    return (nominal * (1000000U + ppm) + 500000U) / 1000000U;
}

FeedbackEstimateResult acceptedEstimate(
    FeedbackRateQ32 rate,
    bool stable = true
) {
    return FeedbackEstimateResult {
        FeedbackEstimateStatus::Accepted,
        true,
        stable,
        true,
        rate,
        0
    };
}

FeedbackClock configuredClock(FeedbackRateQ32 nominal) {
    FeedbackClock clock;
    assert(clock.configure(
        FeedbackClockConfig {
            kPeriodNs,
            5,
            3,
            20,
            2,
            500,
            100
        },
        nominal
    ));
    return clock;
}

void locksHoldsOverAndRelocksWithBoundedSlew() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    const FeedbackRateQ32 target = rateWithPpm(nominal, 1000);
    FeedbackClock clock = configuredClock(nominal);
    assert(clock.start(0) == FeedbackMathStatus::Ok);
    assert(clock.onEstimate(acceptedEstimate(nominal), kPeriodNs) ==
        FeedbackMathStatus::Ok);
    assert(clock.snapshot().state == FeedbackClockState::Locked);

    assert(clock.onTick(4 * kPeriodNs - 1) == FeedbackMathStatus::Ok);
    assert(clock.snapshot().state == FeedbackClockState::Locked);
    assert(clock.onTick(4 * kPeriodNs) == FeedbackMathStatus::Ok);
    assert(clock.snapshot().state == FeedbackClockState::Holdover);
    const FeedbackRateQ32 holdoverRate = clock.snapshot().trustedRateQ32;

    assert(clock.onEstimate(acceptedEstimate(target), 5 * kPeriodNs) ==
        FeedbackMathStatus::Ok);
    auto snapshot = clock.snapshot();
    assert(snapshot.state == FeedbackClockState::Relocking);
    assert(snapshot.trustedRateQ32 > holdoverRate);
    assert(snapshot.trustedRateQ32 < target);

    assert(clock.onEstimate(acceptedEstimate(target), 6 * kPeriodNs) ==
        FeedbackMathStatus::Ok);
    assert(clock.snapshot().state == FeedbackClockState::Relocking);
    assert(clock.onEstimate(acceptedEstimate(target), 7 * kPeriodNs) ==
        FeedbackMathStatus::Ok);
    snapshot = clock.snapshot();
    assert(snapshot.state == FeedbackClockState::Locked);
    assert(snapshot.lockCount == 2);
    assert(snapshot.holdoverCount == 1);
    assert(snapshot.holdoverTotalNs == 3 * kPeriodNs);
    assert(snapshot.relockCount == 1);
    assert(std::string(neri::usb::feedback::feedbackClockStateName(
        snapshot.state
    )) == "locked");
}

void failsAtAcquireAndHoldoverDeadlines() {
    const FeedbackRateQ32 nominal = UINT64_C(6) * kQ32One;
    FeedbackClock acquireClock = configuredClock(nominal);
    assert(acquireClock.start(0) == FeedbackMathStatus::Ok);
    assert(acquireClock.onTick(5 * kPeriodNs - 1) == FeedbackMathStatus::Ok);
    assert(acquireClock.snapshot().state == FeedbackClockState::Acquiring);
    assert(acquireClock.onTick(5 * kPeriodNs) == FeedbackMathStatus::NotReady);
    assert(acquireClock.snapshot().state == FeedbackClockState::Failed);
    assert(acquireClock.snapshot().failureReason ==
        FeedbackClockFailureReason::AcquireTimeout);

    FeedbackClock holdoverClock = configuredClock(nominal);
    assert(holdoverClock.start(0) == FeedbackMathStatus::Ok);
    assert(holdoverClock.onEstimate(acceptedEstimate(nominal), kPeriodNs) ==
        FeedbackMathStatus::Ok);
    assert(holdoverClock.onTick(4 * kPeriodNs) == FeedbackMathStatus::Ok);
    assert(holdoverClock.onTick(24 * kPeriodNs - 1) == FeedbackMathStatus::Ok);
    assert(holdoverClock.snapshot().state == FeedbackClockState::Holdover);
    assert(holdoverClock.onTick(24 * kPeriodNs) == FeedbackMathStatus::NotReady);
    assert(holdoverClock.snapshot().state == FeedbackClockState::Failed);
    assert(holdoverClock.snapshot().failureReason ==
        FeedbackClockFailureReason::HoldoverTimeout);
    assert(holdoverClock.snapshot().holdoverTotalNs == 20 * kPeriodNs);
}

void failsClosedForNonMonotonicClockInput() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    FeedbackClock clock = configuredClock(nominal);
    assert(clock.start(kPeriodNs) == FeedbackMathStatus::Ok);
    assert(clock.onTick(kPeriodNs - 1) ==
        FeedbackMathStatus::NonMonotonicInput);
    const auto snapshot = clock.snapshot();
    assert(snapshot.state == FeedbackClockState::Failed);
    assert(snapshot.nonMonotonicEvents == 1);
    assert(snapshot.failureReason ==
        FeedbackClockFailureReason::NonMonotonicTime);
}

void lateSamplesCannotBypassClockDeadlines() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    FeedbackClock acquireClock = configuredClock(nominal);
    assert(acquireClock.start(0) == FeedbackMathStatus::Ok);
    assert(acquireClock.onEstimate(
        acceptedEstimate(nominal),
        5 * kPeriodNs
    ) == FeedbackMathStatus::NotReady);
    assert(acquireClock.snapshot().state == FeedbackClockState::Failed);
    assert(acquireClock.snapshot().lastValidSampleNs == -1);

    FeedbackClock softMissClock = configuredClock(nominal);
    assert(softMissClock.start(0) == FeedbackMathStatus::Ok);
    assert(softMissClock.onEstimate(
        acceptedEstimate(nominal),
        kPeriodNs
    ) == FeedbackMathStatus::Ok);
    assert(softMissClock.onEstimate(
        acceptedEstimate(nominal),
        4 * kPeriodNs
    ) == FeedbackMathStatus::Ok);
    assert(softMissClock.snapshot().state == FeedbackClockState::Relocking);
    assert(softMissClock.snapshot().holdoverCount == 1);

    const FeedbackEstimateResult rejectedEstimate {
        FeedbackEstimateStatus::LocalOutlier,
        false,
        false,
        true,
        nominal,
        5000
    };
    assert(softMissClock.onEstimate(
        rejectedEstimate,
        5 * kPeriodNs
    ) == FeedbackMathStatus::Ok);
    assert(softMissClock.snapshot().state == FeedbackClockState::Holdover);

    FeedbackClock hardMissClock = configuredClock(nominal);
    assert(hardMissClock.start(0) == FeedbackMathStatus::Ok);
    assert(hardMissClock.onEstimate(
        acceptedEstimate(nominal),
        kPeriodNs
    ) == FeedbackMathStatus::Ok);
    assert(hardMissClock.onEstimate(
        acceptedEstimate(nominal),
        24 * kPeriodNs
    ) == FeedbackMathStatus::NotReady);
    assert(hardMissClock.snapshot().state == FeedbackClockState::Failed);
}

void failedReconfigurationClearsTrustedClockState() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    FeedbackClock clock = configuredClock(nominal);
    assert(clock.start(0) == FeedbackMathStatus::Ok);
    assert(clock.onEstimate(acceptedEstimate(nominal), kPeriodNs) ==
        FeedbackMathStatus::Ok);
    assert(clock.snapshot().hasTrustedRate);

    assert(!clock.configure(
        FeedbackClockConfig {},
        nominal
    ));
    const auto snapshot = clock.snapshot();
    assert(!snapshot.configured);
    assert(snapshot.state == FeedbackClockState::Disabled);
    assert(snapshot.failureReason == FeedbackClockFailureReason::None);
    assert(!snapshot.hasTrustedRate);
    assert(snapshot.trustedRateQ32 == 0);
    assert(snapshot.lockCount == 0);

    FeedbackClock unreachableRelockClock;
    assert(!unreachableRelockClock.configure(
        FeedbackClockConfig {
            kPeriodNs,
            5,
            1,
            2,
            3,
            500,
            100
        },
        nominal
    ));
}

} // namespace

int main() {
    locksHoldsOverAndRelocksWithBoundedSlew();
    failsAtAcquireAndHoldoverDeadlines();
    failsClosedForNonMonotonicClockInput();
    lateSamplesCannotBypassClockDeadlines();
    failedReconfigurationClearsTrustedClockState();
    return 0;
}
