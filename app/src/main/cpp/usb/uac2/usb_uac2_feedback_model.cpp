#include "usb_uac2_feedback_model.h"

#include <algorithm>
#include <cstddef>

namespace neri::usb::uac2 {
namespace {

constexpr uint8_t kEndpointDescriptorType = 0x05;
constexpr uint8_t kUac2EndpointDescriptorLength = 7;
constexpr uint8_t kTransferTypeMask = 0x03;
constexpr uint8_t kSyncTypeMask = 0x0C;
constexpr uint8_t kUsageTypeMask = 0x30;
constexpr uint8_t kEndpointAddressReservedMask = 0x70;
constexpr uint8_t kEndpointAttributesReservedMask = 0xC0;
constexpr uint8_t kIsochronousTransferType = 0x01;
constexpr uint8_t kAsyncSyncType = 0x01;
constexpr uint8_t kDataUsageType = 0x00;
constexpr uint8_t kFeedbackUsageType = 0x01;
constexpr uint8_t kImplicitUsageType = 0x02;

bool sameIdentity(const EndpointSnapshot& left, const EndpointSnapshot& right) {
    return left.configurationValue == right.configurationValue &&
        left.interfaceNumber == right.interfaceNumber &&
        left.alternateSetting == right.alternateSetting &&
        left.endpointAddress == right.endpointAddress;
}

bool sameSnapshot(const EndpointSnapshot& left, const EndpointSnapshot& right) {
    return sameIdentity(left, right) &&
        left.descriptorLength == right.descriptorLength &&
        left.descriptorType == right.descriptorType &&
        left.bmAttributes == right.bmAttributes &&
        left.rawMaxPacketSize == right.rawMaxPacketSize &&
        left.effectiveMaxPacketBytes == right.effectiveMaxPacketBytes &&
        left.effectiveCapacityKnown == right.effectiveCapacityKnown &&
        left.capacitySource == right.capacitySource &&
        left.bInterval == right.bInterval &&
        left.bRefresh == right.bRefresh &&
        left.hasRefresh == right.hasRefresh &&
        left.bSynchAddress == right.bSynchAddress &&
        left.hasSynchAddress == right.hasSynchAddress;
}

bool sameAlternate(const EndpointSnapshot& left, const EndpointSnapshot& right) {
    return left.configurationValue == right.configurationValue &&
        left.interfaceNumber == right.interfaceNumber &&
        left.alternateSetting == right.alternateSetting;
}

uint8_t endpointNumber(const EndpointSnapshot& endpoint) {
    return endpoint.endpointAddress & 0x0FU;
}

void fail(
    FeedbackEndpointResolution* result,
    DescriptorValidationStatus status,
    const char* reason
) {
    result->structuralStatus = status;
    result->status = status;
    result->eligible = false;
    result->reason = reason;
}

bool validateDescriptorFields(const EndpointSnapshot& endpoint, std::string* reason) {
    if (endpoint.configurationValue <= 0 || endpoint.interfaceNumber < 0 ||
        endpoint.alternateSetting < 0) {
        *reason = "endpoint_identity_invalid";
        return false;
    }
    if (endpoint.descriptorLength != kUac2EndpointDescriptorLength) {
        *reason = "uac2_endpoint_descriptor_length_invalid";
        return false;
    }
    if (endpoint.descriptorType != kEndpointDescriptorType) {
        *reason = "endpoint_descriptor_type_invalid";
        return false;
    }
    if (endpoint.hasRefresh || endpoint.hasSynchAddress ||
        endpoint.bRefresh != 0 || endpoint.bSynchAddress != 0) {
        *reason = "uac2_endpoint_legacy_fields_present";
        return false;
    }
    if ((endpoint.endpointAddress & kEndpointAddressReservedMask) != 0 ||
        (endpoint.endpointAddress & 0x0F) == 0) {
        *reason = "endpoint_address_invalid";
        return false;
    }
    if ((endpoint.bmAttributes & kEndpointAttributesReservedMask) != 0) {
        *reason = "endpoint_attributes_reserved_bits";
        return false;
    }
    const uint16_t payloadBytes = endpoint.rawMaxPacketSize & 0x07FFU;
    const uint16_t transactionBits =
        (endpoint.rawMaxPacketSize >> 11U) & 0x03U;
    const bool highBandwidthSizeInvalid =
        (transactionBits == 1U && payloadBytes < 513U) ||
        (transactionBits == 2U && payloadBytes < 683U);
    if (payloadBytes == 0 || payloadBytes > 1024U ||
        transactionBits == 0x03U || highBandwidthSizeInvalid ||
        (endpoint.rawMaxPacketSize & 0xE000U) != 0) {
        *reason = "endpoint_max_packet_invalid";
        return false;
    }
    if (endpoint.bInterval == 0) {
        *reason = "endpoint_interval_invalid";
        return false;
    }
    return true;
}

bool validateCapacity(const EndpointSnapshot& endpoint, std::string* reason) {
    if (!endpoint.effectiveCapacityKnown || endpoint.effectiveMaxPacketBytes <= 0 ||
        endpoint.capacitySource == EndpointCapacitySource::Unknown) {
        *reason = "endpoint_capacity_unknown";
        return false;
    }
    if (endpoint.capacitySource == EndpointCapacitySource::StandardDescriptor) {
        const int payloadBytes = endpoint.rawMaxPacketSize & 0x07FFU;
        const int transactions =
            1 + static_cast<int>((endpoint.rawMaxPacketSize >> 11U) & 0x03U);
        if (endpoint.effectiveMaxPacketBytes != payloadBytes * transactions) {
            *reason = "endpoint_capacity_source_mismatch";
            return false;
        }
    }
    return true;
}

bool validateOutput(
    const EndpointSnapshot& output,
    const FeedbackResolverPolicy& policy,
    std::string* reason
) {
    if (!validateDescriptorFields(output, reason)) {
        return false;
    }
    if (!output.isOut()) {
        *reason = "output_endpoint_direction_invalid";
        return false;
    }
    if (!output.isIsochronous()) {
        *reason = "output_endpoint_transfer_type_invalid";
        return false;
    }
    if (output.usageType() == kFeedbackUsageType) {
        *reason = "output_endpoint_feedback_usage_invalid";
        return false;
    }
    if (output.usageType() == 0x03) {
        *reason = "output_endpoint_usage_reserved";
        return false;
    }
    if (output.usageType() == kImplicitUsageType) {
        if (output.hasSynchAddress && output.bSynchAddress != 0) {
            *reason = "implicit_output_has_synch_address";
            return false;
        }
        *reason = "implicit_feedback_requires_separate_path";
        return false;
    }
    if (output.usageType() != kDataUsageType) {
        *reason = "output_endpoint_usage_invalid";
        return false;
    }
    if (policy.requireAsynchronousOutput && output.syncType() != kAsyncSyncType) {
        *reason = "output_endpoint_not_asynchronous";
        return false;
    }
    return validateCapacity(output, reason);
}

bool validateFeedback(
    const EndpointSnapshot& feedback,
    std::string* reason
) {
    if (!validateDescriptorFields(feedback, reason)) {
        return false;
    }
    if (!feedback.isIn()) {
        *reason = "feedback_endpoint_direction_invalid";
        return false;
    }
    if (!feedback.isIsochronous()) {
        *reason = "feedback_endpoint_transfer_type_invalid";
        return false;
    }
    if (feedback.usageType() != kFeedbackUsageType) {
        *reason = "feedback_endpoint_usage_invalid";
        return false;
    }
    if (feedback.syncType() != 0) {
        *reason = "feedback_endpoint_sync_type_invalid";
        return false;
    }
    return validateCapacity(feedback, reason);
}

bool hasDataEndpointAtNumber(
    const ConfigurationSnapshot& configuration,
    const EndpointSnapshot& output,
    uint8_t number
) {
    return std::any_of(
        configuration.endpoints.begin(),
        configuration.endpoints.end(),
        [&output, number](const EndpointSnapshot& endpoint) {
            return sameAlternate(endpoint, output) &&
                endpoint.isOut() == output.isOut() &&
                endpoint.isIsochronous() &&
                endpoint.usageType() == kDataUsageType &&
                endpointNumber(endpoint) == number;
        }
    );
}

bool hasDuplicateIdentity(const ConfigurationSnapshot& configuration) {
    for (size_t left = 0; left < configuration.endpoints.size(); ++left) {
        for (size_t right = left + 1; right < configuration.endpoints.size(); ++right) {
            if (sameIdentity(configuration.endpoints[left], configuration.endpoints[right])) {
                return true;
            }
        }
    }
    return false;
}

bool isConfigurationConsistent(
    const ConfigurationSnapshot& configuration,
    std::string* reason
) {
    if (configuration.configurationValue <= 0) {
        *reason = "configuration_value_invalid";
        return false;
    }
    if (hasDuplicateIdentity(configuration)) {
        *reason = "duplicate_endpoint_identity";
        return false;
    }
    for (const EndpointSnapshot& endpoint : configuration.endpoints) {
        if (endpoint.configurationValue != configuration.configurationValue) {
            *reason = "endpoint_configuration_mismatch";
            return false;
        }
    }
    return true;
}

void finishValidResolution(
    FeedbackEndpointResolution* result,
    const FeedbackResolverPolicy& policy
) {
    result->structuralStatus = DescriptorValidationStatus::Valid;
    const FeedbackTimingEvidence& timing = policy.timing;
    const bool timingMatches = timing.verified && !timing.profileId.empty() &&
        timing.outputEndpointAddress == result->output.endpointAddress &&
        timing.feedbackEndpointAddress == result->feedback.endpointAddress &&
        timing.outputBInterval == result->output.bInterval &&
        timing.feedbackBInterval == result->feedback.bInterval &&
        timing.payloadBytesExpected > 0 &&
        timing.feedbackExpectedPeriodNanoseconds > 0;
    result->timingProfileVerified = timingMatches;
    if (!timingMatches) {
        result->status = DescriptorValidationStatus::TimingProfileUnknown;
        result->reason = timing.verified
            ? "timing_profile_endpoint_mismatch"
            : "timing_profile_unknown";
        result->eligible = false;
        return;
    }
    result->timingProfileId = timing.profileId;
    result->status = DescriptorValidationStatus::Valid;
    result->reason.clear();
    result->eligible = true;
}

bool associationScopeAllowed(
    const EndpointSnapshot& output,
    const EndpointSnapshot& feedback,
    const FeedbackResolverPolicy& policy,
    std::string* reason
) {
    if (output.interfaceNumber != feedback.interfaceNumber &&
        !policy.allowCrossInterfaceAssociation) {
        *reason = "cross_interface_association_unverified";
        return false;
    }
    if (output.alternateSetting != feedback.alternateSetting &&
        !policy.allowCrossAlternateAssociation) {
        *reason = "cross_alternate_association_unverified";
        return false;
    }
    return true;
}

} // namespace

bool EndpointSnapshot::isIsochronous() const {
    return transferType() == kIsochronousTransferType;
}

bool EndpointSnapshot::isIn() const {
    return (endpointAddress & 0x80U) != 0;
}

bool EndpointSnapshot::isOut() const {
    return !isIn();
}

uint8_t EndpointSnapshot::transferType() const {
    return bmAttributes & kTransferTypeMask;
}

uint8_t EndpointSnapshot::syncType() const {
    return static_cast<uint8_t>((bmAttributes & kSyncTypeMask) >> 2U);
}

uint8_t EndpointSnapshot::usageType() const {
    return static_cast<uint8_t>((bmAttributes & kUsageTypeMask) >> 4U);
}

DescriptorValidationStatus validateOutputEndpointSnapshot(
    const EndpointSnapshot& output,
    const FeedbackResolverPolicy& policy,
    std::string* reason
) {
    std::string validationReason;
    if (validateOutput(output, policy, &validationReason)) {
        if (reason != nullptr) {
            reason->clear();
        }
        return DescriptorValidationStatus::Valid;
    }
    if (reason != nullptr) {
        *reason = validationReason;
    }
    if (validationReason == "endpoint_capacity_unknown") {
        return DescriptorValidationStatus::CapacityUnknown;
    }
    if (validationReason == "implicit_feedback_requires_separate_path") {
        return DescriptorValidationStatus::Unsupported;
    }
    return DescriptorValidationStatus::Invalid;
}

DescriptorValidationStatus validateFeedbackEndpointSnapshot(
    const EndpointSnapshot& feedback,
    std::string* reason
) {
    std::string validationReason;
    if (validateFeedback(feedback, &validationReason)) {
        if (reason != nullptr) {
            reason->clear();
        }
        return DescriptorValidationStatus::Valid;
    }
    if (reason != nullptr) {
        *reason = validationReason;
    }
    return validationReason == "endpoint_capacity_unknown"
        ? DescriptorValidationStatus::CapacityUnknown
        : DescriptorValidationStatus::Invalid;
}

FeedbackEndpointResolution resolveExplicitFeedbackEndpoint(
    const ConfigurationSnapshot& configuration,
    const EndpointSnapshot& output,
    const FeedbackResolverPolicy& policy
) {
    FeedbackEndpointResolution result;
    result.output = output;
    result.mode = FeedbackMode::Explicit;

    std::string reason;
    if (!isConfigurationConsistent(configuration, &reason)) {
        fail(&result, DescriptorValidationStatus::Invalid, reason.c_str());
        return result;
    }
    if (output.configurationValue != configuration.configurationValue) {
        fail(&result, DescriptorValidationStatus::Invalid, "output_configuration_mismatch");
        return result;
    }
    const auto outputIt = std::find_if(
        configuration.endpoints.begin(),
        configuration.endpoints.end(),
        [&output](const EndpointSnapshot& endpoint) {
            return sameIdentity(endpoint, output);
        }
    );
    if (outputIt == configuration.endpoints.end()) {
        fail(&result, DescriptorValidationStatus::Invalid, "output_not_in_configuration");
        return result;
    }
    if (!sameSnapshot(*outputIt, output)) {
        fail(&result, DescriptorValidationStatus::Invalid, "output_snapshot_mismatch");
        return result;
    }
    if (!validateOutput(output, policy, &reason)) {
        if (output.usageType() == kImplicitUsageType &&
            reason == "implicit_feedback_requires_separate_path") {
            result.mode = FeedbackMode::Implicit;
            fail(&result, DescriptorValidationStatus::Unsupported, reason.c_str());
        } else if (reason == "endpoint_capacity_unknown") {
            fail(&result, DescriptorValidationStatus::CapacityUnknown, reason.c_str());
        } else {
            fail(&result, DescriptorValidationStatus::Invalid, reason.c_str());
        }
        return result;
    }

    std::vector<EndpointSnapshot> feedbackEndpoints;
    for (const EndpointSnapshot& endpoint : configuration.endpoints) {
        if (!sameIdentity(endpoint, output) &&
            endpoint.usageType() == kFeedbackUsageType) {
            feedbackEndpoints.push_back(endpoint);
        }
    }
    if (feedbackEndpoints.empty()) {
        fail(&result, DescriptorValidationStatus::Missing, "uac2_feedback_endpoint_missing");
        return result;
    }

    const uint8_t outputNumber = endpointNumber(output);
    const EndpointSnapshot* standardFeedback = nullptr;
    for (const EndpointSnapshot& endpoint : feedbackEndpoints) {
        const uint8_t candidateNumber = endpointNumber(endpoint);
        if (candidateNumber > outputNumber ||
            !hasDataEndpointAtNumber(configuration, output, candidateNumber)) {
            continue;
        }
        if (standardFeedback == nullptr ||
            candidateNumber > endpointNumber(*standardFeedback)) {
            standardFeedback = &endpoint;
        }
    }
    if (standardFeedback != nullptr) {
        result.matches.push_back(*standardFeedback);
        result.feedback = *standardFeedback;
        if (!validateFeedback(result.feedback, &reason)) {
            if (reason == "endpoint_capacity_unknown") {
                fail(&result, DescriptorValidationStatus::CapacityUnknown, reason.c_str());
            } else {
                fail(&result, DescriptorValidationStatus::Invalid, reason.c_str());
            }
            return result;
        }
        result.association = FeedbackAssociationKind::EndpointNumber;
        if (!associationScopeAllowed(output, result.feedback, policy, &reason)) {
            fail(&result, DescriptorValidationStatus::AssociationUnverified, reason.c_str());
            return result;
        }
        finishValidResolution(&result, policy);
        return result;
    }

    result.matches = feedbackEndpoints;
    if (policy.nonStandardAssociationProfileId.empty()) {
        fail(
            &result,
            DescriptorValidationStatus::QuirkRequired,
            "feedback_endpoint_number_mismatch_requires_profile"
        );
        return result;
    }
    const auto profiledFeedback = std::find_if(
        feedbackEndpoints.begin(),
        feedbackEndpoints.end(),
        [&policy](const EndpointSnapshot& endpoint) {
            return endpoint.endpointAddress == policy.timing.feedbackEndpointAddress;
        }
    );
    if (profiledFeedback == feedbackEndpoints.end()) {
        fail(
            &result,
            DescriptorValidationStatus::AssociationUnverified,
            "profiled_feedback_endpoint_missing"
        );
        return result;
    }
    if (std::count_if(
            feedbackEndpoints.begin(),
            feedbackEndpoints.end(),
            [&policy](const EndpointSnapshot& endpoint) {
                return endpoint.endpointAddress == policy.timing.feedbackEndpointAddress;
            }
        ) != 1) {
        fail(
            &result,
            DescriptorValidationStatus::Ambiguous,
            "profiled_feedback_endpoint_ambiguous"
        );
        return result;
    }
    result.feedback = *profiledFeedback;
    if (!validateFeedback(result.feedback, &reason)) {
        if (reason == "endpoint_capacity_unknown") {
            fail(&result, DescriptorValidationStatus::CapacityUnknown, reason.c_str());
        } else {
            fail(&result, DescriptorValidationStatus::Invalid, reason.c_str());
        }
        return result;
    }
    if (!associationScopeAllowed(output, result.feedback, policy, &reason)) {
        fail(&result, DescriptorValidationStatus::AssociationUnverified, reason.c_str());
        return result;
    }
    result.association = FeedbackAssociationKind::SignedProfileSameAlternateUsage;
    result.associationProfileId = policy.nonStandardAssociationProfileId;
    finishValidResolution(&result, policy);
    return result;
}

const char* descriptorValidationStatusName(DescriptorValidationStatus status) {
    switch (status) {
        case DescriptorValidationStatus::Unchecked:
            return "unchecked";
        case DescriptorValidationStatus::Valid:
            return "valid";
        case DescriptorValidationStatus::TimingProfileUnknown:
            return "timing_profile_unknown";
        case DescriptorValidationStatus::AssociationUnverified:
            return "association_unverified";
        case DescriptorValidationStatus::Invalid:
            return "invalid";
        case DescriptorValidationStatus::Ambiguous:
            return "ambiguous";
        case DescriptorValidationStatus::Unsupported:
            return "unsupported";
        case DescriptorValidationStatus::QuirkRequired:
            return "quirk_required";
        case DescriptorValidationStatus::CapacityUnknown:
            return "capacity_unknown";
        case DescriptorValidationStatus::Missing:
            return "missing";
    }
    return "unknown";
}

const char* feedbackModeName(FeedbackMode mode) {
    switch (mode) {
        case FeedbackMode::None:
            return "none";
        case FeedbackMode::Explicit:
            return "explicit";
        case FeedbackMode::Implicit:
            return "implicit";
    }
    return "unknown";
}

const char* feedbackAssociationKindName(FeedbackAssociationKind kind) {
    switch (kind) {
        case FeedbackAssociationKind::None:
            return "none";
        case FeedbackAssociationKind::EndpointNumber:
            return "endpoint_number";
        case FeedbackAssociationKind::SignedProfileSameAlternateUsage:
            return "signed_profile_same_alternate_usage";
    }
    return "unknown";
}

const char* endpointCapacitySourceName(EndpointCapacitySource source) {
    switch (source) {
        case EndpointCapacitySource::Unknown:
            return "unknown";
        case EndpointCapacitySource::StandardDescriptor:
            return "standard_descriptor";
        case EndpointCapacitySource::BackendComputed:
            return "backend_computed";
        case EndpointCapacitySource::SuperSpeedCompanion:
            return "superspeed_companion";
        case EndpointCapacitySource::SignedProfile:
            return "signed_profile";
    }
    return "unknown";
}

} // namespace neri::usb::uac2
