#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <limits>
#include <memory>
#include <new>
#include <system_error>
#include <sys/resource.h>
#include <unordered_map>

#include "libusb/libusb.h"
#include "usb/exclusive/usb_exclusive_device_selection.h"
#include "usb/exclusive/usb_exclusive_session_internal.h"
#include "usb/exclusive/usb_player_replay_buffer.h"
#include "usb/exclusive/usb_player_startup_preroll.h"
#include "usb/exclusive/usb_recovery_action_latch.h"
#include "usb/exclusive/usb_runtime_report_v2.h"
#include "usb/exclusive/usb_streaming_interface_lifecycle.h"
#include "usb/exclusive/usb_streaming_sync_policy.h"
#include "usb/feedback/usb_explicit_feedback_runtime.h"
#include "usb/feedback/usb_feedback_in_transfer_set.h"
#include "usb/feedback/usb_feedback_rate_math.h"
#include "usb/feedback/usb_libusb_feedback_backend.h"
#include "usb/iso/usb_iso_packet_scheduler.h"
#include "usb/iso/usb_iso_transfer_window.h"
#include "usb/iso/usb_iso_transfer_health.h"
#include "usb/pcm/usb_pcm_pipeline.h"
#include "usb/uac1/usb_uac1_format.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"
#include "usb/uac2/usb_uac2_format.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

struct ParkedHandleSlot {
    std::shared_ptr<UsbExclusiveHandle> handle;
    std::chrono::steady_clock::time_point parkedAt {};
    int quarantineIndex = 0;
    bool pumpActive = false;
};

std::mutex g_handleRegistryLock;
std::unordered_map<jlong, std::shared_ptr<UsbExclusiveHandle>> g_handleRegistry;
std::atomic<jlong> g_nextHandleToken { 1 };
std::atomic<int64_t> g_nextNativeStreamGeneration { 1 };
std::atomic<int64_t> g_nextRecoveryActionId { 1 };
std::atomic<int> g_nextQuarantineIndex { 1 };
std::atomic<int> g_quarantinedDrainHandles { 0 };
std::mutex g_parkedHandlesLock;
std::array<ParkedHandleSlot, kMaximumParkedHandles> g_parkedHandles;
std::array<std::shared_ptr<UsbExclusiveHandle>, kMaximumHardRetainedHandles>
    g_hardRetainedHandles;

bool allocateTransfers(UsbExclusiveHandle* handle);
void freeTransfers(UsbExclusiveHandle* handle);
bool refillTransfer(UsbExclusiveHandle* handle, libusb_transfer* transfer);
int activateBufferedIsoReserveTransfers(UsbExclusiveHandle* handle);
void eventLoopThread(UsbExclusiveHandle* handle) noexcept;
bool stopStreamingInternal(UsbExclusiveHandle* handle);
bool closeHandleInternal(const std::shared_ptr<UsbExclusiveHandle>& handle);
void latchTerminalRecoveryAction(
    UsbExclusiveHandle* handle,
    neri::usb::UsbRuntimeRecoveryAction action
);
bool reconfigureOpenedPlayerPcmOutput(
    UsbExclusiveHandle* handle,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    std::string* error
);

int64_t steadyClockNanoseconds() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

int exponentialBackoffMs(int consecutiveErrors) {
    const int shift = std::min(std::max(0, consecutiveErrors - 1), 6);
    return std::min(
        kEventLoopErrorBackoffMaxMs,
        kEventLoopErrorBackoffBaseMs << shift
    );
}

int parkedExponentialBackoffMs(int consecutiveErrors) {
    const int shift = std::min(std::max(0, consecutiveErrors - 1), 6);
    return std::min(
        kParkedErrorBackoffMaxMs,
        kParkedErrorBackoffBaseMs << shift
    );
}

timeval timeoutFromMilliseconds(int timeoutMs) {
    const int boundedTimeoutMs = std::max(0, timeoutMs);
    timeval timeout {};
    timeout.tv_sec = boundedTimeoutMs / 1000;
    timeout.tv_usec = (boundedTimeoutMs % 1000) * 1000;
    return timeout;
}

bool shouldLogRepeatedError(int consecutiveErrors) {
    return consecutiveErrors <= 3 ||
        (consecutiveErrors & (consecutiveErrors - 1)) == 0 ||
        consecutiveErrors == kEventLoopConsecutiveErrorLimit;
}

void interruptUsbEventHandler(UsbExclusiveHandle* handle) {
    if (handle != nullptr && handle->device.ctx != nullptr) {
        libusb_interrupt_event_handler(handle->device.ctx);
    }
}

bool shouldStopTransferSubmission(const UsbExclusiveHandle* handle) {
    return handle == nullptr ||
        !handle->recovery.deviceOnline.load() ||
        handle->recovery.stopRequested.load() ||
        handle->recovery.closing.load() ||
        handle->recovery.transportFailed.load();
}

void requestDeviceStop(UsbExclusiveHandle* handle, bool detachBroadcastConfirmed) {
    if (handle == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
    if (detachBroadcastConfirmed) {
        handle->recovery.detachBroadcastConfirmed.store(true);
    }
    handle->recovery.deviceOnline.store(false);
    handle->player.focusMuted.store(true);
    handle->player.playbackEnabled.store(false);
    handle->player.playerPaused.store(false);
    handle->recovery.stopRequested.store(true);
    latchTerminalRecoveryAction(
        handle,
        neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
    );
}

void requestNoDeviceStop(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->recovery.noDeviceObserved.store(true);
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

void clearError(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->recovery.lock);
    handle->recovery.lastError.clear();
}

void setError(UsbExclusiveHandle* handle, const char* error) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->recovery.lock);
    handle->recovery.lastError = error != nullptr ? error : "unknown";
}

void setError(UsbExclusiveHandle* handle, const std::string& error) {
    setError(handle, error.c_str());
}

std::string getErrorCopy(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return "invalid_handle";
    std::lock_guard<std::mutex> guard(handle->recovery.lock);
    return handle->recovery.lastError;
}

void assignNewNativeStreamGeneration(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->recovery.nativeStreamGeneration = g_nextNativeStreamGeneration.fetch_add(1);
    handle->recovery.recoveryActionLatch.reset(handle->recovery.nativeStreamGeneration);
}

void latchTerminalRecoveryAction(
    UsbExclusiveHandle* handle,
    neri::usb::UsbRuntimeRecoveryAction action
) {
    if (handle == nullptr ||
        (action != neri::usb::UsbRuntimeRecoveryAction::FreshOpen &&
            action != neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent)) {
        return;
    }
    handle->recovery.recoveryActionLatch.latch(
        action,
        g_nextRecoveryActionId.fetch_add(1)
    );
}

void markTransportFailed(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->recovery.transportFailed.store(true);
    latchTerminalRecoveryAction(
        handle,
        handle->recovery.deviceOnline.load()
            ? neri::usb::UsbRuntimeRecoveryAction::FreshOpen
            : neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
    );
}

std::string runtimeCandidateId(const UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return "unknown-candidate";
    }
    char buffer[192] = {};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "vid%04X-pid%04X-bcd%04X-bus%u-dev%u-uac%d-iface%d-alt%d-out%02X-sr%d-ch%d-b%d-s%d",
        handle->device.vendorId,
        handle->device.productId,
        handle->device.deviceRelease,
        static_cast<unsigned int>(handle->device.busNumber),
        static_cast<unsigned int>(handle->device.deviceAddress),
        handle->device.uacVersion,
        handle->device.audioStreamingInterface,
        handle->device.alternateSetting,
        handle->device.outEndpoint,
        handle->device.sampleRate,
        handle->device.channelCount,
        handle->device.bitsPerSample,
        handle->device.subslotBytes
    );
    return buffer;
}

std::string runtimeErrorCode(
    const UsbExclusiveHandle* handle,
    const std::string& lastError
) {
    if (handle == nullptr) {
        return "NativeInternalError";
    }
    if (!handle->recovery.deviceOnline.load() || handle->recovery.noDeviceObserved.load()) {
        return "DeviceDetached";
    }
    if (!handle->recovery.transportFailed.load()) {
        return "None";
    }
    if (lastError.find("first_completion_timeout") != std::string::npos) {
        return "TransferFirstCompletionTimeout";
    }
    if (lastError.find("completion_stalled") != std::string::npos) {
        return "TransferCompletionStalled";
    }
    if (lastError.find("feedback_initial_lock_timeout") != std::string::npos) {
        return "FeedbackInitialLockTimeout";
    }
    if (lastError.find("feedback_payload") != std::string::npos) {
        return "FeedbackPayloadInvalid";
    }
    if (lastError.find("feedback_transfer") != std::string::npos) {
        return "FeedbackTransferFailed";
    }
    if (lastError.find("feedback_lost") != std::string::npos) {
        return "FeedbackLost";
    }
    if (lastError.find("feedback_packet_capacity") != std::string::npos) {
        return "FeedbackPacketCapacityExceeded";
    }
    if (lastError.find("iso_packet") != std::string::npos) {
        return "IsoPacketErrorBurst";
    }
    if (lastError.find("cancel_drain") != std::string::npos) {
        return "CancelDrainTimeout";
    }
    if (lastError.find("quarantine") != std::string::npos) {
        return "Quarantined";
    }
    return "TransportFailed";
}

bool feedbackClockCanStream(
    neri::usb::feedback::FeedbackClockState state
) {
    return state == neri::usb::feedback::FeedbackClockState::Locked ||
        state == neri::usb::feedback::FeedbackClockState::Holdover ||
        state == neri::usb::feedback::FeedbackClockState::Relocking;
}

neri::usb::UsbRuntimeFeedbackState runtimeFeedbackState(
    bool explicitFeedbackEnabled,
    const neri::usb::feedback::ExplicitFeedbackRuntimeSnapshot& snapshot
) {
    if (!explicitFeedbackEnabled) {
        return neri::usb::UsbRuntimeFeedbackState::Disabled;
    }
    if (snapshot.terminalFailure ||
        snapshot.state ==
            neri::usb::feedback::ExplicitFeedbackRuntimeState::Failed ||
        snapshot.gate.clock.state ==
            neri::usb::feedback::FeedbackClockState::Failed) {
        return neri::usb::UsbRuntimeFeedbackState::Failed;
    }
    if (snapshot.state ==
            neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped &&
        snapshot.reusableAfterStop) {
        return neri::usb::UsbRuntimeFeedbackState::Locked;
    }
    switch (snapshot.gate.clock.state) {
        case neri::usb::feedback::FeedbackClockState::Acquiring:
            return neri::usb::UsbRuntimeFeedbackState::Acquiring;
        case neri::usb::feedback::FeedbackClockState::Locked:
            return neri::usb::UsbRuntimeFeedbackState::Locked;
        case neri::usb::feedback::FeedbackClockState::Holdover:
            return neri::usb::UsbRuntimeFeedbackState::Holdover;
        case neri::usb::feedback::FeedbackClockState::Relocking:
            return neri::usb::UsbRuntimeFeedbackState::Relocking;
        case neri::usb::feedback::FeedbackClockState::Disabled:
            return snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Ready ||
                snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped ||
                snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Disabled
                ? neri::usb::UsbRuntimeFeedbackState::Priming
                : neri::usb::UsbRuntimeFeedbackState::Acquiring;
        case neri::usb::feedback::FeedbackClockState::Failed:
            return neri::usb::UsbRuntimeFeedbackState::Failed;
    }
    return neri::usb::UsbRuntimeFeedbackState::Failed;
}

int64_t reportCounter(uint64_t value) {
    return value > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())
        ? std::numeric_limits<int64_t>::max()
        : static_cast<int64_t>(value);
}

uint64_t saturatedCounterSum(uint64_t first, uint64_t second) {
    return second > std::numeric_limits<uint64_t>::max() - first
        ? std::numeric_limits<uint64_t>::max()
        : first + second;
}

double feedbackRateHz(neri::usb::feedback::FeedbackRateQ32 rateQ32) {
    return std::ldexp(static_cast<double>(rateQ32), -32);
}

int64_t signedFeedbackRatePpm(
    neri::usb::feedback::FeedbackRateQ32 rateQ32,
    neri::usb::feedback::FeedbackRateQ32 nominalRateQ32
) {
    if (rateQ32 == 0 || nominalRateQ32 == 0) {
        return 0;
    }
    uint32_t magnitude = 0;
    if (neri::usb::feedback::computeRateDeltaPpm(
            rateQ32,
            nominalRateQ32,
            &magnitude
        ) != neri::usb::feedback::FeedbackMathStatus::Ok) {
        return 0;
    }
    const auto signedMagnitude = static_cast<int64_t>(magnitude);
    return rateQ32 < nominalRateQ32 ? -signedMagnitude : signedMagnitude;
}

neri::usb::UsbRecoveryActionAckStatus acknowledgeRecoveryAction(
    UsbExclusiveHandle* handle,
    int64_t actionGeneration,
    int64_t actionId
) {
    return handle != nullptr
        ? handle->recovery.recoveryActionLatch.acknowledge(
            actionGeneration,
            actionId,
            handle->recovery.closing.load()
        )
        : neri::usb::UsbRecoveryActionAckStatus::NoPending;
}


int64_t steadyClockMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}


const char* sourceName(StreamSource source) {
    return source == StreamSource::PlayerPcm ? "player_pcm" : "tone";
}

bool feedbackTransfersOutstanding(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return false;
    }
    const auto snapshot = handle->transfer.feedbackInTransferSet.snapshot();
    return snapshot.inFlight > 0 || snapshot.callbacksInProgress > 0;
}

bool streamTransfersOutstanding(const UsbExclusiveHandle* handle) {
    return handle != nullptr &&
        (handle->transfer.inFlightTransfers.load() > 0 ||
            feedbackTransfersOutstanding(handle));
}

bool feedbackTransferSetActive(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return false;
    }
    return handle->transfer.feedbackInTransferSet.snapshot().state !=
        neri::usb::feedback::FeedbackInTransferSetState::Empty;
}

bool configureExplicitFeedbackRuntime(
    UsbExclusiveHandle* handle,
    std::string* error
) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        if (error != nullptr) {
            error->clear();
        }
        return true;
    }
    if (handle->device.feedbackTimingProfile.status !=
        neri::usb::uac2::Uac2FeedbackProfileStatus::Valid) {
        if (error != nullptr) {
            *error = "feedback_profile_invalid";
        }
        return false;
    }
    neri::usb::feedback::FeedbackRateQ32 nominalRateQ32 = 0;
    const auto nominalStatus = neri::usb::feedback::makeFeedbackRateQ32(
        static_cast<uint32_t>(handle->device.sampleRate),
        static_cast<uint32_t>(handle->transfer.intervalsPerSecond),
        &nominalRateQ32
    );
    if (nominalStatus != neri::usb::feedback::FeedbackMathStatus::Ok ||
        handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds == 0 ||
        handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds >
            static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
        if (error != nullptr) {
            *error = "feedback_nominal_rate_invalid";
        }
        return false;
    }

    const auto currentTransfers = handle->transfer.feedbackInTransferSet.snapshot();
    if (currentTransfers.state !=
            neri::usb::feedback::FeedbackInTransferSetState::Empty ||
        currentTransfers.inFlight != 0 ||
        currentTransfers.callbacksInProgress != 0) {
        if (error != nullptr) {
            *error = "feedback_transfer_set_not_drained";
        }
        return false;
    }

    handle->transfer.feedbackNominalRateQ32 = nominalRateQ32;
    const neri::usb::feedback::ExplicitFeedbackRuntimeConfig config {
        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration),
        handle->device.feedbackTimingProfile.decodeProfile,
        nominalRateQ32,
        static_cast<int64_t>(
            handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds
        ),
        static_cast<uint32_t>(std::max(1, handle->device.frameBytes)),
        static_cast<uint32_t>(std::max(1, handle->device.endpointMaxPacketBytes)),
        kExplicitFeedbackBootstrapPacketLimit,
        handle->device.feedbackTimingProfile.zeroLengthReportPermitted
    };
    if (!handle->transfer.feedbackRuntime.configure(config)) {
        if (error != nullptr) {
            *error = "feedback_runtime_configure_failed";
        }
        return false;
    }
    const neri::usb::feedback::FeedbackInTransferConfig transferConfig {
        handle->device.devh,
        handle->device.feedbackEndpoint,
        static_cast<uint32_t>(std::max(1, handle->device.feedbackEndpointMaxPacketBytes)),
        kExplicitFeedbackTransferCount,
        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration)
    };
    std::string transferError;
    if (!handle->transfer.feedbackInTransferSet.allocate(
            transferConfig,
            &handle->transfer.feedbackRuntime,
            &transferError
        )) {
        handle->transfer.feedbackRuntime.stop();
        if (error != nullptr) {
            *error = transferError.empty()
                ? "feedback_transfer_allocate_failed"
                : transferError;
        }
        return false;
    }
    if (!handle->transfer.feedbackRuntime.start(steadyClockNanoseconds())) {
        std::string ignoredError;
        handle->transfer.feedbackInTransferSet.beginStop(&ignoredError);
        handle->transfer.feedbackInTransferSet.freeDrained(&ignoredError);
        if (error != nullptr) {
            *error = "feedback_runtime_start_failed";
        }
        return false;
    }
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool stopExplicitFeedbackRuntime(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return true;
    }
    handle->transfer.feedbackRuntime.stop();
    std::string error;
    const bool stopped = handle->transfer.feedbackInTransferSet.beginStop(&error);
    if (!stopped && !error.empty()) {
        setError(handle, error);
    }
    return stopped;
}

bool freeExplicitFeedbackTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return true;
    }
    const auto snapshot = handle->transfer.feedbackInTransferSet.snapshot();
    if (snapshot.state == neri::usb::feedback::FeedbackInTransferSetState::Empty) {
        return true;
    }
    std::string error;
    if (!handle->transfer.feedbackInTransferSet.freeDrained(&error)) {
        if (!error.empty()) {
            setError(handle, error);
        }
        return false;
    }
    return true;
}

void fillToneBuffer(UsbExclusiveHandle* handle, uint8_t* buffer, size_t bytes) {
    if (handle == nullptr || buffer == nullptr || bytes == 0 ||
        handle->device.frameBytes <= 0 || handle->device.channelCount <= 0 || handle->device.subslotBytes <= 0) {
        return;
    }
    const int frames = static_cast<int>(bytes) / handle->device.frameBytes;
    const auto sampleRate = static_cast<double>(handle->device.sampleRate);
    const double phaseStep = 2.0 * M_PI * static_cast<double>(kGeneratedToneFrequencyHz) / sampleRate;
    const double amplitude = 0.30;

    for (int frame = 0; frame < frames; ++frame) {
        const double sample = std::sin(handle->transfer.tonePhase) * amplitude;
        handle->transfer.tonePhase += phaseStep;
        if (handle->transfer.tonePhase > 2.0 * M_PI) {
            handle->transfer.tonePhase -= 2.0 * M_PI;
        }

        for (int ch = 0; ch < handle->device.channelCount; ++ch) {
            const int offset = frame * handle->device.frameBytes + ch * handle->device.subslotBytes;
            neri::usb::writeIntegerPcmSample(
                buffer + offset,
                handle->device.subslotBytes,
                handle->device.bitsPerSample,
                static_cast<float>(sample)
            );
        }
    }
}

bool startStreamingInternal(
    UsbExclusiveHandle* handle,
    StreamSource source
) {
    if (handle == nullptr || handle->device.devh == nullptr || !handle->recovery.deviceOnline.load() ||
        handle->recovery.closing.load()) {
        LOGW("startStreamingInternal rejected: invalid handle");
        return false;
    }
    LOGI(
        "startStreamingInternal request: source=%s running=%d failed=%d playback=%d "
        "transferBytes=%d transferCount=%d packets=%d",
        sourceName(source),
        handle->transfer.running.load() ? 1 : 0,
        handle->recovery.transportFailed.load() ? 1 : 0,
        handle->player.playbackEnabled.load() ? 1 : 0,
        handle->transfer.transferBytes,
        handle->transfer.transferCount,
        handle->transfer.packetsPerTransfer
    );
    if (
        handle->transfer.running.load() ||
        !handle->transfer.transfers.empty() ||
        handle->transfer.eventThread.joinable()
    ) {
        {
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            if (!handle->recovery.deviceOnline.load() || handle->recovery.stopRequested.load() ||
                handle->recovery.closing.load()) {
                return false;
            }
            if (!handle->recovery.transportFailed.load() && handle->transfer.streamSource.load() == source) {
                return true;
            }
        }
        LOGW(
            "startStreamingInternal restarts active stream: oldSource=%s newSource=%s failed=%d",
            sourceName(handle->transfer.streamSource.load()),
            sourceName(source),
            handle->recovery.transportFailed.load() ? 1 : 0
        );
        if (!stopStreamingInternal(handle)) {
            return false;
        }
    }

    clearError(handle);
    handle->transfer.streamSource.store(source);
    handle->transfer.completedTransfers.store(0);
    handle->transfer.submitErrors.store(0);
    handle->transfer.isoPacketErrors.store(0);
    handle->transfer.isoPacketErrorTransfers.store(0);
    handle->transfer.isoPacketErrorScore.store(0);
    handle->transfer.scheduledPackets.store(0);
    handle->transfer.scheduledFrames.store(0);
    handle->transfer.packetFramesMin.store(std::numeric_limits<int>::max());
    handle->transfer.packetFramesMax.store(0);
    handle->transfer.lastTransferBytes.store(0);
    handle->player.shortWriteWarnings.store(0);
    handle->transfer.firstTransferSubmittedAtMs.store(0);
    handle->transfer.lastTransferCompletionAtMs.store(0);
    handle->transfer.packetScheduler.reset();
    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (!handle->recovery.deviceOnline.load() || handle->recovery.closing.load()) {
            setError(handle, "stream_start_cancelled_device_offline");
            return false;
        }
        handle->recovery.stopRequested.store(false);
        handle->recovery.transportFailed.store(false);
        handle->transfer.inFlightTransfers.store(0);
    }
    std::string interfaceActivationError;
    if (!activateStreamingAlternate(handle, &interfaceActivationError)) {
        setError(
            handle,
            interfaceActivationError.empty()
                ? "streaming_interface_activation_failed"
                : interfaceActivationError
        );
        markTransportFailed(handle);
        parkStreamingAlternate(handle, "stream_start_failed");
        return false;
    }
    assignNewNativeStreamGeneration(handle);
    if (handle->device.explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!configureExplicitFeedbackRuntime(handle, &feedbackError)) {
            setError(
                handle,
                feedbackError.empty()
                    ? "feedback_runtime_configure_failed"
                    : feedbackError
            );
            markTransportFailed(handle);
            parkStreamingAlternate(handle, "stream_start_feedback_config_failed");
            return false;
        }
    }
    if (source == StreamSource::PlayerPcm) {
        handle->player.playerStartupPreroll.arm(handle->device.sampleRate, kPlayerStartupPrerollMs);
        handle->player.pcmPipeline.armTransportStartRamp();
    }
    if (!allocateTransfers(handle)) {
        LOGE("allocateTransfers failed before stream start: error=%s", getErrorCopy(handle).c_str());
        freeTransfers(handle);
        markTransportFailed(handle);
        parkStreamingAlternate(handle, "stream_start_transfer_allocation_failed");
        return false;
    }

    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle)) {
            setError(handle, "stream_start_cancelled_after_allocation");
        } else {
            handle->transfer.running.store(true);
        }
    }
    if (!handle->transfer.running.load()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (handle->device.explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!handle->transfer.feedbackInTransferSet.submitAll(&feedbackError)) {
            setError(
                handle,
                feedbackError.empty()
                    ? "feedback_transfer_submit_failed"
                    : feedbackError
            );
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
    }
    const int initialTransferCount = std::min<int>(
        handle->transfer.baseTransferCount,
        static_cast<int>(handle->transfer.transfers.size())
    );
    for (int index = 0; index < initialTransferCount; ++index) {
        libusb_transfer* transfer = handle->transfer.transfers[static_cast<size_t>(index)];
        int rc = LIBUSB_ERROR_NO_DEVICE;
        bool cancelled = false;
        bool refilled = false;
        {
            std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            cancelled = shouldStopTransferSubmission(handle) || !handle->transfer.running.load();
            if (!cancelled) {
                refilled = refillTransfer(handle, transfer);
                if (refilled) {
                    rc = libusb_submit_transfer(transfer);
                    if (rc == LIBUSB_SUCCESS) {
                        const int64_t submittedAtMs = steadyClockMillis();
                        int64_t firstSubmittedAtMs = 0;
                        if (handle->transfer.firstTransferSubmittedAtMs.compare_exchange_strong(
                                firstSubmittedAtMs,
                                submittedAtMs
                            )) {
                            handle->transfer.lastTransferCompletionAtMs.store(submittedAtMs);
                        }
                        handle->transfer.transferSubmitted[static_cast<size_t>(index)] = 1;
                        handle->transfer.inFlightTransfers.fetch_add(1);
                    }
                }
            }
        }
        if (cancelled) {
            setError(handle, "stream_start_cancelled_during_submit");
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
        if (!refilled) {
            setError(handle, "initial_transfer_refill_failed");
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
        if (rc != LIBUSB_SUCCESS) {
            setError(handle, std::string("submit_failed:") + libusbErrName(rc));
            LOGE("libusb_submit_transfer failed: %s", libusbErrName(rc));
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
    }
    try {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->transfer.running.load()) {
            setError(handle, "stream_start_cancelled_before_event_thread");
        } else {
            handle->transfer.eventThread = std::thread(eventLoopThread, handle);
        }
    } catch (const std::system_error& error) {
        setError(handle, std::string("event_thread_start_failed:") + error.what());
        markTransportFailed(handle);
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (!handle->transfer.eventThread.joinable()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }

    LOGI(
        "native stream started: source=%s inFlight=%d transferBytes=%d packetBytes=%d",
        sourceName(source),
        handle->transfer.inFlightTransfers.load(),
        handle->transfer.transferBytes,
        handle->transfer.bytesPerUsbFrame
    );
    return true;
}

bool startStreamingSafely(UsbExclusiveHandle* handle, StreamSource source) noexcept {
    try {
        return startStreamingInternal(handle, source);
    } catch (const std::exception& error) {
        if (handle != nullptr) {
            markTransportFailed(handle);
            try {
                setError(handle, std::string("stream_start_exception:") + error.what());
            } catch (...) {
            }
        }
        LOGE("native stream start exception: %s", error.what());
        return false;
    } catch (...) {
        if (handle != nullptr) {
            markTransportFailed(handle);
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

const char* explicitFeedbackFailureError(
    neri::usb::feedback::ExplicitFeedbackRuntimeFailure failure
) {
    using Failure = neri::usb::feedback::ExplicitFeedbackRuntimeFailure;
    switch (failure) {
        case Failure::InvalidConfiguration:
            return "feedback_runtime_invalid_configuration";
        case Failure::TransferCancelled:
            return "feedback_transfer_cancelled";
        case Failure::TransferFailed:
            return "feedback_transfer_failed";
        case Failure::DeviceDetached:
            return "feedback_device_detached";
        case Failure::FeedbackClock:
            return "feedback_lost";
        case Failure::PacketCapacity:
            return "feedback_packet_capacity_exceeded";
        case Failure::InternalInvariant:
            return "feedback_runtime_internal_invariant";
        case Failure::None:
            return "feedback_runtime_failed";
    }
    return "feedback_runtime_failed";
}

void failForExplicitFeedbackRuntime(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return;
    }
    const auto snapshot = handle->transfer.feedbackRuntime.snapshot();
    if (snapshot.failure ==
        neri::usb::feedback::ExplicitFeedbackRuntimeFailure::DeviceDetached) {
        requestNoDeviceStop(handle);
    }
    const bool initialLockFailure =
        snapshot.failure ==
            neri::usb::feedback::ExplicitFeedbackRuntimeFailure::FeedbackClock &&
        snapshot.validPackets == 0 && !snapshot.realPcmReleased;
    setError(
        handle,
        initialLockFailure
            ? "feedback_initial_lock_timeout"
            : explicitFeedbackFailureError(snapshot.failure)
    );
    markTransportFailed(handle);
}

int applyIsoPacketLengths(
    UsbExclusiveHandle* handle,
    libusb_transfer* transfer
) {
    if (handle == nullptr || transfer == nullptr || handle->transfer.bytesPerUsbFrame <= 0) {
        return -1;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    if (userData != nullptr) {
        userData->forceSilence = false;
    }
    const int packetCount = transfer->num_iso_packets;
    int totalAssigned = 0;
    const bool explicitFeedback = handle->device.explicitFeedbackEnabled;
    bool allowRealPayload = false;
    if (explicitFeedback) {
        const auto snapshot = handle->transfer.feedbackRuntime.snapshot();
        const auto clockState = snapshot.gate.clock.state;
        const bool feedbackUsable =
            clockState == neri::usb::feedback::FeedbackClockState::Locked ||
            clockState == neri::usb::feedback::FeedbackClockState::Holdover ||
            clockState == neri::usb::feedback::FeedbackClockState::Relocking;
        const bool sourceAvailable = handle->transfer.streamSource.load() == StreamSource::Tone ||
            (handle->player.playbackEnabled.load() &&
                handle->recovery.deviceOnline.load() &&
                !handle->player.focusMuted.load());
        allowRealPayload = feedbackUsable && sourceAvailable &&
            !snapshot.terminalFailure;
        if (userData != nullptr) {
            userData->forceSilence = !allowRealPayload;
        }
    }
    for (int packetIndex = 0; packetIndex < packetCount; ++packetIndex) {
        int packetBytes = 0;
        int packetFrames = 0;
        if (explicitFeedback) {
            const auto plan = handle->transfer.feedbackRuntime.nextPacket(allowRealPayload);
            if (plan.status ==
                    neri::usb::feedback::StreamGatePacketStatus::TerminalFailure ||
                plan.packet.status != neri::usb::feedback::FeedbackMathStatus::Ok) {
                failForExplicitFeedbackRuntime(handle);
                return -1;
            }
            if (plan.status !=
                    neri::usb::feedback::StreamGatePacketStatus::ZeroBootstrap &&
                plan.status !=
                    neri::usb::feedback::StreamGatePacketStatus::PlayerPacket) {
                setError(handle, "feedback_packet_scheduler_not_ready");
                markTransportFailed(handle);
                return -1;
            }
            if (plan.allZero && userData != nullptr) {
                userData->forceSilence = true;
            }
            packetBytes = static_cast<int>(plan.packet.bytes);
            packetFrames = static_cast<int>(plan.packet.frames);
        } else {
            const neri::usb::IsoPacketPlan plan = handle->transfer.packetScheduler.next();
            packetBytes = plan.bytes;
            packetFrames = plan.frames;
        }
        if (packetBytes < 0 || packetBytes > handle->device.endpointMaxPacketBytes) {
            setError(handle, "scheduled_packet_exceeds_endpoint_capacity");
            markTransportFailed(handle);
            return -1;
        }
        transfer->iso_packet_desc[packetIndex].length = packetBytes;
        totalAssigned += packetBytes;
        handle->transfer.scheduledPackets.fetch_add(1);
        handle->transfer.scheduledFrames.fetch_add(packetFrames);
        updateAtomicMinimum(handle->transfer.packetFramesMin, packetFrames);
        updateAtomicMaximum(handle->transfer.packetFramesMax, packetFrames);
    }
    transfer->length = totalAssigned;
    handle->transfer.lastTransferBytes.store(totalAssigned);
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

int64_t queuedPlayerReplayFrames(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->device.frameBytes <= 0) {
        return 0;
    }
    return static_cast<int64_t>(
        handle->player.playerReplayBuffer.queuedBytes() / static_cast<size_t>(handle->device.frameBytes)
    );
}

void clearPlayerReplayState(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->player.playerReplayBuffer.clear();
    handle->player.nextPlayerSequence.store(1);
    handle->player.preserveCancelledPlayerFrames.store(false);
    handle->player.playerReplayFailed.store(false);
}

bool preserveCancelledPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    const libusb_transfer* transfer,
    int64_t completedPrefixFrames
) {
    if (handle == nullptr || userData == nullptr || transfer == nullptr ||
        userData->queuedPlayerFrames <= 0) {
        return false;
    }
    const int slot = userData->slot;
    if (slot < 0 || slot >= static_cast<int>(handle->transfer.transferBuffers.size()) ||
        handle->device.frameBytes <= 0 || userData->playerSequence == 0) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    const int64_t queuedFrames = userData->queuedPlayerFrames;
    const int64_t completedFrames = std::clamp<int64_t>(
        completedPrefixFrames,
        0,
        queuedFrames
    );
    const int64_t replayFrames = queuedFrames - completedFrames;
    const size_t replayOffset = static_cast<size_t>(completedFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    const size_t replayBytes = static_cast<size_t>(replayFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    const auto& buffer = handle->transfer.transferBuffers[static_cast<size_t>(slot)];
    if (replayOffset + replayBytes > buffer.size() || transfer->buffer == nullptr ||
        (replayBytes > 0 && !handle->player.playerReplayBuffer.push(
            userData->playerSequence,
            transfer->buffer + replayOffset,
            replayBytes
        ))) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, queuedFrames);
    if (completedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(completedFrames);
    }
    return true;
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
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, frames);
    const int64_t boundedCompletedFrames = std::clamp<int64_t>(completedFrames, 0, frames);
    if (boundedCompletedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(boundedCompletedFrames);
    }
    const int64_t droppedFrames = frames - boundedCompletedFrames;
    if (droppedFrames > 0) {
        handle->player.pcmPipeline.addDroppedFrames(droppedFrames);
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
    if (slot < 0 || slot >= static_cast<int>(handle->transfer.transferBuffers.size())) {
        return false;
    }
    auto& buffer = handle->transfer.transferBuffers[slot];
    transfer->buffer = buffer.data();
    const int transferBytes = applyIsoPacketLengths(handle, transfer);
    if (transferBytes < 0 || static_cast<size_t>(transferBytes) > buffer.size()) {
        return false;
    }

    if (handle->transfer.streamSource.load() == StreamSource::PlayerPcm) {
        const auto transferSize = static_cast<size_t>(transferBytes);
        if ((userData != nullptr && userData->forceSilence) ||
            handle->player.playerStartupPreroll.fillSilenceIfNeeded(
                buffer.data(),
                transferSize,
                handle->device.frameBytes
            )) {
            if (userData != nullptr && userData->forceSilence) {
                std::memset(buffer.data(), 0, transferSize);
            }
            if (userData != nullptr) {
                userData->queuedPlayerFrames = 0;
                userData->playerSequence = 0;
            }
            return true;
        }
        const bool renderPlayerPcm = handle->player.playbackEnabled.load() &&
            handle->recovery.deviceOnline.load() &&
            !handle->player.focusMuted.load();
        const size_t replayBytes = renderPlayerPcm
            ? handle->player.playerReplayBuffer.read(buffer.data(), transferSize)
            : 0;
        const size_t pipelineBytes = handle->player.pcmPipeline.fill(
            buffer.data() + replayBytes,
            transferSize - replayBytes,
            renderPlayerPcm
        );
        const size_t playerBytes = replayBytes + pipelineBytes;
        if (playerBytes > 0) {
            handle->player.pcmPipeline.applyTransportStartRamp(buffer.data(), playerBytes);
        }
        if (userData != nullptr) {
            const int64_t queuedFrames = static_cast<int64_t>(
                playerBytes / static_cast<size_t>(std::max(1, handle->device.frameBytes))
            );
            userData->queuedPlayerFrames = queuedFrames;
            userData->playerSequence = queuedFrames > 0
                ? handle->player.nextPlayerSequence.fetch_add(1)
                : 0;
            handle->player.stagedPlayerFrames.fetch_add(queuedFrames);
        }
    } else {
        if (userData != nullptr && userData->forceSilence) {
            std::memset(buffer.data(), 0, static_cast<size_t>(transferBytes));
        } else {
            fillToneBuffer(handle, buffer.data(), static_cast<size_t>(transferBytes));
        }
    }
    return true;
}

int activateBufferedIsoReserveTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transfer.streamSource.load() != StreamSource::PlayerPcm ||
        !handle->transfer.running.load() || !handle->player.playbackEnabled.load() ||
        shouldStopTransferSubmission(handle)) {
        return 0;
    }

    const int targetTransferCount = std::min<int>(
        handle->transfer.targetTransferCount.load(),
        static_cast<int>(handle->transfer.transfers.size())
    );
    if (targetTransferCount <= handle->transfer.baseTransferCount) {
        return 0;
    }
    const neri::usb::PcmPipelineSnapshot initialPcm = handle->player.pcmPipeline.snapshot();
    int activationBudget = neri::usb::isoReserveActivationBudgetAfterWarmup(
        handle->transfer.completedTransfers.load(),
        initialPcm.levelBytes,
        handle->transfer.transferBytes,
        handle->transfer.baseTransferCount,
        handle->transfer.inFlightTransfers.load(),
        targetTransferCount
    );
    int activated = 0;
    while (activationBudget > 0) {
        std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->transfer.running.load() ||
            !handle->player.playbackEnabled.load() ||
            handle->transfer.inFlightTransfers.load() >= targetTransferCount) {
            break;
        }

        int slot = -1;
        for (int index = handle->transfer.baseTransferCount;
             index < targetTransferCount;
             ++index) {
            if (handle->transfer.transferSubmitted[static_cast<size_t>(index)] == 0) {
                slot = index;
                break;
            }
        }
        if (slot < 0) {
            break;
        }

        const neri::usb::PcmPipelineSnapshot pcm = handle->player.pcmPipeline.snapshot();
        if (pcm.levelBytes < static_cast<size_t>(handle->transfer.transferBytes)) {
            break;
        }
        libusb_transfer* transfer = handle->transfer.transfers[static_cast<size_t>(slot)];
        if (!refillTransfer(handle, transfer)) {
            setError(handle, "reserve_transfer_refill_failed");
            markTransportFailed(handle);
            break;
        }
        const int rc = libusb_submit_transfer(transfer);
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(
                handle,
                &handle->transfer.transferUserData[static_cast<size_t>(slot)],
                false
            );
            handle->transfer.submitErrors.fetch_add(1);
            setError(handle, std::string("reserve_submit_failed:") + libusbErrName(rc));
            markTransportFailed(handle);
            break;
        }
        handle->transfer.transferSubmitted[static_cast<size_t>(slot)] = 1;
        handle->transfer.inFlightTransfers.fetch_add(1);
        ++activated;
        --activationBudget;
    }
    return activated;
}

struct TransferCallbackCompletion final {
    UsbExclusiveHandle* handle = nullptr;
    int slot = -1;
    bool resubmitted = false;

    ~TransferCallbackCompletion() {
        if (handle != nullptr && !resubmitted) {
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            if (slot >= 0 && slot < static_cast<int>(handle->transfer.transferSubmitted.size())) {
                handle->transfer.transferSubmitted[static_cast<size_t>(slot)] = 0;
            }
            handle->transfer.inFlightTransfers.fetch_sub(1);
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
    const bool staleGeneration = userData->generation == 0 ||
        userData->generation !=
            static_cast<uint64_t>(handle->recovery.nativeStreamGeneration);
    if (
        userData->slot >= 0 &&
        userData->slot < static_cast<int>(handle->transfer.transferStatuses.size())
    ) {
        handle->transfer.transferStatuses[static_cast<size_t>(userData->slot)] = transfer->status;
    }
    TransferCallbackCompletion completion { handle, userData->slot, false };
    try {
        const bool transferCompleted = transfer->status == LIBUSB_TRANSFER_COMPLETED;
        const bool transferCancelled = transfer->status == LIBUSB_TRANSFER_CANCELLED;
        bool packetsCompleted = transferCompleted;
        bool packetReportedNoDevice = false;
        bool completedPacketPrefixOpen = true;
        int failedPacketCount = 0;
        int firstFailedPacketIndex = -1;
        int firstFailedPacketStatus = LIBUSB_TRANSFER_COMPLETED;
        int64_t completedPacketBytes = 0;
        int64_t completedPacketPrefixBytes = 0;
        if (transferCompleted || transferCancelled) {
            for (int packetIndex = 0; packetIndex < transfer->num_iso_packets; ++packetIndex) {
                const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[packetIndex];
                if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
                    completedPacketPrefixOpen = false;
                    if (transferCompleted) {
                        packetsCompleted = false;
                        failedPacketCount += 1;
                        if (firstFailedPacketIndex < 0) {
                            firstFailedPacketIndex = packetIndex;
                            firstFailedPacketStatus = packet.status;
                        }
                    }
                }
                if (packet.status == LIBUSB_TRANSFER_NO_DEVICE) {
                    packetReportedNoDevice = true;
                }
                if (packet.status == LIBUSB_TRANSFER_COMPLETED) {
                    const int completedBytes = neri::usb::completedIsoPacketBytes(
                        true,
                        packet.length,
                        packet.actual_length
                    );
                    completedPacketBytes += completedBytes;
                    if (completedPacketPrefixOpen) {
                        if (completedBytes == 0) {
                            completedPacketPrefixOpen = false;
                        } else {
                            completedPacketPrefixBytes += completedBytes;
                        }
                    }
                }
            }
        }
        const int64_t completedPacketFrames = handle->device.frameBytes > 0
            ? completedPacketBytes / handle->device.frameBytes
            : 0;
        const int64_t completedPacketPrefixFrames = handle->device.frameBytes > 0
            ? completedPacketPrefixBytes / handle->device.frameBytes
            : 0;
        const bool replayedCancelledFrames =
            transferCancelled &&
            handle->player.preserveCancelledPlayerFrames.load() &&
            preserveCancelledPlayerFrames(
                handle,
                userData,
                transfer,
                completedPacketPrefixFrames
            );
        if (!replayedCancelledFrames) {
            settlePreparedPlayerFrames(handle, userData, completedPacketFrames);
        }
        if (staleGeneration) {
            return;
        }
        if (packetsCompleted) {
            const int currentScore = handle->transfer.isoPacketErrorScore.load();
            handle->transfer.isoPacketErrorScore.store(
                neri::usb::updateIsoPacketErrorScore(currentScore, 0)
            );
            handle->transfer.lastTransferCompletionAtMs.store(steadyClockMillis());
            const int completedBefore = handle->transfer.completedTransfers.fetch_add(1);
            if (completedBefore == 0) {
                LOGI(
                    "first USB transfer completed: packets=%d requested=%d actual=%d",
                    transfer->num_iso_packets,
                    transfer->length,
                    transfer->actual_length
                );
            }
        } else if (transferCompleted && !packetReportedNoDevice) {
            const int errorTransfers = handle->transfer.isoPacketErrorTransfers.fetch_add(1) + 1;
            const int totalPacketErrors = handle->transfer.isoPacketErrors.fetch_add(failedPacketCount) +
                failedPacketCount;
            const int errorScore = neri::usb::updateIsoPacketErrorScore(
                handle->transfer.isoPacketErrorScore.load(),
                failedPacketCount
            );
            handle->transfer.isoPacketErrorScore.store(errorScore);
            const bool fatalPacketBurst = neri::usb::shouldFailForIsoPacketErrors(errorScore);
            if (errorTransfers <= 3 || (errorTransfers & (errorTransfers - 1)) == 0 ||
                fatalPacketBurst) {
                LOGW(
                    "USB isochronous packet error: failedPackets=%d firstPacket=%d "
                    "firstStatus=%d score=%d/%d errorTransfers=%d totalPacketErrors=%d "
                    "completed=%d inFlight=%d actual=%d fatal=%d",
                    failedPacketCount,
                    firstFailedPacketIndex,
                    firstFailedPacketStatus,
                    errorScore,
                    neri::usb::kIsoPacketErrorFailureScore,
                    errorTransfers,
                    totalPacketErrors,
                    handle->transfer.completedTransfers.load(),
                    handle->transfer.inFlightTransfers.load(),
                    transfer->actual_length,
                    fatalPacketBurst ? 1 : 0
                );
            }
            if (fatalPacketBurst) {
                handle->transfer.submitErrors.fetch_add(1);
                markTransportFailed(handle);
                setError(handle, "iso_packet_status_failed");
                return;
            }
        } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
            if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE || packetReportedNoDevice) {
                requestNoDeviceStop(handle);
            }
            handle->transfer.submitErrors.fetch_add(1);
            markTransportFailed(handle);
            const std::string transferError = transferCompleted
                ? "iso_packet_status_failed"
                : std::string("transfer_status=") + std::to_string(transfer->status);
            setError(handle, transferError);
            LOGW(
                "USB transfer callback status=%d packetsCompleted=%d completed=%d "
                "submitErrors=%d inFlight=%d actual=%d",
                transfer->status,
                packetsCompleted ? 1 : 0,
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load(),
                handle->transfer.inFlightTransfers.load(),
                transfer->actual_length
            );
            return;
        }

        if (shouldStopTransferSubmission(handle)) {
            return;
        }
        if (neri::usb::shouldRetireIsoReserveTransfer(
                userData->slot,
                handle->transfer.baseTransferCount,
                handle->transfer.inFlightTransfers.load(),
                handle->transfer.targetTransferCount.load()
            )) {
            return;
        }

        int rc = LIBUSB_ERROR_NO_DEVICE;
        {
            std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
            if (!refillTransfer(handle, transfer)) {
                handle->transfer.submitErrors.fetch_add(1);
                markTransportFailed(handle);
                return;
            }
            {
                std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
                if (shouldStopTransferSubmission(handle)) {
                    settlePreparedPlayerFrames(handle, userData, false);
                    return;
                }
                rc = libusb_submit_transfer(transfer);
                if (rc == LIBUSB_SUCCESS) {
                    completion.resubmitted = true;
                }
            }
        }
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(handle, userData, false);
            handle->transfer.submitErrors.fetch_add(1);
            markTransportFailed(handle);
            setError(handle, std::string("resubmit_failed:") + libusbErrName(rc));
            LOGE(
                "libusb_submit_transfer resubmit failed: %s completed=%d submitErrors=%d inFlight=%d",
                libusbErrName(rc),
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load(),
                handle->transfer.inFlightTransfers.load()
            );
            return;
        }
    } catch (const std::exception& error) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("transfer_callback_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB transfer callback exception: %s", error.what());
    } catch (...) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, "transfer_callback_unknown_exception");
        } catch (...) {
        }
        LOGE("USB transfer callback unknown exception");
    }
}

bool allocateTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transfer.transferBytes <= 0) {
        return false;
    }

    const int allocationCount = handle->transfer.streamSource.load() == StreamSource::PlayerPcm
        ? handle->transfer.transferCount
        : handle->transfer.baseTransferCount;
    try {
        handle->transfer.transfers.reserve(allocationCount);
        handle->transfer.transferBuffers.reserve(allocationCount);
        handle->transfer.transferUserData.reserve(allocationCount);
        handle->transfer.transferStatuses.reserve(allocationCount);
        handle->transfer.transferSubmitted.reserve(allocationCount);

        for (int index = 0; index < allocationCount; ++index) {
            libusb_transfer* transfer = libusb_alloc_transfer(handle->transfer.packetsPerTransfer);
            if (transfer == nullptr) {
                setError(handle, "libusb_alloc_transfer_failed");
                return false;
            }
            try {
                handle->transfer.transferBuffers.emplace_back(
                    static_cast<size_t>(handle->transfer.transferBytes),
                    0
                );
                auto& buffer = handle->transfer.transferBuffers.back();
                handle->transfer.transferUserData.push_back(
                    TransferUserData {
                        handle,
                        index,
                        0,
                        0,
                        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration),
                        false
                    }
                );
                handle->transfer.transferStatuses.push_back(-1);
                handle->transfer.transferSubmitted.push_back(0);

                libusb_fill_iso_transfer(
                    transfer,
                    handle->device.devh,
                    handle->device.outEndpoint,
                    buffer.data(),
                    handle->transfer.transferBytes,
                    handle->transfer.packetsPerTransfer,
                    transferCallback,
                    &handle->transfer.transferUserData.back(),
                    0
                );
                handle->transfer.transfers.push_back(transfer);
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
    stopExplicitFeedbackRuntime(handle);
    if (!feedbackTransfersOutstanding(handle)) {
        freeExplicitFeedbackTransfers(handle);
    }
    for (libusb_transfer* transfer : handle->transfer.transfers) {
        if (transfer != nullptr) {
            libusb_free_transfer(transfer);
        }
    }
    for (TransferUserData& userData : handle->transfer.transferUserData) {
        settlePreparedPlayerFrames(handle, &userData, false);
    }
    handle->transfer.transfers.clear();
    handle->transfer.transferBuffers.clear();
    handle->transfer.transferUserData.clear();
    handle->transfer.transferStatuses.clear();
    handle->transfer.transferSubmitted.clear();
    handle->transfer.inFlightTransfers.store(0);
}

void eventLoopThread(UsbExclusiveHandle* handle) noexcept {
    try {
        configureUsbEventThreadPriority();
        int consecutiveErrors = 0;
        LOGI("USB event loop entered");
        while (handle->recovery.deviceOnline.load() && !handle->recovery.stopRequested.load()) {
            int eventWaitTimeoutMs = kEventLoopWaitTimeoutMs;
            if (handle->device.explicitFeedbackEnabled &&
                handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds > 0) {
                const uint64_t expectedPeriodMs =
                    (handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds +
                        UINT64_C(999999)) /
                    UINT64_C(1000000);
                eventWaitTimeoutMs = static_cast<int>(std::clamp<uint64_t>(
                    expectedPeriodMs,
                    1,
                    10
                ));
            }
            timeval timeout = timeoutFromMilliseconds(eventWaitTimeoutMs);
            const int rc = libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
                const int totalErrors = handle->transfer.submitErrors.fetch_add(1) + 1;
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle);
                    handle->transfer.running.store(false);
                    markTransportFailed(handle);
                    setError(handle, "event_loop_failed:LIBUSB_ERROR_NO_DEVICE");
                    LOGE(
                        "USB event loop lost device: totalErrors=%d inFlight=%d",
                        totalErrors,
                        handle->transfer.inFlightTransfers.load()
                    );
                    break;
                }
                consecutiveErrors += 1;
                const int backoffMs = exponentialBackoffMs(consecutiveErrors);
                if (shouldLogRepeatedError(consecutiveErrors)) {
                    LOGW(
                        "USB event loop transient error: error=%s consecutive=%d/%d "
                        "totalErrors=%d backoffMs=%d",
                        libusbErrName(rc),
                        consecutiveErrors,
                        kEventLoopConsecutiveErrorLimit,
                        totalErrors,
                        backoffMs
                    );
                }
                if (consecutiveErrors >= kEventLoopConsecutiveErrorLimit) {
                    handle->transfer.running.store(false);
                    markTransportFailed(handle);
                    handle->recovery.stopRequested.store(true);
                    setError(handle, std::string("event_loop_failed:") + libusbErrName(rc));
                    LOGE(
                        "USB event loop error limit reached: error=%s consecutive=%d",
                        libusbErrName(rc),
                        consecutiveErrors
                    );
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
            } else {
                consecutiveErrors = 0;
            }
            if (handle->device.explicitFeedbackEnabled &&
                !handle->transfer.feedbackRuntime.tick(steadyClockNanoseconds())) {
                failForExplicitFeedbackRuntime(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                LOGE(
                    "USB explicit feedback runtime failed: state=%s failure=%s",
                    neri::usb::feedback::explicitFeedbackRuntimeStateName(
                        handle->transfer.feedbackRuntime.snapshot().state
                    ),
                    neri::usb::feedback::explicitFeedbackRuntimeFailureName(
                        handle->transfer.feedbackRuntime.snapshot().failure
                    )
                );
                break;
            }
            const int64_t firstSubmittedAtMs = handle->transfer.firstTransferSubmittedAtMs.load();
            if (
                handle->transfer.completedTransfers.load() == 0 &&
                handle->transfer.inFlightTransfers.load() > 0 &&
                firstSubmittedAtMs > 0 &&
                steadyClockMillis() - firstSubmittedAtMs >= kFirstTransferCompletionTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                setError(handle, "event_loop_first_completion_timeout");
                LOGE(
                    "USB event loop timed out before first completion: inFlight=%d",
                    handle->transfer.inFlightTransfers.load()
                );
                break;
            }
            const int64_t lastCompletionAtMs = handle->transfer.lastTransferCompletionAtMs.load();
            if (
                handle->transfer.completedTransfers.load() > 0 &&
                handle->transfer.inFlightTransfers.load() > 0 &&
                lastCompletionAtMs > 0 &&
                steadyClockMillis() - lastCompletionAtMs >=
                    kTransferCompletionStallTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                setError(handle, "event_loop_completion_stalled");
                LOGE(
                    "USB event loop stalled after completions: completed=%d inFlight=%d",
                    handle->transfer.completedTransfers.load(),
                    handle->transfer.inFlightTransfers.load()
                );
                break;
            }
            if (handle->recovery.transportFailed.load() &&
                !streamTransfersOutstanding(handle)) {
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                break;
            }
        }
        LOGI(
            "USB event loop exited: running=%d stop=%d completed=%d errors=%d inFlight=%d",
            handle->transfer.running.load() ? 1 : 0,
            handle->recovery.stopRequested.load() ? 1 : 0,
            handle->transfer.completedTransfers.load(),
            handle->transfer.submitErrors.load(),
            handle->transfer.inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("event_loop_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB event loop exception: %s", error.what());
    } catch (...) {
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, "event_loop_unknown_exception");
        } catch (...) {
        }
        LOGE("USB event loop unknown exception");
    }
}

void logTransferStatuses(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    for (size_t index = 0; index < handle->transfer.transfers.size(); ++index) {
        libusb_transfer* transfer = handle->transfer.transfers[index];
        const int callbackStatus = index < handle->transfer.transferStatuses.size()
            ? handle->transfer.transferStatuses[index]
            : -2;
        const int liveStatus = transfer != nullptr ? transfer->status : -2;
        LOGW(
            "cancel drain transfer[%zu]: callbackStatus=%d liveStatus=%d ptr=%p",
            index,
            callbackStatus,
            liveStatus,
            static_cast<void*>(transfer)
        );
    }
}

bool drainCancelledTransfers(
    UsbExclusiveHandle* handle,
    std::chrono::steady_clock::time_point hardDeadline
) {
    if (handle == nullptr) {
        return true;
    }
    const auto warningDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainWarningMs);
    bool warnedAboutSlowDrain = false;
    int consecutiveErrors = 0;
    while (handle->transfer.inFlightTransfers.load() > 0 ||
        feedbackTransfersOutstanding(handle)) {
        const auto beforeWait = std::chrono::steady_clock::now();
        const auto remainingMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            hardDeadline - beforeWait
        ).count();
        const int waitTimeoutMs = static_cast<int>(std::clamp<int64_t>(
            remainingMs,
            0,
            kDrainEventWaitTimeoutMs
        ));
        timeval timeout = timeoutFromMilliseconds(waitTimeoutMs);
        const int rc = handle->device.ctx != nullptr
            ? libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr)
            : LIBUSB_ERROR_INVALID_PARAM;
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            consecutiveErrors += 1;
            const int backoffMs = exponentialBackoffMs(consecutiveErrors);
            if (shouldLogRepeatedError(consecutiveErrors)) {
                LOGW(
                "USB cancel drain event error: error=%s consecutive=%d backoffMs=%d "
                    "audioInFlight=%d feedbackInFlight=%d",
                libusbErrName(rc),
                consecutiveErrors,
                backoffMs,
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
                );
            }
            const auto remainingAfterWaitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                hardDeadline - std::chrono::steady_clock::now()
            ).count();
            if (remainingAfterWaitMs > 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(
                    std::min<int64_t>(backoffMs, remainingAfterWaitMs)
                ));
            }
        } else {
            consecutiveErrors = 0;
        }

        const auto now = std::chrono::steady_clock::now();
        if (!warnedAboutSlowDrain && now >= warningDeadline) {
            warnedAboutSlowDrain = true;
            markTransportFailed(handle);
            try {
                setError(handle, "cancel_drain_stalled");
            } catch (...) {
            }
            LOGW(
                "waiting for cancelled USB transfers before close: audioInFlight=%d "
                "feedbackInFlight=%d",
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
            );
            logTransferStatuses(handle);
        }
        if (now >= hardDeadline) {
            markTransportFailed(handle);
            handle->recovery.deviceOnline.store(false);
            handle->player.playbackEnabled.store(false);
            try {
                setError(handle, "cancel_drain_timeout");
            } catch (...) {
            }
            LOGE(
                "cancel drain timed out: audioInFlight=%d feedbackInFlight=%d "
                "completed=%d errors=%d",
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0,
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load()
            );
            logTransferStatuses(handle);
            return false;
        }
    }
    return true;
}

bool stopStreamingInternal(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return true;
    }
    const bool wasRunning = handle->transfer.running.exchange(false);
    if (!wasRunning && handle->transfer.transfers.empty() &&
        !handle->transfer.eventThread.joinable() && !feedbackTransferSetActive(handle)) {
        return parkStreamingAlternate(handle, "stream_stop_idle");
    }

    LOGI(
        "stopStreamingInternal begin: source=%s inFlight=%d completed=%d errors=%d",
        sourceName(handle->transfer.streamSource.load()),
        handle->transfer.inFlightTransfers.load(),
        handle->transfer.completedTransfers.load(),
        handle->transfer.submitErrors.load()
    );
    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        handle->recovery.stopRequested.store(true);
    }
    stopExplicitFeedbackRuntime(handle);
    interruptUsbEventHandler(handle);
    for (libusb_transfer* transfer : handle->transfer.transfers) {
        if (transfer != nullptr) {
            const int rc = libusb_cancel_transfer(transfer);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("libusb_cancel_transfer failed: %s", libusbErrName(rc));
            }
        }
    }
    interruptUsbEventHandler(handle);
    if (handle->transfer.eventThread.joinable()) {
        handle->transfer.eventThread.join();
    }
    const auto hardDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainDeadlineMs);
    if (!drainCancelledTransfers(handle, hardDeadline)) {
        return false;
    }
    freeTransfers(handle);
    if (!parkStreamingAlternate(handle, "stream_stop")) {
        setError(handle, "stream_stop_idle_alt_failed:LIBUSB_ERROR_NO_DEVICE");
        return false;
    }
    if (feedbackTransferSetActive(handle)) {
        setError(handle, "feedback_transfer_set_not_drained");
        return false;
    }
    LOGI(
        "stopStreamingInternal done: completed=%d errors=%d transportFailed=%d",
        handle->transfer.completedTransfers.load(),
        handle->transfer.submitErrors.load(),
        handle->recovery.transportFailed.load() ? 1 : 0
    );
    return true;
}

size_t parkedHandleCountLocked() {
    return static_cast<size_t>(std::count_if(
        g_parkedHandles.begin(),
        g_parkedHandles.end(),
        [](const ParkedHandleSlot& slot) {
            return slot.handle != nullptr;
        }
    ));
}

int parkHandleForRecovery(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    int quarantineIndex,
    const char* reason
) noexcept {
    if (handle == nullptr) {
        return -1;
    }
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        for (size_t slotIndex = 0; slotIndex < g_parkedHandles.size(); ++slotIndex) {
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle != nullptr) {
                continue;
            }
            slot.handle = handle;
            slot.parkedAt = std::chrono::steady_clock::now();
            slot.quarantineIndex = quarantineIndex;
            slot.pumpActive = true;
            LOGE(
                "parked USB handle: index=%d slot=%zu reason=%s parkedCount=%zu "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                reason != nullptr ? reason : "unknown",
                parkedHandleCountLocked(),
                handle->transfer.inFlightTransfers.load()
            );
            return static_cast<int>(slotIndex);
        }
        LOGE(
            "USB parked handle registry full: capacity=%zu index=%d inFlight=%d",
            g_parkedHandles.size(),
            quarantineIndex,
            handle->transfer.inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        LOGE("failed to park USB handle: index=%d error=%s", quarantineIndex, error.what());
    } catch (...) {
        LOGE("failed to park USB handle: index=%d error=unknown", quarantineIndex);
    }
    return -1;
}

void hardRetainHandle(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    int quarantineIndex,
    const char* reason
) noexcept {
    if (handle == nullptr) {
        return;
    }
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        for (size_t slotIndex = 0; slotIndex < g_hardRetainedHandles.size(); ++slotIndex) {
            if (g_hardRetainedHandles[slotIndex] != nullptr) {
                continue;
            }
            g_hardRetainedHandles[slotIndex] = handle;
            LOGE(
                "hard-retained USB handle: index=%d slot=%zu reason=%s inFlight=%d",
                quarantineIndex,
                slotIndex,
                reason != nullptr ? reason : "unknown",
                handle->transfer.inFlightTransfers.load()
            );
            return;
        }
    } catch (...) {
    }

    auto* leakedHandle = new (std::nothrow) std::shared_ptr<UsbExclusiveHandle>(handle);
    static_cast<void>(leakedHandle);
    LOGE(
        "hard-retained USB handle outside fixed registry: index=%d reason=%s "
        "retained=%d inFlight=%d",
        quarantineIndex,
        reason != nullptr ? reason : "unknown",
        leakedHandle != nullptr ? 1 : 0,
        handle->transfer.inFlightTransfers.load()
    );
}

bool setParkedPumpActive(
    size_t slotIndex,
    const UsbExclusiveHandle* expectedHandle,
    bool active
) noexcept {
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        if (slotIndex >= g_parkedHandles.size()) {
            return false;
        }
        ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
        if (slot.handle.get() != expectedHandle) {
            return false;
        }
        slot.pumpActive = active;
        return true;
    } catch (...) {
        return false;
    }
}

void releaseParkedHandle(
    size_t slotIndex,
    const UsbExclusiveHandle* expectedHandle,
    const char* source
) noexcept {
    try {
        size_t parkedCount = 0;
        int quarantineIndex = 0;
        {
            std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
            if (slotIndex >= g_parkedHandles.size()) {
                return;
            }
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle.get() != expectedHandle) {
                return;
            }
            quarantineIndex = slot.quarantineIndex;
            slot.handle.reset();
            slot.parkedAt = {};
            slot.quarantineIndex = 0;
            slot.pumpActive = false;
            parkedCount = parkedHandleCountLocked();
        }
        const int activeQuarantines = g_quarantinedDrainHandles.fetch_sub(1) - 1;
        LOGI(
            "reclaimed parked USB handle: index=%d slot=%zu source=%s parkedCount=%zu "
            "activeQuarantines=%d",
            quarantineIndex,
            slotIndex,
            source != nullptr ? source : "unknown",
            parkedCount,
            activeQuarantines
        );
    } catch (const std::exception& error) {
        LOGE("failed to release parked USB handle slot=%zu error=%s", slotIndex, error.what());
    } catch (...) {
        LOGE("failed to release parked USB handle slot=%zu error=unknown", slotIndex);
    }
}

void finishParkedHandle(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    size_t slotIndex,
    const char* source
) noexcept {
    if (handle == nullptr || streamTransfersOutstanding(handle.get())) {
        if (handle != nullptr) {
            setParkedPumpActive(slotIndex, handle.get(), false);
        }
        return;
    }
    try {
        freeTransfers(handle.get());
        {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            finishClosedUsbResources(handle.get());
            markInterfaceTransitionLocked();
        }
        releaseParkedHandle(slotIndex, handle.get(), source);
    } catch (const std::exception& error) {
        setParkedPumpActive(slotIndex, handle.get(), false);
        LOGE("failed to finalize parked USB handle slot=%zu error=%s", slotIndex, error.what());
    } catch (...) {
        setParkedPumpActive(slotIndex, handle.get(), false);
        LOGE("failed to finalize parked USB handle slot=%zu error=unknown", slotIndex);
    }
}

void pumpParkedHandleUntilReclaimed(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    size_t slotIndex,
    int quarantineIndex
) noexcept {
    const auto lowFrequencyAt = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kQuarantineTotalTimeoutMs);
    auto nextStatusLogAt = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kQuarantineDrainLogIntervalMs);
    bool lowFrequencyAnnounced = false;
    int consecutiveErrors = 0;

    while (streamTransfersOutstanding(handle.get())) {
        const auto now = std::chrono::steady_clock::now();
        const bool lowFrequency = now >= lowFrequencyAt;
        const int waitTimeoutMs = lowFrequency
            ? kParkedEventWaitTimeoutMs
            : kDrainEventWaitTimeoutMs;
        timeval timeout = timeoutFromMilliseconds(waitTimeoutMs);
        const int rc = handle->device.ctx != nullptr
            ? libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr)
            : LIBUSB_ERROR_INVALID_PARAM;
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            consecutiveErrors += 1;
            const int backoffMs = parkedExponentialBackoffMs(consecutiveErrors);
            if (shouldLogRepeatedError(consecutiveErrors)) {
                LOGW(
                    "parked USB event pump error: index=%d error=%s consecutive=%d "
                    "backoffMs=%d audioInFlight=%d feedbackInFlight=%d",
                    quarantineIndex,
                    libusbErrName(rc),
                    consecutiveErrors,
                    backoffMs,
                    handle->transfer.inFlightTransfers.load(),
                    handle->device.explicitFeedbackEnabled
                        ? static_cast<int>(
                            handle->transfer.feedbackInTransferSet.snapshot().inFlight
                        )
                        : 0
                );
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
        } else {
            consecutiveErrors = 0;
        }

        const auto afterPump = std::chrono::steady_clock::now();
        if (!lowFrequencyAnnounced && afterPump >= lowFrequencyAt) {
            lowFrequencyAnnounced = true;
            markTransportFailed(handle.get());
            try {
                setError(handle.get(), "quarantine_drain_timeout");
            } catch (...) {
            }
            LOGE(
                "USB quarantine switched to low-frequency recovery: index=%d slot=%zu "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                handle->transfer.inFlightTransfers.load()
            );
        }
        if (afterPump >= nextStatusLogAt &&
            streamTransfersOutstanding(handle.get())) {
            LOGW(
                "USB quarantine still pumping: index=%d slot=%zu lowFrequency=%d "
                "audioInFlight=%d feedbackInFlight=%d",
                quarantineIndex,
                slotIndex,
                lowFrequencyAnnounced ? 1 : 0,
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
            );
            nextStatusLogAt = afterPump +
                std::chrono::milliseconds(kQuarantineDrainLogIntervalMs);
        }
    }

    finishParkedHandle(handle, slotIndex, "quarantine_thread");
}

void serviceParkedHandlesOnce() noexcept {
    for (size_t slotIndex = 0; slotIndex < g_parkedHandles.size(); ++slotIndex) {
        std::shared_ptr<UsbExclusiveHandle> handle;
        int quarantineIndex = 0;
        try {
            std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle == nullptr || slot.pumpActive) {
                continue;
            }
            slot.pumpActive = true;
            handle = slot.handle;
            quarantineIndex = slot.quarantineIndex;
        } catch (...) {
            continue;
        }

        timeval timeout = timeoutFromMilliseconds(0);
        const int rc = handle->device.ctx != nullptr &&
                streamTransfersOutstanding(handle.get())
            ? libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr)
            : LIBUSB_SUCCESS;
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            requestNoDeviceStop(handle.get());
        } else if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGW(
                "nativeOpen parked handle service failed: index=%d slot=%zu error=%s "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                libusbErrName(rc),
                handle->transfer.inFlightTransfers.load()
            );
        }
        if (!streamTransfersOutstanding(handle.get())) {
            finishParkedHandle(handle, slotIndex, "native_open");
        } else {
            setParkedPumpActive(slotIndex, handle.get(), false);
        }
    }
}

void quarantineCloseHandle(const std::shared_ptr<UsbExclusiveHandle>& handle) noexcept {
    if (handle == nullptr) {
        return;
    }

    const int quarantineIndex = g_nextQuarantineIndex.fetch_add(1);
    g_quarantinedDrainHandles.fetch_add(1);
    const int parkedSlot = parkHandleForRecovery(
        handle,
        quarantineIndex,
        "cancel_drain_timeout"
    );
    if (parkedSlot < 0) {
        hardRetainHandle(handle, quarantineIndex, "parked_registry_full");
        g_quarantinedDrainHandles.fetch_sub(1);
        return;
    }
    try {
        std::thread([handle, quarantineIndex, parkedSlot]() {
            LOGW(
                "USB close quarantined for cancel drain: index=%d slot=%d inFlight=%d",
                quarantineIndex,
                parkedSlot,
                handle->transfer.inFlightTransfers.load()
            );
            pumpParkedHandleUntilReclaimed(
                handle,
                static_cast<size_t>(parkedSlot),
                quarantineIndex
            );
        }).detach();
    } catch (const std::system_error& error) {
        setParkedPumpActive(static_cast<size_t>(parkedSlot), handle.get(), false);
        LOGE(
            "USB close quarantine thread failed, registry will retain handle: %s "
            "slot=%d activeQuarantines=%d",
            error.what(),
            parkedSlot,
            g_quarantinedDrainHandles.load()
        );
    } catch (...) {
        setParkedPumpActive(static_cast<size_t>(parkedSlot), handle.get(), false);
        LOGE(
            "USB close quarantine thread failed, registry will retain handle: unknown "
            "slot=%d activeQuarantines=%d",
            parkedSlot,
            g_quarantinedDrainHandles.load()
        );
    }
}

bool closeHandleInternal(const std::shared_ptr<UsbExclusiveHandle>& handle) {
    if (handle == nullptr) {
        return true;
    }
    bool expected = false;
    if (!handle->recovery.closing.compare_exchange_strong(expected, true)) {
        LOGW("closeHandleInternal ignored duplicate close");
        return true;
    }
    LOGI(
        "closeHandleInternal begin: iface=%d alt=%d claimed=%zu running=%d source=%s",
        handle->device.audioStreamingInterface,
        handle->device.alternateSetting,
        handle->device.claimedAudioInterfaces.size(),
        handle->transfer.running.load() ? 1 : 0,
        sourceName(handle->transfer.streamSource.load())
    );
    if (!stopStreamingInternal(handle.get())) {
        LOGE(
            "closeHandleInternal quarantines active USB transfers: inFlight=%d",
            handle->transfer.inFlightTransfers.load()
        );
        quarantineCloseHandle(handle);
        return false;
    }

    finishClosedUsbResources(handle.get());
    return true;
}

} // namespace neri::usb::exclusive

using namespace neri::usb::exclusive;

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStartGeneratedTone(
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
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
        return JNI_FALSE;
    }
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    return startStreamingSafely(holder.get(), StreamSource::Tone) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeConfigurePlayerBufferDuration(
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
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    const int rawDurationMs = static_cast<int>(durationMs);
    const int requestedDurationMs = std::clamp(
        rawDurationMs,
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
    if (requestedDurationMs != rawDurationMs) {
        LOGW(
            "nativeConfigurePlayerBufferDuration clamped: requested=%d applied=%d",
            rawDurationMs,
            requestedDurationMs
        );
    }
    if (holder->transfer.streamSource.load() == StreamSource::PlayerPcm) {
        std::string resizeError;
        if (!holder->player.pcmPipeline.resizeRingDuration(
                requestedDurationMs,
                holder->transfer.transferBytes,
                holder->transfer.baseTransferCount,
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
    holder->player.pcmRingDurationMs = requestedDurationMs;
    LOGI(
        "nativeConfigurePlayerBufferDuration: handle=%lld requested=%d applied=%d "
        "targetTransfers=%d activeTransfers=%d",
        static_cast<long long>(handleValue),
        durationMs,
        holder->player.pcmRingDurationMs,
        holder->transfer.targetTransferCount.load(),
        holder->transfer.inFlightTransfers.load()
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeConfigurePlayerTransferWindow(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint durationMs
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
        return JNI_FALSE;
    }
    const int requestedDurationMs = std::clamp(
        static_cast<int>(durationMs),
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
    holder->transfer.targetTransferCount.store(targetIsoTransferCount(
        holder.get(),
        requestedDurationMs
    ));
    int activatedTransfers = 0;
    const int targetTransferCount = std::min<int>(
        holder->transfer.targetTransferCount.load(),
        static_cast<int>(holder->transfer.transfers.size())
    );
    if (holder->transfer.inFlightTransfers.load() < targetTransferCount &&
        !holder->recovery.transportFailed.load()) {
        activatedTransfers = activateBufferedIsoReserveTransfers(holder.get());
    }
    LOGI(
        "nativeConfigurePlayerTransferWindow: handle=%lld durationMs=%d "
        "targetTransfers=%d activeTransfers=%d activated=%d",
        static_cast<long long>(handleValue),
        requestedDurationMs,
        holder->transfer.targetTransferCount.load(),
        holder->transfer.inFlightTransfers.load(),
        activatedTransfers
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePreparePlayerPcm(
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
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
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
        holder->device.sampleRate,
        holder->device.channelCount,
        holder->device.bitsPerSample,
        holder->player.pcmRingDurationMs,
        holder->transfer.running.load() ? 1 : 0
    );
    if (holder->transfer.running.load()) {
        if (!stopStreamingInternal(holder.get())) {
            return JNI_FALSE;
        }
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
            holder->device.sampleRate,
            holder->device.channelCount,
            holder->device.subslotBytes,
            holder->device.bitsPerSample,
            holder->device.frameBytes
        },
        {
            inputSampleRate > 0 ? inputSampleRate : holder->device.sampleRate,
            inputChannelCount > 0 ? inputChannelCount : holder->device.channelCount,
            inputEncoding
        },
        holder->player.pcmRingDurationMs,
        holder->transfer.transferBytes,
        holder->transfer.baseTransferCount
    };
    std::string pipelineError;
    if (!holder->player.pcmPipeline.configure(config, &pipelineError)) {
        setError(holder.get(), pipelineError);
        LOGE("nativePreparePlayerPcm pipeline configure failed: %s", pipelineError.c_str());
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->transfer.streamSource.store(StreamSource::PlayerPcm);
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    holder->player.stagedPlayerFrames.store(0);
    holder->player.completedAudioFrames.store(0);
    clearError(holder.get());
    LOGI(
        "nativePreparePlayerPcm ok: handle=%lld ringMs=%d transferBytes=%d "
        "baseTransfers=%d reserveTransfers=%d",
        static_cast<long long>(handleValue),
        holder->player.pcmRingDurationMs,
        holder->transfer.transferBytes,
        holder->transfer.baseTransferCount,
        holder->transfer.transferCount
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeWritePlayerPcm(
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
    if (holder->recovery.closing.load() || !holder->recovery.deviceOnline.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeWritePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
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
    holder->player.playerVolume.store(requestedVolume);
    holder->player.pcmPipeline.setTargetGain(holder->player.focusMuted.load() ? 0.0f : requestedVolume);
    std::string pipelineError;
    size_t written = 0;
    try {
        written = holder->player.pcmPipeline.write(
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
    if (written > 0) {
        activateBufferedIsoReserveTransfers(holder.get());
    }
    if (written == 0 || written < static_cast<size_t>(size)) {
        const int warningIndex = holder->player.shortWriteWarnings.fetch_add(1);
        if (warningIndex < 8) {
            const neri::usb::PcmPipelineSnapshot pcm = holder->player.pcmPipeline.snapshot();
            LOGW(
                "nativeWritePlayerPcm short write: handle=%lld requested=%d written=%zu "
                "level=%zu/%zu free=%zu backpressureEvents=%lld backpressureCurrentMs=%lld "
                "running=%d playback=%d input=%lld output=%lld dropped=%lld underrun=%lld",
                static_cast<long long>(handleValue),
                size,
                written,
                pcm.levelBytes,
                pcm.capacityBytes,
                pcm.freeBytes,
                static_cast<long long>(pcm.backpressureEvents),
                static_cast<long long>(pcm.backpressureCurrentUs / 1000),
                holder->transfer.running.load() ? 1 : 0,
                holder->player.playbackEnabled.load() ? 1 : 0,
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
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
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
    if (holder->recovery.closing.load() || !holder->recovery.deviceOnline.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePlayPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    LOGI(
        "nativePlayPlayerPcm request: handle=%lld running=%d queued=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.completedAudioFrames.load())
    );
    holder->player.playerPaused.store(false);
    holder->player.playbackEnabled.store(true);
    if (startStreamingSafely(holder.get(), StreamSource::PlayerPcm)) {
        LOGI("nativePlayPlayerPcm ok: handle=%lld", static_cast<long long>(handleValue));
        return JNI_TRUE;
    }
    holder->player.playbackEnabled.store(false);
    LOGE(
        "nativePlayPlayerPcm failed: handle=%lld error=%s",
        static_cast<long long>(handleValue),
        getErrorCopy(holder.get()).c_str()
    );
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStartPlayerPcm(
    JNIEnv* env,
    jclass clazz,
    jlong handleValue
) {
    return Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
        env,
        clazz,
        handleValue
    );
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePausePlayerPcm(
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
    if (holder->recovery.closing.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePausePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    holder->player.playbackEnabled.store(false);
    holder->player.playerReplayFailed.store(false);
    holder->player.preserveCancelledPlayerFrames.store(true);
    const bool stopped = stopStreamingInternal(holder.get());
    holder->player.preserveCancelledPlayerFrames.store(false);
    const bool replayPreserved = !holder->player.playerReplayFailed.load();
    const bool paused = stopped && replayPreserved;
    holder->player.playerPaused.store(paused);
    if (!replayPreserved) {
        setError(holder.get(), "pause_replay_preservation_failed");
    }
    LOGI(
        "nativePausePlayerPcm %s: handle=%lld running=%d level=%zu/%zu queued=%lld replay=%lld",
        paused ? "ok" : "failed",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.pcmPipeline.queuedFrames()),
        static_cast<long long>(queuedPlayerReplayFrames(holder.get()))
    );
    return paused ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeFlushPlayerPcm(
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
    if (holder->recovery.closing.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeFlushPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }

    const bool transportWasRunning = holder->transfer.running.load();
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    LOGI(
        "nativeFlushPlayerPcm begin: handle=%lld restart=%d resume=%d level=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        transportWasRunning ? 1 : 0,
        0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.completedAudioFrames.load())
    );
    if (!stopStreamingInternal(holder.get())) {
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->player.pcmPipeline.clear();
    holder->player.pcmPipeline.resetCounters();
    holder->player.stagedPlayerFrames.store(0);
    holder->player.completedAudioFrames.store(0);

    clearError(holder.get());
    LOGI(
        "nativeFlushPlayerPcm done: handle=%lld running=%d playback=%d",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        holder->player.playbackEnabled.load() ? 1 : 0
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jfloat volume
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load() || !holder->recovery.deviceOnline.load()) {
        return JNI_FALSE;
    }
    const float requestedVolume = std::clamp(static_cast<float>(volume), 0.0f, 1.0f);
    holder->player.playerVolume.store(requestedVolume);
    holder->player.pcmPipeline.setTargetGain(holder->player.focusMuted.load() ? 0.0f : requestedVolume);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerFocusMuted(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jboolean muted
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load() || !holder->recovery.deviceOnline.load()) {
        return JNI_FALSE;
    }
    const bool shouldMute = muted == JNI_TRUE;
    holder->player.focusMuted.store(shouldMute);
    holder->player.pcmPipeline.setTargetGain(
        shouldMute ? 0.0f : std::clamp(holder->player.playerVolume.load(), 0.0f, 1.0f)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetCompletedAudioFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    return holder != nullptr ? static_cast<jlong>(holder->player.completedAudioFrames.load()) : 0L;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetQueuedPlayerFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return 0L;
    }
    return static_cast<jlong>(holder->player.pcmPipeline.queuedFrames()) +
        holder->player.stagedPlayerFrames.load() +
        queuedPlayerReplayFrames(holder.get());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetPlayerPcmFreeBytes(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load()) {
        return -1L;
    }
    return static_cast<jlong>(holder->player.pcmPipeline.snapshot().freeBytes);
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStop(
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
        holder->transfer.running.load() ? 1 : 0,
        sourceName(holder->transfer.streamSource.load())
    );
    requestDeviceStop(holder.get(), false);
    std::unique_lock<std::mutex> apiGuard(holder->apiLock, std::try_to_lock);
    if (apiGuard.owns_lock()) {
        interruptUsbEventHandler(holder.get());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeMarkDeviceDetached(
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
    std::unique_lock<std::mutex> apiGuard(holder->apiLock, std::try_to_lock);
    if (apiGuard.owns_lock()) {
        interruptUsbEventHandler(holder.get());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeClose(
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
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    requestDeviceStop(holder.get(), holder->recovery.detachBroadcastConfirmed.load());
    interruptUsbEventHandler(holder.get());
    LOGI(
        "nativeClose: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        sourceName(holder->transfer.streamSource.load())
    );
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    const bool closedNow = closeHandleInternal(holder);
    if (!closedNow) {
        LOGW("nativeClose returned with USB resources quarantined");
    }
    markInterfaceTransitionLocked();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeRuntimeReport(
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
        const neri::usb::PcmPipelineSnapshot pcm = holder->player.pcmPipeline.snapshot();
        const bool explicitFeedback = holder->device.explicitFeedbackEnabled;
        const neri::usb::feedback::FeedbackInTransferSetSnapshot feedbackTransfers =
            holder->transfer.feedbackInTransferSet.snapshot();
        const neri::usb::feedback::ExplicitFeedbackRuntimeSnapshot feedbackRuntime =
            holder->transfer.feedbackRuntime.snapshot();
        const bool feedbackTerminalFailure = explicitFeedback &&
            feedbackRuntime.terminalFailure;
        const bool terminalFailure = holder->recovery.transportFailed.load() ||
            !holder->recovery.deviceOnline.load() || feedbackTerminalFailure;
        if (terminalFailure) {
            if (feedbackTerminalFailure && !holder->recovery.transportFailed.load()) {
                markTransportFailed(holder.get());
            }
            latchTerminalRecoveryAction(
                holder.get(),
                holder->recovery.deviceOnline.load()
                    ? neri::usb::UsbRuntimeRecoveryAction::FreshOpen
                    : neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
            );
        }
        const neri::usb::UsbRecoveryActionSnapshot recovery =
            holder->recovery.recoveryActionLatch.snapshot();
        const bool playerPcmSource =
            holder->transfer.streamSource.load() == StreamSource::PlayerPcm;
        const int64_t outputBytes = std::max<int64_t>(0, pcm.outputBytes);
        const int64_t zeroFillBytes = std::max<int64_t>(0, pcm.zeroFillBytes);
        const int64_t outputAfterZeroFill = outputBytes > zeroFillBytes
            ? outputBytes - zeroFillBytes
            : 0;
        const int64_t pausedZeroFillBytes = std::max<int64_t>(
            0,
            pcm.pausedZeroFillBytes
        );
        const bool pipelineRealPcmReleased = playerPcmSource &&
            outputAfterZeroFill > pausedZeroFillBytes;
        const bool stoppedFeedbackReusable = explicitFeedback &&
            !feedbackRuntime.running &&
            feedbackRuntime.state ==
                neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped &&
            feedbackRuntime.reusableAfterStop &&
            !feedbackRuntime.terminalFailure;
        const bool feedbackReady = !explicitFeedback ||
            stoppedFeedbackReusable ||
            (feedbackRuntime.running &&
                feedbackClockCanStream(feedbackRuntime.gate.clock.state) &&
                !feedbackRuntime.terminalFailure);
        const bool feedbackReusable = !explicitFeedback ||
            ((stoppedFeedbackReusable ||
                (feedbackRuntime.running &&
                    (feedbackRuntime.gate.clock.state ==
                        neri::usb::feedback::FeedbackClockState::Locked ||
                     feedbackRuntime.gate.clock.state ==
                        neri::usb::feedback::FeedbackClockState::Holdover))) &&
                !terminalFailure);
        const bool realPcmReleased = explicitFeedback
            ? playerPcmSource &&
                (feedbackRuntime.realPcmReleased ||
                    (stoppedFeedbackReusable && pipelineRealPcmReleased))
            : pipelineRealPcmReleased;
        const bool canAcceptPcm = playerPcmSource &&
            holder->recovery.deviceOnline.load() &&
            !holder->recovery.closing.load() &&
            !terminalFailure &&
            !recovery.latched &&
            feedbackReady;
        const bool transportRunning = holder->transfer.running.load();
        const bool playbackReady = transportRunning &&
            feedbackReady &&
            realPcmReleased &&
            canAcceptPcm &&
            !terminalFailure;
        neri::usb::UsbRuntimeReportV2Snapshot v2Snapshot;
        const int64_t feedbackNowNs = steadyClockNanoseconds();
        const auto trustedFeedbackRateQ32 =
            feedbackRuntime.gate.clock.hasTrustedRate
                ? feedbackRuntime.gate.clock.trustedRateQ32
                : holder->transfer.feedbackNominalRateQ32;
        const uint64_t feedbackPeriodNs =
            holder->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds;
        const int64_t feedbackExpectedPeriodUs = reportCounter(
            feedbackPeriodNs / UINT64_C(1000) +
                (feedbackPeriodNs % UINT64_C(1000) == 0 ? 0 : 1)
        );
        const int64_t feedbackLastAgeMs =
            feedbackRuntime.gate.clock.lastValidSampleNs >= 0 &&
                feedbackNowNs >= feedbackRuntime.gate.clock.lastValidSampleNs
            ? (feedbackNowNs - feedbackRuntime.gate.clock.lastValidSampleNs) /
                INT64_C(1000000)
            : 0;
        const bool feedbackTimedOut =
            feedbackRuntime.gate.clock.failureReason ==
                neri::usb::feedback::FeedbackClockFailureReason::AcquireTimeout ||
            feedbackRuntime.gate.clock.failureReason ==
                neri::usb::feedback::FeedbackClockFailureReason::HoldoverTimeout;
        v2Snapshot.feedbackMode = explicitFeedback
            ? neri::usb::UsbRuntimeFeedbackMode::Explicit
            : neri::usb::UsbRuntimeFeedbackMode::Disabled;
        v2Snapshot.feedbackEndpointAddress = explicitFeedback
            ? static_cast<int>(holder->device.feedbackEndpoint)
            : 0;
        v2Snapshot.feedbackState = runtimeFeedbackState(
            explicitFeedback,
            feedbackRuntime
        );
        v2Snapshot.feedbackPayloadBytes = explicitFeedback
            ? static_cast<int>(
                holder->device.feedbackTimingProfile.decodeProfile.payloadBytesExpected
            )
            : 0;
        v2Snapshot.feedbackExpectedPeriodUs = explicitFeedback
            ? feedbackExpectedPeriodUs
            : 0;
        v2Snapshot.feedbackRawValue = explicitFeedback &&
                feedbackRuntime.validPackets > 0
            ? std::to_string(feedbackRuntime.lastRawValue)
            : "none";
        v2Snapshot.feedbackRateQ32 = explicitFeedback
            ? trustedFeedbackRateQ32
            : 0;
        v2Snapshot.feedbackRateHz = explicitFeedback
            ? feedbackRateHz(trustedFeedbackRateQ32)
            : 0.0;
        v2Snapshot.feedbackRatePpm = explicitFeedback
            ? signedFeedbackRatePpm(
                trustedFeedbackRateQ32,
                holder->transfer.feedbackNominalRateQ32
            )
            : 0;
        v2Snapshot.feedbackValidSamples = explicitFeedback
            ? reportCounter(feedbackRuntime.validPackets)
            : 0;
        v2Snapshot.feedbackInvalidSamples = explicitFeedback
            ? reportCounter(feedbackRuntime.invalidPackets)
            : 0;
        v2Snapshot.feedbackOutliers = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackRuntime.estimator.hardRangeRejects,
                feedbackRuntime.estimator.localOutliers
            ))
            : 0;
        v2Snapshot.feedbackTimeouts = explicitFeedback && feedbackTimedOut ? 1 : 0;
        v2Snapshot.feedbackLockCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.lockCount)
            : 0;
        v2Snapshot.feedbackRelockCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.relockCount)
            : 0;
        v2Snapshot.feedbackHoldoverCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.holdoverCount)
            : 0;
        uint64_t feedbackHoldoverTotalNs = explicitFeedback
            ? feedbackRuntime.gate.clock.holdoverTotalNs
            : 0;
        const bool feedbackHoldoverActive = explicitFeedback &&
            feedbackRuntime.running &&
            !feedbackRuntime.terminalFailure &&
            (feedbackRuntime.gate.clock.state ==
                neri::usb::feedback::FeedbackClockState::Holdover ||
             feedbackRuntime.gate.clock.state ==
                neri::usb::feedback::FeedbackClockState::Relocking) &&
            feedbackRuntime.gate.clock.holdoverStartedNs >= 0 &&
            feedbackNowNs >= feedbackRuntime.gate.clock.holdoverStartedNs;
        if (feedbackHoldoverActive) {
            feedbackHoldoverTotalNs = saturatedCounterSum(
                feedbackHoldoverTotalNs,
                static_cast<uint64_t>(
                    feedbackNowNs -
                        feedbackRuntime.gate.clock.holdoverStartedNs
                )
            );
        }
        v2Snapshot.feedbackHoldoverTotalMs = explicitFeedback
            ? reportCounter(feedbackHoldoverTotalNs / UINT64_C(1000000))
            : 0;
        v2Snapshot.feedbackLongGapReacquisitions = explicitFeedback
            ? reportCounter(feedbackRuntime.longGapReacquisitions)
            : 0;
        v2Snapshot.feedbackLastAgeMs = explicitFeedback
            ? feedbackLastAgeMs
            : 0;
        v2Snapshot.feedbackClockFailure = explicitFeedback
            ? neri::usb::feedback::feedbackClockFailureReasonName(
                feedbackRuntime.gate.clock.failureReason
            )
            : "none";
        v2Snapshot.feedbackInFlight = explicitFeedback
            ? static_cast<int>(feedbackTransfers.inFlight)
            : 0;
        v2Snapshot.feedbackTransferErrors = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackTransfers.transferErrors,
                feedbackTransfers.cancelErrors
            ))
            : 0;
        v2Snapshot.feedbackPacketErrors = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackTransfers.packetErrors,
                feedbackTransfers.invalidLengths
            ))
            : 0;
        v2Snapshot.transportRunning = transportRunning;
        v2Snapshot.feedbackReady = feedbackReady;
        v2Snapshot.realPcmReleased = realPcmReleased;
        v2Snapshot.canAcceptPcm = canAcceptPcm;
        v2Snapshot.playbackReady = playbackReady;
        v2Snapshot.feedbackReusable = feedbackReusable;
        v2Snapshot.terminalFailure = terminalFailure;
        v2Snapshot.nativeStreamGeneration = holder->recovery.nativeStreamGeneration;
        v2Snapshot.candidateId = runtimeCandidateId(holder.get());
        v2Snapshot.recoveryEpoch = holder->recovery.recoveryEpoch;
        v2Snapshot.recommendedAction = recovery.action;
        v2Snapshot.actionId = recovery.id;
        v2Snapshot.actionGeneration = recovery.generation;
        v2Snapshot.actionOwner = recovery.latched
            ? neri::usb::UsbRuntimeRecoveryOwner::Kotlin
            : neri::usb::UsbRuntimeRecoveryOwner::None;
        v2Snapshot.actionLatched = recovery.latched;
        v2Snapshot.errorCode = runtimeErrorCode(holder.get(), lastError);
        std::string v2Fields;
        std::string v2BuildError;
        if (!neri::usb::buildUsbRuntimeReportV2Fields(
                v2Snapshot,
                &v2Fields,
                &v2BuildError
            )) {
            LOGE("nativeRuntimeReport v2 build failed: %s", v2BuildError.c_str());
            v2Fields = "reportVersion=2 reportBuildError=" + v2BuildError;
        }
        const int64_t stagedPlayerFrames = holder->player.stagedPlayerFrames.load();
        const int64_t replayPlayerFrames = queuedPlayerReplayFrames(holder.get());
        const int64_t queuedFrames = static_cast<int64_t>(
            pcm.levelBytes / static_cast<size_t>(std::max(1, holder->device.frameBytes))
        ) + stagedPlayerFrames + replayPlayerFrames;
        const int64_t fifoMs = holder->device.sampleRate > 0 && holder->device.frameBytes > 0
            ? static_cast<int64_t>(pcm.levelBytes) * 1000 /
                (static_cast<int64_t>(holder->device.sampleRate) * holder->device.frameBytes)
            : 0;
        const int64_t bufferMs = holder->device.sampleRate > 0 && holder->device.frameBytes > 0
            ? static_cast<int64_t>(pcm.capacityBytes) * 1000 /
                (static_cast<int64_t>(holder->device.sampleRate) * holder->device.frameBytes)
            : 0;
        const int minimumPacketFrames = holder->transfer.packetFramesMin.load() == std::numeric_limits<int>::max()
            ? 0
            : holder->transfer.packetFramesMin.load();
        std::string claimedInterfaceSummary;
        for (const ClaimedUsbInterface& entry : holder->device.claimedAudioInterfaces) {
            if (!claimedInterfaceSummary.empty()) {
                claimedInterfaceSummary += ",";
            }
            claimedInterfaceSummary += std::to_string(entry.interfaceNumber);
        }
        if (claimedInterfaceSummary.empty()) {
            claimedInterfaceSummary = "none";
        }
        const std::string report =
            v2Fields +
            " iface=" + std::to_string(holder->device.audioStreamingInterface) +
            " acIface=" + std::to_string(holder->device.audioControlInterface) +
            " alt=" + std::to_string(holder->device.alternateSetting) +
            " streamAltActive=" + std::string(
                holder->device.streamingAlternateActive ? "true" : "false"
            ) +
            " streamAltTransitions=" +
                std::to_string(holder->device.streamingAlternateTransitions) +
            " streamAltResetFailures=" +
                std::to_string(holder->device.streamingAlternateResetFailures) +
            " streamAltStatus=" + holder->device.streamingAlternateStatus +
            " claimedIfaces=" + claimedInterfaceSummary +
            " fullFunctionClaim=" + std::string(
                holder->device.completeAudioFunctionClaim ? "true" : "false"
            ) +
            " outEp=0x" + [&]() {
                char buf[8];
                snprintf(buf, sizeof(buf), "%02X", holder->device.outEndpoint);
                return std::string(buf);
            }() +
            " source=" + std::string(sourceName(holder->transfer.streamSource.load())) +
            " uacVersion=" + std::string(
                holder->device.uacVersion == 1
                    ? "1.0"
                    : holder->device.uacVersion == 2 ? "2.0" : "unsupported"
            ) +
            " clockEntity=" + std::to_string(holder->device.uacClockSourceId) +
            " sampleRate=" + std::to_string(holder->device.sampleRate) +
            " negotiatedRate=" + std::to_string(holder->device.negotiatedSampleRate) +
            " descriptorRates=" + holder->device.descriptorSampleRates +
            " rateControl=" + holder->device.sampleRateControlStatus +
            " channels=" + std::to_string(holder->device.channelCount) +
            " bits=" + std::to_string(holder->device.bitsPerSample) +
            " subslotBytes=" + std::to_string(holder->device.subslotBytes) +
            " formatSelection=" + holder->device.formatSelectionReason +
            " syncType=" + holder->device.endpointSyncType +
            " feedback=" + holder->device.endpointFeedback +
            " usbSpeed=" + std::to_string(holder->device.usbSpeed) +
            " packetBytes=" + std::to_string(holder->transfer.bytesPerUsbFrame) +
            " packetFrames=" + std::to_string(minimumPacketFrames) + ".." +
                std::to_string(holder->transfer.packetFramesMax.load()) +
            " endpointMaxPacketBytes=" + std::to_string(holder->device.endpointMaxPacketBytes) +
            " interval=" + std::to_string(holder->device.endpointInterval) +
            " intervalsPerSecond=" + std::to_string(holder->transfer.intervalsPerSecond) +
            " transferBytes=" + std::to_string(holder->transfer.transferBytes) +
            " transferCount=" + std::to_string(holder->transfer.transferCount) +
            " baseTransferCount=" + std::to_string(holder->transfer.baseTransferCount) +
            " targetTransferCount=" +
                std::to_string(holder->transfer.targetTransferCount.load()) +
            " lastTransferBytes=" + std::to_string(holder->transfer.lastTransferBytes.load()) +
            " deviceOnline=" + std::string(holder->recovery.deviceOnline.load() ? "true" : "false") +
            " noDeviceObserved=" + std::string(
                holder->recovery.noDeviceObserved.load() ? "true" : "false"
            ) +
            " detachConfirmed=" + std::string(
                holder->recovery.detachBroadcastConfirmed.load() ? "true" : "false"
            ) +
            " focusMuted=" + std::string(holder->player.focusMuted.load() ? "true" : "false") +
            " running=" + std::string(holder->transfer.running.load() ? "true" : "false") +
            " paused=" + std::string(holder->player.playerPaused.load() ? "true" : "false") +
            " transportFailed=" + std::string(holder->recovery.transportFailed.load() ? "true" : "false") +
            " inFlight=" + std::to_string(holder->transfer.inFlightTransfers.load()) +
            " completedTransfers=" + std::to_string(holder->transfer.completedTransfers.load()) +
            " submitErrors=" + std::to_string(holder->transfer.submitErrors.load()) +
            " isoPacketErrors=" + std::to_string(holder->transfer.isoPacketErrors.load()) +
            " isoPacketErrorTransfers=" +
                std::to_string(holder->transfer.isoPacketErrorTransfers.load()) +
            " isoPacketErrorScore=" + std::to_string(holder->transfer.isoPacketErrorScore.load()) +
            " scheduledPackets=" + std::to_string(holder->transfer.scheduledPackets.load()) +
            " scheduledFrames=" + std::to_string(holder->transfer.scheduledFrames.load()) +
            " pcmLevel=" + std::to_string(pcm.levelBytes) + "/" +
                std::to_string(pcm.capacityBytes) +
            " pcmFreeBytes=" + std::to_string(pcm.freeBytes) +
            " pcmMaxLevelBytes=" + std::to_string(pcm.maxLevelBytes) +
            " pcmBackpressureEvents=" + std::to_string(pcm.backpressureEvents) +
            " pcmBackpressureTotalMs=" + std::to_string(pcm.backpressureTotalUs / 1000) +
            " pcmBackpressureCurrentMs=" + std::to_string(pcm.backpressureCurrentUs / 1000) +
            " pcmBackpressureMaxMs=" + std::to_string(pcm.backpressureMaxUs / 1000) +
            " bufferMs=" + std::to_string(bufferMs) +
            " requestedBufferMs=" + std::to_string(holder->player.pcmRingDurationMs) +
            " fifoMs=" + std::to_string(fifoMs) +
            " queuedFrames=" + std::to_string(queuedFrames) +
            " stagedFrames=" + std::to_string(stagedPlayerFrames) +
            " replayFrames=" + std::to_string(replayPlayerFrames) +
            " startupPrerollFrames=" +
                std::to_string(holder->player.playerStartupPreroll.framesRemaining()) +
            " completedAudioFrames=" + std::to_string(holder->player.completedAudioFrames.load()) +
            " playerInputBytes=" + std::to_string(pcm.inputBytes) +
            " playerOutputBytes=" + std::to_string(pcm.outputBytes) +
            " playerDroppedBytes=" + std::to_string(pcm.droppedBytes) +
            " playerUnderrunBytes=" + std::to_string(pcm.underrunBytes) +
            " playerZeroFillBytes=" + std::to_string(pcm.zeroFillBytes) +
            " playerPausedZeroFillBytes=" + std::to_string(pcm.pausedZeroFillBytes) +
            " playerSignalFrames=" + std::to_string(pcm.signalOutputFrames) +
            " playerSilentFrames=" + std::to_string(pcm.silentOutputFrames) +
            " playerSignalBytes=" + std::to_string(pcm.signalOutputBytes) +
            " outputPeak=" + std::to_string(pcm.outputPeak) +
            " lastOutputPeak=" + std::to_string(pcm.lastOutputPeak) +
            " channel0OutputPeak=" + std::to_string(pcm.channel0OutputPeak) +
            " channel1OutputPeak=" + std::to_string(pcm.channel1OutputPeak) +
            " lastChannel0OutputPeak=" +
                std::to_string(pcm.lastChannel0OutputPeak) +
            " lastChannel1OutputPeak=" +
                std::to_string(pcm.lastChannel1OutputPeak) +
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
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeAcknowledgeRecoveryAction(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jlong actionGeneration,
    jlong actionId
) {
    try {
        const auto holder = acquireHandle(handleValue);
        const neri::usb::UsbRecoveryActionAckStatus status = acknowledgeRecoveryAction(
            holder.get(),
            static_cast<int64_t>(actionGeneration),
            static_cast<int64_t>(actionId)
        );
        if (holder == nullptr) {
            LOGW(
                "nativeAcknowledgeRecoveryAction ignored invalid handle=%lld status=%s",
                static_cast<long long>(handleValue),
                neri::usb::usbRecoveryActionAckStatusName(status)
            );
        } else {
            LOGI(
                "nativeAcknowledgeRecoveryAction: handle=%lld generation=%lld "
                "actionId=%lld status=%s",
                static_cast<long long>(handleValue),
                static_cast<long long>(actionGeneration),
                static_cast<long long>(actionId),
                neri::usb::usbRecoveryActionAckStatusName(status)
            );
        }
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(status));
    } catch (const std::exception& error) {
        LOGE("nativeAcknowledgeRecoveryAction exception: %s", error.what());
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(
            neri::usb::UsbRecoveryActionAckStatus::NoPending
        ));
    } catch (...) {
        LOGE("nativeAcknowledgeRecoveryAction unknown exception");
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(
            neri::usb::UsbRecoveryActionAckStatus::NoPending
        ));
    }
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
