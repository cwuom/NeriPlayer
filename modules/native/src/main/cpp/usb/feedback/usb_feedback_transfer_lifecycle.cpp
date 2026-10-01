#include "usb/feedback/usb_feedback_transfer_lifecycle.h"

#include <array>
#include <limits>

namespace neri::usb::feedback {
namespace {

constexpr std::array<StartTransactionStep, 7> kStartOrder {
    StartTransactionStep::ClaimInterfaces,
    StartTransactionStep::ActivateAlternateSetting,
    StartTransactionStep::AllocateFeedbackTransfers,
    StartTransactionStep::AllocateAudioTransfers,
    StartTransactionStep::StartEventLoop,
    StartTransactionStep::SubmitFeedbackTransfers,
    StartTransactionStep::SubmitZeroBootstrap
};

bool checkedAdd(uint32_t left, uint32_t right, uint32_t* output) {
    if (output == nullptr || right > std::numeric_limits<uint32_t>::max() - left) {
        return false;
    }
    *output = left + right;
    return true;
}

bool checkedAdd(uint64_t left, uint32_t right, uint64_t* output) {
    if (output == nullptr || right > std::numeric_limits<uint64_t>::max() - left) {
        return false;
    }
    *output = left + right;
    return true;
}

void applyStep(StartTransactionResources* resources, StartTransactionStep step) {
    switch (step) {
        case StartTransactionStep::ClaimInterfaces:
            resources->interfacesClaimed = true;
            break;
        case StartTransactionStep::ActivateAlternateSetting:
            resources->alternateSettingActive = true;
            break;
        case StartTransactionStep::AllocateFeedbackTransfers:
            resources->feedbackTransfersAllocated = true;
            break;
        case StartTransactionStep::AllocateAudioTransfers:
            resources->audioTransfersAllocated = true;
            break;
        case StartTransactionStep::StartEventLoop:
            resources->eventLoopStarted = true;
            break;
        case StartTransactionStep::SubmitFeedbackTransfers:
            resources->feedbackTransfersSubmitted = true;
            break;
        case StartTransactionStep::SubmitZeroBootstrap:
            resources->zeroBootstrapSubmitted = true;
            break;
    }
}

void undoStep(StartTransactionResources* resources, StartTransactionStep step) {
    switch (step) {
        case StartTransactionStep::ClaimInterfaces:
            resources->interfacesClaimed = false;
            break;
        case StartTransactionStep::ActivateAlternateSetting:
            resources->alternateSettingActive = false;
            break;
        case StartTransactionStep::AllocateFeedbackTransfers:
            resources->feedbackTransfersAllocated = false;
            break;
        case StartTransactionStep::AllocateAudioTransfers:
            resources->audioTransfersAllocated = false;
            break;
        case StartTransactionStep::StartEventLoop:
            resources->eventLoopStarted = false;
            break;
        case StartTransactionStep::SubmitFeedbackTransfers:
            resources->feedbackTransfersSubmitted = false;
            break;
        case StartTransactionStep::SubmitZeroBootstrap:
            resources->zeroBootstrapSubmitted = false;
            break;
    }
}

} // namespace

StreamGenerationLifecycle::StreamGenerationLifecycle(uint64_t generation)
    : generation_(generation) {}

uint64_t StreamGenerationLifecycle::generation() const {
    return generation_;
}

GenerationLifecycleState StreamGenerationLifecycle::state() const {
    return state_;
}

bool StreamGenerationLifecycle::deviceOnline() const {
    return deviceOnline_;
}

bool StreamGenerationLifecycle::terminalStopLatched() const {
    return terminalStopLatched_;
}

const TransferSetLifecycle& StreamGenerationLifecycle::audioOut() const {
    return audioOut_;
}

const TransferSetLifecycle& StreamGenerationLifecycle::feedbackIn() const {
    return feedbackIn_;
}

TransferSetLifecycle* StreamGenerationLifecycle::mutableSet(TransferKind kind) {
    switch (kind) {
        case TransferKind::AudioOut:
            return &audioOut_;
        case TransferKind::FeedbackIn:
            return &feedbackIn_;
    }
    return nullptr;
}

const TransferSetLifecycle* StreamGenerationLifecycle::set(TransferKind kind) const {
    switch (kind) {
        case TransferKind::AudioOut:
            return &audioOut_;
        case TransferKind::FeedbackIn:
            return &feedbackIn_;
    }
    return nullptr;
}

bool StreamGenerationLifecycle::allocate(TransferKind kind, uint32_t count) {
    TransferSetLifecycle* transferSet = mutableSet(kind);
    if (transferSet == nullptr || count == 0 ||
        state_ != GenerationLifecycleState::Active || transferSet->allocated != 0) {
        return false;
    }
    transferSet->allocated = count;
    return true;
}

bool StreamGenerationLifecycle::submit(TransferKind kind, uint32_t count) {
    TransferSetLifecycle* transferSet = mutableSet(kind);
    uint64_t nextSubmissions = 0;
    uint32_t nextInFlight = 0;
    if (transferSet == nullptr || count == 0 ||
        state_ != GenerationLifecycleState::Active || !deviceOnline_ ||
        transferSet->inFlight > transferSet->allocated ||
        count > transferSet->allocated - transferSet->inFlight ||
        !checkedAdd(transferSet->submissions, count, &nextSubmissions) ||
        !checkedAdd(transferSet->inFlight, count, &nextInFlight)) {
        return false;
    }
    transferSet->submissions = nextSubmissions;
    transferSet->inFlight = nextInFlight;
    return true;
}

bool StreamGenerationLifecycle::failSubmission(TransferKind kind, uint32_t count) {
    TransferSetLifecycle* transferSet = mutableSet(kind);
    uint64_t nextErrors = 0;
    if (transferSet == nullptr || count == 0 || transferSet->inFlight < count ||
        !checkedAdd(transferSet->errors, count, &nextErrors)) {
        return false;
    }
    transferSet->inFlight -= count;
    transferSet->errors = nextErrors;
    updateReclaimable();
    return true;
}

bool StreamGenerationLifecycle::beginStop() {
    if (state_ != GenerationLifecycleState::Active) {
        return false;
    }
    state_ = GenerationLifecycleState::Stopping;
    return true;
}

bool StreamGenerationLifecycle::retire() {
    if (state_ != GenerationLifecycleState::Stopping) {
        return false;
    }
    state_ = GenerationLifecycleState::Retired;
    updateReclaimable();
    return true;
}

bool StreamGenerationLifecycle::recordCancelResult(
    TransferKind kind,
    CancelResult result
) {
    TransferSetLifecycle* transferSet = mutableSet(kind);
    if (transferSet == nullptr || state_ == GenerationLifecycleState::Active ||
        state_ == GenerationLifecycleState::Reclaimable) {
        return false;
    }
    if (result == CancelResult::NotFound) {
        ++transferSet->cancelNotFound;
        transferSet->inFlight = 0;
        updateReclaimable();
    } else if (result == CancelResult::Failed) {
        ++transferSet->errors;
    }
    return true;
}

CompletionResult StreamGenerationLifecycle::complete(
    TransferKind kind,
    uint64_t callbackGeneration,
    TransferCompletionStatus status
) {
    CompletionResult result;
    TransferSetLifecycle* transferSet = mutableSet(kind);
    if (transferSet == nullptr || transferSet->inFlight == 0) {
        return result;
    }
    if (callbackGeneration != generation_) {
        result.disposition = CompletionDisposition::StaleGenerationIgnored;
        return result;
    }
    --transferSet->inFlight;
    ++transferSet->completions;
    if (status == TransferCompletionStatus::Error ||
        status == TransferCompletionStatus::NoDevice) {
        ++transferSet->errors;
    }

    if (status == TransferCompletionStatus::NoDevice) {
        deviceOnline_ = false;
        if (!terminalStopLatched_) {
            terminalStopLatched_ = true;
            result.terminalStopRaised = true;
        }
        if (state_ == GenerationLifecycleState::Active) {
            state_ = GenerationLifecycleState::Stopping;
        }
        result.disposition = CompletionDisposition::DeviceDetached;
    } else if (state_ == GenerationLifecycleState::Active && deviceOnline_ &&
        status == TransferCompletionStatus::Completed) {
        result.disposition = CompletionDisposition::Resubmit;
    } else {
        result.disposition = CompletionDisposition::Settled;
    }
    updateReclaimable();
    return result;
}

void StreamGenerationLifecycle::updateReclaimable() {
    if (state_ == GenerationLifecycleState::Retired &&
        audioOut_.inFlight == 0 && feedbackIn_.inFlight == 0) {
        state_ = GenerationLifecycleState::Reclaimable;
    }
}

bool StartTransactionResources::empty() const {
    return !interfacesClaimed && !alternateSettingActive &&
        !feedbackTransfersAllocated && !audioTransfersAllocated &&
        !eventLoopStarted && !feedbackTransfersSubmitted &&
        !zeroBootstrapSubmitted;
}

StartTransactionResult runStartTransaction(
    StartTransactionStep failAt,
    bool injectFailure
) {
    StartTransactionResult result;
    for (StartTransactionStep step : kStartOrder) {
        if (injectFailure && step == failAt) {
            result.reason = std::string("start_failed_at_") +
                startTransactionStepName(step);
            rollbackStartTransaction(&result);
            return result;
        }
        applyStep(&result.resources, step);
        result.completedSteps.push_back(step);
    }
    result.started = true;
    result.reason.clear();
    return result;
}

void rollbackStartTransaction(StartTransactionResult* transaction) {
    if (transaction == nullptr) {
        return;
    }
    if (transaction->completedSteps.empty()) {
        transaction->started = false;
        return;
    }
    for (auto step = transaction->completedSteps.rbegin();
         step != transaction->completedSteps.rend();
         ++step) {
        undoStep(&transaction->resources, *step);
        transaction->rollbackOrder.push_back(*step);
    }
    transaction->completedSteps.clear();
    transaction->started = false;
}

const char* transferKindName(TransferKind kind) {
    switch (kind) {
        case TransferKind::AudioOut:
            return "audio_out";
        case TransferKind::FeedbackIn:
            return "feedback_in";
    }
    return "unknown";
}

const char* completionDispositionName(CompletionDisposition disposition) {
    switch (disposition) {
        case CompletionDisposition::Resubmit:
            return "resubmit";
        case CompletionDisposition::Settled:
            return "settled";
        case CompletionDisposition::StaleGenerationIgnored:
            return "stale_generation_ignored";
        case CompletionDisposition::DeviceDetached:
            return "device_detached";
        case CompletionDisposition::Invalid:
            return "invalid";
    }
    return "unknown";
}

const char* generationLifecycleStateName(GenerationLifecycleState state) {
    switch (state) {
        case GenerationLifecycleState::Active:
            return "active";
        case GenerationLifecycleState::Stopping:
            return "stopping";
        case GenerationLifecycleState::Retired:
            return "retired";
        case GenerationLifecycleState::Reclaimable:
            return "reclaimable";
    }
    return "unknown";
}

const char* startTransactionStepName(StartTransactionStep step) {
    switch (step) {
        case StartTransactionStep::ClaimInterfaces:
            return "claim_interfaces";
        case StartTransactionStep::ActivateAlternateSetting:
            return "activate_alternate_setting";
        case StartTransactionStep::AllocateFeedbackTransfers:
            return "allocate_feedback_transfers";
        case StartTransactionStep::AllocateAudioTransfers:
            return "allocate_audio_transfers";
        case StartTransactionStep::StartEventLoop:
            return "start_event_loop";
        case StartTransactionStep::SubmitFeedbackTransfers:
            return "submit_feedback_transfers";
        case StartTransactionStep::SubmitZeroBootstrap:
            return "submit_zero_bootstrap";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
