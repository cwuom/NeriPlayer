#include "usb/uac2/usb_uac2_format.h"
#include "usb/control/usb_feature_unit.h"
#include "usb/control/usb_sample_rate_readback.h"

#include <cassert>
#include <cmath>
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
    constexpr uint8_t isochronousSynchronous = 0x0D;
    constexpr uint8_t isochronousImplicitFeedback = 0x21;
    assert(std::string(neri::usb::uac2::syncTypeName(isochronousAdaptive)) == "adaptive");
    assert(std::string(neri::usb::uac2::syncTypeName(isochronousAsynchronous)) == "asynchronous");
    assert(std::string(neri::usb::uac2::syncTypeName(isochronousSynchronous)) == "synchronous");
    assert(!neri::usb::uac2::requiresFeedbackScheduler(isochronousAdaptive));
    assert(!neri::usb::uac2::requiresFeedbackScheduler(isochronousSynchronous));
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

bool hasControl(
    const std::vector<neri::usb::control::FeatureUnitControl>& controls,
    int unitId,
    int channel,
    uint8_t selector
) {
    for (const auto& control : controls) {
        if (control.unitId == unitId && control.channel == channel && control.selector == selector) return true;
    }
    return false;
}

void findsPlaybackFeatureUnitsButLeavesTheMicrophonePathAlone() {
    constexpr uint8_t uac2Descriptors[] = {
        9, 0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00,
        8, 0x24, 0x0A, 0x05, 0x03, 0x07, 0x00, 0x00,
        17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x05, 2, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0,
        18, 0x24, 0x06, 0x02, 0x01, 0x0F, 0, 0, 0, 0x0C, 0, 0, 0, 0x04, 0, 0, 0, 0,
        12, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x02, 0x05, 0x00, 0x00, 0,
        17, 0x24, 0x02, 0x04, 0x01, 0x02, 0x00, 0x05, 1, 0x00, 0, 0, 0, 0, 0x00, 0x00, 0,
        14, 0x24, 0x06, 0x05, 0x04, 0x0F, 0, 0, 0, 0x0F, 0, 0, 0, 0,
        12, 0x24, 0x03, 0x06, 0x01, 0x01, 0x00, 0x05, 0x05, 0x00, 0x00, 0
    };
    const auto uac2 = neri::usb::control::findPlaybackFeatureUnitControls(uac2Descriptors, sizeof(uac2Descriptors), 2);
    assert(uac2.size() == 3);
    assert(hasControl(uac2, 2, 0, neri::usb::control::kFeatureUnitMuteSelector));
    assert(hasControl(uac2, 2, 0, neri::usb::control::kFeatureUnitVolumeSelector));
    assert(hasControl(uac2, 2, 1, neri::usb::control::kFeatureUnitVolumeSelector));
    assert(!hasControl(uac2, 2, 2, neri::usb::control::kFeatureUnitVolumeSelector));
    assert(!hasControl(uac2, 5, 0, neri::usb::control::kFeatureUnitMuteSelector));

    constexpr uint8_t uac1Descriptors[] = {
        12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 2, 0x03, 0x00, 0, 0,
        10, 0x24, 0x06, 0x02, 0x01, 0x01, 0x03, 0x02, 0x02, 0,
        8, 0x24, 0x06, 0x07, 0x02, 0x01, 0x01, 0,
        9, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x07, 0
    };
    const auto uac1 = neri::usb::control::findPlaybackFeatureUnitControls(uac1Descriptors, sizeof(uac1Descriptors), 1);
    assert(uac1.size() == 5);
    assert(hasControl(uac1, 7, 0, neri::usb::control::kFeatureUnitMuteSelector));
    assert(hasControl(uac1, 2, 0, neri::usb::control::kFeatureUnitMuteSelector));
    assert(hasControl(uac1, 2, 2, neri::usb::control::kFeatureUnitVolumeSelector));

    assert(neri::usb::control::findPlaybackFeatureUnitControls(nullptr, 0, 2).empty());
    constexpr uint8_t truncated[] = { 18, 0x24, 0x06, 0x02 };
    assert(neri::usb::control::findPlaybackFeatureUnitControls(truncated, sizeof(truncated), 2).empty());
}

void choosesUnityHardwareVolumeWithinTheDeviceRange() {
    using neri::usb::control::unityVolumeWithinRange;
    assert(unityVolumeWithinRange(-12800, 0) == 0);
    assert(unityVolumeWithinRange(-6400, 3072) == 0);
    assert(unityVolumeWithinRange(-12800, -256) == -256);
    assert(unityVolumeWithinRange(256, 1024) == 256);

    constexpr uint8_t range[] = { 1, 0, 0x00, 0xCE, 0x00, 0x00, 0x80, 0x00 };
    int16_t minimum = 1;
    int16_t maximum = 1;
    assert(neri::usb::control::decodeUac2VolumeRange(range, sizeof(range), &minimum, &maximum));
    assert(minimum == -12800);
    assert(maximum == 0);
    constexpr uint8_t empty[] = { 0, 0, 0x00, 0xCE, 0x00, 0x00, 0x80, 0x00 };
    assert(!neri::usb::control::decodeUac2VolumeRange(empty, sizeof(empty), &minimum, &maximum));
    assert(!neri::usb::control::decodeUac2VolumeRange(range, 4, &minimum, &maximum));

    uint8_t encoded[2] = { 0, 0 };
    neri::usb::control::encodeLittleEndianInt16(-12800, encoded);
    assert(encoded[0] == 0x00 && encoded[1] == 0xCE);
    assert(neri::usb::control::decodeLittleEndianInt16(encoded) == -12800);
}

void mapsVolumeKeysOntoOneHardwareVolumeControl() {
    using neri::usb::control::FeatureUnitControl;
    using neri::usb::control::FeatureUnitVolume;
    using neri::usb::control::hardwareVolumeForFraction;
    using neri::usb::control::kFeatureUnitVolumeSelector;
    using neri::usb::control::selectHardwareVolumeControls;
    const auto volume = [](int unitId, int channel, int16_t minimum, int16_t maximum) {
        return FeatureUnitVolume { FeatureUnitControl { unitId, channel, kFeatureUnitVolumeSelector }, minimum, maximum };
    };

    const auto nearestUnitMaster = selectHardwareVolumeControls({
        volume(7, 0, -12800, 0),
        volume(2, 0, -12800, 0),
        volume(2, 1, -12800, 0),
    });
    assert(nearestUnitMaster.size() == 1);
    assert(nearestUnitMaster[0].control.unitId == 7 && nearestUnitMaster[0].control.channel == 0);

    const auto skipsNarrowRange = selectHardwareVolumeControls({
        volume(7, 0, -256, 0),
        volume(2, 1, -12800, 0),
        volume(2, 2, -12800, 0),
    });
    assert(skipsNarrowRange.size() == 2);
    assert(skipsNarrowRange[0].control.unitId == 2 && skipsNarrowRange[1].control.channel == 2);
    assert(selectHardwareVolumeControls({ volume(2, 0, 0, 3072), volume(2, 1, -1024, 0) }).empty());

    assert(hardwareVolumeForFraction(1.0f, -12800, 0) == 0);
    assert(hardwareVolumeForFraction(1.0f, -12800, 3072) == 0);
    assert(hardwareVolumeForFraction(1.0f, -12800, -256) == -256);
    assert(hardwareVolumeForFraction(0.5f, -12800, 3072) == -3083);
    assert(hardwareVolumeForFraction(0.01f, -12800, 0) == -12800);
    assert(hardwareVolumeForFraction(0.0f, -12800, 0) == -12800);
    assert(hardwareVolumeForFraction(std::nanf(""), -12800, 0) == -12800);
}

void waitsForTheClockBeforeTrustingTheRateReadback() {
    using neri::usb::control::SampleRateReadback;
    using neri::usb::control::classifySampleRateReadback;
    using neri::usb::control::sampleRateSettleDelayMs;
    assert(classifySampleRateReadback(44100, 44100, 0) == SampleRateReadback::Verified);
    assert(classifySampleRateReadback(44100, 48000, 0) == SampleRateReadback::Settle);
    assert(classifySampleRateReadback(44100, 48000, 2) == SampleRateReadback::Settle);
    assert(classifySampleRateReadback(44100, 48000, 3) == SampleRateReadback::AcceptUnverified);
    assert(classifySampleRateReadback(96000, 96000, 3) == SampleRateReadback::Verified);
    assert(classifySampleRateReadback(44100, 0, 0) == SampleRateReadback::AcceptUnverified);
    assert(sampleRateSettleDelayMs(0) == 5);
    assert(sampleRateSettleDelayMs(1) == 10);
    assert(sampleRateSettleDelayMs(2) == 20);
    assert(sampleRateSettleDelayMs(9) == 20);
    assert(sampleRateSettleDelayMs(-1) == 5);
}

} // namespace

int main() {
    findsPlaybackFeatureUnitsButLeavesTheMicrophonePathAlone();
    choosesUnityHardwareVolumeWithinTheDeviceRange();
    mapsVolumeKeysOntoOneHardwareVolumeControl();
    verifiesUac2TypeI24BitPcmFormat();
    verifiesUac2TypeI32BitPcmFormat();
    verifiesUac2TypeI24BitPaddedContainerFormat();
    rejectsMalformedUac2StreamingDescriptors();
    verifiesEndpointAndClockControls();
    verifiesTerminalClockSourceMapping();
    rejectsFormatsThatNeedFeedbackScheduling();
    verifiesSampleRateRanges();
    verifiesCurrentSampleRateDecoding();
    waitsForTheClockBeforeTrustingTheRateReadback();
    return 0;
}
