#include "usb/feedback/usb_feedback_estimator.h"

#include <cassert>
#include <cstdint>

namespace {

using neri::usb::feedback::FeedbackEstimateStatus;
using neri::usb::feedback::FeedbackEstimator;
using neri::usb::feedback::FeedbackEstimatorConfig;
using neri::usb::feedback::FeedbackRateQ32;
using neri::usb::feedback::NormalizedFeedbackSample;
using neri::usb::feedback::kQ32One;

FeedbackRateQ32 rateWithPpm(FeedbackRateQ32 nominal, int32_t ppm) {
    const uint64_t scale = static_cast<uint64_t>(1000000 + ppm);
    return (nominal * scale + 500000U) / 1000000U;
}

FeedbackEstimator configuredEstimator(FeedbackRateQ32 nominal) {
    FeedbackEstimator estimator;
    assert(estimator.configure(
        FeedbackEstimatorConfig {
            100000,
            2000,
            200,
            5,
            2,
            3
        },
        nominal
    ));
    return estimator;
}

void acquiresStableRateAcrossBoundedJitter() {
    const FeedbackRateQ32 nominal = UINT64_C(6) * kQ32One;
    FeedbackEstimator estimator = configuredEstimator(nominal);
    const int32_t jitterPpm[] = { -50, 20, 40, -30, 10 };
    for (uint64_t index = 0; index < 5; ++index) {
        const auto result = estimator.ingest(NormalizedFeedbackSample {
            rateWithPpm(nominal, jitterPpm[index]),
            static_cast<int64_t>((index + 1U) * 1000000U),
            index + 1U
        });
        assert(result.status == FeedbackEstimateStatus::Accepted);
        assert(result.accepted);
        if (index >= 2U) {
            assert(result.stable);
        }
    }
    const auto snapshot = estimator.snapshot();
    assert(snapshot.stable);
    assert(snapshot.acceptedSamples == 5);
    assert(snapshot.rejectedSamples == 0);
}

void rejectsHardRangeLocalOutlierAndNonMonotonicInput() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    FeedbackEstimator estimator = configuredEstimator(nominal);
    for (uint64_t index = 0; index < 3; ++index) {
        assert(estimator.ingest(NormalizedFeedbackSample {
            nominal,
            static_cast<int64_t>((index + 1U) * 1000U),
            index + 1U
        }).accepted);
    }

    auto result = estimator.ingest(NormalizedFeedbackSample {
        rateWithPpm(nominal, 5000),
        4000,
        4
    });
    assert(result.status == FeedbackEstimateStatus::LocalOutlier);
    assert(!result.accepted);

    result = estimator.ingest(NormalizedFeedbackSample {
        nominal * 2U,
        5000,
        5
    });
    assert(result.status == FeedbackEstimateStatus::OutsideNominalRange);

    result = estimator.ingest(NormalizedFeedbackSample { nominal, 6000, 5 });
    assert(result.status == FeedbackEstimateStatus::NonMonotonicSequence);
    result = estimator.ingest(NormalizedFeedbackSample { nominal, 5000, 6 });
    assert(result.status == FeedbackEstimateStatus::NonMonotonicTimestamp);
    result = estimator.ingest(NormalizedFeedbackSample { nominal, -1, 7 });
    assert(result.status == FeedbackEstimateStatus::NonMonotonicTimestamp);

    const auto snapshot = estimator.snapshot();
    assert(snapshot.localOutliers == 1);
    assert(snapshot.hardRangeRejects == 1);
    assert(snapshot.nonMonotonicSequences == 1);
    assert(snapshot.nonMonotonicTimestamps == 2);
    assert(snapshot.rejectedSamples == 5);
}

void followsSustainedStepWithoutOvershoot() {
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    const FeedbackRateQ32 target = rateWithPpm(nominal, 1000);
    FeedbackEstimator estimator = configuredEstimator(nominal);
    uint64_t sequence = 0;
    int64_t timestamp = 0;
    for (int index = 0; index < 5; ++index) {
        ++sequence;
        timestamp += 1000000;
        assert(estimator.ingest(
            NormalizedFeedbackSample { nominal, timestamp, sequence }
        ).accepted);
    }
    const FeedbackRateQ32 beforeStep = estimator.snapshot().trustedRateQ32;
    FeedbackRateQ32 previous = beforeStep;
    for (int index = 0; index < 24; ++index) {
        ++sequence;
        timestamp += 1000000;
        const auto result = estimator.ingest(
            NormalizedFeedbackSample { target, timestamp, sequence }
        );
        assert(result.accepted);
        assert(result.trustedRateQ32 >= previous);
        assert(result.trustedRateQ32 <= target);
        previous = result.trustedRateQ32;
    }
    assert(previous > beforeStep);
    assert(estimator.snapshot().stable);
}

void rejectsInvalidConfiguration() {
    FeedbackEstimator estimator;
    assert(estimator.configure(
        FeedbackEstimatorConfig { 100000, 2000, 200, 3, 0, 3 },
        kQ32One
    ));
    for (uint64_t index = 0; index < 3; ++index) {
        assert(estimator.ingest(NormalizedFeedbackSample {
            kQ32One,
            static_cast<int64_t>(index + 1U),
            index + 1U
        }).accepted);
    }
    assert(estimator.snapshot().stable);
    assert(!estimator.configure(
        FeedbackEstimatorConfig { 0, 0, 0, 4, 0, 0 },
        kQ32One
    ));
    const auto result = estimator.ingest(
        NormalizedFeedbackSample { kQ32One, 1, 1 }
    );
    assert(result.status == FeedbackEstimateStatus::NotConfigured);
    assert(!estimator.snapshot().stable);
    assert(!estimator.snapshot().hasTrustedRate);
    assert(estimator.snapshot().acceptedSamples == 0);
    assert(!estimator.configure(
        FeedbackEstimatorConfig { 100000, 2000, 200, 1, 0, 1 },
        kQ32One
    ));
    assert(!estimator.snapshot().hasTrustedRate);
}

} // namespace

int main() {
    acquiresStableRateAcrossBoundedJitter();
    rejectsHardRangeLocalOutlierAndNonMonotonicInput();
    followsSustainedStepWithoutOvershoot();
    rejectsInvalidConfiguration();
    return 0;
}
