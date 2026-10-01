#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <mutex>

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

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
