#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac1 {

constexpr uint16_t kPcmFormatTag = 0x0001;

enum class SampleRateKind {
    None,
    Discrete,
    Continuous
};

struct TypeIFormat {
    uint16_t formatTag = 0;
    int channels = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;
    SampleRateKind sampleRateKind = SampleRateKind::None;
    std::vector<int> discreteSampleRates;
    int minimumSampleRate = 0;
    int maximumSampleRate = 0;

    [[nodiscard]] bool isPcm() const;
    [[nodiscard]] bool supportsSampleRate(int sampleRate) const;
    [[nodiscard]] bool isFixedAt(int sampleRate) const;
    [[nodiscard]] std::string sampleRateSummary() const;
};

struct EndpointControls {
    bool hasGeneralDescriptor = false;
    bool samplingFrequencyControl = false;
};

struct FormatTarget {
    int sampleRate = 0;
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

const char* syncTypeName(uint8_t endpointAttributes);

bool requiresFeedbackScheduler(uint8_t endpointAttributes);

} // namespace neri::usb::uac1
