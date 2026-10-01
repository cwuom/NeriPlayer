#pragma once

#include <array>

namespace neri::usb {

enum class StreamingInterfaceActivationStep {
    ActivateAlternate,
    NegotiateSampleRate
};

struct StreamingInterfaceActivationPlan {
    std::array<StreamingInterfaceActivationStep, 2> steps {
        StreamingInterfaceActivationStep::ActivateAlternate,
        StreamingInterfaceActivationStep::NegotiateSampleRate
    };
    bool supported = false;
};

inline StreamingInterfaceActivationPlan streamingInterfaceActivationPlan(
    int uacVersion
) {
    if (uacVersion == 1) {
        return StreamingInterfaceActivationPlan {
            {
                StreamingInterfaceActivationStep::ActivateAlternate,
                StreamingInterfaceActivationStep::NegotiateSampleRate
            },
            true
        };
    }
    if (uacVersion == 2) {
        return StreamingInterfaceActivationPlan {
            {
                StreamingInterfaceActivationStep::NegotiateSampleRate,
                StreamingInterfaceActivationStep::ActivateAlternate
            },
            true
        };
    }
    return StreamingInterfaceActivationPlan {};
}

inline bool canTransitionStreamingInterface(
    bool deviceOnline,
    bool detachConfirmed,
    int interfaceNumber,
    int alternateSetting
) {
    return deviceOnline && !detachConfirmed &&
        interfaceNumber >= 0 && alternateSetting > 0;
}

} // namespace neri::usb
