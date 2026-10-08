#include "usb/exclusive/usb_exclusive_device_selection.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include "usb/exclusive/usb_streaming_sync_policy.h"
#include "usb/feedback/usb_feedback_rate_math.h"
#include "usb/iso/usb_iso_transfer_window.h"
#include "usb/uac2/usb_uac2_clock_graph.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

constexpr int kUsbSubclassAudioStreaming = 0x02;
constexpr int kUsbSubclassAudioControl = 0x01;
constexpr int kUsbAudioProtocolUac1 = 0x00;
constexpr int kUsbAudioProtocolUac2 = 0x20;
constexpr int kUsbDescriptorTypeClassSpecificInterface = 0x24;
constexpr int kUsbAudioControlHeaderSubtype = 0x01;
constexpr int kUsbTransferTypeIsochronous = 0x01;
constexpr auto kLibusbEndpointOut =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_OUT);
constexpr auto kLibusbEndpointIn =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_IN);
constexpr auto kLibusbRequestTypeClass =
    static_cast<unsigned int>(LIBUSB_REQUEST_TYPE_CLASS);
constexpr auto kLibusbRecipientEndpoint =
    static_cast<unsigned int>(LIBUSB_RECIPIENT_ENDPOINT);
constexpr auto kLibusbRecipientInterface =
    static_cast<unsigned int>(LIBUSB_RECIPIENT_INTERFACE);
constexpr auto kLibusbIsoUsageFeedback =
    static_cast<int>(LIBUSB_ISO_USAGE_TYPE_FEEDBACK);
constexpr auto kLibusbIsoUsageImplicit =
    static_cast<int>(LIBUSB_ISO_USAGE_TYPE_IMPLICIT);
constexpr auto kLibusbIsoUsageData =
    static_cast<int>(LIBUSB_ISO_USAGE_TYPE_DATA);
constexpr uint8_t kUac1AudioEndpointDescriptorLength = 9;
constexpr auto kLibusbIsoSyncTypeAdaptive =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_ADAPTIVE);
constexpr auto kLibusbIsoSyncTypeSynchronous =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_SYNC);
constexpr auto kLibusbIsoSyncTypeAsynchronous =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_ASYNC);
static const char* libusbErrName(int rc) {
    return libusb_error_name(rc);
}

bool isIsoOutEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = static_cast<uint8_t>(
        endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK
    );
    const auto transferType = static_cast<uint8_t>(
        endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK
    );
    return direction == kLibusbEndpointOut &&
        transferType == static_cast<uint8_t>(kUsbTransferTypeIsochronous);
}

bool isIsoInEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = static_cast<uint8_t>(
        endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK
    );
    const auto transferType = static_cast<uint8_t>(
        endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK
    );
    return direction == kLibusbEndpointIn &&
        transferType == static_cast<uint8_t>(kUsbTransferTypeIsochronous);
}

int usbIsoUsageType(uint8_t endpointAttributes) {
    return static_cast<int>((endpointAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) >> 4);
}

int usbIsoSyncType(uint8_t endpointAttributes) {
    return static_cast<int>((endpointAttributes & LIBUSB_ISO_SYNC_TYPE_MASK) >> 2);
}

uint8_t makeClassEndpointRequestType(uint8_t direction) {
    return static_cast<uint8_t>(
        static_cast<unsigned int>(direction) |
        kLibusbRequestTypeClass |
        kLibusbRecipientEndpoint
    );
}

uint8_t makeClassInterfaceRequestType(uint8_t direction) {
    return static_cast<uint8_t>(
        static_cast<unsigned int>(direction) |
        kLibusbRequestTypeClass |
        kLibusbRecipientInterface
    );
}

uint16_t makeClockEntityIndex(int clockSourceId, int interfaceNumber) {
    return static_cast<uint16_t>(
        ((clockSourceId & 0xFF) << 8) |
        (interfaceNumber & 0xFF)
    );
}

bool sameClaimPlan(
    const std::vector<ClaimedUsbInterface>& current,
    const std::vector<ClaimedUsbInterface>& requested
) {
    if (current.size() != requested.size()) {
        return false;
    }
    for (size_t index = 0; index < current.size(); ++index) {
        if (current[index].interfaceNumber != requested[index].interfaceNumber ||
            current[index].subclass != requested[index].subclass) {
            return false;
        }
    }
    return true;
}

void appendClaimPlanInterface(
    std::vector<ClaimedUsbInterface>* plan,
    int interfaceNumber,
    uint8_t subclass
) {
    if (plan == nullptr || interfaceNumber < 0) {
        return;
    }
    const auto existing = std::find_if(
        plan->begin(),
        plan->end(),
        [interfaceNumber](const ClaimedUsbInterface& entry) {
            return entry.interfaceNumber == interfaceNumber;
        }
    );
    if (existing == plan->end()) {
        plan->push_back(ClaimedUsbInterface { interfaceNumber, subclass });
    }
}

bool isAudioStreamingInterface(
    const libusb_config_descriptor* config,
    int interfaceNumber
) {
    if (config == nullptr || interfaceNumber < 0) {
        return false;
    }
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceNumber == interfaceNumber &&
                alt.bInterfaceClass == LIBUSB_CLASS_AUDIO &&
                alt.bInterfaceSubClass == kUsbSubclassAudioStreaming) {
                return true;
            }
        }
    }
    return false;
}

void sortAudioClaimPlan(
    std::vector<ClaimedUsbInterface>* plan,
    int selectedStreamingInterface
) {
    if (plan == nullptr) {
        return;
    }
    const auto priority = [selectedStreamingInterface](
        const ClaimedUsbInterface& entry
    ) {
        if (entry.subclass == kUsbSubclassAudioControl) {
            return 0;
        }
        return entry.interfaceNumber == selectedStreamingInterface ? 2 : 1;
    };
    std::stable_sort(
        plan->begin(),
        plan->end(),
        [&priority](
            const ClaimedUsbInterface& left,
            const ClaimedUsbInterface& right
        ) {
            if (priority(left) != priority(right)) {
                return priority(left) < priority(right);
            }
            return left.interfaceNumber < right.interfaceNumber;
        }
    );
}

bool buildAudioFunctionClaimPlan(
    const libusb_config_descriptor* config,
    int selectedStreamingInterface,
    std::vector<ClaimedUsbInterface>* plan
) {
    if (config == nullptr || plan == nullptr) {
        return false;
    }
    plan->clear();
    std::vector<int> audioControlInterfaces;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioControl) {
                continue;
            }
            if (std::find(
                    audioControlInterfaces.begin(),
                    audioControlInterfaces.end(),
                    alt.bInterfaceNumber
                ) == audioControlInterfaces.end()) {
                audioControlInterfaces.push_back(alt.bInterfaceNumber);
            }
            if (alt.extra == nullptr || alt.extra_length < 8) {
                continue;
            }
            const unsigned char* cursor = alt.extra;
            int remaining = alt.extra_length;
            while (remaining >= 3) {
                const int descriptorLength = cursor[0];
                if (descriptorLength < 3 || descriptorLength > remaining) {
                    break;
                }
                if (cursor[1] == kUsbDescriptorTypeClassSpecificInterface &&
                    cursor[2] == kUsbAudioControlHeaderSubtype &&
                    descriptorLength >= 8) {
                    const int collectionCount = cursor[7];
                    if (descriptorLength >= 8 + collectionCount) {
                        bool containsSelected = false;
                        for (int index = 0; index < collectionCount; ++index) {
                            if (cursor[8 + index] == selectedStreamingInterface) {
                                containsSelected = true;
                                break;
                            }
                        }
                        if (containsSelected) {
                            appendClaimPlanInterface(
                                plan,
                                alt.bInterfaceNumber,
                                kUsbSubclassAudioControl
                            );
                            for (int index = 0; index < collectionCount; ++index) {
                                const int streamingInterface = cursor[8 + index];
                                if (isAudioStreamingInterface(config, streamingInterface)) {
                                    appendClaimPlanInterface(
                                        plan,
                                        streamingInterface,
                                        kUsbSubclassAudioStreaming
                                    );
                                }
                            }
                            const bool selectedIncluded = std::any_of(
                                plan->begin(),
                                plan->end(),
                                [selectedStreamingInterface](
                                    const ClaimedUsbInterface& entry
                                ) {
                                    return entry.interfaceNumber == selectedStreamingInterface;
                                }
                            );
                            if (selectedIncluded) {
                                sortAudioClaimPlan(plan, selectedStreamingInterface);
                                return true;
                            }
                            plan->clear();
                        }
                    }
                }
                cursor += descriptorLength;
                remaining -= descriptorLength;
            }
        }
    }
    if (audioControlInterfaces.size() == 1) {
        appendClaimPlanInterface(
            plan,
            audioControlInterfaces.front(),
            kUsbSubclassAudioControl
        );
        for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
            const libusb_interface& iface = config->interface[ifaceIndex];
            for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
                const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
                if (alt.bInterfaceClass == LIBUSB_CLASS_AUDIO &&
                    alt.bInterfaceSubClass == kUsbSubclassAudioStreaming) {
                    appendClaimPlanInterface(
                        plan,
                        alt.bInterfaceNumber,
                        kUsbSubclassAudioStreaming
                    );
                }
            }
        }
    }
    appendClaimPlanInterface(
        plan,
        selectedStreamingInterface,
        kUsbSubclassAudioStreaming
    );
    sortAudioClaimPlan(plan, selectedStreamingInterface);
    return false;
}

bool buildMinimalAudioFunctionClaimPlan(
    const libusb_config_descriptor* config,
    int audioControlInterface,
    int selectedStreamingInterface,
    std::vector<ClaimedUsbInterface>* plan
) {
    if (config == nullptr || plan == nullptr || selectedStreamingInterface < 0) {
        return false;
    }
    plan->clear();
    if (audioControlInterface >= 0) {
        appendClaimPlanInterface(
            plan,
            audioControlInterface,
            kUsbSubclassAudioControl
        );
    }
    appendClaimPlanInterface(
        plan,
        selectedStreamingInterface,
        kUsbSubclassAudioStreaming
    );
    sortAudioClaimPlan(plan, selectedStreamingInterface);
    return audioControlInterface >= 0;
}

void appendCandidateRejection(
    std::string* summary,
    int interfaceNumber,
    int alternateSetting,
    const std::string& reason
) {
    if (summary == nullptr || summary->size() >= 768) {
        return;
    }
    if (!summary->empty()) {
        *summary += ';';
    }
    *summary += "iface=" + std::to_string(interfaceNumber) +
        "/alt=" + std::to_string(alternateSetting) + ":" + reason;
}

int endpointPacketCapacity(const libusb_endpoint_descriptor& endpoint) {
    const int payloadBytes = endpoint.wMaxPacketSize & 0x07FF;
    const int transactionBits = (endpoint.wMaxPacketSize >> 11) & 0x03;
    if (transactionBits == 0x03) {
        return 0;
    }
    const int transactions = 1 + transactionBits;
    return payloadBytes * transactions;
}

neri::usb::uac2::UsbBusSpeed uac2BusSpeedFromLibusb(int usbSpeed) {
    switch (usbSpeed) {
        case LIBUSB_SPEED_FULL:
            return neri::usb::uac2::UsbBusSpeed::Full;
        case LIBUSB_SPEED_HIGH:
            return neri::usb::uac2::UsbBusSpeed::High;
        case LIBUSB_SPEED_LOW:
            return neri::usb::uac2::UsbBusSpeed::Low;
        case LIBUSB_SPEED_SUPER:
            return neri::usb::uac2::UsbBusSpeed::Super;
        case LIBUSB_SPEED_SUPER_PLUS:
        case LIBUSB_SPEED_SUPER_PLUS_X2:
            return neri::usb::uac2::UsbBusSpeed::SuperPlus;
        default:
            return neri::usb::uac2::UsbBusSpeed::Unknown;
    }
}

neri::usb::uac2::EndpointSnapshot makeUac2EndpointSnapshot(
    int configurationValue,
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& endpoint,
    int effectiveMaxPacketBytes,
    bool legacyAudioEndpoint = false
) {
    neri::usb::uac2::EndpointSnapshot snapshot;
    snapshot.configurationValue = configurationValue;
    snapshot.interfaceNumber = alt.bInterfaceNumber;
    snapshot.alternateSetting = alt.bAlternateSetting;
    snapshot.endpointAddress = endpoint.bEndpointAddress;
    snapshot.descriptorLength = endpoint.bLength;
    snapshot.descriptorType = endpoint.bDescriptorType;
    snapshot.bmAttributes = endpoint.bmAttributes;
    snapshot.rawMaxPacketSize = endpoint.wMaxPacketSize;
    snapshot.effectiveMaxPacketBytes = effectiveMaxPacketBytes;
    snapshot.effectiveCapacityKnown = effectiveMaxPacketBytes > 0;
    snapshot.capacitySource = effectiveMaxPacketBytes > 0
        ? neri::usb::uac2::EndpointCapacitySource::BackendComputed
        : neri::usb::uac2::EndpointCapacitySource::Unknown;
    snapshot.bInterval = endpoint.bInterval;
    snapshot.bRefresh = 0;
    snapshot.hasRefresh = false;
    snapshot.bSynchAddress = 0;
    snapshot.hasSynchAddress = false;
    if (legacyAudioEndpoint && endpoint.bLength >= kUac1AudioEndpointDescriptorLength) {
        snapshot.bRefresh = endpoint.bRefresh;
        snapshot.hasRefresh = true;
        snapshot.bSynchAddress = endpoint.bSynchAddress;
        snapshot.hasSynchAddress = true;
    }
    return snapshot;
}

/**
 * UAC1 同步端点按 Linux snd-usb-audio 的方式匹配：bSynchAddress 补上 IN 方向位，
 * 用途位为 0 的老设备只有被 bSynchAddress 明确指向时才接受
 */
bool matchesFeedbackEndpoint(
    const libusb_endpoint_descriptor& candidate,
    const libusb_endpoint_descriptor& outputEndpoint,
    bool legacyAudioEndpoints
) {
    if (!isIsoInEndpoint(candidate)) return false;
    const int usage = usbIsoUsageType(candidate.bmAttributes);
    const uint8_t synchAddress = legacyAudioEndpoints
        ? static_cast<uint8_t>(outputEndpoint.bSynchAddress | LIBUSB_ENDPOINT_IN)
        : outputEndpoint.bSynchAddress;
    if (outputEndpoint.bSynchAddress != 0 && candidate.bEndpointAddress != synchAddress) return false;
    if (usage == kLibusbIsoUsageFeedback) return true;
    return legacyAudioEndpoints && usage == kLibusbIsoUsageData && outputEndpoint.bSynchAddress != 0;
}

bool resolveExplicitFeedbackProfile(
    libusb_device* device,
    int configurationValue,
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& outputEndpoint,
    int outputPacketBytes,
    int usbSpeed,
    int uacVersion,
    uint8_t* feedbackEndpointAddress,
    int* feedbackPacketBytes,
    int* feedbackInterval,
    neri::usb::uac2::Uac2FeedbackTimingProfile* timingProfile,
    std::string* failureReason
) {
    if (device == nullptr || feedbackEndpointAddress == nullptr ||
        feedbackPacketBytes == nullptr || feedbackInterval == nullptr ||
        timingProfile == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "feedback_profile_input_invalid";
        }
        return false;
    }

    const bool legacyAudioEndpoints = uacVersion == 1;
    const std::string reasonPrefix = legacyAudioEndpoints ? "uac1" : "uac2";
    const libusb_endpoint_descriptor* feedbackEndpoint = nullptr;
    int matchingEndpoints = 0;
    for (int index = 0; index < alt.bNumEndpoints; ++index) {
        const libusb_endpoint_descriptor& candidate = alt.endpoint[index];
        if (!matchesFeedbackEndpoint(candidate, outputEndpoint, legacyAudioEndpoints)) {
            continue;
        }
        feedbackEndpoint = &candidate;
        ++matchingEndpoints;
    }
    if (feedbackEndpoint == nullptr || matchingEndpoints != 1) {
        if (failureReason != nullptr) {
            *failureReason = reasonPrefix + (matchingEndpoints == 0
                ? "_feedback_endpoint_missing"
                : "_feedback_endpoint_ambiguous");
        }
        return false;
    }

    int resolvedFeedbackPacketBytes = libusb_get_max_alt_packet_size(
        device,
        alt.bInterfaceNumber,
        alt.bAlternateSetting,
        feedbackEndpoint->bEndpointAddress
    );
    if (resolvedFeedbackPacketBytes <= 0) {
        resolvedFeedbackPacketBytes = endpointPacketCapacity(*feedbackEndpoint);
    }
    const auto profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        uac2BusSpeedFromLibusb(usbSpeed),
        makeUac2EndpointSnapshot(
            configurationValue,
            alt,
            outputEndpoint,
            outputPacketBytes,
            legacyAudioEndpoints
        ),
        makeUac2EndpointSnapshot(
            configurationValue,
            alt,
            *feedbackEndpoint,
            resolvedFeedbackPacketBytes,
            legacyAudioEndpoints
        ),
        legacyAudioEndpoints
    );
    if (profile.status != neri::usb::uac2::Uac2FeedbackProfileStatus::Valid) {
        if (failureReason != nullptr) {
            *failureReason = reasonPrefix + "_feedback_profile_" + std::string(
                neri::usb::uac2::uac2FeedbackProfileStatusName(profile.status)
            ) + ":" + profile.reason;
        }
        return false;
    }

    *feedbackEndpointAddress = feedbackEndpoint->bEndpointAddress;
    *feedbackPacketBytes = resolvedFeedbackPacketBytes;
    *feedbackInterval = feedbackEndpoint->bInterval;
    *timingProfile = profile;
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

std::string describeFeedback(
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& outputEndpoint
) {
    if (outputEndpoint.bSynchAddress != 0) {
        char buffer[24];
        snprintf(
            buffer,
            sizeof(buffer),
            "explicit:0x%02X",
            outputEndpoint.bSynchAddress
        );
        return buffer;
    }
    const int outputUsage = usbIsoUsageType(outputEndpoint.bmAttributes);
    if (outputUsage == kLibusbIsoUsageImplicit) {
        return "implicit";
    }
    for (int index = 0; index < alt.bNumEndpoints; ++index) {
        const libusb_endpoint_descriptor& endpoint = alt.endpoint[index];
        const int usage = usbIsoUsageType(endpoint.bmAttributes);
        if (isIsoInEndpoint(endpoint) && usage == kLibusbIsoUsageFeedback) {
            char buffer[24];
            snprintf(buffer, sizeof(buffer), "explicit:0x%02X", endpoint.bEndpointAddress);
            return buffer;
        }
    }
    return "none";
}

struct Uac2ClockPath {
    int audioControlInterface = -1;
    int clockSourceId = 0;
    neri::usb::uac2::ControlCapability sampleRateControl =
        neri::usb::uac2::ControlCapability::None;
};

int readUac2ClockSelectorPin(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int selectorId,
    int pinCount
) {
    if (deviceHandle == nullptr) {
        return -1;
    }
    constexpr uint8_t kCurRequest = 0x01;
    constexpr uint16_t kClockSelectorControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t pin = 0;
    const int result = libusb_control_transfer(
        deviceHandle,
        makeClassInterfaceRequestType(kLibusbEndpointIn),
        kCurRequest,
        kClockSelectorControl,
        makeClockEntityIndex(selectorId, audioControlInterface),
        &pin,
        1,
        kControlTimeoutMs
    );
    if (result != 1 || pin < 1 || pin > pinCount) {
        LOGW(
            "UAC2 clock selector %d pin unreadable rc=%d pin=%u, using pin 1",
            selectorId,
            result,
            static_cast<unsigned int>(pin)
        );
        return -1;
    }
    return pin - 1;
}

// 终端直连时钟源时原样返回；经选择器时按当前输入脚走到最终时钟源；经倍频器的路径换算不出频率，返回 0
int resolveUac2FinalClockId(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int terminalLink,
    int terminalClockId,
    const std::vector<neri::usb::uac2::ClockSource>& clockSources,
    const std::vector<neri::usb::uac2::ClockRouting>& clockRoutings,
    std::string* failureReason
) {
    std::vector<int> sourceIds;
    for (const auto& source : clockSources) {
        sourceIds.push_back(source.id);
    }
    const bool direct = std::find(sourceIds.begin(), sourceIds.end(), terminalClockId) != sourceIds.end();
    if (direct || clockRoutings.empty()) {
        return terminalClockId;
    }
    const auto route = neri::usb::uac2::resolveTerminalClockSource(
        audioControlInterface,
        terminalLink,
        terminalClockId,
        sourceIds,
        clockRoutings,
        [deviceHandle, audioControlInterface](int selectorId, int pinCount) {
            return readUac2ClockSelectorPin(deviceHandle, audioControlInterface, selectorId, pinCount);
        }
    );
    if (route.status != neri::usb::uac2::ClockGraphStatus::Valid) {
        if (failureReason != nullptr) {
            *failureReason = std::string("uac2_clock_route_") +
                neri::usb::uac2::clockGraphStatusName(route.status) + ":" + route.reason;
        }
        return 0;
    }
    LOGI(
        "UAC2 clock routed: terminal=%d entry=%d source=%d hops=%zu",
        terminalLink,
        terminalClockId,
        route.finalClockSourceId,
        route.traversedEntities.size()
    );
    return route.finalClockSourceId;
}

bool findUac2ClockPath(
    libusb_device_handle* deviceHandle,
    const libusb_config_descriptor* config,
    int terminalLink,
    Uac2ClockPath* output,
    std::string* failureReason
) {
    if (config == nullptr || output == nullptr || terminalLink <= 0) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_invalid_clock_path_input";
        }
        return false;
    }

    bool ambiguousAudioControl = false;
    Uac2ClockPath result;
    std::string routingFailure;

    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioControl ||
                alt.bInterfaceProtocol != kUsbAudioProtocolUac2 ||
                alt.extra == nullptr ||
                alt.extra_length <= 0) {
                continue;
            }

            int terminalClockSourceId = 0;
            std::vector<neri::usb::uac2::ClockSource> clockSources;
            std::vector<neri::usb::uac2::ClockRouting> clockRoutings;
            int offset = 0;
            while (offset + 2 <= alt.extra_length) {
                const int descriptorLength = alt.extra[offset];
                if (descriptorLength < 2 || offset + descriptorLength > alt.extra_length) {
                    break;
                }
                const uint8_t* descriptor = alt.extra + offset;
                if (descriptorLength >= 3 &&
                    descriptor[1] == kUsbDescriptorTypeClassSpecificInterface) {
                    neri::usb::uac2::TerminalClockSource terminal;
                    std::string parseError;
                    if (neri::usb::uac2::parseTerminalClockSourceDescriptor(
                            descriptor,
                            descriptorLength,
                            &terminal,
                            &parseError
                        ) && terminal.terminalId == terminalLink) {
                        terminalClockSourceId = terminal.clockSourceId;
                    }

                    neri::usb::uac2::ClockSource clock;
                    if (neri::usb::uac2::parseClockSourceDescriptor(
                            descriptor,
                            descriptorLength,
                            &clock,
                            &parseError
                        )) {
                        clockSources.push_back(clock);
                    }
                    neri::usb::uac2::ClockRouting routing;
                    if (neri::usb::uac2::parseClockRoutingDescriptor(
                            descriptor,
                            descriptorLength,
                            &routing,
                            &parseError
                        )) {
                        clockRoutings.push_back(routing);
                    }
                }
                offset += descriptorLength;
            }

            if (terminalClockSourceId <= 0) {
                continue;
            }
            const int finalClockId = resolveUac2FinalClockId(
                deviceHandle,
                alt.bInterfaceNumber,
                terminalLink,
                terminalClockSourceId,
                clockSources,
                clockRoutings,
                &routingFailure
            );
            const auto clock = std::find_if(
                clockSources.begin(),
                clockSources.end(),
                [finalClockId](const neri::usb::uac2::ClockSource& candidate) {
                    return candidate.id == finalClockId;
                }
            );
            if (clock == clockSources.end()) {
                continue;
            }
            if (result.audioControlInterface >= 0 &&
                result.audioControlInterface != alt.bInterfaceNumber) {
                ambiguousAudioControl = true;
                continue;
            }
            result.audioControlInterface = alt.bInterfaceNumber;
            result.clockSourceId = clock->id;
            result.sampleRateControl = clock->samplingFrequencyControl();
        }
    }

    if (ambiguousAudioControl) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_audio_control_interface_ambiguous";
        }
        return false;
    }
    if (result.audioControlInterface < 0) {
        if (failureReason != nullptr) {
            *failureReason = routingFailure.empty() ? "uac2_terminal_link_not_found" : routingFailure;
        }
        return false;
    }
    if (result.clockSourceId <= 0) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_clock_topology_unsupported";
        }
        return false;
    }
    *output = result;
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

int scoreStreamingCandidate(
    const neri::usb::uac1::TypeIFormat& format,
    const neri::usb::uac1::EndpointControls& controls,
    int sampleRate,
    uint8_t endpointAttributes,
    const std::string& feedback
) {
    int score = 10000;
    if (format.isFixedAt(sampleRate)) {
        score += 400;
    } else if (format.sampleRateKind == neri::usb::uac1::SampleRateKind::Discrete) {
        score += 300;
    } else {
        score += 200;
    }
    if (controls.samplingFrequencyControl) {
        score += 100;
    }
    const int syncType = usbIsoSyncType(endpointAttributes);
    if (syncType == kLibusbIsoSyncTypeAdaptive) {
        score += 40;
    } else if (syncType == kLibusbIsoSyncTypeSynchronous) {
        score += 30;
    } else if (syncType == kLibusbIsoSyncTypeAsynchronous && feedback != "none") {
        score += 20;
    }
    return score;
}

int scoreUac2StreamingCandidate(
    neri::usb::uac2::ControlCapability sampleRateControl,
    uint8_t endpointAttributes,
    const std::string& feedback
) {
    int score = 11000;
    if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadWrite) {
        score += 300;
    } else if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadOnly) {
        score += 100;
    }
    const int syncType = usbIsoSyncType(endpointAttributes);
    if (syncType == kLibusbIsoSyncTypeAdaptive) {
        score += 40;
    } else if (syncType == kLibusbIsoSyncTypeSynchronous) {
        score += 30;
    } else if (syncType == kLibusbIsoSyncTypeAsynchronous && feedback != "none") {
        score += 20;
    }
    return score;
}

bool findStreamingAltUac1(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    libusb_device* device = libusb_get_device(devh);
    if (device == nullptr || output == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "invalid_libusb_device";
        }
        return false;
    }

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(device, &config);
    if (rc != LIBUSB_SUCCESS || config == nullptr) {
        LOGE("libusb_get_active_config_descriptor failed: %s", libusbErrName(rc));
        if (failureReason != nullptr) {
            *failureReason = std::string("active_config_failed:") + libusbErrName(rc);
        }
        return false;
    }

    StreamingAltSelection best;
    std::string rejectionSummary;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioStreaming) {
                continue;
            }
            if (alt.bInterfaceProtocol != kUsbAudioProtocolUac1) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "non_uac1_protocol_" + std::to_string(alt.bInterfaceProtocol)
                );
                continue;
            }

            neri::usb::uac1::TypeIFormat format;
            std::string parseError;
            if (!neri::usb::uac1::parseTypeIFormat(
                    alt.extra,
                    alt.extra_length,
                    &format,
                    &parseError
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    parseError
                );
                continue;
            }
            const neri::usb::uac1::FormatTarget formatTarget {
                sampleRate,
                channelCount,
                subslotBytes,
                bitsPerSample
            };
            std::string matchError;
            if (!neri::usb::uac1::matchesTarget(format, formatTarget, &matchError)) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    matchError
                );
                continue;
            }
            const int actualFrameBytes = format.channels * format.subslotBytes;

            bool hasIsoOutputEndpoint = false;
            for (int epIndex = 0; epIndex < alt.bNumEndpoints; ++epIndex) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[epIndex];
                if (!isIsoOutEndpoint(endpoint)) {
                    continue;
                }
                const int usage = usbIsoUsageType(endpoint.bmAttributes);
                if (usage == kLibusbIsoUsageFeedback) {
                    continue;
                }
                hasIsoOutputEndpoint = true;
                neri::usb::uac1::EndpointControls controls;
                if (!neri::usb::uac1::parseEndpointControls(
                        endpoint.extra,
                        endpoint.extra_length,
                        &controls,
                        &parseError
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        parseError
                    );
                    continue;
                }
                int packetBytes = libusb_get_max_alt_packet_size(
                    device,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress
                );
                if (packetBytes <= 0) {
                    packetBytes = endpointPacketCapacity(endpoint);
                }
                const int intervalsPerSecond = computeIntervalsPerSecond(
                    usbSpeed,
                    endpoint.bInterval
                );
                if (packetBytes <= 0 || computeMaxPacketBytes(
                        sampleRate,
                        intervalsPerSecond,
                        actualFrameBytes,
                        packetBytes
                    ) <= 0) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "endpoint_capacity_insufficient_" + std::to_string(packetBytes)
                    );
                    continue;
                }
                const std::string feedback = describeFeedback(alt, endpoint);
                const auto syncPolicy = neri::usb::resolveUsbStreamingSyncPolicy(
                    1,
                    neri::usb::uac1::requiresFeedbackScheduler(endpoint.bmAttributes),
                    neri::usb::usbStreamingFeedbackModeForDescription(feedback)
                );
                if (!syncPolicy.supported) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        std::string(neri::usb::usbStreamingSyncPolicyReasonName(
                            syncPolicy.reason
                        )) + ":feedback=" + feedback
                    );
                    continue;
                }
                bool explicitFeedbackEnabled = false;
                uint8_t feedbackEndpointAddress = 0;
                int feedbackPacketBytes = 0;
                int feedbackInterval = 0;
                neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
                if (syncPolicy.requiresExplicitFeedbackProfile) {
                    std::string feedbackProfileFailure;
                    if (!resolveExplicitFeedbackProfile(
                            device,
                            config->bConfigurationValue,
                            alt,
                            endpoint,
                            packetBytes,
                            usbSpeed,
                            1,
                            &feedbackEndpointAddress,
                            &feedbackPacketBytes,
                            &feedbackInterval,
                            &feedbackTimingProfile,
                            &feedbackProfileFailure
                        )) {
                        appendCandidateRejection(
                            &rejectionSummary,
                            alt.bInterfaceNumber,
                            alt.bAlternateSetting,
                            feedbackProfileFailure
                        );
                        continue;
                    }
                    explicitFeedbackEnabled = true;
                }
                const int score = scoreStreamingCandidate(
                    format,
                    controls,
                    sampleRate,
                    endpoint.bmAttributes,
                    feedback
                );
                LOGI(
                    "UAC1 candidate iface=%d alt=%d ep=0x%02X packetBytes=%d rates=%s score=%d",
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress,
                    packetBytes,
                    format.sampleRateSummary().c_str(),
                    score
                );
                if (score <= best.score) {
                    continue;
                }
                best.interfaceNumber = alt.bInterfaceNumber;
                best.alternateSetting = alt.bAlternateSetting;
                best.audioControlInterface = -1;
                best.outEndpoint = endpoint.bEndpointAddress;
                best.endpointMaxPacketBytes = packetBytes;
                best.endpointInterval = endpoint.bInterval;
                best.explicitFeedbackEnabled = explicitFeedbackEnabled;
                best.feedbackEndpoint = feedbackEndpointAddress;
                best.feedbackEndpointMaxPacketBytes = feedbackPacketBytes;
                best.feedbackEndpointInterval = feedbackInterval;
                best.feedbackTimingProfile = feedbackTimingProfile;
                best.score = score;
                best.uacVersion = 1;
                best.uac1.format = format;
                best.uac1.endpointControls = controls;
                best.syncType = neri::usb::uac1::syncTypeName(endpoint.bmAttributes);
                best.feedback = feedback;
                const char* rateKind = format.isFixedAt(sampleRate)
                    ? "fixed"
                    : format.sampleRateKind == neri::usb::uac1::SampleRateKind::Discrete
                        ? "discrete"
                        : "continuous";
                best.reason = "exact_type_i_pcm;rate=" + std::string(rateKind) +
                    ";freqControl=" +
                    (controls.samplingFrequencyControl ? "true" : "false") +
                    ";score=" + std::to_string(score) +
                    (explicitFeedbackEnabled
                        ? ";feedbackProfile=" + feedbackTimingProfile.evidence.profileId
                        : "");
            }
            if (!hasIsoOutputEndpoint) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "iso_output_endpoint_missing"
                );
            }
        }
    }

    if (best.interfaceNumber < 0) {
        libusb_free_config_descriptor(config);
        if (failureReason != nullptr) {
            *failureReason = rejectionSummary.empty()
                ? "no_uac1_type_i_output_candidate"
                : rejectionSummary;
        }
        return false;
    }
    best.completeClaimPlan = buildAudioFunctionClaimPlan(
        config,
        best.interfaceNumber,
        &best.claimPlan
    );
    if (!best.completeClaimPlan) {
        LOGW(
            "UAC1 function ownership uses descriptor fallback: iface=%d claimCount=%zu",
            best.interfaceNumber,
            best.claimPlan.size()
        );
    }
    libusb_free_config_descriptor(config);
    *output = std::move(best);
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

bool findStreamingAltUac2(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    libusb_device* device = libusb_get_device(devh);
    if (device == nullptr || output == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "invalid_libusb_device";
        }
        return false;
    }

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(device, &config);
    if (rc != LIBUSB_SUCCESS || config == nullptr) {
        LOGE("libusb_get_active_config_descriptor failed for UAC2: %s", libusbErrName(rc));
        if (failureReason != nullptr) {
            *failureReason = std::string("active_config_failed:") + libusbErrName(rc);
        }
        return false;
    }

    StreamingAltSelection best;
    std::string rejectionSummary;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioStreaming) {
                continue;
            }
            if (alt.bInterfaceProtocol != kUsbAudioProtocolUac2) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "non_uac2_protocol_" + std::to_string(alt.bInterfaceProtocol)
                );
                continue;
            }

            neri::usb::uac2::TypeIFormat format;
            std::string parseError;
            if (!neri::usb::uac2::parseTypeIFormat(
                    alt.extra,
                    alt.extra_length,
                    &format,
                    &parseError
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    parseError
                );
                continue;
            }
            const neri::usb::uac2::FormatTarget formatTarget {
                channelCount,
                subslotBytes,
                bitsPerSample
            };
            std::string matchError;
            if (!neri::usb::uac2::matchesTarget(format, formatTarget, &matchError)) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    matchError
                );
                continue;
            }
            const int actualFrameBytes = format.channels * format.subslotBytes;

            Uac2ClockPath clockPath;
            std::string clockFailure;
            if (!findUac2ClockPath(
                    devh,
                    config,
                    format.terminalLink,
                    &clockPath,
                    &clockFailure
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    clockFailure
                );
                continue;
            }
            if (clockPath.sampleRateControl == neri::usb::uac2::ControlCapability::None) {
                int fixedSampleRate = 0;
                std::string fixedRateStatus;
                if (!readUac2CurrentSampleRate(
                        devh,
                        clockPath.audioControlInterface,
                        clockPath.clockSourceId,
                        &fixedSampleRate,
                        &fixedRateStatus
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        fixedRateStatus
                    );
                    continue;
                }
                if (fixedSampleRate != sampleRate) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "uac2_fixed_sample_rate_mismatch_requested=" +
                            std::to_string(sampleRate) + "/actual=" +
                            std::to_string(fixedSampleRate)
                    );
                    continue;
                }
            }

            bool hasIsoOutputEndpoint = false;
            for (int epIndex = 0; epIndex < alt.bNumEndpoints; ++epIndex) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[epIndex];
                if (!isIsoOutEndpoint(endpoint)) {
                    continue;
                }
                const int usage = usbIsoUsageType(endpoint.bmAttributes);
                if (usage == kLibusbIsoUsageFeedback) {
                    continue;
                }
                hasIsoOutputEndpoint = true;
                neri::usb::uac2::EndpointControls controls;
                if (!neri::usb::uac2::parseEndpointControls(
                        endpoint.extra,
                        endpoint.extra_length,
                        &controls,
                        &parseError
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        parseError
                    );
                    continue;
                }
                int packetBytes = libusb_get_max_alt_packet_size(
                    device,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress
                );
                if (packetBytes <= 0) {
                    packetBytes = endpointPacketCapacity(endpoint);
                }
                const int intervalsPerSecond = computeIntervalsPerSecond(
                    usbSpeed,
                    endpoint.bInterval
                );
                if (packetBytes <= 0 || computeMaxPacketBytes(
                        sampleRate,
                        intervalsPerSecond,
                        actualFrameBytes,
                        packetBytes
                    ) <= 0) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "endpoint_capacity_insufficient_" + std::to_string(packetBytes)
                    );
                    continue;
                }
                const std::string feedback = describeFeedback(alt, endpoint);
                bool explicitFeedbackEnabled = false;
                uint8_t feedbackEndpointAddress = 0;
                int feedbackPacketBytes = 0;
                int feedbackInterval = 0;
                neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
                const auto syncPolicy = neri::usb::resolveUsbStreamingSyncPolicy(
                    2,
                    neri::usb::uac2::requiresFeedbackScheduler(endpoint.bmAttributes),
                    neri::usb::usbStreamingFeedbackModeForDescription(feedback)
                );
                if (!syncPolicy.supported) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        std::string(neri::usb::usbStreamingSyncPolicyReasonName(
                            syncPolicy.reason
                        )) + ":feedback=" + feedback
                    );
                    continue;
                }
                if (syncPolicy.requiresExplicitFeedbackProfile) {
                    std::string feedbackProfileFailure;
                    if (!resolveExplicitFeedbackProfile(
                            device,
                            config->bConfigurationValue,
                            alt,
                            endpoint,
                            packetBytes,
                            usbSpeed,
                            2,
                            &feedbackEndpointAddress,
                            &feedbackPacketBytes,
                            &feedbackInterval,
                            &feedbackTimingProfile,
                            &feedbackProfileFailure
                        )) {
                        appendCandidateRejection(
                            &rejectionSummary,
                            alt.bInterfaceNumber,
                            alt.bAlternateSetting,
                            feedbackProfileFailure
                        );
                        continue;
                    }
                    explicitFeedbackEnabled = true;
                }
                const int score = scoreUac2StreamingCandidate(
                    clockPath.sampleRateControl,
                    endpoint.bmAttributes,
                    feedback
                );
                LOGI(
                    "UAC2 candidate iface=%d alt=%d ep=0x%02X packetBytes=%d "
                    "clock=%d control=%s endpointDescriptor=%s score=%d",
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress,
                    packetBytes,
                    clockPath.clockSourceId,
                    neri::usb::uac2::controlCapabilityName(clockPath.sampleRateControl),
                    controls.hasGeneralDescriptor ? "general" : "missing",
                    score
                );
                if (score <= best.score) {
                    continue;
                }
                best.interfaceNumber = alt.bInterfaceNumber;
                best.alternateSetting = alt.bAlternateSetting;
                best.audioControlInterface = clockPath.audioControlInterface;
                best.outEndpoint = endpoint.bEndpointAddress;
                best.endpointMaxPacketBytes = packetBytes;
                best.endpointInterval = endpoint.bInterval;
                best.explicitFeedbackEnabled = explicitFeedbackEnabled;
                best.feedbackEndpoint = feedbackEndpointAddress;
                best.feedbackEndpointMaxPacketBytes = feedbackPacketBytes;
                best.feedbackEndpointInterval = feedbackInterval;
                best.feedbackTimingProfile = feedbackTimingProfile;
                best.score = score;
                best.uacVersion = 2;
                best.uac2.format = format;
                best.uac2.clockSourceId = clockPath.clockSourceId;
                best.uac2.sampleRateControl = clockPath.sampleRateControl;
                best.syncType = neri::usb::uac2::syncTypeName(endpoint.bmAttributes);
                best.feedback = feedback;
                best.reason = "exact_uac2_type_i_pcm;clock=" +
                    std::to_string(clockPath.clockSourceId) + ";rateControl=" +
                    neri::usb::uac2::controlCapabilityName(clockPath.sampleRateControl) +
                    ";score=" + std::to_string(score) +
                    (explicitFeedbackEnabled
                        ? ";feedbackProfile=" + feedbackTimingProfile.evidence.profileId
                        : "");
            }
            if (!hasIsoOutputEndpoint) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "iso_output_endpoint_missing"
                );
            }
        }
    }

    if (best.interfaceNumber < 0) {
        libusb_free_config_descriptor(config);
        if (failureReason != nullptr) {
            *failureReason = rejectionSummary.empty()
                ? "no_uac2_type_i_output_candidate"
                : rejectionSummary;
        }
        return false;
    }
    best.completeClaimPlan = buildMinimalAudioFunctionClaimPlan(
        config,
        best.audioControlInterface,
        best.interfaceNumber,
        &best.claimPlan
    );
    libusb_free_config_descriptor(config);
    *output = std::move(best);
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

bool findStreamingAlt(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    std::string uac1Failure;
    StreamingAltSelection uac1Selection;
    if (findStreamingAltUac1(
            devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            usbSpeed,
            &uac1Selection,
            &uac1Failure
        )) {
        uac1Failure.clear();
    }

    std::string uac2Failure;
    StreamingAltSelection uac2Selection;
    if (findStreamingAltUac2(
            devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            usbSpeed,
            &uac2Selection,
            &uac2Failure
        )) {
        uac2Failure.clear();
    }

    const bool hasUac1 = uac1Selection.interfaceNumber >= 0;
    const bool hasUac2 = uac2Selection.interfaceNumber >= 0;
    if (hasUac1 || hasUac2) {
        const StreamingAltSelection* best = nullptr;
        if (hasUac1 && hasUac2) {
            best = uac2Selection.score >= uac1Selection.score
                ? &uac2Selection
                : &uac1Selection;
            LOGI(
                "USB audio alt arbitration picked UAC%d over UAC%d (score=%d vs %d)",
                best->uacVersion,
                best->uacVersion == 2 ? 1 : 2,
                best->score,
                best->uacVersion == 2 ? uac1Selection.score : uac2Selection.score
            );
        } else {
            best = hasUac2 ? &uac2Selection : &uac1Selection;
        }
        *output = *best;
        if (failureReason != nullptr) {
            failureReason->clear();
        }
        return true;
    }

    if (failureReason != nullptr) {
        *failureReason = "uac1={" + uac1Failure + "} uac2={" + uac2Failure + "}";
    }
    return false;
}
int computeIntervalsPerSecond(int usbSpeed, int interval) {
    const int normalizedInterval = std::clamp(interval, 1, 16);
    const int intervalUnits = 1 << (normalizedInterval - 1);
    const bool usesMicroframes = usbSpeed == LIBUSB_SPEED_HIGH ||
        usbSpeed == LIBUSB_SPEED_SUPER ||
        usbSpeed == LIBUSB_SPEED_SUPER_PLUS ||
        usbSpeed == LIBUSB_SPEED_SUPER_PLUS_X2;
    const int baseIntervalsPerSecond = usesMicroframes ? 8000 : 1000;
    return std::max(1, baseIntervalsPerSecond / intervalUnits);
}
int frameAlignedDown(int bytes, int frameBytes) {
    const int frame = std::max(1, frameBytes);
    return std::max(0, (bytes / frame) * frame);
}

int computeMaxPacketBytes(
    int sampleRate,
    int intervalsPerSecond,
    int frameBytes,
    int endpointMaxPacketBytes
) {
    const int frame = std::max(1, frameBytes);
    const int intervals = std::max(1, intervalsPerSecond);
    const int framesPerInterval = (std::max(1, sampleRate) + intervals - 1) / intervals;
    int bytes = std::max(frame, framesPerInterval * frame);
    const int alignedEndpointCapacity = frameAlignedDown(endpointMaxPacketBytes, frame);
    if (endpointMaxPacketBytes > 0 && bytes > alignedEndpointCapacity) {
        return 0;
    }
    return std::max(frame, frameAlignedDown(bytes, frame));
}

bool readUac2CurrentSampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int* currentSampleRate,
    std::string* status
) {
    if (deviceHandle == nullptr || currentSampleRate == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0) {
        if (status != nullptr) {
            *status = "uac2_invalid_get_cur_input";
        }
        return false;
    }

    constexpr uint8_t kCurRequest = 0x01;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[4] = { 0, 0, 0, 0 };
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointIn);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int result = libusb_control_transfer(
        deviceHandle,
        requestType,
        kCurRequest,
        kSampleFrequencyControl,
        entityIndex,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (result != static_cast<int>(sizeof(sampleRateBytes))) {
        if (status != nullptr) {
            *status = result < 0
                ? std::string("uac2_sample_rate_get_cur_failed:") + libusbErrName(result)
                : "uac2_sample_rate_get_cur_short";
        }
        return false;
    }
    std::string decodeError;
    if (!neri::usb::uac2::decodeCurrentSampleRate(
            sampleRateBytes,
            sizeof(sampleRateBytes),
            currentSampleRate,
            &decodeError
        )) {
        if (status != nullptr) {
            *status = "uac2_sample_rate_get_cur_invalid:" + decodeError;
        }
        return false;
    }
    if (status != nullptr) {
        *status = "get_cur_verified";
    }
    return true;
}


} // namespace neri::usb::exclusive
