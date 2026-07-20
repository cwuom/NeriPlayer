#pragma once

#include "usb/uac2/usb_uac2_feedback_model.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac2 {

enum class Uac2DescriptorSnapshotStatus {
    Valid,
    InvalidInput,
    DescriptorHeaderTruncated,
    DescriptorLengthInvalid,
    DescriptorBodyTruncated,
    ConfigurationDescriptorInvalid,
    InterfaceDescriptorInvalid,
    EndpointDescriptorInvalid,
    EndpointCountMismatch,
    DuplicateEndpointIdentity,
    UnsupportedBusSpeed,
    Uac2StreamingInterfaceMissing
};

struct Uac2StreamingInterfaceSnapshot {
    int interfaceNumber = -1;
    int alternateSetting = -1;
    uint8_t advertisedEndpointCount = 0;
    uint8_t observedEndpointCount = 0;
};

struct Uac2DescriptorSnapshotResult {
    Uac2DescriptorSnapshotStatus status =
        Uac2DescriptorSnapshotStatus::InvalidInput;
    ConfigurationSnapshot configuration;
    std::vector<Uac2StreamingInterfaceSnapshot> streamingInterfaces;
    size_t consumedBytes = 0;
    std::string reason;
};

Uac2DescriptorSnapshotResult buildUac2DescriptorSnapshot(
    const uint8_t* descriptors,
    size_t descriptorBytes,
    UsbBusSpeed busSpeed
);

const char* uac2DescriptorSnapshotStatusName(
    Uac2DescriptorSnapshotStatus status
);

} // namespace neri::usb::uac2
