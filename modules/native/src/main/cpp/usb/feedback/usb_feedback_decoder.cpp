#include "usb/feedback/usb_feedback_decoder.h"

#include "usb/feedback/usb_feedback_rate_math.h"

#include <cstdint>

namespace neri::usb::feedback {
namespace {

uint64_t readLittleEndian(const uint8_t* payload, uint8_t payloadBytes) {
    uint64_t value = 0;
    for (uint8_t index = 0; index < payloadBytes; ++index) {
        value |= static_cast<uint64_t>(payload[index]) << (index * 8U);
    }
    return value;
}

uint64_t payloadMask(uint8_t payloadBytes) {
    if (payloadBytes == sizeof(uint64_t)) {
        return UINT64_MAX;
    }
    return (UINT64_C(1) << (payloadBytes * 8U)) - 1U;
}

bool rawUnitIsKnown(FeedbackRawUnit rawUnit) {
    switch (rawUnit) {
        case FeedbackRawUnit::FramesPerBusFrame:
        case FeedbackRawUnit::FramesPerMicroframe:
        case FeedbackRawUnit::FramesPerServiceInterval:
            return true;
        case FeedbackRawUnit::Unknown:
            return false;
    }
    return false;
}

} // namespace

bool isFeedbackDecodeProfileSupported(const FeedbackDecodeProfile& profile) {
    if (profile.payloadBytesExpected == 0 ||
        profile.payloadBytesExpected > sizeof(uint64_t)) {
        return false;
    }
    const uint64_t validPayloadMask = payloadMask(profile.payloadBytesExpected);
    return profile.fractionalBits <= 63U &&
        profile.fractionalBits <= profile.payloadBytesExpected * 8U &&
        rawUnitIsKnown(profile.rawUnit) &&
        profile.sourceIntervalsPerSecond > 0 &&
        profile.sourceIntervalsPerSecond <= 8000U &&
        profile.audioIntervalsPerSecond > 0 &&
        profile.audioIntervalsPerSecond <= 8000U &&
        profile.scaleNumerator > 0 && profile.scaleDenominator > 0 &&
        (profile.requiredZeroMask & ~validPayloadMask) == 0 &&
        profile.requiredZeroMask != validPayloadMask;
}

FeedbackDecodeResult decodeFeedbackSample(
    const FeedbackDecodeProfile& profile,
    const FeedbackDecodeInput& input
) {
    FeedbackDecodeResult result;
    result.sample.payloadBytesExpected = profile.payloadBytesExpected;
    result.sample.payloadBytesActual = input.payloadBytesActual;
    result.sample.fractionalBits = profile.fractionalBits;
    result.sample.rawUnit = profile.rawUnit;

    if (!isFeedbackDecodeProfileSupported(profile)) {
        result.status = FeedbackMathStatus::UnsupportedProfile;
        return result;
    }
    if (input.payload == nullptr) {
        result.status = FeedbackMathStatus::NullPayload;
        return result;
    }
    if (input.payloadBytesActual != profile.payloadBytesExpected ||
        input.payloadBytesActual > UINT8_MAX) {
        result.status = FeedbackMathStatus::PayloadLengthMismatch;
        return result;
    }
    if (input.receivedAtNs < 0) {
        result.status = FeedbackMathStatus::InvalidArgument;
        return result;
    }

    const uint64_t rawValue = readLittleEndian(
        input.payload,
        profile.payloadBytesExpected
    );
    if (rawValue == 0 || rawValue == payloadMask(profile.payloadBytesExpected)) {
        result.status = FeedbackMathStatus::OutOfRange;
        return result;
    }
    if ((rawValue & profile.requiredZeroMask) != 0) {
        result.status = FeedbackMathStatus::OutOfRange;
        return result;
    }

    FeedbackRateQ32 normalizedRate = 0;
    result.status = normalizeFeedbackRateQ32(
        rawValue,
        profile.fractionalBits,
        profile.sourceIntervalsPerSecond,
        profile.audioIntervalsPerSecond,
        profile.scaleNumerator,
        profile.scaleDenominator,
        &normalizedRate
    );
    if (result.status != FeedbackMathStatus::Ok) {
        return result;
    }

    result.status = FeedbackMathStatus::Ok;
    result.sample.rawValue = rawValue;
    result.sample.normalized = NormalizedFeedbackSample {
        normalizedRate,
        input.receivedAtNs,
        input.sequence
    };
    return result;
}

} // namespace neri::usb::feedback
