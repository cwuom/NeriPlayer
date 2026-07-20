#include "usb/iso/usb_iso_transfer_health.h"

#include <cassert>

int main() {
    using neri::usb::kIsoPacketErrorFailureScore;
    using neri::usb::completedIsoPacketBytes;
    using neri::usb::shouldFailForIsoPacketErrors;
    using neri::usb::updateIsoPacketErrorScore;

    int score = updateIsoPacketErrorScore(0, 1);
    assert(score == 1);
    assert(!shouldFailForIsoPacketErrors(score));

    score = updateIsoPacketErrorScore(score, 0);
    assert(score == 0);

    score = updateIsoPacketErrorScore(0, kIsoPacketErrorFailureScore - 1);
    assert(!shouldFailForIsoPacketErrors(score));

    score = updateIsoPacketErrorScore(score, 1);
    assert(shouldFailForIsoPacketErrors(score));

    score = updateIsoPacketErrorScore(kIsoPacketErrorFailureScore, 0);
    assert(score == kIsoPacketErrorFailureScore - 1);

    assert(completedIsoPacketBytes(true, 192, 192) == 192);
    assert(completedIsoPacketBytes(true, 192, 96) == 96);
    assert(completedIsoPacketBytes(true, 192, 384) == 192);
    assert(completedIsoPacketBytes(true, 192, 0) == 0);
    assert(completedIsoPacketBytes(false, 192, 192) == 0);

    return 0;
}
