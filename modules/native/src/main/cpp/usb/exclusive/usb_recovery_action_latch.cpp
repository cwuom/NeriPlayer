#include "usb/exclusive/usb_recovery_action_latch.h"

namespace neri::usb {
namespace {

bool isTerminalAction(UsbRuntimeRecoveryAction action) {
    return action == UsbRuntimeRecoveryAction::FreshOpen ||
        action == UsbRuntimeRecoveryAction::StopPreserveIntent;
}

} // namespace

void UsbRecoveryActionLatch::reset(int64_t generation) {
    std::lock_guard<std::mutex> guard(mutex_);
    generation_ = generation;
    action_ = UsbRuntimeRecoveryAction::None;
    actionGeneration_ = -1;
    actionId_ = -1;
    latched_ = false;
    acknowledged_ = false;
}

bool UsbRecoveryActionLatch::latch(
    UsbRuntimeRecoveryAction action,
    int64_t actionId
) {
    if (!isTerminalAction(action) || actionId <= 0) {
        return false;
    }
    std::lock_guard<std::mutex> guard(mutex_);
    if (generation_ <= 0 || latched_) {
        return false;
    }
    action_ = action;
    actionGeneration_ = generation_;
    actionId_ = actionId;
    latched_ = true;
    acknowledged_ = false;
    return true;
}

UsbRecoveryActionSnapshot UsbRecoveryActionLatch::snapshot() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return UsbRecoveryActionSnapshot {
        action_,
        latched_ ? actionGeneration_ : generation_,
        latched_ ? actionId_ : 0,
        latched_
    };
}

UsbRecoveryActionAckStatus UsbRecoveryActionLatch::acknowledge(
    int64_t actionGeneration,
    int64_t actionId,
    bool handleClosing
) {
    if (actionGeneration < 0 || actionId < 0) {
        return UsbRecoveryActionAckStatus::NoPending;
    }
    if (handleClosing) {
        return UsbRecoveryActionAckStatus::HandleClosing;
    }
    std::lock_guard<std::mutex> guard(mutex_);
    if (!latched_ || actionGeneration_ < 0 || actionId_ < 0) {
        return UsbRecoveryActionAckStatus::NoPending;
    }
    if (actionGeneration_ != actionGeneration) {
        return UsbRecoveryActionAckStatus::GenerationMismatch;
    }
    if (actionId_ != actionId) {
        return UsbRecoveryActionAckStatus::NoPending;
    }
    if (acknowledged_) {
        return UsbRecoveryActionAckStatus::AlreadyAcked;
    }
    acknowledged_ = true;
    return UsbRecoveryActionAckStatus::Acked;
}

const char* usbRecoveryActionAckStatusName(UsbRecoveryActionAckStatus status) {
    switch (status) {
        case UsbRecoveryActionAckStatus::Acked:
            return "ACKED";
        case UsbRecoveryActionAckStatus::AlreadyAcked:
            return "ALREADY_ACKED";
        case UsbRecoveryActionAckStatus::GenerationMismatch:
            return "GENERATION_MISMATCH";
        case UsbRecoveryActionAckStatus::HandleClosing:
            return "HANDLE_CLOSING";
        case UsbRecoveryActionAckStatus::NoPending:
            return "NO_PENDING";
    }
    return "NO_PENDING";
}

} // namespace neri::usb
