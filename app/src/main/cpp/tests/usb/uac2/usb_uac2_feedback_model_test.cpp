#include "usb/uac2/usb_uac2_feedback_model.h"

#include <cassert>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace {

using neri::usb::uac2::ConfigurationSnapshot;
using neri::usb::uac2::DescriptorValidationStatus;
using neri::usb::uac2::EndpointCapacitySource;
using neri::usb::uac2::EndpointSnapshot;
using neri::usb::uac2::FeedbackAssociationKind;
using neri::usb::uac2::FeedbackMode;
using neri::usb::uac2::FeedbackResolverPolicy;

constexpr uint8_t kIsoDataAsync = 0x05;
constexpr uint8_t kIsoDataAdaptive = 0x09;
constexpr uint8_t kIsoFeedback = 0x11;
constexpr uint8_t kIsoImplicitAsync = 0x25;

EndpointSnapshot makeEndpoint(
    int interfaceNumber,
    int alternateSetting,
    uint8_t endpointAddress,
    uint8_t attributes,
    int maxPacketBytes
) {
    EndpointSnapshot endpoint;
    endpoint.configurationValue = 7;
    endpoint.interfaceNumber = interfaceNumber;
    endpoint.alternateSetting = alternateSetting;
    endpoint.endpointAddress = endpointAddress;
    endpoint.descriptorLength = 7;
    endpoint.descriptorType = 0x05;
    endpoint.bmAttributes = attributes;
    endpoint.rawMaxPacketSize = static_cast<uint16_t>(maxPacketBytes);
    endpoint.effectiveMaxPacketBytes = maxPacketBytes;
    endpoint.effectiveCapacityKnown = true;
    endpoint.capacitySource = EndpointCapacitySource::StandardDescriptor;
    endpoint.bInterval = 2;
    return endpoint;
}

EndpointSnapshot makeOutput() {
    return makeEndpoint(9, 5, 0x06, kIsoDataAsync, 512);
}

EndpointSnapshot makeStandardFeedback() {
    EndpointSnapshot feedback = makeEndpoint(9, 5, 0x86, kIsoFeedback, 4);
    feedback.bInterval = 3;
    return feedback;
}

EndpointSnapshot makeNonStandardFeedback() {
    EndpointSnapshot feedback = makeEndpoint(9, 5, 0x87, kIsoFeedback, 4);
    feedback.bInterval = 3;
    return feedback;
}

ConfigurationSnapshot configurationOf(std::vector<EndpointSnapshot> endpoints) {
    return ConfigurationSnapshot { 7, std::move(endpoints) };
}

FeedbackResolverPolicy verifiedTimingPolicy(
    uint8_t outputAddress = 0x06,
    uint8_t feedbackAddress = 0x86,
    std::string profileId = "fixture-hs"
) {
    FeedbackResolverPolicy policy;
    policy.timing = neri::usb::uac2::FeedbackTimingEvidence {
        true,
        outputAddress,
        feedbackAddress,
        2,
        3,
        4,
        500'000,
        std::move(profileId)
    };
    return policy;
}

void resolvesStandardEndpointNumberAndRequiresTiming() {
    const EndpointSnapshot output = makeOutput();
    const EndpointSnapshot feedback = makeStandardFeedback();
    const ConfigurationSnapshot configuration = configurationOf({ output, feedback });

    auto result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configuration, output
    );
    assert(result.structuralStatus == DescriptorValidationStatus::Valid);
    assert(result.status == DescriptorValidationStatus::TimingProfileUnknown);
    assert(result.mode == FeedbackMode::Explicit);
    assert(result.association == FeedbackAssociationKind::EndpointNumber);
    assert(!result.eligible);
    assert(result.reason == "timing_profile_unknown");

    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configuration, output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Valid);
    assert(result.eligible);
    assert(result.feedback.endpointAddress == 0x86);
    assert(std::string(neri::usb::uac2::feedbackAssociationKindName(
        result.association
    )) == "endpoint_number");
}

void acceptsNonStandardShapeOnlyWithSignedAssociationProfile() {
    const EndpointSnapshot output = makeOutput();
    const EndpointSnapshot feedback = makeNonStandardFeedback();
    const ConfigurationSnapshot configuration = configurationOf({ output, feedback });

    auto result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configuration,
        output,
        verifiedTimingPolicy(0x06, 0x87)
    );
    assert(result.status == DescriptorValidationStatus::QuirkRequired);
    assert(result.reason == "feedback_endpoint_number_mismatch_requires_profile");

    FeedbackResolverPolicy policy = verifiedTimingPolicy(0x06, 0x87);
    policy.nonStandardAssociationProfileId = "synthetic-signed-endpoint-order";
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configuration, output, policy
    );
    assert(result.status == DescriptorValidationStatus::Valid);
    assert(result.association == FeedbackAssociationKind::SignedProfileSameAlternateUsage);
    assert(result.associationProfileId == "synthetic-signed-endpoint-order");
}

void rejectsMissingAmbiguousAndWrongAssociation() {
    const EndpointSnapshot output = makeOutput();
    auto result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Missing);

    const EndpointSnapshot first = makeStandardFeedback();
    const EndpointSnapshot second = makeNonStandardFeedback();
    FeedbackResolverPolicy quirk = verifiedTimingPolicy();
    quirk.nonStandardAssociationProfileId = "profile";
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, first, second }), output, quirk
    );
    assert(result.status == DescriptorValidationStatus::Valid);
    assert(result.association == FeedbackAssociationKind::EndpointNumber);
    assert(result.feedback.endpointAddress == 0x86);

    EndpointSnapshot wrong = makeEndpoint(9, 5, 0x86, 0x12, 4);
    wrong.bInterval = 3;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, wrong }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "feedback_endpoint_transfer_type_invalid");
}

void rejectsMalformedDescriptorAndCapacity() {
    EndpointSnapshot output = makeOutput();
    EndpointSnapshot feedback = makeStandardFeedback();
    feedback.effectiveCapacityKnown = false;
    feedback.effectiveMaxPacketBytes = 0;
    auto result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, feedback }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::CapacityUnknown);

    feedback = makeStandardFeedback();
    feedback.descriptorLength = 9;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, feedback }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "uac2_endpoint_descriptor_length_invalid");

    feedback = makeStandardFeedback();
    feedback.rawMaxPacketSize = 0x1804;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, feedback }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "endpoint_max_packet_invalid");

    feedback = makeStandardFeedback();
    feedback.rawMaxPacketSize = 0x0801;
    feedback.effectiveMaxPacketBytes = 2;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, feedback }), output, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "endpoint_max_packet_invalid");
}

void rejectsDuplicatesImplicitAndNonAsync() {
    const EndpointSnapshot output = makeOutput();
    const EndpointSnapshot feedback = makeStandardFeedback();
    auto result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ output, feedback, feedback }),
        output,
        verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "duplicate_endpoint_identity");

    EndpointSnapshot implicit = output;
    implicit.bmAttributes = kIsoImplicitAsync;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ implicit }), implicit, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Unsupported);
    assert(result.mode == FeedbackMode::Implicit);

    EndpointSnapshot adaptive = output;
    adaptive.bmAttributes = kIsoDataAdaptive;
    result = neri::usb::uac2::resolveExplicitFeedbackEndpoint(
        configurationOf({ adaptive }), adaptive, verifiedTimingPolicy()
    );
    assert(result.status == DescriptorValidationStatus::Invalid);
    assert(result.reason == "output_endpoint_not_asynchronous");
}

void exposesStableNames() {
    assert(std::string(neri::usb::uac2::descriptorValidationStatusName(
        DescriptorValidationStatus::QuirkRequired
    )) == "quirk_required");
    assert(std::string(neri::usb::uac2::endpointCapacitySourceName(
        EndpointCapacitySource::StandardDescriptor
    )) == "standard_descriptor");
}

} // namespace

int main() {
    resolvesStandardEndpointNumberAndRequiresTiming();
    acceptsNonStandardShapeOnlyWithSignedAssociationProfile();
    rejectsMissingAmbiguousAndWrongAssociation();
    rejectsMalformedDescriptorAndCapacity();
    rejectsDuplicatesImplicitAndNonAsync();
    exposesStableNames();
    return 0;
}
