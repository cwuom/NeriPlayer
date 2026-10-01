#include "usb_runtime_report_v2.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <iomanip>
#include <sstream>

namespace neri::usb {
namespace {

void assignError(std::string* error, const char* value) {
    if (error != nullptr) {
        *error = value;
    }
}

bool isReportToken(const std::string& value) {
    if (value.empty()) {
        return false;
    }
    return std::none_of(value.begin(), value.end(), [](char character) {
        return character == '=' || character == ' ' || character == '\t' ||
            character == '\r' || character == '\n';
    });
}

bool countersAreValid(const UsbRuntimeReportV2Snapshot& snapshot) {
    return snapshot.feedbackPayloadBytes >= 0 &&
        snapshot.feedbackExpectedPeriodUs >= 0 &&
        snapshot.feedbackValidSamples >= 0 &&
        snapshot.feedbackInvalidSamples >= 0 &&
        snapshot.feedbackOutliers >= 0 &&
        snapshot.feedbackTimeouts >= 0 &&
        snapshot.feedbackLockCount >= 0 &&
        snapshot.feedbackRelockCount >= 0 &&
        snapshot.feedbackHoldoverCount >= 0 &&
        snapshot.feedbackHoldoverTotalMs >= 0 &&
        snapshot.feedbackLongGapReacquisitions >= 0 &&
        snapshot.feedbackLastAgeMs >= 0 &&
        snapshot.feedbackInFlight >= 0 &&
        snapshot.feedbackTransferErrors >= 0 &&
        snapshot.feedbackPacketErrors >= 0 &&
        snapshot.packetLengthClampCount >= 0;
}

const char* booleanToken(bool value) {
    return value ? "true" : "false";
}

const char* feedbackModeToken(UsbRuntimeFeedbackMode mode) {
    switch (mode) {
        case UsbRuntimeFeedbackMode::Explicit:
            return "explicit";
        case UsbRuntimeFeedbackMode::Implicit:
            return "implicit";
        case UsbRuntimeFeedbackMode::Disabled:
            return "disabled";
    }
    return "disabled";
}

const char* feedbackStateToken(UsbRuntimeFeedbackState state) {
    switch (state) {
        case UsbRuntimeFeedbackState::Priming:
            return "Priming";
        case UsbRuntimeFeedbackState::Acquiring:
            return "Acquiring";
        case UsbRuntimeFeedbackState::Locked:
            return "Locked";
        case UsbRuntimeFeedbackState::Holdover:
            return "Holdover";
        case UsbRuntimeFeedbackState::Relocking:
            return "Relocking";
        case UsbRuntimeFeedbackState::Failed:
            return "Failed";
        case UsbRuntimeFeedbackState::Disabled:
            return "Disabled";
    }
    return "Disabled";
}

const char* recoveryActionToken(UsbRuntimeRecoveryAction action) {
    switch (action) {
        case UsbRuntimeRecoveryAction::Holdover:
            return "HOLDOVER";
        case UsbRuntimeRecoveryAction::Relock:
            return "RELOCK";
        case UsbRuntimeRecoveryAction::SameHandleRearm:
            return "SAME_HANDLE_REARM";
        case UsbRuntimeRecoveryAction::SwitchNativeCandidate:
            return "SWITCH_NATIVE_CANDIDATE";
        case UsbRuntimeRecoveryAction::FreshOpen:
            return "FRESH_OPEN";
        case UsbRuntimeRecoveryAction::StopPreserveIntent:
            return "STOP_PRESERVE_INTENT";
        case UsbRuntimeRecoveryAction::None:
            return "NONE";
    }
    return "NONE";
}

const char* recoveryOwnerToken(UsbRuntimeRecoveryOwner owner) {
    switch (owner) {
        case UsbRuntimeRecoveryOwner::Native:
            return "native";
        case UsbRuntimeRecoveryOwner::Kotlin:
            return "kotlin";
        case UsbRuntimeRecoveryOwner::None:
            return "none";
    }
    return "none";
}

bool isKotlinTerminalAction(UsbRuntimeRecoveryAction action) {
    return action == UsbRuntimeRecoveryAction::FreshOpen ||
        action == UsbRuntimeRecoveryAction::StopPreserveIntent;
}

bool isNativeAction(UsbRuntimeRecoveryAction action) {
    return action != UsbRuntimeRecoveryAction::None &&
        !isKotlinTerminalAction(action);
}

std::string endpointToken(uint8_t endpointAddress) {
    if (endpointAddress == 0) {
        return "none";
    }
    char buffer[5] = {};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "0x%02X",
        static_cast<unsigned int>(endpointAddress)
    );
    return buffer;
}

bool validateSnapshot(
    const UsbRuntimeReportV2Snapshot& snapshot,
    std::string* error
) {
    if (!isReportToken(snapshot.candidateId) ||
        !isReportToken(snapshot.feedbackRawValue) ||
        !isReportToken(snapshot.feedbackClockFailure) ||
        !isReportToken(snapshot.errorCode)) {
        assignError(error, "invalid_report_token");
        return false;
    }
    if (!countersAreValid(snapshot) ||
        !std::isfinite(snapshot.feedbackRateHz) ||
        snapshot.feedbackRateHz < 0.0) {
        assignError(error, "invalid_feedback_metric");
        return false;
    }
    if (snapshot.nativeStreamGeneration <= 0 ||
        snapshot.recoveryEpoch < 0 ||
        snapshot.actionGeneration != snapshot.nativeStreamGeneration) {
        assignError(error, "invalid_generation");
        return false;
    }
    const bool expectedPlaybackReady = snapshot.transportRunning &&
        snapshot.feedbackReady &&
        snapshot.realPcmReleased &&
        snapshot.canAcceptPcm &&
        !snapshot.terminalFailure;
    if (snapshot.playbackReady != expectedPlaybackReady) {
        assignError(error, "inconsistent_playback_ready");
        return false;
    }
    if (snapshot.terminalFailure && snapshot.canAcceptPcm) {
        assignError(error, "terminal_accepts_pcm");
        return false;
    }
    if (snapshot.feedbackMode == UsbRuntimeFeedbackMode::Disabled) {
        if (snapshot.feedbackEndpointAddress != 0 ||
            snapshot.feedbackState != UsbRuntimeFeedbackState::Disabled ||
            !snapshot.feedbackReady ||
            !snapshot.feedbackReusable) {
            assignError(error, "invalid_disabled_feedback");
            return false;
        }
    } else if (
        snapshot.feedbackEndpointAddress <= 0 ||
        snapshot.feedbackEndpointAddress > 0xFF ||
        (snapshot.feedbackEndpointAddress & 0x80) == 0 ||
        snapshot.feedbackPayloadBytes <= 0 ||
        snapshot.feedbackExpectedPeriodUs <= 0
    ) {
        assignError(error, "invalid_active_feedback");
        return false;
    }

    if (snapshot.recommendedAction == UsbRuntimeRecoveryAction::None) {
        if (snapshot.actionId != 0 ||
            snapshot.actionOwner != UsbRuntimeRecoveryOwner::None ||
            snapshot.actionLatched ||
            snapshot.terminalFailure) {
            assignError(error, "invalid_none_action");
            return false;
        }
    } else if (isKotlinTerminalAction(snapshot.recommendedAction)) {
        if (snapshot.actionId <= 0 ||
            snapshot.actionOwner != UsbRuntimeRecoveryOwner::Kotlin ||
            !snapshot.actionLatched ||
            !snapshot.terminalFailure) {
            assignError(error, "invalid_kotlin_action");
            return false;
        }
    } else if (isNativeAction(snapshot.recommendedAction)) {
        if (snapshot.actionId <= 0 ||
            snapshot.actionOwner != UsbRuntimeRecoveryOwner::Native ||
            snapshot.actionLatched ||
            snapshot.terminalFailure) {
            assignError(error, "invalid_native_action");
            return false;
        }
    }
    return true;
}

} // namespace

bool buildUsbRuntimeReportV2Fields(
    const UsbRuntimeReportV2Snapshot& snapshot,
    std::string* output,
    std::string* error
) {
    if (output == nullptr) {
        assignError(error, "missing_report_output");
        return false;
    }
    if (!validateSnapshot(snapshot, error)) {
        output->clear();
        return false;
    }
    std::ostringstream report;
    report << "reportVersion=2"
        << " feedbackMode=" << feedbackModeToken(snapshot.feedbackMode)
        << " feedbackEndpoint=" << endpointToken(
            static_cast<uint8_t>(snapshot.feedbackEndpointAddress)
        )
        << " feedbackState=" << feedbackStateToken(snapshot.feedbackState)
        << " feedbackPayloadBytes=" << snapshot.feedbackPayloadBytes
        << " feedbackExpectedPeriodUs=" << snapshot.feedbackExpectedPeriodUs
        << " feedbackRawValue=" << snapshot.feedbackRawValue
        << " feedbackRateQ32=" << snapshot.feedbackRateQ32
        << " feedbackRateHz=" << std::fixed << std::setprecision(6)
        << snapshot.feedbackRateHz
        << " feedbackRatePpm=" << snapshot.feedbackRatePpm
        << " feedbackValidSamples=" << snapshot.feedbackValidSamples
        << " feedbackInvalidSamples=" << snapshot.feedbackInvalidSamples
        << " feedbackOutliers=" << snapshot.feedbackOutliers
        << " feedbackTimeouts=" << snapshot.feedbackTimeouts
        << " feedbackLockCount=" << snapshot.feedbackLockCount
        << " feedbackRelockCount=" << snapshot.feedbackRelockCount
        << " feedbackHoldoverCount=" << snapshot.feedbackHoldoverCount
        << " feedbackHoldoverTotalMs=" << snapshot.feedbackHoldoverTotalMs
        << " feedbackLongGapReacquisitions="
        << snapshot.feedbackLongGapReacquisitions
        << " feedbackLastAgeMs=" << snapshot.feedbackLastAgeMs
        << " feedbackClockFailure=" << snapshot.feedbackClockFailure
        << " feedbackInFlight=" << snapshot.feedbackInFlight
        << " feedbackTransferErrors=" << snapshot.feedbackTransferErrors
        << " feedbackPacketErrors=" << snapshot.feedbackPacketErrors
        << " packetLengthClampCount=" << snapshot.packetLengthClampCount
        << " transportRunning=" << booleanToken(snapshot.transportRunning)
        << " feedbackReady=" << booleanToken(snapshot.feedbackReady)
        << " realPcmReleased=" << booleanToken(snapshot.realPcmReleased)
        << " canAcceptPcm=" << booleanToken(snapshot.canAcceptPcm)
        << " playbackReady=" << booleanToken(snapshot.playbackReady)
        << " feedbackReusable=" << booleanToken(snapshot.feedbackReusable)
        << " terminalFailure=" << booleanToken(snapshot.terminalFailure)
        << " nativeStreamGeneration=" << snapshot.nativeStreamGeneration
        << " candidateId=" << snapshot.candidateId
        << " recoveryEpoch=" << snapshot.recoveryEpoch
        << " recommendedAction=" << recoveryActionToken(snapshot.recommendedAction)
        << " actionId=" << snapshot.actionId
        << " actionGeneration=" << snapshot.actionGeneration
        << " actionOwner=" << recoveryOwnerToken(snapshot.actionOwner)
        << " actionLatched=" << booleanToken(snapshot.actionLatched)
        << " errorCode=" << snapshot.errorCode;
    *output = report.str();
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

} // namespace neri::usb
