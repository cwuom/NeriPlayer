#pragma once

#include <jni.h>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>

#include "usb/exclusive/usb_exclusive_session_state.h"
#include "usb/iso/usb_iso_transfer_window.h"

namespace neri::usb::exclusive {

inline constexpr int kEventLoopWaitTimeoutMs = 100;
inline constexpr int kDrainEventWaitTimeoutMs = 100;
inline constexpr int kParkedEventWaitTimeoutMs = 5000;
inline constexpr int kEventLoopErrorBackoffBaseMs = 4;
inline constexpr int kEventLoopErrorBackoffMaxMs = 200;
inline constexpr int kEventLoopConsecutiveErrorLimit = 64;
inline constexpr int kParkedErrorBackoffBaseMs = 100;
inline constexpr int kParkedErrorBackoffMaxMs = 5000;
inline constexpr int kGeneratedToneFrequencyHz = 440;
inline constexpr int kExplicitFeedbackTransferCount = 4;
inline constexpr int kExplicitFeedbackPacketsPerTransfer = 8;
inline constexpr int kExplicitFeedbackAudioTransferCount = 16;
inline constexpr uint32_t kExplicitFeedbackBootstrapPacketLimit = 4096;
inline constexpr int kHighSpeedTargetInFlightMs = 160;
inline constexpr int kFullSpeedTargetInFlightMs = 320;
inline constexpr int kPlayerStartupPrerollMs = 0;
inline constexpr int kMinimumPcmRingDurationMs = 100;
inline constexpr int kMaximumPcmRingDurationMs = 3000;
inline constexpr int kCancelDrainWarningMs = 1200;
inline constexpr int kCancelDrainDeadlineMs = 3000;
inline constexpr int kQuarantineDrainLogIntervalMs = 10000;
inline constexpr int kQuarantineTotalTimeoutMs = 30000;
inline constexpr size_t kMaximumParkedHandles = 8;
inline constexpr size_t kMaximumHardRetainedHandles = 4;
inline constexpr int kFirstTransferCompletionTimeoutMs = 3000;
inline constexpr int kTransferCompletionStallTimeoutMs = 1500;
inline constexpr int kInterfaceTransitionCooldownMs = 1500;
inline constexpr int kUrgentAudioThreadPriority = -19;
inline constexpr auto kLibusbEndpointOut =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_OUT);
inline constexpr auto kLibusbEndpointIn =
    static_cast<uint8_t>(LIBUSB_ENDPOINT_IN);

extern std::mutex g_usbInterfaceTransitionLock;

std::shared_ptr<UsbExclusiveHandle> acquireHandle(jlong token);
jlong registerHandle(const std::shared_ptr<UsbExclusiveHandle>& handle);
std::shared_ptr<UsbExclusiveHandle> takeHandle(jlong token);
bool closeHandleInternal(const std::shared_ptr<UsbExclusiveHandle>& handle);
void serviceParkedHandlesOnce() noexcept;

const char* libusbErrName(int rc);
void rememberLastOpenError(const std::string& error);
std::string readLastOpenError();
int remainingInterfaceTransitionCooldownMsLocked();
void markInterfaceTransitionLocked();
void clearError(UsbExclusiveHandle* handle);
void setError(UsbExclusiveHandle* handle, const char* error);
void setError(UsbExclusiveHandle* handle, const std::string& error);
std::string getErrorCopy(UsbExclusiveHandle* handle);
void assignNewNativeStreamGeneration(UsbExclusiveHandle* handle);
void requestDeviceStop(UsbExclusiveHandle* handle, bool detachBroadcastConfirmed);
void requestNoDeviceStop(UsbExclusiveHandle* handle);
void markTransportFailed(UsbExclusiveHandle* handle);

int targetIsoTransferCount(const UsbExclusiveHandle* handle, int requestedDurationMs);
int setStreamingAlternateLocked(
    UsbExclusiveHandle* handle,
    int interfaceNumber,
    int activeAlternateSetting,
    int alternateSetting,
    const char* operation
);
int setStreamingAlternateLocked(UsbExclusiveHandle* handle, int alternateSetting, const char* operation);
bool parkStreamingAlternate(UsbExclusiveHandle* handle, const char* operation);
bool activateStreamingAlternate(UsbExclusiveHandle* handle, std::string* error);
bool reconfigureOpenedPlayerPcmOutput(
    UsbExclusiveHandle* handle,
    int sampleRate,
    int channelCount,
    int bitsPerSample,
    int subslotBytes,
    std::string* error
);
void finishClosedUsbResources(UsbExclusiveHandle* handle);

} // namespace neri::usb::exclusive
