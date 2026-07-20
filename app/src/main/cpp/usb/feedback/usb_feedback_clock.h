#pragma once

#include "usb/feedback/usb_feedback_estimator.h"
#include "usb/feedback/usb_feedback_types.h"

#include <cstdint>

namespace neri::usb::feedback {

struct FeedbackClockConfig {
    int64_t expectedReportPeriodNs = 0;
    uint16_t acquireTimeoutPeriods = 0;
    uint16_t softMissPeriods = 0;
    uint16_t hardHoldoverPeriods = 0;
    uint16_t relockStableSamples = 0;
    uint32_t maxRelockSlewPpmPerSample = 0;
    uint32_t relockTolerancePpm = 0;
};

struct FeedbackClockSnapshot {
    FeedbackClockState state = FeedbackClockState::Disabled;
    FeedbackClockFailureReason failureReason = FeedbackClockFailureReason::None;
    bool configured = false;
    bool hasTrustedRate = false;
    FeedbackRateQ32 trustedRateQ32 = 0;
    int64_t lastValidSampleNs = -1;
    int64_t holdoverStartedNs = -1;
    uint64_t lockCount = 0;
    uint64_t relockCount = 0;
    uint64_t holdoverCount = 0;
    uint64_t holdoverTotalNs = 0;
    uint64_t rejectedSamples = 0;
    uint64_t nonMonotonicEvents = 0;
};

class FeedbackClock {
public:
    bool configure(
        const FeedbackClockConfig& config,
        FeedbackRateQ32 nominalRateQ32
    );

    FeedbackMathStatus start(int64_t startedAtNs);

    FeedbackMathStatus onEstimate(
        const FeedbackEstimateResult& estimate,
        int64_t receivedAtNs
    );

    FeedbackMathStatus onRejectedSample(int64_t receivedAtNs);

    FeedbackMathStatus onTick(int64_t nowNs);

    void disable();

    [[nodiscard]] FeedbackClockSnapshot snapshot() const;

private:
    bool acceptTime(int64_t nowNs);
    FeedbackMathStatus evaluateDeadlines(int64_t nowNs);
    void enterHoldover(int64_t transitionAtNs);
    void updateRelocking(
        FeedbackRateQ32 targetRateQ32,
        bool estimatorStable,
        int64_t receivedAtNs
    );
    void finishHoldover(int64_t endedAtNs);
    FeedbackMathStatus recordRejected(int64_t receivedAtNs);
    void clearRuntimeState();
    void fail(FeedbackClockFailureReason reason);

    FeedbackClockConfig config_;
    FeedbackRateQ32 nominalRateQ32_ = 0;
    FeedbackRateQ32 trustedRateQ32_ = 0;
    int64_t acquireTimeoutNs_ = 0;
    int64_t softMissNs_ = 0;
    int64_t hardHoldoverNs_ = 0;
    int64_t startedAtNs_ = -1;
    int64_t lastEventNs_ = -1;
    int64_t lastValidSampleNs_ = -1;
    int64_t holdoverStartedNs_ = -1;
    FeedbackClockState state_ = FeedbackClockState::Disabled;
    FeedbackClockFailureReason failureReason_ = FeedbackClockFailureReason::None;
    bool configured_ = false;
    bool hasTrustedRate_ = false;
    uint16_t consecutiveRelockStable_ = 0;
    uint64_t lockCount_ = 0;
    uint64_t relockCount_ = 0;
    uint64_t holdoverCount_ = 0;
    uint64_t holdoverTotalNs_ = 0;
    uint64_t rejectedSamples_ = 0;
    uint64_t nonMonotonicEvents_ = 0;
};

} // namespace neri::usb::feedback
