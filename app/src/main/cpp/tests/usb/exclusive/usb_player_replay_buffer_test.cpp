#include "usb/exclusive/usb_player_replay_buffer.h"

#include <array>
#include <cassert>
#include <cstdint>

namespace {

void verifiesCancelledTransfersReplayInSubmissionOrder() {
    neri::usb::PlayerReplayBuffer replay;
    const std::array<uint8_t, 4> later { 5, 6, 7, 8 };
    const std::array<uint8_t, 4> earlier { 1, 2, 3, 4 };
    assert(replay.push(2, later.data(), later.size()));
    assert(replay.push(1, earlier.data(), earlier.size()));
    assert(replay.queuedBytes() == 8);

    std::array<uint8_t, 8> output {};
    assert(replay.read(output.data(), output.size()) == output.size());
    const std::array<uint8_t, 8> expected { 1, 2, 3, 4, 5, 6, 7, 8 };
    assert(output == expected);
    assert(replay.queuedBytes() == 0);
}

void verifiesPartialReplayKeepsRemainingBytes() {
    neri::usb::PlayerReplayBuffer replay;
    const std::array<uint8_t, 8> input { 1, 2, 3, 4, 5, 6, 7, 8 };
    assert(replay.push(1, input.data(), input.size()));

    std::array<uint8_t, 4> first {};
    std::array<uint8_t, 4> second {};
    assert(replay.read(first.data(), first.size()) == first.size());
    assert(replay.queuedBytes() == second.size());
    assert(replay.read(second.data(), second.size()) == second.size());
    assert((first == std::array<uint8_t, 4> { 1, 2, 3, 4 }));
    assert((second == std::array<uint8_t, 4> { 5, 6, 7, 8 }));
}

void verifiesDuplicateSequenceIsRejectedWithoutChangingQueue() {
    neri::usb::PlayerReplayBuffer replay;
    const std::array<uint8_t, 4> first { 1, 2, 3, 4 };
    const std::array<uint8_t, 4> duplicate { 5, 6, 7, 8 };
    assert(replay.push(1, first.data(), first.size()));
    assert(!replay.push(1, duplicate.data(), duplicate.size()));
    assert(replay.queuedBytes() == first.size());
}

} // namespace

int main() {
    verifiesCancelledTransfersReplayInSubmissionOrder();
    verifiesPartialReplayKeepsRemainingBytes();
    verifiesDuplicateSequenceIsRejectedWithoutChangingQueue();
    return 0;
}
