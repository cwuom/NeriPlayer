#include "usb/uac1/usb_uac1_format.h"

#include <cassert>
#include <cstdint>
#include <string>

namespace {

void verifiesDiscretePcmFormat() {
    constexpr uint8_t descriptors[] = {
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        14, 0x24, 0x02, 0x01, 2, 2, 16, 2,
        0x44, 0xAC, 0x00,
        0x80, 0xBB, 0x00
    };
    neri::usb::uac1::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac1::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    assert(error.empty());
    assert(format.isPcm());
    assert(format.channels == 2);
    assert(format.subslotBytes == 2);
    assert(format.bitsPerSample == 16);
    assert(format.supportsSampleRate(44100));
    assert(format.supportsSampleRate(48000));
    assert(!format.supportsSampleRate(96000));
    assert(format.sampleRateSummary() == "44100,48000");

    const neri::usb::uac1::FormatTarget exactTarget { 48000, 2, 2, 16 };
    assert(neri::usb::uac1::matchesTarget(format, exactTarget, &error));
    const neri::usb::uac1::FormatTarget wrongDepth { 48000, 2, 3, 24 };
    assert(!neri::usb::uac1::matchesTarget(format, wrongDepth, &error));
    assert(error == "subslot_mismatch_2");
}

void verifiesContinuousRatesAndEndpointControl() {
    constexpr uint8_t descriptors[] = {
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        14, 0x24, 0x02, 0x01, 2, 3, 24, 0,
        0x44, 0xAC, 0x00,
        0x00, 0x77, 0x01
    };
    neri::usb::uac1::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac1::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    assert(format.supportsSampleRate(48000));
    assert(format.supportsSampleRate(96000));
    assert(!format.supportsSampleRate(192000));
    assert(format.sampleRateSummary() == "44100..96000");

    constexpr uint8_t endpointDescriptor[] = { 7, 0x25, 0x01, 0x01, 0, 0, 0 };
    neri::usb::uac1::EndpointControls controls;
    assert(neri::usb::uac1::parseEndpointControls(
        endpointDescriptor,
        sizeof(endpointDescriptor),
        &controls,
        &error
    ));
    assert(controls.hasGeneralDescriptor);
    assert(controls.samplingFrequencyControl);
}

void verifiesUac1Padded24BitContainerFormat() {
    constexpr uint8_t descriptors[] = {
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        11, 0x24, 0x02, 0x01, 2, 4, 24, 1,
        0x80, 0xBB, 0x00
    };
    neri::usb::uac1::TypeIFormat format;
    std::string error;
    assert(neri::usb::uac1::parseTypeIFormat(
        descriptors,
        sizeof(descriptors),
        &format,
        &error
    ));
    const neri::usb::uac1::FormatTarget exactTarget { 48000, 2, 4, 24 };
    assert(neri::usb::uac1::matchesTarget(format, exactTarget, &error));
}

void rejectsMalformedDescriptors() {
    constexpr uint8_t truncated[] = {
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,
        14, 0x24, 0x02, 0x01, 2, 2, 16, 2,
        0x80, 0xBB
    };
    neri::usb::uac1::TypeIFormat format;
    std::string error;
    assert(!neri::usb::uac1::parseTypeIFormat(
        truncated,
        sizeof(truncated),
        &format,
        &error
    ));
    assert(error == "descriptor_body_truncated");

    constexpr uint8_t zeroLength[] = { 0, 0x24 };
    assert(!neri::usb::uac1::parseTypeIFormat(
        zeroLength,
        sizeof(zeroLength),
        &format,
        &error
    ));
    assert(error == "descriptor_length_invalid");
}

void rejectsFormatsThatNeedFeedbackScheduling() {
    constexpr uint8_t isochronousAdaptive = 0x09;
    constexpr uint8_t isochronousAsynchronous = 0x05;
    constexpr uint8_t isochronousSynchronous = 0x0D;
    constexpr uint8_t isochronousImplicitFeedback = 0x21;
    assert(std::string(neri::usb::uac1::syncTypeName(isochronousAdaptive)) == "adaptive");
    assert(std::string(neri::usb::uac1::syncTypeName(isochronousAsynchronous)) == "asynchronous");
    assert(std::string(neri::usb::uac1::syncTypeName(isochronousSynchronous)) == "synchronous");
    assert(!neri::usb::uac1::requiresFeedbackScheduler(isochronousAdaptive));
    assert(!neri::usb::uac1::requiresFeedbackScheduler(isochronousSynchronous));
    assert(neri::usb::uac1::requiresFeedbackScheduler(isochronousAsynchronous));
    assert(neri::usb::uac1::requiresFeedbackScheduler(isochronousImplicitFeedback));
}

} // namespace

int main() {
    verifiesDiscretePcmFormat();
    verifiesContinuousRatesAndEndpointControl();
    verifiesUac1Padded24BitContainerFormat();
    rejectsMalformedDescriptors();
    rejectsFormatsThatNeedFeedbackScheduling();
    return 0;
}
