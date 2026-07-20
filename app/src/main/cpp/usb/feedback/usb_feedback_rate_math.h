#pragma once

#include "usb/feedback/usb_feedback_types.h"

#include <cstdint>

namespace neri::usb::feedback {

struct FeedbackProjection {
    FeedbackMathStatus status = FeedbackMathStatus::NotReady;
    uint64_t totalFrames = 0;
    uint32_t remainderQ32 = 0;
};

FeedbackMathStatus makeFeedbackRateQ32(
    uint32_t sampleRate,
    uint32_t intervalsPerSecond,
    FeedbackRateQ32* output
);

FeedbackMathStatus normalizeFeedbackRateQ32(
    uint64_t rawValue,
    uint8_t fractionalBits,
    uint32_t sourceIntervalsPerSecond,
    uint32_t audioIntervalsPerSecond,
    uint32_t scaleNumerator,
    uint32_t scaleDenominator,
    FeedbackRateQ32* output
);

FeedbackMathStatus computeRateDeltaPpm(
    FeedbackRateQ32 sample,
    FeedbackRateQ32 reference,
    uint32_t* outputPpm
);

FeedbackProjection projectScheduledFrames(
    FeedbackRateQ32 rateQ32,
    uint32_t phaseQ32,
    uint64_t intervalCount
);

} // namespace neri::usb::feedback
