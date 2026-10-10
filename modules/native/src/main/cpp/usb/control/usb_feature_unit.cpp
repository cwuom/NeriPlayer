#include "usb/control/usb_feature_unit.h"

#include <algorithm>
#include <cmath>
#include <unordered_map>
#include <unordered_set>

namespace neri::usb::control {
namespace {

constexpr uint8_t kClassSpecificInterface = 0x24;
constexpr uint8_t kOutputTerminalSubtype = 0x03;
constexpr uint8_t kFeatureUnitSubtype = 0x06;
constexpr uint16_t kUsbTerminalTypeMask = 0xFF00;
constexpr uint16_t kUsbTerminalTypes = 0x0100;
constexpr int kMaxTopologyDepth = 16;
constexpr uint32_t kUac2HostProgrammable = 0x3;
// 可调范围太窄的音量控制交给音量键没有意义
constexpr int kMinimumUsableVolumeRange = 20 * 256;
// UAC 规定 RES 取 1..0x7FFF
constexpr uint16_t kMaximumVolumeResolution = 0x7FFF;

struct FeatureUnit {
    int sourceId = 0;
    std::vector<FeatureUnitControl> controls;
};

struct Topology {
    std::unordered_map<int, FeatureUnit> featureUnits;
    std::vector<int> physicalOutputSources;
};

uint32_t readLittleEndian(const uint8_t* data, int bytes) {
    uint32_t value = 0;
    for (int index = std::min(bytes, 4) - 1; index >= 0; --index) {
        value = (value << 8) | data[index];
    }
    return value;
}

void appendControl(FeatureUnit* unit, int unitId, int channel, uint8_t selector) {
    unit->controls.push_back(FeatureUnitControl { unitId, channel, selector });
}

bool parseUac1FeatureUnit(const uint8_t* descriptor, int length, FeatureUnit* unit) {
    if (length < 7) return false;
    const int controlSize = descriptor[5];
    if (controlSize <= 0) return false;
    const int channelSlots = (length - 7) / controlSize;
    const int unitId = descriptor[3];
    for (int channel = 0; channel < channelSlots; ++channel) {
        const uint32_t bits = readLittleEndian(descriptor + 6 + channel * controlSize, controlSize);
        if (bits & 0x1U) appendControl(unit, unitId, channel, kFeatureUnitMuteSelector);
        if (bits & 0x2U) appendControl(unit, unitId, channel, kFeatureUnitVolumeSelector);
    }
    return true;
}

bool parseUac2FeatureUnit(const uint8_t* descriptor, int length, FeatureUnit* unit) {
    if (length < 10) return false;
    const int channelSlots = (length - 6) / 4;
    const int unitId = descriptor[3];
    for (int channel = 0; channel < channelSlots; ++channel) {
        const uint32_t bits = readLittleEndian(descriptor + 5 + channel * 4, 4);
        if ((bits & kUac2HostProgrammable) == kUac2HostProgrammable) {
            appendControl(unit, unitId, channel, kFeatureUnitMuteSelector);
        }
        if (((bits >> 2) & kUac2HostProgrammable) == kUac2HostProgrammable) {
            appendControl(unit, unitId, channel, kFeatureUnitVolumeSelector);
        }
    }
    return true;
}

void recordDescriptor(const uint8_t* descriptor, int length, int uacVersion, Topology* topology) {
    const uint8_t subtype = descriptor[2];
    if (subtype == kOutputTerminalSubtype && length >= 9) {
        const auto terminalType = static_cast<uint16_t>(readLittleEndian(descriptor + 4, 2));
        if ((terminalType & kUsbTerminalTypeMask) != kUsbTerminalTypes) {
            topology->physicalOutputSources.push_back(descriptor[7]);
        }
        return;
    }
    if (subtype != kFeatureUnitSubtype || length < 6) return;
    FeatureUnit unit;
    unit.sourceId = descriptor[4];
    const bool parsed = uacVersion == 2
        ? parseUac2FeatureUnit(descriptor, length, &unit)
        : parseUac1FeatureUnit(descriptor, length, &unit);
    if (parsed) topology->featureUnits[descriptor[3]] = unit;
}

Topology parseTopology(const uint8_t* descriptors, size_t length, int uacVersion) {
    Topology topology;
    size_t offset = 0;
    while (offset + 3 <= length) {
        const int descriptorLength = descriptors[offset];
        if (descriptorLength < 3 || offset + static_cast<size_t>(descriptorLength) > length) break;
        const uint8_t* descriptor = descriptors + offset;
        if (descriptor[1] == kClassSpecificInterface) {
            recordDescriptor(descriptor, descriptorLength, uacVersion, &topology);
        }
        offset += static_cast<size_t>(descriptorLength);
    }
    return topology;
}

bool usableVolumeRange(const FeatureUnitVolume& volume) {
    const int low = std::min(volume.minimum, volume.maximum);
    const int top = unityVolumeWithinRange(volume.minimum, volume.maximum);
    return low < 0 && top - low >= kMinimumUsableVolumeRange;
}

} // namespace

std::vector<FeatureUnitControl> findPlaybackFeatureUnitControls(
    const uint8_t* descriptors,
    size_t length,
    int uacVersion
) {
    std::vector<FeatureUnitControl> controls;
    if (descriptors == nullptr || length == 0 || (uacVersion != 1 && uacVersion != 2)) return controls;
    const Topology topology = parseTopology(descriptors, length, uacVersion);
    std::unordered_set<int> visited;
    for (int source : topology.physicalOutputSources) {
        for (int depth = 0; depth < kMaxTopologyDepth; ++depth) {
            const auto unit = topology.featureUnits.find(source);
            if (unit == topology.featureUnits.end() || !visited.insert(source).second) break;
            controls.insert(controls.end(), unit->second.controls.begin(), unit->second.controls.end());
            source = unit->second.sourceId;
        }
    }
    return controls;
}

int16_t unityVolumeWithinRange(int16_t minimum, int16_t maximum) {
    const int16_t low = std::min(minimum, maximum);
    const int16_t high = std::max(minimum, maximum);
    return std::clamp<int16_t>(0, low, high);
}

uint8_t muteRestoreValue(bool originalKnown, uint8_t original) {
    return originalKnown && original != 0 ? 1 : 0;
}

std::vector<FeatureUnitVolume> selectHardwareVolumeControls(const std::vector<FeatureUnitVolume>& volumes) {
    for (const auto& candidate : volumes) {
        if (!usableVolumeRange(candidate)) continue;
        std::vector<FeatureUnitVolume> selected;
        for (const auto& volume : volumes) {
            if (volume.control.unitId != candidate.control.unitId || !usableVolumeRange(volume)) continue;
            if (volume.control.channel == 0) return { volume };
            selected.push_back(volume);
        }
        return selected;
    }
    return {};
}

int16_t quantizeVolumeToResolution(int value, int16_t minimum, int16_t maximum, uint16_t resolution) {
    const int low = std::min(minimum, maximum);
    const int high = std::max(minimum, maximum);
    const int clamped = std::clamp(value, low, high);
    if (resolution <= 1 || resolution > kMaximumVolumeResolution || resolution > high - low) {
        return static_cast<int16_t>(clamped);
    }
    return static_cast<int16_t>(low + (clamped - low) / resolution * resolution);
}

int16_t hardwareVolumeForFraction(float fraction, int16_t minimum, int16_t maximum, uint16_t resolution) {
    const int16_t low = std::min(minimum, maximum);
    const int16_t top = unityVolumeWithinRange(minimum, maximum);
    long target = top;
    if (!(fraction > 0.0f)) {
        target = low;
    } else if (fraction < 1.0f) {
        const double decibels = 40.0 * std::log10(static_cast<double>(fraction));
        target = std::clamp<long>(std::lround(decibels * 256.0), low, top);
    }
    return quantizeVolumeToResolution(static_cast<int>(target), minimum, maximum, resolution);
}

bool decodeUac2VolumeRange(
    const uint8_t* data,
    size_t length,
    int16_t* minimum,
    int16_t* maximum,
    uint16_t* resolution
) {
    if (data == nullptr || minimum == nullptr || maximum == nullptr || resolution == nullptr || length < 8) {
        return false;
    }
    if (readLittleEndian(data, 2) == 0) return false;
    *minimum = decodeLittleEndianInt16(data + 2);
    *maximum = decodeLittleEndianInt16(data + 4);
    *resolution = static_cast<uint16_t>(readLittleEndian(data + 6, 2));
    return true;
}

int16_t decodeLittleEndianInt16(const uint8_t* data) {
    return static_cast<int16_t>(static_cast<uint16_t>(data[0]) | (static_cast<uint16_t>(data[1]) << 8));
}

void encodeLittleEndianInt16(int16_t value, uint8_t* output) {
    const auto raw = static_cast<uint16_t>(value);
    output[0] = static_cast<uint8_t>(raw & 0xFF);
    output[1] = static_cast<uint8_t>((raw >> 8) & 0xFF);
}

} // namespace neri::usb::control
