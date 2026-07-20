#include "usb/uac2/usb_uac2_feedback_profile.h"

#include <limits>
#include <sstream>

namespace neri::usb::uac2 {
namespace {

constexpr uint8_t kMinimumInterval = 1;
constexpr uint8_t kMaximumInterval = 16;
constexpr uint8_t kFullSpeedPayloadBytes = 3;
constexpr uint8_t kFullSpeedFractionalBits = 14;
constexpr uint8_t kHighSpeedPayloadBytes = 4;
constexpr uint8_t kHighSpeedFractionalBits = 16;
constexpr uint64_t kHighSpeedReservedValueMask = UINT64_C(0xF0000000);

struct SpeedProfile {
    uint32_t intervalsPerSecond = 0;
    uint32_t intervalNanoseconds = 0;
    uint8_t payloadBytes = 0;
    uint8_t fractionalBits = 0;
    uint64_t requiredZeroMask = 0;
    feedback::FeedbackRawUnit rawUnit = feedback::FeedbackRawUnit::Unknown;
};

bool speedProfileFor(UsbBusSpeed speed, SpeedProfile* output) {
    if (output == nullptr) {
        return false;
    }
    switch (speed) {
        case UsbBusSpeed::Full:
            *output = SpeedProfile {
                1000,
                1'000'000,
                kFullSpeedPayloadBytes,
                kFullSpeedFractionalBits,
                0,
                feedback::FeedbackRawUnit::FramesPerBusFrame
            };
            return true;
        case UsbBusSpeed::High:
            *output = SpeedProfile {
                8000,
                125'000,
                kHighSpeedPayloadBytes,
                kHighSpeedFractionalBits,
                kHighSpeedReservedValueMask,
                feedback::FeedbackRawUnit::FramesPerMicroframe
            };
            return true;
        case UsbBusSpeed::Unknown:
        case UsbBusSpeed::Low:
        case UsbBusSpeed::Super:
        case UsbBusSpeed::SuperPlus:
            return false;
    }
    return false;
}

bool intervalMultiplier(uint8_t bInterval, uint32_t* output) {
    if (output == nullptr || bInterval < kMinimumInterval ||
        bInterval > kMaximumInterval) {
        return false;
    }
    *output = UINT32_C(1) << (bInterval - 1U);
    return true;
}

Uac2FeedbackTimingProfile fail(
    Uac2FeedbackProfileStatus status,
    const char* reason
) {
    Uac2FeedbackTimingProfile result;
    result.status = status;
    result.reason = reason;
    return result;
}

bool multiplyPeriod(
    uint32_t intervalNanoseconds,
    uint32_t multiplier,
    uint64_t* output
) {
    if (output == nullptr || intervalNanoseconds == 0 || multiplier == 0) {
        return false;
    }
    if (multiplier >
        std::numeric_limits<uint64_t>::max() / intervalNanoseconds) {
        return false;
    }
    *output = static_cast<uint64_t>(intervalNanoseconds) * multiplier;
    return true;
}

std::string profileId(
    UsbBusSpeed speed,
    const EndpointSnapshot& output,
    const EndpointSnapshot& feedback,
    uint8_t payloadBytes,
    uint8_t fractionalBits
) {
    std::ostringstream stream;
    stream << "uac2-" << usbBusSpeedName(speed)
           << "-q" << static_cast<unsigned>(fractionalBits)
           << "-p" << static_cast<unsigned>(payloadBytes)
           << "-out" << static_cast<unsigned>(output.endpointAddress)
           << "i" << static_cast<unsigned>(output.bInterval)
           << "-fb" << static_cast<unsigned>(feedback.endpointAddress)
           << "i" << static_cast<unsigned>(feedback.bInterval);
    return stream.str();
}

} // namespace

bool uac2EndpointPacketSizeValidForSpeed(
    UsbBusSpeed busSpeed,
    uint16_t rawMaxPacketSize
) {
    const uint16_t payloadBytes = rawMaxPacketSize & 0x07FFU;
    const auto transactionBits =
        static_cast<uint16_t>((rawMaxPacketSize >> 11U) & 0x03U);
    if (payloadBytes == 0 || (rawMaxPacketSize & 0xE000U) != 0) {
        return false;
    }
    if (busSpeed == UsbBusSpeed::Full) {
        return transactionBits == 0 && payloadBytes <= 1023U;
    }
    if (busSpeed != UsbBusSpeed::High || payloadBytes > 1024U) {
        return false;
    }
    switch (transactionBits) {
        case 0:
            return true;
        case 1:
            return payloadBytes >= 513U;
        case 2:
            return payloadBytes >= 683U;
        case 3:
            return false;
    }
    return false;
}

Uac2FeedbackTimingProfile buildUac2FeedbackTimingProfile(
    UsbBusSpeed busSpeed,
    const EndpointSnapshot& output,
    const EndpointSnapshot& feedbackEndpoint
) {
    SpeedProfile speed;
    if (!speedProfileFor(busSpeed, &speed)) {
        return fail(
            Uac2FeedbackProfileStatus::UnsupportedBusSpeed,
            "uac2_feedback_bus_speed_unsupported"
        );
    }
    if (!uac2EndpointPacketSizeValidForSpeed(
            busSpeed,
            output.rawMaxPacketSize
        ) || !uac2EndpointPacketSizeValidForSpeed(
            busSpeed,
            feedbackEndpoint.rawMaxPacketSize
        )) {
        return fail(
            Uac2FeedbackProfileStatus::InvalidEndpointDescriptor,
            "uac2_endpoint_packet_size_invalid_for_speed"
        );
    }

    std::string endpointReason;
    FeedbackResolverPolicy outputPolicy;
    outputPolicy.requireAsynchronousOutput = true;
    if (validateOutputEndpointSnapshot(output, outputPolicy, &endpointReason) !=
        DescriptorValidationStatus::Valid) {
        return fail(
            Uac2FeedbackProfileStatus::InvalidEndpointDescriptor,
            endpointReason.empty()
                ? "uac2_output_endpoint_invalid"
                : endpointReason.c_str()
        );
    }
    if (validateFeedbackEndpointSnapshot(feedbackEndpoint, &endpointReason) !=
        DescriptorValidationStatus::Valid) {
        return fail(
            Uac2FeedbackProfileStatus::InvalidEndpointDescriptor,
            endpointReason.empty()
                ? "uac2_feedback_endpoint_invalid"
                : endpointReason.c_str()
        );
    }

    uint32_t outputMultiplier = 0;
    uint32_t feedbackMultiplier = 0;
    if (!intervalMultiplier(output.bInterval, &outputMultiplier) ||
        !intervalMultiplier(feedbackEndpoint.bInterval, &feedbackMultiplier)) {
        return fail(
            Uac2FeedbackProfileStatus::InvalidEndpointInterval,
            "uac2_feedback_interval_invalid"
        );
    }
    if (!feedbackEndpoint.effectiveCapacityKnown ||
        feedbackEndpoint.effectiveMaxPacketBytes < speed.payloadBytes) {
        return fail(
            Uac2FeedbackProfileStatus::PayloadCapacityInsufficient,
            "uac2_feedback_payload_capacity_insufficient"
        );
    }

    uint64_t outputPeriodNs = 0;
    uint64_t feedbackPeriodNs = 0;
    if (!multiplyPeriod(
            speed.intervalNanoseconds,
            outputMultiplier,
            &outputPeriodNs
        ) || !multiplyPeriod(
            speed.intervalNanoseconds,
            feedbackMultiplier,
            &feedbackPeriodNs
        )) {
        return fail(
            Uac2FeedbackProfileStatus::ArithmeticOverflow,
            "uac2_feedback_period_overflow"
        );
    }

    Uac2FeedbackTimingProfile result;
    result.status = Uac2FeedbackProfileStatus::Valid;
    result.busSpeed = busSpeed;
    result.busIntervalsPerSecond = speed.intervalsPerSecond;
    result.busIntervalNanoseconds = speed.intervalNanoseconds;
    result.outputServiceIntervalBusUnits = outputMultiplier;
    result.feedbackPollingIntervalBusUnits = feedbackMultiplier;
    result.outputServicePeriodNanoseconds = outputPeriodNs;
    result.feedbackExpectedPeriodNanoseconds = feedbackPeriodNs;
    result.zeroLengthReportPermitted = true;
    result.decodeProfile = feedback::FeedbackDecodeProfile {
        speed.payloadBytes,
        speed.fractionalBits,
        speed.rawUnit,
        speed.intervalsPerSecond,
        speed.intervalsPerSecond,
        outputMultiplier,
        1,
        speed.requiredZeroMask
    };
    result.evidence = FeedbackTimingEvidence {
        true,
        output.endpointAddress,
        feedbackEndpoint.endpointAddress,
        output.bInterval,
        feedbackEndpoint.bInterval,
        speed.payloadBytes,
        feedbackPeriodNs,
        profileId(
            busSpeed,
            output,
            feedbackEndpoint,
            speed.payloadBytes,
            speed.fractionalBits
        )
    };
    return result;
}

const char* usbBusSpeedName(UsbBusSpeed speed) {
    switch (speed) {
        case UsbBusSpeed::Unknown:
            return "unknown";
        case UsbBusSpeed::Low:
            return "low";
        case UsbBusSpeed::Full:
            return "full";
        case UsbBusSpeed::High:
            return "high";
        case UsbBusSpeed::Super:
            return "super";
        case UsbBusSpeed::SuperPlus:
            return "super_plus";
    }
    return "unknown";
}

const char* uac2FeedbackProfileStatusName(Uac2FeedbackProfileStatus status) {
    switch (status) {
        case Uac2FeedbackProfileStatus::Valid:
            return "valid";
        case Uac2FeedbackProfileStatus::InvalidInput:
            return "invalid_input";
        case Uac2FeedbackProfileStatus::UnsupportedBusSpeed:
            return "unsupported_bus_speed";
        case Uac2FeedbackProfileStatus::InvalidEndpointInterval:
            return "invalid_endpoint_interval";
        case Uac2FeedbackProfileStatus::InvalidEndpointDescriptor:
            return "invalid_endpoint_descriptor";
        case Uac2FeedbackProfileStatus::PayloadCapacityInsufficient:
            return "payload_capacity_insufficient";
        case Uac2FeedbackProfileStatus::ArithmeticOverflow:
            return "arithmetic_overflow";
    }
    return "unknown";
}

} // namespace neri::usb::uac2
