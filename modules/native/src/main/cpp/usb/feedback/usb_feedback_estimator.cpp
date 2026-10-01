#include "usb/feedback/usb_feedback_estimator.h"

#include "usb/feedback/usb_feedback_rate_math.h"

#include <algorithm>
#include <array>
#include <limits>

namespace neri::usb::feedback {
namespace {

bool incrementChecked(uint64_t* value) {
    if (value == nullptr || *value == std::numeric_limits<uint64_t>::max()) {
        return false;
    }
    ++(*value);
    return true;
}

uint32_t incrementSaturated(uint32_t value) {
    return value == std::numeric_limits<uint32_t>::max() ? value : value + 1U;
}

} // namespace

bool FeedbackEstimator::configure(
    const FeedbackEstimatorConfig& config,
    FeedbackRateQ32 nominalRateQ32
) {
    configured_ = false;
    if (nominalRateQ32 == 0 || nominalRateQ32 > kMaxSupportedRateQ32 ||
        config.hardDeviationPpm == 0 || config.hardDeviationPpm >= 1000000U ||
        config.localOutlierPpm == 0 ||
        config.localOutlierPpm > config.hardDeviationPpm ||
        config.stableTolerancePpm > config.localOutlierPpm ||
        config.medianWindow < 3U ||
        config.medianWindow > kMaxFeedbackMedianWindow ||
        (config.medianWindow % 2U) == 0 || config.smoothingShift > 16U ||
        config.stableSampleCount == 0 ||
        config.stableSampleCount > config.medianWindow) {
        reset();
        return false;
    }
    config_ = config;
    nominalRateQ32_ = nominalRateQ32;
    reset();
    configured_ = true;
    return true;
}

void FeedbackEstimator::reset() {
    history_.fill(0);
    historySize_ = 0;
    trustedRateQ32_ = 0;
    lastSequence_ = 0;
    lastTimestampNs_ = 0;
    hasObservation_ = false;
    hasTrustedRate_ = false;
    stable_ = false;
    acceptedSamples_ = 0;
    rejectedSamples_ = 0;
    hardRangeRejects_ = 0;
    localOutliers_ = 0;
    nonMonotonicSequences_ = 0;
    nonMonotonicTimestamps_ = 0;
    consecutiveAccepted_ = 0;
    consecutiveRejected_ = 0;
}

FeedbackEstimateResult FeedbackEstimator::ingest(
    const NormalizedFeedbackSample& sample
) {
    if (!configured_) {
        return FeedbackEstimateResult {};
    }
    if (sample.receivedAtNs < 0) {
        incrementChecked(&nonMonotonicTimestamps_);
        return reject(FeedbackEstimateStatus::NonMonotonicTimestamp, 0);
    }
    if (hasObservation_ && sample.sequence <= lastSequence_) {
        incrementChecked(&nonMonotonicSequences_);
        return reject(FeedbackEstimateStatus::NonMonotonicSequence, 0);
    }
    if (hasObservation_ && sample.receivedAtNs <= lastTimestampNs_) {
        incrementChecked(&nonMonotonicTimestamps_);
        return reject(FeedbackEstimateStatus::NonMonotonicTimestamp, 0);
    }
    hasObservation_ = true;
    lastSequence_ = sample.sequence;
    lastTimestampNs_ = sample.receivedAtNs;
    if (sample.rateQ32 == 0 || sample.rateQ32 > kMaxSupportedRateQ32) {
        incrementChecked(&hardRangeRejects_);
        return reject(FeedbackEstimateStatus::OutsideNominalRange, 1000000U);
    }

    uint32_t nominalDeviationPpm = 0;
    const FeedbackMathStatus nominalStatus = computeRateDeltaPpm(
        sample.rateQ32,
        nominalRateQ32_,
        &nominalDeviationPpm
    );
    if (nominalStatus != FeedbackMathStatus::Ok ||
        nominalDeviationPpm > config_.hardDeviationPpm) {
        incrementChecked(&hardRangeRejects_);
        return reject(
            FeedbackEstimateStatus::OutsideNominalRange,
            nominalDeviationPpm
        );
    }

    if (historySize_ >= 3U) {
        const FeedbackRateQ32 localMedian = median(0, historySize_);
        uint32_t localDeviationPpm = 0;
        const FeedbackMathStatus localStatus = computeRateDeltaPpm(
            sample.rateQ32,
            localMedian,
            &localDeviationPpm
        );
        if (localStatus != FeedbackMathStatus::Ok ||
            localDeviationPpm > config_.localOutlierPpm) {
            incrementChecked(&localOutliers_);
            return reject(FeedbackEstimateStatus::LocalOutlier, localDeviationPpm);
        }
    }

    pushAccepted(sample.rateQ32);
    const FeedbackRateQ32 filteredInput = median(0, historySize_);
    if (!hasTrustedRate_ || config_.smoothingShift == 0U) {
        trustedRateQ32_ = filteredInput;
    } else if (filteredInput != trustedRateQ32_) {
        const bool increasing = filteredInput > trustedRateQ32_;
        const FeedbackRateQ32 difference = increasing
            ? filteredInput - trustedRateQ32_
            : trustedRateQ32_ - filteredInput;
        const FeedbackRateQ32 shifted = difference >> config_.smoothingShift;
        const FeedbackRateQ32 step = std::max<FeedbackRateQ32>(1, shifted);
        trustedRateQ32_ = increasing
            ? trustedRateQ32_ + step
            : trustedRateQ32_ - step;
    }
    hasTrustedRate_ = true;
    incrementChecked(&acceptedSamples_);
    consecutiveAccepted_ = incrementSaturated(consecutiveAccepted_);
    consecutiveRejected_ = 0;
    stable_ = consecutiveAccepted_ >= config_.stableSampleCount &&
        recentWindowIsStable();

    FeedbackEstimateResult result;
    result.status = FeedbackEstimateStatus::Accepted;
    result.accepted = true;
    result.stable = stable_;
    result.hasTrustedRate = true;
    result.trustedRateQ32 = trustedRateQ32_;
    result.deviationPpm = nominalDeviationPpm;
    return result;
}

FeedbackEstimatorSnapshot FeedbackEstimator::snapshot() const {
    return FeedbackEstimatorSnapshot {
        configured_,
        stable_,
        hasTrustedRate_,
        trustedRateQ32_,
        acceptedSamples_,
        rejectedSamples_,
        hardRangeRejects_,
        localOutliers_,
        nonMonotonicSequences_,
        nonMonotonicTimestamps_,
        consecutiveAccepted_,
        consecutiveRejected_
    };
}

FeedbackRateQ32 FeedbackEstimator::median(size_t startIndex, size_t count) const {
    std::array<FeedbackRateQ32, kMaxFeedbackMedianWindow> sorted {};
    std::copy_n(history_.begin() + static_cast<std::ptrdiff_t>(startIndex), count, sorted.begin());
    std::sort(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(count));
    return sorted[count / 2U];
}

bool FeedbackEstimator::recentWindowIsStable() const {
    const size_t count = config_.stableSampleCount;
    if (historySize_ < count) {
        return false;
    }
    const size_t start = historySize_ - count;
    const FeedbackRateQ32 stableMedian = median(start, count);
    for (size_t index = start; index < historySize_; ++index) {
        uint32_t deviationPpm = 0;
        if (computeRateDeltaPpm(
                history_[index],
                stableMedian,
                &deviationPpm
            ) != FeedbackMathStatus::Ok ||
            deviationPpm > config_.stableTolerancePpm) {
            return false;
        }
    }
    return true;
}

void FeedbackEstimator::pushAccepted(FeedbackRateQ32 rateQ32) {
    if (historySize_ < config_.medianWindow) {
        history_[historySize_] = rateQ32;
        ++historySize_;
        return;
    }
    std::move(
        history_.begin() + 1,
        history_.begin() + static_cast<std::ptrdiff_t>(historySize_),
        history_.begin()
    );
    history_[historySize_ - 1U] = rateQ32;
}

FeedbackEstimateResult FeedbackEstimator::reject(
    FeedbackEstimateStatus status,
    uint32_t deviationPpm
) {
    incrementChecked(&rejectedSamples_);
    consecutiveAccepted_ = 0;
    consecutiveRejected_ = incrementSaturated(consecutiveRejected_);
    stable_ = false;
    FeedbackEstimateResult result;
    result.status = status;
    result.hasTrustedRate = hasTrustedRate_;
    result.trustedRateQ32 = trustedRateQ32_;
    result.deviationPpm = deviationPpm;
    return result;
}

} // namespace neri::usb::feedback
