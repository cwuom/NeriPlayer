#include "usb_uac1_format.h"

#include <algorithm>
#include <utility>

namespace neri::usb::uac1 {
namespace {

constexpr uint8_t kClassSpecificInterface = 0x24;
constexpr uint8_t kClassSpecificEndpoint = 0x25;
constexpr uint8_t kAsGeneralSubtype = 0x01;
constexpr uint8_t kFormatTypeSubtype = 0x02;
constexpr uint8_t kEndpointGeneralSubtype = 0x01;
constexpr uint8_t kFormatTypeI = 0x01;
constexpr uint8_t kSamplingFrequencyControlMask = 0x01;
constexpr uint8_t kIsoSyncTypeMask = 0x0C;
constexpr uint8_t kIsoAsyncSyncType = 0x04;
constexpr uint8_t kIsoUsageTypeMask = 0x30;
constexpr uint8_t kIsoImplicitUsageType = 0x20;

uint16_t readLe16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0]) |
        static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8);
}

int readLe24(const uint8_t* data) {
    return static_cast<int>(data[0]) |
        (static_cast<int>(data[1]) << 8) |
        (static_cast<int>(data[2]) << 16);
}

void assignError(std::string* error, const char* value) {
    if (error != nullptr) {
        *error = value;
    }
}

bool validateDescriptorBounds(
    const uint8_t* extra,
    int extraLength,
    int offset,
    int* descriptorLength,
    std::string* error
) {
    if (extra == nullptr || offset < 0 || offset + 2 > extraLength) {
        assignError(error, "descriptor_header_truncated");
        return false;
    }
    const int length = extra[offset];
    if (length < 2) {
        assignError(error, "descriptor_length_invalid");
        return false;
    }
    if (offset + length > extraLength) {
        assignError(error, "descriptor_body_truncated");
        return false;
    }
    *descriptorLength = length;
    return true;
}

bool parseFormatDescriptor(
    const uint8_t* descriptor,
    int length,
    TypeIFormat* output,
    std::string* error
) {
    if (length < 8) {
        assignError(error, "format_type_descriptor_too_short");
        return false;
    }
    if (descriptor[3] != kFormatTypeI) {
        assignError(error, "format_type_not_type_i");
        return false;
    }

    output->channels = descriptor[4];
    output->subslotBytes = descriptor[5];
    output->bitsPerSample = descriptor[6];
    const int sampleRateCount = descriptor[7];
    if (output->channels <= 0 || output->subslotBytes <= 0 ||
        output->bitsPerSample <= 0 ||
        output->bitsPerSample > output->subslotBytes * 8) {
        assignError(error, "format_type_fields_invalid");
        return false;
    }

    if (sampleRateCount == 0) {
        if (length < 14) {
            assignError(error, "continuous_sample_rates_truncated");
            return false;
        }
        output->sampleRateKind = SampleRateKind::Continuous;
        output->minimumSampleRate = readLe24(descriptor + 8);
        output->maximumSampleRate = readLe24(descriptor + 11);
        if (output->minimumSampleRate <= 0 ||
            output->maximumSampleRate < output->minimumSampleRate) {
            assignError(error, "continuous_sample_rates_invalid");
            return false;
        }
        return true;
    }

    const int requiredLength = 8 + sampleRateCount * 3;
    if (length < requiredLength) {
        assignError(error, "discrete_sample_rates_truncated");
        return false;
    }
    output->sampleRateKind = SampleRateKind::Discrete;
    output->discreteSampleRates.clear();
    output->discreteSampleRates.reserve(static_cast<size_t>(sampleRateCount));
    for (int index = 0; index < sampleRateCount; ++index) {
        const int sampleRate = readLe24(descriptor + 8 + index * 3);
        if (sampleRate <= 0) {
            assignError(error, "discrete_sample_rate_invalid");
            return false;
        }
        output->discreteSampleRates.push_back(sampleRate);
    }
    std::sort(output->discreteSampleRates.begin(), output->discreteSampleRates.end());
    output->discreteSampleRates.erase(
        std::unique(
            output->discreteSampleRates.begin(),
            output->discreteSampleRates.end()
        ),
        output->discreteSampleRates.end()
    );
    return true;
}

} // namespace

bool TypeIFormat::isPcm() const {
    return formatTag == kPcmFormatTag;
}

bool TypeIFormat::supportsSampleRate(int sampleRate) const {
    if (sampleRate <= 0) {
        return false;
    }
    if (sampleRateKind == SampleRateKind::Continuous) {
        return sampleRate >= minimumSampleRate && sampleRate <= maximumSampleRate;
    }
    return std::find(
        discreteSampleRates.begin(),
        discreteSampleRates.end(),
        sampleRate
    ) != discreteSampleRates.end();
}

bool TypeIFormat::isFixedAt(int sampleRate) const {
    return sampleRateKind == SampleRateKind::Discrete &&
        discreteSampleRates.size() == 1 &&
        discreteSampleRates.front() == sampleRate;
}

std::string TypeIFormat::sampleRateSummary() const {
    if (sampleRateKind == SampleRateKind::Continuous) {
        return std::to_string(minimumSampleRate) + ".." +
            std::to_string(maximumSampleRate);
    }
    if (sampleRateKind != SampleRateKind::Discrete || discreteSampleRates.empty()) {
        return "none";
    }
    std::string summary;
    for (int sampleRate : discreteSampleRates) {
        if (!summary.empty()) {
            summary += ",";
        }
        summary += std::to_string(sampleRate);
    }
    return summary;
}

bool matchesTarget(
    const TypeIFormat& format,
    const FormatTarget& target,
    std::string* rejectionReason
) {
    std::string reason;
    if (!format.isPcm()) {
        reason = "non_pcm_format_tag_" + std::to_string(format.formatTag);
    } else if (format.channels != target.channels) {
        reason = "channel_mismatch_" + std::to_string(format.channels);
    } else if (format.subslotBytes != target.subslotBytes) {
        reason = "subslot_mismatch_" + std::to_string(format.subslotBytes);
    } else if (format.bitsPerSample != target.bitsPerSample) {
        reason = "bit_depth_mismatch_" + std::to_string(format.bitsPerSample);
    } else if (!format.supportsSampleRate(target.sampleRate)) {
        reason = "sample_rate_mismatch_" + format.sampleRateSummary();
    }
    if (rejectionReason != nullptr) {
        *rejectionReason = reason;
    }
    return reason.empty();
}

bool parseTypeIFormat(
    const uint8_t* extra,
    int extraLength,
    TypeIFormat* output,
    std::string* error
) {
    if (output == nullptr || extraLength < 0 || (extra == nullptr && extraLength > 0)) {
        assignError(error, "invalid_streaming_descriptor_input");
        return false;
    }
    TypeIFormat parsed;
    bool foundGeneral = false;
    bool foundFormat = false;
    int offset = 0;
    while (offset < extraLength) {
        int descriptorLength = 0;
        if (!validateDescriptorBounds(
                extra,
                extraLength,
                offset,
                &descriptorLength,
                error
            )) {
            return false;
        }
        const uint8_t* descriptor = extra + offset;
        if (descriptor[1] == kClassSpecificInterface) {
            if (descriptorLength < 3) {
                assignError(error, "class_interface_descriptor_too_short");
                return false;
            }
            if (descriptor[2] == kAsGeneralSubtype) {
                if (descriptorLength < 7) {
                    assignError(error, "as_general_descriptor_too_short");
                    return false;
                }
                parsed.formatTag = readLe16(descriptor + 5);
                foundGeneral = true;
            } else if (descriptor[2] == kFormatTypeSubtype) {
                if (!parseFormatDescriptor(descriptor, descriptorLength, &parsed, error)) {
                    return false;
                }
                foundFormat = true;
            }
        }
        offset += descriptorLength;
    }
    if (!foundGeneral) {
        assignError(error, "as_general_descriptor_missing");
        return false;
    }
    if (!foundFormat) {
        assignError(error, "format_type_descriptor_missing");
        return false;
    }
    *output = std::move(parsed);
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool parseEndpointControls(
    const uint8_t* extra,
    int extraLength,
    EndpointControls* output,
    std::string* error
) {
    if (output == nullptr || extraLength < 0 || (extra == nullptr && extraLength > 0)) {
        assignError(error, "invalid_endpoint_descriptor_input");
        return false;
    }
    EndpointControls parsed;
    int offset = 0;
    while (offset < extraLength) {
        int descriptorLength = 0;
        if (!validateDescriptorBounds(
                extra,
                extraLength,
                offset,
                &descriptorLength,
                error
            )) {
            return false;
        }
        const uint8_t* descriptor = extra + offset;
        if (descriptor[1] == kClassSpecificEndpoint) {
            if (descriptorLength < 3) {
                assignError(error, "class_endpoint_descriptor_too_short");
                return false;
            }
            if (descriptor[2] == kEndpointGeneralSubtype) {
                if (descriptorLength < 7) {
                    assignError(error, "endpoint_general_descriptor_too_short");
                    return false;
                }
                parsed.hasGeneralDescriptor = true;
                parsed.samplingFrequencyControl =
                    (descriptor[3] & kSamplingFrequencyControlMask) != 0;
            }
        }
        offset += descriptorLength;
    }
    *output = parsed;
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

const char* syncTypeName(uint8_t endpointAttributes) {
    switch (endpointAttributes & kIsoSyncTypeMask) {
        case 0x04:
            return "asynchronous";
        case 0x08:
            return "adaptive";
        case 0x0C:
            return "synchronous";
        default:
            return "none";
    }
}

bool requiresFeedbackScheduler(uint8_t endpointAttributes) {
    return (endpointAttributes & kIsoSyncTypeMask) == kIsoAsyncSyncType ||
        (endpointAttributes & kIsoUsageTypeMask) == kIsoImplicitUsageType;
}

} // namespace neri::usb::uac1
