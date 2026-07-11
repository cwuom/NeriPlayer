#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <limits>
#include <memory>
#include <new>
#include <system_error>
#include <sys/resource.h>
#include <unordered_map>

#include "libusb/libusb.h"
#include "usb_iso_packet_scheduler.h"
#include "usb_pcm_pipeline.h"
#include "usb_uac1_format.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kUsbSubclassAudioStreaming = 0x02;
constexpr int kUsbSubclassAudioControl = 0x01;
constexpr int kUsbDescriptorTypeClassSpecificInterface = 0x24;
constexpr int kUsbAudioControlHeaderSubtype = 0x01;
constexpr int kUsbTransferTypeIsochronous = 0x01;
constexpr int kPermissionPollIntervalMs = 2;
constexpr int kGeneratedToneFrequencyHz = 440;
constexpr int kDefaultPacketsPerTransfer = 16;
constexpr int kDefaultTransferCount = 12;
constexpr int kDefaultPcmRingDurationMs = 5000;
constexpr int kMinimumPcmRingDurationMs = 3000;
constexpr int kMaximumPcmRingDurationMs = 12000;
constexpr int kCancelDrainWarningMs = 1200;
constexpr int kFirstTransferCompletionTimeoutMs = 750;
constexpr int kInterfaceTransitionCooldownMs = 1500;
constexpr int kUrgentAudioThreadPriority = -19;

enum class StreamSource {
    Tone,
    PlayerPcm
};

std::mutex g_lastOpenErrorLock;
std::string g_lastOpenError = "none";
std::mutex g_usbInterfaceTransitionLock;
std::chrono::steady_clock::time_point g_lastInterfaceTransitionAt {};

struct UsbExclusiveHandle;

struct TransferUserData {
    UsbExclusiveHandle* handle = nullptr;
    int slot = -1;
    int64_t queuedPlayerFrames = 0;
};

struct ClaimedUsbInterface {
    int interfaceNumber = -1;
    uint8_t subclass = 0;
};

struct UsbExclusiveHandle {
    libusb_context* ctx = nullptr;
    libusb_device_handle* devh = nullptr;
    int originalFd = -1;
    int dupFd = -1;
    int audioStreamingInterface = -1;
    int alternateSetting = -1;
    uint8_t outEndpoint = 0;
    int sampleRate = 0;
    int channelCount = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;
    int frameBytes = 0;
    int endpointMaxPacketBytes = 0;
    int endpointInterval = 0;
    int usbSpeed = LIBUSB_SPEED_UNKNOWN;
    std::vector<ClaimedUsbInterface> claimedAudioInterfaces;
    bool completeAudioFunctionClaim = false;
    int uacVersion = 0;
    int negotiatedSampleRate = 0;
    bool samplingFrequencyControl = false;
    std::string descriptorSampleRates = "none";
    std::string formatSelectionReason = "none";
    std::string sampleRateControlStatus = "not_attempted";
    std::string endpointSyncType = "none";
    std::string endpointFeedback = "none";
    int intervalsPerSecond = 1000;
    int bytesPerUsbFrame = 0;
    int packetsPerTransfer = kDefaultPacketsPerTransfer;
    int transferCount = kDefaultTransferCount;
    int transferBytes = 0;
    std::vector<libusb_transfer*> transfers;
    std::vector<std::vector<uint8_t>> transferBuffers;
    std::vector<TransferUserData> transferUserData;
    std::thread eventThread;
    std::atomic<bool> running { false };
    std::atomic<bool> playbackEnabled { false };
    std::atomic<bool> deviceOnline { true };
    std::atomic<bool> noDeviceObserved { false };
    std::atomic<bool> detachBroadcastConfirmed { false };
    std::atomic<bool> focusMuted { false };
    std::atomic<bool> stopRequested { false };
    std::atomic<bool> closing { false };
    std::atomic<bool> transportFailed { false };
    std::atomic<int> inFlightTransfers { 0 };
    std::mutex apiLock;
    std::mutex transferSubmitLock;
    std::mutex lock;
    std::atomic<StreamSource> streamSource { StreamSource::Tone };
    neri::usb::IsoPacketScheduler packetScheduler;
    neri::usb::PcmPipeline pcmPipeline;
    int pcmRingDurationMs = kDefaultPcmRingDurationMs;
    std::atomic<int64_t> stagedPlayerFrames { 0 };
    std::atomic<int64_t> completedAudioFrames { 0 };
    double tonePhase = 0.0;
    std::atomic<int> completedTransfers { 0 };
    std::atomic<int> submitErrors { 0 };
    std::atomic<int64_t> scheduledPackets { 0 };
    std::atomic<int64_t> scheduledFrames { 0 };
    std::atomic<int> packetFramesMin { std::numeric_limits<int>::max() };
    std::atomic<int> packetFramesMax { 0 };
    std::atomic<int> lastTransferBytes { 0 };
    std::atomic<int> shortWriteWarnings { 0 };
    std::atomic<float> playerVolume { 1.0f };
    std::string lastError;
};

std::mutex g_handleRegistryLock;
std::unordered_map<jlong, std::shared_ptr<UsbExclusiveHandle>> g_handleRegistry;
std::atomic<jlong> g_nextHandleToken { 1 };

bool allocateTransfers(UsbExclusiveHandle* handle);
void freeTransfers(UsbExclusiveHandle* handle);
void eventLoopThread(UsbExclusiveHandle* handle) noexcept;
void stopStreamingInternal(UsbExclusiveHandle* handle);

bool shouldStopTransferSubmission(const UsbExclusiveHandle* handle) {
    return handle == nullptr ||
        !handle->deviceOnline.load() ||
        handle->stopRequested.load() ||
        handle->closing.load() ||
        handle->transportFailed.load();
}

void requestDeviceStop(UsbExclusiveHandle* handle, bool detachBroadcastConfirmed) {
    if (handle == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
    if (detachBroadcastConfirmed) {
        handle->detachBroadcastConfirmed.store(true);
    }
    handle->deviceOnline.store(false);
    handle->focusMuted.store(true);
    handle->playbackEnabled.store(false);
    handle->stopRequested.store(true);
}

void requestNoDeviceStop(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->noDeviceObserved.store(true);
    requestDeviceStop(handle, false);
}

void configureUsbEventThreadPriority() {
    errno = 0;
    const int rc = setpriority(PRIO_PROCESS, 0, kUrgentAudioThreadPriority);
    if (rc == 0) {
        LOGI("USB event thread priority raised for audio stability");
        return;
    }
    LOGW("USB event thread priority unchanged: errno=%d %s", errno, strerror(errno));
}

std::shared_ptr<UsbExclusiveHandle> acquireHandle(jlong token) {
    if (token <= 0) {
        return {};
    }
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    const auto entry = g_handleRegistry.find(token);
    return entry != g_handleRegistry.end() ? entry->second : nullptr;
}

jlong registerHandle(const std::shared_ptr<UsbExclusiveHandle>& handle) {
    if (handle == nullptr) {
        return 0L;
    }
    const jlong token = g_nextHandleToken.fetch_add(1);
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    g_handleRegistry.emplace(token, handle);
    return token;
}

std::shared_ptr<UsbExclusiveHandle> takeHandle(jlong token) {
    if (token <= 0) {
        return {};
    }
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    const auto entry = g_handleRegistry.find(token);
    if (entry == g_handleRegistry.end()) {
        return {};
    }
    auto handle = entry->second;
    g_handleRegistry.erase(entry);
    return handle;
}

const char* libusbErrName(int rc) {
    return libusb_error_name(rc);
}

void rememberLastOpenError(const std::string& error) {
    std::lock_guard<std::mutex> guard(g_lastOpenErrorLock);
    g_lastOpenError = error;
}

std::string readLastOpenError() {
    std::lock_guard<std::mutex> guard(g_lastOpenErrorLock);
    return g_lastOpenError;
}

int remainingInterfaceTransitionCooldownMsLocked() {
    if (g_lastInterfaceTransitionAt == std::chrono::steady_clock::time_point {}) {
        return 0;
    }
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - g_lastInterfaceTransitionAt
    ).count();
    return std::max(0, kInterfaceTransitionCooldownMs - static_cast<int>(elapsedMs));
}

void markInterfaceTransitionLocked() {
    g_lastInterfaceTransitionAt = std::chrono::steady_clock::now();
}

void clearError(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->lastError.clear();
}

void setError(UsbExclusiveHandle* handle, const char* error) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->lastError = error != nullptr ? error : "unknown";
}

void setError(UsbExclusiveHandle* handle, const std::string& error) {
    setError(handle, error.c_str());
}

std::string getErrorCopy(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return "invalid_handle";
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->lastError;
}

bool isIsoOutEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK;
    const auto transferType = endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK;
    return direction == LIBUSB_ENDPOINT_OUT && transferType == kUsbTransferTypeIsochronous;
}

bool isIsoInEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK;
    const auto transferType = endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK;
    return direction == LIBUSB_ENDPOINT_IN && transferType == kUsbTransferTypeIsochronous;
}

int computeIntervalsPerSecond(int usbSpeed, int interval);

int computeMaxPacketBytes(
    int sampleRate,
    int intervalsPerSecond,
    int frameBytes,
    int endpointMaxPacketBytes
);

struct StreamingAltSelection {
    int interfaceNumber = -1;
    int alternateSetting = -1;
    uint8_t outEndpoint = 0;
    int endpointMaxPacketBytes = 0;
    int endpointInterval = 0;
    int score = std::numeric_limits<int>::min();
    neri::usb::uac1::TypeIFormat format;
    neri::usb::uac1::EndpointControls endpointControls;
    std::string syncType = "none";
    std::string feedback = "none";
    std::string reason = "none";
    std::vector<ClaimedUsbInterface> claimPlan;
    bool completeClaimPlan = false;
};

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
        *summary += ";";
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
    const int outputUsage =
        (outputEndpoint.bmAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) >> 4;
    if (outputUsage == LIBUSB_ISO_USAGE_TYPE_IMPLICIT) {
        return "implicit";
    }
    for (int index = 0; index < alt.bNumEndpoints; ++index) {
        const libusb_endpoint_descriptor& endpoint = alt.endpoint[index];
        const int usage = (endpoint.bmAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) >> 4;
        if (isIsoInEndpoint(endpoint) && usage == LIBUSB_ISO_USAGE_TYPE_FEEDBACK) {
            char buffer[24];
            snprintf(buffer, sizeof(buffer), "explicit:0x%02X", endpoint.bEndpointAddress);
            return buffer;
        }
    }
    return "none";
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
    const int syncType = (endpointAttributes & LIBUSB_ISO_SYNC_TYPE_MASK) >> 2;
    if (syncType == LIBUSB_ISO_SYNC_TYPE_ADAPTIVE) {
        score += 40;
    } else if (syncType == LIBUSB_ISO_SYNC_TYPE_SYNC) {
        score += 30;
    } else if (syncType == LIBUSB_ISO_SYNC_TYPE_ASYNC && feedback != "none") {
        score += 20;
    }
    return score;
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
    const int frameBytes = channelCount * subslotBytes;

    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioStreaming) {
                continue;
            }
            if (alt.bInterfaceProtocol != 0) {
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

            bool hasIsoOutputEndpoint = false;
            for (int epIndex = 0; epIndex < alt.bNumEndpoints; ++epIndex) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[epIndex];
                if (!isIsoOutEndpoint(endpoint)) {
                    continue;
                }
                const int usage = (endpoint.bmAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) >> 4;
                if (usage == LIBUSB_ISO_USAGE_TYPE_FEEDBACK) {
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
                        frameBytes,
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
                if (neri::usb::uac1::requiresFeedbackScheduler(endpoint.bmAttributes)) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "async_feedback_scheduler_unavailable"
                    );
                    continue;
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
                best.outEndpoint = endpoint.bEndpointAddress;
                best.endpointMaxPacketBytes = packetBytes;
                best.endpointInterval = endpoint.bInterval;
                best.score = score;
                best.format = format;
                best.endpointControls = controls;
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
                    ";score=" + std::to_string(score);
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

bool negotiateUac1SampleRate(
    libusb_device_handle* deviceHandle,
    uint8_t endpointAddress,
    int sampleRate,
    const neri::usb::uac1::TypeIFormat& format,
    const neri::usb::uac1::EndpointControls& controls,
    int* negotiatedSampleRate,
    std::string* status,
    std::string* error
) {
    if (deviceHandle == nullptr || negotiatedSampleRate == nullptr || status == nullptr) {
        if (error != nullptr) {
            *error = "invalid_sample_rate_negotiation_input";
        }
        return false;
    }
    const bool fixedRate = format.isFixedAt(sampleRate);
    if (!controls.samplingFrequencyControl) {
        if (!fixedRate) {
            if (error != nullptr) {
                *error = "sampling_frequency_control_required";
            }
            return false;
        }
        *negotiatedSampleRate = sampleRate;
        *status = "fixed_descriptor_no_control";
        return true;
    }

    constexpr uint8_t kSetCurRequest = 0x01;
    constexpr uint8_t kGetCurRequest = 0x81;
    constexpr uint16_t kSamplingFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };
    const uint8_t outRequestType = static_cast<uint8_t>(
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT
    );
    const int setResult = libusb_control_transfer(
        deviceHandle,
        outRequestType,
        kSetCurRequest,
        kSamplingFrequencyControl,
        endpointAddress,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (setResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "sample_rate_set_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (setResult != static_cast<int>(sizeof(sampleRateBytes))) {
        const bool unsupportedControl = setResult == LIBUSB_ERROR_PIPE ||
            setResult == LIBUSB_ERROR_NOT_SUPPORTED;
        if (fixedRate && unsupportedControl) {
            *negotiatedSampleRate = sampleRate;
            *status = std::string("fixed_descriptor_set_cur_unsupported:") +
                libusbErrName(setResult);
            return true;
        }
        if (error != nullptr) {
            *error = setResult < 0
                ? std::string("sample_rate_set_cur_failed:") + libusbErrName(setResult)
                : "sample_rate_set_cur_short";
        }
        return false;
    }

    uint8_t verifiedBytes[3] = { 0, 0, 0 };
    const uint8_t inRequestType = static_cast<uint8_t>(
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT
    );
    const int getResult = libusb_control_transfer(
        deviceHandle,
        inRequestType,
        kGetCurRequest,
        kSamplingFrequencyControl,
        endpointAddress,
        verifiedBytes,
        sizeof(verifiedBytes),
        kControlTimeoutMs
    );
    if (getResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "sample_rate_get_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (getResult == static_cast<int>(sizeof(verifiedBytes))) {
        const int verifiedRate = static_cast<int>(verifiedBytes[0]) |
            (static_cast<int>(verifiedBytes[1]) << 8) |
            (static_cast<int>(verifiedBytes[2]) << 16);
        if (verifiedRate != sampleRate) {
            if (error != nullptr) {
                *error = "sample_rate_verify_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" + std::to_string(verifiedRate);
            }
            return false;
        }
        *negotiatedSampleRate = verifiedRate;
        *status = "set_cur_verified";
        return true;
    }

    *negotiatedSampleRate = sampleRate;
    *status = getResult < 0
        ? std::string("set_cur_unverified:") + libusbErrName(getResult)
        : "set_cur_unverified_short_get";
    return true;
}

const char* sourceName(StreamSource source) {
    return source == StreamSource::PlayerPcm ? "player_pcm" : "tone";
}

void fillToneBuffer(UsbExclusiveHandle* handle, uint8_t* buffer, size_t bytes) {
    if (handle == nullptr || buffer == nullptr || bytes == 0 ||
        handle->frameBytes <= 0 || handle->channelCount <= 0 || handle->subslotBytes <= 0) {
        return;
    }
    const int frames = static_cast<int>(bytes) / handle->frameBytes;
    const double sampleRate = static_cast<double>(handle->sampleRate);
    const double phaseStep = 2.0 * M_PI * static_cast<double>(kGeneratedToneFrequencyHz) / sampleRate;
    const double amplitude = 0.30;

    for (int frame = 0; frame < frames; ++frame) {
        const double sample = std::sin(handle->tonePhase) * amplitude;
        handle->tonePhase += phaseStep;
        if (handle->tonePhase > 2.0 * M_PI) {
            handle->tonePhase -= 2.0 * M_PI;
        }

        for (int ch = 0; ch < handle->channelCount; ++ch) {
            const int offset = frame * handle->frameBytes + ch * handle->subslotBytes;
            neri::usb::writeIntegerPcmSample(
                buffer + offset,
                handle->subslotBytes,
                handle->bitsPerSample,
                static_cast<float>(sample)
            );
        }
    }
}

bool startStreamingInternal(
    UsbExclusiveHandle* handle,
    StreamSource source
) {
    if (handle == nullptr || handle->devh == nullptr || !handle->deviceOnline.load() ||
        handle->closing.load()) {
        LOGW("startStreamingInternal rejected: invalid handle");
        return false;
    }
    LOGI(
        "startStreamingInternal request: source=%s running=%d failed=%d playback=%d "
        "transferBytes=%d transferCount=%d packets=%d",
        sourceName(source),
        handle->running.load() ? 1 : 0,
        handle->transportFailed.load() ? 1 : 0,
        handle->playbackEnabled.load() ? 1 : 0,
        handle->transferBytes,
        handle->transferCount,
        handle->packetsPerTransfer
    );
    if (
        handle->running.load() ||
        !handle->transfers.empty() ||
        handle->eventThread.joinable()
    ) {
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            if (!handle->deviceOnline.load() || handle->stopRequested.load() ||
                handle->closing.load()) {
                return false;
            }
            if (!handle->transportFailed.load() && handle->streamSource.load() == source) {
                return true;
            }
        }
        LOGW(
            "startStreamingInternal restarts active stream: oldSource=%s newSource=%s failed=%d",
            sourceName(handle->streamSource.load()),
            sourceName(source),
            handle->transportFailed.load() ? 1 : 0
        );
        stopStreamingInternal(handle);
    }

    clearError(handle);
    handle->streamSource.store(source);
    handle->completedTransfers.store(0);
    handle->submitErrors.store(0);
    handle->scheduledPackets.store(0);
    handle->scheduledFrames.store(0);
    handle->packetFramesMin.store(std::numeric_limits<int>::max());
    handle->packetFramesMax.store(0);
    handle->lastTransferBytes.store(0);
    handle->shortWriteWarnings.store(0);
    handle->packetScheduler.reset();
    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (!handle->deviceOnline.load() || handle->closing.load()) {
            setError(handle, "stream_start_cancelled_device_offline");
            return false;
        }
        handle->stopRequested.store(false);
        handle->transportFailed.store(false);
        handle->inFlightTransfers.store(0);
    }
    if (!allocateTransfers(handle)) {
        LOGE("allocateTransfers failed before stream start: error=%s", getErrorCopy(handle).c_str());
        freeTransfers(handle);
        return false;
    }

    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (shouldStopTransferSubmission(handle)) {
            setError(handle, "stream_start_cancelled_after_allocation");
        } else {
            handle->running.store(true);
        }
    }
    if (!handle->running.load()) {
        stopStreamingInternal(handle);
        return false;
    }
    for (libusb_transfer* transfer : handle->transfers) {
        int rc = LIBUSB_ERROR_NO_DEVICE;
        bool cancelled = false;
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            cancelled = shouldStopTransferSubmission(handle) || !handle->running.load();
            if (!cancelled) {
                rc = libusb_submit_transfer(transfer);
                if (rc == LIBUSB_SUCCESS) {
                    handle->inFlightTransfers.fetch_add(1);
                }
            }
        }
        if (cancelled) {
            setError(handle, "stream_start_cancelled_during_submit");
            stopStreamingInternal(handle);
            return false;
        }
        if (rc != LIBUSB_SUCCESS) {
            setError(handle, std::string("submit_failed:") + libusbErrName(rc));
            LOGE("libusb_submit_transfer failed: %s", libusbErrName(rc));
            handle->transportFailed.store(true);
            stopStreamingInternal(handle);
            return false;
        }
    }
    try {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->running.load()) {
            setError(handle, "stream_start_cancelled_before_event_thread");
        } else {
            handle->eventThread = std::thread(eventLoopThread, handle);
        }
    } catch (const std::system_error& error) {
        setError(handle, std::string("event_thread_start_failed:") + error.what());
        handle->transportFailed.store(true);
        stopStreamingInternal(handle);
        return false;
    }
    if (!handle->eventThread.joinable()) {
        stopStreamingInternal(handle);
        return false;
    }

    LOGI(
        "native stream started: source=%s inFlight=%d transferBytes=%d packetBytes=%d",
        sourceName(source),
        handle->inFlightTransfers.load(),
        handle->transferBytes,
        handle->bytesPerUsbFrame
    );
    return true;
}

bool startStreamingSafely(UsbExclusiveHandle* handle, StreamSource source) noexcept {
    try {
        return startStreamingInternal(handle, source);
    } catch (const std::exception& error) {
        if (handle != nullptr) {
            handle->transportFailed.store(true);
            try {
                setError(handle, std::string("stream_start_exception:") + error.what());
            } catch (...) {
            }
        }
        LOGE("native stream start exception: %s", error.what());
        return false;
    } catch (...) {
        if (handle != nullptr) {
            handle->transportFailed.store(true);
            try {
                setError(handle, "stream_start_unknown_exception");
            } catch (...) {
            }
        }
        LOGE("native stream start unknown exception");
        return false;
    }
}

void updateAtomicMinimum(std::atomic<int>& value, int candidate) {
    int current = value.load();
    while (candidate < current && !value.compare_exchange_weak(current, candidate)) {
    }
}

void updateAtomicMaximum(std::atomic<int>& value, int candidate) {
    int current = value.load();
    while (candidate > current && !value.compare_exchange_weak(current, candidate)) {
    }
}

int applyIsoPacketLengths(
    UsbExclusiveHandle* handle,
    libusb_transfer* transfer
) {
    if (handle == nullptr || transfer == nullptr || handle->bytesPerUsbFrame <= 0) {
        return -1;
    }
    const int packetCount = transfer->num_iso_packets;
    int totalAssigned = 0;
    for (int packetIndex = 0; packetIndex < packetCount; ++packetIndex) {
        const neri::usb::IsoPacketPlan plan = handle->packetScheduler.next();
        if (plan.bytes < 0 || plan.bytes > handle->endpointMaxPacketBytes) {
            setError(handle, "scheduled_packet_exceeds_endpoint_capacity");
            handle->transportFailed.store(true);
            return -1;
        }
        transfer->iso_packet_desc[packetIndex].length = plan.bytes;
        totalAssigned += plan.bytes;
        handle->scheduledPackets.fetch_add(1);
        handle->scheduledFrames.fetch_add(plan.frames);
        updateAtomicMinimum(handle->packetFramesMin, plan.frames);
        updateAtomicMaximum(handle->packetFramesMax, plan.frames);
    }
    transfer->length = totalAssigned;
    handle->lastTransferBytes.store(totalAssigned);
    return totalAssigned;
}

void subtractAtomicFloorZero(std::atomic<int64_t>& value, int64_t amount) {
    int64_t current = value.load();
    while (true) {
        const int64_t updated = std::max<int64_t>(0, current - amount);
        if (value.compare_exchange_weak(current, updated)) {
            return;
        }
    }
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    int64_t completedFrames
) {
    if (handle == nullptr || userData == nullptr || userData->queuedPlayerFrames <= 0) {
        return;
    }
    const int64_t frames = userData->queuedPlayerFrames;
    userData->queuedPlayerFrames = 0;
    subtractAtomicFloorZero(handle->stagedPlayerFrames, frames);
    const int64_t boundedCompletedFrames = std::clamp<int64_t>(completedFrames, 0, frames);
    if (boundedCompletedFrames > 0) {
        handle->completedAudioFrames.fetch_add(boundedCompletedFrames);
    }
    const int64_t droppedFrames = frames - boundedCompletedFrames;
    if (droppedFrames > 0) {
        handle->pcmPipeline.addDroppedFrames(droppedFrames);
    }
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    bool completed
) {
    const int64_t queuedFrames = userData != nullptr ? userData->queuedPlayerFrames : 0;
    settlePreparedPlayerFrames(handle, userData, completed ? queuedFrames : 0);
}

bool refillTransfer(
    UsbExclusiveHandle* handle,
    libusb_transfer* transfer
) {
    if (handle == nullptr || transfer == nullptr) {
        return false;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    const int slot = userData != nullptr ? userData->slot : -1;
    if (slot < 0 || slot >= static_cast<int>(handle->transferBuffers.size())) {
        return false;
    }
    auto& buffer = handle->transferBuffers[slot];
    transfer->buffer = buffer.data();
    const int transferBytes = applyIsoPacketLengths(handle, transfer);
    if (transferBytes < 0 || static_cast<size_t>(transferBytes) > buffer.size()) {
        return false;
    }

    if (handle->streamSource.load() == StreamSource::PlayerPcm) {
        const bool renderPlayerPcm = handle->playbackEnabled.load() &&
            handle->deviceOnline.load() &&
            !handle->focusMuted.load();
        const size_t playerBytes = handle->pcmPipeline.fill(
            buffer.data(),
            static_cast<size_t>(transferBytes),
            renderPlayerPcm
        );
        if (userData != nullptr) {
            const int64_t queuedFrames = static_cast<int64_t>(
                playerBytes / static_cast<size_t>(std::max(1, handle->frameBytes))
            );
            userData->queuedPlayerFrames = queuedFrames;
            handle->stagedPlayerFrames.fetch_add(queuedFrames);
        }
    } else {
        fillToneBuffer(handle, buffer.data(), static_cast<size_t>(transferBytes));
    }
    return true;
}

struct TransferCallbackCompletion final {
    UsbExclusiveHandle* handle = nullptr;
    bool resubmitted = false;

    ~TransferCallbackCompletion() {
        if (handle != nullptr && !resubmitted) {
            handle->inFlightTransfers.fetch_sub(1);
        }
    }
};

void LIBUSB_CALL transferCallback(libusb_transfer* transfer) noexcept {
    if (transfer == nullptr) {
        return;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    auto* handle = userData != nullptr ? userData->handle : nullptr;
    if (handle == nullptr) {
        return;
    }
    TransferCallbackCompletion completion { handle, false };
    try {
        const bool transferCompleted = transfer->status == LIBUSB_TRANSFER_COMPLETED;
        bool packetsCompleted = transferCompleted;
        bool packetReportedNoDevice = false;
        int64_t completedPacketBytes = 0;
        if (transferCompleted) {
            for (int packetIndex = 0; packetIndex < transfer->num_iso_packets; ++packetIndex) {
                const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[packetIndex];
                if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
                    packetsCompleted = false;
                }
                if (packet.status == LIBUSB_TRANSFER_NO_DEVICE) {
                    packetReportedNoDevice = true;
                }
                if (packet.status == LIBUSB_TRANSFER_COMPLETED) {
                    completedPacketBytes += packet.length;
                }
            }
        }
        const int64_t completedPacketFrames = handle->frameBytes > 0
            ? completedPacketBytes / handle->frameBytes
            : 0;
        settlePreparedPlayerFrames(handle, userData, completedPacketFrames);
        if (packetsCompleted) {
            const int completedBefore = handle->completedTransfers.fetch_add(1);
            if (completedBefore == 0) {
                LOGI(
                    "first USB transfer completed: packets=%d requested=%d actual=%d",
                    transfer->num_iso_packets,
                    transfer->length,
                    transfer->actual_length
                );
            }
        } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
            if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE || packetReportedNoDevice) {
                requestNoDeviceStop(handle);
            }
            handle->submitErrors.fetch_add(1);
            handle->transportFailed.store(true);
            const std::string transferError = transferCompleted
                ? "iso_packet_status_failed"
                : std::string("transfer_status=") + std::to_string(transfer->status);
            setError(handle, transferError);
            LOGW(
                "USB transfer callback status=%d packetsCompleted=%d completed=%d "
                "submitErrors=%d inFlight=%d actual=%d",
                transfer->status,
                packetsCompleted ? 1 : 0,
                handle->completedTransfers.load(),
                handle->submitErrors.load(),
                handle->inFlightTransfers.load(),
                transfer->actual_length
            );
            return;
        }

        if (shouldStopTransferSubmission(handle)) {
            return;
        }
        if (!refillTransfer(handle, transfer)) {
            handle->submitErrors.fetch_add(1);
            handle->transportFailed.store(true);
            return;
        }

        int rc = LIBUSB_ERROR_NO_DEVICE;
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            if (shouldStopTransferSubmission(handle)) {
                settlePreparedPlayerFrames(handle, userData, false);
                return;
            }
            rc = libusb_submit_transfer(transfer);
            if (rc == LIBUSB_SUCCESS) {
                completion.resubmitted = true;
            }
        }
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(handle, userData, false);
            handle->submitErrors.fetch_add(1);
            handle->transportFailed.store(true);
            setError(handle, std::string("resubmit_failed:") + libusbErrName(rc));
            LOGE(
                "libusb_submit_transfer resubmit failed: %s completed=%d submitErrors=%d inFlight=%d",
                libusbErrName(rc),
                handle->completedTransfers.load(),
                handle->submitErrors.load(),
                handle->inFlightTransfers.load()
            );
            return;
        }
        handle->transportFailed.store(false);
    } catch (const std::exception& error) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        try {
            setError(handle, std::string("transfer_callback_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB transfer callback exception: %s", error.what());
    } catch (...) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        try {
            setError(handle, "transfer_callback_unknown_exception");
        } catch (...) {
        }
        LOGE("USB transfer callback unknown exception");
    }
}

bool allocateTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transferBytes <= 0) {
        return false;
    }

    try {
        handle->transfers.reserve(handle->transferCount);
        handle->transferBuffers.reserve(handle->transferCount);
        handle->transferUserData.reserve(handle->transferCount);

        for (int index = 0; index < handle->transferCount; ++index) {
            libusb_transfer* transfer = libusb_alloc_transfer(handle->packetsPerTransfer);
            if (transfer == nullptr) {
                setError(handle, "libusb_alloc_transfer_failed");
                return false;
            }
            try {
                handle->transferBuffers.emplace_back(
                    static_cast<size_t>(handle->transferBytes),
                    0
                );
                auto& buffer = handle->transferBuffers.back();
                handle->transferUserData.push_back(TransferUserData { handle, index, 0 });

                libusb_fill_iso_transfer(
                    transfer,
                    handle->devh,
                    handle->outEndpoint,
                    buffer.data(),
                    handle->transferBytes,
                    handle->packetsPerTransfer,
                    transferCallback,
                    &handle->transferUserData.back(),
                    0
                );
                if (!refillTransfer(handle, transfer)) {
                    libusb_free_transfer(transfer);
                    setError(handle, "initial_transfer_refill_failed");
                    return false;
                }
                handle->transfers.push_back(transfer);
            } catch (...) {
                libusb_free_transfer(transfer);
                throw;
            }
        }
    } catch (const std::bad_alloc&) {
        setError(handle, "usb_transfer_allocation_failed");
        freeTransfers(handle);
        return false;
    }

    return true;
}

void freeTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    for (libusb_transfer* transfer : handle->transfers) {
        if (transfer != nullptr) {
            libusb_free_transfer(transfer);
        }
    }
    for (TransferUserData& userData : handle->transferUserData) {
        settlePreparedPlayerFrames(handle, &userData, false);
    }
    handle->transfers.clear();
    handle->transferBuffers.clear();
    handle->transferUserData.clear();
    handle->inFlightTransfers.store(0);
}

void eventLoopThread(UsbExclusiveHandle* handle) noexcept {
    try {
        configureUsbEventThreadPriority();
        const auto startedAt = std::chrono::steady_clock::now();
        LOGI("USB event loop entered");
        while (handle->deviceOnline.load() && !handle->stopRequested.load()) {
            timeval timeout {};
            timeout.tv_sec = 0;
            timeout.tv_usec = kPermissionPollIntervalMs * 1000;
            const int rc = libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle);
                }
                handle->submitErrors.fetch_add(1);
                handle->transportFailed.store(true);
                setError(handle, std::string("event_loop_failed:") + libusbErrName(rc));
                LOGE("libusb_handle_events_timeout_completed failed: %s", libusbErrName(rc));
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
            }
            if (
                handle->completedTransfers.load() == 0 &&
                handle->inFlightTransfers.load() > 0 &&
                std::chrono::steady_clock::now() - startedAt >=
                    std::chrono::milliseconds(kFirstTransferCompletionTimeoutMs)
            ) {
                handle->transportFailed.store(true);
                handle->running.store(false);
                handle->stopRequested.store(true);
                setError(handle, "event_loop_first_completion_timeout");
                LOGE(
                    "USB event loop timed out before first completion: inFlight=%d",
                    handle->inFlightTransfers.load()
                );
                break;
            }
            if (
                handle->transportFailed.load() &&
                handle->inFlightTransfers.load() == 0
            ) {
                handle->running.store(false);
                handle->stopRequested.store(true);
                break;
            }
        }
        LOGI(
            "USB event loop exited: running=%d stop=%d completed=%d errors=%d inFlight=%d",
            handle->running.load() ? 1 : 0,
            handle->stopRequested.load() ? 1 : 0,
            handle->completedTransfers.load(),
            handle->submitErrors.load(),
            handle->inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        try {
            setError(handle, std::string("event_loop_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB event loop exception: %s", error.what());
    } catch (...) {
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        try {
            setError(handle, "event_loop_unknown_exception");
        } catch (...) {
        }
        LOGE("USB event loop unknown exception");
    }
}

void stopStreamingInternal(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    const bool wasRunning = handle->running.exchange(false);
    if (!wasRunning && handle->transfers.empty() && !handle->eventThread.joinable()) {
        return;
    }

    LOGI(
        "stopStreamingInternal begin: source=%s inFlight=%d completed=%d errors=%d",
        sourceName(handle->streamSource.load()),
        handle->inFlightTransfers.load(),
        handle->completedTransfers.load(),
        handle->submitErrors.load()
    );
    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        handle->stopRequested.store(true);
    }
    for (libusb_transfer* transfer : handle->transfers) {
        if (transfer != nullptr) {
            const int rc = libusb_cancel_transfer(transfer);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("libusb_cancel_transfer failed: %s", libusbErrName(rc));
            }
        }
    }
    if (handle->eventThread.joinable()) {
        handle->eventThread.join();
    }
    const auto warningDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainWarningMs);
    bool warnedAboutSlowDrain = false;
    while (handle->inFlightTransfers.load() > 0) {
        timeval timeout {};
        timeout.tv_sec = 0;
        timeout.tv_usec = kPermissionPollIntervalMs * 1000;
        const int rc = libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr);
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGW("libusb_handle_events while draining cancel failed: %s", libusbErrName(rc));
            std::this_thread::sleep_for(std::chrono::milliseconds(kPermissionPollIntervalMs));
        }
        if (!warnedAboutSlowDrain && std::chrono::steady_clock::now() >= warningDeadline) {
            warnedAboutSlowDrain = true;
            handle->transportFailed.store(true);
            try {
                setError(handle, "cancel_drain_stalled");
            } catch (...) {
            }
            LOGW(
                "waiting for %d cancelled USB transfers before close",
                handle->inFlightTransfers.load()
            );
        }
    }
    freeTransfers(handle);
    LOGI(
        "stopStreamingInternal done: completed=%d errors=%d transportFailed=%d",
        handle->completedTransfers.load(),
        handle->submitErrors.load(),
        handle->transportFailed.load() ? 1 : 0
    );
}

void releaseClaimedAudioInterfaces(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->devh == nullptr) {
        return;
    }
    for (auto entry = handle->claimedAudioInterfaces.rbegin();
         entry != handle->claimedAudioInterfaces.rend();
         ++entry) {
        const int releaseRc = libusb_release_interface(handle->devh, entry->interfaceNumber);
        if (releaseRc != LIBUSB_SUCCESS) {
            LOGW(
                "release interface failed: iface=%d err=%s",
                entry->interfaceNumber,
                libusbErrName(releaseRc)
            );
        } else {
            LOGI("released interface: iface=%d", entry->interfaceNumber);
        }
    }
    handle->claimedAudioInterfaces.clear();
}

int claimAudioInterface(UsbExclusiveHandle* handle, int interfaceNumber) {
    if (handle == nullptr || handle->devh == nullptr || interfaceNumber < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    return libusb_claim_interface(handle->devh, interfaceNumber);
}

bool claimAudioFunction(
    UsbExclusiveHandle* handle,
    const std::vector<ClaimedUsbInterface>& claimPlan,
    std::string* failureReason
) {
    if (handle == nullptr || handle->devh == nullptr || claimPlan.empty()) {
        if (failureReason != nullptr) {
            *failureReason = "empty_audio_claim_plan";
        }
        return false;
    }
    for (const ClaimedUsbInterface& planned : claimPlan) {
        const int rc = claimAudioInterface(handle, planned.interfaceNumber);
        if (rc != LIBUSB_SUCCESS) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            if (failureReason != nullptr) {
                *failureReason =
                    "claim_interface_failed:iface=" +
                    std::to_string(planned.interfaceNumber) + ":" +
                    libusbErrName(rc);
            }
            releaseClaimedAudioInterfaces(handle);
            return false;
        }
        handle->claimedAudioInterfaces.push_back(
            ClaimedUsbInterface {
                planned.interfaceNumber,
                planned.subclass
            }
        );
        LOGI(
            "claimed UAC interface: iface=%d subclass=%d",
            planned.interfaceNumber,
            static_cast<int>(planned.subclass)
        );
    }
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

void closeHandleInternal(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    bool expected = false;
    if (!handle->closing.compare_exchange_strong(expected, true)) {
        LOGW("closeHandleInternal ignored duplicate close");
        return;
    }
    LOGI(
        "closeHandleInternal begin: iface=%d alt=%d claimed=%zu running=%d source=%s",
        handle->audioStreamingInterface,
        handle->alternateSetting,
        handle->claimedAudioInterfaces.size(),
        handle->running.load() ? 1 : 0,
        sourceName(handle->streamSource.load())
    );
    stopStreamingInternal(handle);

    if (handle->devh != nullptr && !handle->detachBroadcastConfirmed.load()) {
        const bool streamingInterfaceClaimed = std::any_of(
            handle->claimedAudioInterfaces.begin(),
            handle->claimedAudioInterfaces.end(),
            [handle](const ClaimedUsbInterface& entry) {
                return entry.interfaceNumber == handle->audioStreamingInterface;
            }
        );
        if (streamingInterfaceClaimed && handle->alternateSetting > 0) {
            const int idleAltRc = libusb_set_interface_alt_setting(
                handle->devh,
                handle->audioStreamingInterface,
                0
            );
            if (idleAltRc == LIBUSB_SUCCESS) {
                markInterfaceTransitionLocked();
                LOGI(
                    "restored idle alt setting: iface=%d alt=0",
                    handle->audioStreamingInterface
                );
            } else {
                LOGW("restore idle alt setting failed: %s", libusbErrName(idleAltRc));
            }
        }
        releaseClaimedAudioInterfaces(handle);
        libusb_close(handle->devh);
        handle->devh = nullptr;
    } else if (handle->devh != nullptr) {
        LOGW("skip interface ioctls after physical USB detach");
        handle->claimedAudioInterfaces.clear();
        libusb_close(handle->devh);
        handle->devh = nullptr;
    }
    if (handle->ctx != nullptr) {
        libusb_exit(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->dupFd >= 0) {
        close(handle->dupFd);
        handle->dupFd = -1;
    }
    LOGI("closeHandleInternal done");
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeOpen(
    JNIEnv* env,
    jclass /*clazz*/,
    jint fd,
    jint sampleRate,
    jint channelCount,
    jint bitsPerSample,
    jint subslotBytes
) {
    static_cast<void>(env);
    LOGI(
        "nativeOpen request: fd=%d sampleRate=%d channels=%d bits=%d subslot=%d",
        fd,
        sampleRate,
        channelCount,
        bitsPerSample,
        subslotBytes
    );
    if (fd < 0) {
        LOGE("nativeOpen rejected invalid fd=%d", fd);
        rememberLastOpenError("invalid_fd");
        return 0L;
    }

    std::shared_ptr<UsbExclusiveHandle> handle;
    try {
        handle = std::make_shared<UsbExclusiveHandle>();
        handle->originalFd = fd;
        handle->sampleRate = sampleRate > 0 ? sampleRate : 48000;
        handle->channelCount = channelCount > 0 ? channelCount : 2;
        handle->bitsPerSample = bitsPerSample > 0 ? bitsPerSample : 16;
        handle->subslotBytes = subslotBytes > 0 ? subslotBytes : 2;
        if (handle->sampleRate < 8000 || handle->sampleRate > 768000 ||
            handle->channelCount < 1 || handle->channelCount > 8 ||
            handle->bitsPerSample < 8 || handle->bitsPerSample > 32 ||
            handle->subslotBytes < 1 || handle->subslotBytes > 4 ||
            handle->bitsPerSample > handle->subslotBytes * 8) {
            LOGE(
                "nativeOpen rejected invalid output format: sr=%d ch=%d bits=%d subslot=%d",
                handle->sampleRate,
                handle->channelCount,
                handle->bitsPerSample,
                handle->subslotBytes
            );
            rememberLastOpenError("invalid_output_format");
            return 0L;
        }
        std::unique_lock<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        const int remainingCooldownMs = remainingInterfaceTransitionCooldownMsLocked();
        if (remainingCooldownMs > 0) {
            LOGI("nativeOpen waits for USB interface cooldown: %dms", remainingCooldownMs);
            std::this_thread::sleep_for(std::chrono::milliseconds(remainingCooldownMs));
        }
        handle->frameBytes = handle->channelCount * handle->subslotBytes;

        handle->dupFd = dup(fd);
        if (handle->dupFd < 0) {
            rememberLastOpenError("dup_failed");
            return 0L;
        }

        int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("set_option_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }

        rc = libusb_init(&handle->ctx);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("libusb_init_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }
        libusb_set_option(handle->ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

        rc = libusb_wrap_sys_device(handle->ctx, static_cast<intptr_t>(handle->dupFd), &handle->devh);
        if (rc != LIBUSB_SUCCESS || handle->devh == nullptr) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            LOGE("nativeOpen wrap_sys_device failed: fd=%d rc=%d err=%s", handle->dupFd, rc, libusbErrName(rc));
            const std::string error = std::string("wrap_sys_device_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }

#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
        const int autoDetachRc = libusb_set_auto_detach_kernel_driver(handle->devh, 1);
        LOGI("nativeOpen set_auto_detach_kernel_driver rc=%d", autoDetachRc);
        if (autoDetachRc == LIBUSB_ERROR_NO_DEVICE) {
            requestNoDeviceStop(handle.get());
            rememberLastOpenError("auto_detach_failed:LIBUSB_ERROR_NO_DEVICE");
            closeHandleInternal(handle.get());
            return 0L;
        }
#endif

        handle->usbSpeed = libusb_get_device_speed(libusb_get_device(handle->devh));
        StreamingAltSelection selection;
        std::string selectionFailure;
        if (!findStreamingAlt(
                handle->devh,
                handle->sampleRate,
                handle->channelCount,
                handle->bitsPerSample,
                handle->subslotBytes,
                handle->usbSpeed,
                &selection,
                &selectionFailure
            )) {
            if (selectionFailure.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "no_compatible_uac1_format:" + selectionFailure;
            LOGE("nativeOpen compatible UAC1 alt not found: %s", selectionFailure.c_str());
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }
        handle->audioStreamingInterface = selection.interfaceNumber;
        handle->alternateSetting = selection.alternateSetting;
        handle->outEndpoint = selection.outEndpoint;
        handle->endpointMaxPacketBytes = selection.endpointMaxPacketBytes;
        handle->endpointInterval = selection.endpointInterval;
        handle->uacVersion = 1;
        handle->descriptorSampleRates = selection.format.sampleRateSummary();
        handle->formatSelectionReason = selection.reason;
        handle->samplingFrequencyControl = selection.endpointControls.samplingFrequencyControl;
        handle->endpointSyncType = selection.syncType;
        handle->endpointFeedback = selection.feedback;
        handle->completeAudioFunctionClaim = selection.completeClaimPlan;

        std::string claimFailure;
        if (!claimAudioFunction(handle.get(), selection.claimPlan, &claimFailure)) {
            LOGE("nativeOpen claim UAC function failed: %s", claimFailure.c_str());
            const std::string error = claimFailure.empty()
                ? "claim_audio_function_failed"
                : claimFailure;
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }

        rc = libusb_set_interface_alt_setting(
            handle->devh,
            handle->audioStreamingInterface,
            handle->alternateSetting
        );
        if (rc != LIBUSB_SUCCESS) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            LOGE(
                "nativeOpen set alt failed: iface=%d alt=%d err=%s",
                handle->audioStreamingInterface,
                handle->alternateSetting,
                libusbErrName(rc)
            );
            const std::string error = std::string("set_alt_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            markInterfaceTransitionLocked();
            return 0L;
        }
        markInterfaceTransitionLocked();

        std::string negotiationError;
        if (!negotiateUac1SampleRate(
                handle->devh,
                handle->outEndpoint,
                handle->sampleRate,
                selection.format,
                selection.endpointControls,
                &handle->negotiatedSampleRate,
                &handle->sampleRateControlStatus,
                &negotiationError
            )) {
            if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "sample_rate_negotiation_failed:" + negotiationError;
            LOGE("nativeOpen UAC1 sample rate negotiation failed: %s", negotiationError.c_str());
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }

        const int frameBytes = std::max(1, handle->frameBytes);
        handle->intervalsPerSecond = computeIntervalsPerSecond(
            handle->usbSpeed,
            handle->endpointInterval
        );
        handle->bytesPerUsbFrame = computeMaxPacketBytes(
            handle->sampleRate,
            handle->intervalsPerSecond,
            frameBytes,
            handle->endpointMaxPacketBytes
        );
        if (handle->bytesPerUsbFrame <= 0) {
            const std::string error = "endpoint_capacity_too_small";
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle.get());
            return 0L;
        }
        handle->transferBytes = handle->bytesPerUsbFrame * handle->packetsPerTransfer;
        handle->packetScheduler.configure(
            handle->sampleRate,
            handle->intervalsPerSecond,
            handle->frameBytes
        );
        rememberLastOpenError("none");
        clearError(handle.get());

        LOGI(
            "nativeOpen ok: uac=1 iface=%d alt=%d claimed=%zu fullFunctionClaim=%d "
            "outEp=0x%02X packetBytes=%d endpointMax=%d speed=%d interval=%d ips=%d "
            "sr=%d negotiated=%d ch=%d bits=%d subslot=%d rates=%s control=%s sync=%s feedback=%s",
            handle->audioStreamingInterface,
            handle->alternateSetting,
            handle->claimedAudioInterfaces.size(),
            handle->completeAudioFunctionClaim ? 1 : 0,
            handle->outEndpoint,
            handle->bytesPerUsbFrame,
            handle->endpointMaxPacketBytes,
            handle->usbSpeed,
            handle->endpointInterval,
            handle->intervalsPerSecond,
            handle->sampleRate,
            handle->negotiatedSampleRate,
            handle->channelCount,
            handle->bitsPerSample,
            handle->subslotBytes,
            handle->descriptorSampleRates.c_str(),
            handle->sampleRateControlStatus.c_str(),
            handle->endpointSyncType.c_str(),
            handle->endpointFeedback.c_str()
        );
        return registerHandle(handle);
    } catch (const std::bad_alloc&) {
        try {
            rememberLastOpenError("native_open_allocation_failed");
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle.get());
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen failed because memory allocation was rejected");
        return 0L;
    } catch (const std::exception& error) {
        try {
            rememberLastOpenError(std::string("native_open_exception:") + error.what());
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle.get());
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen exception: %s", error.what());
        return 0L;
    } catch (...) {
        try {
            rememberLastOpenError("native_open_unknown_exception");
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle.get());
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen unknown exception");
        return 0L;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeStartGeneratedTone(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        return JNI_FALSE;
    }
    holder->playbackEnabled.store(false);
    return startStreamingSafely(holder.get(), StreamSource::Tone) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeConfigurePlayerBufferDuration(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint durationMs
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    const int requestedDurationMs = std::clamp(
        static_cast<int>(durationMs),
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
    if (holder->streamSource.load() == StreamSource::PlayerPcm) {
        std::string resizeError;
        if (!holder->pcmPipeline.resizeRingDuration(
                requestedDurationMs,
                holder->transferBytes,
                holder->transferCount,
                &resizeError
            )) {
            setError(holder.get(), resizeError);
            LOGW(
                "nativeConfigurePlayerBufferDuration resize failed: handle=%lld error=%s",
                static_cast<long long>(handleValue),
                resizeError.c_str()
            );
            return JNI_FALSE;
        }
    }
    holder->pcmRingDurationMs = requestedDurationMs;
    LOGI(
        "nativeConfigurePlayerBufferDuration: handle=%lld requested=%d applied=%d",
        static_cast<long long>(handleValue),
        durationMs,
        holder->pcmRingDurationMs
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativePreparePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint inputSampleRate,
    jint inputChannelCount,
    jint inputEncoding
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    LOGI(
        "nativePreparePlayerPcm request: handle=%lld inputSr=%d inputCh=%d inputEncoding=%d "
        "outputSr=%d outputCh=%d bits=%d bufferMs=%d running=%d",
        static_cast<long long>(handleValue),
        inputSampleRate,
        inputChannelCount,
        inputEncoding,
        holder->sampleRate,
        holder->channelCount,
        holder->bitsPerSample,
        holder->pcmRingDurationMs,
        holder->running.load() ? 1 : 0
    );
    if (holder->running.load()) {
        stopStreamingInternal(holder.get());
    }
    const int bytesPerSample = neri::usb::bytesPerSampleForEncoding(inputEncoding);
    if (bytesPerSample <= 0 || inputSampleRate < 8000 || inputSampleRate > 768000 ||
        inputChannelCount < 1 || inputChannelCount > 8) {
        setError(holder.get(), "unsupported_player_pcm_format");
        LOGE(
            "nativePreparePlayerPcm unsupported input: sr=%d ch=%d encoding=%d",
            inputSampleRate,
            inputChannelCount,
            inputEncoding
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineConfig config {
        {
            holder->sampleRate,
            holder->channelCount,
            holder->subslotBytes,
            holder->bitsPerSample,
            holder->frameBytes
        },
        {
            inputSampleRate > 0 ? inputSampleRate : holder->sampleRate,
            inputChannelCount > 0 ? inputChannelCount : holder->channelCount,
            inputEncoding
        },
        holder->pcmRingDurationMs,
        holder->transferBytes,
        holder->transferCount
    };
    std::string pipelineError;
    if (!holder->pcmPipeline.configure(config, &pipelineError)) {
        setError(holder.get(), pipelineError);
        LOGE("nativePreparePlayerPcm pipeline configure failed: %s", pipelineError.c_str());
        return JNI_FALSE;
    }
    holder->streamSource.store(StreamSource::PlayerPcm);
    holder->playbackEnabled.store(false);
    holder->stagedPlayerFrames.store(0);
    holder->completedAudioFrames.store(0);
    clearError(holder.get());
    LOGI(
        "nativePreparePlayerPcm ok: handle=%lld ringMs=%d transferBytes=%d transferCount=%d",
        static_cast<long long>(handleValue),
        holder->pcmRingDurationMs,
        holder->transferBytes,
        holder->transferCount
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeWritePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jobject buffer,
    jint offset,
    jint size,
    jfloat volume
) {
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || buffer == nullptr || size <= 0 || offset < 0) {
        LOGW(
            "nativeWritePlayerPcm rejected: handle=%lld buffer=%d offset=%d size=%d",
            static_cast<long long>(handleValue),
            buffer != nullptr ? 1 : 0,
            offset,
            size
        );
        return 0;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || !holder->deviceOnline.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeWritePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return 0;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0 || static_cast<jlong>(offset) + size > capacity) {
        setError(holder.get(), "invalid_direct_pcm_buffer");
        LOGE(
            "nativeWritePlayerPcm invalid direct buffer: handle=%lld capacity=%lld offset=%d size=%d",
            static_cast<long long>(handleValue),
            static_cast<long long>(capacity),
            offset,
            size
        );
        return 0;
    }
    const float requestedVolume = std::clamp(volume, 0.0f, 1.0f);
    holder->playerVolume.store(requestedVolume);
    holder->pcmPipeline.setTargetGain(holder->focusMuted.load() ? 0.0f : requestedVolume);
    std::string pipelineError;
    size_t written = 0;
    try {
        written = holder->pcmPipeline.write(
            data + offset,
            static_cast<size_t>(size),
            &pipelineError
        );
    } catch (const std::bad_alloc&) {
        pipelineError = "pcm_write_allocation_failed";
    } catch (const std::exception& error) {
        pipelineError = std::string("pcm_write_failed:") + error.what();
    }
    if (!pipelineError.empty()) {
        setError(holder.get(), pipelineError);
        LOGW("nativeWritePlayerPcm pipeline warning: %s", pipelineError.c_str());
    }
    if (written == 0 || written < static_cast<size_t>(size)) {
        const int warningIndex = holder->shortWriteWarnings.fetch_add(1);
        if (warningIndex < 8) {
            const neri::usb::PcmPipelineSnapshot pcm = holder->pcmPipeline.snapshot();
            LOGW(
                "nativeWritePlayerPcm short write: handle=%lld requested=%d written=%zu "
                "level=%zu/%zu running=%d playback=%d input=%lld output=%lld dropped=%lld underrun=%lld",
                static_cast<long long>(handleValue),
                size,
                written,
                pcm.levelBytes,
                pcm.capacityBytes,
                holder->running.load() ? 1 : 0,
                holder->playbackEnabled.load() ? 1 : 0,
                static_cast<long long>(pcm.inputBytes),
                static_cast<long long>(pcm.outputBytes),
                static_cast<long long>(pcm.droppedBytes),
                static_cast<long long>(pcm.underrunBytes)
            );
        }
    }
    return static_cast<jint>(written);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePlayPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || !holder->deviceOnline.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePlayPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    LOGI(
        "nativePlayPlayerPcm request: handle=%lld running=%d queued=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->completedAudioFrames.load())
    );
    holder->playbackEnabled.store(true);
    if (startStreamingSafely(holder.get(), StreamSource::PlayerPcm)) {
        LOGI("nativePlayPlayerPcm ok: handle=%lld", static_cast<long long>(handleValue));
        return JNI_TRUE;
    }
    holder->playbackEnabled.store(false);
    LOGE(
        "nativePlayPlayerPcm failed: handle=%lld error=%s",
        static_cast<long long>(handleValue),
        getErrorCopy(holder.get()).c_str()
    );
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeStartPlayerPcm(
    JNIEnv* env,
    jclass clazz,
    jlong handleValue
) {
    return Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
        env,
        clazz,
        handleValue
    );
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativePausePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePausePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePausePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    holder->playbackEnabled.store(false);
    LOGI(
        "nativePausePlayerPcm ok: handle=%lld running=%d level=%zu/%zu queued=%lld",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->pcmPipeline.queuedFrames())
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeFlushPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeFlushPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeFlushPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }

    const bool transportWasRunning = holder->running.load();
    holder->playbackEnabled.store(false);
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    LOGI(
        "nativeFlushPlayerPcm begin: handle=%lld restart=%d resume=%d level=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        transportWasRunning ? 1 : 0,
        0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->completedAudioFrames.load())
    );
    stopStreamingInternal(holder.get());
    holder->pcmPipeline.clear();
    holder->pcmPipeline.resetCounters();
    holder->stagedPlayerFrames.store(0);
    holder->completedAudioFrames.store(0);

    clearError(holder.get());
    LOGI(
        "nativeFlushPlayerPcm done: handle=%lld running=%d playback=%d",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        holder->playbackEnabled.load() ? 1 : 0
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeSetPlayerVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jfloat volume
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load() || !holder->deviceOnline.load()) {
        return JNI_FALSE;
    }
    const float requestedVolume = std::clamp(static_cast<float>(volume), 0.0f, 1.0f);
    holder->playerVolume.store(requestedVolume);
    holder->pcmPipeline.setTargetGain(holder->focusMuted.load() ? 0.0f : requestedVolume);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeSetPlayerFocusMuted(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jboolean muted
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load() || !holder->deviceOnline.load()) {
        return JNI_FALSE;
    }
    const bool shouldMute = muted == JNI_TRUE;
    holder->focusMuted.store(shouldMute);
    holder->pcmPipeline.setTargetGain(
        shouldMute ? 0.0f : std::clamp(holder->playerVolume.load(), 0.0f, 1.0f)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeGetCompletedAudioFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    return holder != nullptr ? static_cast<jlong>(holder->completedAudioFrames.load()) : 0L;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeGetQueuedPlayerFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return 0L;
    }
    return static_cast<jlong>(holder->pcmPipeline.queuedFrames()) +
        holder->stagedPlayerFrames.load();
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeStop(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeStop ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    LOGI(
        "nativeStop: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        sourceName(holder->streamSource.load())
    );
    requestDeviceStop(holder.get(), false);
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeMarkDeviceDetached(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return;
    }
    LOGW("nativeMarkDeviceDetached: handle=%lld", static_cast<long long>(handleValue));
    requestDeviceStop(holder.get(), true);
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeClose(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = takeHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeClose ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    requestDeviceStop(holder.get(), holder->detachBroadcastConfirmed.load());
    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->ctx != nullptr) {
        libusb_interrupt_event_handler(holder->ctx);
    }
    LOGI(
        "nativeClose: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        sourceName(holder->streamSource.load())
    );
    holder->playbackEnabled.store(false);
    closeHandleInternal(holder.get());
    markInterfaceTransitionLocked();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeRuntimeReport(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    try {
        const auto holder = acquireHandle(handleValue);
        if (holder == nullptr) {
            const std::string error = readLastOpenError();
            return env->NewStringUTF(error.c_str());
        }
        std::lock_guard<std::mutex> apiGuard(holder->apiLock);
        const std::string lastError = getErrorCopy(holder.get());
        const neri::usb::PcmPipelineSnapshot pcm = holder->pcmPipeline.snapshot();
        const int64_t stagedPlayerFrames = holder->stagedPlayerFrames.load();
        const int64_t queuedFrames = static_cast<int64_t>(
            pcm.levelBytes / static_cast<size_t>(std::max(1, holder->frameBytes))
        ) + stagedPlayerFrames;
        const int64_t fifoMs = holder->sampleRate > 0 && holder->frameBytes > 0
            ? static_cast<int64_t>(pcm.levelBytes) * 1000 /
                (static_cast<int64_t>(holder->sampleRate) * holder->frameBytes)
            : 0;
        const int64_t bufferMs = holder->sampleRate > 0 && holder->frameBytes > 0
            ? static_cast<int64_t>(pcm.capacityBytes) * 1000 /
                (static_cast<int64_t>(holder->sampleRate) * holder->frameBytes)
            : 0;
        const int minimumPacketFrames = holder->packetFramesMin.load() == std::numeric_limits<int>::max()
            ? 0
            : holder->packetFramesMin.load();
        std::string claimedInterfaceSummary;
        for (const ClaimedUsbInterface& entry : holder->claimedAudioInterfaces) {
            if (!claimedInterfaceSummary.empty()) {
                claimedInterfaceSummary += ",";
            }
            claimedInterfaceSummary += std::to_string(entry.interfaceNumber);
        }
        if (claimedInterfaceSummary.empty()) {
            claimedInterfaceSummary = "none";
        }
        const std::string report =
            "iface=" + std::to_string(holder->audioStreamingInterface) +
            " alt=" + std::to_string(holder->alternateSetting) +
            " claimedIfaces=" + claimedInterfaceSummary +
            " fullFunctionClaim=" + std::string(
                holder->completeAudioFunctionClaim ? "true" : "false"
            ) +
            " outEp=0x" + [&]() {
                char buf[8];
                snprintf(buf, sizeof(buf), "%02X", holder->outEndpoint);
                return std::string(buf);
            }() +
            " source=" + std::string(sourceName(holder->streamSource.load())) +
            " uacVersion=" + std::string(holder->uacVersion == 1 ? "1.0" : "unsupported") +
            " sampleRate=" + std::to_string(holder->sampleRate) +
            " negotiatedRate=" + std::to_string(holder->negotiatedSampleRate) +
            " descriptorRates=" + holder->descriptorSampleRates +
            " rateControl=" + holder->sampleRateControlStatus +
            " channels=" + std::to_string(holder->channelCount) +
            " bits=" + std::to_string(holder->bitsPerSample) +
            " subslotBytes=" + std::to_string(holder->subslotBytes) +
            " formatSelection=" + holder->formatSelectionReason +
            " syncType=" + holder->endpointSyncType +
            " feedback=" + holder->endpointFeedback +
            " usbSpeed=" + std::to_string(holder->usbSpeed) +
            " packetBytes=" + std::to_string(holder->bytesPerUsbFrame) +
            " packetFrames=" + std::to_string(minimumPacketFrames) + ".." +
                std::to_string(holder->packetFramesMax.load()) +
            " endpointMaxPacketBytes=" + std::to_string(holder->endpointMaxPacketBytes) +
            " interval=" + std::to_string(holder->endpointInterval) +
            " intervalsPerSecond=" + std::to_string(holder->intervalsPerSecond) +
            " transferBytes=" + std::to_string(holder->transferBytes) +
            " lastTransferBytes=" + std::to_string(holder->lastTransferBytes.load()) +
            " deviceOnline=" + std::string(holder->deviceOnline.load() ? "true" : "false") +
            " noDeviceObserved=" + std::string(
                holder->noDeviceObserved.load() ? "true" : "false"
            ) +
            " detachConfirmed=" + std::string(
                holder->detachBroadcastConfirmed.load() ? "true" : "false"
            ) +
            " focusMuted=" + std::string(holder->focusMuted.load() ? "true" : "false") +
            " running=" + std::string(holder->running.load() ? "true" : "false") +
            " paused=" + std::string(
                holder->streamSource.load() == StreamSource::PlayerPcm &&
                !holder->playbackEnabled.load() ? "true" : "false"
            ) +
            " transportFailed=" + std::string(holder->transportFailed.load() ? "true" : "false") +
            " inFlight=" + std::to_string(holder->inFlightTransfers.load()) +
            " completedTransfers=" + std::to_string(holder->completedTransfers.load()) +
            " submitErrors=" + std::to_string(holder->submitErrors.load()) +
            " scheduledPackets=" + std::to_string(holder->scheduledPackets.load()) +
            " scheduledFrames=" + std::to_string(holder->scheduledFrames.load()) +
            " pcmLevel=" + std::to_string(pcm.levelBytes) + "/" +
                std::to_string(pcm.capacityBytes) +
            " bufferMs=" + std::to_string(bufferMs) +
            " requestedBufferMs=" + std::to_string(holder->pcmRingDurationMs) +
            " fifoMs=" + std::to_string(fifoMs) +
            " queuedFrames=" + std::to_string(queuedFrames) +
            " stagedFrames=" + std::to_string(stagedPlayerFrames) +
            " completedAudioFrames=" + std::to_string(holder->completedAudioFrames.load()) +
            " playerInputBytes=" + std::to_string(pcm.inputBytes) +
            " playerOutputBytes=" + std::to_string(pcm.outputBytes) +
            " playerDroppedBytes=" + std::to_string(pcm.droppedBytes) +
            " playerUnderrunBytes=" + std::to_string(pcm.underrunBytes) +
            " playerZeroFillBytes=" + std::to_string(pcm.zeroFillBytes) +
            " playerPausedZeroFillBytes=" + std::to_string(pcm.pausedZeroFillBytes) +
            " targetGain=" + std::to_string(pcm.targetGain) +
            " appliedGain=" + std::to_string(pcm.appliedGain) +
            " lastError=" + (lastError.empty() ? "none" : lastError);
        return env->NewStringUTF(report.c_str());
    } catch (const std::exception& error) {
        LOGE("nativeRuntimeReport exception: %s", error.what());
        return env->NewStringUTF("native_runtime_report_unavailable");
    } catch (...) {
        LOGE("nativeRuntimeReport unknown exception");
        return env->NewStringUTF("native_runtime_report_unavailable");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeLastOpenError(
    JNIEnv* env,
    jclass /*clazz*/
) {
    try {
        const std::string error = readLastOpenError();
        return env->NewStringUTF(error.c_str());
    } catch (const std::exception& error) {
        LOGE("nativeLastOpenError exception: %s", error.what());
        return env->NewStringUTF("native_last_open_error_unavailable");
    } catch (...) {
        LOGE("nativeLastOpenError unknown exception");
        return env->NewStringUTF("native_last_open_error_unavailable");
    }
}
