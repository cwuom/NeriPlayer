#include "usb/exclusive/usb_streaming_interface_lifecycle.h"

#include <cassert>

int main() {
    using namespace neri::usb;

    const StreamingInterfaceActivationPlan uac1 =
        streamingInterfaceActivationPlan(1);
    assert(uac1.supported);
    assert(uac1.steps[0] ==
        StreamingInterfaceActivationStep::ActivateAlternate);
    assert(uac1.steps[1] ==
        StreamingInterfaceActivationStep::NegotiateSampleRate);

    const StreamingInterfaceActivationPlan uac2 =
        streamingInterfaceActivationPlan(2);
    assert(uac2.supported);
    assert(uac2.steps[0] ==
        StreamingInterfaceActivationStep::NegotiateSampleRate);
    assert(uac2.steps[1] ==
        StreamingInterfaceActivationStep::ActivateAlternate);

    assert(!streamingInterfaceActivationPlan(0).supported);
    assert(canTransitionStreamingInterface(true, false, 2, 1));
    assert(!canTransitionStreamingInterface(false, false, 2, 1));
    assert(!canTransitionStreamingInterface(true, true, 2, 1));
    assert(!canTransitionStreamingInterface(true, false, -1, 1));
    assert(!canTransitionStreamingInterface(true, false, 2, 0));
    return 0;
}
