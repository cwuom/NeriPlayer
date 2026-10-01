#pragma once

#include "usb/feedback/usb_feedback_decoder.h"
#include "usb/uac2/usb_uac2_feedback_model.h"

#include <cstdint>
#include <string>

namespace neri::usb::uac2 {

enum class UsbBusSpeed {
    Unknown,
    Low,
    Full,
    High,
    Super,
    SuperPlus
};

enum class Uac2FeedbackProfileStatus {
    Valid,
    InvalidInput,
    UnsupportedBusSpeed,
    InvalidEndpointInterval,
    InvalidEndpointDescriptor,
    PayloadCapacityInsufficient,
    ArithmeticOverflow
};

struct Uac2FeedbackTimingProfile {
    Uac2FeedbackProfileStatus status = Uac2FeedbackProfileStatus::InvalidInput;
    UsbBusSpeed busSpeed = UsbBusSpeed::Unknown;
    uint32_t busIntervalsPerSecond = 0;
    uint32_t busIntervalNanoseconds = 0;
    uint32_t outputServiceIntervalBusUnits = 0;
    uint32_t feedbackPollingIntervalBusUnits = 0;
    uint64_t outputServicePeriodNanoseconds = 0;
    uint64_t feedbackExpectedPeriodNanoseconds = 0;
    bool zeroLengthReportPermitted = false;
    feedback::FeedbackDecodeProfile decodeProfile;
    FeedbackTimingEvidence evidence;
    std::string reason;
};

bool uac2EndpointPacketSizeValidForSpeed(
    UsbBusSpeed busSpeed,
    uint16_t rawMaxPacketSize
);

Uac2FeedbackTimingProfile buildUac2FeedbackTimingProfile(
    UsbBusSpeed busSpeed,
    const EndpointSnapshot& output,
    const EndpointSnapshot& feedback
);

const char* usbBusSpeedName(UsbBusSpeed speed);
const char* uac2FeedbackProfileStatusName(Uac2FeedbackProfileStatus status);

} // namespace neri::usb::uac2
