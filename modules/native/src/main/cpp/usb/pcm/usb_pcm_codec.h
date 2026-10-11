#pragma once

#include <cstdint>

namespace neri::usb {

int bytesPerSampleForEncoding(int encoding);

bool isLittleEndianIntegerPcmEncoding(int encoding);

bool isBigEndianIntegerPcmEncoding(int encoding);

int integerPcmBitsForEncoding(int encoding);

/** 整数 PCM 的有符号样本值，位宽同编码；非整数编码返回 0 */
int32_t readEncodedIntegerPcmSample(const uint8_t* input, int encoding);

float readEncodedPcmSample(const uint8_t* input, int encoding);

float readIntegerPcmSample(
    const uint8_t* input,
    int subslotBytes,
    int bitsPerSample
);

void writeIntegerPcmSample(
    uint8_t* output,
    int subslotBytes,
    int bitsPerSample,
    float sample
);

/** 按设备布局写出已在有效位宽内的整数样本，超出范围时饱和 */
void writeIntegerPcmValue(
    uint8_t* output,
    int subslotBytes,
    int bitsPerSample,
    int64_t value
);

} // namespace neri::usb
