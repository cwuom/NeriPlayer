#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

std::atomic<int64_t> g_nextNativeStreamGeneration { 1 };
std::atomic<int64_t> g_nextRecoveryActionId { 1 };

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

void refreshTerminalRecoveryAction(
    UsbExclusiveHandle* handle,
    bool feedbackTerminalFailure
) {
    if (handle == nullptr ||
        (!feedbackTerminalFailure &&
            !handle->recovery.transportFailed.load() &&
            handle->recovery.deviceOnline.load())) {
        return;
    }
    if (feedbackTerminalFailure && !handle->recovery.transportFailed.load()) {
        markTransportFailed(handle);
    }
    latchTerminalRecoveryAction(
        handle,
        handle->recovery.deviceOnline.load()
            ? neri::usb::UsbRuntimeRecoveryAction::FreshOpen
            : neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
    );
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

} // namespace neri::usb::exclusive
