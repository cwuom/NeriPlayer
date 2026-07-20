#include "usb/feedback/usb_feedback_transfer_lifecycle.h"

#include <cassert>
#include <cstdint>
#include <string>

namespace {

using neri::usb::feedback::CancelResult;
using neri::usb::feedback::CompletionDisposition;
using neri::usb::feedback::GenerationLifecycleState;
using neri::usb::feedback::StartTransactionStep;
using neri::usb::feedback::StreamGenerationLifecycle;
using neri::usb::feedback::TransferCompletionStatus;
using neri::usb::feedback::TransferKind;

void successfulStartRollsBackInReverseOrder() {
    auto transaction = neri::usb::feedback::runStartTransaction(
        StartTransactionStep::ClaimInterfaces,
        false
    );
    assert(transaction.started);
    assert(!transaction.resources.empty());
    assert(transaction.completedSteps.size() == 7U);

    neri::usb::feedback::rollbackStartTransaction(&transaction);
    assert(!transaction.started);
    assert(transaction.resources.empty());
    assert(transaction.completedSteps.empty());
    assert(transaction.rollbackOrder.size() == 7U);
    assert(transaction.rollbackOrder.front() == StartTransactionStep::SubmitZeroBootstrap);
    assert(transaction.rollbackOrder.back() == StartTransactionStep::ClaimInterfaces);

    neri::usb::feedback::rollbackStartTransaction(&transaction);
    assert(transaction.rollbackOrder.size() == 7U);
}

void everyStartFailureReleasesEarlierResources() {
    for (int rawStep = 0; rawStep < 7; ++rawStep) {
        const auto failAt = static_cast<StartTransactionStep>(rawStep);
        const auto transaction = neri::usb::feedback::runStartTransaction(failAt, true);
        assert(!transaction.started);
        assert(transaction.resources.empty());
        assert(transaction.completedSteps.empty());
        assert(transaction.rollbackOrder.size() == static_cast<size_t>(rawStep));
        if (rawStep > 0) {
            assert(transaction.rollbackOrder.front() ==
                static_cast<StartTransactionStep>(rawStep - 1));
        }
        assert(transaction.reason.find("start_failed_at_") == 0U);
    }
}

void keepsAudioAndFeedbackCountersIndependent() {
    StreamGenerationLifecycle lifecycle(7);
    assert(lifecycle.allocate(TransferKind::AudioOut, 4));
    assert(lifecycle.allocate(TransferKind::FeedbackIn, 2));
    assert(lifecycle.submit(TransferKind::AudioOut, 4));
    assert(lifecycle.submit(TransferKind::FeedbackIn, 2));
    assert(lifecycle.audioOut().inFlight == 4U);
    assert(lifecycle.feedbackIn().inFlight == 2U);

    auto result = lifecycle.complete(
        TransferKind::FeedbackIn,
        7,
        TransferCompletionStatus::Completed
    );
    assert(result.disposition == CompletionDisposition::Resubmit);
    assert(lifecycle.feedbackIn().inFlight == 1U);
    assert(lifecycle.audioOut().inFlight == 4U);
    assert(lifecycle.submit(TransferKind::FeedbackIn, 1));
    assert(lifecycle.feedbackIn().inFlight == 2U);
    assert(lifecycle.feedbackIn().submissions == 3U);

    result = lifecycle.complete(
        TransferKind::AudioOut,
        6,
        TransferCompletionStatus::Completed
    );
    assert(result.disposition == CompletionDisposition::StaleGenerationIgnored);
    assert(lifecycle.audioOut().inFlight == 4U);
    assert(lifecycle.state() == GenerationLifecycleState::Active);
}

void staleNoDeviceOnlySettlesItsSlot() {
    StreamGenerationLifecycle lifecycle(8);
    assert(lifecycle.allocate(TransferKind::FeedbackIn, 1));
    assert(lifecycle.submit(TransferKind::FeedbackIn, 1));

    const auto result = lifecycle.complete(
        TransferKind::FeedbackIn,
        7,
        TransferCompletionStatus::NoDevice
    );
    assert(result.disposition == CompletionDisposition::StaleGenerationIgnored);
    assert(!result.terminalStopRaised);
    assert(lifecycle.deviceOnline());
    assert(!lifecycle.terminalStopLatched());
    assert(lifecycle.feedbackIn().completions == 0U);
    assert(lifecycle.feedbackIn().errors == 0U);
    assert(lifecycle.feedbackIn().inFlight == 1U);
}

void stopDisablesResubmitAndAllowsReclaimAfterBothSetsDrain() {
    StreamGenerationLifecycle lifecycle(9);
    assert(lifecycle.allocate(TransferKind::AudioOut, 1));
    assert(lifecycle.allocate(TransferKind::FeedbackIn, 1));
    assert(lifecycle.submit(TransferKind::AudioOut, 1));
    assert(lifecycle.submit(TransferKind::FeedbackIn, 1));
    assert(lifecycle.beginStop());
    assert(lifecycle.state() == GenerationLifecycleState::Stopping);
    assert(!lifecycle.submit(TransferKind::AudioOut, 1));
    assert(lifecycle.recordCancelResult(TransferKind::AudioOut, CancelResult::NotFound));
    assert(lifecycle.audioOut().cancelNotFound == 1U);
    assert(lifecycle.audioOut().inFlight == 0U);
    assert(lifecycle.retire());
    assert(lifecycle.state() == GenerationLifecycleState::Retired);
    assert(lifecycle.feedbackIn().inFlight == 1U);

    const auto result = lifecycle.complete(
        TransferKind::FeedbackIn,
        9,
        TransferCompletionStatus::Cancelled
    );
    assert(result.disposition == CompletionDisposition::Settled);
    assert(lifecycle.state() == GenerationLifecycleState::Reclaimable);
    assert(lifecycle.audioOut().inFlight == 0U);
    assert(lifecycle.feedbackIn().inFlight == 0U);
}

void noDeviceRaisesOneTerminalStopAndSettlesBothSets() {
    StreamGenerationLifecycle lifecycle(11);
    assert(lifecycle.allocate(TransferKind::AudioOut, 1));
    assert(lifecycle.allocate(TransferKind::FeedbackIn, 1));
    assert(lifecycle.submit(TransferKind::AudioOut, 1));
    assert(lifecycle.submit(TransferKind::FeedbackIn, 1));

    auto result = lifecycle.complete(
        TransferKind::FeedbackIn,
        11,
        TransferCompletionStatus::NoDevice
    );
    assert(result.disposition == CompletionDisposition::DeviceDetached);
    assert(result.terminalStopRaised);
    assert(lifecycle.terminalStopLatched());
    assert(!lifecycle.deviceOnline());

    result = lifecycle.complete(
        TransferKind::AudioOut,
        11,
        TransferCompletionStatus::NoDevice
    );
    assert(result.disposition == CompletionDisposition::DeviceDetached);
    assert(!result.terminalStopRaised);
    assert(lifecycle.beginStop() == false);
    assert(lifecycle.retire());
    assert(lifecycle.state() == GenerationLifecycleState::Reclaimable);
}

void rejectsInvalidAndDoubleCompletionOperations() {
    StreamGenerationLifecycle lifecycle(13);
    assert(!lifecycle.allocate(TransferKind::AudioOut, 0));
    assert(lifecycle.allocate(TransferKind::AudioOut, 1));
    assert(!lifecycle.submit(TransferKind::AudioOut, 2));
    assert(lifecycle.submit(TransferKind::AudioOut, 1));
    auto result = lifecycle.complete(
        TransferKind::AudioOut,
        13,
        TransferCompletionStatus::Completed
    );
    assert(result.disposition == CompletionDisposition::Resubmit);
    result = lifecycle.complete(
        TransferKind::AudioOut,
        13,
        TransferCompletionStatus::Completed
    );
    assert(result.disposition == CompletionDisposition::Invalid);
}

void failedSubmissionReleasesReservedSlotWithoutFakeCompletion() {
    StreamGenerationLifecycle lifecycle(15);
    assert(lifecycle.allocate(TransferKind::FeedbackIn, 2));
    assert(lifecycle.submit(TransferKind::FeedbackIn, 2));
    assert(lifecycle.failSubmission(TransferKind::FeedbackIn, 1));
    assert(lifecycle.feedbackIn().inFlight == 1U);
    assert(lifecycle.feedbackIn().submissions == 2U);
    assert(lifecycle.feedbackIn().completions == 0U);
    assert(lifecycle.feedbackIn().errors == 1U);
    assert(!lifecycle.failSubmission(TransferKind::FeedbackIn, 2));
}

void exposesStableNames() {
    assert(std::string(neri::usb::feedback::transferKindName(
        TransferKind::FeedbackIn
    )) == "feedback_in");
    assert(std::string(neri::usb::feedback::completionDispositionName(
        CompletionDisposition::StaleGenerationIgnored
    )) == "stale_generation_ignored");
    assert(std::string(neri::usb::feedback::generationLifecycleStateName(
        GenerationLifecycleState::Reclaimable
    )) == "reclaimable");
}

} // namespace

int main() {
    successfulStartRollsBackInReverseOrder();
    everyStartFailureReleasesEarlierResources();
    keepsAudioAndFeedbackCountersIndependent();
    staleNoDeviceOnlySettlesItsSlot();
    stopDisablesResubmitAndAllowsReclaimAfterBothSetsDrain();
    noDeviceRaisesOneTerminalStopAndSettlesBothSets();
    rejectsInvalidAndDoubleCompletionOperations();
    failedSubmissionReleasesReservedSlotWithoutFakeCompletion();
    exposesStableNames();
    return 0;
}
