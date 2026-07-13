#include "usb/exclusive/usb_player_startup_preroll.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>

namespace {

void verifiesPrerollRoundsUpToWholeTransfers() {
    neri::usb::PlayerStartupPreroll preroll;
    preroll.arm(48000, 150);
    assert(preroll.framesRemaining() == 7200);

    constexpr int frameBytes = 4;
    constexpr size_t transferFrames = 768;
    std::array<uint8_t, transferFrames * frameBytes> transfer {};
    for (int transferIndex = 0; transferIndex < 10; ++transferIndex) {
        std::fill(transfer.begin(), transfer.end(), 0x7f);
        assert(preroll.fillSilenceIfNeeded(
            transfer.data(),
            transfer.size(),
            frameBytes
        ));
        assert(std::all_of(transfer.begin(), transfer.end(), [](uint8_t value) {
            return value == 0;
        }));
    }

    assert(preroll.framesRemaining() == 0);
    std::fill(transfer.begin(), transfer.end(), 0x7f);
    assert(!preroll.fillSilenceIfNeeded(
        transfer.data(),
        transfer.size(),
        frameBytes
    ));
    assert(std::all_of(transfer.begin(), transfer.end(), [](uint8_t value) {
        return value == 0x7f;
    }));
}

void verifiesRearmAndClearResetTheCountdown() {
    neri::usb::PlayerStartupPreroll preroll;
    preroll.arm(96000, 150);
    assert(preroll.framesRemaining() == 14400);

    preroll.clear();
    assert(preroll.framesRemaining() == 0);

    preroll.arm(44100, 150);
    assert(preroll.framesRemaining() == 6615);
}

} // namespace

int main() {
    verifiesPrerollRoundsUpToWholeTransfers();
    verifiesRearmAndClearResetTheCountdown();
    return 0;
}
