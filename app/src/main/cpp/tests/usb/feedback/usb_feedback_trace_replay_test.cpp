#include "usb/feedback/usb_feedback_clock.h"
#include "usb/feedback/usb_feedback_decoder.h"
#include "usb/feedback/usb_feedback_estimator.h"
#include "usb/feedback/usb_feedback_packet_scheduler.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <random>

namespace {

using neri::usb::feedback::FeedbackClock;
using neri::usb::feedback::FeedbackClockConfig;
using neri::usb::feedback::FeedbackClockState;
using neri::usb::feedback::FeedbackDecodeInput;
using neri::usb::feedback::FeedbackDecodeProfile;
using neri::usb::feedback::FeedbackEstimateStatus;
using neri::usb::feedback::FeedbackEstimator;
using neri::usb::feedback::FeedbackEstimatorConfig;
using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackPacketScheduler;
using neri::usb::feedback::FeedbackRawUnit;
using neri::usb::feedback::FeedbackRateQ32;
using neri::usb::feedback::kQ32One;

uint64_t seedFromEnvironment() {
    const char* value = std::getenv("NERI_USB_TEST_SEED");
    return value == nullptr ? UINT64_C(324508639) : std::strtoull(value, nullptr, 10);
}

uint32_t rawForPpm(int32_t ppm) {
    const uint64_t nominalRaw = UINT64_C(48) << 16U;
    const uint64_t scale = static_cast<uint64_t>(1000000 + ppm);
    return static_cast<uint32_t>((nominalRaw * scale + 500000U) / 1000000U);
}

void writesLittleEndian(uint32_t value, std::array<uint8_t, 4>* output) {
    assert(output != nullptr);
    for (size_t index = 0; index < output->size(); ++index) {
        (*output)[index] = static_cast<uint8_t>(value >> (index * 8U));
    }
}

void replaysJitterLossAndRelockDeterministically() {
    constexpr FeedbackDecodeProfile profile {
        4,
        16,
        FeedbackRawUnit::FramesPerServiceInterval,
        1000,
        1000,
        1,
        1
    };
    const FeedbackRateQ32 nominal = UINT64_C(48) * kQ32One;
    FeedbackEstimator estimator;
    assert(estimator.configure(
        FeedbackEstimatorConfig {
            100000,
            5000,
            300,
            5,
            1,
            4
        },
        nominal
    ));
    FeedbackClock clock;
    assert(clock.configure(
        FeedbackClockConfig {
            1000000,
            10,
            4,
            30,
            3,
            500,
            150
        },
        nominal
    ));
    assert(clock.start(0) == FeedbackMathStatus::Ok);

    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(nominal, 8, 49U * 8U) ==
        FeedbackMathStatus::Ok);

    std::mt19937 random(static_cast<uint32_t>(seedFromEnvironment()));
    std::uniform_int_distribution<int32_t> jitter(-80, 80);
    uint32_t expectedPhase = 0;
    uint64_t expectedFrames = 0;
    uint64_t scheduledIntervals = 0;
    uint64_t rejectedTraceSamples = 0;

    for (uint64_t index = 0; index < 10000; ++index) {
        const int64_t timestamp = static_cast<int64_t>((index + 1U) * 1000000U);
        const bool missingFeedback = index >= 2000U && index <= 2004U;
        if (missingFeedback) {
            assert(clock.onTick(timestamp) == FeedbackMathStatus::Ok);
        } else {
            int32_t ppm = jitter(random);
            if (index == 5000U) {
                ppm = 50000;
            }
            std::array<uint8_t, 4> payload {};
            writesLittleEndian(rawForPpm(ppm), &payload);
            const auto decoded = neri::usb::feedback::decodeFeedbackSample(
                profile,
                FeedbackDecodeInput {
                    payload.data(),
                    payload.size(),
                    timestamp,
                    index + 1U
                }
            );
            assert(decoded.status == FeedbackMathStatus::Ok);
            const auto estimate = estimator.ingest(decoded.sample.normalized);
            if (estimate.status == FeedbackEstimateStatus::LocalOutlier) {
                ++rejectedTraceSamples;
                assert(clock.onRejectedSample(timestamp) == FeedbackMathStatus::Ok);
            } else {
                assert(estimate.status == FeedbackEstimateStatus::Accepted);
                assert(clock.onEstimate(estimate, timestamp) == FeedbackMathStatus::Ok);
            }
        }

        const auto clockSnapshot = clock.snapshot();
        if (clockSnapshot.state == FeedbackClockState::Locked ||
            clockSnapshot.state == FeedbackClockState::Holdover ||
            clockSnapshot.state == FeedbackClockState::Relocking) {
            assert(clockSnapshot.hasTrustedRate);
            assert(scheduler.updateRate(clockSnapshot.trustedRateQ32) ==
                FeedbackMathStatus::Ok);
            const auto plan = scheduler.next();
            assert(plan.status == FeedbackMathStatus::Ok);
            const uint64_t total =
                clockSnapshot.trustedRateQ32 + expectedPhase;
            const uint32_t expectedPacketFrames =
                static_cast<uint32_t>(total >> 32U);
            expectedPhase = static_cast<uint32_t>(total & (kQ32One - 1U));
            assert(plan.frames == expectedPacketFrames);
            expectedFrames += expectedPacketFrames;
            ++scheduledIntervals;
        }
    }

    const auto clockSnapshot = clock.snapshot();
    const auto estimatorSnapshot = estimator.snapshot();
    const auto schedulerSnapshot = scheduler.snapshot();
    assert(clockSnapshot.state == FeedbackClockState::Locked);
    assert(clockSnapshot.holdoverCount == 1);
    assert(clockSnapshot.relockCount == 1);
    assert(rejectedTraceSamples == 1);
    assert(estimatorSnapshot.localOutliers == 1);
    assert(scheduledIntervals > 9000U);
    assert(schedulerSnapshot.scheduledFrames == expectedFrames);
    assert(schedulerSnapshot.capacityRejects == 0);
}

} // namespace

int main() {
    replaysJitterLossAndRelockDeterministically();
    return 0;
}
