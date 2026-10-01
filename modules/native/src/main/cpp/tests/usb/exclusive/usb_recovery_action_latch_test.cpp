#include "usb/exclusive/usb_recovery_action_latch.h"

#include <cassert>
#include <string>

namespace {

using neri::usb::UsbRecoveryActionAckStatus;
using neri::usb::UsbRecoveryActionLatch;
using neri::usb::UsbRuntimeRecoveryAction;

void resetSelectsCurrentGeneration() {
    UsbRecoveryActionLatch latch;
    latch.reset(41);
    const auto snapshot = latch.snapshot();
    assert(snapshot.action == UsbRuntimeRecoveryAction::None);
    assert(snapshot.generation == 41);
    assert(snapshot.id == 0);
    assert(!snapshot.latched);
}

void latchAndAckAreIdempotent() {
    UsbRecoveryActionLatch latch;
    latch.reset(41);
    assert(latch.latch(UsbRuntimeRecoveryAction::FreshOpen, 7));
    assert(!latch.latch(UsbRuntimeRecoveryAction::StopPreserveIntent, 8));
    const auto snapshot = latch.snapshot();
    assert(snapshot.action == UsbRuntimeRecoveryAction::FreshOpen);
    assert(snapshot.generation == 41);
    assert(snapshot.id == 7);
    assert(snapshot.latched);
    assert(latch.acknowledge(40, 7, false) ==
        UsbRecoveryActionAckStatus::GenerationMismatch);
    assert(latch.acknowledge(41, 8, false) ==
        UsbRecoveryActionAckStatus::NoPending);
    assert(latch.acknowledge(41, 7, false) == UsbRecoveryActionAckStatus::Acked);
    assert(latch.acknowledge(41, 7, false) ==
        UsbRecoveryActionAckStatus::AlreadyAcked);
}

void invalidAndClosingAcksDoNotConsumeLatch() {
    UsbRecoveryActionLatch latch;
    latch.reset(9);
    assert(!latch.latch(UsbRuntimeRecoveryAction::None, 1));
    assert(!latch.latch(UsbRuntimeRecoveryAction::FreshOpen, 0));
    assert(latch.acknowledge(9, 1, true) ==
        UsbRecoveryActionAckStatus::HandleClosing);
    assert(!latch.snapshot().latched);
}

void resetRetiresPreviousAction() {
    UsbRecoveryActionLatch latch;
    latch.reset(1);
    assert(latch.latch(UsbRuntimeRecoveryAction::StopPreserveIntent, 3));
    latch.reset(2);
    assert(latch.acknowledge(1, 3, false) ==
        UsbRecoveryActionAckStatus::NoPending);
    assert(latch.latch(UsbRuntimeRecoveryAction::FreshOpen, 4));
    assert(latch.snapshot().generation == 2);
}

void exposesStableAckNames() {
    assert(std::string(neri::usb::usbRecoveryActionAckStatusName(
        UsbRecoveryActionAckStatus::GenerationMismatch
    )) == "GENERATION_MISMATCH");
}

} // namespace

int main() {
    resetSelectsCurrentGeneration();
    latchAndAckAreIdempotent();
    invalidAndClosingAcksDoNotConsumeLatch();
    resetRetiresPreviousAction();
    exposesStableAckNames();
    return 0;
}
