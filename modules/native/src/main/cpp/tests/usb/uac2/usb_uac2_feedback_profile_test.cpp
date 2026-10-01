#include "usb/feedback/usb_feedback_decoder.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>

namespace {

using neri::usb::feedback::FeedbackDecodeInput;
using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackRawUnit;
using neri::usb::uac2::EndpointCapacitySource;
using neri::usb::uac2::EndpointSnapshot;
using neri::usb::uac2::UsbBusSpeed;
using neri::usb::uac2::Uac2FeedbackProfileStatus;

EndpointSnapshot endpoint(
    uint8_t address,
    uint8_t attributes,
    int capacity,
    uint8_t interval
) {
    EndpointSnapshot value;
    value.configurationValue = 7;
    value.interfaceNumber = 9;
    value.alternateSetting = 5;
    value.endpointAddress = address;
    value.descriptorLength = 7;
    value.descriptorType = 0x05;
    value.bmAttributes = attributes;
    value.rawMaxPacketSize = static_cast<uint16_t>(capacity);
    value.effectiveMaxPacketBytes = capacity;
    value.effectiveCapacityKnown = true;
    value.capacitySource = EndpointCapacitySource::StandardDescriptor;
    value.bInterval = interval;
    return value;
}

void verifiesHighSpeedProfileAndCadence() {
    const EndpointSnapshot output = endpoint(0x06, 0x05, 512, 2);
    const EndpointSnapshot feedback = endpoint(0x86, 0x11, 4, 3);
    const auto profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::High,
        output,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::Valid);
    assert(profile.decodeProfile.payloadBytesExpected == 4U);
    assert(profile.decodeProfile.fractionalBits == 16U);
    assert(profile.decodeProfile.rawUnit == FeedbackRawUnit::FramesPerMicroframe);
    assert(profile.decodeProfile.requiredZeroMask == UINT64_C(0xF0000000));
    assert(profile.outputServiceIntervalBusUnits == 2U);
    assert(profile.feedbackPollingIntervalBusUnits == 4U);
    assert(profile.feedbackExpectedPeriodNanoseconds == 500'000U);
    assert(profile.evidence.verified);
    assert(profile.evidence.outputEndpointAddress == 0x06U);
    assert(profile.evidence.feedbackEndpointAddress == 0x86U);
    assert(!profile.evidence.profileId.empty());

    constexpr std::array<uint8_t, 4> payload { 0x00, 0x00, 0x06, 0x00 };
    const auto decoded = neri::usb::feedback::decodeFeedbackSample(
        profile.decodeProfile,
        FeedbackDecodeInput { payload.data(), payload.size(), 1'000'000, 1 }
    );
    assert(decoded.status == FeedbackMathStatus::Ok);
    assert(decoded.sample.normalized.rateQ32 == UINT64_C(12) * (UINT64_C(1) << 32));
}

void verifiesFullSpeedProfileAndOutputIntervalScaling() {
    EndpointSnapshot output = endpoint(0x02, 0x05, 192, 2);
    EndpointSnapshot feedback = endpoint(0x82, 0x11, 3, 1);
    const auto profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::Full,
        output,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::Valid);
    assert(profile.decodeProfile.payloadBytesExpected == 3U);
    assert(profile.decodeProfile.fractionalBits == 14U);
    assert(profile.decodeProfile.rawUnit == FeedbackRawUnit::FramesPerBusFrame);
    assert(profile.outputServiceIntervalBusUnits == 2U);
    assert(profile.outputServicePeriodNanoseconds == 2'000'000U);

    constexpr std::array<uint8_t, 3> payload { 0x00, 0x00, 0x0C };
    const auto decoded = neri::usb::feedback::decodeFeedbackSample(
        profile.decodeProfile,
        FeedbackDecodeInput { payload.data(), payload.size(), 2'000'000, 1 }
    );
    assert(decoded.status == FeedbackMathStatus::Ok);
    assert(decoded.sample.normalized.rateQ32 == UINT64_C(96) * (UINT64_C(1) << 32));
}

void rejectsMalformedProfilesAndReservedBits() {
    const EndpointSnapshot output = endpoint(0x06, 0x05, 512, 2);
    EndpointSnapshot feedback = endpoint(0x86, 0x11, 3, 3);
    auto profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::High,
        output,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::PayloadCapacityInsufficient);

    feedback = endpoint(0x86, 0x11, 4, 0);
    profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::High,
        output,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::InvalidEndpointDescriptor);

    feedback = endpoint(0x86, 0x11, 4, 3);
    profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::Super,
        output,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::UnsupportedBusSpeed);

    profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::High,
        output,
        feedback
    );
    constexpr std::array<uint8_t, 4> reservedBits { 0x00, 0x00, 0x06, 0xF0 };
    const auto decoded = neri::usb::feedback::decodeFeedbackSample(
        profile.decodeProfile,
        FeedbackDecodeInput { reservedBits.data(), reservedBits.size(), 1, 1 }
    );
    assert(decoded.status == FeedbackMathStatus::OutOfRange);

    EndpointSnapshot legacy = output;
    legacy.descriptorLength = 9;
    legacy.hasRefresh = true;
    profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::High,
        legacy,
        feedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::InvalidEndpointDescriptor);

    EndpointSnapshot fullSpeedOversize = endpoint(0x02, 0x05, 1024, 1);
    const EndpointSnapshot fullSpeedFeedback = endpoint(0x82, 0x11, 3, 1);
    profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        UsbBusSpeed::Full,
        fullSpeedOversize,
        fullSpeedFeedback
    );
    assert(profile.status == Uac2FeedbackProfileStatus::InvalidEndpointDescriptor);
}

void exposesStableNames() {
    assert(std::string(neri::usb::uac2::usbBusSpeedName(UsbBusSpeed::High)) == "high");
    assert(std::string(neri::usb::uac2::uac2FeedbackProfileStatusName(
        Uac2FeedbackProfileStatus::Valid
    )) == "valid");
}

} // namespace

int main() {
    verifiesHighSpeedProfileAndCadence();
    verifiesFullSpeedProfileAndOutputIntervalScaling();
    rejectsMalformedProfilesAndReservedBits();
    exposesStableNames();
    return 0;
}
