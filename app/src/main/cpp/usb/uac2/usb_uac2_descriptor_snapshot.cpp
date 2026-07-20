#include "usb/uac2/usb_uac2_descriptor_snapshot.h"

#include <limits>

namespace neri::usb::uac2 {
namespace {

constexpr uint8_t kConfigurationDescriptorType = 0x02;
constexpr uint8_t kInterfaceDescriptorType = 0x04;
constexpr uint8_t kEndpointDescriptorType = 0x05;
constexpr uint8_t kAudioClass = 0x01;
constexpr uint8_t kAudioStreamingSubclass = 0x02;
constexpr uint8_t kUac2Protocol = 0x20;
constexpr uint8_t kConfigurationDescriptorLength = 9;
constexpr uint8_t kInterfaceDescriptorLength = 9;
constexpr uint8_t kEndpointDescriptorLength = 7;

uint16_t readLe16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0]) |
        static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8U);
}

Uac2DescriptorSnapshotResult fail(
    Uac2DescriptorSnapshotStatus status,
    const char* reason,
    size_t consumedBytes = 0
) {
    Uac2DescriptorSnapshotResult result;
    result.status = status;
    result.reason = reason;
    result.consumedBytes = consumedBytes;
    return result;
}

bool supportedSpeed(UsbBusSpeed speed) {
    return speed == UsbBusSpeed::Full || speed == UsbBusSpeed::High;
}

bool sameIdentity(const EndpointSnapshot& left, const EndpointSnapshot& right) {
    return left.configurationValue == right.configurationValue &&
        left.interfaceNumber == right.interfaceNumber &&
        left.alternateSetting == right.alternateSetting &&
        left.endpointAddress == right.endpointAddress;
}

bool capacityFor(
    UsbBusSpeed speed,
    uint16_t rawMaxPacketSize,
    int* output
) {
    if (output == nullptr) {
        return false;
    }
    const uint16_t payloadBytes = rawMaxPacketSize & 0x07FFU;
    const auto transactionBits =
        static_cast<uint16_t>((rawMaxPacketSize >> 11U) & 0x03U);
    if (!uac2EndpointPacketSizeValidForSpeed(speed, rawMaxPacketSize)) {
        return false;
    }
    if (speed == UsbBusSpeed::Full) {
        *output = static_cast<int>(payloadBytes);
        return true;
    }
    if (speed != UsbBusSpeed::High) {
        return false;
    }
    const int transactions = 1 + static_cast<int>(transactionBits);
    if (payloadBytes >
        static_cast<uint16_t>(std::numeric_limits<int>::max() / transactions)) {
        return false;
    }
    *output = static_cast<int>(payloadBytes) * transactions;
    return true;
}

bool hasDuplicateEndpoint(const ConfigurationSnapshot& configuration) {
    for (size_t left = 0; left < configuration.endpoints.size(); ++left) {
        for (size_t right = left + 1; right < configuration.endpoints.size(); ++right) {
            if (sameIdentity(
                    configuration.endpoints[left],
                    configuration.endpoints[right]
                )) {
                return true;
            }
        }
    }
    return false;
}

} // namespace

Uac2DescriptorSnapshotResult buildUac2DescriptorSnapshot(
    const uint8_t* descriptors,
    size_t descriptorBytes,
    UsbBusSpeed busSpeed
) {
    if (descriptors == nullptr || descriptorBytes < kConfigurationDescriptorLength) {
        return fail(
            Uac2DescriptorSnapshotStatus::InvalidInput,
            "uac2_descriptor_input_invalid"
        );
    }
    if (!supportedSpeed(busSpeed)) {
        return fail(
            Uac2DescriptorSnapshotStatus::UnsupportedBusSpeed,
            "uac2_descriptor_bus_speed_unsupported"
        );
    }
    if (descriptors[0] != kConfigurationDescriptorLength ||
        descriptors[1] != kConfigurationDescriptorType) {
        return fail(
            Uac2DescriptorSnapshotStatus::ConfigurationDescriptorInvalid,
            "configuration_descriptor_invalid"
        );
    }
    const size_t totalLength = readLe16(descriptors + 2);
    if (totalLength < kConfigurationDescriptorLength || totalLength > descriptorBytes) {
        return fail(
            Uac2DescriptorSnapshotStatus::DescriptorBodyTruncated,
            "configuration_total_length_truncated"
        );
    }
    const int configurationValue = descriptors[5];
    if (configurationValue <= 0) {
        return fail(
            Uac2DescriptorSnapshotStatus::ConfigurationDescriptorInvalid,
            "configuration_value_invalid"
        );
    }

    Uac2DescriptorSnapshotResult result;
    result.configuration.configurationValue = configurationValue;
    int activeStreamingInterface = -1;
    size_t offset = 0;
    while (offset < totalLength) {
        if (totalLength - offset < 2U) {
            return fail(
                Uac2DescriptorSnapshotStatus::DescriptorHeaderTruncated,
                "descriptor_header_truncated",
                offset
            );
        }
        const uint8_t length = descriptors[offset];
        const uint8_t type = descriptors[offset + 1U];
        if (length < 2U) {
            return fail(
                Uac2DescriptorSnapshotStatus::DescriptorLengthInvalid,
                "descriptor_length_invalid",
                offset
            );
        }
        if (length > totalLength - offset) {
            return fail(
                Uac2DescriptorSnapshotStatus::DescriptorBodyTruncated,
                "descriptor_body_truncated",
                offset
            );
        }
        const uint8_t* descriptor = descriptors + offset;
        if (type == kInterfaceDescriptorType) {
            activeStreamingInterface = -1;
            if (length < kInterfaceDescriptorLength) {
                return fail(
                    Uac2DescriptorSnapshotStatus::InterfaceDescriptorInvalid,
                    "interface_descriptor_too_short",
                    offset
                );
            }
            if (descriptor[5] == kAudioClass &&
                descriptor[6] == kAudioStreamingSubclass &&
                descriptor[7] == kUac2Protocol) {
                result.streamingInterfaces.push_back(
                    Uac2StreamingInterfaceSnapshot {
                        descriptor[2],
                        descriptor[3],
                        descriptor[4],
                        0
                    }
                );
                activeStreamingInterface = static_cast<int>(
                    result.streamingInterfaces.size() - 1U
                );
            }
        } else if (type == kEndpointDescriptorType &&
            activeStreamingInterface >= 0) {
            if (length != kEndpointDescriptorLength) {
                return fail(
                    Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid,
                    "uac2_endpoint_descriptor_length_invalid",
                    offset
                );
            }
            Uac2StreamingInterfaceSnapshot& interface =
                result.streamingInterfaces[static_cast<size_t>(activeStreamingInterface)];
            if (interface.observedEndpointCount ==
                std::numeric_limits<uint8_t>::max()) {
                return fail(
                    Uac2DescriptorSnapshotStatus::EndpointCountMismatch,
                    "endpoint_count_overflow",
                    offset
                );
            }
            ++interface.observedEndpointCount;

            const uint16_t rawMaxPacketSize = readLe16(descriptor + 4);
            int effectiveMaxPacketBytes = 0;
            if (!capacityFor(busSpeed, rawMaxPacketSize, &effectiveMaxPacketBytes) ||
                descriptor[6] < 1U || descriptor[6] > 16U) {
                return fail(
                    Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid,
                    "uac2_endpoint_fields_invalid",
                    offset
                );
            }
            EndpointSnapshot endpoint;
            endpoint.configurationValue = configurationValue;
            endpoint.interfaceNumber = interface.interfaceNumber;
            endpoint.alternateSetting = interface.alternateSetting;
            endpoint.endpointAddress = descriptor[2];
            endpoint.descriptorLength = length;
            endpoint.descriptorType = type;
            endpoint.bmAttributes = descriptor[3];
            endpoint.rawMaxPacketSize = rawMaxPacketSize;
            endpoint.effectiveMaxPacketBytes = effectiveMaxPacketBytes;
            endpoint.effectiveCapacityKnown = true;
            endpoint.capacitySource = EndpointCapacitySource::StandardDescriptor;
            endpoint.bInterval = descriptor[6];
            result.configuration.endpoints.push_back(endpoint);
        }
        offset += length;
    }

    if (result.streamingInterfaces.empty()) {
        return fail(
            Uac2DescriptorSnapshotStatus::Uac2StreamingInterfaceMissing,
            "uac2_streaming_interface_missing",
            offset
        );
    }
    for (const Uac2StreamingInterfaceSnapshot& interface :
        result.streamingInterfaces) {
        if (interface.advertisedEndpointCount != interface.observedEndpointCount) {
            return fail(
                Uac2DescriptorSnapshotStatus::EndpointCountMismatch,
                "uac2_streaming_endpoint_count_mismatch",
                offset
            );
        }
    }
    if (hasDuplicateEndpoint(result.configuration)) {
        return fail(
            Uac2DescriptorSnapshotStatus::DuplicateEndpointIdentity,
            "uac2_endpoint_identity_duplicate",
            offset
        );
    }

    result.status = Uac2DescriptorSnapshotStatus::Valid;
    result.consumedBytes = offset;
    return result;
}

const char* uac2DescriptorSnapshotStatusName(
    Uac2DescriptorSnapshotStatus status
) {
    switch (status) {
        case Uac2DescriptorSnapshotStatus::Valid:
            return "valid";
        case Uac2DescriptorSnapshotStatus::InvalidInput:
            return "invalid_input";
        case Uac2DescriptorSnapshotStatus::DescriptorHeaderTruncated:
            return "descriptor_header_truncated";
        case Uac2DescriptorSnapshotStatus::DescriptorLengthInvalid:
            return "descriptor_length_invalid";
        case Uac2DescriptorSnapshotStatus::DescriptorBodyTruncated:
            return "descriptor_body_truncated";
        case Uac2DescriptorSnapshotStatus::ConfigurationDescriptorInvalid:
            return "configuration_descriptor_invalid";
        case Uac2DescriptorSnapshotStatus::InterfaceDescriptorInvalid:
            return "interface_descriptor_invalid";
        case Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid:
            return "endpoint_descriptor_invalid";
        case Uac2DescriptorSnapshotStatus::EndpointCountMismatch:
            return "endpoint_count_mismatch";
        case Uac2DescriptorSnapshotStatus::DuplicateEndpointIdentity:
            return "duplicate_endpoint_identity";
        case Uac2DescriptorSnapshotStatus::UnsupportedBusSpeed:
            return "unsupported_bus_speed";
        case Uac2DescriptorSnapshotStatus::Uac2StreamingInterfaceMissing:
            return "uac2_streaming_interface_missing";
    }
    return "unknown";
}

} // namespace neri::usb::uac2
