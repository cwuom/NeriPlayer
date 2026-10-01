#pragma once

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include "libusb/libusb.h"
#include "usb/uac1/usb_uac1_format.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"
#include "usb/uac2/usb_uac2_format.h"

namespace neri::usb::exclusive {

struct ClaimedUsbInterface {
    int interfaceNumber = -1;
    uint8_t subclass = 0;
};
struct StreamingAltSelection {
    int interfaceNumber = -1;
    int alternateSetting = -1;
    int audioControlInterface = -1;
    uint8_t outEndpoint = 0;
    int endpointMaxPacketBytes = 0;
    int endpointInterval = 0;
    bool explicitFeedbackEnabled = false;
    uint8_t feedbackEndpoint = 0;
    int feedbackEndpointMaxPacketBytes = 0;
    int feedbackEndpointInterval = 0;
    neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
    int score = std::numeric_limits<int>::min();
    int uacVersion = 0;
    struct Uac1Details {
        neri::usb::uac1::TypeIFormat format;
        neri::usb::uac1::EndpointControls endpointControls;
    } uac1;
    struct Uac2Details {
        neri::usb::uac2::TypeIFormat format;
        int clockSourceId = 0;
        neri::usb::uac2::ControlCapability sampleRateControl =
            neri::usb::uac2::ControlCapability::None;
    } uac2;
    std::string syncType = "none";
    std::string feedback = "none";
    std::string reason = "none";
    std::vector<ClaimedUsbInterface> claimPlan;
    bool completeClaimPlan = false;
};
uint8_t makeClassEndpointRequestType(uint8_t direction);
uint8_t makeClassInterfaceRequestType(uint8_t direction);
uint16_t makeClockEntityIndex(int clockSourceId, int interfaceNumber);
bool readUac2CurrentSampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int* currentSampleRate,
    std::string* status
);
bool sameClaimPlan(
    const std::vector<ClaimedUsbInterface>& current,
    const std::vector<ClaimedUsbInterface>& requested
);
int computeIntervalsPerSecond(int usbSpeed, int interval);
int computeMaxPacketBytes(
    int sampleRate,
    int intervalsPerSecond,
    int frameBytes,
    int endpointMaxPacketBytes
);
bool findStreamingAlt(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
);

} // namespace neri::usb::exclusive
