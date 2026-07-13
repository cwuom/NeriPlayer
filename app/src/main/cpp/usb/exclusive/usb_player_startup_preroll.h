#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace neri::usb {

class PlayerStartupPreroll final {
public:
    void arm(int sampleRate, int durationMs) noexcept {
        const int64_t frames = sampleRate > 0 && durationMs > 0
            ? static_cast<int64_t>(sampleRate) * durationMs / 1000
            : 0;
        framesRemaining_.store(std::max<int64_t>(0, frames));
    }

    bool fillSilenceIfNeeded(
        uint8_t* output,
        size_t bytes,
        int frameBytes
    ) noexcept {
        if (output == nullptr || bytes == 0 || frameBytes <= 0) {
            return false;
        }
        const int64_t transferFrames = static_cast<int64_t>(
            bytes / static_cast<size_t>(frameBytes)
        );
        if (transferFrames <= 0) {
            return false;
        }

        int64_t remaining = framesRemaining_.load();
        while (remaining > 0) {
            const int64_t updated = std::max<int64_t>(0, remaining - transferFrames);
            if (framesRemaining_.compare_exchange_weak(remaining, updated)) {
                std::memset(output, 0, bytes);
                return true;
            }
        }
        return false;
    }

    void clear() noexcept {
        framesRemaining_.store(0);
    }

    [[nodiscard]] int64_t framesRemaining() const noexcept {
        return framesRemaining_.load();
    }

private:
    std::atomic<int64_t> framesRemaining_ { 0 };
};

} // namespace neri::usb
