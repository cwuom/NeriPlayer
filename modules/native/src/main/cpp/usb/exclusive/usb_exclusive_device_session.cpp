#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <memory>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "usb/control/usb_sample_rate_readback.h"
#include "usb/exclusive/usb_streaming_interface_lifecycle.h"
#include "usb/feedback/usb_feedback_rate_math.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

std::mutex g_lastOpenErrorLock;
std::string g_lastOpenError = "none";
std::mutex g_usbInterfaceTransitionLock;
std::chrono::steady_clock::time_point g_lastInterfaceTransitionAt {};

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

namespace {

using neri::usb::control::SampleRateReadback;

void acceptSampleRateReadback(
    SampleRateReadback readback,
    int requestedRate,
    int reportedRate,
    int attempt,
    int* negotiatedSampleRate,
    std::string* status
) {
    if (readback == SampleRateReadback::Verified) {
        *negotiatedSampleRate = reportedRate;
        *status = attempt == 0 ? "set_cur_verified" : "set_cur_verified_after_settle";
        return;
    }
    LOGW(
        "sample rate readback differs: requested=%d reported=%d attempts=%d",
        requestedRate,
        reportedRate,
        attempt + 1
    );
    *negotiatedSampleRate = requestedRate;
    *status = "set_cur_unverified_readback=" + std::to_string(reportedRate);
}

void waitForClockSettle(int attempt) {
    std::this_thread::sleep_for(std::chrono::milliseconds(neri::usb::control::sampleRateSettleDelayMs(attempt)));
}

} // namespace

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
    const auto outRequestType = makeClassEndpointRequestType(kLibusbEndpointOut);
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

    const auto inRequestType = makeClassEndpointRequestType(kLibusbEndpointIn);
    int getResult = 0;
    for (int attempt = 0;; ++attempt) {
        uint8_t verifiedBytes[3] = { 0, 0, 0 };
        getResult = libusb_control_transfer(
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
        if (getResult != static_cast<int>(sizeof(verifiedBytes))) break;
        const int verifiedRate = static_cast<int>(verifiedBytes[0]) |
            (static_cast<int>(verifiedBytes[1]) << 8) |
            (static_cast<int>(verifiedBytes[2]) << 16);
        const SampleRateReadback readback =
            neri::usb::control::classifySampleRateReadback(sampleRate, verifiedRate, attempt);
        if (readback == SampleRateReadback::Settle) {
            waitForClockSettle(attempt);
            continue;
        }
        acceptSampleRateReadback(readback, sampleRate, verifiedRate, attempt, negotiatedSampleRate, status);
        return true;
    }

    *negotiatedSampleRate = sampleRate;
    *status = getResult < 0
        ? std::string("set_cur_unverified:") + libusbErrName(getResult)
        : "set_cur_unverified_short_get";
    return true;
}

bool readUac2SampleRateRanges(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    std::vector<neri::usb::uac2::SampleRateSubrange>* ranges,
    std::string* status
) {
    if (deviceHandle == nullptr || ranges == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0) {
        if (status != nullptr) {
            *status = "uac2_invalid_range_input";
        }
        return false;
    }

    constexpr uint8_t kRangeRequest = 0x02;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    std::array<uint8_t, 512> rangeBytes {};
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointIn);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int result = libusb_control_transfer(
        deviceHandle,
        requestType,
        kRangeRequest,
        kSampleFrequencyControl,
        entityIndex,
        rangeBytes.data(),
        rangeBytes.size(),
        kControlTimeoutMs
    );
    if (result < 0) {
        if (status != nullptr) {
            *status = std::string("uac2_sample_rate_range_failed:") + libusbErrName(result);
        }
        return false;
    }
    std::string parseError;
    if (!neri::usb::uac2::parseSampleRateRanges(
            rangeBytes.data(),
            result,
            ranges,
            &parseError
        )) {
        if (status != nullptr) {
            *status = "uac2_sample_rate_range_parse_failed:" + parseError;
        }
        return false;
    }
    if (status != nullptr) {
        *status = "range_verified";
    }
    return true;
}

bool negotiateUac2SampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int sampleRate,
    neri::usb::uac2::ControlCapability sampleRateControl,
    int* negotiatedSampleRate,
    std::string* status,
    std::string* error
) {
    if (deviceHandle == nullptr || negotiatedSampleRate == nullptr || status == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0 || sampleRate <= 0) {
        if (error != nullptr) {
            *error = "invalid_uac2_sample_rate_negotiation_input";
        }
        return false;
    }

    if (sampleRateControl == neri::usb::uac2::ControlCapability::None) {
        int currentSampleRate = 0;
        std::string currentStatus;
        if (!readUac2CurrentSampleRate(
                deviceHandle,
                audioControlInterface,
                clockSourceId,
                &currentSampleRate,
                &currentStatus
            )) {
            if (error != nullptr) {
                *error = currentStatus;
            }
            return false;
        }
        if (currentSampleRate != sampleRate) {
            if (error != nullptr) {
                *error = "uac2_fixed_sample_rate_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" +
                    std::to_string(currentSampleRate);
            }
            return false;
        }
        *negotiatedSampleRate = currentSampleRate;
        *status = "fixed_get_cur_verified";
        return true;
    }

    if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadOnly) {
        int currentSampleRate = 0;
        std::string currentStatus;
        if (!readUac2CurrentSampleRate(
                deviceHandle,
                audioControlInterface,
                clockSourceId,
                &currentSampleRate,
                &currentStatus
            )) {
            if (error != nullptr) {
                *error = currentStatus;
            }
            return false;
        }
        if (currentSampleRate != sampleRate) {
            if (error != nullptr) {
                *error = "uac2_read_only_sample_rate_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" +
                    std::to_string(currentSampleRate);
            }
            return false;
        }
        *negotiatedSampleRate = currentSampleRate;
        *status = "read_only_get_cur_verified";
        return true;
    }

    if (sampleRateControl != neri::usb::uac2::ControlCapability::ReadWrite) {
        if (error != nullptr) {
            *error = "uac2_sample_rate_control_not_writable";
        }
        return false;
    }

    std::vector<neri::usb::uac2::SampleRateSubrange> ranges;
    std::string rangeStatus;
    if (readUac2SampleRateRanges(
            deviceHandle,
            audioControlInterface,
            clockSourceId,
            &ranges,
            &rangeStatus
        )) {
        const bool rangeSupportsTarget = std::any_of(
            ranges.begin(),
            ranges.end(),
            [sampleRate](const neri::usb::uac2::SampleRateSubrange& range) {
                return range.supports(sampleRate);
            }
        );
        if (!rangeSupportsTarget) {
            if (error != nullptr) {
                *error = "uac2_sample_rate_unsupported_by_range";
            }
            return false;
        }
    } else if (rangeStatus.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
        if (error != nullptr) {
            *error = rangeStatus;
        }
        return false;
    }

    constexpr uint8_t kCurRequest = 0x01;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[4] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFF)
    };
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointOut);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int setResult = libusb_control_transfer(
        deviceHandle,
        requestType,
        kCurRequest,
        kSampleFrequencyControl,
        entityIndex,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (setResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "uac2_sample_rate_set_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (setResult != static_cast<int>(sizeof(sampleRateBytes))) {
        if (error != nullptr) {
            *error = setResult < 0
                ? std::string("uac2_sample_rate_set_cur_failed:") + libusbErrName(setResult)
                : "uac2_sample_rate_set_cur_short";
        }
        return false;
    }

    int verifiedRate = 0;
    std::string getStatus;
    for (int attempt = 0; readUac2CurrentSampleRate(
            deviceHandle,
            audioControlInterface,
            clockSourceId,
            &verifiedRate,
            &getStatus
        ); ++attempt) {
        const SampleRateReadback readback =
            neri::usb::control::classifySampleRateReadback(sampleRate, verifiedRate, attempt);
        if (readback == SampleRateReadback::Settle) {
            waitForClockSettle(attempt);
            continue;
        }
        acceptSampleRateReadback(readback, sampleRate, verifiedRate, attempt, negotiatedSampleRate, status);
        return true;
    }

    if (getStatus.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
        if (error != nullptr) {
            *error = getStatus;
        }
        return false;
    }
    *negotiatedSampleRate = sampleRate;
    *status = "set_cur_unverified:" + getStatus;
    return true;
}

int setStreamingAlternateLocked(
    UsbExclusiveHandle* handle,
    int interfaceNumber,
    int activeAlternateSetting,
    int alternateSetting,
    const char* operation
) {
    if (handle == nullptr || handle->device.devh == nullptr ||
        interfaceNumber < 0 || activeAlternateSetting <= 0 || alternateSetting < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    const int rc = libusb_set_interface_alt_setting(
        handle->device.devh,
        interfaceNumber,
        alternateSetting
    );
    if (rc == LIBUSB_SUCCESS) {
        handle->device.streamingAlternateActive =
            alternateSetting == activeAlternateSetting && alternateSetting > 0;
        ++handle->device.streamingAlternateTransitions;
        handle->device.streamingAlternateStatus =
            std::string(operation != nullptr ? operation : "transition") +
            (handle->device.streamingAlternateActive ? ":active" : ":idle");
        markInterfaceTransitionLocked();
        LOGI(
            "streaming alternate transition: operation=%s iface=%d alt=%d active=%d",
            operation != nullptr ? operation : "transition",
            interfaceNumber,
            alternateSetting,
            handle->device.streamingAlternateActive ? 1 : 0
        );
        return rc;
    }
    if (alternateSetting == 0) {
        ++handle->device.streamingAlternateResetFailures;
    }
    handle->device.streamingAlternateStatus =
        std::string(operation != nullptr ? operation : "transition") +
        ":failed:" + libusbErrName(rc);
    if (rc == LIBUSB_ERROR_NO_DEVICE) {
        requestNoDeviceStop(handle);
    }
    LOGW(
        "streaming alternate transition failed: operation=%s iface=%d alt=%d err=%s",
        operation != nullptr ? operation : "transition",
        interfaceNumber,
        alternateSetting,
        libusbErrName(rc)
    );
    return rc;
}

int setStreamingAlternateLocked(
    UsbExclusiveHandle* handle,
    int alternateSetting,
    const char* operation
) {
    if (handle == nullptr) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    return setStreamingAlternateLocked(
        handle,
        handle->device.audioStreamingInterface,
        handle->device.alternateSetting,
        alternateSetting,
        operation
    );
}

bool parkStreamingAlternate(
    UsbExclusiveHandle* handle,
    const char* operation
) {
    if (handle == nullptr || handle->recovery.closing.load()) {
        return true;
    }
    if (!neri::usb::canTransitionStreamingInterface(
            handle->recovery.deviceOnline.load(),
            handle->recovery.detachBroadcastConfirmed.load(),
            handle->device.audioStreamingInterface,
            handle->device.alternateSetting
        )) {
        handle->device.streamingAlternateActive = false;
        handle->device.streamingAlternateStatus =
            std::string(operation != nullptr ? operation : "park") + ":skipped_offline";
        return true;
    }
    if (!handle->device.streamingAlternateActive) {
        return true;
    }
    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    const int rc = setStreamingAlternateLocked(handle, 0, operation);
    return rc != LIBUSB_ERROR_NO_DEVICE;
}

bool negotiateStoredSampleRate(
    UsbExclusiveHandle* handle,
    std::string* error
) {
    if (handle == nullptr) {
        if (error != nullptr) {
            *error = "stream_activation_invalid_handle";
        }
        return false;
    }
    int negotiatedSampleRate = 0;
    std::string negotiationStatus;
    std::string negotiationError;
    const bool negotiated = handle->device.uacVersion == 1
        ? negotiateUac1SampleRate(
            handle->device.devh,
            handle->device.outEndpoint,
            handle->device.sampleRate,
            handle->device.uac1Format,
            handle->device.uac1EndpointControls,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        )
        : handle->device.uacVersion == 2 && negotiateUac2SampleRate(
            handle->device.devh,
            handle->device.audioControlInterface,
            handle->device.uacClockSourceId,
            handle->device.sampleRate,
            handle->device.uac2SampleRateControl,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        );
    if (!negotiated) {
        if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
            requestNoDeviceStop(handle);
        }
        if (error != nullptr) {
            *error = "stream_activation_sample_rate_failed:" + negotiationError;
        }
        return false;
    }
    handle->device.negotiatedSampleRate = negotiatedSampleRate;
    handle->device.sampleRateControlStatus = negotiationStatus;
    return true;
}

bool activateStreamingAlternate(
    UsbExclusiveHandle* handle,
    std::string* error
) {
    if (handle == nullptr || handle->device.devh == nullptr || handle->recovery.closing.load() ||
        !neri::usb::canTransitionStreamingInterface(
            handle->recovery.deviceOnline.load(),
            handle->recovery.detachBroadcastConfirmed.load(),
            handle->device.audioStreamingInterface,
            handle->device.alternateSetting
        )) {
        if (error != nullptr) {
            *error = "stream_activation_invalid_state";
        }
        return false;
    }
    const neri::usb::StreamingInterfaceActivationPlan plan =
        neri::usb::streamingInterfaceActivationPlan(handle->device.uacVersion);
    if (!plan.supported) {
        if (error != nullptr) {
            *error = "stream_activation_unsupported_uac_version";
        }
        return false;
    }

    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    if (handle->device.streamingAlternateActive) {
        const int idleRc = setStreamingAlternateLocked(
            handle,
            0,
            "start_rearm_idle"
        );
        if (idleRc == LIBUSB_ERROR_NO_DEVICE) {
            if (error != nullptr) {
                *error = "stream_activation_idle_failed:LIBUSB_ERROR_NO_DEVICE";
            }
            return false;
        }
    }

    for (const neri::usb::StreamingInterfaceActivationStep step : plan.steps) {
        if (step == neri::usb::StreamingInterfaceActivationStep::ActivateAlternate) {
            const int rc = setStreamingAlternateLocked(
                handle,
                handle->device.alternateSetting,
                "stream_start"
            );
            if (rc != LIBUSB_SUCCESS) {
                if (error != nullptr) {
                    *error = std::string("stream_activation_set_alt_failed:") +
                        libusbErrName(rc);
                }
                return false;
            }
            continue;
        }
        if (!negotiateStoredSampleRate(handle, error)) {
            if (handle->device.streamingAlternateActive && handle->recovery.deviceOnline.load()) {
                setStreamingAlternateLocked(handle, 0, "activation_rollback");
            }
            return false;
        }
    }
    handle->device.streamingAlternateStatus = "stream_start:ready";
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool reconfigureOpenedPlayerPcmOutput(
    UsbExclusiveHandle* handle,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    std::string* error
) {
    if (handle == nullptr || handle->device.devh == nullptr) {
        if (error != nullptr) {
            *error = "reconfigure_invalid_handle";
        }
        return false;
    }
    if (handle->transfer.running.load() || !handle->recovery.deviceOnline.load() || handle->recovery.closing.load()) {
        if (error != nullptr) {
            *error = "reconfigure_requires_idle_handle";
        }
        return false;
    }
    if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || subslotBytes <= 0 ||
        bitsPerSample > subslotBytes * 8) {
        if (error != nullptr) {
            *error = "reconfigure_invalid_output_format";
        }
        return false;
    }

    StreamingAltSelection selection;
    std::string selectionFailure;
    if (!findStreamingAlt(
            handle->device.devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            handle->device.usbSpeed,
            &selection,
            &selectionFailure
        )) {
        if (error != nullptr) {
            *error = "reconfigure_no_compatible_output:" + selectionFailure;
        }
        return false;
    }
    if (selection.interfaceNumber != handle->device.audioStreamingInterface ||
        selection.audioControlInterface != handle->device.audioControlInterface ||
        !sameClaimPlan(handle->device.claimedAudioInterfaces, selection.claimPlan)) {
        if (error != nullptr) {
            *error = "reconfigure_requires_reopen:claim_or_interface_changed";
        }
        return false;
    }
    if (!parkStreamingAlternate(handle, "reconfigure_idle")) {
        if (error != nullptr) {
            *error = "reconfigure_idle_alt_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }

    if (selection.uacVersion == 1) {
        std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        const int rc = setStreamingAlternateLocked(
            handle,
            selection.interfaceNumber,
            selection.alternateSetting,
            selection.alternateSetting,
            "reconfigure_uac1"
        );
        if (rc != LIBUSB_SUCCESS) {
            if (error != nullptr) {
                *error = std::string("reconfigure_set_alt_failed:") + libusbErrName(rc);
            }
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            return false;
        }
    }

    int negotiatedSampleRate = 0;
    std::string negotiationStatus;
    std::string negotiationError;
    const bool sampleRateNegotiated = selection.uacVersion == 1
        ? negotiateUac1SampleRate(
            handle->device.devh,
            selection.outEndpoint,
            sampleRate,
            selection.uac1.format,
            selection.uac1.endpointControls,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        )
        : negotiateUac2SampleRate(
            handle->device.devh,
            selection.audioControlInterface,
            selection.uac2.clockSourceId,
            sampleRate,
            selection.uac2.sampleRateControl,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        );
    if (!sampleRateNegotiated) {
        if (selection.uacVersion == 1 && handle->device.streamingAlternateActive &&
            handle->recovery.deviceOnline.load()) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            setStreamingAlternateLocked(
                handle,
                selection.interfaceNumber,
                selection.alternateSetting,
                0,
                "reconfigure_rollback"
            );
        }
        if (error != nullptr) {
            *error = "reconfigure_sample_rate_failed:" + negotiationError;
        }
        if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
            requestNoDeviceStop(handle);
        }
        return false;
    }

    if (selection.uacVersion == 2) {
        std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        const int rc = setStreamingAlternateLocked(
            handle,
            selection.interfaceNumber,
            selection.alternateSetting,
            selection.alternateSetting,
            "reconfigure_uac2"
        );
        if (rc != LIBUSB_SUCCESS) {
            if (error != nullptr) {
                *error = std::string("reconfigure_set_alt_failed:") + libusbErrName(rc);
            }
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            return false;
        }
    }

    handle->device.sampleRate = sampleRate;
    handle->device.channelCount = channelCount;
    handle->device.bitsPerSample = bitsPerSample;
    handle->device.subslotBytes = selection.uacVersion == 1
        ? selection.uac1.format.subslotBytes
        : selection.uac2.format.subslotBytes;
    handle->device.frameBytes = handle->device.channelCount * handle->device.subslotBytes;
    handle->device.audioStreamingInterface = selection.interfaceNumber;
    handle->device.audioControlInterface = selection.audioControlInterface;
    handle->device.alternateSetting = selection.alternateSetting;
    handle->device.outEndpoint = selection.outEndpoint;
    handle->device.endpointMaxPacketBytes = selection.endpointMaxPacketBytes;
    handle->device.endpointInterval = selection.endpointInterval;
    handle->device.explicitFeedbackEnabled = selection.explicitFeedbackEnabled;
    handle->device.feedbackEndpoint = selection.feedbackEndpoint;
    handle->device.feedbackEndpointMaxPacketBytes = selection.feedbackEndpointMaxPacketBytes;
    handle->device.feedbackEndpointInterval = selection.feedbackEndpointInterval;
    handle->device.feedbackTimingProfile = selection.feedbackTimingProfile;
    handle->device.uacVersion = selection.uacVersion;
    handle->device.uacClockSourceId = selection.uac2.clockSourceId;
    handle->device.uac1Format = selection.uac1.format;
    handle->device.uac1EndpointControls = selection.uac1.endpointControls;
    handle->device.uac2SampleRateControl = selection.uac2.sampleRateControl;
    handle->device.descriptorSampleRates = selection.uacVersion == 1
        ? selection.uac1.format.sampleRateSummary()
        : "uac2_clock_source";
    handle->device.formatSelectionReason = selection.reason;
    handle->device.sampleRateControlStatus = negotiationStatus;
    handle->device.endpointSyncType = selection.syncType;
    handle->device.endpointFeedback = selection.feedback;
    handle->device.completeAudioFunctionClaim = selection.completeClaimPlan;
    handle->device.negotiatedSampleRate = negotiatedSampleRate;
    if (!configureTransferPlan(handle)) {
        parkStreamingAlternate(handle, "reconfigure_capacity_failed");
        if (error != nullptr) {
            *error = "reconfigure_endpoint_capacity_too_small";
        }
        return false;
    }
    assignNewNativeStreamGeneration(handle);
    if (!parkStreamingAlternate(handle, "reconfigure_ready")) {
        if (error != nullptr) {
            *error = "reconfigure_ready_idle_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    clearError(handle);
    if (error != nullptr) {
        error->clear();
    }
    LOGI(
        "reconfigureOpenedPlayerPcmOutput ok: iface=%d alt=%d sr=%d negotiated=%d ch=%d bits=%d subslot=%d packetBytes=%d",
        handle->device.audioStreamingInterface,
        handle->device.alternateSetting,
        handle->device.sampleRate,
        handle->device.negotiatedSampleRate,
        handle->device.channelCount,
        handle->device.bitsPerSample,
        handle->device.subslotBytes,
        handle->transfer.bytesPerUsbFrame
    );
    return true;
}

void releaseClaimedAudioInterfaces(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->device.devh == nullptr) {
        return;
    }
    for (auto entry = handle->device.claimedAudioInterfaces.rbegin();
         entry != handle->device.claimedAudioInterfaces.rend();
         ++entry) {
        const int releaseRc = libusb_release_interface(handle->device.devh, entry->interfaceNumber);
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
    handle->device.claimedAudioInterfaces.clear();
}

int claimAudioInterface(UsbExclusiveHandle* handle, int interfaceNumber) {
    if (handle == nullptr || handle->device.devh == nullptr || interfaceNumber < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    return libusb_claim_interface(handle->device.devh, interfaceNumber);
}

bool claimAudioFunction(
    UsbExclusiveHandle* handle,
    const std::vector<ClaimedUsbInterface>& claimPlan,
    std::string* failureReason
) {
    if (handle == nullptr || handle->device.devh == nullptr || claimPlan.empty()) {
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
        handle->device.claimedAudioInterfaces.push_back(
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

void finishClosedUsbResources(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    if (handle->device.devh != nullptr && !handle->recovery.detachBroadcastConfirmed.load()) {
        const bool streamingInterfaceClaimed = std::any_of(
            handle->device.claimedAudioInterfaces.begin(),
            handle->device.claimedAudioInterfaces.end(),
            [handle](const ClaimedUsbInterface& entry) {
                return entry.interfaceNumber == handle->device.audioStreamingInterface;
            }
        );
        if (streamingInterfaceClaimed && handle->device.alternateSetting > 0) {
            const int idleAltRc = setStreamingAlternateLocked(
                handle,
                0,
                "close"
            );
            if (idleAltRc == LIBUSB_SUCCESS) {
                LOGI(
                    "restored idle alt setting: iface=%d alt=0",
                    handle->device.audioStreamingInterface
                );
            }
        }
        releaseFeatureUnits(handle, true);
        releaseClaimedAudioInterfaces(handle);
        libusb_close(handle->device.devh);
        handle->device.devh = nullptr;
    } else if (handle->device.devh != nullptr) {
        LOGW("skip interface ioctls after physical USB detach");
        handle->device.streamingAlternateActive = false;
        handle->device.streamingAlternateStatus = "close:detached";
        handle->device.claimedAudioInterfaces.clear();
        releaseFeatureUnits(handle, false);
        libusb_close(handle->device.devh);
        handle->device.devh = nullptr;
    }
    if (handle->device.ctx != nullptr) {
        libusb_exit(handle->device.ctx);
        handle->device.ctx = nullptr;
    }
    if (handle->device.dupFd >= 0) {
        close(handle->device.dupFd);
        handle->device.dupFd = -1;
    }
    LOGI("closeHandleInternal done");
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeOpen(
    JNIEnv* env,
    jclass /*clazz*/,
    jint fd,
    jint sampleRate,
    jint channelCount,
    jint bitsPerSample,
    jint subslotBytes
) {
    static_cast<void>(env);
    serviceParkedHandlesOnce();
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
        assignNewNativeStreamGeneration(handle.get());
        handle->device.sampleRate = sampleRate > 0 ? sampleRate : 48000;
        handle->device.channelCount = channelCount > 0 ? channelCount : 2;
        handle->device.bitsPerSample = bitsPerSample > 0 ? bitsPerSample : 16;
        handle->device.subslotBytes = subslotBytes > 0 ? subslotBytes : 2;
        if (handle->device.sampleRate < 8000 || handle->device.sampleRate > 768000 ||
            handle->device.channelCount < 1 || handle->device.channelCount > 8 ||
            handle->device.bitsPerSample < 8 || handle->device.bitsPerSample > 32 ||
            handle->device.subslotBytes < 1 || handle->device.subslotBytes > 4 ||
            handle->device.bitsPerSample > handle->device.subslotBytes * 8) {
            LOGE(
                "nativeOpen rejected invalid output format: sr=%d ch=%d bits=%d subslot=%d",
                handle->device.sampleRate,
                handle->device.channelCount,
                handle->device.bitsPerSample,
                handle->device.subslotBytes
            );
            rememberLastOpenError("invalid_output_format");
            return 0L;
        }
        std::unique_lock<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        while (true) {
            const int remainingCooldownMs = remainingInterfaceTransitionCooldownMsLocked();
            if (remainingCooldownMs <= 0) {
                break;
            }
            LOGI("nativeOpen waits for USB interface cooldown: %dms", remainingCooldownMs);
            transitionGuard.unlock();
            std::this_thread::sleep_for(std::chrono::milliseconds(remainingCooldownMs));
            transitionGuard.lock();
        }
        handle->device.frameBytes = handle->device.channelCount * handle->device.subslotBytes;

        handle->device.dupFd = dup(fd);
        if (handle->device.dupFd < 0) {
            rememberLastOpenError("dup_failed");
            return 0L;
        }

        int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("set_option_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }

        rc = libusb_init(&handle->device.ctx);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("libusb_init_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        libusb_set_option(handle->device.ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

        rc = libusb_wrap_sys_device(handle->device.ctx, static_cast<intptr_t>(handle->device.dupFd), &handle->device.devh);
        if (rc != LIBUSB_SUCCESS || handle->device.devh == nullptr) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            LOGE("nativeOpen wrap_sys_device failed: fd=%d rc=%d err=%s", handle->device.dupFd, rc, libusbErrName(rc));
            const std::string error = std::string("wrap_sys_device_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        libusb_device* wrappedDevice = libusb_get_device(handle->device.devh);
        if (wrappedDevice != nullptr) {
            libusb_device_descriptor descriptor {};
            if (libusb_get_device_descriptor(wrappedDevice, &descriptor) == LIBUSB_SUCCESS) {
                handle->device.vendorId = descriptor.idVendor;
                handle->device.productId = descriptor.idProduct;
                handle->device.deviceRelease = descriptor.bcdDevice;
            }
            handle->device.busNumber = libusb_get_bus_number(wrappedDevice);
            handle->device.deviceAddress = libusb_get_device_address(wrappedDevice);
        }

#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
        const int autoDetachRc = libusb_set_auto_detach_kernel_driver(handle->device.devh, 1);
        LOGI("nativeOpen set_auto_detach_kernel_driver rc=%d", autoDetachRc);
        if (autoDetachRc == LIBUSB_ERROR_NO_DEVICE) {
            requestNoDeviceStop(handle.get());
            rememberLastOpenError("auto_detach_failed:LIBUSB_ERROR_NO_DEVICE");
            closeHandleInternal(handle);
            return 0L;
        }
#endif

        handle->device.usbSpeed = libusb_get_device_speed(libusb_get_device(handle->device.devh));
        StreamingAltSelection selection;
        std::string selectionFailure;
        if (!findStreamingAlt(
                handle->device.devh,
                handle->device.sampleRate,
                handle->device.channelCount,
                handle->device.bitsPerSample,
                handle->device.subslotBytes,
                handle->device.usbSpeed,
                &selection,
                &selectionFailure
            )) {
            if (selectionFailure.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "no_compatible_usb_audio_format:" + selectionFailure;
            LOGE("nativeOpen compatible USB audio alt not found: %s", selectionFailure.c_str());
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        handle->device.audioStreamingInterface = selection.interfaceNumber;
        handle->device.audioControlInterface = selection.audioControlInterface;
        handle->device.alternateSetting = selection.alternateSetting;
        handle->device.outEndpoint = selection.outEndpoint;
        handle->device.endpointMaxPacketBytes = selection.endpointMaxPacketBytes;
        handle->device.endpointInterval = selection.endpointInterval;
        handle->device.explicitFeedbackEnabled = selection.explicitFeedbackEnabled;
        handle->device.feedbackEndpoint = selection.feedbackEndpoint;
        handle->device.feedbackEndpointMaxPacketBytes =
            selection.feedbackEndpointMaxPacketBytes;
        handle->device.feedbackEndpointInterval = selection.feedbackEndpointInterval;
        handle->device.feedbackTimingProfile = selection.feedbackTimingProfile;
        handle->device.uacVersion = selection.uacVersion;
        handle->device.subslotBytes = selection.uacVersion == 1
            ? selection.uac1.format.subslotBytes
            : selection.uac2.format.subslotBytes;
        handle->device.frameBytes = handle->device.channelCount * handle->device.subslotBytes;
        handle->device.uacClockSourceId = selection.uac2.clockSourceId;
        handle->device.uac1Format = selection.uac1.format;
        handle->device.uac1EndpointControls = selection.uac1.endpointControls;
        handle->device.uac2SampleRateControl = selection.uac2.sampleRateControl;
        handle->device.descriptorSampleRates = selection.uacVersion == 1
            ? selection.uac1.format.sampleRateSummary()
            : "uac2_clock_source";
        handle->device.formatSelectionReason = selection.reason;
        handle->device.endpointSyncType = selection.syncType;
        handle->device.endpointFeedback = selection.feedback;
        handle->device.completeAudioFunctionClaim = selection.completeClaimPlan;

        std::string claimFailure;
        if (!claimAudioFunction(handle.get(), selection.claimPlan, &claimFailure)) {
            LOGE("nativeOpen claim UAC function failed: %s", claimFailure.c_str());
            const std::string error = claimFailure.empty()
                ? "claim_audio_function_failed"
                : claimFailure;
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        applyBitPerfectFeatureUnits(handle.get());

        if (selection.uacVersion == 1) {
            rc = setStreamingAlternateLocked(
                handle.get(),
                handle->device.alternateSetting,
                "open_uac1"
            );
            if (rc != LIBUSB_SUCCESS) {
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle.get());
                }
                LOGE(
                    "nativeOpen set alt failed: iface=%d alt=%d err=%s",
                    handle->device.audioStreamingInterface,
                    handle->device.alternateSetting,
                    libusbErrName(rc)
                );
                const std::string error = std::string("set_alt_failed:") + libusbErrName(rc);
                rememberLastOpenError(error);
                setError(handle.get(), error);
                closeHandleInternal(handle);
                return 0L;
            }
        }

        std::string negotiationError;
        const bool sampleRateNegotiated = selection.uacVersion == 1
            ? negotiateUac1SampleRate(
                handle->device.devh,
                handle->device.outEndpoint,
                handle->device.sampleRate,
                selection.uac1.format,
                selection.uac1.endpointControls,
                &handle->device.negotiatedSampleRate,
                &handle->device.sampleRateControlStatus,
                &negotiationError
            )
            : negotiateUac2SampleRate(
                handle->device.devh,
                handle->device.audioControlInterface,
                handle->device.uacClockSourceId,
                handle->device.sampleRate,
                selection.uac2.sampleRateControl,
                &handle->device.negotiatedSampleRate,
                &handle->device.sampleRateControlStatus,
                &negotiationError
            );
        if (!sampleRateNegotiated) {
            if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "sample_rate_negotiation_failed:" + negotiationError;
            LOGE(
                "nativeOpen UAC%d sample rate negotiation failed: %s",
                selection.uacVersion,
                negotiationError.c_str()
            );
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }

        if (selection.uacVersion == 2) {
            rc = setStreamingAlternateLocked(
                handle.get(),
                handle->device.alternateSetting,
                "open_uac2"
            );
            if (rc != LIBUSB_SUCCESS) {
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle.get());
                }
                LOGE(
                    "nativeOpen set alt failed after UAC2 clock configure: iface=%d alt=%d err=%s",
                    handle->device.audioStreamingInterface,
                    handle->device.alternateSetting,
                    libusbErrName(rc)
                );
                const std::string error = std::string("set_alt_failed:") + libusbErrName(rc);
                rememberLastOpenError(error);
                setError(handle.get(), error);
                closeHandleInternal(handle);
                return 0L;
            }
        }

        if (!configureTransferPlan(handle.get())) {
            const std::string error = "endpoint_capacity_too_small";
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        const int idleAltRc = setStreamingAlternateLocked(
            handle.get(),
            0,
            "open_ready"
        );
        if (idleAltRc == LIBUSB_ERROR_NO_DEVICE) {
            const std::string error = "open_ready_idle_failed:LIBUSB_ERROR_NO_DEVICE";
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        rememberLastOpenError("none");
        clearError(handle.get());

        LOGI(
            "nativeOpen ok: uac=%d iface=%d acIface=%d alt=%d claimed=%zu fullFunctionClaim=%d "
            "outEp=0x%02X packetBytes=%d endpointMax=%d speed=%d interval=%d ips=%d "
            "sr=%d negotiated=%d ch=%d bits=%d subslot=%d rates=%s control=%s "
            "clock=%d sync=%s feedback=%s feedbackEp=0x%02X feedbackPacket=%d "
            "feedbackInterval=%d",
            handle->device.uacVersion,
            handle->device.audioStreamingInterface,
            handle->device.audioControlInterface,
            handle->device.alternateSetting,
            handle->device.claimedAudioInterfaces.size(),
            handle->device.completeAudioFunctionClaim ? 1 : 0,
            handle->device.outEndpoint,
            handle->transfer.bytesPerUsbFrame,
            handle->device.endpointMaxPacketBytes,
            handle->device.usbSpeed,
            handle->device.endpointInterval,
            handle->transfer.intervalsPerSecond,
            handle->device.sampleRate,
            handle->device.negotiatedSampleRate,
            handle->device.channelCount,
            handle->device.bitsPerSample,
            handle->device.subslotBytes,
            handle->device.descriptorSampleRates.c_str(),
            handle->device.sampleRateControlStatus.c_str(),
            handle->device.uacClockSourceId,
            handle->device.endpointSyncType.c_str(),
            handle->device.endpointFeedback.c_str(),
            handle->device.feedbackEndpoint,
            handle->device.feedbackEndpointMaxPacketBytes,
            handle->device.feedbackEndpointInterval
        );
        return registerHandle(handle);
    } catch (const std::bad_alloc&) {
        try {
            rememberLastOpenError("native_open_allocation_failed");
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle);
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
            closeHandleInternal(handle);
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
            closeHandleInternal(handle);
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen unknown exception");
        return 0L;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeReconfigurePlayerPcmOutput(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint sampleRate,
    jint channelCount,
    jint bitsPerSample,
    jint subslotBytes
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW(
            "nativeReconfigurePlayerPcmOutput rejected: invalid handle=%lld",
            static_cast<long long>(handleValue)
        );
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    std::string error;
    if (!reconfigureOpenedPlayerPcmOutput(
            holder.get(),
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            &error
        )) {
        if (!error.empty()) {
            setError(holder.get(), error);
            LOGW(
                "nativeReconfigurePlayerPcmOutput failed: handle=%lld error=%s",
                static_cast<long long>(handleValue),
                error.c_str()
            );
        }
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeLastOpenError(
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

} // namespace neri::usb::exclusive
