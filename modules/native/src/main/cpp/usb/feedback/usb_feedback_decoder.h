#pragma once

#include "usb/feedback/usb_feedback_types.h"

#include <cstddef>
#include <cstdint>

namespace neri::usb::feedback {

struct FeedbackDecodeProfile {
    uint8_t payloadBytesExpected = 0;
    uint8_t fractionalBits = 0;
    FeedbackRawUnit rawUnit = FeedbackRawUnit::Unknown;
    uint32_t sourceIntervalsPerSecond = 0;
    uint32_t audioIntervalsPerSecond = 0;
    uint32_t scaleNumerator = 1;
    uint32_t scaleDenominator = 1;
    uint64_t requiredZeroMask = 0;
};

struct FeedbackDecodeInput {
    const uint8_t* payload = nullptr;
    size_t payloadBytesActual = 0;
    int64_t receivedAtNs = 0;
    uint64_t sequence = 0;
};

struct DecodedFeedbackSample {
    NormalizedFeedbackSample normalized;
    uint64_t rawValue = 0;
    uint8_t payloadBytesExpected = 0;
    size_t payloadBytesActual = 0;
    uint8_t fractionalBits = 0;
    FeedbackRawUnit rawUnit = FeedbackRawUnit::Unknown;
};

struct FeedbackDecodeResult {
    FeedbackMathStatus status = FeedbackMathStatus::NotReady;
    DecodedFeedbackSample sample;
};

bool isFeedbackDecodeProfileSupported(const FeedbackDecodeProfile& profile);

FeedbackDecodeResult decodeFeedbackSample(
    const FeedbackDecodeProfile& profile,
    const FeedbackDecodeInput& input
);

} // namespace neri::usb::feedback
