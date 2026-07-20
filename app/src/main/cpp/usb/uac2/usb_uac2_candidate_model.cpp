#include "usb/uac2/usb_uac2_candidate_model.h"

#include <algorithm>
#include <iomanip>
#include <map>
#include <sstream>
#include <utility>

namespace neri::usb::uac2 {
namespace {

constexpr uint8_t kUsageData = 0x00;
constexpr uint8_t kSyncAsync = 0x01;
constexpr uint8_t kSyncAdaptive = 0x02;
constexpr uint8_t kSyncSynchronous = 0x03;

bool sameIdentity(const EndpointSnapshot& left, const EndpointSnapshot& right) {
    return left.configurationValue == right.configurationValue &&
        left.interfaceNumber == right.interfaceNumber &&
        left.alternateSetting == right.alternateSetting &&
        left.endpointAddress == right.endpointAddress;
}

bool sameEndpoint(const EndpointSnapshot& left, const EndpointSnapshot& right) {
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

bool validIdentity(const DeviceDescriptorIdentity& identity) {
    return !identity.descriptorFingerprint.empty();
}

std::string makeCandidateId(const Uac2CandidateInput& input) {
    std::ostringstream stream;
    stream << "uac2:" << std::hex << std::setfill('0') << std::setw(4)
           << input.identity.vendorId << ':' << std::setw(4)
           << input.identity.productId << ':' << std::setw(4)
           << input.identity.bcdDevice << ":fp=" << std::dec
           << input.identity.descriptorFingerprint.size() << ':'
           << input.identity.descriptorFingerprint << ":cfg="
           << input.configurationValue << ":if="
           << input.streamingInterface << ":alt="
           << input.streamingAlternateSetting << ":ep=0x" << std::hex
           << std::setw(2) << static_cast<int>(input.output.endpointAddress);
    return stream.str();
}

void reject(
    Uac2StreamingCandidate* candidate,
    CandidateRejectionCode code,
    const std::string& reason
) {
    candidate->decision = CandidateDecision::Rejected;
    candidate->rejection = code;
    candidate->hardEligible = false;
    candidate->softScore = 0;
    candidate->reason = reason;
}

CandidateRejectionCode mapEndpointStatus(DescriptorValidationStatus status) {
    switch (status) {
        case DescriptorValidationStatus::CapacityUnknown:
            return CandidateRejectionCode::CapacityUnverified;
        case DescriptorValidationStatus::Unsupported:
            return CandidateRejectionCode::ImplicitFeedbackUnsupported;
        case DescriptorValidationStatus::QuirkRequired:
        case DescriptorValidationStatus::AssociationUnverified:
            return CandidateRejectionCode::FeedbackAssociationUnverified;
        default:
            return CandidateRejectionCode::OutputEndpointInvalid;
    }
}

CandidateSyncMode syncModeFor(const EndpointSnapshot& endpoint) {
    switch (endpoint.syncType()) {
        case kSyncAsync:
            return CandidateSyncMode::Asynchronous;
        case kSyncAdaptive:
            return CandidateSyncMode::Adaptive;
        case kSyncSynchronous:
            return CandidateSyncMode::Synchronous;
        default:
            return CandidateSyncMode::None;
    }
}

bool configurationHasUniqueEndpoint(
    const ConfigurationSnapshot& configuration,
    const EndpointSnapshot& expected
) {
    size_t matches = 0;
    for (const EndpointSnapshot& endpoint : configuration.endpoints) {
        if (sameIdentity(endpoint, expected)) {
            ++matches;
            if (!sameEndpoint(endpoint, expected)) {
                return false;
            }
        }
    }
    return matches == 1U;
}

bool hasDuplicateEndpointIdentity(const ConfigurationSnapshot& configuration) {
    for (size_t left = 0; left < configuration.endpoints.size(); ++left) {
        for (size_t right = left + 1; right < configuration.endpoints.size(); ++right) {
            if (sameIdentity(configuration.endpoints[left], configuration.endpoints[right])) {
                return true;
            }
        }
    }
    return false;
}

bool addClaimEntry(
    std::vector<ClaimPlanEntry>* plan,
    const ClaimPlanEntry& entry
) {
    if (plan == nullptr || entry.interfaceNumber < 0 || entry.alternateSetting < 0) {
        return false;
    }
    for (const ClaimPlanEntry& existing : *plan) {
        if (existing.interfaceNumber != entry.interfaceNumber) {
            continue;
        }
        if (existing.alternateSetting != entry.alternateSetting) {
            return false;
        }
        return true;
    }
    plan->push_back(entry);
    return true;
}

bool buildClaimPlan(
    const Uac2CandidateInput& input,
    const FeedbackEndpointResolution& feedback,
    std::vector<ClaimPlanEntry>* plan,
    std::string* reason
) {
    if (plan == nullptr || reason == nullptr || input.audioControlInterface < 0 ||
        input.streamingInterface < 0 || input.streamingAlternateSetting < 0 ||
        input.audioControlAlternateSetting < 0) {
        if (reason != nullptr) {
            *reason = "claim_interface_identity_invalid";
        }
        return false;
    }
    if (input.audioControlInterface == input.streamingInterface) {
        *reason = "audio_control_streaming_interface_conflict";
        return false;
    }
    plan->clear();
    if (!addClaimEntry(
            plan,
            ClaimPlanEntry {
                input.audioControlInterface,
                input.audioControlAlternateSetting,
                ClaimInterfaceKind::AudioControl
            }
        ) ||
        !addClaimEntry(
            plan,
            ClaimPlanEntry {
                input.streamingInterface,
                input.streamingAlternateSetting,
                ClaimInterfaceKind::AudioStreamingOutput
            }
        )) {
        *reason = "claim_interface_alt_conflict";
        return false;
    }

    if (feedback.mode == FeedbackMode::Explicit && feedback.eligible) {
        if (feedback.feedback.interfaceNumber == input.streamingInterface &&
            feedback.feedback.alternateSetting != input.streamingAlternateSetting) {
            *reason = "feedback_same_interface_alt_conflict";
            return false;
        }
        if (feedback.feedback.interfaceNumber != input.streamingInterface) {
            if (feedback.feedback.interfaceNumber == input.audioControlInterface) {
                *reason = "feedback_audio_control_interface_conflict";
                return false;
            }
            if (!input.feedbackInterfaceBoundToAudioFunction) {
                *reason = "feedback_audio_function_binding_unverified";
                return false;
            }
            if (!input.feedbackInterfaceIsAudioStreaming) {
                *reason = "feedback_interface_not_audio_streaming";
                return false;
            }
            if (!addClaimEntry(
                    plan,
                    ClaimPlanEntry {
                        feedback.feedback.interfaceNumber,
                        feedback.feedback.alternateSetting,
                        ClaimInterfaceKind::AudioStreamingFeedback
                    }
                )) {
                *reason = "feedback_interface_alt_conflict";
                return false;
            }
        }
    }

    for (size_t index = 0; index < plan->size(); ++index) {
        for (size_t other = index + 1; other < plan->size(); ++other) {
            if ((*plan)[index].interfaceNumber == (*plan)[other].interfaceNumber &&
                (*plan)[index].alternateSetting != (*plan)[other].alternateSetting) {
                *reason = "claim_interface_alt_conflict";
                return false;
            }
        }
    }
    return true;
}

int scoreCandidate(
    const Uac2StreamingCandidate& candidate,
    ControlCapability sampleRateControl
) {
    int score = 1000;
    switch (candidate.syncMode) {
        case CandidateSyncMode::Asynchronous:
            score += 80;
            break;
        case CandidateSyncMode::Adaptive:
            score += 50;
            break;
        case CandidateSyncMode::Synchronous:
            score += 40;
            break;
        case CandidateSyncMode::None:
            break;
    }
    if (sampleRateControl == ControlCapability::ReadWrite) {
        score += 30;
    } else if (sampleRateControl == ControlCapability::ReadOnly) {
        score += 10;
    }
    if (candidate.clockGraph.validity == ClockValidityState::Valid) {
        score += 20;
    }
    if (candidate.feedbackMode == FeedbackMode::Explicit) {
        score += candidate.feedbackAssociation == FeedbackAssociationKind::EndpointNumber
            ? 30
            : 20;
    }
    return score;
}

Uac2StreamingCandidate buildCandidate(
    const Uac2CandidateInput& input,
    const Uac2CandidateRequest& request
) {
    Uac2StreamingCandidate candidate;
    candidate.candidateId = makeCandidateId(input);
    candidate.output = input.output;

    if (!validIdentity(input.identity) || request.sampleRate <= 0 ||
        request.formatTarget.channels <= 0 || request.formatTarget.subslotBytes <= 0 ||
        request.formatTarget.bitsPerSample <= 0 || input.configurationValue <= 0 ||
        input.audioControlInterface < 0 || input.streamingInterface < 0 ||
        input.streamingAlternateSetting < 0) {
        reject(&candidate, CandidateRejectionCode::InvalidInput, "candidate_input_invalid");
        return candidate;
    }
    if (input.interfaceProtocol != kUac2InterfaceProtocol) {
        reject(
            &candidate,
            CandidateRejectionCode::ProtocolMismatch,
            "uac2_interface_protocol_mismatch"
        );
        return candidate;
    }
    if (!input.audioControlBindsStreaming) {
        reject(
            &candidate,
            CandidateRejectionCode::AudioFunctionUnbound,
            "audio_control_streaming_binding_unverified"
        );
        return candidate;
    }
    if (!input.sampleRateExact) {
        reject(
            &candidate,
            CandidateRejectionCode::SampleRateUnverified,
            "sample_rate_exact_match_unverified"
        );
        return candidate;
    }

    std::string formatReason;
    if (!matchesTarget(input.format, request.formatTarget, &formatReason)) {
        reject(
            &candidate,
            CandidateRejectionCode::FormatMismatch,
            formatReason.empty() ? "pcm_format_mismatch" : formatReason
        );
        return candidate;
    }
    if (input.configuration.configurationValue != input.configurationValue ||
        input.output.configurationValue != input.configurationValue ||
        input.output.interfaceNumber != input.streamingInterface ||
        input.output.alternateSetting != input.streamingAlternateSetting) {
        reject(
            &candidate,
            CandidateRejectionCode::OutputEndpointInvalid,
            "output_configuration_identity_invalid"
        );
        return candidate;
    }
    if (hasDuplicateEndpointIdentity(input.configuration)) {
        reject(
            &candidate,
            CandidateRejectionCode::DuplicateIdentity,
            "duplicate_endpoint_identity"
        );
        return candidate;
    }
    if (
        !configurationHasUniqueEndpoint(input.configuration, input.output)) {
        reject(
            &candidate,
            CandidateRejectionCode::OutputEndpointInvalid,
            "output_configuration_identity_invalid"
        );
        return candidate;
    }

    candidate.syncMode = syncModeFor(input.output);
    if (candidate.syncMode == CandidateSyncMode::None ||
        input.output.usageType() != kUsageData) {
        reject(
            &candidate,
            CandidateRejectionCode::OutputEndpointInvalid,
            "output_sync_or_usage_invalid"
        );
        return candidate;
    }
    if (!uac2EndpointPacketSizeValidForSpeed(
            input.busSpeed,
            input.output.rawMaxPacketSize
        )) {
        reject(
            &candidate,
            CandidateRejectionCode::OutputEndpointInvalid,
            "output_packet_size_invalid_for_speed"
        );
        return candidate;
    }

    FeedbackResolverPolicy outputPolicy = request.feedbackPolicy;
    outputPolicy.requireAsynchronousOutput =
        candidate.syncMode == CandidateSyncMode::Asynchronous;
    std::string outputReason;
    const DescriptorValidationStatus outputStatus = validateOutputEndpointSnapshot(
        input.output,
        outputPolicy,
        &outputReason
    );
    if (outputStatus != DescriptorValidationStatus::Valid) {
        reject(
            &candidate,
            mapEndpointStatus(outputStatus),
            outputReason.empty() ? "output_endpoint_invalid" : outputReason
        );
        return candidate;
    }
    if (!input.capacityEvidence.verified ||
        input.capacityEvidence.maximumRequiredPacketBytes <= 0) {
        reject(
            &candidate,
            CandidateRejectionCode::CapacityUnverified,
            "output_capacity_evidence_unverified"
        );
        return candidate;
    }
    if (input.capacityEvidence.maximumRequiredPacketBytes >
        input.output.effectiveMaxPacketBytes) {
        reject(
            &candidate,
            CandidateRejectionCode::CapacityInsufficient,
            "output_capacity_insufficient"
        );
        return candidate;
    }

    candidate.clockGraph = resolveClockGraph(
        input.clockFunctions,
        input.format.terminalLink
    );
    if (candidate.clockGraph.status != ClockGraphStatus::Valid) {
        reject(
            &candidate,
            CandidateRejectionCode::ClockGraphInvalid,
            std::string("clock_graph:") + clockGraphStatusName(candidate.clockGraph.status) +
                ":" + candidate.clockGraph.reason
        );
        return candidate;
    }
    if (candidate.clockGraph.audioControlInterface != input.audioControlInterface) {
        reject(
            &candidate,
            CandidateRejectionCode::ClockFunctionMismatch,
            "clock_graph_audio_control_interface_mismatch"
        );
        return candidate;
    }
    if (candidate.clockGraph.validity == ClockValidityState::Unchecked) {
        reject(
            &candidate,
            CandidateRejectionCode::ClockGraphInvalid,
            "clock_source_validity_unverified"
        );
        return candidate;
    }
    if (candidate.clockGraph.validity == ClockValidityState::Invalid ||
        candidate.clockGraph.validity == ClockValidityState::IoError) {
        reject(
            &candidate,
            CandidateRejectionCode::ClockGraphInvalid,
            "clock_source_validity_invalid"
        );
        return candidate;
    }

    FeedbackEndpointResolution feedback;
    if (candidate.syncMode == CandidateSyncMode::Asynchronous) {
        FeedbackResolverPolicy feedbackPolicy = request.feedbackPolicy;
        feedbackPolicy.timing = input.feedbackTiming;
        feedbackPolicy.nonStandardAssociationProfileId =
            input.nonStandardFeedbackAssociationProfileId;
        feedback = resolveExplicitFeedbackEndpoint(
            input.configuration,
            input.output,
            feedbackPolicy
        );
        candidate.feedbackMode = feedback.mode;
        candidate.feedbackAssociation = feedback.association;
        candidate.feedbackTimingProfileId = feedback.timingProfileId;
        candidate.feedbackAssociationProfileId = feedback.associationProfileId;
        if (feedback.status != DescriptorValidationStatus::Valid) {
            CandidateRejectionCode code = CandidateRejectionCode::FeedbackInvalid;
            switch (feedback.status) {
                case DescriptorValidationStatus::Missing:
                    code = CandidateRejectionCode::FeedbackMissing;
                    break;
                case DescriptorValidationStatus::Ambiguous:
                    code = CandidateRejectionCode::FeedbackAmbiguous;
                    break;
                case DescriptorValidationStatus::AssociationUnverified:
                case DescriptorValidationStatus::QuirkRequired:
                    code = CandidateRejectionCode::FeedbackAssociationUnverified;
                    break;
                case DescriptorValidationStatus::TimingProfileUnknown:
                    code = CandidateRejectionCode::TimingProfileUnknown;
                    break;
                case DescriptorValidationStatus::CapacityUnknown:
                    code = CandidateRejectionCode::CapacityUnverified;
                    break;
                case DescriptorValidationStatus::Unsupported:
                    code = CandidateRejectionCode::ImplicitFeedbackUnsupported;
                    break;
                default:
                    break;
            }
            reject(
                &candidate,
                code,
                feedback.reason.empty() ? "feedback_endpoint_invalid" : feedback.reason
            );
            return candidate;
        }
        if (!uac2EndpointPacketSizeValidForSpeed(
                input.busSpeed,
                feedback.feedback.rawMaxPacketSize
            )) {
            reject(
                &candidate,
                CandidateRejectionCode::FeedbackInvalid,
                "feedback_packet_size_invalid_for_speed"
            );
            return candidate;
        }
        candidate.feedback = feedback.feedback;
    }

    std::string claimReason;
    if (!buildClaimPlan(input, feedback, &candidate.claimPlan, &claimReason)) {
        reject(
            &candidate,
            claimReason == "feedback_same_interface_alt_conflict" ||
                    claimReason == "feedback_audio_control_interface_conflict" ||
                    claimReason == "feedback_interface_alt_conflict" ||
                    claimReason == "claim_interface_alt_conflict"
                ? CandidateRejectionCode::InterfaceAlternateConflict
                : CandidateRejectionCode::ClaimPlanInvalid,
            claimReason.empty() ? "claim_plan_invalid" : claimReason
        );
        return candidate;
    }

    candidate.hardEligible = true;
    candidate.rejection = CandidateRejectionCode::None;
    candidate.softScore = scoreCandidate(candidate, input.sampleRateControl);
    if (candidate.syncMode == CandidateSyncMode::Asynchronous &&
        candidate.feedbackMode == FeedbackMode::Explicit) {
        candidate.decision = CandidateDecision::FeatureDisabled;
        candidate.reason = "async_explicit_candidate_validated_feature_disabled";
    } else {
        candidate.decision = CandidateDecision::Eligible;
        candidate.reason = "candidate_eligible";
    }
    return candidate;
}

} // namespace

Uac2CandidateListResult buildUac2CandidateList(
    const std::vector<Uac2CandidateInput>& inputs,
    const Uac2CandidateRequest& request
) {
    Uac2CandidateListResult result;
    result.candidates.reserve(inputs.size());
    for (const Uac2CandidateInput& input : inputs) {
        result.candidates.push_back(buildCandidate(input, request));
    }

    std::map<std::string, size_t> identityCounts;
    for (const Uac2StreamingCandidate& candidate : result.candidates) {
        if (!candidate.candidateId.empty()) {
            ++identityCounts[candidate.candidateId];
        }
    }
    for (Uac2StreamingCandidate& candidate : result.candidates) {
        const auto count = identityCounts.find(candidate.candidateId);
        if (count != identityCounts.end() && count->second > 1U) {
            reject(
                &candidate,
                CandidateRejectionCode::DuplicateIdentity,
                "duplicate_candidate_identity"
            );
        }
    }

    for (size_t index = 0; index < result.candidates.size(); ++index) {
        const Uac2StreamingCandidate& candidate = result.candidates[index];
        if (candidate.decision != CandidateDecision::Eligible) {
            continue;
        }
        if (result.bestCandidateIndex < 0 ||
            candidate.softScore > result.candidates[
                static_cast<size_t>(result.bestCandidateIndex)
            ].softScore) {
            result.bestCandidateIndex = static_cast<int>(index);
        }
    }
    return result;
}

const char* candidateDecisionName(CandidateDecision decision) {
    switch (decision) {
        case CandidateDecision::Rejected:
            return "rejected";
        case CandidateDecision::Eligible:
            return "eligible";
        case CandidateDecision::FeatureDisabled:
            return "feature_disabled";
    }
    return "unknown";
}

const char* candidateRejectionCodeName(CandidateRejectionCode code) {
    switch (code) {
        case CandidateRejectionCode::None:
            return "none";
        case CandidateRejectionCode::InvalidInput:
            return "invalid_input";
        case CandidateRejectionCode::DuplicateIdentity:
            return "duplicate_identity";
        case CandidateRejectionCode::ProtocolMismatch:
            return "protocol_mismatch";
        case CandidateRejectionCode::AudioFunctionUnbound:
            return "audio_function_unbound";
        case CandidateRejectionCode::FormatMismatch:
            return "format_mismatch";
        case CandidateRejectionCode::SampleRateUnverified:
            return "sample_rate_unverified";
        case CandidateRejectionCode::ClockGraphInvalid:
            return "clock_graph_invalid";
        case CandidateRejectionCode::ClockFunctionMismatch:
            return "clock_function_mismatch";
        case CandidateRejectionCode::OutputEndpointInvalid:
            return "output_endpoint_invalid";
        case CandidateRejectionCode::CapacityUnverified:
            return "capacity_unverified";
        case CandidateRejectionCode::CapacityInsufficient:
            return "capacity_insufficient";
        case CandidateRejectionCode::FeedbackMissing:
            return "feedback_missing";
        case CandidateRejectionCode::FeedbackAmbiguous:
            return "feedback_ambiguous";
        case CandidateRejectionCode::FeedbackInvalid:
            return "feedback_invalid";
        case CandidateRejectionCode::FeedbackAssociationUnverified:
            return "feedback_association_unverified";
        case CandidateRejectionCode::TimingProfileUnknown:
            return "timing_profile_unknown";
        case CandidateRejectionCode::ImplicitFeedbackUnsupported:
            return "implicit_feedback_unsupported";
        case CandidateRejectionCode::ClaimPlanInvalid:
            return "claim_plan_invalid";
        case CandidateRejectionCode::InterfaceAlternateConflict:
            return "interface_alternate_conflict";
    }
    return "unknown";
}

const char* candidateSyncModeName(CandidateSyncMode mode) {
    switch (mode) {
        case CandidateSyncMode::None:
            return "none";
        case CandidateSyncMode::Asynchronous:
            return "asynchronous";
        case CandidateSyncMode::Adaptive:
            return "adaptive";
        case CandidateSyncMode::Synchronous:
            return "synchronous";
    }
    return "unknown";
}

const char* claimInterfaceKindName(ClaimInterfaceKind kind) {
    switch (kind) {
        case ClaimInterfaceKind::AudioControl:
            return "audio_control";
        case ClaimInterfaceKind::AudioStreamingOutput:
            return "audio_streaming_output";
        case ClaimInterfaceKind::AudioStreamingFeedback:
            return "audio_streaming_feedback";
    }
    return "unknown";
}

} // namespace neri::usb::uac2
