#include "usb_uac2_format.h"

#include <algorithm>
#include <utility>

namespace neri::usb::uac2 {
namespace {

constexpr uint8_t kClassSpecificInterface = 0x24;
constexpr uint8_t kClassSpecificEndpoint = 0x25;
constexpr uint8_t kAsGeneralSubtype = 0x01;
constexpr uint8_t kFormatTypeSubtype = 0x02;
constexpr uint8_t kEndpointGeneralSubtype = 0x01;
constexpr uint8_t kInputTerminalSubtype = 0x02;
constexpr uint8_t kOutputTerminalSubtype = 0x03;
constexpr uint8_t kClockSourceSubtype = 0x0A;
constexpr uint8_t kFormatTypeI = 0x01;
constexpr uint8_t kControlCapabilityMask = 0x03;
constexpr uint8_t kIsoSyncTypeMask = 0x0C;
constexpr uint8_t kIsoAsyncSyncType = 0x04;
constexpr uint8_t kIsoUsageTypeMask = 0x30;
constexpr uint8_t kIsoImplicitUsageType = 0x20;

uint32_t readLe32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8) |
        (static_cast<uint32_t>(data[2]) << 16) |
        (static_cast<uint32_t>(data[3]) << 24);
}

int readLe32Signed(const uint8_t* data) {
    return static_cast<int>(readLe32(data));
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

bool parseAsGeneralDescriptor(
    const uint8_t* descriptor,
    int length,
    TypeIFormat* output,
    std::string* error
) {
    if (length < 16) {
        assignError(error, "as_general_descriptor_too_short");
        return false;
    }
    output->terminalLink = descriptor[3];
    output->formatType = descriptor[5];
    output->formats = readLe32(descriptor + 6);
    output->channels = descriptor[10];
    if (output->terminalLink <= 0 || output->channels <= 0) {
        assignError(error, "as_general_fields_invalid");
        return false;
    }
    return true;
}

bool parseFormatDescriptor(
    const uint8_t* descriptor,
    int length,
    TypeIFormat* output,
    std::string* error
) {
    if (length < 6) {
        assignError(error, "format_type_descriptor_too_short");
        return false;
    }
    if (descriptor[3] != kFormatTypeI) {
        assignError(error, "format_type_not_type_i");
        return false;
    }
    output->subslotBytes = descriptor[4];
    output->bitsPerSample = descriptor[5];
    if (output->subslotBytes <= 0 || output->bitsPerSample <= 0 ||
        output->bitsPerSample > output->subslotBytes * 8) {
        assignError(error, "format_type_fields_invalid");
        return false;
    }
    return true;
}

ControlCapability parseControlCapability(uint8_t value) {
    switch (value & kControlCapabilityMask) {
        case 0x01:
            return ControlCapability::ReadOnly;
        case 0x03:
            return ControlCapability::ReadWrite;
        default:
            return ControlCapability::None;
    }
}

} // namespace

bool TypeIFormat::isPcm() const {
    return (formats & kPcmFormatBit) != 0;
}

std::string TypeIFormat::formatSummary() const {
    return "formats=0x" + std::to_string(formats);
}

ControlCapability ClockSource::samplingFrequencyControl() const {
    return parseControlCapability(controls);
}

bool SampleRateSubrange::supports(int sampleRate) const {
    if (sampleRate < minimum || sampleRate > maximum || sampleRate <= 0) {
        return false;
    }
    if (resolution <= 0) {
        return minimum == maximum && sampleRate == minimum;
    }
    return ((sampleRate - minimum) % resolution) == 0;
}

bool matchesTarget(
    const TypeIFormat& format,
    const FormatTarget& target,
    std::string* rejectionReason
) {
    std::string reason;
    if (!format.isPcm()) {
        reason = "non_pcm_formats_" + std::to_string(format.formats);
    } else if (format.formatType != kFormatTypeI) {
        reason = "format_type_not_type_i_" + std::to_string(format.formatType);
    } else if (format.channels != target.channels) {
        reason = "channel_mismatch_" + std::to_string(format.channels);
    } else if (format.subslotBytes != target.subslotBytes) {
        reason = "subslot_mismatch_" + std::to_string(format.subslotBytes);
    } else if (format.bitsPerSample != target.bitsPerSample) {
        reason = "bit_depth_mismatch_" + std::to_string(format.bitsPerSample);
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
                if (!parseAsGeneralDescriptor(descriptor, descriptorLength, &parsed, error)) {
                    return false;
                }
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
                if (descriptorLength < 8) {
                    assignError(error, "endpoint_general_descriptor_too_short");
                    return false;
                }
                parsed.hasGeneralDescriptor = true;
                parsed.attributes = descriptor[3];
                parsed.controls = descriptor[4];
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

bool parseClockSourceDescriptor(
    const uint8_t* descriptor,
    int descriptorLength,
    ClockSource* output,
    std::string* error
) {
    if (descriptor == nullptr || output == nullptr) {
        assignError(error, "invalid_clock_source_input");
        return false;
    }
    if (descriptorLength < 8) {
        assignError(error, "clock_source_descriptor_too_short");
        return false;
    }
    if (descriptor[1] != kClassSpecificInterface || descriptor[2] != kClockSourceSubtype) {
        assignError(error, "descriptor_not_clock_source");
        return false;
    }
    ClockSource parsed;
    parsed.id = descriptor[3];
    parsed.attributes = descriptor[4];
    parsed.controls = descriptor[5];
    if (parsed.id <= 0) {
        assignError(error, "clock_source_id_invalid");
        return false;
    }
    *output = parsed;
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool parseTerminalClockSourceDescriptor(
    const uint8_t* descriptor,
    int descriptorLength,
    TerminalClockSource* output,
    std::string* error
) {
    if (descriptor == nullptr || output == nullptr) {
        assignError(error, "invalid_terminal_input");
        return false;
    }
    if (descriptorLength < 4 || descriptor[1] != kClassSpecificInterface) {
        assignError(error, "descriptor_not_audio_control_terminal");
        return false;
    }

    TerminalClockSource parsed;
    if (descriptor[2] == kInputTerminalSubtype) {
        if (descriptorLength < 17) {
            assignError(error, "input_terminal_descriptor_too_short");
            return false;
        }
        parsed.terminalId = descriptor[3];
        parsed.clockSourceId = descriptor[7];
    } else if (descriptor[2] == kOutputTerminalSubtype) {
        if (descriptorLength < 12) {
            assignError(error, "output_terminal_descriptor_too_short");
            return false;
        }
        parsed.terminalId = descriptor[3];
        parsed.clockSourceId = descriptor[8];
    } else {
        assignError(error, "descriptor_not_audio_control_terminal");
        return false;
    }

    if (parsed.terminalId <= 0 || parsed.clockSourceId <= 0) {
        assignError(error, "terminal_clock_source_invalid");
        return false;
    }
    *output = parsed;
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool parseSampleRateRanges(
    const uint8_t* data,
    int dataLength,
    std::vector<SampleRateSubrange>* output,
    std::string* error
) {
    if (output == nullptr || dataLength < 0 || (data == nullptr && dataLength > 0)) {
        assignError(error, "invalid_sample_rate_range_input");
        return false;
    }
    if (dataLength < 2) {
        assignError(error, "sample_rate_range_header_truncated");
        return false;
    }
    const int rangeCount = static_cast<int>(data[0]) | (static_cast<int>(data[1]) << 8);
    if (rangeCount <= 0) {
        assignError(error, "sample_rate_range_empty");
        return false;
    }
    constexpr int kSubrangeBytes = 12;
    const int requiredLength = 2 + rangeCount * kSubrangeBytes;
    if (dataLength < requiredLength) {
        assignError(error, "sample_rate_range_body_truncated");
        return false;
    }

    std::vector<SampleRateSubrange> parsed;
    parsed.reserve(static_cast<size_t>(rangeCount));
    for (int index = 0; index < rangeCount; ++index) {
        const uint8_t* entry = data + 2 + index * kSubrangeBytes;
        SampleRateSubrange range {
            readLe32Signed(entry),
            readLe32Signed(entry + 4),
            readLe32Signed(entry + 8)
        };
        if (range.minimum <= 0 || range.maximum < range.minimum || range.resolution < 0) {
            assignError(error, "sample_rate_range_invalid");
            return false;
        }
        parsed.push_back(range);
    }
    *output = std::move(parsed);
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

const char* controlCapabilityName(ControlCapability capability) {
    switch (capability) {
        case ControlCapability::ReadOnly:
            return "read_only";
        case ControlCapability::ReadWrite:
            return "read_write";
        default:
            return "none";
    }
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

} // namespace neri::usb::uac2
