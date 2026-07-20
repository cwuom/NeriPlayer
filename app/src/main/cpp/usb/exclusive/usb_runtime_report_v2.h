#pragma once

#include <cstdint>
#include <string>

namespace neri::usb {

enum class UsbRuntimeFeedbackMode {
    Disabled,
    Explicit,
    Implicit
};

enum class UsbRuntimeFeedbackState {
    Disabled,
    Priming,
    Acquiring,
    Locked,
    Holdover,
    Relocking,
    Failed
};

enum class UsbRuntimeRecoveryAction {
    None,
    Holdover,
    Relock,
    SameHandleRearm,
    SwitchNativeCandidate,
    FreshOpen,
    StopPreserveIntent
};

enum class UsbRuntimeRecoveryOwner {
    None,
    Native,
    Kotlin
};

struct UsbRuntimeReportV2Snapshot {
    UsbRuntimeFeedbackMode feedbackMode = UsbRuntimeFeedbackMode::Disabled;
    int feedbackEndpointAddress = 0;
    UsbRuntimeFeedbackState feedbackState = UsbRuntimeFeedbackState::Disabled;
    int feedbackPayloadBytes = 0;
    int64_t feedbackExpectedPeriodUs = 0;
    std::string feedbackRawValue = "none";
    uint64_t feedbackRateQ32 = 0;
    double feedbackRateHz = 0.0;
    int64_t feedbackRatePpm = 0;
    int64_t feedbackValidSamples = 0;
    int64_t feedbackInvalidSamples = 0;
    int64_t feedbackOutliers = 0;
    int64_t feedbackTimeouts = 0;
    int64_t feedbackLockCount = 0;
    int64_t feedbackRelockCount = 0;
    int64_t feedbackHoldoverCount = 0;
    int64_t feedbackHoldoverTotalMs = 0;
    int64_t feedbackLastAgeMs = 0;
    int feedbackInFlight = 0;
    int64_t feedbackTransferErrors = 0;
    int64_t feedbackPacketErrors = 0;
    int64_t packetLengthClampCount = 0;
    bool transportRunning = false;
    bool feedbackReady = true;
    bool realPcmReleased = false;
    bool canAcceptPcm = false;
    bool playbackReady = false;
    bool feedbackReusable = true;
    bool terminalFailure = false;
    int64_t nativeStreamGeneration = 0;
    std::string candidateId;
    int64_t recoveryEpoch = 0;
    UsbRuntimeRecoveryAction recommendedAction = UsbRuntimeRecoveryAction::None;
    int64_t actionId = 0;
    int64_t actionGeneration = 0;
    UsbRuntimeRecoveryOwner actionOwner = UsbRuntimeRecoveryOwner::None;
    bool actionLatched = false;
    std::string errorCode = "None";
};

bool buildUsbRuntimeReportV2Fields(
    const UsbRuntimeReportV2Snapshot& snapshot,
    std::string* output,
    std::string* error
);

} // namespace neri::usb
