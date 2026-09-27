#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <limits>
#include <memory>
#include <new>
#include <system_error>
#include <sys/resource.h>
#include <unordered_map>

#include "libusb/libusb.h"
#include "usb/exclusive/usb_exclusive_device_selection.h"
#include "usb/exclusive/usb_exclusive_session_internal.h"
#include "usb/exclusive/usb_player_replay_buffer.h"
#include "usb/exclusive/usb_player_startup_preroll.h"
#include "usb/exclusive/usb_recovery_action_latch.h"
#include "usb/exclusive/usb_runtime_report_v2.h"
#include "usb/exclusive/usb_streaming_interface_lifecycle.h"
#include "usb/exclusive/usb_streaming_sync_policy.h"
#include "usb/feedback/usb_explicit_feedback_runtime.h"
#include "usb/feedback/usb_feedback_in_transfer_set.h"
#include "usb/feedback/usb_feedback_rate_math.h"
#include "usb/feedback/usb_libusb_feedback_backend.h"
#include "usb/iso/usb_iso_packet_scheduler.h"
#include "usb/iso/usb_iso_transfer_window.h"
#include "usb/iso/usb_iso_transfer_health.h"
#include "usb/pcm/usb_pcm_pipeline.h"
#include "usb/uac1/usb_uac1_format.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"
#include "usb/uac2/usb_uac2_format.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

bool allocateTransfers(UsbExclusiveHandle* handle);
bool refillTransfer(UsbExclusiveHandle* handle, libusb_transfer* transfer);
void eventLoopThread(UsbExclusiveHandle* handle) noexcept;

void interruptUsbEventHandler(UsbExclusiveHandle* handle) {
    if (handle != nullptr && handle->device.ctx != nullptr) {
        libusb_interrupt_event_handler(handle->device.ctx);
    }
}

bool shouldStopTransferSubmission(const UsbExclusiveHandle* handle) {
    return handle == nullptr ||
        !handle->recovery.deviceOnline.load() ||
        handle->recovery.stopRequested.load() ||
        handle->recovery.closing.load() ||
        handle->recovery.transportFailed.load();
}

void configureUsbEventThreadPriority() {
    errno = 0;
    const int rc = setpriority(PRIO_PROCESS, 0, kUrgentAudioThreadPriority);
    if (rc == 0) {
        LOGI("USB event thread priority raised for audio stability");
        return;
    }
    LOGW("USB event thread priority unchanged: errno=%d %s", errno, strerror(errno));
}

int64_t steadyClockMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}


const char* sourceName(StreamSource source) {
    return source == StreamSource::PlayerPcm ? "player_pcm" : "tone";
}

bool feedbackTransfersOutstanding(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return false;
    }
    const auto snapshot = handle->transfer.feedbackInTransferSet.snapshot();
    return snapshot.inFlight > 0 || snapshot.callbacksInProgress > 0;
}

bool streamTransfersOutstanding(const UsbExclusiveHandle* handle) {
    return handle != nullptr &&
        (handle->transfer.inFlightTransfers.load() > 0 ||
            feedbackTransfersOutstanding(handle));
}

bool feedbackTransferSetActive(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return false;
    }
    return handle->transfer.feedbackInTransferSet.snapshot().state !=
        neri::usb::feedback::FeedbackInTransferSetState::Empty;
}

bool configureExplicitFeedbackRuntime(
    UsbExclusiveHandle* handle,
    std::string* error
) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        if (error != nullptr) {
            error->clear();
        }
        return true;
    }
    if (handle->device.feedbackTimingProfile.status !=
        neri::usb::uac2::Uac2FeedbackProfileStatus::Valid) {
        if (error != nullptr) {
            *error = "feedback_profile_invalid";
        }
        return false;
    }
    neri::usb::feedback::FeedbackRateQ32 nominalRateQ32 = 0;
    const auto nominalStatus = neri::usb::feedback::makeFeedbackRateQ32(
        static_cast<uint32_t>(handle->device.sampleRate),
        static_cast<uint32_t>(handle->transfer.intervalsPerSecond),
        &nominalRateQ32
    );
    if (nominalStatus != neri::usb::feedback::FeedbackMathStatus::Ok ||
        handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds == 0 ||
        handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds >
            static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
        if (error != nullptr) {
            *error = "feedback_nominal_rate_invalid";
        }
        return false;
    }

    const auto currentTransfers = handle->transfer.feedbackInTransferSet.snapshot();
    if (currentTransfers.state !=
            neri::usb::feedback::FeedbackInTransferSetState::Empty ||
        currentTransfers.inFlight != 0 ||
        currentTransfers.callbacksInProgress != 0) {
        if (error != nullptr) {
            *error = "feedback_transfer_set_not_drained";
        }
        return false;
    }

    handle->transfer.feedbackNominalRateQ32 = nominalRateQ32;
    const neri::usb::feedback::ExplicitFeedbackRuntimeConfig config {
        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration),
        handle->device.feedbackTimingProfile.decodeProfile,
        nominalRateQ32,
        static_cast<int64_t>(
            handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds
        ),
        static_cast<uint32_t>(std::max(1, handle->device.frameBytes)),
        static_cast<uint32_t>(std::max(1, handle->device.endpointMaxPacketBytes)),
        kExplicitFeedbackBootstrapPacketLimit,
        handle->device.feedbackTimingProfile.zeroLengthReportPermitted
    };
    if (!handle->transfer.feedbackRuntime.configure(config)) {
        if (error != nullptr) {
            *error = "feedback_runtime_configure_failed";
        }
        return false;
    }
    const neri::usb::feedback::FeedbackInTransferConfig transferConfig {
        handle->device.devh,
        handle->device.feedbackEndpoint,
        static_cast<uint32_t>(std::max(1, handle->device.feedbackEndpointMaxPacketBytes)),
        kExplicitFeedbackTransferCount,
        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration)
    };
    std::string transferError;
    if (!handle->transfer.feedbackInTransferSet.allocate(
            transferConfig,
            &handle->transfer.feedbackRuntime,
            &transferError
        )) {
        handle->transfer.feedbackRuntime.stop();
        if (error != nullptr) {
            *error = transferError.empty()
                ? "feedback_transfer_allocate_failed"
                : transferError;
        }
        return false;
    }
    if (!handle->transfer.feedbackRuntime.start(steadyClockNanoseconds())) {
        std::string ignoredError;
        handle->transfer.feedbackInTransferSet.beginStop(&ignoredError);
        handle->transfer.feedbackInTransferSet.freeDrained(&ignoredError);
        if (error != nullptr) {
            *error = "feedback_runtime_start_failed";
        }
        return false;
    }
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool stopExplicitFeedbackRuntime(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return true;
    }
    handle->transfer.feedbackRuntime.stop();
    std::string error;
    const bool stopped = handle->transfer.feedbackInTransferSet.beginStop(&error);
    if (!stopped && !error.empty()) {
        setError(handle, error);
    }
    return stopped;
}

bool freeExplicitFeedbackTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return true;
    }
    const auto snapshot = handle->transfer.feedbackInTransferSet.snapshot();
    if (snapshot.state == neri::usb::feedback::FeedbackInTransferSetState::Empty) {
        return true;
    }
    std::string error;
    if (!handle->transfer.feedbackInTransferSet.freeDrained(&error)) {
        if (!error.empty()) {
            setError(handle, error);
        }
        return false;
    }
    return true;
}

void fillToneBuffer(UsbExclusiveHandle* handle, uint8_t* buffer, size_t bytes) {
    if (handle == nullptr || buffer == nullptr || bytes == 0 ||
        handle->device.frameBytes <= 0 || handle->device.channelCount <= 0 || handle->device.subslotBytes <= 0) {
        return;
    }
    const int frames = static_cast<int>(bytes) / handle->device.frameBytes;
    const auto sampleRate = static_cast<double>(handle->device.sampleRate);
    const double phaseStep = 2.0 * M_PI * static_cast<double>(kGeneratedToneFrequencyHz) / sampleRate;
    const double amplitude = 0.30;

    for (int frame = 0; frame < frames; ++frame) {
        const double sample = std::sin(handle->transfer.tonePhase) * amplitude;
        handle->transfer.tonePhase += phaseStep;
        if (handle->transfer.tonePhase > 2.0 * M_PI) {
            handle->transfer.tonePhase -= 2.0 * M_PI;
        }

        for (int ch = 0; ch < handle->device.channelCount; ++ch) {
            const int offset = frame * handle->device.frameBytes + ch * handle->device.subslotBytes;
            neri::usb::writeIntegerPcmSample(
                buffer + offset,
                handle->device.subslotBytes,
                handle->device.bitsPerSample,
                static_cast<float>(sample)
            );
        }
    }
}

bool startStreamingInternal(
    UsbExclusiveHandle* handle,
    StreamSource source
) {
    if (handle == nullptr || handle->device.devh == nullptr || !handle->recovery.deviceOnline.load() ||
        handle->recovery.closing.load()) {
        LOGW("startStreamingInternal rejected: invalid handle");
        return false;
    }
    LOGI(
        "startStreamingInternal request: source=%s running=%d failed=%d playback=%d "
        "transferBytes=%d transferCount=%d packets=%d",
        sourceName(source),
        handle->transfer.running.load() ? 1 : 0,
        handle->recovery.transportFailed.load() ? 1 : 0,
        handle->player.playbackEnabled.load() ? 1 : 0,
        handle->transfer.transferBytes,
        handle->transfer.transferCount,
        handle->transfer.packetsPerTransfer
    );
    if (
        handle->transfer.running.load() ||
        !handle->transfer.transfers.empty() ||
        handle->transfer.eventThread.joinable()
    ) {
        {
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            if (!handle->recovery.deviceOnline.load() || handle->recovery.stopRequested.load() ||
                handle->recovery.closing.load()) {
                return false;
            }
            if (!handle->recovery.transportFailed.load() && handle->transfer.streamSource.load() == source) {
                return true;
            }
        }
        LOGW(
            "startStreamingInternal restarts active stream: oldSource=%s newSource=%s failed=%d",
            sourceName(handle->transfer.streamSource.load()),
            sourceName(source),
            handle->recovery.transportFailed.load() ? 1 : 0
        );
        if (!stopStreamingInternal(handle)) {
            return false;
        }
    }

    clearError(handle);
    handle->transfer.streamSource.store(source);
    handle->transfer.completedTransfers.store(0);
    handle->transfer.submitErrors.store(0);
    handle->transfer.isoPacketErrors.store(0);
    handle->transfer.isoPacketErrorTransfers.store(0);
    handle->transfer.isoPacketErrorScore.store(0);
    handle->transfer.scheduledPackets.store(0);
    handle->transfer.scheduledFrames.store(0);
    handle->transfer.packetFramesMin.store(std::numeric_limits<int>::max());
    handle->transfer.packetFramesMax.store(0);
    handle->transfer.lastTransferBytes.store(0);
    handle->player.shortWriteWarnings.store(0);
    handle->transfer.firstTransferSubmittedAtMs.store(0);
    handle->transfer.lastTransferCompletionAtMs.store(0);
    handle->transfer.packetScheduler.reset();
    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (!handle->recovery.deviceOnline.load() || handle->recovery.closing.load()) {
            setError(handle, "stream_start_cancelled_device_offline");
            return false;
        }
        handle->recovery.stopRequested.store(false);
        handle->recovery.transportFailed.store(false);
        handle->transfer.inFlightTransfers.store(0);
    }
    std::string interfaceActivationError;
    if (!activateStreamingAlternate(handle, &interfaceActivationError)) {
        setError(
            handle,
            interfaceActivationError.empty()
                ? "streaming_interface_activation_failed"
                : interfaceActivationError
        );
        markTransportFailed(handle);
        parkStreamingAlternate(handle, "stream_start_failed");
        return false;
    }
    assignNewNativeStreamGeneration(handle);
    if (handle->device.explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!configureExplicitFeedbackRuntime(handle, &feedbackError)) {
            setError(
                handle,
                feedbackError.empty()
                    ? "feedback_runtime_configure_failed"
                    : feedbackError
            );
            markTransportFailed(handle);
            parkStreamingAlternate(handle, "stream_start_feedback_config_failed");
            return false;
        }
    }
    if (source == StreamSource::PlayerPcm) {
        handle->player.playerStartupPreroll.arm(handle->device.sampleRate, kPlayerStartupPrerollMs);
        handle->player.pcmPipeline.armTransportStartRamp();
    }
    if (!allocateTransfers(handle)) {
        LOGE("allocateTransfers failed before stream start: error=%s", getErrorCopy(handle).c_str());
        freeTransfers(handle);
        markTransportFailed(handle);
        parkStreamingAlternate(handle, "stream_start_transfer_allocation_failed");
        return false;
    }

    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle)) {
            setError(handle, "stream_start_cancelled_after_allocation");
        } else {
            handle->transfer.running.store(true);
        }
    }
    if (!handle->transfer.running.load()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (handle->device.explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!handle->transfer.feedbackInTransferSet.submitAll(&feedbackError)) {
            setError(
                handle,
                feedbackError.empty()
                    ? "feedback_transfer_submit_failed"
                    : feedbackError
            );
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
    }
    const int initialTransferCount = std::min<int>(
        handle->transfer.baseTransferCount,
        static_cast<int>(handle->transfer.transfers.size())
    );
    for (int index = 0; index < initialTransferCount; ++index) {
        libusb_transfer* transfer = handle->transfer.transfers[static_cast<size_t>(index)];
        int rc = LIBUSB_ERROR_NO_DEVICE;
        bool cancelled = false;
        bool refilled = false;
        {
            std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            cancelled = shouldStopTransferSubmission(handle) || !handle->transfer.running.load();
            if (!cancelled) {
                refilled = refillTransfer(handle, transfer);
                if (refilled) {
                    rc = libusb_submit_transfer(transfer);
                    if (rc == LIBUSB_SUCCESS) {
                        const int64_t submittedAtMs = steadyClockMillis();
                        int64_t firstSubmittedAtMs = 0;
                        if (handle->transfer.firstTransferSubmittedAtMs.compare_exchange_strong(
                                firstSubmittedAtMs,
                                submittedAtMs
                            )) {
                            handle->transfer.lastTransferCompletionAtMs.store(submittedAtMs);
                        }
                        handle->transfer.transferSubmitted[static_cast<size_t>(index)] = 1;
                        handle->transfer.inFlightTransfers.fetch_add(1);
                    }
                }
            }
        }
        if (cancelled) {
            setError(handle, "stream_start_cancelled_during_submit");
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
        if (!refilled) {
            setError(handle, "initial_transfer_refill_failed");
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
        if (rc != LIBUSB_SUCCESS) {
            setError(handle, std::string("submit_failed:") + libusbErrName(rc));
            LOGE("libusb_submit_transfer failed: %s", libusbErrName(rc));
            markTransportFailed(handle);
            if (!stopStreamingInternal(handle)) {
                return false;
            }
            return false;
        }
    }
    try {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->transfer.running.load()) {
            setError(handle, "stream_start_cancelled_before_event_thread");
        } else {
            handle->transfer.eventThread = std::thread(eventLoopThread, handle);
        }
    } catch (const std::system_error& error) {
        setError(handle, std::string("event_thread_start_failed:") + error.what());
        markTransportFailed(handle);
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (!handle->transfer.eventThread.joinable()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }

    LOGI(
        "native stream started: source=%s inFlight=%d transferBytes=%d packetBytes=%d",
        sourceName(source),
        handle->transfer.inFlightTransfers.load(),
        handle->transfer.transferBytes,
        handle->transfer.bytesPerUsbFrame
    );
    return true;
}

bool startStreamingSafely(UsbExclusiveHandle* handle, StreamSource source) noexcept {
    try {
        return startStreamingInternal(handle, source);
    } catch (const std::exception& error) {
        if (handle != nullptr) {
            markTransportFailed(handle);
            try {
                setError(handle, std::string("stream_start_exception:") + error.what());
            } catch (...) {
            }
        }
        LOGE("native stream start exception: %s", error.what());
        return false;
    } catch (...) {
        if (handle != nullptr) {
            markTransportFailed(handle);
            try {
                setError(handle, "stream_start_unknown_exception");
            } catch (...) {
            }
        }
        LOGE("native stream start unknown exception");
        return false;
    }
}

void updateAtomicMinimum(std::atomic<int>& value, int candidate) {
    int current = value.load();
    while (candidate < current && !value.compare_exchange_weak(current, candidate)) {
    }
}

void updateAtomicMaximum(std::atomic<int>& value, int candidate) {
    int current = value.load();
    while (candidate > current && !value.compare_exchange_weak(current, candidate)) {
    }
}

const char* explicitFeedbackFailureError(
    neri::usb::feedback::ExplicitFeedbackRuntimeFailure failure
) {
    using Failure = neri::usb::feedback::ExplicitFeedbackRuntimeFailure;
    switch (failure) {
        case Failure::InvalidConfiguration:
            return "feedback_runtime_invalid_configuration";
        case Failure::TransferCancelled:
            return "feedback_transfer_cancelled";
        case Failure::TransferFailed:
            return "feedback_transfer_failed";
        case Failure::DeviceDetached:
            return "feedback_device_detached";
        case Failure::FeedbackClock:
            return "feedback_lost";
        case Failure::PacketCapacity:
            return "feedback_packet_capacity_exceeded";
        case Failure::InternalInvariant:
            return "feedback_runtime_internal_invariant";
        case Failure::None:
            return "feedback_runtime_failed";
    }
    return "feedback_runtime_failed";
}

void failForExplicitFeedbackRuntime(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->device.explicitFeedbackEnabled) {
        return;
    }
    const auto snapshot = handle->transfer.feedbackRuntime.snapshot();
    if (snapshot.failure ==
        neri::usb::feedback::ExplicitFeedbackRuntimeFailure::DeviceDetached) {
        requestNoDeviceStop(handle);
    }
    const bool initialLockFailure =
        snapshot.failure ==
            neri::usb::feedback::ExplicitFeedbackRuntimeFailure::FeedbackClock &&
        snapshot.validPackets == 0 && !snapshot.realPcmReleased;
    setError(
        handle,
        initialLockFailure
            ? "feedback_initial_lock_timeout"
            : explicitFeedbackFailureError(snapshot.failure)
    );
    markTransportFailed(handle);
}

int applyIsoPacketLengths(
    UsbExclusiveHandle* handle,
    libusb_transfer* transfer
) {
    if (handle == nullptr || transfer == nullptr || handle->transfer.bytesPerUsbFrame <= 0) {
        return -1;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    if (userData != nullptr) {
        userData->forceSilence = false;
    }
    const int packetCount = transfer->num_iso_packets;
    int totalAssigned = 0;
    const bool explicitFeedback = handle->device.explicitFeedbackEnabled;
    bool allowRealPayload = false;
    if (explicitFeedback) {
        const auto snapshot = handle->transfer.feedbackRuntime.snapshot();
        const auto clockState = snapshot.gate.clock.state;
        const bool feedbackUsable =
            clockState == neri::usb::feedback::FeedbackClockState::Locked ||
            clockState == neri::usb::feedback::FeedbackClockState::Holdover ||
            clockState == neri::usb::feedback::FeedbackClockState::Relocking;
        const bool sourceAvailable = handle->transfer.streamSource.load() == StreamSource::Tone ||
            (handle->player.playbackEnabled.load() &&
                handle->recovery.deviceOnline.load() &&
                !handle->player.focusMuted.load());
        allowRealPayload = feedbackUsable && sourceAvailable &&
            !snapshot.terminalFailure;
        if (userData != nullptr) {
            userData->forceSilence = !allowRealPayload;
        }
    }
    for (int packetIndex = 0; packetIndex < packetCount; ++packetIndex) {
        int packetBytes = 0;
        int packetFrames = 0;
        if (explicitFeedback) {
            const auto plan = handle->transfer.feedbackRuntime.nextPacket(allowRealPayload);
            if (plan.status ==
                    neri::usb::feedback::StreamGatePacketStatus::TerminalFailure ||
                plan.packet.status != neri::usb::feedback::FeedbackMathStatus::Ok) {
                failForExplicitFeedbackRuntime(handle);
                return -1;
            }
            if (plan.status !=
                    neri::usb::feedback::StreamGatePacketStatus::ZeroBootstrap &&
                plan.status !=
                    neri::usb::feedback::StreamGatePacketStatus::PlayerPacket) {
                setError(handle, "feedback_packet_scheduler_not_ready");
                markTransportFailed(handle);
                return -1;
            }
            if (plan.allZero && userData != nullptr) {
                userData->forceSilence = true;
            }
            packetBytes = static_cast<int>(plan.packet.bytes);
            packetFrames = static_cast<int>(plan.packet.frames);
        } else {
            const neri::usb::IsoPacketPlan plan = handle->transfer.packetScheduler.next();
            packetBytes = plan.bytes;
            packetFrames = plan.frames;
        }
        if (packetBytes < 0 || packetBytes > handle->device.endpointMaxPacketBytes) {
            setError(handle, "scheduled_packet_exceeds_endpoint_capacity");
            markTransportFailed(handle);
            return -1;
        }
        transfer->iso_packet_desc[packetIndex].length = packetBytes;
        totalAssigned += packetBytes;
        handle->transfer.scheduledPackets.fetch_add(1);
        handle->transfer.scheduledFrames.fetch_add(packetFrames);
        updateAtomicMinimum(handle->transfer.packetFramesMin, packetFrames);
        updateAtomicMaximum(handle->transfer.packetFramesMax, packetFrames);
    }
    transfer->length = totalAssigned;
    handle->transfer.lastTransferBytes.store(totalAssigned);
    return totalAssigned;
}

void subtractAtomicFloorZero(std::atomic<int64_t>& value, int64_t amount) {
    int64_t current = value.load();
    while (true) {
        const int64_t updated = std::max<int64_t>(0, current - amount);
        if (value.compare_exchange_weak(current, updated)) {
            return;
        }
    }
}

int64_t queuedPlayerReplayFrames(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->device.frameBytes <= 0) {
        return 0;
    }
    return static_cast<int64_t>(
        handle->player.playerReplayBuffer.queuedBytes() / static_cast<size_t>(handle->device.frameBytes)
    );
}

void clearPlayerReplayState(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->player.playerReplayBuffer.clear();
    handle->player.nextPlayerSequence.store(1);
    handle->player.preserveCancelledPlayerFrames.store(false);
    handle->player.playerReplayFailed.store(false);
}

bool preserveCancelledPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    const libusb_transfer* transfer,
    int64_t completedPrefixFrames
) {
    if (handle == nullptr || userData == nullptr || transfer == nullptr ||
        userData->queuedPlayerFrames <= 0) {
        return false;
    }
    const int slot = userData->slot;
    if (slot < 0 || slot >= static_cast<int>(handle->transfer.transferBuffers.size()) ||
        handle->device.frameBytes <= 0 || userData->playerSequence == 0) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    const int64_t queuedFrames = userData->queuedPlayerFrames;
    const int64_t completedFrames = std::clamp<int64_t>(
        completedPrefixFrames,
        0,
        queuedFrames
    );
    const int64_t replayFrames = queuedFrames - completedFrames;
    const size_t replayOffset = static_cast<size_t>(completedFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    const size_t replayBytes = static_cast<size_t>(replayFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    const auto& buffer = handle->transfer.transferBuffers[static_cast<size_t>(slot)];
    if (replayOffset + replayBytes > buffer.size() || transfer->buffer == nullptr ||
        (replayBytes > 0 && !handle->player.playerReplayBuffer.push(
            userData->playerSequence,
            transfer->buffer + replayOffset,
            replayBytes
        ))) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, queuedFrames);
    if (completedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(completedFrames);
    }
    return true;
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    int64_t completedFrames
) {
    if (handle == nullptr || userData == nullptr || userData->queuedPlayerFrames <= 0) {
        return;
    }
    const int64_t frames = userData->queuedPlayerFrames;
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, frames);
    const int64_t boundedCompletedFrames = std::clamp<int64_t>(completedFrames, 0, frames);
    if (boundedCompletedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(boundedCompletedFrames);
    }
    const int64_t droppedFrames = frames - boundedCompletedFrames;
    if (droppedFrames > 0) {
        handle->player.pcmPipeline.addDroppedFrames(droppedFrames);
    }
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    bool completed
) {
    const int64_t queuedFrames = userData != nullptr ? userData->queuedPlayerFrames : 0;
    settlePreparedPlayerFrames(handle, userData, completed ? queuedFrames : 0);
}

bool refillTransfer(
    UsbExclusiveHandle* handle,
    libusb_transfer* transfer
) {
    if (handle == nullptr || transfer == nullptr) {
        return false;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    const int slot = userData != nullptr ? userData->slot : -1;
    if (slot < 0 || slot >= static_cast<int>(handle->transfer.transferBuffers.size())) {
        return false;
    }
    auto& buffer = handle->transfer.transferBuffers[slot];
    transfer->buffer = buffer.data();
    const int transferBytes = applyIsoPacketLengths(handle, transfer);
    if (transferBytes < 0 || static_cast<size_t>(transferBytes) > buffer.size()) {
        return false;
    }

    if (handle->transfer.streamSource.load() == StreamSource::PlayerPcm) {
        const auto transferSize = static_cast<size_t>(transferBytes);
        if ((userData != nullptr && userData->forceSilence) ||
            handle->player.playerStartupPreroll.fillSilenceIfNeeded(
                buffer.data(),
                transferSize,
                handle->device.frameBytes
            )) {
            if (userData != nullptr && userData->forceSilence) {
                std::memset(buffer.data(), 0, transferSize);
            }
            if (userData != nullptr) {
                userData->queuedPlayerFrames = 0;
                userData->playerSequence = 0;
            }
            return true;
        }
        const bool renderPlayerPcm = handle->player.playbackEnabled.load() &&
            handle->recovery.deviceOnline.load() &&
            !handle->player.focusMuted.load();
        const size_t replayBytes = renderPlayerPcm
            ? handle->player.playerReplayBuffer.read(buffer.data(), transferSize)
            : 0;
        const size_t pipelineBytes = handle->player.pcmPipeline.fill(
            buffer.data() + replayBytes,
            transferSize - replayBytes,
            renderPlayerPcm
        );
        const size_t playerBytes = replayBytes + pipelineBytes;
        if (playerBytes > 0) {
            handle->player.pcmPipeline.applyTransportStartRamp(buffer.data(), playerBytes);
        }
        if (userData != nullptr) {
            const int64_t queuedFrames = static_cast<int64_t>(
                playerBytes / static_cast<size_t>(std::max(1, handle->device.frameBytes))
            );
            userData->queuedPlayerFrames = queuedFrames;
            userData->playerSequence = queuedFrames > 0
                ? handle->player.nextPlayerSequence.fetch_add(1)
                : 0;
            handle->player.stagedPlayerFrames.fetch_add(queuedFrames);
        }
    } else {
        if (userData != nullptr && userData->forceSilence) {
            std::memset(buffer.data(), 0, static_cast<size_t>(transferBytes));
        } else {
            fillToneBuffer(handle, buffer.data(), static_cast<size_t>(transferBytes));
        }
    }
    return true;
}

int activateBufferedIsoReserveTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transfer.streamSource.load() != StreamSource::PlayerPcm ||
        !handle->transfer.running.load() || !handle->player.playbackEnabled.load() ||
        shouldStopTransferSubmission(handle)) {
        return 0;
    }

    const int targetTransferCount = std::min<int>(
        handle->transfer.targetTransferCount.load(),
        static_cast<int>(handle->transfer.transfers.size())
    );
    if (targetTransferCount <= handle->transfer.baseTransferCount) {
        return 0;
    }
    const neri::usb::PcmPipelineSnapshot initialPcm = handle->player.pcmPipeline.snapshot();
    int activationBudget = neri::usb::isoReserveActivationBudgetAfterWarmup(
        handle->transfer.completedTransfers.load(),
        initialPcm.levelBytes,
        handle->transfer.transferBytes,
        handle->transfer.baseTransferCount,
        handle->transfer.inFlightTransfers.load(),
        targetTransferCount
    );
    int activated = 0;
    while (activationBudget > 0) {
        std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->transfer.running.load() ||
            !handle->player.playbackEnabled.load() ||
            handle->transfer.inFlightTransfers.load() >= targetTransferCount) {
            break;
        }

        int slot = -1;
        for (int index = handle->transfer.baseTransferCount;
             index < targetTransferCount;
             ++index) {
            if (handle->transfer.transferSubmitted[static_cast<size_t>(index)] == 0) {
                slot = index;
                break;
            }
        }
        if (slot < 0) {
            break;
        }

        const neri::usb::PcmPipelineSnapshot pcm = handle->player.pcmPipeline.snapshot();
        if (pcm.levelBytes < static_cast<size_t>(handle->transfer.transferBytes)) {
            break;
        }
        libusb_transfer* transfer = handle->transfer.transfers[static_cast<size_t>(slot)];
        if (!refillTransfer(handle, transfer)) {
            setError(handle, "reserve_transfer_refill_failed");
            markTransportFailed(handle);
            break;
        }
        const int rc = libusb_submit_transfer(transfer);
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(
                handle,
                &handle->transfer.transferUserData[static_cast<size_t>(slot)],
                false
            );
            handle->transfer.submitErrors.fetch_add(1);
            setError(handle, std::string("reserve_submit_failed:") + libusbErrName(rc));
            markTransportFailed(handle);
            break;
        }
        handle->transfer.transferSubmitted[static_cast<size_t>(slot)] = 1;
        handle->transfer.inFlightTransfers.fetch_add(1);
        ++activated;
        --activationBudget;
    }
    return activated;
}

struct TransferCallbackCompletion final {
    UsbExclusiveHandle* handle = nullptr;
    int slot = -1;
    bool resubmitted = false;

    ~TransferCallbackCompletion() {
        if (handle != nullptr && !resubmitted) {
            std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
            if (slot >= 0 && slot < static_cast<int>(handle->transfer.transferSubmitted.size())) {
                handle->transfer.transferSubmitted[static_cast<size_t>(slot)] = 0;
            }
            handle->transfer.inFlightTransfers.fetch_sub(1);
        }
    }
};

void LIBUSB_CALL transferCallback(libusb_transfer* transfer) noexcept {
    if (transfer == nullptr) {
        return;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    auto* handle = userData != nullptr ? userData->handle : nullptr;
    if (handle == nullptr) {
        return;
    }
    const bool staleGeneration = userData->generation == 0 ||
        userData->generation !=
            static_cast<uint64_t>(handle->recovery.nativeStreamGeneration);
    if (
        userData->slot >= 0 &&
        userData->slot < static_cast<int>(handle->transfer.transferStatuses.size())
    ) {
        handle->transfer.transferStatuses[static_cast<size_t>(userData->slot)] = transfer->status;
    }
    TransferCallbackCompletion completion { handle, userData->slot, false };
    try {
        const bool transferCompleted = transfer->status == LIBUSB_TRANSFER_COMPLETED;
        const bool transferCancelled = transfer->status == LIBUSB_TRANSFER_CANCELLED;
        bool packetsCompleted = transferCompleted;
        bool packetReportedNoDevice = false;
        bool completedPacketPrefixOpen = true;
        int failedPacketCount = 0;
        int firstFailedPacketIndex = -1;
        int firstFailedPacketStatus = LIBUSB_TRANSFER_COMPLETED;
        int64_t completedPacketBytes = 0;
        int64_t completedPacketPrefixBytes = 0;
        if (transferCompleted || transferCancelled) {
            for (int packetIndex = 0; packetIndex < transfer->num_iso_packets; ++packetIndex) {
                const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[packetIndex];
                if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
                    completedPacketPrefixOpen = false;
                    if (transferCompleted) {
                        packetsCompleted = false;
                        failedPacketCount += 1;
                        if (firstFailedPacketIndex < 0) {
                            firstFailedPacketIndex = packetIndex;
                            firstFailedPacketStatus = packet.status;
                        }
                    }
                }
                if (packet.status == LIBUSB_TRANSFER_NO_DEVICE) {
                    packetReportedNoDevice = true;
                }
                if (packet.status == LIBUSB_TRANSFER_COMPLETED) {
                    const int completedBytes = neri::usb::completedIsoPacketBytes(
                        true,
                        packet.length,
                        packet.actual_length
                    );
                    completedPacketBytes += completedBytes;
                    if (completedPacketPrefixOpen) {
                        if (completedBytes == 0) {
                            completedPacketPrefixOpen = false;
                        } else {
                            completedPacketPrefixBytes += completedBytes;
                        }
                    }
                }
            }
        }
        const int64_t completedPacketFrames = handle->device.frameBytes > 0
            ? completedPacketBytes / handle->device.frameBytes
            : 0;
        const int64_t completedPacketPrefixFrames = handle->device.frameBytes > 0
            ? completedPacketPrefixBytes / handle->device.frameBytes
            : 0;
        const bool replayedCancelledFrames =
            transferCancelled &&
            handle->player.preserveCancelledPlayerFrames.load() &&
            preserveCancelledPlayerFrames(
                handle,
                userData,
                transfer,
                completedPacketPrefixFrames
            );
        if (!replayedCancelledFrames) {
            settlePreparedPlayerFrames(handle, userData, completedPacketFrames);
        }
        if (staleGeneration) {
            return;
        }
        if (packetsCompleted) {
            const int currentScore = handle->transfer.isoPacketErrorScore.load();
            handle->transfer.isoPacketErrorScore.store(
                neri::usb::updateIsoPacketErrorScore(currentScore, 0)
            );
            handle->transfer.lastTransferCompletionAtMs.store(steadyClockMillis());
            const int completedBefore = handle->transfer.completedTransfers.fetch_add(1);
            if (completedBefore == 0) {
                LOGI(
                    "first USB transfer completed: packets=%d requested=%d actual=%d",
                    transfer->num_iso_packets,
                    transfer->length,
                    transfer->actual_length
                );
            }
        } else if (transferCompleted && !packetReportedNoDevice) {
            const int errorTransfers = handle->transfer.isoPacketErrorTransfers.fetch_add(1) + 1;
            const int totalPacketErrors = handle->transfer.isoPacketErrors.fetch_add(failedPacketCount) +
                failedPacketCount;
            const int errorScore = neri::usb::updateIsoPacketErrorScore(
                handle->transfer.isoPacketErrorScore.load(),
                failedPacketCount
            );
            handle->transfer.isoPacketErrorScore.store(errorScore);
            const bool fatalPacketBurst = neri::usb::shouldFailForIsoPacketErrors(errorScore);
            if (errorTransfers <= 3 || (errorTransfers & (errorTransfers - 1)) == 0 ||
                fatalPacketBurst) {
                LOGW(
                    "USB isochronous packet error: failedPackets=%d firstPacket=%d "
                    "firstStatus=%d score=%d/%d errorTransfers=%d totalPacketErrors=%d "
                    "completed=%d inFlight=%d actual=%d fatal=%d",
                    failedPacketCount,
                    firstFailedPacketIndex,
                    firstFailedPacketStatus,
                    errorScore,
                    neri::usb::kIsoPacketErrorFailureScore,
                    errorTransfers,
                    totalPacketErrors,
                    handle->transfer.completedTransfers.load(),
                    handle->transfer.inFlightTransfers.load(),
                    transfer->actual_length,
                    fatalPacketBurst ? 1 : 0
                );
            }
            if (fatalPacketBurst) {
                handle->transfer.submitErrors.fetch_add(1);
                markTransportFailed(handle);
                setError(handle, "iso_packet_status_failed");
                return;
            }
        } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
            if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE || packetReportedNoDevice) {
                requestNoDeviceStop(handle);
            }
            handle->transfer.submitErrors.fetch_add(1);
            markTransportFailed(handle);
            const std::string transferError = transferCompleted
                ? "iso_packet_status_failed"
                : std::string("transfer_status=") + std::to_string(transfer->status);
            setError(handle, transferError);
            LOGW(
                "USB transfer callback status=%d packetsCompleted=%d completed=%d "
                "submitErrors=%d inFlight=%d actual=%d",
                transfer->status,
                packetsCompleted ? 1 : 0,
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load(),
                handle->transfer.inFlightTransfers.load(),
                transfer->actual_length
            );
            return;
        }

        if (shouldStopTransferSubmission(handle)) {
            return;
        }
        if (neri::usb::shouldRetireIsoReserveTransfer(
                userData->slot,
                handle->transfer.baseTransferCount,
                handle->transfer.inFlightTransfers.load(),
                handle->transfer.targetTransferCount.load()
            )) {
            return;
        }

        int rc = LIBUSB_ERROR_NO_DEVICE;
        {
            std::lock_guard<std::mutex> refillGuard(handle->transfer.transferRefillLock);
            if (!refillTransfer(handle, transfer)) {
                handle->transfer.submitErrors.fetch_add(1);
                markTransportFailed(handle);
                return;
            }
            {
                std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
                if (shouldStopTransferSubmission(handle)) {
                    settlePreparedPlayerFrames(handle, userData, false);
                    return;
                }
                rc = libusb_submit_transfer(transfer);
                if (rc == LIBUSB_SUCCESS) {
                    completion.resubmitted = true;
                }
            }
        }
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(handle, userData, false);
            handle->transfer.submitErrors.fetch_add(1);
            markTransportFailed(handle);
            setError(handle, std::string("resubmit_failed:") + libusbErrName(rc));
            LOGE(
                "libusb_submit_transfer resubmit failed: %s completed=%d submitErrors=%d inFlight=%d",
                libusbErrName(rc),
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load(),
                handle->transfer.inFlightTransfers.load()
            );
            return;
        }
    } catch (const std::exception& error) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("transfer_callback_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB transfer callback exception: %s", error.what());
    } catch (...) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, "transfer_callback_unknown_exception");
        } catch (...) {
        }
        LOGE("USB transfer callback unknown exception");
    }
}

bool allocateTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transfer.transferBytes <= 0) {
        return false;
    }

    const int allocationCount = handle->transfer.streamSource.load() == StreamSource::PlayerPcm
        ? handle->transfer.transferCount
        : handle->transfer.baseTransferCount;
    try {
        handle->transfer.transfers.reserve(allocationCount);
        handle->transfer.transferBuffers.reserve(allocationCount);
        handle->transfer.transferUserData.reserve(allocationCount);
        handle->transfer.transferStatuses.reserve(allocationCount);
        handle->transfer.transferSubmitted.reserve(allocationCount);

        for (int index = 0; index < allocationCount; ++index) {
            libusb_transfer* transfer = libusb_alloc_transfer(handle->transfer.packetsPerTransfer);
            if (transfer == nullptr) {
                setError(handle, "libusb_alloc_transfer_failed");
                return false;
            }
            try {
                handle->transfer.transferBuffers.emplace_back(
                    static_cast<size_t>(handle->transfer.transferBytes),
                    0
                );
                auto& buffer = handle->transfer.transferBuffers.back();
                handle->transfer.transferUserData.push_back(
                    TransferUserData {
                        handle,
                        index,
                        0,
                        0,
                        static_cast<uint64_t>(handle->recovery.nativeStreamGeneration),
                        false
                    }
                );
                handle->transfer.transferStatuses.push_back(-1);
                handle->transfer.transferSubmitted.push_back(0);

                libusb_fill_iso_transfer(
                    transfer,
                    handle->device.devh,
                    handle->device.outEndpoint,
                    buffer.data(),
                    handle->transfer.transferBytes,
                    handle->transfer.packetsPerTransfer,
                    transferCallback,
                    &handle->transfer.transferUserData.back(),
                    0
                );
                handle->transfer.transfers.push_back(transfer);
            } catch (...) {
                libusb_free_transfer(transfer);
                throw;
            }
        }
    } catch (const std::bad_alloc&) {
        setError(handle, "usb_transfer_allocation_failed");
        freeTransfers(handle);
        return false;
    }

    return true;
}

void freeTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    stopExplicitFeedbackRuntime(handle);
    if (!feedbackTransfersOutstanding(handle)) {
        freeExplicitFeedbackTransfers(handle);
    }
    for (libusb_transfer* transfer : handle->transfer.transfers) {
        if (transfer != nullptr) {
            libusb_free_transfer(transfer);
        }
    }
    for (TransferUserData& userData : handle->transfer.transferUserData) {
        settlePreparedPlayerFrames(handle, &userData, false);
    }
    handle->transfer.transfers.clear();
    handle->transfer.transferBuffers.clear();
    handle->transfer.transferUserData.clear();
    handle->transfer.transferStatuses.clear();
    handle->transfer.transferSubmitted.clear();
    handle->transfer.inFlightTransfers.store(0);
}

void eventLoopThread(UsbExclusiveHandle* handle) noexcept {
    try {
        configureUsbEventThreadPriority();
        int consecutiveErrors = 0;
        LOGI("USB event loop entered");
        while (handle->recovery.deviceOnline.load() && !handle->recovery.stopRequested.load()) {
            int eventWaitTimeoutMs = kEventLoopWaitTimeoutMs;
            if (handle->device.explicitFeedbackEnabled &&
                handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds > 0) {
                const uint64_t expectedPeriodMs =
                    (handle->device.feedbackTimingProfile.feedbackExpectedPeriodNanoseconds +
                        UINT64_C(999999)) /
                    UINT64_C(1000000);
                eventWaitTimeoutMs = static_cast<int>(std::clamp<uint64_t>(
                    expectedPeriodMs,
                    1,
                    10
                ));
            }
            timeval timeout = timeoutFromMilliseconds(eventWaitTimeoutMs);
            const int rc = libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
                const int totalErrors = handle->transfer.submitErrors.fetch_add(1) + 1;
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle);
                    handle->transfer.running.store(false);
                    markTransportFailed(handle);
                    setError(handle, "event_loop_failed:LIBUSB_ERROR_NO_DEVICE");
                    LOGE(
                        "USB event loop lost device: totalErrors=%d inFlight=%d",
                        totalErrors,
                        handle->transfer.inFlightTransfers.load()
                    );
                    break;
                }
                consecutiveErrors += 1;
                const int backoffMs = exponentialBackoffMs(consecutiveErrors);
                if (shouldLogRepeatedError(consecutiveErrors)) {
                    LOGW(
                        "USB event loop transient error: error=%s consecutive=%d/%d "
                        "totalErrors=%d backoffMs=%d",
                        libusbErrName(rc),
                        consecutiveErrors,
                        kEventLoopConsecutiveErrorLimit,
                        totalErrors,
                        backoffMs
                    );
                }
                if (consecutiveErrors >= kEventLoopConsecutiveErrorLimit) {
                    handle->transfer.running.store(false);
                    markTransportFailed(handle);
                    handle->recovery.stopRequested.store(true);
                    setError(handle, std::string("event_loop_failed:") + libusbErrName(rc));
                    LOGE(
                        "USB event loop error limit reached: error=%s consecutive=%d",
                        libusbErrName(rc),
                        consecutiveErrors
                    );
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
            } else {
                consecutiveErrors = 0;
            }
            if (handle->device.explicitFeedbackEnabled &&
                !handle->transfer.feedbackRuntime.tick(steadyClockNanoseconds())) {
                failForExplicitFeedbackRuntime(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                LOGE(
                    "USB explicit feedback runtime failed: state=%s failure=%s",
                    neri::usb::feedback::explicitFeedbackRuntimeStateName(
                        handle->transfer.feedbackRuntime.snapshot().state
                    ),
                    neri::usb::feedback::explicitFeedbackRuntimeFailureName(
                        handle->transfer.feedbackRuntime.snapshot().failure
                    )
                );
                break;
            }
            const int64_t firstSubmittedAtMs = handle->transfer.firstTransferSubmittedAtMs.load();
            if (
                handle->transfer.completedTransfers.load() == 0 &&
                handle->transfer.inFlightTransfers.load() > 0 &&
                firstSubmittedAtMs > 0 &&
                steadyClockMillis() - firstSubmittedAtMs >= kFirstTransferCompletionTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                setError(handle, "event_loop_first_completion_timeout");
                LOGE(
                    "USB event loop timed out before first completion: inFlight=%d",
                    handle->transfer.inFlightTransfers.load()
                );
                break;
            }
            const int64_t lastCompletionAtMs = handle->transfer.lastTransferCompletionAtMs.load();
            if (
                handle->transfer.completedTransfers.load() > 0 &&
                handle->transfer.inFlightTransfers.load() > 0 &&
                lastCompletionAtMs > 0 &&
                steadyClockMillis() - lastCompletionAtMs >=
                    kTransferCompletionStallTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                setError(handle, "event_loop_completion_stalled");
                LOGE(
                    "USB event loop stalled after completions: completed=%d inFlight=%d",
                    handle->transfer.completedTransfers.load(),
                    handle->transfer.inFlightTransfers.load()
                );
                break;
            }
            if (handle->recovery.transportFailed.load() &&
                !streamTransfersOutstanding(handle)) {
                handle->transfer.running.store(false);
                handle->recovery.stopRequested.store(true);
                break;
            }
        }
        LOGI(
            "USB event loop exited: running=%d stop=%d completed=%d errors=%d inFlight=%d",
            handle->transfer.running.load() ? 1 : 0,
            handle->recovery.stopRequested.load() ? 1 : 0,
            handle->transfer.completedTransfers.load(),
            handle->transfer.submitErrors.load(),
            handle->transfer.inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("event_loop_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB event loop exception: %s", error.what());
    } catch (...) {
        handle->transfer.submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, "event_loop_unknown_exception");
        } catch (...) {
        }
        LOGE("USB event loop unknown exception");
    }
}

void logTransferStatuses(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    for (size_t index = 0; index < handle->transfer.transfers.size(); ++index) {
        libusb_transfer* transfer = handle->transfer.transfers[index];
        const int callbackStatus = index < handle->transfer.transferStatuses.size()
            ? handle->transfer.transferStatuses[index]
            : -2;
        const int liveStatus = transfer != nullptr ? transfer->status : -2;
        LOGW(
            "cancel drain transfer[%zu]: callbackStatus=%d liveStatus=%d ptr=%p",
            index,
            callbackStatus,
            liveStatus,
            static_cast<void*>(transfer)
        );
    }
}

bool drainCancelledTransfers(
    UsbExclusiveHandle* handle,
    std::chrono::steady_clock::time_point hardDeadline
) {
    if (handle == nullptr) {
        return true;
    }
    const auto warningDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainWarningMs);
    bool warnedAboutSlowDrain = false;
    int consecutiveErrors = 0;
    while (handle->transfer.inFlightTransfers.load() > 0 ||
        feedbackTransfersOutstanding(handle)) {
        const auto beforeWait = std::chrono::steady_clock::now();
        const auto remainingMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            hardDeadline - beforeWait
        ).count();
        const int waitTimeoutMs = static_cast<int>(std::clamp<int64_t>(
            remainingMs,
            0,
            kDrainEventWaitTimeoutMs
        ));
        timeval timeout = timeoutFromMilliseconds(waitTimeoutMs);
        const int rc = handle->device.ctx != nullptr
            ? libusb_handle_events_timeout_completed(handle->device.ctx, &timeout, nullptr)
            : LIBUSB_ERROR_INVALID_PARAM;
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            consecutiveErrors += 1;
            const int backoffMs = exponentialBackoffMs(consecutiveErrors);
            if (shouldLogRepeatedError(consecutiveErrors)) {
                LOGW(
                "USB cancel drain event error: error=%s consecutive=%d backoffMs=%d "
                    "audioInFlight=%d feedbackInFlight=%d",
                libusbErrName(rc),
                consecutiveErrors,
                backoffMs,
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
                );
            }
            const auto remainingAfterWaitMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                hardDeadline - std::chrono::steady_clock::now()
            ).count();
            if (remainingAfterWaitMs > 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(
                    std::min<int64_t>(backoffMs, remainingAfterWaitMs)
                ));
            }
        } else {
            consecutiveErrors = 0;
        }

        const auto now = std::chrono::steady_clock::now();
        if (!warnedAboutSlowDrain && now >= warningDeadline) {
            warnedAboutSlowDrain = true;
            markTransportFailed(handle);
            try {
                setError(handle, "cancel_drain_stalled");
            } catch (...) {
            }
            LOGW(
                "waiting for cancelled USB transfers before close: audioInFlight=%d "
                "feedbackInFlight=%d",
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
            );
            logTransferStatuses(handle);
        }
        if (now >= hardDeadline) {
            markTransportFailed(handle);
            handle->recovery.deviceOnline.store(false);
            handle->player.playbackEnabled.store(false);
            try {
                setError(handle, "cancel_drain_timeout");
            } catch (...) {
            }
            LOGE(
                "cancel drain timed out: audioInFlight=%d feedbackInFlight=%d "
                "completed=%d errors=%d",
                handle->transfer.inFlightTransfers.load(),
                handle->device.explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->transfer.feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0,
                handle->transfer.completedTransfers.load(),
                handle->transfer.submitErrors.load()
            );
            logTransferStatuses(handle);
            return false;
        }
    }
    return true;
}

bool stopStreamingInternal(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return true;
    }
    const bool wasRunning = handle->transfer.running.exchange(false);
    if (!wasRunning && handle->transfer.transfers.empty() &&
        !handle->transfer.eventThread.joinable() && !feedbackTransferSetActive(handle)) {
        return parkStreamingAlternate(handle, "stream_stop_idle");
    }

    LOGI(
        "stopStreamingInternal begin: source=%s inFlight=%d completed=%d errors=%d",
        sourceName(handle->transfer.streamSource.load()),
        handle->transfer.inFlightTransfers.load(),
        handle->transfer.completedTransfers.load(),
        handle->transfer.submitErrors.load()
    );
    {
        std::lock_guard<std::mutex> submitGuard(handle->transfer.transferSubmitLock);
        handle->recovery.stopRequested.store(true);
    }
    stopExplicitFeedbackRuntime(handle);
    interruptUsbEventHandler(handle);
    for (libusb_transfer* transfer : handle->transfer.transfers) {
        if (transfer != nullptr) {
            const int rc = libusb_cancel_transfer(transfer);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("libusb_cancel_transfer failed: %s", libusbErrName(rc));
            }
        }
    }
    interruptUsbEventHandler(handle);
    if (handle->transfer.eventThread.joinable()) {
        handle->transfer.eventThread.join();
    }
    const auto hardDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainDeadlineMs);
    if (!drainCancelledTransfers(handle, hardDeadline)) {
        return false;
    }
    freeTransfers(handle);
    if (!parkStreamingAlternate(handle, "stream_stop")) {
        setError(handle, "stream_stop_idle_alt_failed:LIBUSB_ERROR_NO_DEVICE");
        return false;
    }
    if (feedbackTransferSetActive(handle)) {
        setError(handle, "feedback_transfer_set_not_drained");
        return false;
    }
    LOGI(
        "stopStreamingInternal done: completed=%d errors=%d transportFailed=%d",
        handle->transfer.completedTransfers.load(),
        handle->transfer.submitErrors.load(),
        handle->recovery.transportFailed.load() ? 1 : 0
    );
    return true;
}

} // namespace neri::usb::exclusive
