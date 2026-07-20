#include "usb/feedback/usb_explicit_feedback_runtime.h"

#include <algorithm>
#include <limits>

namespace neri::usb::feedback {
namespace {

constexpr uint32_t kMaximumConsecutiveTransferErrors = 8;
constexpr int64_t kMinimumHardHoldoverNs = 500'000'000;
constexpr uint16_t kMinimumHardHoldoverPeriods = 64;

uint16_t hardHoldoverPeriods(int64_t expectedReportPeriodNs) {
    if (expectedReportPeriodNs <= 0) {
        return kMinimumHardHoldoverPeriods;
    }
    const uint64_t expectedPeriod = static_cast<uint64_t>(expectedReportPeriodNs);
    const uint64_t durationPeriods =
        (static_cast<uint64_t>(kMinimumHardHoldoverNs) + expectedPeriod - 1U) /
        expectedPeriod;
    const uint64_t boundedPeriods = std::clamp<uint64_t>(
        std::max<uint64_t>(durationPeriods, kMinimumHardHoldoverPeriods),
        kMinimumHardHoldoverPeriods,
        std::numeric_limits<uint16_t>::max()
    );
    return static_cast<uint16_t>(boundedPeriods);
}

FeedbackEstimatorConfig estimatorConfig() {
    return FeedbackEstimatorConfig {
        100'000,
        10'000,
        2'000,
        5,
        2,
        3
    };
}

FeedbackClockConfig clockConfig(int64_t expectedReportPeriodNs) {
    return FeedbackClockConfig {
        expectedReportPeriodNs,
        64,
        8,
        hardHoldoverPeriods(expectedReportPeriodNs),
        3,
        2'000,
        1'000
    };
}

int64_t longGapReacquisitionThresholdNs(int64_t expectedReportPeriodNs) {
    if (expectedReportPeriodNs <= 0) {
        return std::numeric_limits<int64_t>::max();
    }
    const FeedbackClockConfig config = clockConfig(expectedReportPeriodNs);
    const uint64_t periods = static_cast<uint64_t>(config.softMissPeriods) +
        static_cast<uint64_t>(config.hardHoldoverPeriods);
    const uint64_t expectedPeriod = static_cast<uint64_t>(expectedReportPeriodNs);
    const uint64_t maximum = static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    if (periods == 0 || expectedPeriod > maximum / periods) {
        return std::numeric_limits<int64_t>::max();
    }
    return static_cast<int64_t>(expectedPeriod * periods);
}

bool configurationValid(const ExplicitFeedbackRuntimeConfig& config) {
    return config.generation > 0 &&
        isFeedbackDecodeProfileSupported(config.decodeProfile) &&
        config.nominalRateQ32 > 0 &&
        config.nominalRateQ32 <= kMaxSupportedRateQ32 &&
        config.expectedReportPeriodNs > 0 &&
        config.frameBytes > 0 &&
        config.endpointCapacityBytes >= config.frameBytes &&
        config.bootstrapPacketLimit > 0;
}

} // namespace

bool ExplicitFeedbackRuntime::configure(
    const ExplicitFeedbackRuntimeConfig& config
) {
    std::lock_guard<std::mutex> guard(mutex_);
    configured_ = false;
    running_ = false;
    reusableAfterStop_ = false;
    state_ = ExplicitFeedbackRuntimeState::Disabled;
    failure_ = ExplicitFeedbackRuntimeFailure::None;
    validPackets_ = 0;
    invalidPackets_ = 0;
    zeroLengthPackets_ = 0;
    transferErrors_ = 0;
    staleCallbacks_ = 0;
    consecutiveTransferErrors_ = 0;
    longGapReacquisitions_ = 0;
    lastRawValue_ = 0;
    lastPayloadBytes_ = 0;
    hasTerminalGateSnapshot_ = false;
    terminalGateSnapshot_ = {};

    if (!configurationValid(config) ||
        !estimator_.configure(estimatorConfig(), config.nominalRateQ32) ||
        !gate_.configure(
            clockConfig(config.expectedReportPeriodNs),
            config.nominalRateQ32,
            config.frameBytes,
            config.endpointCapacityBytes,
            config.bootstrapPacketLimit
        )) {
        state_ = ExplicitFeedbackRuntimeState::Failed;
        failure_ = ExplicitFeedbackRuntimeFailure::InvalidConfiguration;
        return false;
    }

    config_ = config;
    configured_ = true;
    state_ = ExplicitFeedbackRuntimeState::Ready;
    return true;
}

bool ExplicitFeedbackRuntime::start(int64_t startedAtNs) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!configured_ || running_ || state_ != ExplicitFeedbackRuntimeState::Ready ||
        gate_.start(startedAtNs) != FeedbackMathStatus::Ok) {
        return failLocked(ExplicitFeedbackRuntimeFailure::InternalInvariant);
    }
    running_ = true;
    reusableAfterStop_ = false;
    hasTerminalGateSnapshot_ = false;
    terminalGateSnapshot_ = {};
    state_ = ExplicitFeedbackRuntimeState::Acquiring;
    return true;
}

bool ExplicitFeedbackRuntime::tick(int64_t nowNs) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!running_) {
        return state_ != ExplicitFeedbackRuntimeState::Failed;
    }
    const FeedbackMathStatus status = gate_.onTick(nowNs);
    if (gate_.snapshot().terminalFailure || status == FeedbackMathStatus::NotReady) {
        return failLocked(ExplicitFeedbackRuntimeFailure::FeedbackClock);
    }
    if (status != FeedbackMathStatus::Ok) {
        return failLocked(ExplicitFeedbackRuntimeFailure::InternalInvariant);
    }
    updateStateLocked();
    return true;
}

StreamGatePacket ExplicitFeedbackRuntime::nextPacket(bool playerPcmAvailable) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!running_ || state_ == ExplicitFeedbackRuntimeState::Failed) {
        StreamGatePacket result;
        result.status = state_ == ExplicitFeedbackRuntimeState::Failed
            ? StreamGatePacketStatus::TerminalFailure
            : StreamGatePacketStatus::NotRunning;
        return result;
    }
    StreamGatePacket result = gate_.next(playerPcmAvailable);
    if (result.status == StreamGatePacketStatus::TerminalFailure) {
        failLocked(
            result.packet.status == FeedbackMathStatus::CapacityExceeded
                ? ExplicitFeedbackRuntimeFailure::PacketCapacity
                : ExplicitFeedbackRuntimeFailure::FeedbackClock
        );
    }
    updateStateLocked();
    return result;
}

void ExplicitFeedbackRuntime::stop() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (state_ != ExplicitFeedbackRuntimeState::Stopped) {
        const FeedbackClockState clockState = gate_.snapshot().clock.state;
        reusableAfterStop_ = state_ != ExplicitFeedbackRuntimeState::Failed &&
            validPackets_ > 0 &&
            (clockState == FeedbackClockState::Locked ||
                clockState == FeedbackClockState::Holdover);
    }
    running_ = false;
    gate_.stop();
    if (state_ != ExplicitFeedbackRuntimeState::Failed) {
        state_ = ExplicitFeedbackRuntimeState::Stopped;
    }
}

bool ExplicitFeedbackRuntime::onFeedbackInCompletion(
    const FeedbackInCompletion& completion
) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (completion.status == FeedbackInCompletionStatus::StaleGeneration ||
        completion.generation != config_.generation) {
        incrementSaturated(&staleCallbacks_);
        return false;
    }
    if (!running_) {
        return false;
    }

    switch (completion.status) {
        case FeedbackInCompletionStatus::Completed: {
            consecutiveTransferErrors_ = 0;
            if (completion.payloadBytes == 0 && config_.zeroLengthFeedbackPermitted) {
                incrementSaturated(&zeroLengthPackets_);
                lastPayloadBytes_ = 0;
                const FeedbackMathStatus status = gate_.onTick(completion.receivedAtNs);
                if (gate_.snapshot().terminalFailure ||
                    status == FeedbackMathStatus::NotReady) {
                    return failLocked(ExplicitFeedbackRuntimeFailure::FeedbackClock);
                }
                updateStateLocked();
                return true;
            }

            const FeedbackDecodeResult decoded = decodeFeedbackSample(
                config_.decodeProfile,
                FeedbackDecodeInput {
                    completion.payload,
                    completion.payloadBytes,
                    completion.receivedAtNs,
                    completion.sequence
                }
            );
            if (decoded.status != FeedbackMathStatus::Ok) {
                incrementSaturated(&invalidPackets_);
                return handleRejectedSampleLocked(completion.receivedAtNs);
            }

            lastRawValue_ = decoded.sample.rawValue;
            lastPayloadBytes_ = static_cast<uint32_t>(
                decoded.sample.payloadBytesActual
            );

            if (shouldReacquireAfterLongGapLocked(completion.receivedAtNs) &&
                !restartAcquisitionLocked(completion.receivedAtNs)) {
                return failLocked(ExplicitFeedbackRuntimeFailure::InternalInvariant);
            }

            const FeedbackEstimateResult estimate = estimator_.ingest(
                decoded.sample.normalized
            );
            if (estimate.status != FeedbackEstimateStatus::Accepted) {
                incrementSaturated(&invalidPackets_);
                return handleRejectedSampleLocked(completion.receivedAtNs);
            }
            incrementSaturated(&validPackets_);
            const FeedbackMathStatus status = gate_.onEstimate(
                estimate,
                completion.receivedAtNs
            );
            if (gate_.snapshot().terminalFailure ||
                (status != FeedbackMathStatus::Ok &&
                 status != FeedbackMathStatus::NotReady)) {
                return failLocked(ExplicitFeedbackRuntimeFailure::FeedbackClock);
            }
            updateStateLocked();
            return true;
        }
        case FeedbackInCompletionStatus::Cancelled:
            return failLocked(ExplicitFeedbackRuntimeFailure::TransferCancelled);
        case FeedbackInCompletionStatus::NoDevice:
            return failLocked(ExplicitFeedbackRuntimeFailure::DeviceDetached);
        case FeedbackInCompletionStatus::TransferError:
        case FeedbackInCompletionStatus::PacketError:
        case FeedbackInCompletionStatus::InvalidLength:
            incrementSaturated(&transferErrors_);
            if (consecutiveTransferErrors_ != std::numeric_limits<uint32_t>::max()) {
                ++consecutiveTransferErrors_;
            }
            if (consecutiveTransferErrors_ >= kMaximumConsecutiveTransferErrors) {
                return failLocked(ExplicitFeedbackRuntimeFailure::TransferFailed);
            }
            return handleRejectedSampleLocked(completion.receivedAtNs);
        case FeedbackInCompletionStatus::StaleGeneration:
            return false;
    }
    return failLocked(ExplicitFeedbackRuntimeFailure::InternalInvariant);
}

ExplicitFeedbackRuntimeSnapshot ExplicitFeedbackRuntime::snapshot() const {
    std::lock_guard<std::mutex> guard(mutex_);
    const FeedbackStreamGateSnapshot gate = hasTerminalGateSnapshot_
        ? terminalGateSnapshot_
        : gate_.snapshot();
    return ExplicitFeedbackRuntimeSnapshot {
        state_,
        failure_,
        config_.generation,
        configured_,
        running_,
        gate.clock.state == FeedbackClockState::Locked,
        gate.realPcmReleased,
        reusableAfterStop_,
        state_ == ExplicitFeedbackRuntimeState::Failed || gate.terminalFailure,
        validPackets_,
        invalidPackets_,
        zeroLengthPackets_,
        transferErrors_,
        staleCallbacks_,
        consecutiveTransferErrors_,
        longGapReacquisitions_,
        lastRawValue_,
        lastPayloadBytes_,
        estimator_.snapshot(),
        gate
    };
}

bool ExplicitFeedbackRuntime::failLocked(
    ExplicitFeedbackRuntimeFailure failure
) {
    if (failure_ == ExplicitFeedbackRuntimeFailure::None) {
        failure_ = failure;
    }
    if (!hasTerminalGateSnapshot_) {
        terminalGateSnapshot_ = gate_.snapshot();
        hasTerminalGateSnapshot_ = true;
    }
    running_ = false;
    reusableAfterStop_ = false;
    state_ = ExplicitFeedbackRuntimeState::Failed;
    gate_.stop();
    return false;
}

bool ExplicitFeedbackRuntime::shouldReacquireAfterLongGapLocked(
    int64_t receivedAtNs
) const {
    const FeedbackStreamGateSnapshot snapshot = gate_.snapshot();
    const int64_t lastValidSampleNs = snapshot.clock.lastValidSampleNs;
    if (!snapshot.running || !snapshot.realPcmReleased || receivedAtNs < 0 ||
        lastValidSampleNs < 0 || receivedAtNs < lastValidSampleNs) {
        return false;
    }
    const int64_t thresholdNs = longGapReacquisitionThresholdNs(
        config_.expectedReportPeriodNs
    );
    return receivedAtNs - lastValidSampleNs >= thresholdNs;
}

bool ExplicitFeedbackRuntime::restartAcquisitionLocked(int64_t startedAtNs) {
    estimator_.reset();
    gate_.stop();
    if (!gate_.configure(
            clockConfig(config_.expectedReportPeriodNs),
            config_.nominalRateQ32,
            config_.frameBytes,
            config_.endpointCapacityBytes,
            config_.bootstrapPacketLimit
        ) || gate_.start(startedAtNs) != FeedbackMathStatus::Ok) {
        return false;
    }
    incrementSaturated(&longGapReacquisitions_);
    consecutiveTransferErrors_ = 0;
    reusableAfterStop_ = false;
    hasTerminalGateSnapshot_ = false;
    terminalGateSnapshot_ = {};
    state_ = ExplicitFeedbackRuntimeState::Acquiring;
    return true;
}

void ExplicitFeedbackRuntime::updateStateLocked() {
    const FeedbackStreamGateSnapshot snapshot = gate_.snapshot();
    if (snapshot.terminalFailure) {
        failLocked(ExplicitFeedbackRuntimeFailure::FeedbackClock);
    } else if (snapshot.realPcmReleased) {
        state_ = ExplicitFeedbackRuntimeState::Streaming;
    } else if (running_) {
        state_ = ExplicitFeedbackRuntimeState::Acquiring;
    }
}

bool ExplicitFeedbackRuntime::handleRejectedSampleLocked(int64_t receivedAtNs) {
    const FeedbackMathStatus status = gate_.onRejectedSample(receivedAtNs);
    if (gate_.snapshot().terminalFailure || status == FeedbackMathStatus::NotReady) {
        return failLocked(ExplicitFeedbackRuntimeFailure::FeedbackClock);
    }
    if (status != FeedbackMathStatus::Ok) {
        return failLocked(ExplicitFeedbackRuntimeFailure::InternalInvariant);
    }
    updateStateLocked();
    return true;
}

void ExplicitFeedbackRuntime::incrementSaturated(uint64_t* value) {
    if (value != nullptr && *value != std::numeric_limits<uint64_t>::max()) {
        ++(*value);
    }
}

const char* explicitFeedbackRuntimeStateName(ExplicitFeedbackRuntimeState state) {
    switch (state) {
        case ExplicitFeedbackRuntimeState::Disabled: return "disabled";
        case ExplicitFeedbackRuntimeState::Ready: return "ready";
        case ExplicitFeedbackRuntimeState::Acquiring: return "acquiring";
        case ExplicitFeedbackRuntimeState::Streaming: return "streaming";
        case ExplicitFeedbackRuntimeState::Stopped: return "stopped";
        case ExplicitFeedbackRuntimeState::Failed: return "failed";
    }
    return "unknown";
}

const char* explicitFeedbackRuntimeFailureName(
    ExplicitFeedbackRuntimeFailure failure
) {
    switch (failure) {
        case ExplicitFeedbackRuntimeFailure::None: return "none";
        case ExplicitFeedbackRuntimeFailure::InvalidConfiguration:
            return "invalid_configuration";
        case ExplicitFeedbackRuntimeFailure::TransferCancelled:
            return "transfer_cancelled";
        case ExplicitFeedbackRuntimeFailure::TransferFailed:
            return "transfer_failed";
        case ExplicitFeedbackRuntimeFailure::DeviceDetached:
            return "device_detached";
        case ExplicitFeedbackRuntimeFailure::FeedbackClock:
            return "feedback_clock";
        case ExplicitFeedbackRuntimeFailure::PacketCapacity:
            return "packet_capacity";
        case ExplicitFeedbackRuntimeFailure::InternalInvariant:
            return "internal_invariant";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
