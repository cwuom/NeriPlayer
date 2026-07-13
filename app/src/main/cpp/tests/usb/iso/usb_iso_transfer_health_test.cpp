#include "usb/iso/usb_iso_transfer_health.h"

#include <cassert>

int main() {
    using neri::usb::kIsoPacketErrorFailureScore;
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

    return 0;
}
