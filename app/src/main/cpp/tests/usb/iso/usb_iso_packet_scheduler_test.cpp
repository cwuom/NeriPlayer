#include "usb/iso/usb_iso_packet_scheduler.h"

#include <cassert>
#include <cstdint>

namespace {

void preservesNominalFortyFourPointOneKilohertzDistribution() {
    neri::usb::IsoPacketScheduler scheduler;
    scheduler.configure(44100, 1000, 4);
    uint64_t frames = 0;
    for (int interval = 0; interval < 1000; ++interval) {
        const auto plan = scheduler.next();
        assert(plan.frames == 44 || plan.frames == 45);
        assert(plan.bytes == plan.frames * 4);
        frames += static_cast<uint64_t>(plan.frames);
    }
    assert(frames == 44100U);
}

void resetRestoresTheInitialPacketPhase() {
    neri::usb::IsoPacketScheduler scheduler;
    scheduler.configure(44100, 1000, 4);
    const auto first = scheduler.next();
    const auto second = scheduler.next();
    assert(second.bytes == second.frames * 4);
    scheduler.reset();
    const auto afterReset = scheduler.next();
    assert(afterReset.frames == first.frames);
    assert(afterReset.bytes == first.bytes);
}

void preservesHighSpeedNominalPackets() {
    neri::usb::IsoPacketScheduler scheduler;
    scheduler.configure(48000, 8000, 8);
    for (int interval = 0; interval < 8000; ++interval) {
        const auto plan = scheduler.next();
        assert(plan.frames == 6);
        assert(plan.bytes == 48);
    }
}

} // namespace

int main() {
    preservesNominalFortyFourPointOneKilohertzDistribution();
    resetRestoresTheInitialPacketPhase();
    preservesHighSpeedNominalPackets();
    return 0;
}
