#pragma once

#include <cstddef>
#include <cstdint>

namespace neri::usb::feedback {

using FeedbackRateQ32 = uint64_t;

constexpr FeedbackRateQ32 kQ32One = UINT64_C(1) << 32;
constexpr uint32_t kMaxSupportedSampleRate = 768000;
constexpr FeedbackRateQ32 kMaxSupportedRateQ32 =
    static_cast<FeedbackRateQ32>(kMaxSupportedSampleRate) * kQ32One;

enum class FeedbackMathStatus {
    Ok,
    InvalidArgument,
    UnsupportedProfile,
    PayloadLengthMismatch,
    NullPayload,
    NonMonotonicInput,
    OutOfRange,
    Overflow,
    CapacityExceeded,
    NotReady
};

enum class FeedbackRawUnit {
    Unknown,
    FramesPerBusFrame,
    FramesPerMicroframe,
    FramesPerServiceInterval
};

enum class FeedbackEstimateStatus {
    Accepted,
    NotConfigured,
    NonMonotonicSequence,
    NonMonotonicTimestamp,
    OutsideNominalRange,
    LocalOutlier
};

enum class FeedbackClockState {
    Disabled,
    Acquiring,
    Locked,
    Holdover,
    Relocking,
    Failed
};

enum class FeedbackClockFailureReason {
    None,
    AcquireTimeout,
    HoldoverTimeout,
    NonMonotonicTime
};

enum class FeedbackAheadWindowStatus {
    Ok,
    InvalidArgument,
    CounterOrder,
    Overflow,
    CapacityExceeded
};

struct NormalizedFeedbackSample {
    FeedbackRateQ32 rateQ32 = 0;
    int64_t receivedAtNs = 0;
    uint64_t sequence = 0;
};

struct FeedbackPacketPlan {
    FeedbackMathStatus status = FeedbackMathStatus::NotReady;
    uint32_t frames = 0;
    uint32_t bytes = 0;
};

const char* feedbackMathStatusName(FeedbackMathStatus status);
const char* feedbackEstimateStatusName(FeedbackEstimateStatus status);
const char* feedbackClockStateName(FeedbackClockState state);
const char* feedbackClockFailureReasonName(FeedbackClockFailureReason reason);

} // namespace neri::usb::feedback
