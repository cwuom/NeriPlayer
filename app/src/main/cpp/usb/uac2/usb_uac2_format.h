#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac2 {

constexpr uint32_t kPcmFormatBit = 0x00000001;

enum class ControlCapability {
    None,
    ReadOnly,
    ReadWrite
};

struct TypeIFormat {
    int terminalLink = 0;
    uint8_t formatType = 0;
    uint32_t formats = 0;
    int channels = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;

    [[nodiscard]] bool isPcm() const;
    [[nodiscard]] std::string formatSummary() const;
};

struct EndpointControls {
    bool hasGeneralDescriptor = false;
    uint8_t attributes = 0;
    uint8_t controls = 0;
};

struct ClockSource {
    int id = 0;
    uint8_t attributes = 0;
    uint8_t controls = 0;

    [[nodiscard]] ControlCapability samplingFrequencyControl() const;
};

struct TerminalClockSource {
    int terminalId = 0;
    int clockSourceId = 0;
};

struct SampleRateSubrange {
    int minimum = 0;
    int maximum = 0;
    int resolution = 0;

    [[nodiscard]] bool supports(int sampleRate) const;
};

struct FormatTarget {
    int channels = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;
};

bool matchesTarget(
    const TypeIFormat& format,
    const FormatTarget& target,
    std::string* rejectionReason
);

bool parseTypeIFormat(
    const uint8_t* extra,
    int extraLength,
    TypeIFormat* output,
    std::string* error
);

bool parseEndpointControls(
    const uint8_t* extra,
    int extraLength,
    EndpointControls* output,
    std::string* error
);

bool parseClockSourceDescriptor(
    const uint8_t* descriptor,
    int descriptorLength,
    ClockSource* output,
    std::string* error
);

bool parseTerminalClockSourceDescriptor(
    const uint8_t* descriptor,
    int descriptorLength,
    TerminalClockSource* output,
    std::string* error
);

bool parseSampleRateRanges(
    const uint8_t* data,
    int dataLength,
    std::vector<SampleRateSubrange>* output,
    std::string* error
);

bool decodeCurrentSampleRate(
    const uint8_t* data,
    int dataLength,
    int* output,
    std::string* error
);

const char* controlCapabilityName(ControlCapability capability);

const char* syncTypeName(uint8_t endpointAttributes);

bool requiresFeedbackScheduler(uint8_t endpointAttributes);

} // namespace neri::usb::uac2
