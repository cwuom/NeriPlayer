#include "usb/feedback/usb_feedback_decoder.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <limits>
#include <string>

namespace {

using neri::usb::feedback::FeedbackDecodeInput;
using neri::usb::feedback::FeedbackDecodeProfile;
using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackRawUnit;
using neri::usb::feedback::kQ32One;

void verifiesConfiguredThreeByteFixedPointVector() {
    constexpr std::array<uint8_t, 3> payload { 0x00, 0x00, 0x0C };
    const FeedbackDecodeProfile profile {
        3,
        14,
        FeedbackRawUnit::FramesPerServiceInterval,
        1000,
        1000,
        1,
        1
    };
    const auto result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { payload.data(), payload.size(), 1000, 7 }
    );
    assert(result.status == FeedbackMathStatus::Ok);
    assert(result.sample.rawValue == (UINT64_C(48) << 14U));
    assert(result.sample.normalized.rateQ32 == UINT64_C(48) * kQ32One);
    assert(result.sample.normalized.receivedAtNs == 1000);
    assert(result.sample.normalized.sequence == 7);
}

void verifiesSourceAndAudioCadenceStayIndependent() {
    constexpr std::array<uint8_t, 4> payload { 0x00, 0x00, 0x30, 0x00 };
    const FeedbackDecodeProfile profile {
        4,
        16,
        FeedbackRawUnit::FramesPerServiceInterval,
        1000,
        8000,
        1,
        1
    };
    const auto result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { payload.data(), payload.size(), 2000, 8 }
    );
    assert(result.status == FeedbackMathStatus::Ok);
    assert(result.sample.normalized.rateQ32 == UINT64_C(6) * kQ32One);
}

void rejectsUnknownProfilesAndMalformedPayloads() {
    constexpr std::array<uint8_t, 4> validPayload { 0x00, 0x00, 0x06, 0x00 };
    FeedbackDecodeProfile profile {
        4,
        16,
        FeedbackRawUnit::Unknown,
        8000,
        8000,
        1,
        1
    };
    auto result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { validPayload.data(), validPayload.size(), 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::UnsupportedProfile);

    profile.rawUnit = FeedbackRawUnit::FramesPerServiceInterval;
    result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { validPayload.data(), 3, 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::PayloadLengthMismatch);
    result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { nullptr, 4, 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::NullPayload);

    constexpr std::array<uint8_t, 4> zeroPayload {};
    result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { zeroPayload.data(), zeroPayload.size(), 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::OutOfRange);
    constexpr std::array<uint8_t, 4> allOnesPayload {
        0xFF,
        0xFF,
        0xFF,
        0xFF
    };
    result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { allOnesPayload.data(), allOnesPayload.size(), 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::OutOfRange);

    constexpr std::array<uint8_t, 8> allOnesWidePayload {
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF
    };
    profile.payloadBytesExpected = 8;
    result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput {
            allOnesWidePayload.data(),
            allOnesWidePayload.size(),
            1,
            1
        }
    );
    assert(result.status == FeedbackMathStatus::OutOfRange);
}

void rejectsOverflowAndExposesStableStatusNames() {
    constexpr std::array<uint8_t, 8> payload {
        0xFE,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF,
        0xFF
    };
    const FeedbackDecodeProfile profile {
        8,
        0,
        FeedbackRawUnit::FramesPerServiceInterval,
        8000,
        1,
        std::numeric_limits<uint32_t>::max(),
        1
    };
    const auto result = neri::usb::feedback::decodeFeedbackSample(
        profile,
        FeedbackDecodeInput { payload.data(), payload.size(), 1, 1 }
    );
    assert(result.status == FeedbackMathStatus::Overflow);
    assert(std::string(neri::usb::feedback::feedbackMathStatusName(
        result.status
    )) == "overflow");
}

void rejectsProfilesThatCannotNormalize() {
    FeedbackDecodeProfile profile {
        8,
        64,
        FeedbackRawUnit::FramesPerServiceInterval,
        8000,
        8000,
        1,
        1
    };
    assert(!neri::usb::feedback::isFeedbackDecodeProfileSupported(profile));

    profile.fractionalBits = 16;
    profile.sourceIntervalsPerSecond = 8001;
    assert(!neri::usb::feedback::isFeedbackDecodeProfileSupported(profile));

    profile.payloadBytesExpected = 4;
    profile.sourceIntervalsPerSecond = 8000;
    profile.requiredZeroMask = UINT64_C(0xFFFFFFFF);
    assert(!neri::usb::feedback::isFeedbackDecodeProfileSupported(profile));
}

} // namespace

int main() {
    verifiesConfiguredThreeByteFixedPointVector();
    verifiesSourceAndAudioCadenceStayIndependent();
    rejectsUnknownProfilesAndMalformedPayloads();
    rejectsOverflowAndExposesStableStatusNames();
    rejectsProfilesThatCannotNormalize();
    return 0;
}
