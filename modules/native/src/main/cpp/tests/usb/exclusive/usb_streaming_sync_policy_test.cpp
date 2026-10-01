#include "usb/exclusive/usb_streaming_sync_policy.h"
#include "usb/uac1/usb_uac1_format.h"
#include "usb/uac2/usb_uac2_format.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>

namespace {

using neri::usb::UsbStreamingFeedbackMode;
using neri::usb::UsbStreamingSyncPolicyReason;

struct SyncPolicyCase {
    int uacVersion;
    uint8_t endpointAttributes;
    UsbStreamingFeedbackMode feedbackMode;
    bool supported;
    bool requiresExplicitFeedbackProfile;
    UsbStreamingSyncPolicyReason reason;
};

bool requiresFeedbackScheduler(int uacVersion, uint8_t endpointAttributes) {
    return uacVersion == 1
        ? neri::usb::uac1::requiresFeedbackScheduler(endpointAttributes)
        : neri::usb::uac2::requiresFeedbackScheduler(endpointAttributes);
}

void verifiesFeedbackDescriptionClassification() {
    assert(neri::usb::usbStreamingFeedbackModeForDescription("none") ==
        UsbStreamingFeedbackMode::None);
    assert(neri::usb::usbStreamingFeedbackModeForDescription("implicit") ==
        UsbStreamingFeedbackMode::Implicit);
    assert(neri::usb::usbStreamingFeedbackModeForDescription("explicit:0x86") ==
        UsbStreamingFeedbackMode::Explicit);
    assert(neri::usb::usbStreamingFeedbackModeForDescription("unsupported") ==
        UsbStreamingFeedbackMode::None);
}

void verifiesUac1AndUac2SynchronizationMatrix() {
    constexpr uint8_t kAsynchronousData = 0x05;
    constexpr uint8_t kAdaptiveData = 0x09;
    constexpr uint8_t kSynchronousData = 0x0D;
    constexpr uint8_t kImplicitAsynchronousData = 0x25;

    constexpr std::array<SyncPolicyCase, 10> cases {
        SyncPolicyCase {
            1,
            kAdaptiveData,
            UsbStreamingFeedbackMode::None,
            true,
            false,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            1,
            kSynchronousData,
            UsbStreamingFeedbackMode::None,
            true,
            false,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            1,
            kAsynchronousData,
            UsbStreamingFeedbackMode::None,
            false,
            false,
            UsbStreamingSyncPolicyReason::Uac1AsyncFeedbackSchedulerUnavailable
        },
        SyncPolicyCase {
            1,
            kAsynchronousData,
            UsbStreamingFeedbackMode::Explicit,
            false,
            false,
            UsbStreamingSyncPolicyReason::Uac1AsyncFeedbackSchedulerUnavailable
        },
        SyncPolicyCase {
            1,
            kImplicitAsynchronousData,
            UsbStreamingFeedbackMode::Implicit,
            false,
            false,
            UsbStreamingSyncPolicyReason::ImplicitFeedbackSchedulerUnavailable
        },
        SyncPolicyCase {
            2,
            kAdaptiveData,
            UsbStreamingFeedbackMode::Explicit,
            true,
            false,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            2,
            kSynchronousData,
            UsbStreamingFeedbackMode::Explicit,
            true,
            false,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            2,
            kAsynchronousData,
            UsbStreamingFeedbackMode::None,
            true,
            true,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            2,
            kAsynchronousData,
            UsbStreamingFeedbackMode::Explicit,
            true,
            true,
            UsbStreamingSyncPolicyReason::Accepted
        },
        SyncPolicyCase {
            2,
            kImplicitAsynchronousData,
            UsbStreamingFeedbackMode::Implicit,
            false,
            false,
            UsbStreamingSyncPolicyReason::ImplicitFeedbackSchedulerUnavailable
        }
    };

    for (const SyncPolicyCase& testCase : cases) {
        const auto decision = neri::usb::resolveUsbStreamingSyncPolicy(
            testCase.uacVersion,
            requiresFeedbackScheduler(testCase.uacVersion, testCase.endpointAttributes),
            testCase.feedbackMode
        );
        assert(decision.supported == testCase.supported);
        assert(decision.requiresExplicitFeedbackProfile ==
            testCase.requiresExplicitFeedbackProfile);
        assert(decision.reason == testCase.reason);
    }
}

void rejectsUnknownAudioClassWithoutCreatingFeedbackWork() {
    const auto decision = neri::usb::resolveUsbStreamingSyncPolicy(
        3,
        false,
        UsbStreamingFeedbackMode::None
    );
    assert(!decision.supported);
    assert(!decision.requiresExplicitFeedbackProfile);
    assert(decision.reason == UsbStreamingSyncPolicyReason::UnsupportedUacVersion);
    assert(std::string(neri::usb::usbStreamingSyncPolicyReasonName(
        decision.reason
    )) == "unsupported_uac_version");
}

} // namespace

int main() {
    verifiesFeedbackDescriptionClassification();
    verifiesUac1AndUac2SynchronizationMatrix();
    rejectsUnknownAudioClassWithoutCreatingFeedbackWork();
    return 0;
}
