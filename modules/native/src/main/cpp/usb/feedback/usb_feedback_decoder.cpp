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

constexpr uint64_t kFourBytePayloadReservedMask = UINT64_C(0xF0000000);

// 高速设备沿用 UAC1 的 3 字节 10.14、全速 UAC2 设备发 4 字节 16.16 都很常见，
// 3/4 字节互相接受，定点格式差异由运行时按名义速率检测 2 的幂次修正
uint8_t acceptedPayloadBytes(const FeedbackDecodeProfile& profile, size_t actualBytes) {
    if (actualBytes == profile.payloadBytesExpected) {
        return profile.payloadBytesExpected;
    }
    const bool interchangeable =
        (profile.payloadBytesExpected == 3U || profile.payloadBytesExpected == 4U) &&
        (actualBytes == 3U || actualBytes == 4U);
    return interchangeable ? static_cast<uint8_t>(actualBytes) : 0U;
}

uint64_t requiredZeroMaskFor(const FeedbackDecodeProfile& profile, uint8_t payloadBytes) {
    if (payloadBytes == profile.payloadBytesExpected) {
        return profile.requiredZeroMask;
    }
    return payloadBytes == 4U ? kFourBytePayloadReservedMask : 0U;
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
    const uint8_t payloadBytes = acceptedPayloadBytes(profile, input.payloadBytesActual);
    if (payloadBytes == 0U) {
        result.status = FeedbackMathStatus::PayloadLengthMismatch;
        return result;
    }
    if (input.receivedAtNs < 0) {
        result.status = FeedbackMathStatus::InvalidArgument;
        return result;
    }

    const uint64_t rawValue = readLittleEndian(input.payload, payloadBytes);
    if (rawValue == 0 || rawValue == payloadMask(payloadBytes)) {
        result.status = FeedbackMathStatus::OutOfRange;
        return result;
    }
    if ((rawValue & requiredZeroMaskFor(profile, payloadBytes)) != 0) {
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
