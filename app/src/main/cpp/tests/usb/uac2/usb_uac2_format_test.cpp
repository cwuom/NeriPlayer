#include "usb/uac2/usb_uac2_format.h"

#include <cassert>
#include <cstdint>
#include <string>
#include <vector>

namespace {

void verifiesUac2TypeI24BitPcmFormat() {
    constexpr uint8_t descriptors[] = {
        16, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00,
        0x00, 0x00, 2, 0x03, 0x00, 0x00, 0x00, 0,
        6, 0x24, 0x02, 0x01, 3, 24
    };
    neri::usb::uac2::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac2::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    assert(error.empty());
    assert(format.isPcm());
    assert(format.terminalLink == 2);
    assert(format.channels == 2);
    assert(format.subslotBytes == 3);
    assert(format.bitsPerSample == 24);

    const neri::usb::uac2::FormatTarget exactTarget { 2, 3, 24 };
    assert(neri::usb::uac2::matchesTarget(format, exactTarget, &error));
    const neri::usb::uac2::FormatTarget padded24Target { 2, 4, 24 };
    assert(!neri::usb::uac2::matchesTarget(format, padded24Target, &error));
    assert(error == "subslot_mismatch_3");
    const neri::usb::uac2::FormatTarget wrongDepth { 2, 3, 32 };
    assert(!neri::usb::uac2::matchesTarget(format, wrongDepth, &error));
    assert(error == "bit_depth_mismatch_24");
}

void verifiesUac2TypeI32BitPcmFormat() {
    constexpr uint8_t descriptors[] = {
        16, 0x24, 0x01, 3, 0, 0x01, 0x01, 0x00,
        0x00, 0x00, 2, 0x03, 0x00, 0x00, 0x00, 0,
        6, 0x24, 0x02, 0x01, 4, 32
    };
    neri::usb::uac2::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac2::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    assert(format.isPcm());
    assert(format.channels == 2);
    assert(format.subslotBytes == 4);
    assert(format.bitsPerSample == 32);

    const neri::usb::uac2::FormatTarget exactTarget { 2, 4, 32 };
    assert(neri::usb::uac2::matchesTarget(format, exactTarget, &error));
}

void verifiesUac2TypeI24BitPaddedContainerFormat() {
    constexpr uint8_t descriptors[] = {
        16, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00,
        0x00, 0x00, 2, 0x03, 0x00, 0x00, 0x00, 0,
        6, 0x24, 0x02, 0x01, 4, 24
    };
    neri::usb::uac2::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac2::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    const neri::usb::uac2::FormatTarget exactTarget { 2, 4, 24 };
    assert(neri::usb::uac2::matchesTarget(format, exactTarget, &error));
}

void rejectsMalformedUac2StreamingDescriptors() {
    constexpr uint8_t truncated[] = {
        16, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00
    };
    neri::usb::uac2::TypeIFormat format;
    std::string error;
    assert(!neri::usb::uac2::parseTypeIFormat(
        truncated,
        sizeof(truncated),
        &format,
        &error
    ));
    assert(error == "descriptor_body_truncated");

    constexpr uint8_t shortGeneral[] = {
        8, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00,
        6, 0x24, 0x02, 0x01, 3, 24
    };
    assert(!neri::usb::uac2::parseTypeIFormat(
        shortGeneral,
        sizeof(shortGeneral),
        &format,
        &error
    ));
    assert(error == "as_general_descriptor_too_short");

    constexpr uint8_t missingFormat[] = {
        16, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00,
        0x00, 0x00, 2, 0x03, 0x00, 0x00, 0x00, 0
    };
    assert(!neri::usb::uac2::parseTypeIFormat(
        missingFormat,
        sizeof(missingFormat),
        &format,
        &error
    ));
    assert(error == "format_type_descriptor_missing");
}

void verifiesEndpointAndClockControls() {
    constexpr uint8_t endpointDescriptor[] = { 8, 0x25, 0x01, 0x00, 0x03, 0, 0, 0 };
    neri::usb::uac2::EndpointControls controls;
    std::string error;
    assert(neri::usb::uac2::parseEndpointControls(
        endpointDescriptor,
        sizeof(endpointDescriptor),
        &controls,
        &error
    ));
    assert(controls.hasGeneralDescriptor);
    assert(controls.controls == 0x03);

    constexpr uint8_t clockSource[] = { 8, 0x24, 0x0A, 4, 3, 0x03, 0, 0 };
    neri::usb::uac2::ClockSource clock;
    assert(neri::usb::uac2::parseClockSourceDescriptor(
        clockSource,
        sizeof(clockSource),
        &clock,
        &error
    ));
    assert(clock.id == 4);
    assert(clock.samplingFrequencyControl() == neri::usb::uac2::ControlCapability::ReadWrite);
    assert(std::string(neri::usb::uac2::controlCapabilityName(
        clock.samplingFrequencyControl()
    )) == "read_write");
}

void verifiesTerminalClockSourceMapping() {
    constexpr uint8_t inputTerminal[] = {
        17, 0x24, 0x02, 2, 0x01, 0x01, 0, 4,
        2, 0x03, 0x00, 0x00, 0x00, 0, 0, 0, 0
    };
    constexpr uint8_t outputTerminal[] = {
        12, 0x24, 0x03, 3, 0x01, 0x03, 0, 2, 4, 0, 0, 0
    };
    neri::usb::uac2::TerminalClockSource terminal;
    std::string error;
    assert(neri::usb::uac2::parseTerminalClockSourceDescriptor(
        inputTerminal,
        sizeof(inputTerminal),
        &terminal,
        &error
    ));
    assert(terminal.terminalId == 2);
    assert(terminal.clockSourceId == 4);
    assert(neri::usb::uac2::parseTerminalClockSourceDescriptor(
        outputTerminal,
        sizeof(outputTerminal),
        &terminal,
        &error
    ));
    assert(terminal.terminalId == 3);
    assert(terminal.clockSourceId == 4);
}

void rejectsFormatsThatNeedFeedbackScheduling() {
    constexpr uint8_t isochronousAdaptive = 0x09;
    constexpr uint8_t isochronousAsynchronous = 0x05;
    constexpr uint8_t isochronousImplicitFeedback = 0x21;
    assert(!neri::usb::uac2::requiresFeedbackScheduler(isochronousAdaptive));
    assert(neri::usb::uac2::requiresFeedbackScheduler(isochronousAsynchronous));
    assert(neri::usb::uac2::requiresFeedbackScheduler(isochronousImplicitFeedback));
}

void verifiesSampleRateRanges() {
    constexpr uint8_t rangeResponse[] = {
        0x03, 0x00,
        0x44, 0xAC, 0x00, 0x00,
        0x44, 0xAC, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x80, 0xBB, 0x00, 0x00,
        0x80, 0xBB, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x44, 0xAC, 0x00, 0x00,
        0x00, 0x77, 0x01, 0x00,
        0x01, 0x00, 0x00, 0x00
    };
    std::vector<neri::usb::uac2::SampleRateSubrange> ranges;
    std::string error;
    assert(neri::usb::uac2::parseSampleRateRanges(
        rangeResponse,
        sizeof(rangeResponse),
        &ranges,
        &error
    ));
    assert(ranges.size() == 3);
    assert(ranges[0].supports(44100));
    assert(ranges[1].supports(48000));
    assert(ranges[2].supports(96000));
    assert(!ranges[0].supports(48000));
}

void verifiesCurrentSampleRateDecoding() {
    constexpr uint8_t rate48000[] = { 0x80, 0xBB, 0x00, 0x00 };
    constexpr uint8_t zeroRate[] = { 0x00, 0x00, 0x00, 0x00 };
    constexpr uint8_t signedOverflow[] = { 0x00, 0x00, 0x00, 0x80 };
    int sampleRate = 0;
    std::string error;

    assert(neri::usb::uac2::decodeCurrentSampleRate(
        rate48000,
        sizeof(rate48000),
        &sampleRate,
        &error
    ));
    assert(sampleRate == 48000);
    assert(!neri::usb::uac2::decodeCurrentSampleRate(
        zeroRate,
        sizeof(zeroRate),
        &sampleRate,
        &error
    ));
    assert(error == "current_sample_rate_out_of_range");
    assert(!neri::usb::uac2::decodeCurrentSampleRate(
        signedOverflow,
        sizeof(signedOverflow),
        &sampleRate,
        &error
    ));
    assert(error == "current_sample_rate_out_of_range");
    assert(!neri::usb::uac2::decodeCurrentSampleRate(
        rate48000,
        3,
        &sampleRate,
        &error
    ));
    assert(error == "invalid_current_sample_rate_input");
}

} // namespace

int main() {
    verifiesUac2TypeI24BitPcmFormat();
    verifiesUac2TypeI32BitPcmFormat();
    verifiesUac2TypeI24BitPaddedContainerFormat();
    rejectsMalformedUac2StreamingDescriptors();
    verifiesEndpointAndClockControls();
    verifiesTerminalClockSourceMapping();
    rejectsFormatsThatNeedFeedbackScheduling();
    verifiesSampleRateRanges();
    verifiesCurrentSampleRateDecoding();
    return 0;
}
