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
#include "usb/exclusive/usb_player_replay_buffer.h"
#include "usb/exclusive/usb_player_startup_preroll.h"
#include "usb/exclusive/usb_recovery_action_latch.h"
#include "usb/exclusive/usb_runtime_report_v2.h"
#include "usb/feedback/usb_explicit_feedback_runtime.h"
#include "usb/feedback/usb_feedback_in_transfer_set.h"
#include "usb/feedback/usb_feedback_rate_math.h"
#include "usb/feedback/usb_libusb_feedback_backend.h"
#include "usb/iso/usb_iso_packet_scheduler.h"
#include "usb/iso/usb_iso_transfer_health.h"
#include "usb/pcm/usb_pcm_pipeline.h"
#include "usb/uac1/usb_uac1_format.h"
#include "usb/uac2/usb_uac2_feedback_profile.h"
#include "usb/uac2/usb_uac2_format.h"

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kUsbSubclassAudioStreaming = 0x02;
constexpr int kUsbSubclassAudioControl = 0x01;
constexpr int kUsbAudioProtocolUac1 = 0x00;
constexpr int kUsbAudioProtocolUac2 = 0x20;
constexpr int kUsbDescriptorTypeClassSpecificInterface = 0x24;
constexpr int kUsbAudioControlHeaderSubtype = 0x01;
constexpr int kUsbTransferTypeIsochronous = 0x01;
constexpr int kEventLoopWaitTimeoutMs = 100;
constexpr int kDrainEventWaitTimeoutMs = 100;
constexpr int kParkedEventWaitTimeoutMs = 5000;
constexpr int kEventLoopErrorBackoffBaseMs = 4;
constexpr int kEventLoopErrorBackoffMaxMs = 200;
constexpr int kEventLoopConsecutiveErrorLimit = 64;
constexpr int kParkedErrorBackoffBaseMs = 100;
constexpr int kParkedErrorBackoffMaxMs = 5000;
constexpr int kGeneratedToneFrequencyHz = 440;
constexpr int kDefaultPacketsPerTransfer = 32;
constexpr int kDefaultTransferCount = 16;
constexpr int kMinimumTransferCount = 8;
constexpr int kMaximumPacketsPerTransfer = 64;
constexpr int kMaximumTransferCount = 32;
constexpr int kExplicitFeedbackTransferCount = 4;
constexpr int kExplicitFeedbackPacketsPerTransfer = 8;
constexpr int kExplicitFeedbackAudioTransferCount = 16;
constexpr uint32_t kExplicitFeedbackBootstrapPacketLimit = 4096;
constexpr int kHighSpeedTargetInFlightMs = 160;
constexpr int kFullSpeedTargetInFlightMs = 320;
constexpr int kDefaultPcmRingDurationMs = 250;
constexpr int kPlayerStartupPrerollMs = 0;
constexpr int kMinimumPcmRingDurationMs = 100;
constexpr int kMaximumPcmRingDurationMs = 3000;
constexpr int kCancelDrainWarningMs = 1200;
constexpr int kCancelDrainDeadlineMs = 3000;
constexpr int kQuarantineDrainLogIntervalMs = 10000;
constexpr int kQuarantineTotalTimeoutMs = 30000;
constexpr size_t kMaximumParkedHandles = 8;
constexpr size_t kMaximumHardRetainedHandles = 4;
constexpr int kFirstTransferCompletionTimeoutMs = 3000;
constexpr int kTransferCompletionStallTimeoutMs = 1500;
constexpr int kInterfaceTransitionCooldownMs = 1500;
constexpr int kUrgentAudioThreadPriority = -19;
constexpr auto kLibusbEndpointOut =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_OUT);
constexpr auto kLibusbEndpointIn =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_IN);
constexpr auto kLibusbRequestTypeClass =
    static_cast<unsigned int>(LIBUSB_REQUEST_TYPE_CLASS);
constexpr auto kLibusbRecipientEndpoint =
    static_cast<unsigned int>(LIBUSB_RECIPIENT_ENDPOINT);
constexpr auto kLibusbRecipientInterface =
    static_cast<unsigned int>(LIBUSB_RECIPIENT_INTERFACE);
constexpr auto kLibusbIsoUsageFeedback =
    static_cast<int>(LIBUSB_ISO_USAGE_TYPE_FEEDBACK);
constexpr auto kLibusbIsoUsageImplicit =
    static_cast<int>(LIBUSB_ISO_USAGE_TYPE_IMPLICIT);
constexpr auto kLibusbIsoSyncTypeAdaptive =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_ADAPTIVE);
constexpr auto kLibusbIsoSyncTypeSynchronous =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_SYNC);
constexpr auto kLibusbIsoSyncTypeAsynchronous =
    static_cast<int>(LIBUSB_ISO_SYNC_TYPE_ASYNC);

enum class StreamSource {
    Tone,
    PlayerPcm
};

std::mutex g_lastOpenErrorLock;
std::string g_lastOpenError = "none";
std::mutex g_usbInterfaceTransitionLock;
std::chrono::steady_clock::time_point g_lastInterfaceTransitionAt {};

struct UsbExclusiveHandle;

struct TransferUserData {
    UsbExclusiveHandle* handle = nullptr;
    int slot = -1;
    int64_t queuedPlayerFrames = 0;
    uint64_t playerSequence = 0;
    uint64_t generation = 0;
    bool forceSilence = false;
};

struct ClaimedUsbInterface {
    int interfaceNumber = -1;
    uint8_t subclass = 0;
};

struct UsbExclusiveHandle {
    libusb_context* ctx = nullptr;
    libusb_device_handle* devh = nullptr;
    int dupFd = -1;
    int audioStreamingInterface = -1;
    int audioControlInterface = -1;
    int alternateSetting = -1;
    uint8_t outEndpoint = 0;
    int sampleRate = 0;
    int channelCount = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;
    int frameBytes = 0;
    int endpointMaxPacketBytes = 0;
    int endpointInterval = 0;
    bool explicitFeedbackEnabled = false;
    uint8_t feedbackEndpoint = 0;
    int feedbackEndpointMaxPacketBytes = 0;
    int feedbackEndpointInterval = 0;
    neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
    neri::usb::feedback::FeedbackRateQ32 feedbackNominalRateQ32 = 0;
    int usbSpeed = LIBUSB_SPEED_UNKNOWN;
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    uint16_t deviceRelease = 0;
    uint8_t busNumber = 0;
    uint8_t deviceAddress = 0;
    std::vector<ClaimedUsbInterface> claimedAudioInterfaces;
    bool completeAudioFunctionClaim = false;
    int uacVersion = 0;
    int uacClockSourceId = 0;
    int negotiatedSampleRate = 0;
    std::string descriptorSampleRates = "none";
    std::string formatSelectionReason = "none";
    std::string sampleRateControlStatus = "not_attempted";
    std::string endpointSyncType = "none";
    std::string endpointFeedback = "none";
    int intervalsPerSecond = 1000;
    int bytesPerUsbFrame = 0;
    int packetsPerTransfer = kDefaultPacketsPerTransfer;
    int transferCount = kDefaultTransferCount;
    int transferBytes = 0;
    std::vector<libusb_transfer*> transfers;
    std::vector<std::vector<uint8_t>> transferBuffers;
    std::vector<TransferUserData> transferUserData;
    std::vector<int> transferStatuses;
    neri::usb::feedback::LibusbFeedbackTransferBackend feedbackTransferBackend;
    neri::usb::feedback::FeedbackInTransferSet feedbackInTransferSet {
        &feedbackTransferBackend
    };
    neri::usb::feedback::ExplicitFeedbackRuntime feedbackRuntime;
    std::thread eventThread;
    std::atomic<bool> running { false };
    std::atomic<bool> playbackEnabled { false };
    std::atomic<bool> playerPaused { false };
    std::atomic<bool> deviceOnline { true };
    std::atomic<bool> noDeviceObserved { false };
    std::atomic<bool> detachBroadcastConfirmed { false };
    std::atomic<bool> focusMuted { false };
    std::atomic<bool> stopRequested { false };
    std::atomic<bool> closing { false };
    std::atomic<bool> transportFailed { false };
    std::atomic<int> inFlightTransfers { 0 };
    std::mutex apiLock;
    std::mutex transferSubmitLock;
    std::mutex lock;
    std::atomic<StreamSource> streamSource { StreamSource::Tone };
    neri::usb::IsoPacketScheduler packetScheduler;
    neri::usb::PcmPipeline pcmPipeline;
    neri::usb::PlayerReplayBuffer playerReplayBuffer;
    neri::usb::PlayerStartupPreroll playerStartupPreroll;
    int pcmRingDurationMs = kDefaultPcmRingDurationMs;
    std::atomic<int64_t> stagedPlayerFrames { 0 };
    std::atomic<int64_t> completedAudioFrames { 0 };
    std::atomic<uint64_t> nextPlayerSequence { 1 };
    std::atomic<bool> preserveCancelledPlayerFrames { false };
    std::atomic<bool> playerReplayFailed { false };
    double tonePhase = 0.0;
    std::atomic<int> completedTransfers { 0 };
    std::atomic<int64_t> firstTransferSubmittedAtMs { 0 };
    std::atomic<int64_t> lastTransferCompletionAtMs { 0 };
    std::atomic<int> submitErrors { 0 };
    std::atomic<int> isoPacketErrors { 0 };
    std::atomic<int> isoPacketErrorTransfers { 0 };
    std::atomic<int> isoPacketErrorScore { 0 };
    std::atomic<int64_t> scheduledPackets { 0 };
    std::atomic<int64_t> scheduledFrames { 0 };
    std::atomic<int> packetFramesMin { std::numeric_limits<int>::max() };
    std::atomic<int> packetFramesMax { 0 };
    std::atomic<int> lastTransferBytes { 0 };
    std::atomic<int> shortWriteWarnings { 0 };
    std::atomic<float> playerVolume { 1.0f };
    std::string lastError;
    int64_t nativeStreamGeneration = 0;
    int64_t recoveryEpoch = 1;
    neri::usb::UsbRecoveryActionLatch recoveryActionLatch;
};

struct ParkedHandleSlot {
    std::shared_ptr<UsbExclusiveHandle> handle;
    std::chrono::steady_clock::time_point parkedAt {};
    int quarantineIndex = 0;
    bool pumpActive = false;
};

std::mutex g_handleRegistryLock;
std::unordered_map<jlong, std::shared_ptr<UsbExclusiveHandle>> g_handleRegistry;
std::atomic<jlong> g_nextHandleToken { 1 };
std::atomic<int64_t> g_nextNativeStreamGeneration { 1 };
std::atomic<int64_t> g_nextRecoveryActionId { 1 };
std::atomic<int> g_nextQuarantineIndex { 1 };
std::atomic<int> g_quarantinedDrainHandles { 0 };
std::mutex g_parkedHandlesLock;
std::array<ParkedHandleSlot, kMaximumParkedHandles> g_parkedHandles;
std::array<std::shared_ptr<UsbExclusiveHandle>, kMaximumHardRetainedHandles>
    g_hardRetainedHandles;

bool allocateTransfers(UsbExclusiveHandle* handle);
void freeTransfers(UsbExclusiveHandle* handle);
void eventLoopThread(UsbExclusiveHandle* handle) noexcept;
bool stopStreamingInternal(UsbExclusiveHandle* handle);
bool closeHandleInternal(const std::shared_ptr<UsbExclusiveHandle>& handle);
void latchTerminalRecoveryAction(
    UsbExclusiveHandle* handle,
    neri::usb::UsbRuntimeRecoveryAction action
);
bool reconfigureOpenedPlayerPcmOutput(
    UsbExclusiveHandle* handle,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    std::string* error
);
bool readUac2CurrentSampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int* currentSampleRate,
    std::string* status
);

int64_t steadyClockNanoseconds() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

int exponentialBackoffMs(int consecutiveErrors) {
    const int shift = std::min(std::max(0, consecutiveErrors - 1), 6);
    return std::min(
        kEventLoopErrorBackoffMaxMs,
        kEventLoopErrorBackoffBaseMs << shift
    );
}

int parkedExponentialBackoffMs(int consecutiveErrors) {
    const int shift = std::min(std::max(0, consecutiveErrors - 1), 6);
    return std::min(
        kParkedErrorBackoffMaxMs,
        kParkedErrorBackoffBaseMs << shift
    );
}

timeval timeoutFromMilliseconds(int timeoutMs) {
    const int boundedTimeoutMs = std::max(0, timeoutMs);
    timeval timeout {};
    timeout.tv_sec = boundedTimeoutMs / 1000;
    timeout.tv_usec = (boundedTimeoutMs % 1000) * 1000;
    return timeout;
}

bool shouldLogRepeatedError(int consecutiveErrors) {
    return consecutiveErrors <= 3 ||
        (consecutiveErrors & (consecutiveErrors - 1)) == 0 ||
        consecutiveErrors == kEventLoopConsecutiveErrorLimit;
}

void interruptUsbEventHandler(UsbExclusiveHandle* handle) {
    if (handle != nullptr && handle->ctx != nullptr) {
        libusb_interrupt_event_handler(handle->ctx);
    }
}

bool shouldStopTransferSubmission(const UsbExclusiveHandle* handle) {
    return handle == nullptr ||
        !handle->deviceOnline.load() ||
        handle->stopRequested.load() ||
        handle->closing.load() ||
        handle->transportFailed.load();
}

void requestDeviceStop(UsbExclusiveHandle* handle, bool detachBroadcastConfirmed) {
    if (handle == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
    if (detachBroadcastConfirmed) {
        handle->detachBroadcastConfirmed.store(true);
    }
    handle->deviceOnline.store(false);
    handle->focusMuted.store(true);
    handle->playbackEnabled.store(false);
    handle->playerPaused.store(false);
    handle->stopRequested.store(true);
    latchTerminalRecoveryAction(
        handle,
        neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
    );
}

void requestNoDeviceStop(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->noDeviceObserved.store(true);
    requestDeviceStop(handle, false);
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

std::shared_ptr<UsbExclusiveHandle> acquireHandle(jlong token) {
    if (token <= 0) {
        return {};
    }
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    const auto entry = g_handleRegistry.find(token);
    return entry != g_handleRegistry.end() ? entry->second : nullptr;
}

jlong registerHandle(const std::shared_ptr<UsbExclusiveHandle>& handle) {
    if (handle == nullptr) {
        return 0L;
    }
    const jlong token = g_nextHandleToken.fetch_add(1);
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    g_handleRegistry.emplace(token, handle);
    return token;
}

std::shared_ptr<UsbExclusiveHandle> takeHandle(jlong token) {
    if (token <= 0) {
        return {};
    }
    std::lock_guard<std::mutex> guard(g_handleRegistryLock);
    const auto entry = g_handleRegistry.find(token);
    if (entry == g_handleRegistry.end()) {
        return {};
    }
    auto handle = entry->second;
    g_handleRegistry.erase(entry);
    return handle;
}

const char* libusbErrName(int rc) {
    return libusb_error_name(rc);
}

void rememberLastOpenError(const std::string& error) {
    std::lock_guard<std::mutex> guard(g_lastOpenErrorLock);
    g_lastOpenError = error;
}

std::string readLastOpenError() {
    std::lock_guard<std::mutex> guard(g_lastOpenErrorLock);
    return g_lastOpenError;
}

int remainingInterfaceTransitionCooldownMsLocked() {
    if (g_lastInterfaceTransitionAt == std::chrono::steady_clock::time_point {}) {
        return 0;
    }
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - g_lastInterfaceTransitionAt
    ).count();
    return std::max(0, kInterfaceTransitionCooldownMs - static_cast<int>(elapsedMs));
}

void markInterfaceTransitionLocked() {
    g_lastInterfaceTransitionAt = std::chrono::steady_clock::now();
}

void clearError(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->lastError.clear();
}

void setError(UsbExclusiveHandle* handle, const char* error) {
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->lastError = error != nullptr ? error : "unknown";
}

void setError(UsbExclusiveHandle* handle, const std::string& error) {
    setError(handle, error.c_str());
}

std::string getErrorCopy(UsbExclusiveHandle* handle) {
    if (handle == nullptr) return "invalid_handle";
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->lastError;
}

void assignNewNativeStreamGeneration(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->nativeStreamGeneration = g_nextNativeStreamGeneration.fetch_add(1);
    handle->recoveryActionLatch.reset(handle->nativeStreamGeneration);
}

void latchTerminalRecoveryAction(
    UsbExclusiveHandle* handle,
    neri::usb::UsbRuntimeRecoveryAction action
) {
    if (handle == nullptr ||
        (action != neri::usb::UsbRuntimeRecoveryAction::FreshOpen &&
            action != neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent)) {
        return;
    }
    handle->recoveryActionLatch.latch(
        action,
        g_nextRecoveryActionId.fetch_add(1)
    );
}

void markTransportFailed(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->transportFailed.store(true);
    latchTerminalRecoveryAction(
        handle,
        handle->deviceOnline.load()
            ? neri::usb::UsbRuntimeRecoveryAction::FreshOpen
            : neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
    );
}

std::string runtimeCandidateId(const UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return "unknown-candidate";
    }
    char buffer[192] = {};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "vid%04X-pid%04X-bcd%04X-bus%u-dev%u-uac%d-iface%d-alt%d-out%02X-sr%d-ch%d-b%d-s%d",
        handle->vendorId,
        handle->productId,
        handle->deviceRelease,
        static_cast<unsigned int>(handle->busNumber),
        static_cast<unsigned int>(handle->deviceAddress),
        handle->uacVersion,
        handle->audioStreamingInterface,
        handle->alternateSetting,
        handle->outEndpoint,
        handle->sampleRate,
        handle->channelCount,
        handle->bitsPerSample,
        handle->subslotBytes
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
    if (!handle->deviceOnline.load() || handle->noDeviceObserved.load()) {
        return "DeviceDetached";
    }
    if (!handle->transportFailed.load()) {
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

double feedbackRateHz(neri::usb::feedback::FeedbackRateQ32 rateQ32) {
    return std::ldexp(static_cast<double>(rateQ32), -32);
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

neri::usb::UsbRecoveryActionAckStatus acknowledgeRecoveryAction(
    UsbExclusiveHandle* handle,
    int64_t actionGeneration,
    int64_t actionId
) {
    return handle != nullptr
        ? handle->recoveryActionLatch.acknowledge(
            actionGeneration,
            actionId,
            handle->closing.load()
        )
        : neri::usb::UsbRecoveryActionAckStatus::NoPending;
}

bool isIsoOutEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = static_cast<uint8_t>(
        endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK
    );
    const auto transferType = static_cast<uint8_t>(
        endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK
    );
    return direction == kLibusbEndpointOut &&
        transferType == static_cast<uint8_t>(kUsbTransferTypeIsochronous);
}

bool isIsoInEndpoint(const libusb_endpoint_descriptor& endpoint) {
    const auto direction = static_cast<uint8_t>(
        endpoint.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK
    );
    const auto transferType = static_cast<uint8_t>(
        endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK
    );
    return direction == kLibusbEndpointIn &&
        transferType == static_cast<uint8_t>(kUsbTransferTypeIsochronous);
}

int usbIsoUsageType(uint8_t endpointAttributes) {
    return static_cast<int>((endpointAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) >> 4);
}

int usbIsoSyncType(uint8_t endpointAttributes) {
    return static_cast<int>((endpointAttributes & LIBUSB_ISO_SYNC_TYPE_MASK) >> 2);
}

uint8_t makeClassEndpointRequestType(uint8_t direction) {
    return static_cast<uint8_t>(
        static_cast<unsigned int>(direction) |
        kLibusbRequestTypeClass |
        kLibusbRecipientEndpoint
    );
}

uint8_t makeClassInterfaceRequestType(uint8_t direction) {
    return static_cast<uint8_t>(
        static_cast<unsigned int>(direction) |
        kLibusbRequestTypeClass |
        kLibusbRecipientInterface
    );
}

uint16_t makeClockEntityIndex(int clockSourceId, int interfaceNumber) {
    return static_cast<uint16_t>(
        ((clockSourceId & 0xFF) << 8) |
        (interfaceNumber & 0xFF)
    );
}

bool sameClaimPlan(
    const std::vector<ClaimedUsbInterface>& current,
    const std::vector<ClaimedUsbInterface>& requested
) {
    if (current.size() != requested.size()) {
        return false;
    }
    for (size_t index = 0; index < current.size(); ++index) {
        if (current[index].interfaceNumber != requested[index].interfaceNumber ||
            current[index].subclass != requested[index].subclass) {
            return false;
        }
    }
    return true;
}

int computeIntervalsPerSecond(int usbSpeed, int interval);

int computeMaxPacketBytes(
    int sampleRate,
    int intervalsPerSecond,
    int frameBytes,
    int endpointMaxPacketBytes
);

struct StreamingAltSelection {
    int interfaceNumber = -1;
    int alternateSetting = -1;
    int audioControlInterface = -1;
    uint8_t outEndpoint = 0;
    int endpointMaxPacketBytes = 0;
    int endpointInterval = 0;
    bool explicitFeedbackEnabled = false;
    uint8_t feedbackEndpoint = 0;
    int feedbackEndpointMaxPacketBytes = 0;
    int feedbackEndpointInterval = 0;
    neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
    int score = std::numeric_limits<int>::min();
    int uacVersion = 0;
    struct Uac1Details {
        neri::usb::uac1::TypeIFormat format;
        neri::usb::uac1::EndpointControls endpointControls;
    } uac1;
    struct Uac2Details {
        neri::usb::uac2::TypeIFormat format;
        int clockSourceId = 0;
        neri::usb::uac2::ControlCapability sampleRateControl =
            neri::usb::uac2::ControlCapability::None;
    } uac2;
    std::string syncType = "none";
    std::string feedback = "none";
    std::string reason = "none";
    std::vector<ClaimedUsbInterface> claimPlan;
    bool completeClaimPlan = false;
};

void appendClaimPlanInterface(
    std::vector<ClaimedUsbInterface>* plan,
    int interfaceNumber,
    uint8_t subclass
) {
    if (plan == nullptr || interfaceNumber < 0) {
        return;
    }
    const auto existing = std::find_if(
        plan->begin(),
        plan->end(),
        [interfaceNumber](const ClaimedUsbInterface& entry) {
            return entry.interfaceNumber == interfaceNumber;
        }
    );
    if (existing == plan->end()) {
        plan->push_back(ClaimedUsbInterface { interfaceNumber, subclass });
    }
}

bool isAudioStreamingInterface(
    const libusb_config_descriptor* config,
    int interfaceNumber
) {
    if (config == nullptr || interfaceNumber < 0) {
        return false;
    }
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceNumber == interfaceNumber &&
                alt.bInterfaceClass == LIBUSB_CLASS_AUDIO &&
                alt.bInterfaceSubClass == kUsbSubclassAudioStreaming) {
                return true;
            }
        }
    }
    return false;
}

void sortAudioClaimPlan(
    std::vector<ClaimedUsbInterface>* plan,
    int selectedStreamingInterface
) {
    if (plan == nullptr) {
        return;
    }
    const auto priority = [selectedStreamingInterface](
        const ClaimedUsbInterface& entry
    ) {
        if (entry.subclass == kUsbSubclassAudioControl) {
            return 0;
        }
        return entry.interfaceNumber == selectedStreamingInterface ? 2 : 1;
    };
    std::stable_sort(
        plan->begin(),
        plan->end(),
        [&priority](
            const ClaimedUsbInterface& left,
            const ClaimedUsbInterface& right
        ) {
            if (priority(left) != priority(right)) {
                return priority(left) < priority(right);
            }
            return left.interfaceNumber < right.interfaceNumber;
        }
    );
}

bool buildAudioFunctionClaimPlan(
    const libusb_config_descriptor* config,
    int selectedStreamingInterface,
    std::vector<ClaimedUsbInterface>* plan
) {
    if (config == nullptr || plan == nullptr) {
        return false;
    }
    plan->clear();
    std::vector<int> audioControlInterfaces;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioControl) {
                continue;
            }
            if (std::find(
                    audioControlInterfaces.begin(),
                    audioControlInterfaces.end(),
                    alt.bInterfaceNumber
                ) == audioControlInterfaces.end()) {
                audioControlInterfaces.push_back(alt.bInterfaceNumber);
            }
            if (alt.extra == nullptr || alt.extra_length < 8) {
                continue;
            }
            const unsigned char* cursor = alt.extra;
            int remaining = alt.extra_length;
            while (remaining >= 3) {
                const int descriptorLength = cursor[0];
                if (descriptorLength < 3 || descriptorLength > remaining) {
                    break;
                }
                if (cursor[1] == kUsbDescriptorTypeClassSpecificInterface &&
                    cursor[2] == kUsbAudioControlHeaderSubtype &&
                    descriptorLength >= 8) {
                    const int collectionCount = cursor[7];
                    if (descriptorLength >= 8 + collectionCount) {
                        bool containsSelected = false;
                        for (int index = 0; index < collectionCount; ++index) {
                            if (cursor[8 + index] == selectedStreamingInterface) {
                                containsSelected = true;
                                break;
                            }
                        }
                        if (containsSelected) {
                            appendClaimPlanInterface(
                                plan,
                                alt.bInterfaceNumber,
                                kUsbSubclassAudioControl
                            );
                            for (int index = 0; index < collectionCount; ++index) {
                                const int streamingInterface = cursor[8 + index];
                                if (isAudioStreamingInterface(config, streamingInterface)) {
                                    appendClaimPlanInterface(
                                        plan,
                                        streamingInterface,
                                        kUsbSubclassAudioStreaming
                                    );
                                }
                            }
                            const bool selectedIncluded = std::any_of(
                                plan->begin(),
                                plan->end(),
                                [selectedStreamingInterface](
                                    const ClaimedUsbInterface& entry
                                ) {
                                    return entry.interfaceNumber == selectedStreamingInterface;
                                }
                            );
                            if (selectedIncluded) {
                                sortAudioClaimPlan(plan, selectedStreamingInterface);
                                return true;
                            }
                            plan->clear();
                        }
                    }
                }
                cursor += descriptorLength;
                remaining -= descriptorLength;
            }
        }
    }
    if (audioControlInterfaces.size() == 1) {
        appendClaimPlanInterface(
            plan,
            audioControlInterfaces.front(),
            kUsbSubclassAudioControl
        );
        for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
            const libusb_interface& iface = config->interface[ifaceIndex];
            for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
                const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
                if (alt.bInterfaceClass == LIBUSB_CLASS_AUDIO &&
                    alt.bInterfaceSubClass == kUsbSubclassAudioStreaming) {
                    appendClaimPlanInterface(
                        plan,
                        alt.bInterfaceNumber,
                        kUsbSubclassAudioStreaming
                    );
                }
            }
        }
    }
    appendClaimPlanInterface(
        plan,
        selectedStreamingInterface,
        kUsbSubclassAudioStreaming
    );
    sortAudioClaimPlan(plan, selectedStreamingInterface);
    return false;
}

bool buildMinimalAudioFunctionClaimPlan(
    const libusb_config_descriptor* config,
    int audioControlInterface,
    int selectedStreamingInterface,
    std::vector<ClaimedUsbInterface>* plan
) {
    if (config == nullptr || plan == nullptr || selectedStreamingInterface < 0) {
        return false;
    }
    plan->clear();
    if (audioControlInterface >= 0) {
        appendClaimPlanInterface(
            plan,
            audioControlInterface,
            kUsbSubclassAudioControl
        );
    }
    appendClaimPlanInterface(
        plan,
        selectedStreamingInterface,
        kUsbSubclassAudioStreaming
    );
    sortAudioClaimPlan(plan, selectedStreamingInterface);
    return audioControlInterface >= 0;
}

void appendCandidateRejection(
    std::string* summary,
    int interfaceNumber,
    int alternateSetting,
    const std::string& reason
) {
    if (summary == nullptr || summary->size() >= 768) {
        return;
    }
    if (!summary->empty()) {
        *summary += ";";
    }
    *summary += "iface=" + std::to_string(interfaceNumber) +
        "/alt=" + std::to_string(alternateSetting) + ":" + reason;
}

int endpointPacketCapacity(const libusb_endpoint_descriptor& endpoint) {
    const int payloadBytes = endpoint.wMaxPacketSize & 0x07FF;
    const int transactionBits = (endpoint.wMaxPacketSize >> 11) & 0x03;
    if (transactionBits == 0x03) {
        return 0;
    }
    const int transactions = 1 + transactionBits;
    return payloadBytes * transactions;
}

neri::usb::uac2::UsbBusSpeed uac2BusSpeedFromLibusb(int usbSpeed) {
    switch (usbSpeed) {
        case LIBUSB_SPEED_FULL:
            return neri::usb::uac2::UsbBusSpeed::Full;
        case LIBUSB_SPEED_HIGH:
            return neri::usb::uac2::UsbBusSpeed::High;
        case LIBUSB_SPEED_LOW:
            return neri::usb::uac2::UsbBusSpeed::Low;
        case LIBUSB_SPEED_SUPER:
            return neri::usb::uac2::UsbBusSpeed::Super;
        case LIBUSB_SPEED_SUPER_PLUS:
        case LIBUSB_SPEED_SUPER_PLUS_X2:
            return neri::usb::uac2::UsbBusSpeed::SuperPlus;
        default:
            return neri::usb::uac2::UsbBusSpeed::Unknown;
    }
}

neri::usb::uac2::EndpointSnapshot makeUac2EndpointSnapshot(
    int configurationValue,
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& endpoint,
    int effectiveMaxPacketBytes
) {
    neri::usb::uac2::EndpointSnapshot snapshot;
    snapshot.configurationValue = configurationValue;
    snapshot.interfaceNumber = alt.bInterfaceNumber;
    snapshot.alternateSetting = alt.bAlternateSetting;
    snapshot.endpointAddress = endpoint.bEndpointAddress;
    snapshot.descriptorLength = endpoint.bLength;
    snapshot.descriptorType = endpoint.bDescriptorType;
    snapshot.bmAttributes = endpoint.bmAttributes;
    snapshot.rawMaxPacketSize = endpoint.wMaxPacketSize;
    snapshot.effectiveMaxPacketBytes = effectiveMaxPacketBytes;
    snapshot.effectiveCapacityKnown = effectiveMaxPacketBytes > 0;
    snapshot.capacitySource = effectiveMaxPacketBytes > 0
        ? neri::usb::uac2::EndpointCapacitySource::BackendComputed
        : neri::usb::uac2::EndpointCapacitySource::Unknown;
    snapshot.bInterval = endpoint.bInterval;
    snapshot.bRefresh = 0;
    snapshot.hasRefresh = false;
    snapshot.bSynchAddress = 0;
    snapshot.hasSynchAddress = false;
    return snapshot;
}

bool resolveUac2ExplicitFeedbackProfile(
    libusb_device* device,
    int configurationValue,
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& outputEndpoint,
    int outputPacketBytes,
    int usbSpeed,
    uint8_t* feedbackEndpointAddress,
    int* feedbackPacketBytes,
    int* feedbackInterval,
    neri::usb::uac2::Uac2FeedbackTimingProfile* timingProfile,
    std::string* failureReason
) {
    if (device == nullptr || feedbackEndpointAddress == nullptr ||
        feedbackPacketBytes == nullptr || feedbackInterval == nullptr ||
        timingProfile == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "feedback_profile_input_invalid";
        }
        return false;
    }

    const libusb_endpoint_descriptor* feedbackEndpoint = nullptr;
    int matchingEndpoints = 0;
    for (int index = 0; index < alt.bNumEndpoints; ++index) {
        const libusb_endpoint_descriptor& candidate = alt.endpoint[index];
        if (!isIsoInEndpoint(candidate) ||
            usbIsoUsageType(candidate.bmAttributes) != kLibusbIsoUsageFeedback) {
            continue;
        }
        if (outputEndpoint.bSynchAddress != 0 &&
            candidate.bEndpointAddress != outputEndpoint.bSynchAddress) {
            continue;
        }
        feedbackEndpoint = &candidate;
        ++matchingEndpoints;
    }
    if (feedbackEndpoint == nullptr || matchingEndpoints != 1) {
        if (failureReason != nullptr) {
            *failureReason = matchingEndpoints == 0
                ? "uac2_feedback_endpoint_missing"
                : "uac2_feedback_endpoint_ambiguous";
        }
        return false;
    }

    int resolvedFeedbackPacketBytes = libusb_get_max_alt_packet_size(
        device,
        alt.bInterfaceNumber,
        alt.bAlternateSetting,
        feedbackEndpoint->bEndpointAddress
    );
    if (resolvedFeedbackPacketBytes <= 0) {
        resolvedFeedbackPacketBytes = endpointPacketCapacity(*feedbackEndpoint);
    }
    const auto profile = neri::usb::uac2::buildUac2FeedbackTimingProfile(
        uac2BusSpeedFromLibusb(usbSpeed),
        makeUac2EndpointSnapshot(
            configurationValue,
            alt,
            outputEndpoint,
            outputPacketBytes
        ),
        makeUac2EndpointSnapshot(
            configurationValue,
            alt,
            *feedbackEndpoint,
            resolvedFeedbackPacketBytes
        )
    );
    if (profile.status != neri::usb::uac2::Uac2FeedbackProfileStatus::Valid) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_feedback_profile_" + std::string(
                neri::usb::uac2::uac2FeedbackProfileStatusName(profile.status)
            ) + ":" + profile.reason;
        }
        return false;
    }

    *feedbackEndpointAddress = feedbackEndpoint->bEndpointAddress;
    *feedbackPacketBytes = resolvedFeedbackPacketBytes;
    *feedbackInterval = feedbackEndpoint->bInterval;
    *timingProfile = profile;
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

std::string describeFeedback(
    const libusb_interface_descriptor& alt,
    const libusb_endpoint_descriptor& outputEndpoint
) {
    if (outputEndpoint.bSynchAddress != 0) {
        char buffer[24];
        snprintf(
            buffer,
            sizeof(buffer),
            "explicit:0x%02X",
            outputEndpoint.bSynchAddress
        );
        return buffer;
    }
    const int outputUsage = usbIsoUsageType(outputEndpoint.bmAttributes);
    if (outputUsage == kLibusbIsoUsageImplicit) {
        return "implicit";
    }
    for (int index = 0; index < alt.bNumEndpoints; ++index) {
        const libusb_endpoint_descriptor& endpoint = alt.endpoint[index];
        const int usage = usbIsoUsageType(endpoint.bmAttributes);
        if (isIsoInEndpoint(endpoint) && usage == kLibusbIsoUsageFeedback) {
            char buffer[24];
            snprintf(buffer, sizeof(buffer), "explicit:0x%02X", endpoint.bEndpointAddress);
            return buffer;
        }
    }
    return "none";
}

struct Uac2ClockPath {
    int audioControlInterface = -1;
    int clockSourceId = 0;
    neri::usb::uac2::ControlCapability sampleRateControl =
        neri::usb::uac2::ControlCapability::None;
};

bool findUac2ClockPath(
    const libusb_config_descriptor* config,
    int terminalLink,
    Uac2ClockPath* output,
    std::string* failureReason
) {
    if (config == nullptr || output == nullptr || terminalLink <= 0) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_invalid_clock_path_input";
        }
        return false;
    }

    bool ambiguousAudioControl = false;
    Uac2ClockPath result;

    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioControl ||
                alt.bInterfaceProtocol != kUsbAudioProtocolUac2 ||
                alt.extra == nullptr ||
                alt.extra_length <= 0) {
                continue;
            }

            int terminalClockSourceId = 0;
            std::vector<neri::usb::uac2::ClockSource> clockSources;
            int offset = 0;
            while (offset + 2 <= alt.extra_length) {
                const int descriptorLength = alt.extra[offset];
                if (descriptorLength < 2 || offset + descriptorLength > alt.extra_length) {
                    break;
                }
                const uint8_t* descriptor = alt.extra + offset;
                if (descriptorLength >= 3 &&
                    descriptor[1] == kUsbDescriptorTypeClassSpecificInterface) {
                    neri::usb::uac2::TerminalClockSource terminal;
                    std::string parseError;
                    if (neri::usb::uac2::parseTerminalClockSourceDescriptor(
                            descriptor,
                            descriptorLength,
                            &terminal,
                            &parseError
                        ) && terminal.terminalId == terminalLink) {
                        terminalClockSourceId = terminal.clockSourceId;
                    }

                    neri::usb::uac2::ClockSource clock;
                    if (neri::usb::uac2::parseClockSourceDescriptor(
                            descriptor,
                            descriptorLength,
                            &clock,
                            &parseError
                        )) {
                        clockSources.push_back(clock);
                    }
                }
                offset += descriptorLength;
            }

            if (terminalClockSourceId <= 0) {
                continue;
            }
            const auto clock = std::find_if(
                clockSources.begin(),
                clockSources.end(),
                [terminalClockSourceId](const neri::usb::uac2::ClockSource& candidate) {
                    return candidate.id == terminalClockSourceId;
                }
            );
            if (clock == clockSources.end()) {
                continue;
            }
            if (result.audioControlInterface >= 0 &&
                result.audioControlInterface != alt.bInterfaceNumber) {
                ambiguousAudioControl = true;
                continue;
            }
            result.audioControlInterface = alt.bInterfaceNumber;
            result.clockSourceId = clock->id;
            result.sampleRateControl = clock->samplingFrequencyControl();
        }
    }

    if (ambiguousAudioControl) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_audio_control_interface_ambiguous";
        }
        return false;
    }
    if (result.audioControlInterface < 0) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_terminal_link_not_found";
        }
        return false;
    }
    if (result.clockSourceId <= 0) {
        if (failureReason != nullptr) {
            *failureReason = "uac2_clock_topology_unsupported";
        }
        return false;
    }
    *output = result;
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

int scoreStreamingCandidate(
    const neri::usb::uac1::TypeIFormat& format,
    const neri::usb::uac1::EndpointControls& controls,
    int sampleRate,
    uint8_t endpointAttributes,
    const std::string& feedback
) {
    int score = 10000;
    if (format.isFixedAt(sampleRate)) {
        score += 400;
    } else if (format.sampleRateKind == neri::usb::uac1::SampleRateKind::Discrete) {
        score += 300;
    } else {
        score += 200;
    }
    if (controls.samplingFrequencyControl) {
        score += 100;
    }
    const int syncType = usbIsoSyncType(endpointAttributes);
    if (syncType == kLibusbIsoSyncTypeAdaptive) {
        score += 40;
    } else if (syncType == kLibusbIsoSyncTypeSynchronous) {
        score += 30;
    } else if (syncType == kLibusbIsoSyncTypeAsynchronous && feedback != "none") {
        score += 20;
    }
    return score;
}

int scoreUac2StreamingCandidate(
    neri::usb::uac2::ControlCapability sampleRateControl,
    uint8_t endpointAttributes,
    const std::string& feedback
) {
    int score = 11000;
    if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadWrite) {
        score += 300;
    } else if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadOnly) {
        score += 100;
    }
    const int syncType = usbIsoSyncType(endpointAttributes);
    if (syncType == kLibusbIsoSyncTypeAdaptive) {
        score += 40;
    } else if (syncType == kLibusbIsoSyncTypeSynchronous) {
        score += 30;
    } else if (syncType == kLibusbIsoSyncTypeAsynchronous && feedback != "none") {
        score += 20;
    }
    return score;
}

bool findStreamingAltUac1(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    libusb_device* device = libusb_get_device(devh);
    if (device == nullptr || output == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "invalid_libusb_device";
        }
        return false;
    }

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(device, &config);
    if (rc != LIBUSB_SUCCESS || config == nullptr) {
        LOGE("libusb_get_active_config_descriptor failed: %s", libusbErrName(rc));
        if (failureReason != nullptr) {
            *failureReason = std::string("active_config_failed:") + libusbErrName(rc);
        }
        return false;
    }

    StreamingAltSelection best;
    std::string rejectionSummary;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioStreaming) {
                continue;
            }
            if (alt.bInterfaceProtocol != kUsbAudioProtocolUac1) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "non_uac1_protocol_" + std::to_string(alt.bInterfaceProtocol)
                );
                continue;
            }

            neri::usb::uac1::TypeIFormat format;
            std::string parseError;
            if (!neri::usb::uac1::parseTypeIFormat(
                    alt.extra,
                    alt.extra_length,
                    &format,
                    &parseError
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    parseError
                );
                continue;
            }
            const neri::usb::uac1::FormatTarget formatTarget {
                sampleRate,
                channelCount,
                subslotBytes,
                bitsPerSample
            };
            std::string matchError;
            if (!neri::usb::uac1::matchesTarget(format, formatTarget, &matchError)) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    matchError
                );
                continue;
            }
            const int actualFrameBytes = format.channels * format.subslotBytes;

            bool hasIsoOutputEndpoint = false;
            for (int epIndex = 0; epIndex < alt.bNumEndpoints; ++epIndex) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[epIndex];
                if (!isIsoOutEndpoint(endpoint)) {
                    continue;
                }
                const int usage = usbIsoUsageType(endpoint.bmAttributes);
                if (usage == kLibusbIsoUsageFeedback) {
                    continue;
                }
                hasIsoOutputEndpoint = true;
                neri::usb::uac1::EndpointControls controls;
                if (!neri::usb::uac1::parseEndpointControls(
                        endpoint.extra,
                        endpoint.extra_length,
                        &controls,
                        &parseError
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        parseError
                    );
                    continue;
                }
                int packetBytes = libusb_get_max_alt_packet_size(
                    device,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress
                );
                if (packetBytes <= 0) {
                    packetBytes = endpointPacketCapacity(endpoint);
                }
                const int intervalsPerSecond = computeIntervalsPerSecond(
                    usbSpeed,
                    endpoint.bInterval
                );
                if (packetBytes <= 0 || computeMaxPacketBytes(
                        sampleRate,
                        intervalsPerSecond,
                        actualFrameBytes,
                        packetBytes
                    ) <= 0) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "endpoint_capacity_insufficient_" + std::to_string(packetBytes)
                    );
                    continue;
                }
                const std::string feedback = describeFeedback(alt, endpoint);
                if (neri::usb::uac1::requiresFeedbackScheduler(endpoint.bmAttributes)) {
                    const std::string feedbackBlockReason = feedback == "implicit"
                        ? "implicit_feedback_scheduler_unavailable"
                        : "async_feedback_scheduler_unavailable";
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        feedbackBlockReason + ":feedback=" + feedback
                    );
                    continue;
                }
                const int score = scoreStreamingCandidate(
                    format,
                    controls,
                    sampleRate,
                    endpoint.bmAttributes,
                    feedback
                );
                LOGI(
                    "UAC1 candidate iface=%d alt=%d ep=0x%02X packetBytes=%d rates=%s score=%d",
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress,
                    packetBytes,
                    format.sampleRateSummary().c_str(),
                    score
                );
                if (score <= best.score) {
                    continue;
                }
                best.interfaceNumber = alt.bInterfaceNumber;
                best.alternateSetting = alt.bAlternateSetting;
                best.audioControlInterface = -1;
                best.outEndpoint = endpoint.bEndpointAddress;
                best.endpointMaxPacketBytes = packetBytes;
                best.endpointInterval = endpoint.bInterval;
                best.score = score;
                best.uacVersion = 1;
                best.uac1.format = format;
                best.uac1.endpointControls = controls;
                best.syncType = neri::usb::uac1::syncTypeName(endpoint.bmAttributes);
                best.feedback = feedback;
                const char* rateKind = format.isFixedAt(sampleRate)
                    ? "fixed"
                    : format.sampleRateKind == neri::usb::uac1::SampleRateKind::Discrete
                        ? "discrete"
                        : "continuous";
                best.reason = "exact_type_i_pcm;rate=" + std::string(rateKind) +
                    ";freqControl=" +
                    (controls.samplingFrequencyControl ? "true" : "false") +
                    ";score=" + std::to_string(score);
            }
            if (!hasIsoOutputEndpoint) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "iso_output_endpoint_missing"
                );
            }
        }
    }

    if (best.interfaceNumber < 0) {
        libusb_free_config_descriptor(config);
        if (failureReason != nullptr) {
            *failureReason = rejectionSummary.empty()
                ? "no_uac1_type_i_output_candidate"
                : rejectionSummary;
        }
        return false;
    }
    best.completeClaimPlan = buildAudioFunctionClaimPlan(
        config,
        best.interfaceNumber,
        &best.claimPlan
    );
    if (!best.completeClaimPlan) {
        LOGW(
            "UAC1 function ownership uses descriptor fallback: iface=%d claimCount=%zu",
            best.interfaceNumber,
            best.claimPlan.size()
        );
    }
    libusb_free_config_descriptor(config);
    *output = std::move(best);
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

bool findStreamingAltUac2(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    libusb_device* device = libusb_get_device(devh);
    if (device == nullptr || output == nullptr) {
        if (failureReason != nullptr) {
            *failureReason = "invalid_libusb_device";
        }
        return false;
    }

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(device, &config);
    if (rc != LIBUSB_SUCCESS || config == nullptr) {
        LOGE("libusb_get_active_config_descriptor failed for UAC2: %s", libusbErrName(rc));
        if (failureReason != nullptr) {
            *failureReason = std::string("active_config_failed:") + libusbErrName(rc);
        }
        return false;
    }

    StreamingAltSelection best;
    std::string rejectionSummary;
    for (int ifaceIndex = 0; ifaceIndex < config->bNumInterfaces; ++ifaceIndex) {
        const libusb_interface& iface = config->interface[ifaceIndex];
        for (int altIndex = 0; altIndex < iface.num_altsetting; ++altIndex) {
            const libusb_interface_descriptor& alt = iface.altsetting[altIndex];
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != kUsbSubclassAudioStreaming) {
                continue;
            }
            if (alt.bInterfaceProtocol != kUsbAudioProtocolUac2) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "non_uac2_protocol_" + std::to_string(alt.bInterfaceProtocol)
                );
                continue;
            }

            neri::usb::uac2::TypeIFormat format;
            std::string parseError;
            if (!neri::usb::uac2::parseTypeIFormat(
                    alt.extra,
                    alt.extra_length,
                    &format,
                    &parseError
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    parseError
                );
                continue;
            }
            const neri::usb::uac2::FormatTarget formatTarget {
                channelCount,
                subslotBytes,
                bitsPerSample
            };
            std::string matchError;
            if (!neri::usb::uac2::matchesTarget(format, formatTarget, &matchError)) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    matchError
                );
                continue;
            }
            const int actualFrameBytes = format.channels * format.subslotBytes;

            Uac2ClockPath clockPath;
            std::string clockFailure;
            if (!findUac2ClockPath(
                    config,
                    format.terminalLink,
                    &clockPath,
                    &clockFailure
                )) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    clockFailure
                );
                continue;
            }
            if (clockPath.sampleRateControl == neri::usb::uac2::ControlCapability::None) {
                int fixedSampleRate = 0;
                std::string fixedRateStatus;
                if (!readUac2CurrentSampleRate(
                        devh,
                        clockPath.audioControlInterface,
                        clockPath.clockSourceId,
                        &fixedSampleRate,
                        &fixedRateStatus
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        fixedRateStatus
                    );
                    continue;
                }
                if (fixedSampleRate != sampleRate) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "uac2_fixed_sample_rate_mismatch_requested=" +
                            std::to_string(sampleRate) + "/actual=" +
                            std::to_string(fixedSampleRate)
                    );
                    continue;
                }
            }

            bool hasIsoOutputEndpoint = false;
            for (int epIndex = 0; epIndex < alt.bNumEndpoints; ++epIndex) {
                const libusb_endpoint_descriptor& endpoint = alt.endpoint[epIndex];
                if (!isIsoOutEndpoint(endpoint)) {
                    continue;
                }
                const int usage = usbIsoUsageType(endpoint.bmAttributes);
                if (usage == kLibusbIsoUsageFeedback) {
                    continue;
                }
                hasIsoOutputEndpoint = true;
                neri::usb::uac2::EndpointControls controls;
                if (!neri::usb::uac2::parseEndpointControls(
                        endpoint.extra,
                        endpoint.extra_length,
                        &controls,
                        &parseError
                    )) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        parseError
                    );
                    continue;
                }
                int packetBytes = libusb_get_max_alt_packet_size(
                    device,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress
                );
                if (packetBytes <= 0) {
                    packetBytes = endpointPacketCapacity(endpoint);
                }
                const int intervalsPerSecond = computeIntervalsPerSecond(
                    usbSpeed,
                    endpoint.bInterval
                );
                if (packetBytes <= 0 || computeMaxPacketBytes(
                        sampleRate,
                        intervalsPerSecond,
                        actualFrameBytes,
                        packetBytes
                    ) <= 0) {
                    appendCandidateRejection(
                        &rejectionSummary,
                        alt.bInterfaceNumber,
                        alt.bAlternateSetting,
                        "endpoint_capacity_insufficient_" + std::to_string(packetBytes)
                    );
                    continue;
                }
                const std::string feedback = describeFeedback(alt, endpoint);
                bool explicitFeedbackEnabled = false;
                uint8_t feedbackEndpointAddress = 0;
                int feedbackPacketBytes = 0;
                int feedbackInterval = 0;
                neri::usb::uac2::Uac2FeedbackTimingProfile feedbackTimingProfile;
                if (neri::usb::uac2::requiresFeedbackScheduler(endpoint.bmAttributes)) {
                    if (feedback == "implicit") {
                        appendCandidateRejection(
                            &rejectionSummary,
                            alt.bInterfaceNumber,
                            alt.bAlternateSetting,
                            "implicit_feedback_scheduler_unavailable:feedback=implicit"
                        );
                        continue;
                    }
                    std::string feedbackProfileFailure;
                    if (!resolveUac2ExplicitFeedbackProfile(
                            device,
                            config->bConfigurationValue,
                            alt,
                            endpoint,
                            packetBytes,
                            usbSpeed,
                            &feedbackEndpointAddress,
                            &feedbackPacketBytes,
                            &feedbackInterval,
                            &feedbackTimingProfile,
                            &feedbackProfileFailure
                        )) {
                        appendCandidateRejection(
                            &rejectionSummary,
                            alt.bInterfaceNumber,
                            alt.bAlternateSetting,
                            feedbackProfileFailure
                        );
                        continue;
                    }
                    explicitFeedbackEnabled = true;
                }
                const int score = scoreUac2StreamingCandidate(
                    clockPath.sampleRateControl,
                    endpoint.bmAttributes,
                    feedback
                );
                LOGI(
                    "UAC2 candidate iface=%d alt=%d ep=0x%02X packetBytes=%d "
                    "clock=%d control=%s endpointDescriptor=%s score=%d",
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    endpoint.bEndpointAddress,
                    packetBytes,
                    clockPath.clockSourceId,
                    neri::usb::uac2::controlCapabilityName(clockPath.sampleRateControl),
                    controls.hasGeneralDescriptor ? "general" : "missing",
                    score
                );
                if (score <= best.score) {
                    continue;
                }
                best.interfaceNumber = alt.bInterfaceNumber;
                best.alternateSetting = alt.bAlternateSetting;
                best.audioControlInterface = clockPath.audioControlInterface;
                best.outEndpoint = endpoint.bEndpointAddress;
                best.endpointMaxPacketBytes = packetBytes;
                best.endpointInterval = endpoint.bInterval;
                best.explicitFeedbackEnabled = explicitFeedbackEnabled;
                best.feedbackEndpoint = feedbackEndpointAddress;
                best.feedbackEndpointMaxPacketBytes = feedbackPacketBytes;
                best.feedbackEndpointInterval = feedbackInterval;
                best.feedbackTimingProfile = feedbackTimingProfile;
                best.score = score;
                best.uacVersion = 2;
                best.uac2.format = format;
                best.uac2.clockSourceId = clockPath.clockSourceId;
                best.uac2.sampleRateControl = clockPath.sampleRateControl;
                best.syncType = neri::usb::uac2::syncTypeName(endpoint.bmAttributes);
                best.feedback = feedback;
                best.reason = "exact_uac2_type_i_pcm;clock=" +
                    std::to_string(clockPath.clockSourceId) + ";rateControl=" +
                    neri::usb::uac2::controlCapabilityName(clockPath.sampleRateControl) +
                    ";score=" + std::to_string(score) +
                    (explicitFeedbackEnabled
                        ? ";feedbackProfile=" + feedbackTimingProfile.evidence.profileId
                        : "");
            }
            if (!hasIsoOutputEndpoint) {
                appendCandidateRejection(
                    &rejectionSummary,
                    alt.bInterfaceNumber,
                    alt.bAlternateSetting,
                    "iso_output_endpoint_missing"
                );
            }
        }
    }

    if (best.interfaceNumber < 0) {
        libusb_free_config_descriptor(config);
        if (failureReason != nullptr) {
            *failureReason = rejectionSummary.empty()
                ? "no_uac2_type_i_output_candidate"
                : rejectionSummary;
        }
        return false;
    }
    best.completeClaimPlan = buildMinimalAudioFunctionClaimPlan(
        config,
        best.audioControlInterface,
        best.interfaceNumber,
        &best.claimPlan
    );
    libusb_free_config_descriptor(config);
    *output = std::move(best);
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

bool findStreamingAlt(
    libusb_device_handle* devh,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    int usbSpeed,
    StreamingAltSelection* output,
    std::string* failureReason
) {
    std::string uac1Failure;
    StreamingAltSelection uac1Selection;
    if (findStreamingAltUac1(
            devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            usbSpeed,
            &uac1Selection,
            &uac1Failure
        )) {
        uac1Failure.clear();
    }

    std::string uac2Failure;
    StreamingAltSelection uac2Selection;
    if (findStreamingAltUac2(
            devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            usbSpeed,
            &uac2Selection,
            &uac2Failure
        )) {
        uac2Failure.clear();
    }

    const bool hasUac1 = uac1Selection.interfaceNumber >= 0;
    const bool hasUac2 = uac2Selection.interfaceNumber >= 0;
    if (hasUac1 || hasUac2) {
        const StreamingAltSelection* best = nullptr;
        if (hasUac1 && hasUac2) {
            best = uac2Selection.score >= uac1Selection.score
                ? &uac2Selection
                : &uac1Selection;
            LOGI(
                "USB audio alt arbitration picked UAC%d over UAC%d (score=%d vs %d)",
                best->uacVersion,
                best->uacVersion == 2 ? 1 : 2,
                best->score,
                best->uacVersion == 2 ? uac1Selection.score : uac2Selection.score
            );
        } else {
            best = hasUac2 ? &uac2Selection : &uac1Selection;
        }
        *output = *best;
        if (failureReason != nullptr) {
            failureReason->clear();
        }
        return true;
    }

    if (failureReason != nullptr) {
        *failureReason = "uac1={" + uac1Failure + "} uac2={" + uac2Failure + "}";
    }
    return false;
}

int computeIntervalsPerSecond(int usbSpeed, int interval) {
    const int normalizedInterval = std::clamp(interval, 1, 16);
    const int intervalUnits = 1 << (normalizedInterval - 1);
    const bool usesMicroframes = usbSpeed == LIBUSB_SPEED_HIGH ||
        usbSpeed == LIBUSB_SPEED_SUPER ||
        usbSpeed == LIBUSB_SPEED_SUPER_PLUS ||
        usbSpeed == LIBUSB_SPEED_SUPER_PLUS_X2;
    const int baseIntervalsPerSecond = usesMicroframes ? 8000 : 1000;
    return std::max(1, baseIntervalsPerSecond / intervalUnits);
}

int scaledPacketsPerTransfer(int intervalsPerSecond) {
    const int normalizedIntervals = std::max(1, intervalsPerSecond);
    const int intervalRatio = std::max(1, normalizedIntervals / 1000);
    return std::clamp(
        kDefaultPacketsPerTransfer * intervalRatio,
        kDefaultPacketsPerTransfer,
        kMaximumPacketsPerTransfer
    );
}

int scaledTransferCount(int intervalsPerSecond, int packetsPerTransfer) {
    const int normalizedIntervals = std::max(1, intervalsPerSecond);
    const bool highSpeedSchedule = normalizedIntervals > 1000;
    const int targetInFlightMs = highSpeedSchedule
        ? kHighSpeedTargetInFlightMs
        : kFullSpeedTargetInFlightMs;
    const int targetPacketsInFlight = std::max(
        kDefaultPacketsPerTransfer,
        normalizedIntervals * targetInFlightMs / 1000
    );
    const int normalizedPacketsPerTransfer = std::max(1, packetsPerTransfer);
    const int requiredTransfers =
        (targetPacketsInFlight + normalizedPacketsPerTransfer - 1) /
        normalizedPacketsPerTransfer;
    return std::clamp(
        requiredTransfers,
        kMinimumTransferCount,
        kMaximumTransferCount
    );
}

int64_t steadyClockMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

int frameAlignedDown(int bytes, int frameBytes) {
    const int frame = std::max(1, frameBytes);
    return std::max(0, (bytes / frame) * frame);
}

int computeMaxPacketBytes(
    int sampleRate,
    int intervalsPerSecond,
    int frameBytes,
    int endpointMaxPacketBytes
) {
    const int frame = std::max(1, frameBytes);
    const int intervals = std::max(1, intervalsPerSecond);
    const int framesPerInterval = (std::max(1, sampleRate) + intervals - 1) / intervals;
    int bytes = std::max(frame, framesPerInterval * frame);
    const int alignedEndpointCapacity = frameAlignedDown(endpointMaxPacketBytes, frame);
    if (endpointMaxPacketBytes > 0 && bytes > alignedEndpointCapacity) {
        return 0;
    }
    return std::max(frame, frameAlignedDown(bytes, frame));
}

bool negotiateUac1SampleRate(
    libusb_device_handle* deviceHandle,
    uint8_t endpointAddress,
    int sampleRate,
    const neri::usb::uac1::TypeIFormat& format,
    const neri::usb::uac1::EndpointControls& controls,
    int* negotiatedSampleRate,
    std::string* status,
    std::string* error
) {
    if (deviceHandle == nullptr || negotiatedSampleRate == nullptr || status == nullptr) {
        if (error != nullptr) {
            *error = "invalid_sample_rate_negotiation_input";
        }
        return false;
    }
    const bool fixedRate = format.isFixedAt(sampleRate);
    if (!controls.samplingFrequencyControl) {
        if (!fixedRate) {
            if (error != nullptr) {
                *error = "sampling_frequency_control_required";
            }
            return false;
        }
        *negotiatedSampleRate = sampleRate;
        *status = "fixed_descriptor_no_control";
        return true;
    }

    constexpr uint8_t kSetCurRequest = 0x01;
    constexpr uint8_t kGetCurRequest = 0x81;
    constexpr uint16_t kSamplingFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };
    const auto outRequestType = makeClassEndpointRequestType(kLibusbEndpointOut);
    const int setResult = libusb_control_transfer(
        deviceHandle,
        outRequestType,
        kSetCurRequest,
        kSamplingFrequencyControl,
        endpointAddress,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (setResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "sample_rate_set_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (setResult != static_cast<int>(sizeof(sampleRateBytes))) {
        const bool unsupportedControl = setResult == LIBUSB_ERROR_PIPE ||
            setResult == LIBUSB_ERROR_NOT_SUPPORTED;
        if (fixedRate && unsupportedControl) {
            *negotiatedSampleRate = sampleRate;
            *status = std::string("fixed_descriptor_set_cur_unsupported:") +
                libusbErrName(setResult);
            return true;
        }
        if (error != nullptr) {
            *error = setResult < 0
                ? std::string("sample_rate_set_cur_failed:") + libusbErrName(setResult)
                : "sample_rate_set_cur_short";
        }
        return false;
    }

    uint8_t verifiedBytes[3] = { 0, 0, 0 };
    const auto inRequestType = makeClassEndpointRequestType(kLibusbEndpointIn);
    const int getResult = libusb_control_transfer(
        deviceHandle,
        inRequestType,
        kGetCurRequest,
        kSamplingFrequencyControl,
        endpointAddress,
        verifiedBytes,
        sizeof(verifiedBytes),
        kControlTimeoutMs
    );
    if (getResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "sample_rate_get_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (getResult == static_cast<int>(sizeof(verifiedBytes))) {
        const int verifiedRate = static_cast<int>(verifiedBytes[0]) |
            (static_cast<int>(verifiedBytes[1]) << 8) |
            (static_cast<int>(verifiedBytes[2]) << 16);
        if (verifiedRate != sampleRate) {
            if (error != nullptr) {
                *error = "sample_rate_verify_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" + std::to_string(verifiedRate);
            }
            return false;
        }
        *negotiatedSampleRate = verifiedRate;
        *status = "set_cur_verified";
        return true;
    }

    *negotiatedSampleRate = sampleRate;
    *status = getResult < 0
        ? std::string("set_cur_unverified:") + libusbErrName(getResult)
        : "set_cur_unverified_short_get";
    return true;
}

bool readUac2CurrentSampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int* currentSampleRate,
    std::string* status
) {
    if (deviceHandle == nullptr || currentSampleRate == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0) {
        if (status != nullptr) {
            *status = "uac2_invalid_get_cur_input";
        }
        return false;
    }

    constexpr uint8_t kCurRequest = 0x01;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[4] = { 0, 0, 0, 0 };
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointIn);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int result = libusb_control_transfer(
        deviceHandle,
        requestType,
        kCurRequest,
        kSampleFrequencyControl,
        entityIndex,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (result != static_cast<int>(sizeof(sampleRateBytes))) {
        if (status != nullptr) {
            *status = result < 0
                ? std::string("uac2_sample_rate_get_cur_failed:") + libusbErrName(result)
                : "uac2_sample_rate_get_cur_short";
        }
        return false;
    }
    std::string decodeError;
    if (!neri::usb::uac2::decodeCurrentSampleRate(
            sampleRateBytes,
            sizeof(sampleRateBytes),
            currentSampleRate,
            &decodeError
        )) {
        if (status != nullptr) {
            *status = "uac2_sample_rate_get_cur_invalid:" + decodeError;
        }
        return false;
    }
    if (status != nullptr) {
        *status = "get_cur_verified";
    }
    return true;
}

bool readUac2SampleRateRanges(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    std::vector<neri::usb::uac2::SampleRateSubrange>* ranges,
    std::string* status
) {
    if (deviceHandle == nullptr || ranges == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0) {
        if (status != nullptr) {
            *status = "uac2_invalid_range_input";
        }
        return false;
    }

    constexpr uint8_t kRangeRequest = 0x02;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    std::array<uint8_t, 512> rangeBytes {};
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointIn);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int result = libusb_control_transfer(
        deviceHandle,
        requestType,
        kRangeRequest,
        kSampleFrequencyControl,
        entityIndex,
        rangeBytes.data(),
        rangeBytes.size(),
        kControlTimeoutMs
    );
    if (result < 0) {
        if (status != nullptr) {
            *status = std::string("uac2_sample_rate_range_failed:") + libusbErrName(result);
        }
        return false;
    }
    std::string parseError;
    if (!neri::usb::uac2::parseSampleRateRanges(
            rangeBytes.data(),
            result,
            ranges,
            &parseError
        )) {
        if (status != nullptr) {
            *status = "uac2_sample_rate_range_parse_failed:" + parseError;
        }
        return false;
    }
    if (status != nullptr) {
        *status = "range_verified";
    }
    return true;
}

bool negotiateUac2SampleRate(
    libusb_device_handle* deviceHandle,
    int audioControlInterface,
    int clockSourceId,
    int sampleRate,
    neri::usb::uac2::ControlCapability sampleRateControl,
    int* negotiatedSampleRate,
    std::string* status,
    std::string* error
) {
    if (deviceHandle == nullptr || negotiatedSampleRate == nullptr || status == nullptr ||
        audioControlInterface < 0 || clockSourceId <= 0 || sampleRate <= 0) {
        if (error != nullptr) {
            *error = "invalid_uac2_sample_rate_negotiation_input";
        }
        return false;
    }

    if (sampleRateControl == neri::usb::uac2::ControlCapability::None) {
        int currentSampleRate = 0;
        std::string currentStatus;
        if (!readUac2CurrentSampleRate(
                deviceHandle,
                audioControlInterface,
                clockSourceId,
                &currentSampleRate,
                &currentStatus
            )) {
            if (error != nullptr) {
                *error = currentStatus;
            }
            return false;
        }
        if (currentSampleRate != sampleRate) {
            if (error != nullptr) {
                *error = "uac2_fixed_sample_rate_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" +
                    std::to_string(currentSampleRate);
            }
            return false;
        }
        *negotiatedSampleRate = currentSampleRate;
        *status = "fixed_get_cur_verified";
        return true;
    }

    if (sampleRateControl == neri::usb::uac2::ControlCapability::ReadOnly) {
        int currentSampleRate = 0;
        std::string currentStatus;
        if (!readUac2CurrentSampleRate(
                deviceHandle,
                audioControlInterface,
                clockSourceId,
                &currentSampleRate,
                &currentStatus
            )) {
            if (error != nullptr) {
                *error = currentStatus;
            }
            return false;
        }
        if (currentSampleRate != sampleRate) {
            if (error != nullptr) {
                *error = "uac2_read_only_sample_rate_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" +
                    std::to_string(currentSampleRate);
            }
            return false;
        }
        *negotiatedSampleRate = currentSampleRate;
        *status = "read_only_get_cur_verified";
        return true;
    }

    if (sampleRateControl != neri::usb::uac2::ControlCapability::ReadWrite) {
        if (error != nullptr) {
            *error = "uac2_sample_rate_control_not_writable";
        }
        return false;
    }

    std::vector<neri::usb::uac2::SampleRateSubrange> ranges;
    std::string rangeStatus;
    if (readUac2SampleRateRanges(
            deviceHandle,
            audioControlInterface,
            clockSourceId,
            &ranges,
            &rangeStatus
        )) {
        const bool rangeSupportsTarget = std::any_of(
            ranges.begin(),
            ranges.end(),
            [sampleRate](const neri::usb::uac2::SampleRateSubrange& range) {
                return range.supports(sampleRate);
            }
        );
        if (!rangeSupportsTarget) {
            if (error != nullptr) {
                *error = "uac2_sample_rate_unsupported_by_range";
            }
            return false;
        }
    } else if (rangeStatus.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
        if (error != nullptr) {
            *error = rangeStatus;
        }
        return false;
    }

    constexpr uint8_t kCurRequest = 0x01;
    constexpr uint16_t kSampleFrequencyControl = 0x0100;
    constexpr unsigned int kControlTimeoutMs = 1000;
    uint8_t sampleRateBytes[4] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFF)
    };
    const auto requestType = makeClassInterfaceRequestType(kLibusbEndpointOut);
    const auto entityIndex = makeClockEntityIndex(clockSourceId, audioControlInterface);
    const int setResult = libusb_control_transfer(
        deviceHandle,
        requestType,
        kCurRequest,
        kSampleFrequencyControl,
        entityIndex,
        sampleRateBytes,
        sizeof(sampleRateBytes),
        kControlTimeoutMs
    );
    if (setResult == LIBUSB_ERROR_NO_DEVICE) {
        if (error != nullptr) {
            *error = "uac2_sample_rate_set_cur_failed:LIBUSB_ERROR_NO_DEVICE";
        }
        return false;
    }
    if (setResult != static_cast<int>(sizeof(sampleRateBytes))) {
        if (error != nullptr) {
            *error = setResult < 0
                ? std::string("uac2_sample_rate_set_cur_failed:") + libusbErrName(setResult)
                : "uac2_sample_rate_set_cur_short";
        }
        return false;
    }

    int verifiedRate = 0;
    std::string getStatus;
    if (readUac2CurrentSampleRate(
            deviceHandle,
            audioControlInterface,
            clockSourceId,
            &verifiedRate,
            &getStatus
        )) {
        if (verifiedRate != sampleRate) {
            if (error != nullptr) {
                *error = "uac2_sample_rate_verify_mismatch_requested=" +
                    std::to_string(sampleRate) + "/actual=" +
                    std::to_string(verifiedRate);
            }
            return false;
        }
        *negotiatedSampleRate = verifiedRate;
        *status = "set_cur_verified";
        return true;
    }

    if (getStatus.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
        if (error != nullptr) {
            *error = getStatus;
        }
        return false;
    }
    *negotiatedSampleRate = sampleRate;
    *status = "set_cur_unverified:" + getStatus;
    return true;
}

bool reconfigureOpenedPlayerPcmOutput(
    UsbExclusiveHandle* handle,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    std::string* error
) {
    if (handle == nullptr || handle->devh == nullptr) {
        if (error != nullptr) {
            *error = "reconfigure_invalid_handle";
        }
        return false;
    }
    if (handle->running.load() || !handle->deviceOnline.load() || handle->closing.load()) {
        if (error != nullptr) {
            *error = "reconfigure_requires_idle_handle";
        }
        return false;
    }
    if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || subslotBytes <= 0 ||
        bitsPerSample > subslotBytes * 8) {
        if (error != nullptr) {
            *error = "reconfigure_invalid_output_format";
        }
        return false;
    }

    StreamingAltSelection selection;
    std::string selectionFailure;
    if (!findStreamingAlt(
            handle->devh,
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            handle->usbSpeed,
            &selection,
            &selectionFailure
        )) {
        if (error != nullptr) {
            *error = "reconfigure_no_compatible_output:" + selectionFailure;
        }
        return false;
    }
    if (selection.interfaceNumber != handle->audioStreamingInterface ||
        selection.audioControlInterface != handle->audioControlInterface ||
        !sameClaimPlan(handle->claimedAudioInterfaces, selection.claimPlan)) {
        if (error != nullptr) {
            *error = "reconfigure_requires_reopen:claim_or_interface_changed";
        }
        return false;
    }

    if (selection.uacVersion == 1 &&
        selection.alternateSetting != handle->alternateSetting) {
        const int rc = libusb_set_interface_alt_setting(
            handle->devh,
            selection.interfaceNumber,
            selection.alternateSetting
        );
        if (rc != LIBUSB_SUCCESS) {
            if (error != nullptr) {
                *error = std::string("reconfigure_set_alt_failed:") + libusbErrName(rc);
            }
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            return false;
        }
        std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        markInterfaceTransitionLocked();
    }

    int negotiatedSampleRate = 0;
    std::string negotiationStatus;
    std::string negotiationError;
    const bool sampleRateNegotiated = selection.uacVersion == 1
        ? negotiateUac1SampleRate(
            handle->devh,
            selection.outEndpoint,
            sampleRate,
            selection.uac1.format,
            selection.uac1.endpointControls,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        )
        : negotiateUac2SampleRate(
            handle->devh,
            selection.audioControlInterface,
            selection.uac2.clockSourceId,
            sampleRate,
            selection.uac2.sampleRateControl,
            &negotiatedSampleRate,
            &negotiationStatus,
            &negotiationError
        );
    if (!sampleRateNegotiated) {
        if (error != nullptr) {
            *error = "reconfigure_sample_rate_failed:" + negotiationError;
        }
        if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
            requestNoDeviceStop(handle);
        }
        return false;
    }

    if (selection.uacVersion == 2) {
        const int rc = libusb_set_interface_alt_setting(
            handle->devh,
            selection.interfaceNumber,
            selection.alternateSetting
        );
        if (rc != LIBUSB_SUCCESS) {
            if (error != nullptr) {
                *error = std::string("reconfigure_set_alt_failed:") + libusbErrName(rc);
            }
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            return false;
        }
        std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        markInterfaceTransitionLocked();
    }

    handle->sampleRate = sampleRate;
    handle->channelCount = channelCount;
    handle->bitsPerSample = bitsPerSample;
    handle->subslotBytes = selection.uacVersion == 1
        ? selection.uac1.format.subslotBytes
        : selection.uac2.format.subslotBytes;
    handle->frameBytes = handle->channelCount * handle->subslotBytes;
    handle->audioStreamingInterface = selection.interfaceNumber;
    handle->audioControlInterface = selection.audioControlInterface;
    handle->alternateSetting = selection.alternateSetting;
    handle->outEndpoint = selection.outEndpoint;
    handle->endpointMaxPacketBytes = selection.endpointMaxPacketBytes;
    handle->endpointInterval = selection.endpointInterval;
    handle->explicitFeedbackEnabled = selection.explicitFeedbackEnabled;
    handle->feedbackEndpoint = selection.feedbackEndpoint;
    handle->feedbackEndpointMaxPacketBytes = selection.feedbackEndpointMaxPacketBytes;
    handle->feedbackEndpointInterval = selection.feedbackEndpointInterval;
    handle->feedbackTimingProfile = selection.feedbackTimingProfile;
    handle->uacVersion = selection.uacVersion;
    handle->uacClockSourceId = selection.uac2.clockSourceId;
    handle->descriptorSampleRates = selection.uacVersion == 1
        ? selection.uac1.format.sampleRateSummary()
        : "uac2_clock_source";
    handle->formatSelectionReason = selection.reason;
    handle->sampleRateControlStatus = negotiationStatus;
    handle->endpointSyncType = selection.syncType;
    handle->endpointFeedback = selection.feedback;
    handle->completeAudioFunctionClaim = selection.completeClaimPlan;
    handle->negotiatedSampleRate = negotiatedSampleRate;
    handle->intervalsPerSecond = computeIntervalsPerSecond(
        handle->usbSpeed,
        handle->endpointInterval
    );
    handle->packetsPerTransfer = handle->explicitFeedbackEnabled
        ? kExplicitFeedbackPacketsPerTransfer
        : scaledPacketsPerTransfer(handle->intervalsPerSecond);
    handle->transferCount = handle->explicitFeedbackEnabled
        ? kExplicitFeedbackAudioTransferCount
        : scaledTransferCount(
            handle->intervalsPerSecond,
            handle->packetsPerTransfer
        );
    handle->bytesPerUsbFrame = computeMaxPacketBytes(
        handle->sampleRate,
        handle->intervalsPerSecond,
        std::max(1, handle->frameBytes),
        handle->endpointMaxPacketBytes
    );
    if (handle->bytesPerUsbFrame <= 0) {
        if (error != nullptr) {
            *error = "reconfigure_endpoint_capacity_too_small";
        }
        return false;
    }
    handle->transferBytes = (handle->explicitFeedbackEnabled
        ? handle->endpointMaxPacketBytes
        : handle->bytesPerUsbFrame) * handle->packetsPerTransfer;
    handle->packetScheduler.configure(
        handle->sampleRate,
        handle->intervalsPerSecond,
        handle->frameBytes
    );
    handle->lastTransferBytes.store(0);
    assignNewNativeStreamGeneration(handle);
    clearError(handle);
    if (error != nullptr) {
        error->clear();
    }
    LOGI(
        "reconfigureOpenedPlayerPcmOutput ok: iface=%d alt=%d sr=%d negotiated=%d ch=%d bits=%d subslot=%d packetBytes=%d",
        handle->audioStreamingInterface,
        handle->alternateSetting,
        handle->sampleRate,
        handle->negotiatedSampleRate,
        handle->channelCount,
        handle->bitsPerSample,
        handle->subslotBytes,
        handle->bytesPerUsbFrame
    );
    return true;
}

const char* sourceName(StreamSource source) {
    return source == StreamSource::PlayerPcm ? "player_pcm" : "tone";
}

bool feedbackTransfersOutstanding(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        return false;
    }
    const auto snapshot = handle->feedbackInTransferSet.snapshot();
    return snapshot.inFlight > 0 || snapshot.callbacksInProgress > 0;
}

bool streamTransfersOutstanding(const UsbExclusiveHandle* handle) {
    return handle != nullptr &&
        (handle->inFlightTransfers.load() > 0 ||
            feedbackTransfersOutstanding(handle));
}

bool feedbackTransferSetActive(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        return false;
    }
    return handle->feedbackInTransferSet.snapshot().state !=
        neri::usb::feedback::FeedbackInTransferSetState::Empty;
}

bool configureExplicitFeedbackRuntime(
    UsbExclusiveHandle* handle,
    std::string* error
) {
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        if (error != nullptr) {
            error->clear();
        }
        return true;
    }
    if (handle->feedbackTimingProfile.status !=
        neri::usb::uac2::Uac2FeedbackProfileStatus::Valid) {
        if (error != nullptr) {
            *error = "feedback_profile_invalid";
        }
        return false;
    }
    neri::usb::feedback::FeedbackRateQ32 nominalRateQ32 = 0;
    const auto nominalStatus = neri::usb::feedback::makeFeedbackRateQ32(
        static_cast<uint32_t>(handle->sampleRate),
        static_cast<uint32_t>(handle->intervalsPerSecond),
        &nominalRateQ32
    );
    if (nominalStatus != neri::usb::feedback::FeedbackMathStatus::Ok ||
        handle->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds == 0 ||
        handle->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds >
            static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
        if (error != nullptr) {
            *error = "feedback_nominal_rate_invalid";
        }
        return false;
    }

    const auto currentTransfers = handle->feedbackInTransferSet.snapshot();
    if (currentTransfers.state !=
            neri::usb::feedback::FeedbackInTransferSetState::Empty ||
        currentTransfers.inFlight != 0 ||
        currentTransfers.callbacksInProgress != 0) {
        if (error != nullptr) {
            *error = "feedback_transfer_set_not_drained";
        }
        return false;
    }

    handle->feedbackNominalRateQ32 = nominalRateQ32;
    const neri::usb::feedback::ExplicitFeedbackRuntimeConfig config {
        static_cast<uint64_t>(handle->nativeStreamGeneration),
        handle->feedbackTimingProfile.decodeProfile,
        nominalRateQ32,
        static_cast<int64_t>(
            handle->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds
        ),
        static_cast<uint32_t>(std::max(1, handle->frameBytes)),
        static_cast<uint32_t>(std::max(1, handle->endpointMaxPacketBytes)),
        kExplicitFeedbackBootstrapPacketLimit,
        handle->feedbackTimingProfile.zeroLengthReportPermitted
    };
    if (!handle->feedbackRuntime.configure(config)) {
        if (error != nullptr) {
            *error = "feedback_runtime_configure_failed";
        }
        return false;
    }
    const neri::usb::feedback::FeedbackInTransferConfig transferConfig {
        handle->devh,
        handle->feedbackEndpoint,
        static_cast<uint32_t>(std::max(1, handle->feedbackEndpointMaxPacketBytes)),
        kExplicitFeedbackTransferCount,
        static_cast<uint64_t>(handle->nativeStreamGeneration)
    };
    std::string transferError;
    if (!handle->feedbackInTransferSet.allocate(
            transferConfig,
            &handle->feedbackRuntime,
            &transferError
        )) {
        handle->feedbackRuntime.stop();
        if (error != nullptr) {
            *error = transferError.empty()
                ? "feedback_transfer_allocate_failed"
                : transferError;
        }
        return false;
    }
    if (!handle->feedbackRuntime.start(steadyClockNanoseconds())) {
        std::string ignoredError;
        handle->feedbackInTransferSet.beginStop(&ignoredError);
        handle->feedbackInTransferSet.freeDrained(&ignoredError);
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
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        return true;
    }
    handle->feedbackRuntime.stop();
    std::string error;
    const bool stopped = handle->feedbackInTransferSet.beginStop(&error);
    if (!stopped && !error.empty()) {
        setError(handle, error);
    }
    return stopped;
}

bool freeExplicitFeedbackTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        return true;
    }
    const auto snapshot = handle->feedbackInTransferSet.snapshot();
    if (snapshot.state == neri::usb::feedback::FeedbackInTransferSetState::Empty) {
        return true;
    }
    std::string error;
    if (!handle->feedbackInTransferSet.freeDrained(&error)) {
        if (!error.empty()) {
            setError(handle, error);
        }
        return false;
    }
    return true;
}

void fillToneBuffer(UsbExclusiveHandle* handle, uint8_t* buffer, size_t bytes) {
    if (handle == nullptr || buffer == nullptr || bytes == 0 ||
        handle->frameBytes <= 0 || handle->channelCount <= 0 || handle->subslotBytes <= 0) {
        return;
    }
    const int frames = static_cast<int>(bytes) / handle->frameBytes;
    const auto sampleRate = static_cast<double>(handle->sampleRate);
    const double phaseStep = 2.0 * M_PI * static_cast<double>(kGeneratedToneFrequencyHz) / sampleRate;
    const double amplitude = 0.30;

    for (int frame = 0; frame < frames; ++frame) {
        const double sample = std::sin(handle->tonePhase) * amplitude;
        handle->tonePhase += phaseStep;
        if (handle->tonePhase > 2.0 * M_PI) {
            handle->tonePhase -= 2.0 * M_PI;
        }

        for (int ch = 0; ch < handle->channelCount; ++ch) {
            const int offset = frame * handle->frameBytes + ch * handle->subslotBytes;
            neri::usb::writeIntegerPcmSample(
                buffer + offset,
                handle->subslotBytes,
                handle->bitsPerSample,
                static_cast<float>(sample)
            );
        }
    }
}

bool startStreamingInternal(
    UsbExclusiveHandle* handle,
    StreamSource source
) {
    if (handle == nullptr || handle->devh == nullptr || !handle->deviceOnline.load() ||
        handle->closing.load()) {
        LOGW("startStreamingInternal rejected: invalid handle");
        return false;
    }
    LOGI(
        "startStreamingInternal request: source=%s running=%d failed=%d playback=%d "
        "transferBytes=%d transferCount=%d packets=%d",
        sourceName(source),
        handle->running.load() ? 1 : 0,
        handle->transportFailed.load() ? 1 : 0,
        handle->playbackEnabled.load() ? 1 : 0,
        handle->transferBytes,
        handle->transferCount,
        handle->packetsPerTransfer
    );
    if (
        handle->running.load() ||
        !handle->transfers.empty() ||
        handle->eventThread.joinable()
    ) {
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            if (!handle->deviceOnline.load() || handle->stopRequested.load() ||
                handle->closing.load()) {
                return false;
            }
            if (!handle->transportFailed.load() && handle->streamSource.load() == source) {
                return true;
            }
        }
        LOGW(
            "startStreamingInternal restarts active stream: oldSource=%s newSource=%s failed=%d",
            sourceName(handle->streamSource.load()),
            sourceName(source),
            handle->transportFailed.load() ? 1 : 0
        );
        if (!stopStreamingInternal(handle)) {
            return false;
        }
    }

    clearError(handle);
    handle->streamSource.store(source);
    handle->completedTransfers.store(0);
    handle->submitErrors.store(0);
    handle->isoPacketErrors.store(0);
    handle->isoPacketErrorTransfers.store(0);
    handle->isoPacketErrorScore.store(0);
    handle->scheduledPackets.store(0);
    handle->scheduledFrames.store(0);
    handle->packetFramesMin.store(std::numeric_limits<int>::max());
    handle->packetFramesMax.store(0);
    handle->lastTransferBytes.store(0);
    handle->shortWriteWarnings.store(0);
    handle->firstTransferSubmittedAtMs.store(0);
    handle->lastTransferCompletionAtMs.store(0);
    handle->packetScheduler.reset();
    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (!handle->deviceOnline.load() || handle->closing.load()) {
            setError(handle, "stream_start_cancelled_device_offline");
            return false;
        }
        handle->stopRequested.store(false);
        handle->transportFailed.store(false);
        handle->inFlightTransfers.store(0);
    }
    assignNewNativeStreamGeneration(handle);
    if (handle->explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!configureExplicitFeedbackRuntime(handle, &feedbackError)) {
            setError(
                handle,
                feedbackError.empty()
                    ? "feedback_runtime_configure_failed"
                    : feedbackError
            );
            markTransportFailed(handle);
            return false;
        }
    }
    if (source == StreamSource::PlayerPcm) {
        handle->playerStartupPreroll.arm(handle->sampleRate, kPlayerStartupPrerollMs);
        handle->pcmPipeline.armTransportStartRamp();
    }
    if (!allocateTransfers(handle)) {
        LOGE("allocateTransfers failed before stream start: error=%s", getErrorCopy(handle).c_str());
        freeTransfers(handle);
        return false;
    }

    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (shouldStopTransferSubmission(handle)) {
            setError(handle, "stream_start_cancelled_after_allocation");
        } else {
            handle->running.store(true);
        }
    }
    if (!handle->running.load()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (handle->explicitFeedbackEnabled) {
        std::string feedbackError;
        if (!handle->feedbackInTransferSet.submitAll(&feedbackError)) {
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
    for (libusb_transfer* transfer : handle->transfers) {
        int rc = LIBUSB_ERROR_NO_DEVICE;
        bool cancelled = false;
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            cancelled = shouldStopTransferSubmission(handle) || !handle->running.load();
            if (!cancelled) {
                rc = libusb_submit_transfer(transfer);
                if (rc == LIBUSB_SUCCESS) {
                    const int64_t submittedAtMs = steadyClockMillis();
                    int64_t firstSubmittedAtMs = 0;
                    if (handle->firstTransferSubmittedAtMs.compare_exchange_strong(
                            firstSubmittedAtMs,
                            submittedAtMs
                        )) {
                        handle->lastTransferCompletionAtMs.store(submittedAtMs);
                    }
                    handle->inFlightTransfers.fetch_add(1);
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
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        if (shouldStopTransferSubmission(handle) || !handle->running.load()) {
            setError(handle, "stream_start_cancelled_before_event_thread");
        } else {
            handle->eventThread = std::thread(eventLoopThread, handle);
        }
    } catch (const std::system_error& error) {
        setError(handle, std::string("event_thread_start_failed:") + error.what());
        markTransportFailed(handle);
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }
    if (!handle->eventThread.joinable()) {
        if (!stopStreamingInternal(handle)) {
            return false;
        }
        return false;
    }

    LOGI(
        "native stream started: source=%s inFlight=%d transferBytes=%d packetBytes=%d",
        sourceName(source),
        handle->inFlightTransfers.load(),
        handle->transferBytes,
        handle->bytesPerUsbFrame
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
    if (handle == nullptr || !handle->explicitFeedbackEnabled) {
        return;
    }
    const auto snapshot = handle->feedbackRuntime.snapshot();
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
    if (handle == nullptr || transfer == nullptr || handle->bytesPerUsbFrame <= 0) {
        return -1;
    }
    auto* userData = static_cast<TransferUserData*>(transfer->user_data);
    if (userData != nullptr) {
        userData->forceSilence = false;
    }
    const int packetCount = transfer->num_iso_packets;
    int totalAssigned = 0;
    const bool explicitFeedback = handle->explicitFeedbackEnabled;
    bool allowRealPayload = false;
    if (explicitFeedback) {
        const auto snapshot = handle->feedbackRuntime.snapshot();
        const auto clockState = snapshot.gate.clock.state;
        const bool feedbackUsable =
            clockState == neri::usb::feedback::FeedbackClockState::Locked ||
            clockState == neri::usb::feedback::FeedbackClockState::Holdover ||
            clockState == neri::usb::feedback::FeedbackClockState::Relocking;
        const bool sourceAvailable = handle->streamSource.load() == StreamSource::Tone ||
            (handle->playbackEnabled.load() &&
                handle->deviceOnline.load() &&
                !handle->focusMuted.load());
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
            const auto plan = handle->feedbackRuntime.nextPacket(allowRealPayload);
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
            const neri::usb::IsoPacketPlan plan = handle->packetScheduler.next();
            packetBytes = plan.bytes;
            packetFrames = plan.frames;
        }
        if (packetBytes < 0 || packetBytes > handle->endpointMaxPacketBytes) {
            setError(handle, "scheduled_packet_exceeds_endpoint_capacity");
            markTransportFailed(handle);
            return -1;
        }
        transfer->iso_packet_desc[packetIndex].length = packetBytes;
        totalAssigned += packetBytes;
        handle->scheduledPackets.fetch_add(1);
        handle->scheduledFrames.fetch_add(packetFrames);
        updateAtomicMinimum(handle->packetFramesMin, packetFrames);
        updateAtomicMaximum(handle->packetFramesMax, packetFrames);
    }
    transfer->length = totalAssigned;
    handle->lastTransferBytes.store(totalAssigned);
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
    if (handle == nullptr || handle->frameBytes <= 0) {
        return 0;
    }
    return static_cast<int64_t>(
        handle->playerReplayBuffer.queuedBytes() / static_cast<size_t>(handle->frameBytes)
    );
}

void clearPlayerReplayState(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->playerReplayBuffer.clear();
    handle->nextPlayerSequence.store(1);
    handle->preserveCancelledPlayerFrames.store(false);
    handle->playerReplayFailed.store(false);
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
    if (slot < 0 || slot >= static_cast<int>(handle->transferBuffers.size()) ||
        handle->frameBytes <= 0 || userData->playerSequence == 0) {
        handle->playerReplayFailed.store(true);
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
        static_cast<size_t>(handle->frameBytes);
    const size_t replayBytes = static_cast<size_t>(replayFrames) *
        static_cast<size_t>(handle->frameBytes);
    const auto& buffer = handle->transferBuffers[static_cast<size_t>(slot)];
    if (replayOffset + replayBytes > buffer.size() || transfer->buffer == nullptr ||
        (replayBytes > 0 && !handle->playerReplayBuffer.push(
            userData->playerSequence,
            transfer->buffer + replayOffset,
            replayBytes
        ))) {
        handle->playerReplayFailed.store(true);
        return false;
    }
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->stagedPlayerFrames, queuedFrames);
    if (completedFrames > 0) {
        handle->completedAudioFrames.fetch_add(completedFrames);
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
    subtractAtomicFloorZero(handle->stagedPlayerFrames, frames);
    const int64_t boundedCompletedFrames = std::clamp<int64_t>(completedFrames, 0, frames);
    if (boundedCompletedFrames > 0) {
        handle->completedAudioFrames.fetch_add(boundedCompletedFrames);
    }
    const int64_t droppedFrames = frames - boundedCompletedFrames;
    if (droppedFrames > 0) {
        handle->pcmPipeline.addDroppedFrames(droppedFrames);
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
    if (slot < 0 || slot >= static_cast<int>(handle->transferBuffers.size())) {
        return false;
    }
    auto& buffer = handle->transferBuffers[slot];
    transfer->buffer = buffer.data();
    const int transferBytes = applyIsoPacketLengths(handle, transfer);
    if (transferBytes < 0 || static_cast<size_t>(transferBytes) > buffer.size()) {
        return false;
    }

    if (handle->streamSource.load() == StreamSource::PlayerPcm) {
        const auto transferSize = static_cast<size_t>(transferBytes);
        if ((userData != nullptr && userData->forceSilence) ||
            handle->playerStartupPreroll.fillSilenceIfNeeded(
                buffer.data(),
                transferSize,
                handle->frameBytes
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
        const bool renderPlayerPcm = handle->playbackEnabled.load() &&
            handle->deviceOnline.load() &&
            !handle->focusMuted.load();
        const size_t replayBytes = renderPlayerPcm
            ? handle->playerReplayBuffer.read(buffer.data(), transferSize)
            : 0;
        const size_t pipelineBytes = handle->pcmPipeline.fill(
            buffer.data() + replayBytes,
            transferSize - replayBytes,
            renderPlayerPcm
        );
        const size_t playerBytes = replayBytes + pipelineBytes;
        if (playerBytes > 0) {
            handle->pcmPipeline.applyTransportStartRamp(buffer.data(), playerBytes);
        }
        if (userData != nullptr) {
            const int64_t queuedFrames = static_cast<int64_t>(
                playerBytes / static_cast<size_t>(std::max(1, handle->frameBytes))
            );
            userData->queuedPlayerFrames = queuedFrames;
            userData->playerSequence = queuedFrames > 0
                ? handle->nextPlayerSequence.fetch_add(1)
                : 0;
            handle->stagedPlayerFrames.fetch_add(queuedFrames);
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

struct TransferCallbackCompletion final {
    UsbExclusiveHandle* handle = nullptr;
    bool resubmitted = false;

    ~TransferCallbackCompletion() {
        if (handle != nullptr && !resubmitted) {
            handle->inFlightTransfers.fetch_sub(1);
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
            static_cast<uint64_t>(handle->nativeStreamGeneration);
    if (
        userData->slot >= 0 &&
        userData->slot < static_cast<int>(handle->transferStatuses.size())
    ) {
        handle->transferStatuses[static_cast<size_t>(userData->slot)] = transfer->status;
    }
    TransferCallbackCompletion completion { handle, false };
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
        const int64_t completedPacketFrames = handle->frameBytes > 0
            ? completedPacketBytes / handle->frameBytes
            : 0;
        const int64_t completedPacketPrefixFrames = handle->frameBytes > 0
            ? completedPacketPrefixBytes / handle->frameBytes
            : 0;
        const bool replayedCancelledFrames =
            transferCancelled &&
            handle->preserveCancelledPlayerFrames.load() &&
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
            const int currentScore = handle->isoPacketErrorScore.load();
            handle->isoPacketErrorScore.store(
                neri::usb::updateIsoPacketErrorScore(currentScore, 0)
            );
            handle->lastTransferCompletionAtMs.store(steadyClockMillis());
            const int completedBefore = handle->completedTransfers.fetch_add(1);
            if (completedBefore == 0) {
                LOGI(
                    "first USB transfer completed: packets=%d requested=%d actual=%d",
                    transfer->num_iso_packets,
                    transfer->length,
                    transfer->actual_length
                );
            }
        } else if (transferCompleted && !packetReportedNoDevice) {
            const int errorTransfers = handle->isoPacketErrorTransfers.fetch_add(1) + 1;
            const int totalPacketErrors = handle->isoPacketErrors.fetch_add(failedPacketCount) +
                failedPacketCount;
            const int errorScore = neri::usb::updateIsoPacketErrorScore(
                handle->isoPacketErrorScore.load(),
                failedPacketCount
            );
            handle->isoPacketErrorScore.store(errorScore);
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
                    handle->completedTransfers.load(),
                    handle->inFlightTransfers.load(),
                    transfer->actual_length,
                    fatalPacketBurst ? 1 : 0
                );
            }
            if (fatalPacketBurst) {
                handle->submitErrors.fetch_add(1);
                markTransportFailed(handle);
                setError(handle, "iso_packet_status_failed");
                return;
            }
        } else if (transfer->status != LIBUSB_TRANSFER_CANCELLED) {
            if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE || packetReportedNoDevice) {
                requestNoDeviceStop(handle);
            }
            handle->submitErrors.fetch_add(1);
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
                handle->completedTransfers.load(),
                handle->submitErrors.load(),
                handle->inFlightTransfers.load(),
                transfer->actual_length
            );
            return;
        }

        if (shouldStopTransferSubmission(handle)) {
            return;
        }
        if (!refillTransfer(handle, transfer)) {
            handle->submitErrors.fetch_add(1);
            markTransportFailed(handle);
            return;
        }

        int rc = LIBUSB_ERROR_NO_DEVICE;
        {
            std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
            if (shouldStopTransferSubmission(handle)) {
                settlePreparedPlayerFrames(handle, userData, false);
                return;
            }
            rc = libusb_submit_transfer(transfer);
            if (rc == LIBUSB_SUCCESS) {
                completion.resubmitted = true;
            }
        }
        if (rc != LIBUSB_SUCCESS) {
            settlePreparedPlayerFrames(handle, userData, false);
            handle->submitErrors.fetch_add(1);
            markTransportFailed(handle);
            setError(handle, std::string("resubmit_failed:") + libusbErrName(rc));
            LOGE(
                "libusb_submit_transfer resubmit failed: %s completed=%d submitErrors=%d inFlight=%d",
                libusbErrName(rc),
                handle->completedTransfers.load(),
                handle->submitErrors.load(),
                handle->inFlightTransfers.load()
            );
            return;
        }
    } catch (const std::exception& error) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("transfer_callback_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB transfer callback exception: %s", error.what());
    } catch (...) {
        settlePreparedPlayerFrames(handle, userData, false);
        handle->submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, "transfer_callback_unknown_exception");
        } catch (...) {
        }
        LOGE("USB transfer callback unknown exception");
    }
}

bool allocateTransfers(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->transferBytes <= 0) {
        return false;
    }

    try {
        handle->transfers.reserve(handle->transferCount);
        handle->transferBuffers.reserve(handle->transferCount);
        handle->transferUserData.reserve(handle->transferCount);
        handle->transferStatuses.reserve(handle->transferCount);

        for (int index = 0; index < handle->transferCount; ++index) {
            libusb_transfer* transfer = libusb_alloc_transfer(handle->packetsPerTransfer);
            if (transfer == nullptr) {
                setError(handle, "libusb_alloc_transfer_failed");
                return false;
            }
            try {
                handle->transferBuffers.emplace_back(
                    static_cast<size_t>(handle->transferBytes),
                    0
                );
                auto& buffer = handle->transferBuffers.back();
                handle->transferUserData.push_back(
                    TransferUserData {
                        handle,
                        index,
                        0,
                        0,
                        static_cast<uint64_t>(handle->nativeStreamGeneration),
                        false
                    }
                );
                handle->transferStatuses.push_back(-1);

                libusb_fill_iso_transfer(
                    transfer,
                    handle->devh,
                    handle->outEndpoint,
                    buffer.data(),
                    handle->transferBytes,
                    handle->packetsPerTransfer,
                    transferCallback,
                    &handle->transferUserData.back(),
                    0
                );
                if (!refillTransfer(handle, transfer)) {
                    libusb_free_transfer(transfer);
                    setError(handle, "initial_transfer_refill_failed");
                    return false;
                }
                handle->transfers.push_back(transfer);
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
    for (libusb_transfer* transfer : handle->transfers) {
        if (transfer != nullptr) {
            libusb_free_transfer(transfer);
        }
    }
    for (TransferUserData& userData : handle->transferUserData) {
        settlePreparedPlayerFrames(handle, &userData, false);
    }
    handle->transfers.clear();
    handle->transferBuffers.clear();
    handle->transferUserData.clear();
    handle->transferStatuses.clear();
    handle->inFlightTransfers.store(0);
}

void eventLoopThread(UsbExclusiveHandle* handle) noexcept {
    try {
        configureUsbEventThreadPriority();
        int consecutiveErrors = 0;
        LOGI("USB event loop entered");
        while (handle->deviceOnline.load() && !handle->stopRequested.load()) {
            int eventWaitTimeoutMs = kEventLoopWaitTimeoutMs;
            if (handle->explicitFeedbackEnabled &&
                handle->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds > 0) {
                const uint64_t expectedPeriodMs =
                    (handle->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds +
                        UINT64_C(999999)) /
                    UINT64_C(1000000);
                eventWaitTimeoutMs = static_cast<int>(std::clamp<uint64_t>(
                    expectedPeriodMs,
                    1,
                    10
                ));
            }
            timeval timeout = timeoutFromMilliseconds(eventWaitTimeoutMs);
            const int rc = libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
                const int totalErrors = handle->submitErrors.fetch_add(1) + 1;
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle);
                    handle->running.store(false);
                    markTransportFailed(handle);
                    setError(handle, "event_loop_failed:LIBUSB_ERROR_NO_DEVICE");
                    LOGE(
                        "USB event loop lost device: totalErrors=%d inFlight=%d",
                        totalErrors,
                        handle->inFlightTransfers.load()
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
                    handle->running.store(false);
                    markTransportFailed(handle);
                    handle->stopRequested.store(true);
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
            if (handle->explicitFeedbackEnabled &&
                !handle->feedbackRuntime.tick(steadyClockNanoseconds())) {
                failForExplicitFeedbackRuntime(handle);
                handle->running.store(false);
                handle->stopRequested.store(true);
                LOGE(
                    "USB explicit feedback runtime failed: state=%s failure=%s",
                    neri::usb::feedback::explicitFeedbackRuntimeStateName(
                        handle->feedbackRuntime.snapshot().state
                    ),
                    neri::usb::feedback::explicitFeedbackRuntimeFailureName(
                        handle->feedbackRuntime.snapshot().failure
                    )
                );
                break;
            }
            const int64_t firstSubmittedAtMs = handle->firstTransferSubmittedAtMs.load();
            if (
                handle->completedTransfers.load() == 0 &&
                handle->inFlightTransfers.load() > 0 &&
                firstSubmittedAtMs > 0 &&
                steadyClockMillis() - firstSubmittedAtMs >= kFirstTransferCompletionTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->running.store(false);
                handle->stopRequested.store(true);
                setError(handle, "event_loop_first_completion_timeout");
                LOGE(
                    "USB event loop timed out before first completion: inFlight=%d",
                    handle->inFlightTransfers.load()
                );
                break;
            }
            const int64_t lastCompletionAtMs = handle->lastTransferCompletionAtMs.load();
            if (
                handle->completedTransfers.load() > 0 &&
                handle->inFlightTransfers.load() > 0 &&
                lastCompletionAtMs > 0 &&
                steadyClockMillis() - lastCompletionAtMs >=
                    kTransferCompletionStallTimeoutMs
            ) {
                markTransportFailed(handle);
                handle->running.store(false);
                handle->stopRequested.store(true);
                setError(handle, "event_loop_completion_stalled");
                LOGE(
                    "USB event loop stalled after completions: completed=%d inFlight=%d",
                    handle->completedTransfers.load(),
                    handle->inFlightTransfers.load()
                );
                break;
            }
            if (handle->transportFailed.load() &&
                !streamTransfersOutstanding(handle)) {
                handle->running.store(false);
                handle->stopRequested.store(true);
                break;
            }
        }
        LOGI(
            "USB event loop exited: running=%d stop=%d completed=%d errors=%d inFlight=%d",
            handle->running.load() ? 1 : 0,
            handle->stopRequested.load() ? 1 : 0,
            handle->completedTransfers.load(),
            handle->submitErrors.load(),
            handle->inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        handle->submitErrors.fetch_add(1);
        markTransportFailed(handle);
        try {
            setError(handle, std::string("event_loop_exception:") + error.what());
        } catch (...) {
        }
        LOGE("USB event loop exception: %s", error.what());
    } catch (...) {
        handle->submitErrors.fetch_add(1);
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
    for (size_t index = 0; index < handle->transfers.size(); ++index) {
        libusb_transfer* transfer = handle->transfers[index];
        const int callbackStatus = index < handle->transferStatuses.size()
            ? handle->transferStatuses[index]
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
    while (handle->inFlightTransfers.load() > 0 ||
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
        const int rc = handle->ctx != nullptr
            ? libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr)
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
                handle->inFlightTransfers.load(),
                handle->explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->feedbackInTransferSet.snapshot().inFlight
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
                handle->inFlightTransfers.load(),
                handle->explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
            );
            logTransferStatuses(handle);
        }
        if (now >= hardDeadline) {
            markTransportFailed(handle);
            handle->deviceOnline.store(false);
            handle->playbackEnabled.store(false);
            try {
                setError(handle, "cancel_drain_timeout");
            } catch (...) {
            }
            LOGE(
                "cancel drain timed out: audioInFlight=%d feedbackInFlight=%d "
                "completed=%d errors=%d",
                handle->inFlightTransfers.load(),
                handle->explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0,
                handle->completedTransfers.load(),
                handle->submitErrors.load()
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
    const bool wasRunning = handle->running.exchange(false);
    if (!wasRunning && handle->transfers.empty() &&
        !handle->eventThread.joinable() && !feedbackTransferSetActive(handle)) {
        return true;
    }

    LOGI(
        "stopStreamingInternal begin: source=%s inFlight=%d completed=%d errors=%d",
        sourceName(handle->streamSource.load()),
        handle->inFlightTransfers.load(),
        handle->completedTransfers.load(),
        handle->submitErrors.load()
    );
    {
        std::lock_guard<std::mutex> submitGuard(handle->transferSubmitLock);
        handle->stopRequested.store(true);
    }
    stopExplicitFeedbackRuntime(handle);
    interruptUsbEventHandler(handle);
    for (libusb_transfer* transfer : handle->transfers) {
        if (transfer != nullptr) {
            const int rc = libusb_cancel_transfer(transfer);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("libusb_cancel_transfer failed: %s", libusbErrName(rc));
            }
        }
    }
    interruptUsbEventHandler(handle);
    if (handle->eventThread.joinable()) {
        handle->eventThread.join();
    }
    const auto hardDeadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kCancelDrainDeadlineMs);
    if (!drainCancelledTransfers(handle, hardDeadline)) {
        return false;
    }
    freeTransfers(handle);
    if (feedbackTransferSetActive(handle)) {
        setError(handle, "feedback_transfer_set_not_drained");
        return false;
    }
    LOGI(
        "stopStreamingInternal done: completed=%d errors=%d transportFailed=%d",
        handle->completedTransfers.load(),
        handle->submitErrors.load(),
        handle->transportFailed.load() ? 1 : 0
    );
    return true;
}

void releaseClaimedAudioInterfaces(UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->devh == nullptr) {
        return;
    }
    for (auto entry = handle->claimedAudioInterfaces.rbegin();
         entry != handle->claimedAudioInterfaces.rend();
         ++entry) {
        const int releaseRc = libusb_release_interface(handle->devh, entry->interfaceNumber);
        if (releaseRc != LIBUSB_SUCCESS) {
            LOGW(
                "release interface failed: iface=%d err=%s",
                entry->interfaceNumber,
                libusbErrName(releaseRc)
            );
        } else {
            LOGI("released interface: iface=%d", entry->interfaceNumber);
        }
    }
    handle->claimedAudioInterfaces.clear();
}

int claimAudioInterface(UsbExclusiveHandle* handle, int interfaceNumber) {
    if (handle == nullptr || handle->devh == nullptr || interfaceNumber < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    return libusb_claim_interface(handle->devh, interfaceNumber);
}

bool claimAudioFunction(
    UsbExclusiveHandle* handle,
    const std::vector<ClaimedUsbInterface>& claimPlan,
    std::string* failureReason
) {
    if (handle == nullptr || handle->devh == nullptr || claimPlan.empty()) {
        if (failureReason != nullptr) {
            *failureReason = "empty_audio_claim_plan";
        }
        return false;
    }
    for (const ClaimedUsbInterface& planned : claimPlan) {
        const int rc = claimAudioInterface(handle, planned.interfaceNumber);
        if (rc != LIBUSB_SUCCESS) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle);
            }
            if (failureReason != nullptr) {
                *failureReason =
                    "claim_interface_failed:iface=" +
                    std::to_string(planned.interfaceNumber) + ":" +
                    libusbErrName(rc);
            }
            releaseClaimedAudioInterfaces(handle);
            return false;
        }
        handle->claimedAudioInterfaces.push_back(
            ClaimedUsbInterface {
                planned.interfaceNumber,
                planned.subclass
            }
        );
        LOGI(
            "claimed UAC interface: iface=%d subclass=%d",
            planned.interfaceNumber,
            static_cast<int>(planned.subclass)
        );
    }
    if (failureReason != nullptr) {
        failureReason->clear();
    }
    return true;
}

void finishClosedUsbResources(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    if (handle->devh != nullptr && !handle->detachBroadcastConfirmed.load()) {
        const bool streamingInterfaceClaimed = std::any_of(
            handle->claimedAudioInterfaces.begin(),
            handle->claimedAudioInterfaces.end(),
            [handle](const ClaimedUsbInterface& entry) {
                return entry.interfaceNumber == handle->audioStreamingInterface;
            }
        );
        if (streamingInterfaceClaimed && handle->alternateSetting > 0) {
            const int idleAltRc = libusb_set_interface_alt_setting(
                handle->devh,
                handle->audioStreamingInterface,
                0
            );
            if (idleAltRc == LIBUSB_SUCCESS) {
                markInterfaceTransitionLocked();
                LOGI(
                    "restored idle alt setting: iface=%d alt=0",
                    handle->audioStreamingInterface
                );
            } else {
                LOGW("restore idle alt setting failed: %s", libusbErrName(idleAltRc));
            }
        }
        releaseClaimedAudioInterfaces(handle);
        libusb_close(handle->devh);
        handle->devh = nullptr;
    } else if (handle->devh != nullptr) {
        LOGW("skip interface ioctls after physical USB detach");
        handle->claimedAudioInterfaces.clear();
        libusb_close(handle->devh);
        handle->devh = nullptr;
    }
    if (handle->ctx != nullptr) {
        libusb_exit(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->dupFd >= 0) {
        close(handle->dupFd);
        handle->dupFd = -1;
    }
    LOGI("closeHandleInternal done");
}

size_t parkedHandleCountLocked() {
    return static_cast<size_t>(std::count_if(
        g_parkedHandles.begin(),
        g_parkedHandles.end(),
        [](const ParkedHandleSlot& slot) {
            return slot.handle != nullptr;
        }
    ));
}

int parkHandleForRecovery(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    int quarantineIndex,
    const char* reason
) noexcept {
    if (handle == nullptr) {
        return -1;
    }
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        for (size_t slotIndex = 0; slotIndex < g_parkedHandles.size(); ++slotIndex) {
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle != nullptr) {
                continue;
            }
            slot.handle = handle;
            slot.parkedAt = std::chrono::steady_clock::now();
            slot.quarantineIndex = quarantineIndex;
            slot.pumpActive = true;
            LOGE(
                "parked USB handle: index=%d slot=%zu reason=%s parkedCount=%zu "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                reason != nullptr ? reason : "unknown",
                parkedHandleCountLocked(),
                handle->inFlightTransfers.load()
            );
            return static_cast<int>(slotIndex);
        }
        LOGE(
            "USB parked handle registry full: capacity=%zu index=%d inFlight=%d",
            g_parkedHandles.size(),
            quarantineIndex,
            handle->inFlightTransfers.load()
        );
    } catch (const std::exception& error) {
        LOGE("failed to park USB handle: index=%d error=%s", quarantineIndex, error.what());
    } catch (...) {
        LOGE("failed to park USB handle: index=%d error=unknown", quarantineIndex);
    }
    return -1;
}

void hardRetainHandle(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    int quarantineIndex,
    const char* reason
) noexcept {
    if (handle == nullptr) {
        return;
    }
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        for (size_t slotIndex = 0; slotIndex < g_hardRetainedHandles.size(); ++slotIndex) {
            if (g_hardRetainedHandles[slotIndex] != nullptr) {
                continue;
            }
            g_hardRetainedHandles[slotIndex] = handle;
            LOGE(
                "hard-retained USB handle: index=%d slot=%zu reason=%s inFlight=%d",
                quarantineIndex,
                slotIndex,
                reason != nullptr ? reason : "unknown",
                handle->inFlightTransfers.load()
            );
            return;
        }
    } catch (...) {
    }

    auto* leakedHandle = new (std::nothrow) std::shared_ptr<UsbExclusiveHandle>(handle);
    static_cast<void>(leakedHandle);
    LOGE(
        "hard-retained USB handle outside fixed registry: index=%d reason=%s "
        "retained=%d inFlight=%d",
        quarantineIndex,
        reason != nullptr ? reason : "unknown",
        leakedHandle != nullptr ? 1 : 0,
        handle->inFlightTransfers.load()
    );
}

bool setParkedPumpActive(
    size_t slotIndex,
    const UsbExclusiveHandle* expectedHandle,
    bool active
) noexcept {
    try {
        std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
        if (slotIndex >= g_parkedHandles.size()) {
            return false;
        }
        ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
        if (slot.handle.get() != expectedHandle) {
            return false;
        }
        slot.pumpActive = active;
        return true;
    } catch (...) {
        return false;
    }
}

void releaseParkedHandle(
    size_t slotIndex,
    const UsbExclusiveHandle* expectedHandle,
    const char* source
) noexcept {
    try {
        size_t parkedCount = 0;
        int quarantineIndex = 0;
        {
            std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
            if (slotIndex >= g_parkedHandles.size()) {
                return;
            }
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle.get() != expectedHandle) {
                return;
            }
            quarantineIndex = slot.quarantineIndex;
            slot.handle.reset();
            slot.parkedAt = {};
            slot.quarantineIndex = 0;
            slot.pumpActive = false;
            parkedCount = parkedHandleCountLocked();
        }
        const int activeQuarantines = g_quarantinedDrainHandles.fetch_sub(1) - 1;
        LOGI(
            "reclaimed parked USB handle: index=%d slot=%zu source=%s parkedCount=%zu "
            "activeQuarantines=%d",
            quarantineIndex,
            slotIndex,
            source != nullptr ? source : "unknown",
            parkedCount,
            activeQuarantines
        );
    } catch (const std::exception& error) {
        LOGE("failed to release parked USB handle slot=%zu error=%s", slotIndex, error.what());
    } catch (...) {
        LOGE("failed to release parked USB handle slot=%zu error=unknown", slotIndex);
    }
}

void finishParkedHandle(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    size_t slotIndex,
    const char* source
) noexcept {
    if (handle == nullptr || streamTransfersOutstanding(handle.get())) {
        if (handle != nullptr) {
            setParkedPumpActive(slotIndex, handle.get(), false);
        }
        return;
    }
    try {
        freeTransfers(handle.get());
        {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            finishClosedUsbResources(handle.get());
            markInterfaceTransitionLocked();
        }
        releaseParkedHandle(slotIndex, handle.get(), source);
    } catch (const std::exception& error) {
        setParkedPumpActive(slotIndex, handle.get(), false);
        LOGE("failed to finalize parked USB handle slot=%zu error=%s", slotIndex, error.what());
    } catch (...) {
        setParkedPumpActive(slotIndex, handle.get(), false);
        LOGE("failed to finalize parked USB handle slot=%zu error=unknown", slotIndex);
    }
}

void pumpParkedHandleUntilReclaimed(
    const std::shared_ptr<UsbExclusiveHandle>& handle,
    size_t slotIndex,
    int quarantineIndex
) noexcept {
    const auto lowFrequencyAt = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kQuarantineTotalTimeoutMs);
    auto nextStatusLogAt = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(kQuarantineDrainLogIntervalMs);
    bool lowFrequencyAnnounced = false;
    int consecutiveErrors = 0;

    while (streamTransfersOutstanding(handle.get())) {
        const auto now = std::chrono::steady_clock::now();
        const bool lowFrequency = now >= lowFrequencyAt;
        const int waitTimeoutMs = lowFrequency
            ? kParkedEventWaitTimeoutMs
            : kDrainEventWaitTimeoutMs;
        timeval timeout = timeoutFromMilliseconds(waitTimeoutMs);
        const int rc = handle->ctx != nullptr
            ? libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr)
            : LIBUSB_ERROR_INVALID_PARAM;
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            consecutiveErrors += 1;
            const int backoffMs = parkedExponentialBackoffMs(consecutiveErrors);
            if (shouldLogRepeatedError(consecutiveErrors)) {
                LOGW(
                    "parked USB event pump error: index=%d error=%s consecutive=%d "
                    "backoffMs=%d audioInFlight=%d feedbackInFlight=%d",
                    quarantineIndex,
                    libusbErrName(rc),
                    consecutiveErrors,
                    backoffMs,
                    handle->inFlightTransfers.load(),
                    handle->explicitFeedbackEnabled
                        ? static_cast<int>(
                            handle->feedbackInTransferSet.snapshot().inFlight
                        )
                        : 0
                );
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
        } else {
            consecutiveErrors = 0;
        }

        const auto afterPump = std::chrono::steady_clock::now();
        if (!lowFrequencyAnnounced && afterPump >= lowFrequencyAt) {
            lowFrequencyAnnounced = true;
            markTransportFailed(handle.get());
            try {
                setError(handle.get(), "quarantine_drain_timeout");
            } catch (...) {
            }
            LOGE(
                "USB quarantine switched to low-frequency recovery: index=%d slot=%zu "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                handle->inFlightTransfers.load()
            );
        }
        if (afterPump >= nextStatusLogAt &&
            streamTransfersOutstanding(handle.get())) {
            LOGW(
                "USB quarantine still pumping: index=%d slot=%zu lowFrequency=%d "
                "audioInFlight=%d feedbackInFlight=%d",
                quarantineIndex,
                slotIndex,
                lowFrequencyAnnounced ? 1 : 0,
                handle->inFlightTransfers.load(),
                handle->explicitFeedbackEnabled
                    ? static_cast<int>(
                        handle->feedbackInTransferSet.snapshot().inFlight
                    )
                    : 0
            );
            nextStatusLogAt = afterPump +
                std::chrono::milliseconds(kQuarantineDrainLogIntervalMs);
        }
    }

    finishParkedHandle(handle, slotIndex, "quarantine_thread");
}

void serviceParkedHandlesOnce() noexcept {
    for (size_t slotIndex = 0; slotIndex < g_parkedHandles.size(); ++slotIndex) {
        std::shared_ptr<UsbExclusiveHandle> handle;
        int quarantineIndex = 0;
        try {
            std::lock_guard<std::mutex> guard(g_parkedHandlesLock);
            ParkedHandleSlot& slot = g_parkedHandles[slotIndex];
            if (slot.handle == nullptr || slot.pumpActive) {
                continue;
            }
            slot.pumpActive = true;
            handle = slot.handle;
            quarantineIndex = slot.quarantineIndex;
        } catch (...) {
            continue;
        }

        timeval timeout = timeoutFromMilliseconds(0);
        const int rc = handle->ctx != nullptr &&
                streamTransfersOutstanding(handle.get())
            ? libusb_handle_events_timeout_completed(handle->ctx, &timeout, nullptr)
            : LIBUSB_SUCCESS;
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            requestNoDeviceStop(handle.get());
        } else if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGW(
                "nativeOpen parked handle service failed: index=%d slot=%zu error=%s "
                "inFlight=%d",
                quarantineIndex,
                slotIndex,
                libusbErrName(rc),
                handle->inFlightTransfers.load()
            );
        }
        if (!streamTransfersOutstanding(handle.get())) {
            finishParkedHandle(handle, slotIndex, "native_open");
        } else {
            setParkedPumpActive(slotIndex, handle.get(), false);
        }
    }
}

void quarantineCloseHandle(const std::shared_ptr<UsbExclusiveHandle>& handle) noexcept {
    if (handle == nullptr) {
        return;
    }

    const int quarantineIndex = g_nextQuarantineIndex.fetch_add(1);
    g_quarantinedDrainHandles.fetch_add(1);
    const int parkedSlot = parkHandleForRecovery(
        handle,
        quarantineIndex,
        "cancel_drain_timeout"
    );
    if (parkedSlot < 0) {
        hardRetainHandle(handle, quarantineIndex, "parked_registry_full");
        g_quarantinedDrainHandles.fetch_sub(1);
        return;
    }
    try {
        std::thread([handle, quarantineIndex, parkedSlot]() {
            LOGW(
                "USB close quarantined for cancel drain: index=%d slot=%d inFlight=%d",
                quarantineIndex,
                parkedSlot,
                handle->inFlightTransfers.load()
            );
            pumpParkedHandleUntilReclaimed(
                handle,
                static_cast<size_t>(parkedSlot),
                quarantineIndex
            );
        }).detach();
    } catch (const std::system_error& error) {
        setParkedPumpActive(static_cast<size_t>(parkedSlot), handle.get(), false);
        LOGE(
            "USB close quarantine thread failed, registry will retain handle: %s "
            "slot=%d activeQuarantines=%d",
            error.what(),
            parkedSlot,
            g_quarantinedDrainHandles.load()
        );
    } catch (...) {
        setParkedPumpActive(static_cast<size_t>(parkedSlot), handle.get(), false);
        LOGE(
            "USB close quarantine thread failed, registry will retain handle: unknown "
            "slot=%d activeQuarantines=%d",
            parkedSlot,
            g_quarantinedDrainHandles.load()
        );
    }
}

bool closeHandleInternal(const std::shared_ptr<UsbExclusiveHandle>& handle) {
    if (handle == nullptr) {
        return true;
    }
    bool expected = false;
    if (!handle->closing.compare_exchange_strong(expected, true)) {
        LOGW("closeHandleInternal ignored duplicate close");
        return true;
    }
    LOGI(
        "closeHandleInternal begin: iface=%d alt=%d claimed=%zu running=%d source=%s",
        handle->audioStreamingInterface,
        handle->alternateSetting,
        handle->claimedAudioInterfaces.size(),
        handle->running.load() ? 1 : 0,
        sourceName(handle->streamSource.load())
    );
    if (!stopStreamingInternal(handle.get())) {
        LOGE(
            "closeHandleInternal quarantines active USB transfers: inFlight=%d",
            handle->inFlightTransfers.load()
        );
        quarantineCloseHandle(handle);
        return false;
    }

    finishClosedUsbResources(handle.get());
    return true;
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeOpen(
    JNIEnv* env,
    jclass /*clazz*/,
    jint fd,
    jint sampleRate,
    jint channelCount,
    jint bitsPerSample,
    jint subslotBytes
) {
    static_cast<void>(env);
    serviceParkedHandlesOnce();
    LOGI(
        "nativeOpen request: fd=%d sampleRate=%d channels=%d bits=%d subslot=%d",
        fd,
        sampleRate,
        channelCount,
        bitsPerSample,
        subslotBytes
    );
    if (fd < 0) {
        LOGE("nativeOpen rejected invalid fd=%d", fd);
        rememberLastOpenError("invalid_fd");
        return 0L;
    }

    std::shared_ptr<UsbExclusiveHandle> handle;
    try {
        handle = std::make_shared<UsbExclusiveHandle>();
        assignNewNativeStreamGeneration(handle.get());
        handle->sampleRate = sampleRate > 0 ? sampleRate : 48000;
        handle->channelCount = channelCount > 0 ? channelCount : 2;
        handle->bitsPerSample = bitsPerSample > 0 ? bitsPerSample : 16;
        handle->subslotBytes = subslotBytes > 0 ? subslotBytes : 2;
        if (handle->sampleRate < 8000 || handle->sampleRate > 768000 ||
            handle->channelCount < 1 || handle->channelCount > 8 ||
            handle->bitsPerSample < 8 || handle->bitsPerSample > 32 ||
            handle->subslotBytes < 1 || handle->subslotBytes > 4 ||
            handle->bitsPerSample > handle->subslotBytes * 8) {
            LOGE(
                "nativeOpen rejected invalid output format: sr=%d ch=%d bits=%d subslot=%d",
                handle->sampleRate,
                handle->channelCount,
                handle->bitsPerSample,
                handle->subslotBytes
            );
            rememberLastOpenError("invalid_output_format");
            return 0L;
        }
        std::unique_lock<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
        while (true) {
            const int remainingCooldownMs = remainingInterfaceTransitionCooldownMsLocked();
            if (remainingCooldownMs <= 0) {
                break;
            }
            LOGI("nativeOpen waits for USB interface cooldown: %dms", remainingCooldownMs);
            transitionGuard.unlock();
            std::this_thread::sleep_for(std::chrono::milliseconds(remainingCooldownMs));
            transitionGuard.lock();
        }
        handle->frameBytes = handle->channelCount * handle->subslotBytes;

        handle->dupFd = dup(fd);
        if (handle->dupFd < 0) {
            rememberLastOpenError("dup_failed");
            return 0L;
        }

        int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("set_option_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }

        rc = libusb_init(&handle->ctx);
        if (rc != LIBUSB_SUCCESS) {
            const std::string error = std::string("libusb_init_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        libusb_set_option(handle->ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

        rc = libusb_wrap_sys_device(handle->ctx, static_cast<intptr_t>(handle->dupFd), &handle->devh);
        if (rc != LIBUSB_SUCCESS || handle->devh == nullptr) {
            if (rc == LIBUSB_ERROR_NO_DEVICE) {
                requestNoDeviceStop(handle.get());
            }
            LOGE("nativeOpen wrap_sys_device failed: fd=%d rc=%d err=%s", handle->dupFd, rc, libusbErrName(rc));
            const std::string error = std::string("wrap_sys_device_failed:") + libusbErrName(rc);
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        libusb_device* wrappedDevice = libusb_get_device(handle->devh);
        if (wrappedDevice != nullptr) {
            libusb_device_descriptor descriptor {};
            if (libusb_get_device_descriptor(wrappedDevice, &descriptor) == LIBUSB_SUCCESS) {
                handle->vendorId = descriptor.idVendor;
                handle->productId = descriptor.idProduct;
                handle->deviceRelease = descriptor.bcdDevice;
            }
            handle->busNumber = libusb_get_bus_number(wrappedDevice);
            handle->deviceAddress = libusb_get_device_address(wrappedDevice);
        }

#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
        const int autoDetachRc = libusb_set_auto_detach_kernel_driver(handle->devh, 1);
        LOGI("nativeOpen set_auto_detach_kernel_driver rc=%d", autoDetachRc);
        if (autoDetachRc == LIBUSB_ERROR_NO_DEVICE) {
            requestNoDeviceStop(handle.get());
            rememberLastOpenError("auto_detach_failed:LIBUSB_ERROR_NO_DEVICE");
            closeHandleInternal(handle);
            return 0L;
        }
#endif

        handle->usbSpeed = libusb_get_device_speed(libusb_get_device(handle->devh));
        StreamingAltSelection selection;
        std::string selectionFailure;
        if (!findStreamingAlt(
                handle->devh,
                handle->sampleRate,
                handle->channelCount,
                handle->bitsPerSample,
                handle->subslotBytes,
                handle->usbSpeed,
                &selection,
                &selectionFailure
            )) {
            if (selectionFailure.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "no_compatible_usb_audio_format:" + selectionFailure;
            LOGE("nativeOpen compatible USB audio alt not found: %s", selectionFailure.c_str());
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        handle->audioStreamingInterface = selection.interfaceNumber;
        handle->audioControlInterface = selection.audioControlInterface;
        handle->alternateSetting = selection.alternateSetting;
        handle->outEndpoint = selection.outEndpoint;
        handle->endpointMaxPacketBytes = selection.endpointMaxPacketBytes;
        handle->endpointInterval = selection.endpointInterval;
        handle->explicitFeedbackEnabled = selection.explicitFeedbackEnabled;
        handle->feedbackEndpoint = selection.feedbackEndpoint;
        handle->feedbackEndpointMaxPacketBytes =
            selection.feedbackEndpointMaxPacketBytes;
        handle->feedbackEndpointInterval = selection.feedbackEndpointInterval;
        handle->feedbackTimingProfile = selection.feedbackTimingProfile;
        handle->uacVersion = selection.uacVersion;
        handle->subslotBytes = selection.uacVersion == 1
            ? selection.uac1.format.subslotBytes
            : selection.uac2.format.subslotBytes;
        handle->frameBytes = handle->channelCount * handle->subslotBytes;
        handle->uacClockSourceId = selection.uac2.clockSourceId;
        handle->descriptorSampleRates = selection.uacVersion == 1
            ? selection.uac1.format.sampleRateSummary()
            : "uac2_clock_source";
        handle->formatSelectionReason = selection.reason;
        handle->endpointSyncType = selection.syncType;
        handle->endpointFeedback = selection.feedback;
        handle->completeAudioFunctionClaim = selection.completeClaimPlan;

        std::string claimFailure;
        if (!claimAudioFunction(handle.get(), selection.claimPlan, &claimFailure)) {
            LOGE("nativeOpen claim UAC function failed: %s", claimFailure.c_str());
            const std::string error = claimFailure.empty()
                ? "claim_audio_function_failed"
                : claimFailure;
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }

        if (selection.uacVersion == 1) {
            rc = libusb_set_interface_alt_setting(
                handle->devh,
                handle->audioStreamingInterface,
                handle->alternateSetting
            );
            if (rc != LIBUSB_SUCCESS) {
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle.get());
                }
                LOGE(
                    "nativeOpen set alt failed: iface=%d alt=%d err=%s",
                    handle->audioStreamingInterface,
                    handle->alternateSetting,
                    libusbErrName(rc)
                );
                const std::string error = std::string("set_alt_failed:") + libusbErrName(rc);
                rememberLastOpenError(error);
                setError(handle.get(), error);
                closeHandleInternal(handle);
                markInterfaceTransitionLocked();
                return 0L;
            }
            markInterfaceTransitionLocked();
        }

        std::string negotiationError;
        const bool sampleRateNegotiated = selection.uacVersion == 1
            ? negotiateUac1SampleRate(
                handle->devh,
                handle->outEndpoint,
                handle->sampleRate,
                selection.uac1.format,
                selection.uac1.endpointControls,
                &handle->negotiatedSampleRate,
                &handle->sampleRateControlStatus,
                &negotiationError
            )
            : negotiateUac2SampleRate(
                handle->devh,
                handle->audioControlInterface,
                handle->uacClockSourceId,
                handle->sampleRate,
                selection.uac2.sampleRateControl,
                &handle->negotiatedSampleRate,
                &handle->sampleRateControlStatus,
                &negotiationError
            );
        if (!sampleRateNegotiated) {
            if (negotiationError.find("LIBUSB_ERROR_NO_DEVICE") != std::string::npos) {
                requestNoDeviceStop(handle.get());
            }
            const std::string error = "sample_rate_negotiation_failed:" + negotiationError;
            LOGE(
                "nativeOpen UAC%d sample rate negotiation failed: %s",
                selection.uacVersion,
                negotiationError.c_str()
            );
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }

        if (selection.uacVersion == 2) {
            rc = libusb_set_interface_alt_setting(
                handle->devh,
                handle->audioStreamingInterface,
                handle->alternateSetting
            );
            if (rc != LIBUSB_SUCCESS) {
                if (rc == LIBUSB_ERROR_NO_DEVICE) {
                    requestNoDeviceStop(handle.get());
                }
                LOGE(
                    "nativeOpen set alt failed after UAC2 clock configure: iface=%d alt=%d err=%s",
                    handle->audioStreamingInterface,
                    handle->alternateSetting,
                    libusbErrName(rc)
                );
                const std::string error = std::string("set_alt_failed:") + libusbErrName(rc);
                rememberLastOpenError(error);
                setError(handle.get(), error);
                closeHandleInternal(handle);
                markInterfaceTransitionLocked();
                return 0L;
            }
            markInterfaceTransitionLocked();
        }

        const int frameBytes = std::max(1, handle->frameBytes);
        handle->intervalsPerSecond = computeIntervalsPerSecond(
            handle->usbSpeed,
            handle->endpointInterval
        );
        handle->packetsPerTransfer = handle->explicitFeedbackEnabled
            ? kExplicitFeedbackPacketsPerTransfer
            : scaledPacketsPerTransfer(handle->intervalsPerSecond);
        handle->transferCount = handle->explicitFeedbackEnabled
            ? kExplicitFeedbackAudioTransferCount
            : scaledTransferCount(
                handle->intervalsPerSecond,
                handle->packetsPerTransfer
            );
        handle->bytesPerUsbFrame = computeMaxPacketBytes(
            handle->sampleRate,
            handle->intervalsPerSecond,
            frameBytes,
            handle->endpointMaxPacketBytes
        );
        if (handle->bytesPerUsbFrame <= 0) {
            const std::string error = "endpoint_capacity_too_small";
            rememberLastOpenError(error);
            setError(handle.get(), error);
            closeHandleInternal(handle);
            return 0L;
        }
        handle->transferBytes = (handle->explicitFeedbackEnabled
            ? handle->endpointMaxPacketBytes
            : handle->bytesPerUsbFrame) * handle->packetsPerTransfer;
        handle->packetScheduler.configure(
            handle->sampleRate,
            handle->intervalsPerSecond,
            handle->frameBytes
        );
        rememberLastOpenError("none");
        clearError(handle.get());

        LOGI(
            "nativeOpen ok: uac=%d iface=%d acIface=%d alt=%d claimed=%zu fullFunctionClaim=%d "
            "outEp=0x%02X packetBytes=%d endpointMax=%d speed=%d interval=%d ips=%d "
            "sr=%d negotiated=%d ch=%d bits=%d subslot=%d rates=%s control=%s "
            "clock=%d sync=%s feedback=%s feedbackEp=0x%02X feedbackPacket=%d "
            "feedbackInterval=%d",
            handle->uacVersion,
            handle->audioStreamingInterface,
            handle->audioControlInterface,
            handle->alternateSetting,
            handle->claimedAudioInterfaces.size(),
            handle->completeAudioFunctionClaim ? 1 : 0,
            handle->outEndpoint,
            handle->bytesPerUsbFrame,
            handle->endpointMaxPacketBytes,
            handle->usbSpeed,
            handle->endpointInterval,
            handle->intervalsPerSecond,
            handle->sampleRate,
            handle->negotiatedSampleRate,
            handle->channelCount,
            handle->bitsPerSample,
            handle->subslotBytes,
            handle->descriptorSampleRates.c_str(),
            handle->sampleRateControlStatus.c_str(),
            handle->uacClockSourceId,
            handle->endpointSyncType.c_str(),
            handle->endpointFeedback.c_str(),
            handle->feedbackEndpoint,
            handle->feedbackEndpointMaxPacketBytes,
            handle->feedbackEndpointInterval
        );
        return registerHandle(handle);
    } catch (const std::bad_alloc&) {
        try {
            rememberLastOpenError("native_open_allocation_failed");
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle);
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen failed because memory allocation was rejected");
        return 0L;
    } catch (const std::exception& error) {
        try {
            rememberLastOpenError(std::string("native_open_exception:") + error.what());
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle);
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen exception: %s", error.what());
        return 0L;
    } catch (...) {
        try {
            rememberLastOpenError("native_open_unknown_exception");
        } catch (...) {
        }
        if (handle != nullptr) {
            std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
            closeHandleInternal(handle);
            markInterfaceTransitionLocked();
        }
        LOGE("nativeOpen unknown exception");
        return 0L;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStartGeneratedTone(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        return JNI_FALSE;
    }
    holder->playbackEnabled.store(false);
    holder->playerPaused.store(false);
    return startStreamingSafely(holder.get(), StreamSource::Tone) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeConfigurePlayerBufferDuration(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint durationMs
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    const int rawDurationMs = static_cast<int>(durationMs);
    const int requestedDurationMs = std::clamp(
        rawDurationMs,
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
    if (requestedDurationMs != rawDurationMs) {
        LOGW(
            "nativeConfigurePlayerBufferDuration clamped: requested=%d applied=%d",
            rawDurationMs,
            requestedDurationMs
        );
    }
    if (holder->streamSource.load() == StreamSource::PlayerPcm) {
        std::string resizeError;
        if (!holder->pcmPipeline.resizeRingDuration(
                requestedDurationMs,
                holder->transferBytes,
                holder->transferCount,
                &resizeError
            )) {
            setError(holder.get(), resizeError);
            LOGW(
                "nativeConfigurePlayerBufferDuration resize failed: handle=%lld error=%s",
                static_cast<long long>(handleValue),
                resizeError.c_str()
            );
            return JNI_FALSE;
        }
    }
    holder->pcmRingDurationMs = requestedDurationMs;
    LOGI(
        "nativeConfigurePlayerBufferDuration: handle=%lld requested=%d applied=%d",
        static_cast<long long>(handleValue),
        durationMs,
        holder->pcmRingDurationMs
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePreparePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint inputSampleRate,
    jint inputChannelCount,
    jint inputEncoding
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    LOGI(
        "nativePreparePlayerPcm request: handle=%lld inputSr=%d inputCh=%d inputEncoding=%d "
        "outputSr=%d outputCh=%d bits=%d bufferMs=%d running=%d",
        static_cast<long long>(handleValue),
        inputSampleRate,
        inputChannelCount,
        inputEncoding,
        holder->sampleRate,
        holder->channelCount,
        holder->bitsPerSample,
        holder->pcmRingDurationMs,
        holder->running.load() ? 1 : 0
    );
    if (holder->running.load()) {
        if (!stopStreamingInternal(holder.get())) {
            return JNI_FALSE;
        }
    }
    const int bytesPerSample = neri::usb::bytesPerSampleForEncoding(inputEncoding);
    if (bytesPerSample <= 0 || inputSampleRate < 8000 || inputSampleRate > 768000 ||
        inputChannelCount < 1 || inputChannelCount > 8) {
        setError(holder.get(), "unsupported_player_pcm_format");
        LOGE(
            "nativePreparePlayerPcm unsupported input: sr=%d ch=%d encoding=%d",
            inputSampleRate,
            inputChannelCount,
            inputEncoding
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineConfig config {
        {
            holder->sampleRate,
            holder->channelCount,
            holder->subslotBytes,
            holder->bitsPerSample,
            holder->frameBytes
        },
        {
            inputSampleRate > 0 ? inputSampleRate : holder->sampleRate,
            inputChannelCount > 0 ? inputChannelCount : holder->channelCount,
            inputEncoding
        },
        holder->pcmRingDurationMs,
        holder->transferBytes,
        holder->transferCount
    };
    std::string pipelineError;
    if (!holder->pcmPipeline.configure(config, &pipelineError)) {
        setError(holder.get(), pipelineError);
        LOGE("nativePreparePlayerPcm pipeline configure failed: %s", pipelineError.c_str());
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->streamSource.store(StreamSource::PlayerPcm);
    holder->playbackEnabled.store(false);
    holder->playerPaused.store(false);
    holder->stagedPlayerFrames.store(0);
    holder->completedAudioFrames.store(0);
    clearError(holder.get());
    LOGI(
        "nativePreparePlayerPcm ok: handle=%lld ringMs=%d transferBytes=%d transferCount=%d",
        static_cast<long long>(handleValue),
        holder->pcmRingDurationMs,
        holder->transferBytes,
        holder->transferCount
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeWritePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jobject buffer,
    jint offset,
    jint size,
    jfloat volume
) {
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || buffer == nullptr || size <= 0 || offset < 0) {
        LOGW(
            "nativeWritePlayerPcm rejected: handle=%lld buffer=%d offset=%d size=%d",
            static_cast<long long>(handleValue),
            buffer != nullptr ? 1 : 0,
            offset,
            size
        );
        return 0;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || !holder->deviceOnline.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeWritePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return 0;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0 || static_cast<jlong>(offset) + size > capacity) {
        setError(holder.get(), "invalid_direct_pcm_buffer");
        LOGE(
            "nativeWritePlayerPcm invalid direct buffer: handle=%lld capacity=%lld offset=%d size=%d",
            static_cast<long long>(handleValue),
            static_cast<long long>(capacity),
            offset,
            size
        );
        return 0;
    }
    const float requestedVolume = std::clamp(volume, 0.0f, 1.0f);
    holder->playerVolume.store(requestedVolume);
    holder->pcmPipeline.setTargetGain(holder->focusMuted.load() ? 0.0f : requestedVolume);
    std::string pipelineError;
    size_t written = 0;
    try {
        written = holder->pcmPipeline.write(
            data + offset,
            static_cast<size_t>(size),
            &pipelineError
        );
    } catch (const std::bad_alloc&) {
        pipelineError = "pcm_write_allocation_failed";
    } catch (const std::exception& error) {
        pipelineError = std::string("pcm_write_failed:") + error.what();
    }
    if (!pipelineError.empty()) {
        setError(holder.get(), pipelineError);
        LOGW("nativeWritePlayerPcm pipeline warning: %s", pipelineError.c_str());
    }
    if (written == 0 || written < static_cast<size_t>(size)) {
        const int warningIndex = holder->shortWriteWarnings.fetch_add(1);
        if (warningIndex < 8) {
            const neri::usb::PcmPipelineSnapshot pcm = holder->pcmPipeline.snapshot();
            LOGW(
                "nativeWritePlayerPcm short write: handle=%lld requested=%d written=%zu "
                "level=%zu/%zu free=%zu backpressureEvents=%lld backpressureCurrentMs=%lld "
                "running=%d playback=%d input=%lld output=%lld dropped=%lld underrun=%lld",
                static_cast<long long>(handleValue),
                size,
                written,
                pcm.levelBytes,
                pcm.capacityBytes,
                pcm.freeBytes,
                static_cast<long long>(pcm.backpressureEvents),
                static_cast<long long>(pcm.backpressureCurrentUs / 1000),
                holder->running.load() ? 1 : 0,
                holder->playbackEnabled.load() ? 1 : 0,
                static_cast<long long>(pcm.inputBytes),
                static_cast<long long>(pcm.outputBytes),
                static_cast<long long>(pcm.droppedBytes),
                static_cast<long long>(pcm.underrunBytes)
            );
        }
    }
    return static_cast<jint>(written);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeReconfigurePlayerPcmOutput(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint sampleRate,
    jint channelCount,
    jint bitsPerSample,
    jint subslotBytes
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW(
            "nativeReconfigurePlayerPcmOutput rejected: invalid handle=%lld",
            static_cast<long long>(handleValue)
        );
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    std::string error;
    if (!reconfigureOpenedPlayerPcmOutput(
            holder.get(),
            sampleRate,
            channelCount,
            bitsPerSample,
            subslotBytes,
            &error
        )) {
        if (!error.empty()) {
            setError(holder.get(), error);
            LOGW(
                "nativeReconfigurePlayerPcmOutput failed: handle=%lld error=%s",
                static_cast<long long>(handleValue),
                error.c_str()
            );
        }
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePlayPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || !holder->deviceOnline.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePlayPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    LOGI(
        "nativePlayPlayerPcm request: handle=%lld running=%d queued=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->completedAudioFrames.load())
    );
    holder->playerPaused.store(false);
    holder->playbackEnabled.store(true);
    if (startStreamingSafely(holder.get(), StreamSource::PlayerPcm)) {
        LOGI("nativePlayPlayerPcm ok: handle=%lld", static_cast<long long>(handleValue));
        return JNI_TRUE;
    }
    holder->playbackEnabled.store(false);
    LOGE(
        "nativePlayPlayerPcm failed: handle=%lld error=%s",
        static_cast<long long>(handleValue),
        getErrorCopy(holder.get()).c_str()
    );
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStartPlayerPcm(
    JNIEnv* env,
    jclass clazz,
    jlong handleValue
) {
    return Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
        env,
        clazz,
        handleValue
    );
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePausePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePausePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePausePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    holder->playbackEnabled.store(false);
    holder->playerReplayFailed.store(false);
    holder->preserveCancelledPlayerFrames.store(true);
    const bool stopped = stopStreamingInternal(holder.get());
    holder->preserveCancelledPlayerFrames.store(false);
    const bool replayPreserved = !holder->playerReplayFailed.load();
    const bool paused = stopped && replayPreserved;
    holder->playerPaused.store(paused);
    if (!replayPreserved) {
        setError(holder.get(), "pause_replay_preservation_failed");
    }
    LOGI(
        "nativePausePlayerPcm %s: handle=%lld running=%d level=%zu/%zu queued=%lld replay=%lld",
        paused ? "ok" : "failed",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->pcmPipeline.queuedFrames()),
        static_cast<long long>(queuedPlayerReplayFrames(holder.get()))
    );
    return paused ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeFlushPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeFlushPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->closing.load() || holder->devh == nullptr ||
        holder->streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeFlushPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->closing.load() ? 1 : 0,
            holder->devh != nullptr ? 1 : 0,
            sourceName(holder->streamSource.load())
        );
        return JNI_FALSE;
    }

    const bool transportWasRunning = holder->running.load();
    holder->playbackEnabled.store(false);
    holder->playerPaused.store(false);
    const neri::usb::PcmPipelineSnapshot before = holder->pcmPipeline.snapshot();
    LOGI(
        "nativeFlushPlayerPcm begin: handle=%lld restart=%d resume=%d level=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        transportWasRunning ? 1 : 0,
        0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->completedAudioFrames.load())
    );
    if (!stopStreamingInternal(holder.get())) {
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->pcmPipeline.clear();
    holder->pcmPipeline.resetCounters();
    holder->stagedPlayerFrames.store(0);
    holder->completedAudioFrames.store(0);

    clearError(holder.get());
    LOGI(
        "nativeFlushPlayerPcm done: handle=%lld running=%d playback=%d",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        holder->playbackEnabled.load() ? 1 : 0
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jfloat volume
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load() || !holder->deviceOnline.load()) {
        return JNI_FALSE;
    }
    const float requestedVolume = std::clamp(static_cast<float>(volume), 0.0f, 1.0f);
    holder->playerVolume.store(requestedVolume);
    holder->pcmPipeline.setTargetGain(holder->focusMuted.load() ? 0.0f : requestedVolume);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerFocusMuted(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jboolean muted
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load() || !holder->deviceOnline.load()) {
        return JNI_FALSE;
    }
    const bool shouldMute = muted == JNI_TRUE;
    holder->focusMuted.store(shouldMute);
    holder->pcmPipeline.setTargetGain(
        shouldMute ? 0.0f : std::clamp(holder->playerVolume.load(), 0.0f, 1.0f)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetCompletedAudioFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    return holder != nullptr ? static_cast<jlong>(holder->completedAudioFrames.load()) : 0L;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetQueuedPlayerFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return 0L;
    }
    return static_cast<jlong>(holder->pcmPipeline.queuedFrames()) +
        holder->stagedPlayerFrames.load() +
        queuedPlayerReplayFrames(holder.get());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetPlayerPcmFreeBytes(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->closing.load()) {
        return -1L;
    }
    return static_cast<jlong>(holder->pcmPipeline.snapshot().freeBytes);
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStop(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeStop ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    LOGI(
        "nativeStop: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        sourceName(holder->streamSource.load())
    );
    requestDeviceStop(holder.get(), false);
    std::unique_lock<std::mutex> apiGuard(holder->apiLock, std::try_to_lock);
    if (apiGuard.owns_lock()) {
        interruptUsbEventHandler(holder.get());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeMarkDeviceDetached(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return;
    }
    LOGW("nativeMarkDeviceDetached: handle=%lld", static_cast<long long>(handleValue));
    requestDeviceStop(holder.get(), true);
    std::unique_lock<std::mutex> apiGuard(holder->apiLock, std::try_to_lock);
    if (apiGuard.owns_lock()) {
        interruptUsbEventHandler(holder.get());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeClose(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = takeHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeClose ignored invalid handle=%lld", static_cast<long long>(handleValue));
        return;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    std::lock_guard<std::mutex> transitionGuard(g_usbInterfaceTransitionLock);
    requestDeviceStop(holder.get(), holder->detachBroadcastConfirmed.load());
    interruptUsbEventHandler(holder.get());
    LOGI(
        "nativeClose: handle=%lld running=%d source=%s",
        static_cast<long long>(handleValue),
        holder->running.load() ? 1 : 0,
        sourceName(holder->streamSource.load())
    );
    holder->playbackEnabled.store(false);
    holder->playerPaused.store(false);
    const bool closedNow = closeHandleInternal(holder);
    if (!closedNow) {
        LOGW("nativeClose returned with USB resources quarantined");
    }
    markInterfaceTransitionLocked();
}

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
        const neri::usb::PcmPipelineSnapshot pcm = holder->pcmPipeline.snapshot();
        const bool explicitFeedback = holder->explicitFeedbackEnabled;
        const neri::usb::feedback::FeedbackInTransferSetSnapshot feedbackTransfers =
            holder->feedbackInTransferSet.snapshot();
        const neri::usb::feedback::ExplicitFeedbackRuntimeSnapshot feedbackRuntime =
            holder->feedbackRuntime.snapshot();
        const bool feedbackTerminalFailure = explicitFeedback &&
            feedbackRuntime.terminalFailure;
        const bool terminalFailure = holder->transportFailed.load() ||
            !holder->deviceOnline.load() || feedbackTerminalFailure;
        if (terminalFailure) {
            if (feedbackTerminalFailure && !holder->transportFailed.load()) {
                markTransportFailed(holder.get());
            }
            latchTerminalRecoveryAction(
                holder.get(),
                holder->deviceOnline.load()
                    ? neri::usb::UsbRuntimeRecoveryAction::FreshOpen
                    : neri::usb::UsbRuntimeRecoveryAction::StopPreserveIntent
            );
        }
        const neri::usb::UsbRecoveryActionSnapshot recovery =
            holder->recoveryActionLatch.snapshot();
        const bool playerPcmSource =
            holder->streamSource.load() == StreamSource::PlayerPcm;
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
            holder->deviceOnline.load() &&
            !holder->closing.load() &&
            !terminalFailure &&
            !recovery.latched &&
            feedbackReady;
        const bool transportRunning = holder->running.load();
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
                : holder->feedbackNominalRateQ32;
        const uint64_t feedbackPeriodNs =
            holder->feedbackTimingProfile.feedbackExpectedPeriodNanoseconds;
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
            ? static_cast<int>(holder->feedbackEndpoint)
            : 0;
        v2Snapshot.feedbackState = runtimeFeedbackState(
            explicitFeedback,
            feedbackRuntime
        );
        v2Snapshot.feedbackPayloadBytes = explicitFeedback
            ? static_cast<int>(
                holder->feedbackTimingProfile.decodeProfile.payloadBytesExpected
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
            ? feedbackRateHz(trustedFeedbackRateQ32)
            : 0.0;
        v2Snapshot.feedbackRatePpm = explicitFeedback
            ? signedFeedbackRatePpm(
                trustedFeedbackRateQ32,
                holder->feedbackNominalRateQ32
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
        v2Snapshot.nativeStreamGeneration = holder->nativeStreamGeneration;
        v2Snapshot.candidateId = runtimeCandidateId(holder.get());
        v2Snapshot.recoveryEpoch = holder->recoveryEpoch;
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
        const int64_t stagedPlayerFrames = holder->stagedPlayerFrames.load();
        const int64_t replayPlayerFrames = queuedPlayerReplayFrames(holder.get());
        const int64_t queuedFrames = static_cast<int64_t>(
            pcm.levelBytes / static_cast<size_t>(std::max(1, holder->frameBytes))
        ) + stagedPlayerFrames + replayPlayerFrames;
        const int64_t fifoMs = holder->sampleRate > 0 && holder->frameBytes > 0
            ? static_cast<int64_t>(pcm.levelBytes) * 1000 /
                (static_cast<int64_t>(holder->sampleRate) * holder->frameBytes)
            : 0;
        const int64_t bufferMs = holder->sampleRate > 0 && holder->frameBytes > 0
            ? static_cast<int64_t>(pcm.capacityBytes) * 1000 /
                (static_cast<int64_t>(holder->sampleRate) * holder->frameBytes)
            : 0;
        const int minimumPacketFrames = holder->packetFramesMin.load() == std::numeric_limits<int>::max()
            ? 0
            : holder->packetFramesMin.load();
        std::string claimedInterfaceSummary;
        for (const ClaimedUsbInterface& entry : holder->claimedAudioInterfaces) {
            if (!claimedInterfaceSummary.empty()) {
                claimedInterfaceSummary += ",";
            }
            claimedInterfaceSummary += std::to_string(entry.interfaceNumber);
        }
        if (claimedInterfaceSummary.empty()) {
            claimedInterfaceSummary = "none";
        }
        const std::string report =
            v2Fields +
            " iface=" + std::to_string(holder->audioStreamingInterface) +
            " acIface=" + std::to_string(holder->audioControlInterface) +
            " alt=" + std::to_string(holder->alternateSetting) +
            " claimedIfaces=" + claimedInterfaceSummary +
            " fullFunctionClaim=" + std::string(
                holder->completeAudioFunctionClaim ? "true" : "false"
            ) +
            " outEp=0x" + [&]() {
                char buf[8];
                snprintf(buf, sizeof(buf), "%02X", holder->outEndpoint);
                return std::string(buf);
            }() +
            " source=" + std::string(sourceName(holder->streamSource.load())) +
            " uacVersion=" + std::string(
                holder->uacVersion == 1
                    ? "1.0"
                    : holder->uacVersion == 2 ? "2.0" : "unsupported"
            ) +
            " clockEntity=" + std::to_string(holder->uacClockSourceId) +
            " sampleRate=" + std::to_string(holder->sampleRate) +
            " negotiatedRate=" + std::to_string(holder->negotiatedSampleRate) +
            " descriptorRates=" + holder->descriptorSampleRates +
            " rateControl=" + holder->sampleRateControlStatus +
            " channels=" + std::to_string(holder->channelCount) +
            " bits=" + std::to_string(holder->bitsPerSample) +
            " subslotBytes=" + std::to_string(holder->subslotBytes) +
            " formatSelection=" + holder->formatSelectionReason +
            " syncType=" + holder->endpointSyncType +
            " feedback=" + holder->endpointFeedback +
            " usbSpeed=" + std::to_string(holder->usbSpeed) +
            " packetBytes=" + std::to_string(holder->bytesPerUsbFrame) +
            " packetFrames=" + std::to_string(minimumPacketFrames) + ".." +
                std::to_string(holder->packetFramesMax.load()) +
            " endpointMaxPacketBytes=" + std::to_string(holder->endpointMaxPacketBytes) +
            " interval=" + std::to_string(holder->endpointInterval) +
            " intervalsPerSecond=" + std::to_string(holder->intervalsPerSecond) +
            " transferBytes=" + std::to_string(holder->transferBytes) +
            " lastTransferBytes=" + std::to_string(holder->lastTransferBytes.load()) +
            " deviceOnline=" + std::string(holder->deviceOnline.load() ? "true" : "false") +
            " noDeviceObserved=" + std::string(
                holder->noDeviceObserved.load() ? "true" : "false"
            ) +
            " detachConfirmed=" + std::string(
                holder->detachBroadcastConfirmed.load() ? "true" : "false"
            ) +
            " focusMuted=" + std::string(holder->focusMuted.load() ? "true" : "false") +
            " running=" + std::string(holder->running.load() ? "true" : "false") +
            " paused=" + std::string(holder->playerPaused.load() ? "true" : "false") +
            " transportFailed=" + std::string(holder->transportFailed.load() ? "true" : "false") +
            " inFlight=" + std::to_string(holder->inFlightTransfers.load()) +
            " completedTransfers=" + std::to_string(holder->completedTransfers.load()) +
            " submitErrors=" + std::to_string(holder->submitErrors.load()) +
            " isoPacketErrors=" + std::to_string(holder->isoPacketErrors.load()) +
            " isoPacketErrorTransfers=" +
                std::to_string(holder->isoPacketErrorTransfers.load()) +
            " isoPacketErrorScore=" + std::to_string(holder->isoPacketErrorScore.load()) +
            " scheduledPackets=" + std::to_string(holder->scheduledPackets.load()) +
            " scheduledFrames=" + std::to_string(holder->scheduledFrames.load()) +
            " pcmLevel=" + std::to_string(pcm.levelBytes) + "/" +
                std::to_string(pcm.capacityBytes) +
            " pcmFreeBytes=" + std::to_string(pcm.freeBytes) +
            " pcmMaxLevelBytes=" + std::to_string(pcm.maxLevelBytes) +
            " pcmBackpressureEvents=" + std::to_string(pcm.backpressureEvents) +
            " pcmBackpressureTotalMs=" + std::to_string(pcm.backpressureTotalUs / 1000) +
            " pcmBackpressureCurrentMs=" + std::to_string(pcm.backpressureCurrentUs / 1000) +
            " pcmBackpressureMaxMs=" + std::to_string(pcm.backpressureMaxUs / 1000) +
            " bufferMs=" + std::to_string(bufferMs) +
            " requestedBufferMs=" + std::to_string(holder->pcmRingDurationMs) +
            " fifoMs=" + std::to_string(fifoMs) +
            " queuedFrames=" + std::to_string(queuedFrames) +
            " stagedFrames=" + std::to_string(stagedPlayerFrames) +
            " replayFrames=" + std::to_string(replayPlayerFrames) +
            " startupPrerollFrames=" +
                std::to_string(holder->playerStartupPreroll.framesRemaining()) +
            " completedAudioFrames=" + std::to_string(holder->completedAudioFrames.load()) +
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

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeAcknowledgeRecoveryAction(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jlong actionGeneration,
    jlong actionId
) {
    try {
        const auto holder = acquireHandle(handleValue);
        const neri::usb::UsbRecoveryActionAckStatus status = acknowledgeRecoveryAction(
            holder.get(),
            static_cast<int64_t>(actionGeneration),
            static_cast<int64_t>(actionId)
        );
        if (holder == nullptr) {
            LOGW(
                "nativeAcknowledgeRecoveryAction ignored invalid handle=%lld status=%s",
                static_cast<long long>(handleValue),
                neri::usb::usbRecoveryActionAckStatusName(status)
            );
        } else {
            LOGI(
                "nativeAcknowledgeRecoveryAction: handle=%lld generation=%lld "
                "actionId=%lld status=%s",
                static_cast<long long>(handleValue),
                static_cast<long long>(actionGeneration),
                static_cast<long long>(actionId),
                neri::usb::usbRecoveryActionAckStatusName(status)
            );
        }
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(status));
    } catch (const std::exception& error) {
        LOGE("nativeAcknowledgeRecoveryAction exception: %s", error.what());
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(
            neri::usb::UsbRecoveryActionAckStatus::NoPending
        ));
    } catch (...) {
        LOGE("nativeAcknowledgeRecoveryAction unknown exception");
        return env->NewStringUTF(neri::usb::usbRecoveryActionAckStatusName(
            neri::usb::UsbRecoveryActionAckStatus::NoPending
        ));
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeLastOpenError(
    JNIEnv* env,
    jclass /*clazz*/
) {
    try {
        const std::string error = readLastOpenError();
        return env->NewStringUTF(error.c_str());
    } catch (const std::exception& error) {
        LOGE("nativeLastOpenError exception: %s", error.what());
        return env->NewStringUTF("native_last_open_error_unavailable");
    } catch (...) {
        LOGE("nativeLastOpenError unknown exception");
        return env->NewStringUTF("native_last_open_error_unavailable");
    }
}
