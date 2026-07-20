#include "usb/exclusive/usb_runtime_report_v2.h"

#include <cassert>
#include <cstdint>
#include <string>

namespace {

neri::usb::UsbRuntimeReportV2Snapshot validDisabledSnapshot() {
    neri::usb::UsbRuntimeReportV2Snapshot snapshot;
    snapshot.transportRunning = true;
    snapshot.realPcmReleased = true;
    snapshot.canAcceptPcm = true;
    snapshot.playbackReady = true;
    snapshot.nativeStreamGeneration = 9;
    snapshot.candidateId = "vid262a-pid18c8-iface2-alt3";
    snapshot.recoveryEpoch = 4;
    snapshot.actionGeneration = 9;
    return snapshot;
}

void verifiesDisabledReport() {
    const auto snapshot = validDisabledSnapshot();
    std::string report;
    std::string error;
    assert(neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error.empty());
    assert(report.find("reportVersion=2") == 0);
    assert(report.find("feedbackMode=disabled") != std::string::npos);
    assert(report.find("playbackReady=true") != std::string::npos);
    assert(report.find("recommendedAction=NONE") != std::string::npos);
    assert(report.find("actionOwner=none") != std::string::npos);
}

void rejectsReadinessMismatch() {
    auto snapshot = validDisabledSnapshot();
    snapshot.transportRunning = false;
    std::string report;
    std::string error;
    assert(!neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error == "inconsistent_playback_ready");
}

void verifiesLatchedTerminalAction() {
    auto snapshot = validDisabledSnapshot();
    snapshot.transportRunning = false;
    snapshot.canAcceptPcm = false;
    snapshot.playbackReady = false;
    snapshot.terminalFailure = true;
    snapshot.recommendedAction = neri::usb::UsbRuntimeRecoveryAction::FreshOpen;
    snapshot.actionId = 17;
    snapshot.actionOwner = neri::usb::UsbRuntimeRecoveryOwner::Kotlin;
    snapshot.actionLatched = true;
    snapshot.errorCode = "TransportFailed";
    std::string report;
    std::string error;
    assert(neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(report.find("recommendedAction=FRESH_OPEN") != std::string::npos);
    assert(report.find("actionId=17") != std::string::npos);
    assert(report.find("actionOwner=kotlin") != std::string::npos);
    assert(report.find("terminalFailure=true") != std::string::npos);
}

void rejectsUnlatchedTerminalFailure() {
    auto snapshot = validDisabledSnapshot();
    snapshot.transportRunning = false;
    snapshot.canAcceptPcm = false;
    snapshot.playbackReady = false;
    snapshot.terminalFailure = true;
    std::string report;
    std::string error;
    assert(!neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error == "invalid_none_action");
}

void rejectsGenerationMismatch() {
    auto snapshot = validDisabledSnapshot();
    snapshot.actionGeneration = snapshot.nativeStreamGeneration - 1;
    std::string report;
    std::string error;
    assert(!neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error == "invalid_generation");
}

void rejectsInvalidActionIdentity() {
    auto snapshot = validDisabledSnapshot();
    snapshot.actionId = 1;
    std::string report;
    std::string error;
    assert(!neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error == "invalid_none_action");
}

void verifiesSignedFeedbackPpm() {
    auto snapshot = validDisabledSnapshot();
    snapshot.feedbackRatePpm = -37;
    std::string report;
    std::string error;
    assert(neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(report.find("feedbackRatePpm=-37") != std::string::npos);
}

void verifiesExplicitFeedbackReport() {
    auto snapshot = validDisabledSnapshot();
    snapshot.feedbackMode = neri::usb::UsbRuntimeFeedbackMode::Explicit;
    snapshot.feedbackEndpointAddress = 0x84;
    snapshot.feedbackState = neri::usb::UsbRuntimeFeedbackState::Locked;
    snapshot.feedbackPayloadBytes = 4;
    snapshot.feedbackExpectedPeriodUs = 1000;
    snapshot.feedbackClockFailure = "holdover_timeout";
    snapshot.feedbackRawValue = "1572864";
    snapshot.feedbackRateQ32 = UINT64_C(103079215104);
    snapshot.feedbackRateHz = 24.0;
    snapshot.feedbackValidSamples = 32;
    snapshot.feedbackLockCount = 1;
    snapshot.feedbackInFlight = 4;
    snapshot.feedbackReady = true;
    snapshot.feedbackReusable = true;
    std::string report;
    std::string error;
    assert(neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error.empty());
    assert(report.find("feedbackMode=explicit") != std::string::npos);
    assert(report.find("feedbackEndpoint=0x84") != std::string::npos);
    assert(report.find("feedbackState=Locked") != std::string::npos);
    assert(report.find("feedbackValidSamples=32") != std::string::npos);
    assert(report.find("feedbackClockFailure=holdover_timeout") != std::string::npos);
    assert(report.find("feedbackInFlight=4") != std::string::npos);
}

void verifiesMaximumFeedbackEndpointAddress() {
    auto snapshot = validDisabledSnapshot();
    snapshot.feedbackMode = neri::usb::UsbRuntimeFeedbackMode::Explicit;
    snapshot.feedbackEndpointAddress = 0xFF;
    snapshot.feedbackState = neri::usb::UsbRuntimeFeedbackState::Locked;
    snapshot.feedbackPayloadBytes = 4;
    snapshot.feedbackExpectedPeriodUs = 1000;
    std::string report;
    std::string error;
    assert(neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(error.empty());
    assert(report.find("feedbackEndpoint=0xFF") != std::string::npos);
}

void rejectsOutOfRangeFeedbackEndpointAddress() {
    auto snapshot = validDisabledSnapshot();
    snapshot.feedbackMode = neri::usb::UsbRuntimeFeedbackMode::Explicit;
    snapshot.feedbackEndpointAddress = 0x100;
    snapshot.feedbackState = neri::usb::UsbRuntimeFeedbackState::Locked;
    snapshot.feedbackPayloadBytes = 4;
    snapshot.feedbackExpectedPeriodUs = 1000;
    std::string report;
    std::string error;
    assert(!neri::usb::buildUsbRuntimeReportV2Fields(snapshot, &report, &error));
    assert(report.empty());
    assert(error == "invalid_active_feedback");
}

} // namespace

int main() {
    verifiesDisabledReport();
    rejectsReadinessMismatch();
    verifiesLatchedTerminalAction();
    rejectsUnlatchedTerminalFailure();
    rejectsGenerationMismatch();
    rejectsInvalidActionIdentity();
    verifiesSignedFeedbackPpm();
    verifiesExplicitFeedbackReport();
    verifiesMaximumFeedbackEndpointAddress();
    rejectsOutOfRangeFeedbackEndpointAddress();
    return 0;
}
