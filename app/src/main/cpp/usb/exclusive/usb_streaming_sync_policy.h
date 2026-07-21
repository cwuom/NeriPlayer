#pragma once

#include <cstdint>
#include <string_view>

namespace neri::usb {

enum class UsbStreamingFeedbackMode {
    None,
    Explicit,
    Implicit
};

enum class UsbStreamingSyncPolicyReason {
    Accepted,
    Uac1AsyncFeedbackSchedulerUnavailable,
    ImplicitFeedbackSchedulerUnavailable,
    UnsupportedUacVersion
};

struct UsbStreamingSyncPolicyDecision {
    bool supported = false;
    bool requiresExplicitFeedbackProfile = false;
    UsbStreamingSyncPolicyReason reason =
        UsbStreamingSyncPolicyReason::UnsupportedUacVersion;
};

[[nodiscard]] inline UsbStreamingFeedbackMode usbStreamingFeedbackModeForDescription(
    std::string_view feedbackDescription
) {
    if (feedbackDescription == "implicit") {
        return UsbStreamingFeedbackMode::Implicit;
    }
    return feedbackDescription.rfind("explicit:", 0) == 0
        ? UsbStreamingFeedbackMode::Explicit
        : UsbStreamingFeedbackMode::None;
}

[[nodiscard]] inline UsbStreamingSyncPolicyDecision resolveUsbStreamingSyncPolicy(
    int uacVersion,
    bool requiresFeedbackScheduler,
    UsbStreamingFeedbackMode feedbackMode
) {
    if (!requiresFeedbackScheduler) {
        return UsbStreamingSyncPolicyDecision {
            uacVersion == 1 || uacVersion == 2,
            false,
            uacVersion == 1 || uacVersion == 2
                ? UsbStreamingSyncPolicyReason::Accepted
                : UsbStreamingSyncPolicyReason::UnsupportedUacVersion
        };
    }

    if (uacVersion == 1) {
        return UsbStreamingSyncPolicyDecision {
            false,
            false,
            feedbackMode == UsbStreamingFeedbackMode::Implicit
                ? UsbStreamingSyncPolicyReason::ImplicitFeedbackSchedulerUnavailable
                : UsbStreamingSyncPolicyReason::Uac1AsyncFeedbackSchedulerUnavailable
        };
    }

    if (uacVersion == 2) {
        if (feedbackMode == UsbStreamingFeedbackMode::Implicit) {
            return UsbStreamingSyncPolicyDecision {
                false,
                false,
                UsbStreamingSyncPolicyReason::ImplicitFeedbackSchedulerUnavailable
            };
        }
        return UsbStreamingSyncPolicyDecision {
            true,
            true,
            UsbStreamingSyncPolicyReason::Accepted
        };
    }

    return UsbStreamingSyncPolicyDecision {
        false,
        false,
        UsbStreamingSyncPolicyReason::UnsupportedUacVersion
    };
}

[[nodiscard]] inline const char* usbStreamingSyncPolicyReasonName(
    UsbStreamingSyncPolicyReason reason
) {
    switch (reason) {
        case UsbStreamingSyncPolicyReason::Accepted:
            return "accepted";
        case UsbStreamingSyncPolicyReason::Uac1AsyncFeedbackSchedulerUnavailable:
            return "async_feedback_scheduler_unavailable";
        case UsbStreamingSyncPolicyReason::ImplicitFeedbackSchedulerUnavailable:
            return "implicit_feedback_scheduler_unavailable";
        case UsbStreamingSyncPolicyReason::UnsupportedUacVersion:
            return "unsupported_uac_version";
    }
    return "unsupported_uac_version";
}

} // namespace neri::usb
