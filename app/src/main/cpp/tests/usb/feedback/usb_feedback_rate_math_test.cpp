#include "usb/feedback/usb_feedback_rate_math.h"

#include <cassert>
#include <cstdint>
#include <limits>

namespace {

using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackRateQ32;
using neri::usb::feedback::kQ32One;

void verifiesRateConstructionAndPpmMath() {
    FeedbackRateQ32 rate = 0;
    assert(neri::usb::feedback::makeFeedbackRateQ32(44100, 1000, &rate) ==
        FeedbackMathStatus::Ok);
    const uint64_t expected =
        (UINT64_C(44100) * kQ32One + 500U) / 1000U;
    assert(rate == expected);

    const FeedbackRateQ32 reference = UINT64_C(48) * kQ32One;
    const FeedbackRateQ32 shifted = reference + reference / 10000U;
    uint32_t ppm = 0;
    assert(neri::usb::feedback::computeRateDeltaPpm(
        shifted,
        reference,
        &ppm
    ) == FeedbackMathStatus::Ok);
    assert(ppm == 100U);
    assert(neri::usb::feedback::computeRateDeltaPpm(3, 2, &ppm) ==
        FeedbackMathStatus::Ok);
    assert(ppm == 500000U);
    assert(neri::usb::feedback::computeRateDeltaPpm(1, 3, &ppm) ==
        FeedbackMathStatus::Ok);
    assert(ppm == 666667U);
}

void verifiesExplicitNormalizationMath() {
    FeedbackRateQ32 normalized = 0;
    const uint64_t rawQ16 = UINT64_C(48) << 16U;
    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        rawQ16,
        16,
        1000,
        8000,
        1,
        1,
        &normalized
    ) == FeedbackMathStatus::Ok);
    assert(normalized == UINT64_C(6) * kQ32One);

    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        rawQ16,
        16,
        1000,
        8000,
        2,
        1,
        &normalized
    ) == FeedbackMathStatus::Ok);
    assert(normalized == UINT64_C(12) * kQ32One);
}

void normalizesReducibleWideFeedbackWithoutFalseOverflow() {
    constexpr uint64_t multiplier = UINT64_C(1000000);
    constexpr uint64_t scaleNumerator = UINT64_C(3221225469);
    constexpr uint64_t scaleDenominator = UINT64_C(4294967291);
    constexpr uint64_t exactRaw = multiplier * scaleDenominator;
    constexpr uint64_t expected = multiplier * scaleNumerator;
    FeedbackRateQ32 normalized = 0;
    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        exactRaw - 1U,
        32,
        1,
        1,
        static_cast<uint32_t>(scaleNumerator),
        static_cast<uint32_t>(scaleDenominator),
        &normalized
    ) == FeedbackMathStatus::Ok);
    assert(normalized == expected - 1U);
    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        exactRaw,
        32,
        1,
        1,
        static_cast<uint32_t>(scaleNumerator),
        static_cast<uint32_t>(scaleDenominator),
        &normalized
    ) == FeedbackMathStatus::Ok);
    assert(normalized == expected);
    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        exactRaw + 1U,
        32,
        1,
        1,
        static_cast<uint32_t>(scaleNumerator),
        static_cast<uint32_t>(scaleDenominator),
        &normalized
    ) == FeedbackMathStatus::Ok);
    assert(normalized == expected + 1U);
}

void verifiesLongDurationProjectionWithoutIteration() {
    FeedbackRateQ32 rate = 0;
    assert(neri::usb::feedback::makeFeedbackRateQ32(44100, 8000, &rate) ==
        FeedbackMathStatus::Ok);
    constexpr uint64_t seconds = 72U * 60U * 60U;
    constexpr uint64_t intervals = seconds * 8000U;
    const auto projection = neri::usb::feedback::projectScheduledFrames(
        rate,
        0,
        intervals
    );
    assert(projection.status == FeedbackMathStatus::Ok);
    const uint64_t idealFrames = seconds * 44100U;
    const uint64_t frameError = projection.totalFrames >= idealFrames
        ? projection.totalFrames - idealFrames
        : idealFrames - projection.totalFrames;
    assert(frameError <= 1U);

    const auto highIntervalProjection =
        neri::usb::feedback::projectScheduledFrames(
            kQ32One + (kQ32One / 2U),
            static_cast<uint32_t>(kQ32One / 4U),
            kQ32One + 3U
        );
    assert(highIntervalProjection.status == FeedbackMathStatus::Ok);
    assert(highIntervalProjection.totalFrames == UINT64_C(6442450948));
    assert(highIntervalProjection.remainderQ32 == UINT32_C(0xC0000000));

}

void rejectsInvalidAndOverflowingMath() {
    FeedbackRateQ32 output = 0;
    assert(neri::usb::feedback::makeFeedbackRateQ32(0, 1000, &output) ==
        FeedbackMathStatus::InvalidArgument);
    assert(neri::usb::feedback::makeFeedbackRateQ32(768001, 1000, &output) ==
        FeedbackMathStatus::OutOfRange);
    assert(neri::usb::feedback::normalizeFeedbackRateQ32(
        std::numeric_limits<uint64_t>::max() - 1U,
        0,
        8000,
        1,
        std::numeric_limits<uint32_t>::max(),
        1,
        &output
    ) == FeedbackMathStatus::Overflow);
    const auto projection = neri::usb::feedback::projectScheduledFrames(
        std::numeric_limits<uint64_t>::max(),
        0,
        std::numeric_limits<uint64_t>::max()
    );
    assert(projection.status == FeedbackMathStatus::Overflow);
}

} // namespace

int main() {
    verifiesRateConstructionAndPpmMath();
    verifiesExplicitNormalizationMath();
    normalizesReducibleWideFeedbackWithoutFalseOverflow();
    verifiesLongDurationProjectionWithoutIteration();
    rejectsInvalidAndOverflowingMath();
    return 0;
}
