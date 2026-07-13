#pragma once

#include <algorithm>
#include <cstdint>

namespace neri::usb {

struct IsoPacketPlan {
    int frames = 0;
    int bytes = 0;
};

class IsoPacketScheduler {
public:
    void configure(int sampleRate, int intervalsPerSecond, int frameBytes) {
        sampleRate_ = std::max(1, sampleRate);
        intervalsPerSecond_ = std::max(1, intervalsPerSecond);
        frameBytes_ = std::max(1, frameBytes);
        reset();
    }

    void reset() {
        frameRemainder_ = 0;
    }

    [[nodiscard]] IsoPacketPlan next() {
        frameRemainder_ += static_cast<uint64_t>(sampleRate_);
        const int frames = static_cast<int>(
            frameRemainder_ / static_cast<uint64_t>(intervalsPerSecond_)
        );
        frameRemainder_ %= static_cast<uint64_t>(intervalsPerSecond_);
        return IsoPacketPlan { frames, frames * frameBytes_ };
    }

private:
    int sampleRate_ = 1;
    int intervalsPerSecond_ = 1;
    int frameBytes_ = 1;
    uint64_t frameRemainder_ = 0;
};

} // namespace neri::usb
