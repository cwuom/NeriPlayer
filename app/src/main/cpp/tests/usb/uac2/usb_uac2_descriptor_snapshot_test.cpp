#include "usb/uac2/usb_uac2_descriptor_snapshot.h"

#include <cassert>
#include <cstdint>
#include <string>
#include <vector>

namespace {

using neri::usb::uac2::UsbBusSpeed;
using neri::usb::uac2::Uac2DescriptorSnapshotStatus;

std::vector<uint8_t> syntheticExplicitFeedbackDescriptor() {
    std::vector<uint8_t> bytes {
        9, 0x02, 50, 0, 2, 7, 0, 0x80, 37,
        9, 0x04, 5, 0, 0, 1, 1, 0, 0,
        9, 0x04, 9, 0, 0, 1, 2, 0x20, 0,
        9, 0x04, 9, 5, 2, 1, 2, 0x20, 0,
        7, 0x05, 0x06, 0x05, 0x00, 0x02, 2,
        7, 0x05, 0x86, 0x11, 0x04, 0x00, 3
    };
    return bytes;
}

void buildsHighSpeedSnapshot() {
    const auto bytes = syntheticExplicitFeedbackDescriptor();
    const auto result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(),
        bytes.size(),
        UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::Valid);
    assert(result.consumedBytes == bytes.size());
    assert(result.configuration.configurationValue == 7);
    assert(result.configuration.endpoints.size() == 2U);
    assert(result.streamingInterfaces.size() == 2U);
    assert(result.streamingInterfaces[1].observedEndpointCount == 2U);
    assert(result.configuration.endpoints[0].endpointAddress == 0x06U);
    assert(result.configuration.endpoints[0].effectiveMaxPacketBytes == 512);
    assert(result.configuration.endpoints[1].endpointAddress == 0x86U);
    assert(result.configuration.endpoints[1].effectiveMaxPacketBytes == 4);
    assert(!result.configuration.endpoints[0].hasSynchAddress);
    assert(!result.configuration.endpoints[1].hasRefresh);
}

void rejectsMalformedDescriptorShapes() {
    auto bytes = syntheticExplicitFeedbackDescriptor();
    bytes[2] = 49;
    auto result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::DescriptorBodyTruncated);

    bytes = syntheticExplicitFeedbackDescriptor();
    bytes[9 + 9 + 9 + 9] = 9;
    result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid);

    bytes = syntheticExplicitFeedbackDescriptor();
    bytes[31] = 1;
    result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::EndpointCountMismatch);

    bytes = syntheticExplicitFeedbackDescriptor();
    result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::Super
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::UnsupportedBusSpeed);
}

void rejectsNonUac2AndFullSpeedTransactionBits() {
    auto bytes = syntheticExplicitFeedbackDescriptor();
    bytes[25] = 0x10;
    bytes[34] = 0x10;
    auto result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::Uac2StreamingInterfaceMissing);

    bytes = syntheticExplicitFeedbackDescriptor();
    bytes[4 + 9 + 9 + 9 + 9] = 0x00;
    bytes[5 + 9 + 9 + 9 + 9] = 0x0E;
    result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(), bytes.size(), UsbBusSpeed::Full
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid);
}

void rejectsIllegalHighBandwidthPacketEncoding() {
    auto bytes = syntheticExplicitFeedbackDescriptor();
    bytes[40] = 0x01;
    bytes[41] = 0x08;
    const auto result = neri::usb::uac2::buildUac2DescriptorSnapshot(
        bytes.data(),
        bytes.size(),
        UsbBusSpeed::High
    );
    assert(result.status == Uac2DescriptorSnapshotStatus::EndpointDescriptorInvalid);
}

void exposesStableNames() {
    assert(std::string(neri::usb::uac2::uac2DescriptorSnapshotStatusName(
        Uac2DescriptorSnapshotStatus::Valid
    )) == "valid");
}

} // namespace

int main() {
    buildsHighSpeedSnapshot();
    rejectsMalformedDescriptorShapes();
    rejectsNonUac2AndFullSpeedTransactionBits();
    rejectsIllegalHighBandwidthPacketEncoding();
    exposesStableNames();
    return 0;
}
