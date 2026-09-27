#pragma once

#include <atomic>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libusb/libusb.h"
#include "usb/exclusive/usb_exclusive_device_selection.h"
#include "usb/exclusive/usb_player_replay_buffer.h"
#include "usb/exclusive/usb_player_startup_preroll.h"
#include "usb/exclusive/usb_recovery_action_latch.h"
#include "usb/feedback/usb_explicit_feedback_runtime.h"
#include "usb/feedback/usb_feedback_in_transfer_set.h"
#include "usb/feedback/usb_libusb_feedback_backend.h"
#include "usb/iso/usb_iso_packet_scheduler.h"
#include "usb/iso/usb_iso_transfer_window.h"
#include "usb/pcm/usb_pcm_pipeline.h"

inline constexpr int kDefaultPcmRingDurationMs = 250;

enum class StreamSource {
    Tone,
    PlayerPcm
};

struct UsbExclusiveHandle;

struct TransferUserData {
    UsbExclusiveHandle* handle = nullptr;
    int slot = -1;
    int64_t queuedPlayerFrames = 0;
    uint64_t playerSequence = 0;
    uint64_t generation = 0;
    bool forceSilence = false;
};

struct UsbDeviceState {
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
    int usbSpeed = LIBUSB_SPEED_UNKNOWN;
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    uint16_t deviceRelease = 0;
    uint8_t busNumber = 0;
    uint8_t deviceAddress = 0;
    std::vector<neri::usb::exclusive::ClaimedUsbInterface> claimedAudioInterfaces;
    bool completeAudioFunctionClaim = false;
    int uacVersion = 0;
    int uacClockSourceId = 0;
    neri::usb::uac1::TypeIFormat uac1Format;
    neri::usb::uac1::EndpointControls uac1EndpointControls;
    neri::usb::uac2::ControlCapability uac2SampleRateControl =
        neri::usb::uac2::ControlCapability::None;
    int negotiatedSampleRate = 0;
    std::string descriptorSampleRates = "none";
    std::string formatSelectionReason = "none";
    std::string sampleRateControlStatus = "not_attempted";
    std::string endpointSyncType = "none";
    std::string endpointFeedback = "none";
    bool streamingAlternateActive = false;
    int streamingAlternateTransitions = 0;
    int streamingAlternateResetFailures = 0;
    std::string streamingAlternateStatus = "not_configured";
};

struct UsbTransferState {
    neri::usb::feedback::FeedbackRateQ32 feedbackNominalRateQ32 = 0;
    int intervalsPerSecond = 1000;
    int bytesPerUsbFrame = 0;
    int packetsPerTransfer = neri::usb::kDefaultIsoPacketsPerTransfer;
    int baseTransferCount = neri::usb::kMinimumIsoTransferCount;
    int transferCount = neri::usb::kMinimumIsoTransferCount;
    int transferBytes = 0;
    std::vector<libusb_transfer*> transfers;
    std::vector<std::vector<uint8_t>> transferBuffers;
    std::vector<TransferUserData> transferUserData;
    std::vector<int> transferStatuses;
    std::vector<uint8_t> transferSubmitted;
    neri::usb::feedback::LibusbFeedbackTransferBackend feedbackTransferBackend;
    neri::usb::feedback::FeedbackInTransferSet feedbackInTransferSet {
        &feedbackTransferBackend
    };
    neri::usb::feedback::ExplicitFeedbackRuntime feedbackRuntime;
    std::thread eventThread;
    std::atomic<bool> running { false };
    std::atomic<int> inFlightTransfers { 0 };
    std::atomic<int> targetTransferCount { neri::usb::kMinimumIsoTransferCount };
    std::mutex transferSubmitLock;
    std::mutex transferRefillLock;
    std::atomic<StreamSource> streamSource { StreamSource::Tone };
    neri::usb::IsoPacketScheduler packetScheduler;
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
};

struct UsbPlayerState {
    std::atomic<bool> playbackEnabled { false };
    std::atomic<bool> playerPaused { false };
    std::atomic<bool> focusMuted { false };
    neri::usb::PcmPipeline pcmPipeline;
    neri::usb::PlayerReplayBuffer playerReplayBuffer;
    neri::usb::PlayerStartupPreroll playerStartupPreroll;
    int pcmRingDurationMs = kDefaultPcmRingDurationMs;
    std::atomic<int64_t> stagedPlayerFrames { 0 };
    std::atomic<int64_t> completedAudioFrames { 0 };
    std::atomic<uint64_t> nextPlayerSequence { 1 };
    std::atomic<bool> preserveCancelledPlayerFrames { false };
    std::atomic<bool> playerReplayFailed { false };
    std::atomic<int> shortWriteWarnings { 0 };
    std::atomic<float> playerVolume { 1.0f };
};

struct UsbRecoveryState {
    std::atomic<bool> deviceOnline { true };
    std::atomic<bool> noDeviceObserved { false };
    std::atomic<bool> detachBroadcastConfirmed { false };
    std::atomic<bool> stopRequested { false };
    std::atomic<bool> closing { false };
    std::atomic<bool> transportFailed { false };
    std::mutex lock;
    std::string lastError;
    int64_t nativeStreamGeneration = 0;
    int64_t recoveryEpoch = 1;
    neri::usb::UsbRecoveryActionLatch recoveryActionLatch;
};

struct UsbExclusiveHandle {
    std::mutex apiLock;
    UsbDeviceState device;
    UsbTransferState transfer;
    UsbPlayerState player;
    UsbRecoveryState recovery;
};
