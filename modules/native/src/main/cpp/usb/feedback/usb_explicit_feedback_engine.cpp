#include "usb/feedback/usb_explicit_feedback_engine.h"

#include <limits>

namespace neri::usb::feedback {
namespace {

bool acceptsCompletions(ExplicitFeedbackEngineState state) {
    return state == ExplicitFeedbackEngineState::Starting ||
        state == ExplicitFeedbackEngineState::Acquiring ||
        state == ExplicitFeedbackEngineState::Streaming ||
        state == ExplicitFeedbackEngineState::Draining ||
        state == ExplicitFeedbackEngineState::Quarantined;
}

bool isDraining(ExplicitFeedbackEngineState state) {
    return state == ExplicitFeedbackEngineState::Draining ||
        state == ExplicitFeedbackEngineState::Quarantined;
}

} // namespace

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::start(int64_t startedAtNs) {
    if (!configured_ || backend_ == nullptr ||
        state_ != ExplicitFeedbackEngineState::Ready || startedAtNs < 0) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    state_ = ExplicitFeedbackEngineState::Starting;
    if (!backend_->claimInterfaces()) {
        return failStart(ExplicitFeedbackFailureReason::ClaimInterfaces);
    }
    resources_.interfacesClaimed = true;
    if (!backend_->activateAlternateSetting()) {
        return failStart(ExplicitFeedbackFailureReason::ActivateAlternateSetting);
    }
    resources_.alternateSettingActive = true;
    if (!backend_->allocateTransfers(
            TransferKind::FeedbackIn,
            config_.feedbackTransferCount
        )) {
        return failStart(ExplicitFeedbackFailureReason::AllocateFeedbackTransfers);
    }
    resources_.feedbackTransfersAllocated = true;
    if (!lifecycle_.allocate(TransferKind::FeedbackIn, config_.feedbackTransferCount)) {
        return failStart(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!backend_->allocateTransfers(TransferKind::AudioOut, config_.audioTransferCount)) {
        return failStart(ExplicitFeedbackFailureReason::AllocateAudioTransfers);
    }
    resources_.audioTransfersAllocated = true;
    if (!lifecycle_.allocate(TransferKind::AudioOut, config_.audioTransferCount)) {
        return failStart(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!backend_->startEventLoop()) {
        return failStart(ExplicitFeedbackFailureReason::StartEventLoop);
    }
    resources_.eventLoopStarted = true;
    if (gate_.start(startedAtNs) != FeedbackMathStatus::Ok) {
        return failStart(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!lifecycle_.submit(TransferKind::FeedbackIn, config_.feedbackTransferCount)) {
        return failStart(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!backend_->submitFeedbackTransfers(config_.feedbackTransferCount)) {
        lifecycle_.failSubmission(TransferKind::FeedbackIn, config_.feedbackTransferCount);
        return failStart(ExplicitFeedbackFailureReason::SubmitFeedbackTransfers);
    }
    const ExplicitFeedbackEngineResult audioResult = fillAudioAheadWindow();
    if (audioResult != ExplicitFeedbackEngineResult::Ok &&
        audioResult != ExplicitFeedbackEngineResult::AwaitingFeedback) {
        if (failure_ == ExplicitFeedbackFailureReason::None) {
            return failStart(ExplicitFeedbackFailureReason::SubmitZeroBootstrap);
        }
        return audioResult;
    }
    state_ = ExplicitFeedbackEngineState::Acquiring;
    return ExplicitFeedbackEngineResult::Ok;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::failStart(
    ExplicitFeedbackFailureReason reason
) {
    failure_ = reason;
    beginDrain();
    return ExplicitFeedbackEngineResult::BackendFailure;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::enterTerminal(
    ExplicitFeedbackFailureReason reason
) {
    if (failure_ == ExplicitFeedbackFailureReason::None) {
        failure_ = reason;
    }
    beginDrain();
    return ExplicitFeedbackEngineResult::TerminalFailure;
}

void ExplicitFeedbackEngine::requestCancellation() {
    if (cancellationRequested_ || backend_ == nullptr) {
        return;
    }
    cancellationRequested_ = true;
    if (lifecycle_.feedbackIn().inFlight > 0) {
        lifecycle_.recordCancelResult(
            TransferKind::FeedbackIn,
            backend_->cancelTransfers(TransferKind::FeedbackIn)
        );
    }
    if (lifecycle_.audioOut().inFlight > 0) {
        lifecycle_.recordCancelResult(
            TransferKind::AudioOut,
            backend_->cancelTransfers(TransferKind::AudioOut)
        );
    }
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::beginDrain() {
    gate_.stop();
    if (lifecycle_.state() == GenerationLifecycleState::Active) {
        lifecycle_.beginStop();
    }
    state_ = ExplicitFeedbackEngineState::Draining;
    requestCancellation();
    return settleWhileDraining();
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::settleWhileDraining() {
    if (lifecycle_.audioOut().inFlight > 0 || lifecycle_.feedbackIn().inFlight > 0) {
        return ExplicitFeedbackEngineResult::Draining;
    }
    return releaseDrainedResources();
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::releaseDrainedResources() {
    if (backend_ == nullptr) {
        state_ = ExplicitFeedbackEngineState::Failed;
        failure_ = ExplicitFeedbackFailureReason::InternalInvariant;
        return ExplicitFeedbackEngineResult::TerminalFailure;
    }
    if (lifecycle_.audioOut().inFlight > 0 || lifecycle_.feedbackIn().inFlight > 0) {
        return ExplicitFeedbackEngineResult::Draining;
    }
    if (lifecycle_.state() == GenerationLifecycleState::Active) {
        lifecycle_.beginStop();
    }
    if (lifecycle_.state() == GenerationLifecycleState::Stopping) {
        lifecycle_.retire();
    }
    if (lifecycle_.state() != GenerationLifecycleState::Reclaimable) {
        failure_ = ExplicitFeedbackFailureReason::InternalInvariant;
        state_ = ExplicitFeedbackEngineState::Quarantined;
        return ExplicitFeedbackEngineResult::TerminalFailure;
    }

    if (resources_.eventLoopStarted) {
        if (!backend_->stopEventLoop()) {
            increment(&cleanupErrors_);
            if (failure_ == ExplicitFeedbackFailureReason::None) {
                failure_ = ExplicitFeedbackFailureReason::Cleanup;
            }
            state_ = ExplicitFeedbackEngineState::Quarantined;
            return ExplicitFeedbackEngineResult::TerminalFailure;
        }
        resources_.eventLoopStarted = false;
    }
    if (resources_.audioTransfersAllocated) {
        if (backend_->freeTransfers(TransferKind::AudioOut)) {
            resources_.audioTransfersAllocated = false;
        } else {
            increment(&cleanupErrors_);
        }
    }
    if (resources_.feedbackTransfersAllocated) {
        if (backend_->freeTransfers(TransferKind::FeedbackIn)) {
            resources_.feedbackTransfersAllocated = false;
        } else {
            increment(&cleanupErrors_);
        }
    }
    if (resources_.audioTransfersAllocated || resources_.feedbackTransfersAllocated) {
        if (failure_ == ExplicitFeedbackFailureReason::None) {
            failure_ = ExplicitFeedbackFailureReason::Cleanup;
        }
        state_ = ExplicitFeedbackEngineState::Quarantined;
        return ExplicitFeedbackEngineResult::TerminalFailure;
    }
    if (resources_.alternateSettingActive) {
        if (!backend_->deactivateAlternateSetting()) {
            increment(&cleanupErrors_);
            if (failure_ == ExplicitFeedbackFailureReason::None) {
                failure_ = ExplicitFeedbackFailureReason::Cleanup;
            }
            state_ = ExplicitFeedbackEngineState::Quarantined;
            return ExplicitFeedbackEngineResult::TerminalFailure;
        }
        resources_.alternateSettingActive = false;
    }
    if (resources_.interfacesClaimed) {
        if (!backend_->releaseInterfaces()) {
            increment(&cleanupErrors_);
            if (failure_ == ExplicitFeedbackFailureReason::None) {
                failure_ = ExplicitFeedbackFailureReason::Cleanup;
            }
            state_ = ExplicitFeedbackEngineState::Quarantined;
            return ExplicitFeedbackEngineResult::TerminalFailure;
        }
        resources_.interfacesClaimed = false;
    }
    state_ = failure_ == ExplicitFeedbackFailureReason::None
        ? ExplicitFeedbackEngineState::Stopped
        : ExplicitFeedbackEngineState::Failed;
    return failure_ == ExplicitFeedbackFailureReason::None
        ? ExplicitFeedbackEngineResult::Ok
        : ExplicitFeedbackEngineResult::TerminalFailure;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::submitFeedback(uint32_t count) {
    if (!lifecycle_.submit(TransferKind::FeedbackIn, count)) {
        return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!backend_->submitFeedbackTransfers(count)) {
        lifecycle_.failSubmission(TransferKind::FeedbackIn, count);
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackTransfer);
    }
    return ExplicitFeedbackEngineResult::Ok;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::submitNextAudio() {
    if (lifecycle_.audioOut().inFlight >= lifecycle_.audioOut().allocated) {
        return ExplicitFeedbackEngineResult::Ok;
    }
    const StreamGatePacket next = gate_.next(playerPcmAvailable_);
    if (next.status == StreamGatePacketStatus::PlayerBlockedUntilLock) {
        return ExplicitFeedbackEngineResult::AwaitingFeedback;
    }
    if (next.status == StreamGatePacketStatus::TerminalFailure) {
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackClock);
    }
    if ((next.status != StreamGatePacketStatus::ZeroBootstrap &&
         next.status != StreamGatePacketStatus::PlayerPacket) ||
        next.packet.status != FeedbackMathStatus::Ok) {
        return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!lifecycle_.submit(TransferKind::AudioOut, 1)) {
        return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (!backend_->submitAudioTransfer(next.packet, next.allZero)) {
        lifecycle_.failSubmission(TransferKind::AudioOut, 1);
        return enterTerminal(
            state_ == ExplicitFeedbackEngineState::Starting
                ? ExplicitFeedbackFailureReason::SubmitZeroBootstrap
                : ExplicitFeedbackFailureReason::AudioTransfer
        );
    }
    increment(&audioPacketsSubmitted_);
    if (next.allZero) {
        increment(&zeroPacketsSubmitted_);
    } else {
        increment(&playerPacketsSubmitted_);
    }
    if (next.realPcmReleased && state_ != ExplicitFeedbackEngineState::Starting) {
        state_ = ExplicitFeedbackEngineState::Streaming;
    }
    return ExplicitFeedbackEngineResult::Ok;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::fillAudioAheadWindow() {
    while (lifecycle_.audioOut().inFlight < lifecycle_.audioOut().allocated) {
        const FeedbackAheadWindowResult window = checkFeedbackAheadWindow(
            FeedbackAheadWindowInput {
                lifecycle_.audioOut().submissions,
                lifecycle_.audioOut().completions,
                1,
                config_.maxAheadIntervals
            }
        );
        if (window.status != FeedbackAheadWindowStatus::Ok) {
            return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
        }
        const ExplicitFeedbackEngineResult result = submitNextAudio();
        if (result == ExplicitFeedbackEngineResult::AwaitingFeedback) {
            return result;
        }
        if (result != ExplicitFeedbackEngineResult::Ok) {
            return result;
        }
    }
    return ExplicitFeedbackEngineResult::Ok;
}

void ExplicitFeedbackEngine::updateStreamingState() {
    const FeedbackStreamGateSnapshot gate = gate_.snapshot();
    if (gate.realPcmReleased) {
        state_ = ExplicitFeedbackEngineState::Streaming;
    } else if (gate.clock.state == FeedbackClockState::Locked) {
        state_ = ExplicitFeedbackEngineState::Acquiring;
    }
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::onFeedbackCompletion(
    uint64_t callbackGeneration,
    TransferCompletionStatus status,
    const uint8_t* payload,
    size_t payloadBytes,
    int64_t receivedAtNs,
    uint64_t sequence,
    bool playerPcmAvailable
) {
    if (!acceptsCompletions(state_)) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    playerPcmAvailable_ = playerPcmAvailable;
    const CompletionResult completion = lifecycle_.complete(
        TransferKind::FeedbackIn,
        callbackGeneration,
        status
    );
    if (completion.disposition == CompletionDisposition::Invalid) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    if (completion.disposition == CompletionDisposition::StaleGenerationIgnored) {
        if (isDraining(state_)) {
            settleWhileDraining();
            return ExplicitFeedbackEngineResult::StaleGeneration;
        }
        return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (status == TransferCompletionStatus::NoDevice) {
        return enterTerminal(ExplicitFeedbackFailureReason::DeviceDetached);
    }
    if (isDraining(state_)) {
        return settleWhileDraining();
    }
    if (status != TransferCompletionStatus::Completed) {
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackTransfer);
    }

    ExplicitFeedbackEngineResult sampleResult = ExplicitFeedbackEngineResult::Ok;
    FeedbackMathStatus clockStatus = FeedbackMathStatus::Ok;
    if (payloadBytes == 0 && config_.zeroLengthFeedbackPermitted) {
        increment(&feedbackPacketsZeroLength_);
        clockStatus = gate_.onTick(receivedAtNs);
    } else {
        const FeedbackDecodeResult decoded = decodeFeedbackSample(
            config_.decodeProfile,
            FeedbackDecodeInput {
                payload,
                payloadBytes,
                receivedAtNs,
                sequence
            }
        );
        if (decoded.status != FeedbackMathStatus::Ok) {
            increment(&feedbackPacketsInvalid_);
            clockStatus = gate_.onRejectedSample(receivedAtNs);
            sampleResult = ExplicitFeedbackEngineResult::RejectedFeedback;
        } else {
            const FeedbackEstimateResult estimate = estimator_.ingest(
                decoded.sample.normalized
            );
            if (estimate.status == FeedbackEstimateStatus::Accepted) {
                increment(&feedbackPacketsValid_);
                clockStatus = gate_.onEstimate(estimate, receivedAtNs);
            } else {
                increment(&feedbackPacketsInvalid_);
                clockStatus = gate_.onRejectedSample(receivedAtNs);
                sampleResult = ExplicitFeedbackEngineResult::RejectedFeedback;
            }
        }
    }
    if (gate_.snapshot().terminalFailure ||
        (clockStatus != FeedbackMathStatus::Ok &&
         clockStatus != FeedbackMathStatus::NotReady)) {
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackClock);
    }
    updateStreamingState();
    const ExplicitFeedbackEngineResult feedbackSubmit = submitFeedback(1);
    if (feedbackSubmit != ExplicitFeedbackEngineResult::Ok) {
        return feedbackSubmit;
    }
    if (lifecycle_.audioOut().inFlight < lifecycle_.audioOut().allocated) {
        const ExplicitFeedbackEngineResult audioSubmit = fillAudioAheadWindow();
        if (audioSubmit != ExplicitFeedbackEngineResult::Ok &&
            audioSubmit != ExplicitFeedbackEngineResult::AwaitingFeedback) {
            return audioSubmit;
        }
    }
    return sampleResult;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::onAudioCompletion(
    uint64_t callbackGeneration,
    TransferCompletionStatus status,
    bool playerPcmAvailable
) {
    if (!acceptsCompletions(state_)) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    playerPcmAvailable_ = playerPcmAvailable;
    const CompletionResult completion = lifecycle_.complete(
        TransferKind::AudioOut,
        callbackGeneration,
        status
    );
    if (completion.disposition == CompletionDisposition::Invalid) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    if (completion.disposition == CompletionDisposition::StaleGenerationIgnored) {
        if (isDraining(state_)) {
            settleWhileDraining();
            return ExplicitFeedbackEngineResult::StaleGeneration;
        }
        return enterTerminal(ExplicitFeedbackFailureReason::InternalInvariant);
    }
    if (status == TransferCompletionStatus::NoDevice) {
        return enterTerminal(ExplicitFeedbackFailureReason::DeviceDetached);
    }
    if (isDraining(state_)) {
        return settleWhileDraining();
    }
    if (status != TransferCompletionStatus::Completed) {
        return enterTerminal(ExplicitFeedbackFailureReason::AudioTransfer);
    }
    updateStreamingState();
    return fillAudioAheadWindow();
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::tick(
    int64_t nowNs,
    bool playerPcmAvailable
) {
    if (state_ != ExplicitFeedbackEngineState::Acquiring &&
        state_ != ExplicitFeedbackEngineState::Streaming) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    playerPcmAvailable_ = playerPcmAvailable;
    const FeedbackMathStatus status = gate_.onTick(nowNs);
    if (gate_.snapshot().terminalFailure || status == FeedbackMathStatus::NotReady) {
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackClock);
    }
    if (status != FeedbackMathStatus::Ok) {
        return enterTerminal(ExplicitFeedbackFailureReason::FeedbackClock);
    }
    updateStreamingState();
    if (lifecycle_.audioOut().inFlight < lifecycle_.audioOut().allocated) {
        return fillAudioAheadWindow();
    }
    return ExplicitFeedbackEngineResult::Ok;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::stop() {
    if (state_ == ExplicitFeedbackEngineState::Ready) {
        state_ = ExplicitFeedbackEngineState::Stopped;
        gate_.stop();
        return ExplicitFeedbackEngineResult::Ok;
    }
    if (state_ != ExplicitFeedbackEngineState::Starting &&
        state_ != ExplicitFeedbackEngineState::Acquiring &&
        state_ != ExplicitFeedbackEngineState::Streaming) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    return beginDrain();
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::onDrainTimeout() {
    if (state_ != ExplicitFeedbackEngineState::Draining ||
        (lifecycle_.audioOut().inFlight == 0 && lifecycle_.feedbackIn().inFlight == 0)) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    failure_ = ExplicitFeedbackFailureReason::DrainTimeout;
    state_ = ExplicitFeedbackEngineState::Quarantined;
    return ExplicitFeedbackEngineResult::TerminalFailure;
}

ExplicitFeedbackEngineResult ExplicitFeedbackEngine::retryCleanup() {
    if (state_ != ExplicitFeedbackEngineState::Draining &&
        state_ != ExplicitFeedbackEngineState::Quarantined) {
        return ExplicitFeedbackEngineResult::InvalidState;
    }
    return settleWhileDraining();
}

void ExplicitFeedbackEngine::increment(uint64_t* value) {
    if (value != nullptr && *value != std::numeric_limits<uint64_t>::max()) {
        ++(*value);
    }
}

} // namespace neri::usb::feedback
