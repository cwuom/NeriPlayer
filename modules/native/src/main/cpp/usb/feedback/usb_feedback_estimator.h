#pragma once

#include "usb/feedback/usb_feedback_types.h"

#include <array>
#include <cstddef>
#include <cstdint>

namespace neri::usb::feedback {

constexpr size_t kMaxFeedbackMedianWindow = 9;

struct FeedbackEstimatorConfig {
    uint32_t hardDeviationPpm = 0;
    uint32_t localOutlierPpm = 0;
    uint32_t stableTolerancePpm = 0;
    uint8_t medianWindow = 0;
    uint8_t smoothingShift = 0;
    uint16_t stableSampleCount = 0;
};

struct FeedbackEstimateResult {
    FeedbackEstimateStatus status = FeedbackEstimateStatus::NotConfigured;
    bool accepted = false;
    bool stable = false;
    bool hasTrustedRate = false;
    FeedbackRateQ32 trustedRateQ32 = 0;
    uint32_t deviationPpm = 0;
};

struct FeedbackEstimatorSnapshot {
    bool configured = false;
    bool stable = false;
    bool hasTrustedRate = false;
    FeedbackRateQ32 trustedRateQ32 = 0;
    uint64_t acceptedSamples = 0;
    uint64_t rejectedSamples = 0;
    uint64_t hardRangeRejects = 0;
    uint64_t localOutliers = 0;
    uint64_t nonMonotonicSequences = 0;
    uint64_t nonMonotonicTimestamps = 0;
    uint32_t consecutiveAccepted = 0;
    uint32_t consecutiveRejected = 0;
};

class FeedbackEstimator {
public:
    bool configure(
        const FeedbackEstimatorConfig& config,
        FeedbackRateQ32 nominalRateQ32
    );

    void reset();

    FeedbackEstimateResult ingest(const NormalizedFeedbackSample& sample);

    [[nodiscard]] FeedbackEstimatorSnapshot snapshot() const;

private:
    [[nodiscard]] FeedbackRateQ32 median(size_t startIndex, size_t count) const;
    [[nodiscard]] bool recentWindowIsStable() const;
    void pushAccepted(FeedbackRateQ32 rateQ32);
    FeedbackEstimateResult reject(
        FeedbackEstimateStatus status,
        uint32_t deviationPpm
    );

    FeedbackEstimatorConfig config_;
    FeedbackRateQ32 nominalRateQ32_ = 0;
    std::array<FeedbackRateQ32, kMaxFeedbackMedianWindow> history_ {};
    size_t historySize_ = 0;
    FeedbackRateQ32 trustedRateQ32_ = 0;
    uint64_t lastSequence_ = 0;
    int64_t lastTimestampNs_ = 0;
    bool configured_ = false;
    bool hasObservation_ = false;
    bool hasTrustedRate_ = false;
    bool stable_ = false;
    uint64_t acceptedSamples_ = 0;
    uint64_t rejectedSamples_ = 0;
    uint64_t hardRangeRejects_ = 0;
    uint64_t localOutliers_ = 0;
    uint64_t nonMonotonicSequences_ = 0;
    uint64_t nonMonotonicTimestamps_ = 0;
    uint32_t consecutiveAccepted_ = 0;
    uint32_t consecutiveRejected_ = 0;
};

} // namespace neri::usb::feedback
