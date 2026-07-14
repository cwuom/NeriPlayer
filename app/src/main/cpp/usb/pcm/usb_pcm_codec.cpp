#include "usb_pcm_codec.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace neri::usb {
namespace {

constexpr int kEncodingPcm16Bit = 2;
constexpr int kEncodingPcm8Bit = 3;
constexpr int kEncodingPcmFloat = 4;
constexpr int kEncodingPcm24Bit = 21;
constexpr int kEncodingPcm32Bit = 22;
constexpr int kEncodingPcm16BitBigEndian = 0x10000000;
constexpr int kEncodingPcm24BitBigEndian = 0x50000000;
constexpr int kEncodingPcm32BitBigEndian = 0x60000000;

bool isBigEndianEncoding(int encoding) {
    return encoding == kEncodingPcm16BitBigEndian ||
        encoding == kEncodingPcm24BitBigEndian ||
        encoding == kEncodingPcm32BitBigEndian;
}

} // namespace

int bytesPerSampleForEncoding(int encoding) {
    switch (encoding) {
        case kEncodingPcm8Bit:
            return 1;
        case kEncodingPcm16Bit:
        case kEncodingPcm16BitBigEndian:
            return 2;
        case kEncodingPcm24Bit:
        case kEncodingPcm24BitBigEndian:
            return 3;
        case kEncodingPcmFloat:
        case kEncodingPcm32Bit:
        case kEncodingPcm32BitBigEndian:
            return 4;
        default:
            return 0;
    }
}

float readEncodedPcmSample(const uint8_t* input, int encoding) {
    if (input == nullptr) {
        return 0.0f;
    }
    const bool bigEndian = isBigEndianEncoding(encoding);
    switch (encoding) {
        case kEncodingPcm8Bit:
            return (static_cast<int>(input[0]) - 128) / 128.0f;
        case kEncodingPcm16Bit:
        case kEncodingPcm16BitBigEndian: {
            const uint16_t raw = bigEndian
                ? (static_cast<uint16_t>(input[0]) << 8U) | input[1]
                : static_cast<uint16_t>(input[0]) |
                    (static_cast<uint16_t>(input[1]) << 8U);
            return static_cast<float>(static_cast<int16_t>(raw)) / 32768.0f;
        }
        case kEncodingPcm24Bit:
        case kEncodingPcm24BitBigEndian: {
            uint32_t raw = bigEndian
                ? (static_cast<uint32_t>(input[0]) << 16U) |
                    (static_cast<uint32_t>(input[1]) << 8U) | input[2]
                : static_cast<uint32_t>(input[0]) |
                    (static_cast<uint32_t>(input[1]) << 8U) |
                    (static_cast<uint32_t>(input[2]) << 16U);
            if ((raw & UINT32_C(0x800000)) != 0U) {
                raw |= UINT32_C(0xFF000000);
            }
            return static_cast<float>(static_cast<int32_t>(raw)) / 8388608.0f;
        }
        case kEncodingPcm32Bit:
        case kEncodingPcm32BitBigEndian: {
            const uint32_t raw = bigEndian
                ? (static_cast<uint32_t>(input[0]) << 24U) |
                    (static_cast<uint32_t>(input[1]) << 16U) |
                    (static_cast<uint32_t>(input[2]) << 8U) | input[3]
                : static_cast<uint32_t>(input[0]) |
                    (static_cast<uint32_t>(input[1]) << 8U) |
                    (static_cast<uint32_t>(input[2]) << 16U) |
                    (static_cast<uint32_t>(input[3]) << 24U);
            return static_cast<float>(
                static_cast<double>(static_cast<int32_t>(raw)) / 2147483648.0
            );
        }
        case kEncodingPcmFloat: {
            const uint32_t raw = static_cast<uint32_t>(input[0]) |
                (static_cast<uint32_t>(input[1]) << 8U) |
                (static_cast<uint32_t>(input[2]) << 16U) |
                (static_cast<uint32_t>(input[3]) << 24U);
            float value = 0.0f;
            static_assert(sizeof(value) == sizeof(raw));
            std::memcpy(&value, &raw, sizeof(value));
            return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
        }
        default:
            return 0.0f;
    }
}

float readIntegerPcmSample(
    const uint8_t* input,
    int subslotBytes,
    int bitsPerSample
) {
    if (input == nullptr || subslotBytes <= 0) {
        return 0.0f;
    }
    const int validBits = std::clamp(bitsPerSample, 1, std::min(32, subslotBytes * 8));
    uint32_t raw = 0;
    for (int index = 0; index < std::min(4, subslotBytes); ++index) {
        raw |= static_cast<uint32_t>(input[index]) << (index * 8);
    }
    if (validBits < 32) {
        const uint32_t validMask = (UINT32_C(1) << validBits) - 1U;
        raw &= validMask;
        const uint32_t signBit = UINT32_C(1) << (validBits - 1);
        if ((raw & signBit) != 0U) {
            raw |= ~validMask;
        }
    }
    const auto scale = static_cast<double>(uint64_t { 1 } << (validBits - 1));
    return std::clamp(
        static_cast<float>(static_cast<double>(static_cast<int32_t>(raw)) / scale),
        -1.0f,
        1.0f
    );
}

void writeIntegerPcmSample(
    uint8_t* output,
    int subslotBytes,
    int bitsPerSample,
    float sample
) {
    if (output == nullptr || subslotBytes <= 0) {
        return;
    }
    const int validBits = std::clamp(bitsPerSample, 1, std::min(32, subslotBytes * 8));
    const float clipped = std::isfinite(sample) ? std::clamp(sample, -1.0f, 1.0f) : 0.0f;
    const int64_t negativeScale = int64_t { 1 } << (validBits - 1);
    const int64_t positiveScale = negativeScale - 1;
    const int64_t value = clipped >= 0.0f
        ? static_cast<int64_t>(std::llround(static_cast<double>(clipped) * positiveScale))
        : static_cast<int64_t>(std::llround(static_cast<double>(clipped) * negativeScale));
    const uint64_t validMask = validBits == 32
        ? UINT64_C(0xFFFFFFFF)
        : (UINT64_C(1) << validBits) - 1U;
    uint64_t encoded = static_cast<uint64_t>(value) & validMask;
    if (value < 0 && validBits < subslotBytes * 8) {
        const int storageBits = std::min(32, subslotBytes * 8);
        const uint64_t storageMask = storageBits == 32
            ? UINT64_C(0xFFFFFFFF)
            : (UINT64_C(1) << storageBits) - 1U;
        encoded |= storageMask & ~validMask;
    }
    for (int index = 0; index < subslotBytes; ++index) {
        output[index] = static_cast<uint8_t>((encoded >> (index * 8)) & 0xFFU);
    }
}

} // namespace neri::usb
