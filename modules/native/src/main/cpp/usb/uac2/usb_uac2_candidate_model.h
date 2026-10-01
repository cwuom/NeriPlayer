#pragma once

#include "usb/uac2/usb_uac2_clock_graph.h"
#include "usb/uac2/usb_uac2_feedback_model.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"
#include "usb/uac2/usb_uac2_format.h"

#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac2 {

constexpr uint8_t kUac2InterfaceProtocol = 0x20;

enum class CandidateDecision {
    Rejected,
    Eligible,
    FeatureDisabled
};

enum class CandidateRejectionCode {
    None,
    InvalidInput,
    DuplicateIdentity,
    ProtocolMismatch,
    AudioFunctionUnbound,
    FormatMismatch,
    SampleRateUnverified,
    ClockGraphInvalid,
    ClockFunctionMismatch,
    OutputEndpointInvalid,
    CapacityUnverified,
    CapacityInsufficient,
    FeedbackMissing,
    FeedbackAmbiguous,
    FeedbackInvalid,
    FeedbackAssociationUnverified,
    TimingProfileUnknown,
    ImplicitFeedbackUnsupported,
    ClaimPlanInvalid,
    InterfaceAlternateConflict
};

enum class CandidateSyncMode {
    None,
    Asynchronous,
    Adaptive,
    Synchronous
};

enum class ClaimInterfaceKind {
    AudioControl,
    AudioStreamingOutput,
    AudioStreamingFeedback
};

struct DeviceDescriptorIdentity {
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    uint16_t bcdDevice = 0;
    std::string descriptorFingerprint;
};

struct EndpointCapacityEvidence {
    bool verified = false;
    int maximumRequiredPacketBytes = 0;
};

struct ClaimPlanEntry {
    int interfaceNumber = -1;
    int alternateSetting = -1;
    ClaimInterfaceKind kind = ClaimInterfaceKind::AudioControl;
};

struct Uac2CandidateInput {
    DeviceDescriptorIdentity identity;
    int configurationValue = -1;
    int audioControlInterface = -1;
    int audioControlAlternateSetting = 0;
    int streamingInterface = -1;
    int streamingAlternateSetting = -1;
    uint8_t interfaceProtocol = 0;
    UsbBusSpeed busSpeed = UsbBusSpeed::Unknown;
    bool audioControlBindsStreaming = false;
    bool sampleRateExact = false;
    bool feedbackInterfaceIsAudioStreaming = true;
    bool feedbackInterfaceBoundToAudioFunction = false;
    ControlCapability sampleRateControl = ControlCapability::None;
    TypeIFormat format;
    EndpointSnapshot output;
    ConfigurationSnapshot configuration;
    std::vector<AudioFunctionClockGraph> clockFunctions;
    EndpointCapacityEvidence capacityEvidence;
    FeedbackTimingEvidence feedbackTiming;
    std::string nonStandardFeedbackAssociationProfileId;
};

struct Uac2CandidateRequest {
    int sampleRate = 0;
    FormatTarget formatTarget;
    FeedbackResolverPolicy feedbackPolicy;
};

struct Uac2StreamingCandidate {
    std::string candidateId;
    CandidateDecision decision = CandidateDecision::Rejected;
    CandidateRejectionCode rejection = CandidateRejectionCode::InvalidInput;
    CandidateSyncMode syncMode = CandidateSyncMode::None;
    FeedbackMode feedbackMode = FeedbackMode::None;
    FeedbackAssociationKind feedbackAssociation = FeedbackAssociationKind::None;
    std::string feedbackTimingProfileId;
    std::string feedbackAssociationProfileId;
    int softScore = 0;
    bool hardEligible = false;
    std::string reason;
    EndpointSnapshot output;
    EndpointSnapshot feedback;
    ClockGraphResult clockGraph;
    std::vector<ClaimPlanEntry> claimPlan;
};

struct Uac2CandidateListResult {
    std::vector<Uac2StreamingCandidate> candidates;
    int bestCandidateIndex = -1;
};

Uac2CandidateListResult buildUac2CandidateList(
    const std::vector<Uac2CandidateInput>& inputs,
    const Uac2CandidateRequest& request
);

const char* candidateDecisionName(CandidateDecision decision);
const char* candidateRejectionCodeName(CandidateRejectionCode code);
const char* candidateSyncModeName(CandidateSyncMode mode);
const char* claimInterfaceKindName(ClaimInterfaceKind kind);

} // namespace neri::usb::uac2
