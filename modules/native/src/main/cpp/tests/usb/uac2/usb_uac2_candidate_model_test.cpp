#include "usb/uac2/usb_uac2_candidate_model.h"

#include <cassert>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace {

using neri::usb::uac2::AudioFunctionClockGraph;
using neri::usb::uac2::CandidateDecision;
using neri::usb::uac2::CandidateRejectionCode;
using neri::usb::uac2::ClockEntity;
using neri::usb::uac2::ClockEntityKind;
using neri::usb::uac2::ConfigurationSnapshot;
using neri::usb::uac2::ControlCapability;
using neri::usb::uac2::DeviceDescriptorIdentity;
using neri::usb::uac2::EndpointSnapshot;
using neri::usb::uac2::EndpointCapacitySource;
using neri::usb::uac2::FeedbackResolverPolicy;
using neri::usb::uac2::FormatTarget;
using neri::usb::uac2::TypeIFormat;
using neri::usb::uac2::Uac2CandidateInput;
using neri::usb::uac2::Uac2CandidateRequest;

constexpr uint8_t kAsyncData = 0x05;
constexpr uint8_t kAdaptiveData = 0x09;
constexpr uint8_t kSynchronousData = 0x0D;
constexpr uint8_t kFeedback = 0x11;

EndpointSnapshot endpoint(
    int interfaceNumber,
    int alternateSetting,
    uint8_t address,
    uint8_t attributes,
    int capacity
) {
    EndpointSnapshot value;
    value.configurationValue = 7;
    value.interfaceNumber = interfaceNumber;
    value.alternateSetting = alternateSetting;
    value.endpointAddress = address;
    value.descriptorLength = 7;
    value.descriptorType = 0x05;
    value.bmAttributes = attributes;
    value.rawMaxPacketSize = static_cast<uint16_t>(capacity);
    value.effectiveMaxPacketBytes = capacity;
    value.effectiveCapacityKnown = true;
    value.capacitySource = EndpointCapacitySource::StandardDescriptor;
    value.bInterval = 2;
    return value;
}

AudioFunctionClockGraph clockFunction() {
    ClockEntity terminal;
    terminal.id = 37;
    terminal.kind = ClockEntityKind::Terminal;
    terminal.sourceIds = { 41 };

    ClockEntity source;
    source.id = 41;
    source.kind = ClockEntityKind::ClockSource;
    source.validityControlAdvertised = true;
    source.validityState = neri::usb::uac2::ClockValidityState::Valid;

    return AudioFunctionClockGraph { 5, { terminal, source } };
}

Uac2CandidateInput baseInput(uint8_t syncAttributes) {
    Uac2CandidateInput input;
    input.identity = DeviceDescriptorIdentity {
        0xFFFE,
        0xF1C7,
        0x0102,
        "synthetic-topology-alpha"
    };
    input.configurationValue = 7;
    input.audioControlInterface = 5;
    input.audioControlAlternateSetting = 0;
    input.streamingInterface = 9;
    input.streamingAlternateSetting = 5;
    input.interfaceProtocol = neri::usb::uac2::kUac2InterfaceProtocol;
    input.busSpeed = neri::usb::uac2::UsbBusSpeed::High;
    input.audioControlBindsStreaming = true;
    input.sampleRateExact = true;
    input.sampleRateControl = ControlCapability::ReadWrite;
    input.format = TypeIFormat {
        37,
        1,
        neri::usb::uac2::kPcmFormatBit,
        2,
        4,
        32
    };
    input.output = endpoint(9, 5, 0x06, syncAttributes, 512);
    input.capacityEvidence = { true, 512 };
    input.clockFunctions = { clockFunction() };
    return input;
}

Uac2CandidateRequest requestWithTiming() {
    Uac2CandidateRequest request;
    request.sampleRate = 64000;
    request.formatTarget = FormatTarget { 2, 4, 32 };
    return request;
}

void addExplicitFeedback(Uac2CandidateInput* input, int interfaceNumber = 9, int alt = 5) {
    EndpointSnapshot feedback = endpoint(interfaceNumber, alt, 0x86, kFeedback, 4);
    feedback.bInterval = 3;
    input->configuration = ConfigurationSnapshot { 7, { input->output, feedback } };
    input->feedbackTiming = neri::usb::uac2::FeedbackTimingEvidence {
        true,
        input->output.endpointAddress,
        feedback.endpointAddress,
        input->output.bInterval,
        feedback.bInterval,
        4,
        500'000,
        "fixture-hs-explicit"
    };
}

void buildsCompleteListAndKeepsAsyncFeatureOff() {
    Uac2CandidateInput async = baseInput(kAsyncData);
    addExplicitFeedback(&async);
    Uac2CandidateInput adaptive = baseInput(kAdaptiveData);
    adaptive.streamingAlternateSetting = 4;
    adaptive.output.alternateSetting = 4;
    adaptive.configuration = ConfigurationSnapshot { 7, { adaptive.output } };

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        { async, adaptive },
        requestWithTiming()
    );
    assert(result.candidates.size() == 2U);
    assert(result.candidates[0].decision == CandidateDecision::FeatureDisabled);
    assert(result.candidates[0].hardEligible);
    assert(result.candidates[0].reason ==
        "async_explicit_candidate_validated_feature_disabled");
    assert(result.candidates[0].claimPlan.size() == 2U);
    assert(result.candidates[1].decision == CandidateDecision::Eligible);
    assert(result.bestCandidateIndex == 1);
    assert(result.candidates[0].candidateId.find("fffe:f1c7:0102:fp=") !=
        std::string::npos);
}

void rejectsMissingFeedbackAndKeepsOtherCandidates() {
    Uac2CandidateInput missing = baseInput(kAsyncData);
    missing.configuration = ConfigurationSnapshot { 7, { missing.output } };
    Uac2CandidateInput valid = baseInput(kSynchronousData);
    valid.streamingAlternateSetting = 4;
    valid.output.alternateSetting = 4;
    valid.configuration = ConfigurationSnapshot { 7, { valid.output } };

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        { missing, valid },
        requestWithTiming()
    );
    assert(result.candidates[0].decision == CandidateDecision::Rejected);
    assert(result.candidates[0].rejection == CandidateRejectionCode::FeedbackMissing);
    assert(result.candidates[0].reason == "uac2_feedback_endpoint_missing");
    assert(result.candidates[1].decision == CandidateDecision::Eligible);
    assert(result.bestCandidateIndex == 1);
}

void rejectsTimingCapacityAndFormatErrors() {
    Uac2CandidateInput timing = baseInput(kAsyncData);
    addExplicitFeedback(&timing);
    timing.feedbackTiming.verified = false;
    auto result = neri::usb::uac2::buildUac2CandidateList(
        { timing },
        requestWithTiming()
    );
    assert(result.candidates[0].rejection == CandidateRejectionCode::TimingProfileUnknown);

    Uac2CandidateInput capacity = baseInput(kSynchronousData);
    capacity.configuration = ConfigurationSnapshot { 7, { capacity.output } };
    capacity.capacityEvidence.maximumRequiredPacketBytes = 513;
    result = neri::usb::uac2::buildUac2CandidateList(
        { capacity },
        requestWithTiming()
    );
    assert(result.candidates[0].rejection == CandidateRejectionCode::CapacityInsufficient);

    Uac2CandidateInput format = baseInput(kSynchronousData);
    format.configuration = ConfigurationSnapshot { 7, { format.output } };
    format.format.bitsPerSample = 24;
    result = neri::usb::uac2::buildUac2CandidateList(
        { format },
        requestWithTiming()
    );
    assert(result.candidates[0].rejection == CandidateRejectionCode::FormatMismatch);
}

void rejectsClockAmbiguityAndDuplicateIdentity() {
    Uac2CandidateInput first = baseInput(kSynchronousData);
    first.configuration = ConfigurationSnapshot { 7, { first.output } };
    Uac2CandidateInput second = first;
    auto result = neri::usb::uac2::buildUac2CandidateList(
        { first, second },
        requestWithTiming()
    );
    assert(result.candidates[0].rejection == CandidateRejectionCode::DuplicateIdentity);
    assert(result.candidates[1].rejection == CandidateRejectionCode::DuplicateIdentity);
    assert(result.bestCandidateIndex == -1);

    Uac2CandidateInput ambiguous = baseInput(kSynchronousData);
    ambiguous.configuration = ConfigurationSnapshot { 7, { ambiguous.output } };
    auto function = clockFunction();
    ClockEntity duplicate = function.entities.front();
    function.entities.push_back(duplicate);
    ambiguous.clockFunctions = { function };
    result = neri::usb::uac2::buildUac2CandidateList(
        { ambiguous },
        requestWithTiming()
    );
    assert(result.candidates[0].rejection == CandidateRejectionCode::ClockGraphInvalid);
}

void rejectsAdvertisedButUncheckedClockValidity() {
    Uac2CandidateInput input = baseInput(kSynchronousData);
    input.configuration = ConfigurationSnapshot { 7, { input.output } };
    input.clockFunctions.front().entities.back().validityState =
        neri::usb::uac2::ClockValidityState::Unchecked;

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        { input },
        requestWithTiming()
    );
    assert(result.candidates[0].decision == CandidateDecision::Rejected);
    assert(result.candidates[0].rejection == CandidateRejectionCode::ClockGraphInvalid);
    assert(result.candidates[0].reason == "clock_source_validity_unverified");
}

void rejectsPacketSizeInvalidForBusSpeed() {
    Uac2CandidateInput input = baseInput(kSynchronousData);
    input.busSpeed = neri::usb::uac2::UsbBusSpeed::Full;
    input.output.rawMaxPacketSize = 1024;
    input.output.effectiveMaxPacketBytes = 1024;
    input.capacityEvidence.maximumRequiredPacketBytes = 1024;
    input.configuration = ConfigurationSnapshot { 7, { input.output } };

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        { input },
        requestWithTiming()
    );
    assert(result.candidates[0].decision == CandidateDecision::Rejected);
    assert(result.candidates[0].rejection == CandidateRejectionCode::OutputEndpointInvalid);
    assert(result.candidates[0].reason == "output_packet_size_invalid_for_speed");
}

void rejectsSameInterfaceAlternateConflict() {
    Uac2CandidateInput input = baseInput(kAsyncData);
    addExplicitFeedback(&input, 9, 6);
    FeedbackResolverPolicy policy = requestWithTiming().feedbackPolicy;
    policy.allowCrossAlternateAssociation = true;
    Uac2CandidateRequest request = requestWithTiming();
    request.feedbackPolicy = policy;

    const auto result = neri::usb::uac2::buildUac2CandidateList({ input }, request);
    assert(result.candidates[0].rejection ==
        CandidateRejectionCode::InterfaceAlternateConflict);
    assert(result.candidates[0].reason == "feedback_same_interface_alt_conflict");
}

void rejectsCrossInterfaceWithoutAssociationEvidence() {
    Uac2CandidateInput input = baseInput(kAsyncData);
    addExplicitFeedback(&input, 10, 0);
    FeedbackResolverPolicy policy = requestWithTiming().feedbackPolicy;
    policy.allowCrossInterfaceAssociation = false;
    Uac2CandidateRequest request = requestWithTiming();
    request.feedbackPolicy = policy;

    const auto result = neri::usb::uac2::buildUac2CandidateList({ input }, request);
    assert(result.candidates[0].rejection ==
        CandidateRejectionCode::FeedbackAssociationUnverified);
}

void rejectsFeedbackOnAudioControlInterface() {
    Uac2CandidateInput input = baseInput(kAsyncData);
    addExplicitFeedback(&input, 5, 0);
    Uac2CandidateRequest request = requestWithTiming();
    request.feedbackPolicy.allowCrossInterfaceAssociation = true;
    request.feedbackPolicy.allowCrossAlternateAssociation = true;

    const auto result = neri::usb::uac2::buildUac2CandidateList({ input }, request);
    assert(result.candidates[0].rejection ==
        CandidateRejectionCode::InterfaceAlternateConflict);
    assert(result.candidates[0].reason ==
        "feedback_audio_control_interface_conflict");
}

void requiresFunctionBindingForCrossInterfaceFeedback() {
    Uac2CandidateInput input = baseInput(kAsyncData);
    addExplicitFeedback(&input, 10, 0);
    Uac2CandidateRequest request = requestWithTiming();
    request.feedbackPolicy.allowCrossInterfaceAssociation = true;
    request.feedbackPolicy.allowCrossAlternateAssociation = true;

    auto result = neri::usb::uac2::buildUac2CandidateList({ input }, request);
    assert(result.candidates[0].rejection == CandidateRejectionCode::ClaimPlanInvalid);
    assert(result.candidates[0].reason ==
        "feedback_audio_function_binding_unverified");

    input.feedbackInterfaceBoundToAudioFunction = true;
    result = neri::usb::uac2::buildUac2CandidateList({ input }, request);
    assert(result.candidates[0].decision == CandidateDecision::FeatureDisabled);
    assert(result.candidates[0].claimPlan.size() == 3U);
}

void exposesStableNames() {
    assert(std::string(neri::usb::uac2::candidateDecisionName(
        CandidateDecision::FeatureDisabled
    )) == "feature_disabled");
    assert(std::string(neri::usb::uac2::candidateRejectionCodeName(
        CandidateRejectionCode::CapacityInsufficient
    )) == "capacity_insufficient");
    assert(std::string(neri::usb::uac2::candidateSyncModeName(
        neri::usb::uac2::CandidateSyncMode::Adaptive
    )) == "adaptive");
}

void deterministicMalformedInputsFailClosed() {
    Uac2CandidateInput valid = baseInput(kSynchronousData);
    valid.configuration = ConfigurationSnapshot { 7, { valid.output } };

    std::vector<Uac2CandidateInput> malformed;

    Uac2CandidateInput value = valid;
    value.interfaceProtocol = 0;
    malformed.push_back(value);

    value = valid;
    value.audioControlBindsStreaming = false;
    malformed.push_back(value);

    value = valid;
    value.output.endpointAddress = 0;
    value.configuration.endpoints.front() = value.output;
    malformed.push_back(value);

    value = valid;
    value.output.descriptorType = 0x24;
    value.configuration.endpoints.front() = value.output;
    malformed.push_back(value);

    value = valid;
    value.output.rawMaxPacketSize = 0;
    value.configuration.endpoints.front() = value.output;
    malformed.push_back(value);

    value = valid;
    value.output.capacitySource = EndpointCapacitySource::Unknown;
    value.configuration.endpoints.front() = value.output;
    malformed.push_back(value);

    value = valid;
    value.configuration.configurationValue = 8;
    malformed.push_back(value);

    value = valid;
    value.configuration.endpoints.push_back(value.output);
    malformed.push_back(value);

    for (size_t index = 0; index < malformed.size(); ++index) {
        malformed[index].streamingAlternateSetting += static_cast<int>(index) + 10;
        malformed[index].output.alternateSetting =
            malformed[index].streamingAlternateSetting;
        for (EndpointSnapshot& candidateEndpoint : malformed[index].configuration.endpoints) {
            candidateEndpoint.alternateSetting = malformed[index].streamingAlternateSetting;
        }
    }

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        malformed,
        requestWithTiming()
    );
    assert(result.candidates.size() == malformed.size());
    assert(result.bestCandidateIndex == -1);
    for (const auto& candidate : result.candidates) {
        assert(candidate.decision == CandidateDecision::Rejected);
        assert(!candidate.hardEligible);
        assert(candidate.rejection != CandidateRejectionCode::None);
    }
}

void preservesZeroValuedDescriptorIdentityFields() {
    Uac2CandidateInput input = baseInput(kSynchronousData);
    input.identity.productId = 0;
    input.identity.bcdDevice = 0;
    input.configuration = ConfigurationSnapshot { 7, { input.output } };

    const auto result = neri::usb::uac2::buildUac2CandidateList(
        { input },
        requestWithTiming()
    );
    assert(result.candidates[0].decision == CandidateDecision::Eligible);
    assert(result.candidates[0].candidateId.find("fffe:0000:0000:fp=") !=
        std::string::npos);
}

} // namespace

int main() {
    buildsCompleteListAndKeepsAsyncFeatureOff();
    rejectsMissingFeedbackAndKeepsOtherCandidates();
    rejectsTimingCapacityAndFormatErrors();
    rejectsClockAmbiguityAndDuplicateIdentity();
    rejectsAdvertisedButUncheckedClockValidity();
    rejectsPacketSizeInvalidForBusSpeed();
    rejectsSameInterfaceAlternateConflict();
    rejectsCrossInterfaceWithoutAssociationEvidence();
    rejectsFeedbackOnAudioControlInterface();
    requiresFunctionBindingForCrossInterfaceFeedback();
    exposesStableNames();
    deterministicMalformedInputsFailClosed();
    preservesZeroValuedDescriptorIdentityFields();
    return 0;
}
