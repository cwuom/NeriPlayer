#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

namespace neri::usb {

class PlayerReplayBuffer final {
public:
    bool push(uint64_t sequence, const uint8_t* data, size_t bytes) noexcept {
        if (sequence == 0 || data == nullptr || bytes == 0) {
            return false;
        }
        try {
            std::vector<uint8_t> copy(data, data + bytes);
            std::lock_guard<std::mutex> guard(lock_);
            const auto [entry, inserted] = chunks_.emplace(
                sequence,
                Chunk { std::move(copy), 0 }
            );
            if (!inserted) {
                return false;
            }
            queuedBytes_ += entry->second.bytes.size();
            return true;
        } catch (...) {
            return false;
        }
    }

    size_t read(uint8_t* output, size_t bytes) noexcept {
        if (output == nullptr || bytes == 0) {
            return 0;
        }
        std::lock_guard<std::mutex> guard(lock_);
        size_t copied = 0;
        while (copied < bytes && !chunks_.empty()) {
            auto entry = chunks_.begin();
            Chunk& chunk = entry->second;
            const size_t available = chunk.bytes.size() - chunk.offset;
            const size_t requested = std::min(bytes - copied, available);
            std::memcpy(output + copied, chunk.bytes.data() + chunk.offset, requested);
            copied += requested;
            chunk.offset += requested;
            queuedBytes_ -= requested;
            if (chunk.offset == chunk.bytes.size()) {
                chunks_.erase(entry);
            }
        }
        return copied;
    }

    [[nodiscard]] size_t queuedBytes() const noexcept {
        std::lock_guard<std::mutex> guard(lock_);
        return queuedBytes_;
    }

    void clear() noexcept {
        std::lock_guard<std::mutex> guard(lock_);
        chunks_.clear();
        queuedBytes_ = 0;
    }

private:
    struct Chunk final {
        std::vector<uint8_t> bytes;
        size_t offset = 0;
    };

    mutable std::mutex lock_;
    std::map<uint64_t, Chunk> chunks_;
    size_t queuedBytes_ = 0;
};

} // namespace neri::usb
