#include "usb/feedback/usb_feedback_types.h"

namespace neri::usb::feedback {

const char* feedbackMathStatusName(FeedbackMathStatus status) {
    switch (status) {
        case FeedbackMathStatus::Ok:
            return "ok";
        case FeedbackMathStatus::InvalidArgument:
            return "invalid_argument";
        case FeedbackMathStatus::UnsupportedProfile:
            return "unsupported_profile";
        case FeedbackMathStatus::PayloadLengthMismatch:
            return "payload_length_mismatch";
        case FeedbackMathStatus::NullPayload:
            return "null_payload";
        case FeedbackMathStatus::NonMonotonicInput:
            return "non_monotonic_input";
        case FeedbackMathStatus::OutOfRange:
            return "out_of_range";
        case FeedbackMathStatus::Overflow:
            return "overflow";
        case FeedbackMathStatus::CapacityExceeded:
            return "capacity_exceeded";
        case FeedbackMathStatus::NotReady:
            return "not_ready";
    }
    return "unknown";
}

const char* feedbackEstimateStatusName(FeedbackEstimateStatus status) {
    switch (status) {
        case FeedbackEstimateStatus::Accepted:
            return "accepted";
        case FeedbackEstimateStatus::NotConfigured:
            return "not_configured";
        case FeedbackEstimateStatus::NonMonotonicSequence:
            return "non_monotonic_sequence";
        case FeedbackEstimateStatus::NonMonotonicTimestamp:
            return "non_monotonic_timestamp";
        case FeedbackEstimateStatus::OutsideNominalRange:
            return "outside_nominal_range";
        case FeedbackEstimateStatus::LocalOutlier:
            return "local_outlier";
    }
    return "unknown";
}

const char* feedbackClockStateName(FeedbackClockState state) {
    switch (state) {
        case FeedbackClockState::Disabled:
            return "disabled";
        case FeedbackClockState::Acquiring:
            return "acquiring";
        case FeedbackClockState::Locked:
            return "locked";
        case FeedbackClockState::Holdover:
            return "holdover";
        case FeedbackClockState::Relocking:
            return "relocking";
        case FeedbackClockState::Failed:
            return "failed";
    }
    return "unknown";
}

const char* feedbackClockFailureReasonName(FeedbackClockFailureReason reason) {
    switch (reason) {
        case FeedbackClockFailureReason::None:
            return "none";
        case FeedbackClockFailureReason::AcquireTimeout:
            return "acquire_timeout";
        case FeedbackClockFailureReason::HoldoverTimeout:
            return "holdover_timeout";
        case FeedbackClockFailureReason::NonMonotonicTime:
            return "non_monotonic_time";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
