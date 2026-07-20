#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac2 {

enum class DescriptorValidationStatus {
    Unchecked,
    Valid,
    TimingProfileUnknown,
    AssociationUnverified,
    Invalid,
    Ambiguous,
    Unsupported,
    QuirkRequired,
    CapacityUnknown,
    Missing
};

enum class FeedbackMode {
    None,
    Explicit,
    Implicit
};

enum class FeedbackAssociationKind {
    None,
    EndpointNumber,
    SignedProfileSameAlternateUsage
};

enum class EndpointCapacitySource {
    Unknown,
    StandardDescriptor,
    BackendComputed,
    SuperSpeedCompanion,
    SignedProfile
};

struct FeedbackTimingEvidence {
    bool verified = false;
    uint8_t outputEndpointAddress = 0;
    uint8_t feedbackEndpointAddress = 0;
    uint8_t outputBInterval = 0;
    uint8_t feedbackBInterval = 0;
    uint8_t payloadBytesExpected = 0;
    uint64_t feedbackExpectedPeriodNanoseconds = 0;
    std::string profileId;
};

struct EndpointSnapshot {
    int configurationValue = -1;
    int interfaceNumber = -1;
    int alternateSetting = -1;
    uint8_t endpointAddress = 0;

    uint8_t descriptorLength = 0;
    uint8_t descriptorType = 0;
    uint8_t bmAttributes = 0;
    uint16_t rawMaxPacketSize = 0;
    int effectiveMaxPacketBytes = 0;
    bool effectiveCapacityKnown = false;
    EndpointCapacitySource capacitySource = EndpointCapacitySource::Unknown;

    uint8_t bInterval = 0;
    uint8_t bRefresh = 0;
    bool hasRefresh = false;
    uint8_t bSynchAddress = 0;
    bool hasSynchAddress = false;

    [[nodiscard]] bool isIsochronous() const;
    [[nodiscard]] bool isIn() const;
    [[nodiscard]] bool isOut() const;
    [[nodiscard]] uint8_t transferType() const;
    [[nodiscard]] uint8_t syncType() const;
    [[nodiscard]] uint8_t usageType() const;
};

struct ConfigurationSnapshot {
    int configurationValue = -1;
    std::vector<EndpointSnapshot> endpoints;
};

struct FeedbackResolverPolicy {
    FeedbackTimingEvidence timing;
    std::string nonStandardAssociationProfileId;
    bool allowCrossInterfaceAssociation = false;
    bool allowCrossAlternateAssociation = false;
    bool requireAsynchronousOutput = true;
};

struct FeedbackEndpointResolution {
    DescriptorValidationStatus structuralStatus = DescriptorValidationStatus::Unchecked;
    DescriptorValidationStatus status = DescriptorValidationStatus::Unchecked;
    FeedbackMode mode = FeedbackMode::None;
    FeedbackAssociationKind association = FeedbackAssociationKind::None;
    EndpointSnapshot output;
    EndpointSnapshot feedback;
    std::vector<EndpointSnapshot> matches;
    bool timingProfileVerified = false;
    std::string timingProfileId;
    std::string associationProfileId;
    bool eligible = false;
    std::string reason;
};

// Validate one endpoint without resolving an association. The candidate model
// uses these helpers for synchronous/adaptive outputs, where no feedback
// endpoint is expected.
DescriptorValidationStatus validateOutputEndpointSnapshot(
    const EndpointSnapshot& output,
    const FeedbackResolverPolicy& policy = {},
    std::string* reason = nullptr
);

DescriptorValidationStatus validateFeedbackEndpointSnapshot(
    const EndpointSnapshot& feedback,
    std::string* reason = nullptr
);

FeedbackEndpointResolution resolveExplicitFeedbackEndpoint(
    const ConfigurationSnapshot& configuration,
    const EndpointSnapshot& output,
    const FeedbackResolverPolicy& policy = {}
);

const char* descriptorValidationStatusName(DescriptorValidationStatus status);
const char* feedbackModeName(FeedbackMode mode);
const char* feedbackAssociationKindName(FeedbackAssociationKind kind);
const char* endpointCapacitySourceName(EndpointCapacitySource source);

} // namespace neri::usb::uac2
