#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::feedback {

enum class TransferKind {
    AudioOut,
    FeedbackIn
};

enum class TransferCompletionStatus {
    Completed,
    Cancelled,
    NoDevice,
    Error
};

enum class CancelResult {
    Accepted,
    NotFound,
    Failed
};

enum class CompletionDisposition {
    Resubmit,
    Settled,
    StaleGenerationIgnored,
    DeviceDetached,
    Invalid
};

enum class GenerationLifecycleState {
    Active,
    Stopping,
    Retired,
    Reclaimable
};

struct TransferSetLifecycle {
    uint32_t allocated = 0;
    uint64_t submissions = 0;
    uint32_t inFlight = 0;
    uint64_t completions = 0;
    uint64_t errors = 0;
    uint64_t cancelNotFound = 0;
};

struct CompletionResult {
    CompletionDisposition disposition = CompletionDisposition::Invalid;
    bool terminalStopRaised = false;
};

class StreamGenerationLifecycle {
public:
    explicit StreamGenerationLifecycle(uint64_t generation);

    [[nodiscard]] uint64_t generation() const;
    [[nodiscard]] GenerationLifecycleState state() const;
    [[nodiscard]] bool deviceOnline() const;
    [[nodiscard]] bool terminalStopLatched() const;
    [[nodiscard]] const TransferSetLifecycle& audioOut() const;
    [[nodiscard]] const TransferSetLifecycle& feedbackIn() const;

    bool allocate(TransferKind kind, uint32_t count);
    bool submit(TransferKind kind, uint32_t count);
    bool failSubmission(TransferKind kind, uint32_t count);
    bool beginStop();
    bool retire();
    bool recordCancelResult(TransferKind kind, CancelResult result);

    CompletionResult complete(
        TransferKind kind,
        uint64_t callbackGeneration,
        TransferCompletionStatus status
    );

private:
    TransferSetLifecycle* mutableSet(TransferKind kind);
    const TransferSetLifecycle* set(TransferKind kind) const;
    void updateReclaimable();

    uint64_t generation_ = 0;
    GenerationLifecycleState state_ = GenerationLifecycleState::Active;
    bool deviceOnline_ = true;
    bool terminalStopLatched_ = false;
    TransferSetLifecycle audioOut_;
    TransferSetLifecycle feedbackIn_;
};

enum class StartTransactionStep {
    ClaimInterfaces,
    ActivateAlternateSetting,
    AllocateFeedbackTransfers,
    AllocateAudioTransfers,
    StartEventLoop,
    SubmitFeedbackTransfers,
    SubmitZeroBootstrap
};

struct StartTransactionResources {
    bool interfacesClaimed = false;
    bool alternateSettingActive = false;
    bool feedbackTransfersAllocated = false;
    bool audioTransfersAllocated = false;
    bool eventLoopStarted = false;
    bool feedbackTransfersSubmitted = false;
    bool zeroBootstrapSubmitted = false;

    [[nodiscard]] bool empty() const;
};

struct StartTransactionResult {
    bool started = false;
    StartTransactionResources resources;
    std::vector<StartTransactionStep> completedSteps;
    std::vector<StartTransactionStep> rollbackOrder;
    std::string reason;
};

StartTransactionResult runStartTransaction(
    StartTransactionStep failAt,
    bool injectFailure
);

void rollbackStartTransaction(StartTransactionResult* transaction);

const char* transferKindName(TransferKind kind);
const char* completionDispositionName(CompletionDisposition disposition);
const char* generationLifecycleStateName(GenerationLifecycleState state);
const char* startTransactionStepName(StartTransactionStep step);

} // namespace neri::usb::feedback
