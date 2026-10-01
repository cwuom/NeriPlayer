#pragma once

#include "usb/exclusive/usb_runtime_report_v2.h"

#include <cstdint>
#include <mutex>

namespace neri::usb {

enum class UsbRecoveryActionAckStatus {
    Acked,
    AlreadyAcked,
    GenerationMismatch,
    HandleClosing,
    NoPending
};

struct UsbRecoveryActionSnapshot {
    UsbRuntimeRecoveryAction action = UsbRuntimeRecoveryAction::None;
    int64_t generation = 0;
    int64_t id = 0;
    bool latched = false;
};

class UsbRecoveryActionLatch {
public:
    void reset(int64_t generation);

    bool latch(
        UsbRuntimeRecoveryAction action,
        int64_t actionId
    );

    [[nodiscard]] UsbRecoveryActionSnapshot snapshot() const;

    UsbRecoveryActionAckStatus acknowledge(
        int64_t actionGeneration,
        int64_t actionId,
        bool handleClosing
    );

private:
    mutable std::mutex mutex_;
    UsbRuntimeRecoveryAction action_ = UsbRuntimeRecoveryAction::None;
    int64_t generation_ = 0;
    int64_t actionGeneration_ = -1;
    int64_t actionId_ = -1;
    bool latched_ = false;
    bool acknowledged_ = false;
};

const char* usbRecoveryActionAckStatusName(UsbRecoveryActionAckStatus status);

} // namespace neri::usb
