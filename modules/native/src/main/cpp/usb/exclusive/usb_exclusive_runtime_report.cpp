#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <limits>
#include <mutex>
#include <string>

#include "usb/exclusive/usb_runtime_report_v2.h"
#include "usb/feedback/usb_feedback_rate_math.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

std::string runtimeCandidateId(const UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return "unknown-candidate";
    }
    char buffer[192] = {};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "vid%04X-pid%04X-bcd%04X-bus%u-dev%u-uac%d-iface%d-alt%d-out%02X-sr%d-ch%d-b%d-s%d",
        handle->device.vendorId,
        handle->device.productId,
        handle->device.deviceRelease,
        static_cast<unsigned int>(handle->device.busNumber),
        static_cast<unsigned int>(handle->device.deviceAddress),
        handle->device.uacVersion,
        handle->device.audioStreamingInterface,
        handle->device.alternateSetting,
        handle->device.outEndpoint,
        handle->device.sampleRate,
        handle->device.channelCount,
        handle->device.bitsPerSample,
        handle->device.subslotBytes
    );
    return buffer;
}

std::string runtimeErrorCode(
    const UsbExclusiveHandle* handle,
    const std::string& lastError
) {
    if (handle == nullptr) {
        return "NativeInternalError";
    }
    if (!handle->recovery.deviceOnline.load() || handle->recovery.noDeviceObserved.load()) {
        return "DeviceDetached";
    }
    if (!handle->recovery.transportFailed.load()) {
        return "None";
    }
    if (lastError.find("first_completion_timeout") != std::string::npos) {
        return "TransferFirstCompletionTimeout";
    }
    if (lastError.find("completion_stalled") != std::string::npos) {
        return "TransferCompletionStalled";
    }
    if (lastError.find("feedback_initial_lock_timeout") != std::string::npos) {
        return "FeedbackInitialLockTimeout";
    }
    if (lastError.find("feedback_payload") != std::string::npos) {
        return "FeedbackPayloadInvalid";
    }
    if (lastError.find("feedback_transfer") != std::string::npos) {
        return "FeedbackTransferFailed";
    }
    if (lastError.find("feedback_lost") != std::string::npos) {
        return "FeedbackLost";
    }
    if (lastError.find("feedback_packet_capacity") != std::string::npos) {
        return "FeedbackPacketCapacityExceeded";
    }
    if (lastError.find("iso_packet") != std::string::npos) {
        return "IsoPacketErrorBurst";
    }
    if (lastError.find("cancel_drain") != std::string::npos) {
        return "CancelDrainTimeout";
    }
    if (lastError.find("quarantine") != std::string::npos) {
        return "Quarantined";
    }
    return "TransportFailed";
}

bool feedbackClockCanStream(
    neri::usb::feedback::FeedbackClockState state
) {
    return state == neri::usb::feedback::FeedbackClockState::Locked ||
        state == neri::usb::feedback::FeedbackClockState::Holdover ||
        state == neri::usb::feedback::FeedbackClockState::Relocking;
}

neri::usb::UsbRuntimeFeedbackState runtimeFeedbackState(
    bool explicitFeedbackEnabled,
    const neri::usb::feedback::ExplicitFeedbackRuntimeSnapshot& snapshot
) {
    if (!explicitFeedbackEnabled) {
        return neri::usb::UsbRuntimeFeedbackState::Disabled;
    }
    if (snapshot.terminalFailure ||
        snapshot.state ==
            neri::usb::feedback::ExplicitFeedbackRuntimeState::Failed ||
        snapshot.gate.clock.state ==
            neri::usb::feedback::FeedbackClockState::Failed) {
        return neri::usb::UsbRuntimeFeedbackState::Failed;
    }
    if (snapshot.state ==
            neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped &&
        snapshot.reusableAfterStop) {
        return neri::usb::UsbRuntimeFeedbackState::Locked;
    }
    switch (snapshot.gate.clock.state) {
        case neri::usb::feedback::FeedbackClockState::Acquiring:
            return neri::usb::UsbRuntimeFeedbackState::Acquiring;
        case neri::usb::feedback::FeedbackClockState::Locked:
            return neri::usb::UsbRuntimeFeedbackState::Locked;
        case neri::usb::feedback::FeedbackClockState::Holdover:
            return neri::usb::UsbRuntimeFeedbackState::Holdover;
        case neri::usb::feedback::FeedbackClockState::Relocking:
            return neri::usb::UsbRuntimeFeedbackState::Relocking;
        case neri::usb::feedback::FeedbackClockState::Disabled:
            return snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Ready ||
                snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped ||
                snapshot.state ==
                    neri::usb::feedback::ExplicitFeedbackRuntimeState::Disabled
                ? neri::usb::UsbRuntimeFeedbackState::Priming
                : neri::usb::UsbRuntimeFeedbackState::Acquiring;
        case neri::usb::feedback::FeedbackClockState::Failed:
            return neri::usb::UsbRuntimeFeedbackState::Failed;
    }
    return neri::usb::UsbRuntimeFeedbackState::Failed;
}

int64_t reportCounter(uint64_t value) {
    return value > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())
        ? std::numeric_limits<int64_t>::max()
        : static_cast<int64_t>(value);
}

uint64_t saturatedCounterSum(uint64_t first, uint64_t second) {
    return second > std::numeric_limits<uint64_t>::max() - first
        ? std::numeric_limits<uint64_t>::max()
        : first + second;
}

int64_t signedFeedbackRatePpm(
    neri::usb::feedback::FeedbackRateQ32 rateQ32,
    neri::usb::feedback::FeedbackRateQ32 nominalRateQ32
) {
    if (rateQ32 == 0 || nominalRateQ32 == 0) {
        return 0;
    }
    uint32_t magnitude = 0;
    if (neri::usb::feedback::computeRateDeltaPpm(
            rateQ32,
            nominalRateQ32,
            &magnitude
        ) != neri::usb::feedback::FeedbackMathStatus::Ok) {
        return 0;
    }
    const auto signedMagnitude = static_cast<int64_t>(magnitude);
    return rateQ32 < nominalRateQ32 ? -signedMagnitude : signedMagnitude;
}



} // namespace neri::usb::exclusive

using namespace neri::usb::exclusive;

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeRuntimeReport(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    try {
        const auto holder = acquireHandle(handleValue);
        if (holder == nullptr) {
            const std::string error = readLastOpenError();
            return env->NewStringUTF(error.c_str());
        }
        std::lock_guard<std::mutex> apiGuard(holder->apiLock);
        const std::string lastError = getErrorCopy(holder.get());
        const neri::usb::PcmPipelineSnapshot pcm = holder->player.pcmPipeline.snapshot();
        const bool explicitFeedback = holder->device.explicitFeedbackEnabled;
        const neri::usb::feedback::FeedbackInTransferSetSnapshot feedbackTransfers =
            holder->transfer.feedbackInTransferSet.snapshot();
        const neri::usb::feedback::ExplicitFeedbackRuntimeSnapshot feedbackRuntime =
            holder->transfer.feedbackRuntime.snapshot();
        const bool feedbackTerminalFailure = explicitFeedback &&
            feedbackRuntime.terminalFailure;
        const bool terminalFailure = holder->recovery.transportFailed.load() ||
            !holder->recovery.deviceOnline.load() || feedbackTerminalFailure;
        if (terminalFailure) {
            refreshTerminalRecoveryAction(holder.get(), feedbackTerminalFailure);
        }
        const neri::usb::UsbRecoveryActionSnapshot recovery =
            holder->recovery.recoveryActionLatch.snapshot();
        const bool playerPcmSource =
            holder->transfer.streamSource.load() == StreamSource::PlayerPcm;
        const int64_t outputBytes = std::max<int64_t>(0, pcm.outputBytes);
        const int64_t zeroFillBytes = std::max<int64_t>(0, pcm.zeroFillBytes);
        const int64_t outputAfterZeroFill = outputBytes > zeroFillBytes
            ? outputBytes - zeroFillBytes
            : 0;
        const int64_t pausedZeroFillBytes = std::max<int64_t>(
            0,
            pcm.pausedZeroFillBytes
        );
        const bool pipelineRealPcmReleased = playerPcmSource &&
            outputAfterZeroFill > pausedZeroFillBytes;
        const bool stoppedFeedbackReusable = explicitFeedback &&
            !feedbackRuntime.running &&
            feedbackRuntime.state ==
                neri::usb::feedback::ExplicitFeedbackRuntimeState::Stopped &&
            feedbackRuntime.reusableAfterStop &&
            !feedbackRuntime.terminalFailure;
        const bool feedbackReady = !explicitFeedback ||
            stoppedFeedbackReusable ||
            (feedbackRuntime.running &&
                feedbackClockCanStream(feedbackRuntime.gate.clock.state) &&
                !feedbackRuntime.terminalFailure);
        const bool feedbackReusable = !explicitFeedback ||
            ((stoppedFeedbackReusable ||
                (feedbackRuntime.running &&
                    (feedbackRuntime.gate.clock.state ==
                        neri::usb::feedback::FeedbackClockState::Locked ||
                     feedbackRuntime.gate.clock.state ==
                        neri::usb::feedback::FeedbackClockState::Holdover))) &&
                !terminalFailure);
        const bool realPcmReleased = explicitFeedback
            ? playerPcmSource &&
                (feedbackRuntime.realPcmReleased ||
                    (stoppedFeedbackReusable && pipelineRealPcmReleased))
            : pipelineRealPcmReleased;
        const bool canAcceptPcm = playerPcmSource &&
            holder->recovery.deviceOnline.load() &&
            !holder->recovery.closing.load() &&
            !terminalFailure &&
            !recovery.latched &&
            feedbackReady;
        const bool transportRunning = holder->transfer.running.load();
        const bool playbackReady = transportRunning &&
            feedbackReady &&
            realPcmReleased &&
            canAcceptPcm &&
            !terminalFailure;
        neri::usb::UsbRuntimeReportV2Snapshot v2Snapshot;
        const int64_t feedbackNowNs = steadyClockNanoseconds();
        const auto trustedFeedbackRateQ32 =
            feedbackRuntime.gate.clock.hasTrustedRate
                ? feedbackRuntime.gate.clock.trustedRateQ32
                : holder->transfer.feedbackNominalRateQ32;
        const uint64_t feedbackPeriodNs =
            holder->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds;
        const int64_t feedbackExpectedPeriodUs = reportCounter(
            feedbackPeriodNs / UINT64_C(1000) +
                (feedbackPeriodNs % UINT64_C(1000) == 0 ? 0 : 1)
        );
        const int64_t feedbackLastAgeMs =
            feedbackRuntime.gate.clock.lastValidSampleNs >= 0 &&
                feedbackNowNs >= feedbackRuntime.gate.clock.lastValidSampleNs
            ? (feedbackNowNs - feedbackRuntime.gate.clock.lastValidSampleNs) /
                INT64_C(1000000)
            : 0;
        const bool feedbackTimedOut =
            feedbackRuntime.gate.clock.failureReason ==
                neri::usb::feedback::FeedbackClockFailureReason::AcquireTimeout ||
            feedbackRuntime.gate.clock.failureReason ==
                neri::usb::feedback::FeedbackClockFailureReason::HoldoverTimeout;
        v2Snapshot.feedbackMode = explicitFeedback
            ? neri::usb::UsbRuntimeFeedbackMode::Explicit
            : neri::usb::UsbRuntimeFeedbackMode::Disabled;
        v2Snapshot.feedbackEndpointAddress = explicitFeedback
            ? static_cast<int>(holder->device.feedbackEndpoint)
            : 0;
        v2Snapshot.feedbackState = runtimeFeedbackState(
            explicitFeedback,
            feedbackRuntime
        );
        v2Snapshot.feedbackPayloadBytes = explicitFeedback
            ? static_cast<int>(
                holder->device.feedbackTimingProfile.decodeProfile.payloadBytesExpected
            )
            : 0;
        v2Snapshot.feedbackExpectedPeriodUs = explicitFeedback
            ? feedbackExpectedPeriodUs
            : 0;
        v2Snapshot.feedbackRawValue = explicitFeedback &&
                feedbackRuntime.validPackets > 0
            ? std::to_string(feedbackRuntime.lastRawValue)
            : "none";
        v2Snapshot.feedbackRateQ32 = explicitFeedback
            ? trustedFeedbackRateQ32
            : 0;
        v2Snapshot.feedbackRateHz = explicitFeedback
            ? neri::usb::feedback::feedbackRateHz(
                trustedFeedbackRateQ32,
                static_cast<uint32_t>(std::max(0, holder->transfer.intervalsPerSecond))
            )
            : 0.0;
        v2Snapshot.feedbackRatePpm = explicitFeedback
            ? signedFeedbackRatePpm(
                trustedFeedbackRateQ32,
                holder->transfer.feedbackNominalRateQ32
            )
            : 0;
        v2Snapshot.feedbackValidSamples = explicitFeedback
            ? reportCounter(feedbackRuntime.validPackets)
            : 0;
        v2Snapshot.feedbackInvalidSamples = explicitFeedback
            ? reportCounter(feedbackRuntime.invalidPackets)
            : 0;
        v2Snapshot.feedbackOutliers = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackRuntime.estimator.hardRangeRejects,
                feedbackRuntime.estimator.localOutliers
            ))
            : 0;
        v2Snapshot.feedbackTimeouts = explicitFeedback && feedbackTimedOut ? 1 : 0;
        v2Snapshot.feedbackLockCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.lockCount)
            : 0;
        v2Snapshot.feedbackRelockCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.relockCount)
            : 0;
        v2Snapshot.feedbackHoldoverCount = explicitFeedback
            ? reportCounter(feedbackRuntime.gate.clock.holdoverCount)
            : 0;
        uint64_t feedbackHoldoverTotalNs = explicitFeedback
            ? feedbackRuntime.gate.clock.holdoverTotalNs
            : 0;
        const bool feedbackHoldoverActive = explicitFeedback &&
            feedbackRuntime.running &&
            !feedbackRuntime.terminalFailure &&
            (feedbackRuntime.gate.clock.state ==
                neri::usb::feedback::FeedbackClockState::Holdover ||
             feedbackRuntime.gate.clock.state ==
                neri::usb::feedback::FeedbackClockState::Relocking) &&
            feedbackRuntime.gate.clock.holdoverStartedNs >= 0 &&
            feedbackNowNs >= feedbackRuntime.gate.clock.holdoverStartedNs;
        if (feedbackHoldoverActive) {
            feedbackHoldoverTotalNs = saturatedCounterSum(
                feedbackHoldoverTotalNs,
                static_cast<uint64_t>(
                    feedbackNowNs -
                        feedbackRuntime.gate.clock.holdoverStartedNs
                )
            );
        }
        v2Snapshot.feedbackHoldoverTotalMs = explicitFeedback
            ? reportCounter(feedbackHoldoverTotalNs / UINT64_C(1000000))
            : 0;
        v2Snapshot.feedbackLongGapReacquisitions = explicitFeedback
            ? reportCounter(feedbackRuntime.longGapReacquisitions)
            : 0;
        v2Snapshot.feedbackLastAgeMs = explicitFeedback
            ? feedbackLastAgeMs
            : 0;
        v2Snapshot.feedbackClockFailure = explicitFeedback
            ? neri::usb::feedback::feedbackClockFailureReasonName(
                feedbackRuntime.gate.clock.failureReason
            )
            : "none";
        v2Snapshot.feedbackInFlight = explicitFeedback
            ? static_cast<int>(feedbackTransfers.inFlight)
            : 0;
        v2Snapshot.feedbackTransferErrors = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackTransfers.transferErrors,
                feedbackTransfers.cancelErrors
            ))
            : 0;
        v2Snapshot.feedbackPacketErrors = explicitFeedback
            ? reportCounter(saturatedCounterSum(
                feedbackTransfers.packetErrors,
                feedbackTransfers.invalidLengths
            ))
            : 0;
        v2Snapshot.transportRunning = transportRunning;
        v2Snapshot.feedbackReady = feedbackReady;
        v2Snapshot.realPcmReleased = realPcmReleased;
        v2Snapshot.canAcceptPcm = canAcceptPcm;
        v2Snapshot.playbackReady = playbackReady;
        v2Snapshot.feedbackReusable = feedbackReusable;
        v2Snapshot.terminalFailure = terminalFailure;
        v2Snapshot.nativeStreamGeneration = holder->recovery.nativeStreamGeneration;
        v2Snapshot.candidateId = runtimeCandidateId(holder.get());
        v2Snapshot.recoveryEpoch = holder->recovery.recoveryEpoch;
        v2Snapshot.recommendedAction = recovery.action;
        v2Snapshot.actionId = recovery.id;
        v2Snapshot.actionGeneration = recovery.generation;
        v2Snapshot.actionOwner = recovery.latched
            ? neri::usb::UsbRuntimeRecoveryOwner::Kotlin
            : neri::usb::UsbRuntimeRecoveryOwner::None;
        v2Snapshot.actionLatched = recovery.latched;
        v2Snapshot.errorCode = runtimeErrorCode(holder.get(), lastError);
        std::string v2Fields;
        std::string v2BuildError;
        if (!neri::usb::buildUsbRuntimeReportV2Fields(
                v2Snapshot,
                &v2Fields,
                &v2BuildError
            )) {
            LOGE("nativeRuntimeReport v2 build failed: %s", v2BuildError.c_str());
            v2Fields = "reportVersion=2 reportBuildError=" + v2BuildError;
        }
        const int64_t stagedPlayerFrames = holder->player.stagedPlayerFrames.load();
        const int64_t replayPlayerFrames = queuedPlayerReplayFrames(holder.get());
        const int64_t queuedFrames = static_cast<int64_t>(
            pcm.levelBytes / static_cast<size_t>(std::max(1, holder->device.frameBytes))
        ) + stagedPlayerFrames + replayPlayerFrames;
        const int64_t fifoMs = holder->device.sampleRate > 0 && holder->device.frameBytes > 0
            ? static_cast<int64_t>(pcm.levelBytes) * 1000 /
                (static_cast<int64_t>(holder->device.sampleRate) * holder->device.frameBytes)
            : 0;
        const int64_t bufferMs = holder->device.sampleRate > 0 && holder->device.frameBytes > 0
            ? static_cast<int64_t>(pcm.capacityBytes) * 1000 /
                (static_cast<int64_t>(holder->device.sampleRate) * holder->device.frameBytes)
            : 0;
        const int minimumPacketFrames = holder->transfer.packetFramesMin.load() == std::numeric_limits<int>::max()
            ? 0
            : holder->transfer.packetFramesMin.load();
        std::string claimedInterfaceSummary;
        for (const ClaimedUsbInterface& entry : holder->device.claimedAudioInterfaces) {
            if (!claimedInterfaceSummary.empty()) {
                claimedInterfaceSummary += ',';
            }
            claimedInterfaceSummary += std::to_string(entry.interfaceNumber);
        }
        if (claimedInterfaceSummary.empty()) {
            claimedInterfaceSummary = "none";
        }
        const std::string report =
            v2Fields +
            " iface=" + std::to_string(holder->device.audioStreamingInterface) +
            " acIface=" + std::to_string(holder->device.audioControlInterface) +
            " alt=" + std::to_string(holder->device.alternateSetting) +
            " streamAltActive=" + std::string(
                holder->device.streamingAlternateActive ? "true" : "false"
            ) +
            " streamAltTransitions=" +
                std::to_string(holder->device.streamingAlternateTransitions) +
            " streamAltResetFailures=" +
                std::to_string(holder->device.streamingAlternateResetFailures) +
            " streamAltStatus=" + holder->device.streamingAlternateStatus +
            " claimedIfaces=" + claimedInterfaceSummary +
            " fullFunctionClaim=" + std::string(
                holder->device.completeAudioFunctionClaim ? "true" : "false"
            ) +
            " outEp=0x" + [&]() {
                char buf[8];
                snprintf(buf, sizeof(buf), "%02X", holder->device.outEndpoint);
                return std::string(buf);
            }() +
            " source=" + std::string(sourceName(holder->transfer.streamSource.load())) +
            " uacVersion=" + std::string(
                holder->device.uacVersion == 1
                    ? "1.0"
                    : holder->device.uacVersion == 2 ? "2.0" : "unsupported"
            ) +
            " clockEntity=" + std::to_string(holder->device.uacClockSourceId) +
            " sampleRate=" + std::to_string(holder->device.sampleRate) +
            " negotiatedRate=" + std::to_string(holder->device.negotiatedSampleRate) +
            " descriptorRates=" + holder->device.descriptorSampleRates +
            " rateControl=" + holder->device.sampleRateControlStatus +
            " channels=" + std::to_string(holder->device.channelCount) +
            " bits=" + std::to_string(holder->device.bitsPerSample) +
            " subslotBytes=" + std::to_string(holder->device.subslotBytes) +
            " formatSelection=" + holder->device.formatSelectionReason +
            " syncType=" + holder->device.endpointSyncType +
            " feedback=" + holder->device.endpointFeedback +
            " usbSpeed=" + std::to_string(holder->device.usbSpeed) +
            " packetBytes=" + std::to_string(holder->transfer.bytesPerUsbFrame) +
            " packetFrames=" + std::to_string(minimumPacketFrames) + ".." +
                std::to_string(holder->transfer.packetFramesMax.load()) +
            " endpointMaxPacketBytes=" + std::to_string(holder->device.endpointMaxPacketBytes) +
            " interval=" + std::to_string(holder->device.endpointInterval) +
            " intervalsPerSecond=" + std::to_string(holder->transfer.intervalsPerSecond) +
            " transferBytes=" + std::to_string(holder->transfer.transferBytes) +
            " transferCount=" + std::to_string(holder->transfer.transferCount) +
            " baseTransferCount=" + std::to_string(holder->transfer.baseTransferCount) +
            " targetTransferCount=" +
                std::to_string(holder->transfer.targetTransferCount.load()) +
            " lastTransferBytes=" + std::to_string(holder->transfer.lastTransferBytes.load()) +
            " deviceOnline=" + std::string(holder->recovery.deviceOnline.load() ? "true" : "false") +
            " noDeviceObserved=" + std::string(
                holder->recovery.noDeviceObserved.load() ? "true" : "false"
            ) +
            " detachConfirmed=" + std::string(
                holder->recovery.detachBroadcastConfirmed.load() ? "true" : "false"
            ) +
            " focusMuted=" + std::string(holder->player.focusMuted.load() ? "true" : "false") +
            " running=" + std::string(holder->transfer.running.load() ? "true" : "false") +
            " paused=" + std::string(holder->player.playerPaused.load() ? "true" : "false") +
            " transportFailed=" + std::string(holder->recovery.transportFailed.load() ? "true" : "false") +
            " inFlight=" + std::to_string(holder->transfer.inFlightTransfers.load()) +
            " completedTransfers=" + std::to_string(holder->transfer.completedTransfers.load()) +
            " submitErrors=" + std::to_string(holder->transfer.submitErrors.load()) +
            " isoPacketErrors=" + std::to_string(holder->transfer.isoPacketErrors.load()) +
            " isoPacketErrorTransfers=" +
                std::to_string(holder->transfer.isoPacketErrorTransfers.load()) +
            " isoPacketErrorScore=" + std::to_string(holder->transfer.isoPacketErrorScore.load()) +
            " scheduledPackets=" + std::to_string(holder->transfer.scheduledPackets.load()) +
            " scheduledFrames=" + std::to_string(holder->transfer.scheduledFrames.load()) +
            " pcmLevel=" + std::to_string(pcm.levelBytes) + "/" +
                std::to_string(pcm.capacityBytes) +
            " pcmFreeBytes=" + std::to_string(pcm.freeBytes) +
            " pcmMaxLevelBytes=" + std::to_string(pcm.maxLevelBytes) +
            " pcmBackpressureEvents=" + std::to_string(pcm.backpressureEvents) +
            " pcmBackpressureTotalMs=" + std::to_string(pcm.backpressureTotalUs / 1000) +
            " pcmBackpressureCurrentMs=" + std::to_string(pcm.backpressureCurrentUs / 1000) +
            " pcmBackpressureMaxMs=" + std::to_string(pcm.backpressureMaxUs / 1000) +
            " bufferMs=" + std::to_string(bufferMs) +
            " requestedBufferMs=" + std::to_string(holder->player.pcmRingDurationMs) +
            " fifoMs=" + std::to_string(fifoMs) +
            " queuedFrames=" + std::to_string(queuedFrames) +
            " stagedFrames=" + std::to_string(stagedPlayerFrames) +
            " replayFrames=" + std::to_string(replayPlayerFrames) +
            " startupPrerollFrames=" +
                std::to_string(holder->player.playerStartupPreroll.framesRemaining()) +
            " completedAudioFrames=" + std::to_string(holder->player.completedAudioFrames.load()) +
            " playerInputBytes=" + std::to_string(pcm.inputBytes) +
            " playerOutputBytes=" + std::to_string(pcm.outputBytes) +
            " playerDroppedBytes=" + std::to_string(pcm.droppedBytes) +
            " playerUnderrunBytes=" + std::to_string(pcm.underrunBytes) +
            " playerZeroFillBytes=" + std::to_string(pcm.zeroFillBytes) +
            " playerPausedZeroFillBytes=" + std::to_string(pcm.pausedZeroFillBytes) +
            " playerSignalFrames=" + std::to_string(pcm.signalOutputFrames) +
            " playerSilentFrames=" + std::to_string(pcm.silentOutputFrames) +
            " playerSignalBytes=" + std::to_string(pcm.signalOutputBytes) +
            " outputPeak=" + std::to_string(pcm.outputPeak) +
            " lastOutputPeak=" + std::to_string(pcm.lastOutputPeak) +
            " channel0OutputPeak=" + std::to_string(pcm.channel0OutputPeak) +
            " channel1OutputPeak=" + std::to_string(pcm.channel1OutputPeak) +
            " lastChannel0OutputPeak=" +
                std::to_string(pcm.lastChannel0OutputPeak) +
            " lastChannel1OutputPeak=" +
                std::to_string(pcm.lastChannel1OutputPeak) +
            " targetGain=" + std::to_string(pcm.targetGain) +
            " appliedGain=" + std::to_string(pcm.appliedGain) +
            " lastError=" + (lastError.empty() ? "none" : lastError);
        return env->NewStringUTF(report.c_str());
    } catch (const std::exception& error) {
        LOGE("nativeRuntimeReport exception: %s", error.what());
        return env->NewStringUTF("native_runtime_report_unavailable");
    } catch (...) {
        LOGE("nativeRuntimeReport unknown exception");
        return env->NewStringUTF("native_runtime_report_unavailable");
    }
}
