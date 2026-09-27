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
