#include "usb/feedback/usb_explicit_feedback_engine.h"

#include "usb/feedback/usb_feedback_rate_math.h"

#include <array>

namespace neri::usb::feedback {
namespace {

constexpr uint32_t kMaximumTransferCount = 64;
constexpr uint32_t kMaximumBootstrapPackets = 4096;
constexpr size_t kWideWordCount = 6;

struct WideUnsigned {
    explicit WideUnsigned(uint64_t value) {
        words[0] = static_cast<uint32_t>(value);
        words[1] = static_cast<uint32_t>(value >> 32U);
    }

    bool multiply(uint32_t factor) {
        uint64_t carry = 0;
        for (uint32_t& word : words) {
            const uint64_t product =
                static_cast<uint64_t>(word) * factor + carry;
            word = static_cast<uint32_t>(product);
            carry = product >> 32U;
        }
        return carry == 0;
    }

    bool multiplyPowerOfTwo(uint32_t exponent) {
        for (uint32_t index = 0; index < exponent; ++index) {
            if (!multiply(2U)) {
                return false;
            }
        }
        return true;
    }

    std::array<uint32_t, kWideWordCount> words {};
};

int compareWideUnsigned(const WideUnsigned& left, const WideUnsigned& right) {
    for (size_t index = kWideWordCount; index > 0; --index) {
        const uint32_t leftWord = left.words[index - 1U];
        const uint32_t rightWord = right.words[index - 1U];
        if (leftWord != rightWord) {
            return leftWord < rightWord ? -1 : 1;
        }
    }
    return 0;
}

uint64_t payloadMask(uint8_t payloadBytes) {
    if (payloadBytes == static_cast<uint8_t>(sizeof(uint64_t))) {
        return UINT64_MAX;
    }
    return (UINT64_C(1) << (static_cast<uint32_t>(payloadBytes) * 8U)) - 1U;
}

bool highestSetBitIndex(uint64_t value, uint32_t* output) {
    if (value == 0 || output == nullptr) {
        return false;
    }
    for (uint32_t index = 64U; index > 0U; --index) {
        const uint32_t shift = index - 1U;
        if ((value & (UINT64_C(1) << shift)) != 0) {
            *output = shift;
            return true;
        }
    }
    return false;
}

bool compareRawRateToNominal(
    const FeedbackDecodeProfile& profile,
    uint64_t rawValue,
    FeedbackRateQ32 nominalRateQ32,
    int* comparison
) {
    if (rawValue == 0 || nominalRateQ32 == 0 || comparison == nullptr) {
        return false;
    }

    // Fixed-width products keep this setup-time check portable across Android ABIs
    WideUnsigned normalizedNumerator(rawValue);
    WideUnsigned nominalDenominator(nominalRateQ32);
    if (!normalizedNumerator.multiply(profile.sourceIntervalsPerSecond) ||
        !normalizedNumerator.multiply(profile.scaleNumerator) ||
        !normalizedNumerator.multiplyPowerOfTwo(32U) ||
        !nominalDenominator.multiply(profile.audioIntervalsPerSecond) ||
        !nominalDenominator.multiply(profile.scaleDenominator) ||
        !nominalDenominator.multiplyPowerOfTwo(profile.fractionalBits)) {
        return false;
    }
    *comparison = compareWideUnsigned(normalizedNumerator, nominalDenominator);
    return true;
}

bool greatestAllowedRawAtOrBelow(
    const FeedbackDecodeProfile& profile,
    uint64_t upperBound,
    uint64_t* output
) {
    if (upperBound == 0 || output == nullptr) {
        return false;
    }
    const uint64_t rawMask = payloadMask(profile.payloadBytesExpected);
    const uint64_t maximumRaw = rawMask - 1U;
    const uint64_t bounded = upperBound > maximumRaw ? maximumRaw : upperBound;
    const uint64_t forbiddenBits = bounded & profile.requiredZeroMask;
    if (forbiddenBits == 0) {
        *output = bounded;
        return true;
    }

    uint32_t forbiddenIndex = 0;
    if (!highestSetBitIndex(forbiddenBits, &forbiddenIndex)) {
        return false;
    }
    const uint64_t forbiddenBit = UINT64_C(1) << forbiddenIndex;
    const uint64_t lowerBits = forbiddenBit - 1U;
    const uint64_t allowedBits = rawMask & ~profile.requiredZeroMask;
    const uint64_t candidate =
        (bounded & ~(forbiddenBit | lowerBits)) |
        (allowedBits & lowerBits);
    if (candidate == 0 || candidate > maximumRaw) {
        return false;
    }
    *output = candidate;
    return true;
}

bool smallestAllowedRawAtOrAbove(
    const FeedbackDecodeProfile& profile,
    uint64_t lowerBound,
    uint64_t* output
) {
    if (output == nullptr) {
        return false;
    }
    const uint64_t rawMask = payloadMask(profile.payloadBytesExpected);
    const uint64_t maximumRaw = rawMask - 1U;
    const uint64_t bounded = lowerBound == 0 ? 1U : lowerBound;
    if (bounded > maximumRaw) {
        return false;
    }
    const uint64_t forbiddenBits = bounded & profile.requiredZeroMask;
    if (forbiddenBits == 0) {
        *output = bounded;
        return true;
    }

    uint32_t highestForbiddenIndex = 0;
    if (!highestSetBitIndex(forbiddenBits, &highestForbiddenIndex)) {
        return false;
    }
    const uint64_t allowedBits = rawMask & ~profile.requiredZeroMask;
    const uint32_t payloadBitCount =
        static_cast<uint32_t>(profile.payloadBytesExpected) * 8U;
    for (uint32_t index = highestForbiddenIndex + 1U;
         index < payloadBitCount;
         ++index) {
        const uint64_t bit = UINT64_C(1) << index;
        if ((allowedBits & bit) == 0 || (bounded & bit) != 0) {
            continue;
        }
        const uint64_t lowerBits = bit - 1U;
        const uint64_t candidate = (bounded & ~(bit | lowerBits)) | bit;
        if (candidate <= maximumRaw) {
            *output = candidate;
            return true;
        }
    }
    return false;
}

bool rawCandidateMatchesNominal(
    const FeedbackDecodeProfile& profile,
    uint64_t rawValue,
    FeedbackRateQ32 nominalRateQ32,
    uint32_t hardDeviationPpm
) {
    std::array<uint8_t, sizeof(uint64_t)> payload {};
    uint64_t remaining = rawValue;
    for (uint8_t index = 0; index < profile.payloadBytesExpected; ++index) {
        payload[index] = static_cast<uint8_t>(remaining);
        remaining >>= 8U;
    }
    const FeedbackDecodeResult decoded = decodeFeedbackSample(
        profile,
        FeedbackDecodeInput {
            payload.data(),
            static_cast<size_t>(profile.payloadBytesExpected),
            0,
            1
        }
    );
    if (decoded.status != FeedbackMathStatus::Ok) {
        return false;
    }
    uint32_t deviationPpm = 0;
    return computeRateDeltaPpm(
        decoded.sample.normalized.rateQ32,
        nominalRateQ32,
        &deviationPpm
    ) == FeedbackMathStatus::Ok && deviationPpm <= hardDeviationPpm;
}

bool profileCanRepresentNominalRate(
    const FeedbackDecodeProfile& profile,
    FeedbackRateQ32 nominalRateQ32,
    uint32_t hardDeviationPpm
) {
    if (!isFeedbackDecodeProfileSupported(profile) || nominalRateQ32 == 0 ||
        hardDeviationPpm == 0) {
        return false;
    }
    const uint64_t maximumRaw = payloadMask(profile.payloadBytesExpected) - 1U;
    uint64_t lowerBound = 1U;
    uint64_t upperBound = maximumRaw;
    while (lowerBound < upperBound) {
        const uint64_t candidate = lowerBound +
            (upperBound - lowerBound) / 2U;
        int comparison = 0;
        if (!compareRawRateToNominal(
                profile,
                candidate,
                nominalRateQ32,
                &comparison
            )) {
            return false;
        }
        if (comparison < 0) {
            lowerBound = candidate + 1U;
        } else {
            upperBound = candidate;
        }
    }

    std::array<uint64_t, 3> candidates {};
    size_t candidateCount = 0;
    const auto addCandidate = [&candidates, &candidateCount](uint64_t candidate) {
        for (size_t index = 0; index < candidateCount; ++index) {
            if (candidates[index] == candidate) {
                return;
            }
        }
        if (candidateCount < candidates.size()) {
            candidates[candidateCount] = candidate;
            ++candidateCount;
        }
    };
    uint64_t candidate = 0;
    if (greatestAllowedRawAtOrBelow(profile, lowerBound, &candidate)) {
        addCandidate(candidate);
    }
    if (lowerBound > 1U && greatestAllowedRawAtOrBelow(
            profile,
            lowerBound - 1U,
            &candidate
        )) {
        addCandidate(candidate);
    }
    if (smallestAllowedRawAtOrAbove(profile, lowerBound, &candidate)) {
        addCandidate(candidate);
    }
    for (size_t index = 0; index < candidateCount; ++index) {
        if (rawCandidateMatchesNominal(
                profile,
                candidates[index],
                nominalRateQ32,
                hardDeviationPpm
            )) {
            return true;
        }
    }
    return false;
}

bool ownsActiveGeneration(ExplicitFeedbackEngineState state) {
    return state == ExplicitFeedbackEngineState::Starting ||
        state == ExplicitFeedbackEngineState::Acquiring ||
        state == ExplicitFeedbackEngineState::Streaming ||
        state == ExplicitFeedbackEngineState::Draining ||
        state == ExplicitFeedbackEngineState::Quarantined;
}

} // namespace

bool ExplicitFeedbackEngineResources::empty() const {
    return !interfacesClaimed && !alternateSettingActive &&
        !feedbackTransfersAllocated && !audioTransfersAllocated &&
        !eventLoopStarted;
}

bool ExplicitFeedbackEngine::configurationValid(
    const ExplicitFeedbackEngineConfig& config
) const {
    return config.generation > 0 && config.feedbackTransferCount > 0 &&
        config.feedbackTransferCount <= kMaximumTransferCount &&
        config.audioTransferCount > 0 &&
        config.audioTransferCount <= kMaximumTransferCount &&
        config.maxAheadIntervals > 0 &&
        config.audioTransferCount <= config.maxAheadIntervals &&
        config.nominalRateQ32 > 0 &&
        config.nominalRateQ32 <= kMaxSupportedRateQ32 &&
        config.frameBytes > 0 && config.endpointCapacityBytes >= config.frameBytes &&
        config.bootstrapPacketLimit > 0 &&
        config.bootstrapPacketLimit <= kMaximumBootstrapPackets &&
        isFeedbackDecodeProfileSupported(config.decodeProfile) &&
        profileCanRepresentNominalRate(
            config.decodeProfile,
            config.nominalRateQ32,
            config.estimatorConfig.hardDeviationPpm
        ) &&
        config.estimatorConfig.stableSampleCount <
            config.clockConfig.acquireTimeoutPeriods;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::configure(
    const ExplicitFeedbackEngineConfig& config,
    ExplicitFeedbackBackend* backend
) {
    if (!resources_.empty() || ownsActiveGeneration(state_)) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    gate_.stop();
    estimator_.reset();
    config_ = config;
    backend_ = backend;
    lifecycle_ = StreamGenerationLifecycle(config.generation);
    resources_ = {};
    configured_ = false;
    playerPcmAvailable_ = false;
    cancellationRequested_ = false;
    failure_ = ExplicitFeedbackFailureReason::None;
    feedbackPacketsValid_ = 0;
    feedbackPacketsInvalid_ = 0;
    feedbackPacketsZeroLength_ = 0;
    audioPacketsSubmitted_ = 0;
    zeroPacketsSubmitted_ = 0;
    playerPacketsSubmitted_ = 0;
    cleanupErrors_ = 0;

    if (!config.enabled) {
        state_ = ExplicitFeedbackEngineState::Disabled;
        failure_ = ExplicitFeedbackFailureReason::FeatureDisabled;
        return ExplicitFeedbackEngineResult::FeatureDisabled;
    }
    if (backend == nullptr || !configurationValid(config) ||
        !estimator_.configure(config.estimatorConfig, config.nominalRateQ32) ||
        !gate_.configure(
            config.clockConfig,
            config.nominalRateQ32,
            config.frameBytes,
            config.endpointCapacityBytes,
            config.bootstrapPacketLimit
        )) {
        state_ = ExplicitFeedbackEngineState::Failed;
        failure_ = ExplicitFeedbackFailureReason::InvalidConfiguration;
        return ExplicitFeedbackEngineResult::InvalidArgument;
    }
    configured_ = true;
    state_ = ExplicitFeedbackEngineState::Ready;
    return ExplicitFeedbackEngineResult::Ok;
}

ExplicitFeedbackEngineSnapshot ExplicitFeedbackEngine::snapshot() const {
    return ExplicitFeedbackEngineSnapshot {
        state_, failure_, config_.generation, configured_, playerPcmAvailable_,
        cancellationRequested_, feedbackPacketsValid_, feedbackPacketsInvalid_,
        feedbackPacketsZeroLength_, audioPacketsSubmitted_, zeroPacketsSubmitted_,
        playerPacketsSubmitted_, cleanupErrors_, lifecycle_.audioOut(),
        lifecycle_.feedbackIn(), estimator_.snapshot(), gate_.snapshot(), resources_
    };
}

const char* explicitFeedbackEngineStateName(ExplicitFeedbackEngineState state) {
    switch (state) {
        case ExplicitFeedbackEngineState::Disabled: return "disabled";
        case ExplicitFeedbackEngineState::Ready: return "ready";
        case ExplicitFeedbackEngineState::Starting: return "starting";
        case ExplicitFeedbackEngineState::Acquiring: return "acquiring";
        case ExplicitFeedbackEngineState::Streaming: return "streaming";
        case ExplicitFeedbackEngineState::Draining: return "draining";
        case ExplicitFeedbackEngineState::Quarantined: return "quarantined";
        case ExplicitFeedbackEngineState::Stopped: return "stopped";
        case ExplicitFeedbackEngineState::Failed: return "failed";
    }
    return "unknown";
}

const char* explicitFeedbackEngineResultName(ExplicitFeedbackEngineResult result) {
    switch (result) {
        case ExplicitFeedbackEngineResult::Ok: return "ok";
        case ExplicitFeedbackEngineResult::FeatureDisabled: return "feature_disabled";
        case ExplicitFeedbackEngineResult::InvalidArgument: return "invalid_argument";
        case ExplicitFeedbackEngineResult::InvalidState: return "invalid_state";
        case ExplicitFeedbackEngineResult::Draining: return "draining";
        case ExplicitFeedbackEngineResult::AwaitingFeedback: return "awaiting_feedback";
        case ExplicitFeedbackEngineResult::RejectedFeedback: return "rejected_feedback";
        case ExplicitFeedbackEngineResult::StaleGeneration: return "stale_generation";
        case ExplicitFeedbackEngineResult::BackendFailure: return "backend_failure";
        case ExplicitFeedbackEngineResult::TerminalFailure: return "terminal_failure";
    }
    return "unknown";
}

const char* explicitFeedbackFailureReasonName(ExplicitFeedbackFailureReason reason) {
    switch (reason) {
        case ExplicitFeedbackFailureReason::None: return "none";
        case ExplicitFeedbackFailureReason::FeatureDisabled: return "feature_disabled";
        case ExplicitFeedbackFailureReason::InvalidConfiguration: return "invalid_configuration";
        case ExplicitFeedbackFailureReason::ClaimInterfaces: return "claim_interfaces";
        case ExplicitFeedbackFailureReason::ActivateAlternateSetting: return "activate_alt";
        case ExplicitFeedbackFailureReason::AllocateFeedbackTransfers: return "allocate_feedback";
        case ExplicitFeedbackFailureReason::AllocateAudioTransfers: return "allocate_audio";
        case ExplicitFeedbackFailureReason::StartEventLoop: return "start_event_loop";
        case ExplicitFeedbackFailureReason::SubmitFeedbackTransfers: return "submit_feedback";
        case ExplicitFeedbackFailureReason::SubmitZeroBootstrap: return "submit_zero_bootstrap";
        case ExplicitFeedbackFailureReason::FeedbackTransfer: return "feedback_transfer";
        case ExplicitFeedbackFailureReason::AudioTransfer: return "audio_transfer";
        case ExplicitFeedbackFailureReason::FeedbackClock: return "feedback_clock";
        case ExplicitFeedbackFailureReason::FeedbackDecode: return "feedback_decode";
        case ExplicitFeedbackFailureReason::DeviceDetached: return "device_detached";
        case ExplicitFeedbackFailureReason::DrainTimeout: return "drain_timeout";
        case ExplicitFeedbackFailureReason::Cleanup: return "cleanup";
        case ExplicitFeedbackFailureReason::InternalInvariant: return "internal_invariant";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
