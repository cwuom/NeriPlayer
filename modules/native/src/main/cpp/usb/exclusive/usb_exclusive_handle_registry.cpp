#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <system_error>
#include <thread>
#include <unordered_map>

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
std::atomic<int> g_nextQuarantineIndex { 1 };
std::atomic<int> g_quarantinedDrainHandles { 0 };
std::mutex g_parkedHandlesLock;
std::array<ParkedHandleSlot, kMaximumParkedHandles> g_parkedHandles;
std::array<std::shared_ptr<UsbExclusiveHandle>, kMaximumHardRetainedHandles>
    g_hardRetainedHandles;

int parkedExponentialBackoffMs(int consecutiveErrors) {
    const int shift = std::min(std::max(0, consecutiveErrors - 1), 6);
    return std::min(
        kParkedErrorBackoffMaxMs,
        kParkedErrorBackoffBaseMs << shift
    );
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

} // namespace neri::usb::exclusive
