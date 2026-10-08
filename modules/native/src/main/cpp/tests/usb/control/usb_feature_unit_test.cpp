#include "usb/control/usb_feature_unit.h"

#include <cassert>
#include <cstdint>

namespace {

using neri::usb::control::hardwareVolumeForFraction;
using neri::usb::control::muteRestoreValue;
using neri::usb::control::quantizeVolumeToResolution;

void restoresUnmutedOriginalAfterVolumeMute() {
    // 原本未静音：音量键拉到 0 补上的静音必须在关闭时撤销
    assert(muteRestoreValue(true, 0) == 0);
}

void restoresMutedOriginal() {
    assert(muteRestoreValue(true, 1) == 1);
    assert(muteRestoreValue(true, 0xFF) == 1);
}

void restoresUnknownOriginalAsUnmuted() {
    assert(muteRestoreValue(false, 1) == 0);
    assert(muteRestoreValue(false, 0) == 0);
}

void quantizesHardwareVolumeToDeviceSteps() {
    // -127.5 dB 起步、1 dB 一档：0 dB 不在步进上
    constexpr int16_t minimum = -127 * 256 - 128;
    constexpr int16_t maximum = 6 * 256;
    constexpr uint16_t resolution = 256;
    // 取不高于 0 dB 的那一档，比特完美时不放大
    assert(hardwareVolumeForFraction(1.0f, minimum, maximum, resolution) == -128);
    // 一半音量是 -12.04 dB，落到 -12.5 dB
    assert(hardwareVolumeForFraction(0.5f, minimum, maximum, resolution) == -12 * 256 - 128);
    assert(hardwareVolumeForFraction(0.0f, minimum, maximum, resolution) == minimum);
    for (int percent = 1; percent < 100; ++percent) {
        const float fraction = static_cast<float>(percent) / 100.0f;
        const int value = hardwareVolumeForFraction(fraction, minimum, maximum, resolution);
        assert((value - minimum) % resolution == 0);
        assert(value <= hardwareVolumeForFraction(fraction, minimum, maximum));
    }
}

void keepsRequestedVolumeWithoutUsableResolution() {
    assert(quantizeVolumeToResolution(-3083, -12800, 0, 1) == -3083);
    assert(quantizeVolumeToResolution(-3083, -12800, 0, 0) == -3083);
    assert(quantizeVolumeToResolution(-3083, -12800, 0, 20000) == -3083);
    assert(quantizeVolumeToResolution(-3083, -32768, 32767, 0x8000) == -3083);
    assert(quantizeVolumeToResolution(100, -12800, 0, 256) == 0);
    assert(quantizeVolumeToResolution(-20000, -12800, 0, 256) == -12800);
}

} // namespace

int main() {
    restoresUnmutedOriginalAfterVolumeMute();
    restoresMutedOriginal();
    restoresUnknownOriginalAsUnmuted();
    quantizesHardwareVolumeToDeviceSteps();
    keepsRequestedVolumeWithoutUsableResolution();
    return 0;
}
