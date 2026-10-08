#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace neri::usb::control {

inline constexpr uint8_t kFeatureUnitMuteSelector = 0x01;
inline constexpr uint8_t kFeatureUnitVolumeSelector = 0x02;

/** Feature Unit 上一个主机可写的控制；channel 0 是主通道 */
struct FeatureUnitControl {
    int unitId = 0;
    int channel = 0;
    uint8_t selector = 0;
};

/**
 * 从 Audio Control 接口的类描述符里找出播放路径上的静音与音量控制。
 * 播放路径指最终送到耳机、扬声器等物理输出端子的那一串 Feature Unit；
 * 录音路径（输出端子是 USB streaming）不受影响，避免改动耳麦话筒增益
 */
std::vector<FeatureUnitControl> findPlaybackFeatureUnitControls(
    const uint8_t* descriptors,
    size_t length,
    int uacVersion
);

/**
 * 硬件音量取 0 dB（单位 1/256 dB）：比特完美要求 DAC 既不额外衰减也不放大，
 * 设备范围不含 0 dB 时取最接近的端点
 */
int16_t unityVolumeWithinRange(int16_t minimum, int16_t maximum);

/**
 * 关闭时给静音控制写回的值。音量键拉到 0 时会补静音，原本没静音也得写回 0；
 * 读不到原值时同样写回 0，与打开后解除静音的状态一致
 */
uint8_t muteRestoreValue(bool originalKnown, uint8_t original);

struct FeatureUnitVolume {
    FeatureUnitControl control;
    int16_t minimum = 0;
    int16_t maximum = 0;
};

/**
 * 比特完美模式下由音量键驱动的硬件音量：取最靠近输出端子、可调范围足够的那个 Feature Unit，
 * 有主通道就只调主通道，避免主通道和分通道叠加衰减
 */
std::vector<FeatureUnitVolume> selectHardwareVolumeControls(const std::vector<FeatureUnitVolume>& volumes);

/** 音量比例到硬件音量，曲线与应用数字音量一致（增益 = 比例²），上限 0 dB */
int16_t hardwareVolumeForFraction(float fraction, int16_t minimum, int16_t maximum);

/** 读取 UAC2 RANGE 应答的第一个子区间 */
bool decodeUac2VolumeRange(const uint8_t* data, size_t length, int16_t* minimum, int16_t* maximum);

int16_t decodeLittleEndianInt16(const uint8_t* data);
void encodeLittleEndianInt16(int16_t value, uint8_t* output);

} // namespace neri::usb::control
