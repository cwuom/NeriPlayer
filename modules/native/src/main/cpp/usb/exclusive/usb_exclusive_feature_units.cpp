#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "usb/control/usb_feature_unit.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {
namespace {

using neri::usb::control::FeatureUnitControl;
using neri::usb::control::FeatureUnitVolume;

// 部分 DAC 不支持某些请求会直接 STALL，超时放短避免拖慢打开
constexpr unsigned int kFeatureUnitTimeoutMs = 300;
constexpr uint8_t kAudioControlSubclass = 0x01;
constexpr uint8_t kSetCur = 0x01;
constexpr uint8_t kUac1GetCur = 0x81;
constexpr uint8_t kUac1GetMin = 0x82;
constexpr uint8_t kUac1GetMax = 0x83;
constexpr uint8_t kUac2Cur = 0x01;
constexpr uint8_t kUac2Range = 0x02;
constexpr uint16_t kUac2RangeBytes = 14;
constexpr float kVolumeFractionEpsilon = 0.0001f;

int audioControlInterfaceFor(const UsbExclusiveHandle* handle) {
    if (handle->device.audioControlInterface >= 0) return handle->device.audioControlInterface;
    for (const auto& claimed : handle->device.claimedAudioInterfaces) {
        if (claimed.subclass == kAudioControlSubclass) return claimed.interfaceNumber;
    }
    return -1;
}

std::vector<uint8_t> audioControlDescriptors(libusb_device_handle* devh, int interfaceNumber) {
    std::vector<uint8_t> descriptors;
    libusb_config_descriptor* config = nullptr;
    if (libusb_get_active_config_descriptor(libusb_get_device(devh), &config) != LIBUSB_SUCCESS || config == nullptr) {
        return descriptors;
    }
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces && descriptors.empty(); ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceNumber != interfaceNumber || alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kAudioControlSubclass || alt.extra == nullptr || alt.extra_length <= 0) {
                continue;
            }
            descriptors.assign(alt.extra, alt.extra + alt.extra_length);
            break;
        }
    }
    libusb_free_config_descriptor(config);
    return descriptors;
}

int controlRequest(
    libusb_device_handle* devh,
    uint8_t direction,
    uint8_t request,
    const FeatureUnitControl& control,
    int interfaceNumber,
    uint8_t* data,
    uint16_t length
) {
    const auto value = static_cast<uint16_t>((control.selector << 8) | (control.channel & 0xFF));
    const auto index = static_cast<uint16_t>((control.unitId << 8) | (interfaceNumber & 0xFF));
    return libusb_control_transfer(
        devh,
        makeClassInterfaceRequestType(direction),
        request,
        value,
        index,
        data,
        length,
        kFeatureUnitTimeoutMs
    );
}

int setMute(libusb_device_handle* devh, const FeatureUnitControl& control, int interfaceNumber, bool muted) {
    uint8_t value = muted ? 1 : 0;
    return controlRequest(devh, kLibusbEndpointOut, kSetCur, control, interfaceNumber, &value, 1);
}

int setVolume(libusb_device_handle* devh, const FeatureUnitControl& control, int interfaceNumber, int16_t volume) {
    uint8_t value[2] = {};
    neri::usb::control::encodeLittleEndianInt16(volume, value);
    return controlRequest(devh, kLibusbEndpointOut, kSetCur, control, interfaceNumber, value, 2);
}

bool readVolumeRange(
    libusb_device_handle* devh,
    const FeatureUnitControl& control,
    int interfaceNumber,
    int uacVersion,
    int16_t* minimum,
    int16_t* maximum
) {
    if (uacVersion == 2) {
        uint8_t range[kUac2RangeBytes] = {};
        const int rc = controlRequest(devh, kLibusbEndpointIn, kUac2Range, control, interfaceNumber, range, kUac2RangeBytes);
        return rc > 0 && neri::usb::control::decodeUac2VolumeRange(range, static_cast<size_t>(rc), minimum, maximum);
    }
    uint8_t low[2] = {};
    uint8_t high[2] = {};
    if (controlRequest(devh, kLibusbEndpointIn, kUac1GetMin, control, interfaceNumber, low, 2) != 2 ||
        controlRequest(devh, kLibusbEndpointIn, kUac1GetMax, control, interfaceNumber, high, 2) != 2) {
        return false;
    }
    *minimum = neri::usb::control::decodeLittleEndianInt16(low);
    *maximum = neri::usb::control::decodeLittleEndianInt16(high);
    return true;
}

struct ApplyResult {
    std::vector<FeatureUnitVolume> volumes;
    std::vector<FeatureUnitControl> mutes;
    int failures = 0;
    bool deviceGone = false;
};

void recordFailure(ApplyResult* result, int rc) {
    ++result->failures;
    result->deviceGone = result->deviceGone || rc == LIBUSB_ERROR_NO_DEVICE;
}

void applyMute(UsbExclusiveHandle* handle, const FeatureUnitControl& control, ApplyResult* result) {
    UsbFeatureUnitState& state = handle->featureUnits;
    libusb_device_handle* devh = handle->device.devh;
    const uint8_t getCur = handle->device.uacVersion == 2 ? kUac2Cur : kUac1GetCur;
    uint8_t current = 0;
    const bool known = controlRequest(devh, kLibusbEndpointIn, getCur, control, state.interfaceNumber, &current, 1) == 1;
    const int rc = setMute(devh, control, state.interfaceNumber, false);
    if (rc != 1) {
        recordFailure(result, rc);
        return;
    }
    result->mutes.push_back(control);
    if (known && current != 0) state.restore.push_back(FeatureUnitRestoreEntry { control, current });
}

void applyVolume(UsbExclusiveHandle* handle, const FeatureUnitControl& control, ApplyResult* result) {
    UsbFeatureUnitState& state = handle->featureUnits;
    libusb_device_handle* devh = handle->device.devh;
    FeatureUnitVolume volume { control, 0, 0 };
    if (!readVolumeRange(devh, control, state.interfaceNumber, handle->device.uacVersion, &volume.minimum, &volume.maximum)) {
        ++result->failures;
        return;
    }
    const uint8_t getCur = handle->device.uacVersion == 2 ? kUac2Cur : kUac1GetCur;
    uint8_t current[2] = {};
    const bool known = controlRequest(devh, kLibusbEndpointIn, getCur, control, state.interfaceNumber, current, 2) == 2;
    const int16_t unity = neri::usb::control::unityVolumeWithinRange(volume.minimum, volume.maximum);
    const int rc = setVolume(devh, control, state.interfaceNumber, unity);
    if (rc != 2) {
        recordFailure(result, rc);
        return;
    }
    result->volumes.push_back(volume);
    if (known) {
        state.restore.push_back(FeatureUnitRestoreEntry { control, neri::usb::control::decodeLittleEndianInt16(current) });
    }
}

void resetFeatureUnitStateLocked(UsbFeatureUnitState* state) {
    state->active = false;
    state->restore.clear();
    state->hardwareVolume.clear();
    state->hardwareMute.clear();
    state->appliedFraction = 1.0f;
    state->mutedForVolume = false;
}

std::vector<FeatureUnitControl> mutesOfUnit(const std::vector<FeatureUnitControl>& mutes, int unitId) {
    std::vector<FeatureUnitControl> selected;
    for (const auto& mute : mutes) {
        if (mute.unitId != unitId) continue;
        if (mute.channel == 0) return { mute };
        selected.push_back(mute);
    }
    return selected;
}

} // namespace

/**
 * 独占时绕过了系统混音器，内核驱动解绑后 DAC 仍保留系统设过的硬件音量甚至静音，
 * 所以打开时把播放路径上的 Feature Unit 解除静音并设为 0 dB，数字音量全部交给应用
 */
void applyBitPerfectFeatureUnits(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->device.devh == nullptr) return;
    UsbFeatureUnitState& state = handle->featureUnits;
    std::lock_guard<std::mutex> guard(state.lock);
    resetFeatureUnitStateLocked(&state);
    state.interfaceNumber = audioControlInterfaceFor(handle);
    if (state.interfaceNumber < 0) {
        state.status = "no_audio_control_interface";
        return;
    }
    const std::vector<uint8_t> descriptors = audioControlDescriptors(handle->device.devh, state.interfaceNumber);
    const auto controls = neri::usb::control::findPlaybackFeatureUnitControls(
        descriptors.data(),
        descriptors.size(),
        handle->device.uacVersion
    );
    ApplyResult result;
    for (const auto& control : controls) {
        if (control.selector == neri::usb::control::kFeatureUnitMuteSelector) {
            applyMute(handle, control, &result);
        } else {
            applyVolume(handle, control, &result);
        }
        if (result.deviceGone) break;
    }
    state.hardwareVolume = neri::usb::control::selectHardwareVolumeControls(result.volumes);
    if (!state.hardwareVolume.empty()) {
        state.hardwareMute = mutesOfUnit(result.mutes, state.hardwareVolume.front().control.unitId);
    }
    state.active = !result.deviceGone;
    state.status = "controls=" + std::to_string(controls.size()) +
        ":unmuted=" + std::to_string(result.mutes.size()) +
        ":unity=" + std::to_string(result.volumes.size()) +
        ":hardware_volume=" + std::to_string(state.hardwareVolume.size()) +
        ":failures=" + std::to_string(result.failures);
    LOGI("feature unit defaults: ac=%d uac=%d %s", state.interfaceNumber, handle->device.uacVersion, state.status.c_str());
}

/** 比特完美时音量键改的是 DAC 硬件音量，PCM 数据保持不变 */
bool setHardwareVolumeFraction(UsbExclusiveHandle* handle, float fraction) {
    UsbFeatureUnitState& state = handle->featureUnits;
    std::lock_guard<std::mutex> guard(state.lock);
    if (!state.active || state.hardwareVolume.empty() || handle->device.devh == nullptr) return false;
    const float target = std::isfinite(fraction) ? std::clamp(fraction, 0.0f, 1.0f) : 0.0f;
    if (std::fabs(target - state.appliedFraction) < kVolumeFractionEpsilon) return true;
    bool applied = false;
    for (const auto& volume : state.hardwareVolume) {
        const int16_t value = neri::usb::control::hardwareVolumeForFraction(target, volume.minimum, volume.maximum);
        const int rc = setVolume(handle->device.devh, volume.control, state.interfaceNumber, value);
        if (rc == LIBUSB_ERROR_NO_DEVICE) return false;
        applied = applied || rc == 2;
    }
    const bool muted = target <= 0.0f;
    if (applied && muted != state.mutedForVolume) {
        for (const auto& mute : state.hardwareMute) setMute(handle->device.devh, mute, state.interfaceNumber, muted);
        state.mutedForVolume = muted;
    }
    if (applied) state.appliedFraction = target;
    return applied;
}

bool hasHardwareVolume(UsbExclusiveHandle* handle) {
    UsbFeatureUnitState& state = handle->featureUnits;
    std::lock_guard<std::mutex> guard(state.lock);
    return state.active && !state.hardwareVolume.empty();
}

void releaseFeatureUnits(UsbExclusiveHandle* handle, bool restoreDevice) {
    if (handle == nullptr) return;
    UsbFeatureUnitState& state = handle->featureUnits;
    std::lock_guard<std::mutex> guard(state.lock);
    const bool wasActive = state.active;
    state.active = false;
    if (!wasActive || !restoreDevice || handle->device.devh == nullptr || state.restore.empty()) {
        resetFeatureUnitStateLocked(&state);
        return;
    }
    size_t restored = 0;
    for (auto entry = state.restore.rbegin(); entry != state.restore.rend(); ++entry) {
        const bool mute = entry->control.selector == neri::usb::control::kFeatureUnitMuteSelector;
        const int rc = mute
            ? setMute(handle->device.devh, entry->control, state.interfaceNumber, entry->value != 0)
            : setVolume(handle->device.devh, entry->control, state.interfaceNumber, entry->value);
        if (rc == LIBUSB_ERROR_NO_DEVICE) break;
        if (rc == (mute ? 1 : 2)) ++restored;
    }
    if (restored != state.restore.size()) {
        LOGW("feature unit restore incomplete: restored=%zu of %zu", restored, state.restore.size());
    }
    resetFeatureUnitStateLocked(&state);
}

} // namespace neri::usb::exclusive

using neri::usb::exclusive::acquireHandle;

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetHardwareVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jfloat fraction
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load() || !holder->recovery.deviceOnline.load()) {
        return JNI_FALSE;
    }
    return neri::usb::exclusive::setHardwareVolumeFraction(holder.get(), static_cast<float>(fraction)) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeHasHardwareVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load()) return JNI_FALSE;
    return neri::usb::exclusive::hasHardwareVolume(holder.get()) ? JNI_TRUE : JNI_FALSE;
}
