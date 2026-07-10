#pragma once

#include <cstdint>

namespace neri::usb {

int bytesPerSampleForEncoding(int encoding);

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

} // namespace neri::usb
