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
constexpr int kUsbTransferTypeIsochronous = 0x01;
constexpr int kPermissionPollIntervalMs = 2;
constexpr int kGeneratedToneFrequencyHz = 440;
constexpr int kDefaultPacketsPerTransfer = 4;
constexpr int kDefaultTransferCount = 6;
constexpr int kDefaultPcmRingDurationMs = 250;
constexpr int kMinimumPcmRingDurationMs = 40;
constexpr int kMaximumPcmRingDurationMs = 2000;
constexpr int kCancelDrainWarningMs = 1200;
constexpr int kInterfaceTransitionCooldownMs = 1500;

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
    bool interfaceClaimed = false;
    bool manuallyDetachedKernelDriver = false;
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
    std::atomic<bool> stopRequested { false };
    std::atomic<bool> closing { false };
    std::atomic<bool> transportFailed { false };
    std::atomic<int> inFlightTransfers { 0 };
    std::mutex apiLock;
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
    std::string lastError;
};

std::mutex g_handleRegistryLock;
std::unordered_map<jlong, std::shared_ptr<UsbExclusiveHandle>> g_handleRegistry;
std::atomic<jlong> g_nextHandleToken { 1 };

bool allocateTransfers(UsbExclusiveHandle* handle);
void freeTransfers(UsbExclusiveHandle* handle);
void eventLoopThread(UsbExclusiveHandle* handle);
void stopStreamingInternal(UsbExclusiveHandle* handle);

void configureUsbEventThreadPriority() {
    errno = 0;
    const int rc = setpriority(PRIO_PROCESS, 0, -16);
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
};

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

    libusb_free_config_descriptor(config);

    if (best.interfaceNumber < 0) {
        if (failureReason != nullptr) {
            *failureReason = rejectionSummary.empty()
                ? "no_uac1_type_i_output_candidate"
                : rejectionSummary;
        }
        return false;
    }
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
    if (handle == nullptr || handle->devh == nullptr) {
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
    if (handle->running.load()) {
        if (!handle->transportFailed.load() && handle->streamSource.load() == source) {
            return true;
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
    handle->stopRequested.store(false);
    handle->transportFailed.store(false);
    handle->inFlightTransfers.store(0);
    if (!allocateTransfers(handle)) {
        LOGE("allocateTransfers failed before stream start: error=%s", getErrorCopy(handle).c_str());
        freeTransfers(handle);
        return false;
    }

    handle->running.store(true);
    for (libusb_transfer* transfer : handle->transfers) {
        const int rc = libusb_submit_transfer(transfer);
        if (rc != LIBUSB_SUCCESS) {
            setError(handle, std::string("submit_failed:") + libusbErrName(rc));
            LOGE("libusb_submit_transfer failed: %s", libusbErrName(rc));
            handle->transportFailed.store(true);
            stopStreamingInternal(handle);
            return false;
        }
        handle->inFlightTransfers.fetch_add(1);
    }
    handle->eventThread = std::thread(eventLoopThread, handle);

    LOGI(
        "native stream started: source=%s inFlight=%d transferBytes=%d packetBytes=%d",
        sourceName(source),
        handle->inFlightTransfers.load(),
        handle->transferBytes,
        handle->bytesPerUsbFrame
    );
    return true;
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

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    bool completed
) {
    if (handle == nullptr || userData == nullptr || userData->queuedPlayerFrames <= 0) {
        return;
    }
    const int64_t frames = userData->queuedPlayerFrames;
    userData->queuedPlayerFrames = 0;
    handle->stagedPlayerFrames.fetch_sub(frames);
    if (completed) {
        handle->completedAudioFrames.fetch_add(frames);
        return;
    }
    handle->pcmPipeline.addDroppedFrames(frames);
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
        const size_t playerBytes = handle->pcmPipeline.fill(
            buffer.data(),
            static_cast<size_t>(transferBytes),
            handle->playbackEnabled.load()
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

void LIBUSB_CALL transferCallback(libusb_transfer* transfer) {
    if (transfer == nullptr) {
        return;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    auto* handle = userData != nullptr ? userData->handle : nullptr;
    if (handle == nullptr) {
        return;
    }

    const bool completed = transfer->status == LIBUSB_TRANSFER_COMPLETED;
    settlePreparedPlayerFrames(handle, userData, completed);
    if (completed) {
        handle->completedTransfers.fetch_add(1);
    } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        setError(handle, std::string("transfer_status=") + std::to_string(transfer->status));
        LOGW(
            "USB transfer callback status=%d completed=%d submitErrors=%d inFlight=%d actual=%d",
            transfer->status,
            handle->completedTransfers.load(),
            handle->submitErrors.load(),
            handle->inFlightTransfers.load(),
            transfer->actual_length
        );
    }

    if (handle->stopRequested.load()) {
        handle->inFlightTransfers.fetch_sub(1);
        return;
    }

    if (!refillTransfer(handle, transfer)) {
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        handle->inFlightTransfers.fetch_sub(1);
        return;
    }
    const int rc = libusb_submit_transfer(transfer);
    if (rc != LIBUSB_SUCCESS) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->submitErrors.fetch_add(1);
        handle->transportFailed.store(true);
        handle->inFlightTransfers.fetch_sub(1);
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
    return;
}

bool allocateTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transferBytes <= 0) {
        return false;
    }

    handle->transfers.reserve(handle->transferCount);
    handle->transferBuffers.reserve(handle->transferCount);
    handle->transferUserData.reserve(handle->transferCount);

    for (int index = 0; index < handle->transferCount; ++index) {
        libusb_transfer* transfer = libusb_alloc_transfer(handle->packetsPerTransfer);
        if (transfer == nullptr) {
            setError(handle, "libusb_alloc_transfer_failed");
            return false;
        }

        handle->transferBuffers.emplace_back(static_cast<size_t>(handle->transferBytes), 0);
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

void eventLoopThread(UsbExclusiveHandle* handle) {
    configureUsbEventThreadPriority();
    while (!handle->stopRequested.load()) {
        timeval timeout {};
        timeout.tv_sec = 0;
        timeout.tv_usec = kPermissionPollIntervalMs * 1000;
        const int rc = libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr);
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            handle->submitErrors.fetch_add(1);
            handle->transportFailed.store(true);
            setError(handle, std::string("event_loop_failed:") + libusbErrName(rc));
            LOGE("libusb_handle_events_timeout_completed failed: %s", libusbErrName(rc));
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
    }
}

void stopStreamingInternal(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    if (!handle->running.exchange(false) && handle->transfers.empty()) {
        return;
    }

    LOGI(
        "stopStreamingInternal begin: source=%s inFlight=%d completed=%d errors=%d",
        sourceName(handle->streamSource.load()),
        handle->inFlightTransfers.load(),
        handle->completedTransfers.load(),
        handle->submitErrors.load()
    );
    handle->stopRequested.store(true);
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
        "closeHandleInternal begin: iface=%d alt=%d claimed=%d running=%d source=%s",
        handle->audioStreamingInterface,
        handle->alternateSetting,
        handle->interfaceClaimed ? 1 : 0,
        handle->running.load() ? 1 : 0,
        sourceName(handle->streamSource.load())
    );
    stopStreamingInternal(handle);

    if (handle->devh != nullptr) {
        if (handle->audioStreamingInterface >= 0) {
            if (handle->interfaceClaimed) {
                if (handle->alternateSetting > 0) {
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
                const int releaseRc = libusb_release_interface(handle->devh, handle->audioStreamingInterface);
                if (releaseRc != LIBUSB_SUCCESS) {
                    LOGW("release interface failed: %s", libusbErrName(releaseRc));
                } else {
                    LOGI("released interface: iface=%d", handle->audioStreamingInterface);
                }
                handle->interfaceClaimed = false;
            }
            if (handle->manuallyDetachedKernelDriver) {
                const int attachRc = libusb_attach_kernel_driver(
                    handle->devh,
                    static_cast<uint8_t>(handle->audioStreamingInterface)
                );
                if (attachRc == LIBUSB_SUCCESS) {
                    LOGI("reattached kernel driver: iface=%d", handle->audioStreamingInterface);
                } else if (attachRc != LIBUSB_ERROR_NOT_SUPPORTED) {
                    LOGW("reattach kernel driver failed: %s", libusbErrName(attachRc));
                }
                handle->manuallyDetachedKernelDriver = false;
            }
        }
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
    (void) env;
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

    auto handle = std::make_shared<UsbExclusiveHandle>();
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

    rc = libusb_claim_interface(handle->devh, handle->audioStreamingInterface);
    if (rc == LIBUSB_ERROR_BUSY) {
        const int activeRc = libusb_kernel_driver_active(
            handle->devh,
            static_cast<uint8_t>(handle->audioStreamingInterface)
        );
        LOGW(
            "nativeOpen claim busy: iface=%d kernel_driver_active=%d",
            handle->audioStreamingInterface,
            activeRc
        );
        if (activeRc == 1) {
            const int detachRc = libusb_detach_kernel_driver(
                handle->devh,
                static_cast<uint8_t>(handle->audioStreamingInterface)
            );
            LOGW(
                "nativeOpen detach kernel driver: iface=%d rc=%d err=%s",
                handle->audioStreamingInterface,
                detachRc,
                libusbErrName(detachRc)
            );
            if (detachRc == LIBUSB_SUCCESS) {
                handle->manuallyDetachedKernelDriver = true;
                rc = libusb_claim_interface(handle->devh, handle->audioStreamingInterface);
                LOGW(
                    "nativeOpen retry claim after detach: iface=%d rc=%d err=%s",
                    handle->audioStreamingInterface,
                    rc,
                    libusbErrName(rc)
                );
            }
        }
    }
    if (rc != LIBUSB_SUCCESS) {
        LOGE(
            "nativeOpen claim interface failed: iface=%d err=%s",
            handle->audioStreamingInterface,
            libusbErrName(rc)
        );
        const std::string error = std::string("claim_interface_failed:") + libusbErrName(rc);
        rememberLastOpenError(error);
        setError(handle.get(), error);
        closeHandleInternal(handle.get());
        return 0L;
    }
    handle->interfaceClaimed = true;

    rc = libusb_set_interface_alt_setting(
        handle->devh,
        handle->audioStreamingInterface,
        handle->alternateSetting
    );
    if (rc != LIBUSB_SUCCESS) {
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
        "nativeOpen ok: uac=1 iface=%d alt=%d outEp=0x%02X packetBytes=%d endpointMax=%d speed=%d interval=%d ips=%d sr=%d negotiated=%d ch=%d bits=%d subslot=%d rates=%s control=%s sync=%s feedback=%s",
        handle->audioStreamingInterface,
        handle->alternateSetting,
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
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeStartGeneratedTone(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    (void) env;
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        return JNI_FALSE;
    }
    holder->playbackEnabled.store(false);
    return startStreamingInternal(holder.get(), StreamSource::Tone) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeConfigurePlayerBufferDuration(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint durationMs
) {
    (void) env;
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
    holder->pcmRingDurationMs = std::clamp(
        static_cast<int>(durationMs),
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
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
    (void) env;
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
        holder->playbackEnabled.store(false);
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
    if (holder->closing.load() || holder->devh == nullptr ||
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
    holder->pcmPipeline.setTargetGain(std::clamp(volume, 0.0f, 1.0f));
    std::string pipelineError;
    const size_t written = holder->pcmPipeline.write(
        data + offset,
        static_cast<size_t>(size),
        &pipelineError
    );
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
    (void) env;
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePlayPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr ||
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
    if (startStreamingInternal(holder.get(), StreamSource::PlayerPcm)) {
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
    (void) env;
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
    if (holder->running.load()) {
        stopStreamingInternal(holder.get());
    }
    holder->pcmPipeline.clear();
    holder->stagedPlayerFrames.store(0);
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
    (void) env;
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

    const bool restartTransport = holder->running.load();
    const bool resumePlayback = holder->playbackEnabled.exchange(false);
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    LOGI(
        "nativeFlushPlayerPcm begin: handle=%lld restart=%d resume=%d level=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        restartTransport ? 1 : 0,
        resumePlayback ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->completedAudioFrames.load())
    );
    if (restartTransport) {
        stopStreamingInternal(holder.get());
    }
    holder->pcmPipeline.clear();
    holder->pcmPipeline.resetCounters();
    holder->stagedPlayerFrames.store(0);
    holder->completedAudioFrames.store(0);

    if (restartTransport && resumePlayback &&
        !startStreamingInternal(holder.get(), StreamSource::PlayerPcm)) {
        LOGE(
            "nativeFlushPlayerPcm restart failed: handle=%lld error=%s",
            static_cast<long long>(handleValue),
            getErrorCopy(holder.get()).c_str()
        );
        return JNI_FALSE;
    }
    holder->playbackEnabled.store(resumePlayback);
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
    (void) env;
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load()) {
        return JNI_FALSE;
    }
    holder->pcmPipeline.setTargetGain(std::clamp(static_cast<float>(volume), 0.0f, 1.0f));
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeGetCompletedAudioFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    (void) env;
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
    (void) env;
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
    (void) env;
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeStop ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    LOGI(
        "nativeStop: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        sourceName(holder->streamSource.load())
    );
    holder->playbackEnabled.store(false);
    stopStreamingInternal(holder.get());
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeClose(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    (void) env;
    const auto holder = takeHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeClose ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
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
    const std::string report =
        "iface=" + std::to_string(holder->audioStreamingInterface) +
        " alt=" + std::to_string(holder->alternateSetting) +
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
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_UsbExclusiveNativeBridge_nativeLastOpenError(
    JNIEnv* env,
    jclass /*clazz*/
) {
    const std::string error = readLastOpenError();
    return env->NewStringUTF(error.c_str());
}
