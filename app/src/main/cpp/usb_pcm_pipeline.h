#pragma once

#include "usb_pcm_codec.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace neri::usb {

struct PcmOutputFormat {
    int sampleRate = 0;
    int channelCount = 0;
    int subslotBytes = 0;
    int bitsPerSample = 0;
    int frameBytes = 0;
};

struct PcmInputFormat {
    int sampleRate = 0;
    int channelCount = 0;
    int encoding = 0;
};

struct PcmPipelineConfig {
    PcmOutputFormat output;
    PcmInputFormat input;
    int ringDurationMs = 0;
    int transferBytes = 0;
    int transferCount = 0;
};

struct PcmPipelineSnapshot {
    size_t levelBytes = 0;
    size_t capacityBytes = 0;
    size_t freeBytes = 0;
    size_t maxLevelBytes = 0;
    int64_t inputBytes = 0;
    int64_t outputBytes = 0;
    int64_t droppedBytes = 0;
    int64_t underrunBytes = 0;
    int64_t zeroFillBytes = 0;
    int64_t pausedZeroFillBytes = 0;
    int64_t signalOutputFrames = 0;
    int64_t silentOutputFrames = 0;
    int64_t signalOutputBytes = 0;
    int64_t backpressureEvents = 0;
    int64_t backpressureTotalUs = 0;
    int64_t backpressureCurrentUs = 0;
    int64_t backpressureMaxUs = 0;
    float outputPeak = 0.0f;
    float lastOutputPeak = 0.0f;
    float targetGain = 1.0f;
    float appliedGain = 1.0f;
};

class PcmPipeline final {
public:
    bool configure(const PcmPipelineConfig& config, std::string* error);
    bool resizeRingDuration(
        int ringDurationMs,
        int transferBytes,
        int transferCount,
        std::string* error
    );
    size_t write(const uint8_t* input, size_t inputBytes, std::string* error);
    size_t fill(uint8_t* output, size_t bytes, bool playbackEnabled);

    void clear();
    void resetCounters();
    void addDroppedFrames(int64_t frames);
    void setTargetGain(float gain);

    [[nodiscard]] size_t queuedFrames() const;
    [[nodiscard]] PcmPipelineSnapshot snapshot() const;

private:
    [[nodiscard]] size_t freeBytesLocked() const;
    void beginBackpressureLocked(int64_t nowUs);
    void endBackpressureLocked(int64_t nowUs);
    size_t writeRingLocked(const uint8_t* input, size_t bytes);
    size_t readRingLocked(uint8_t* output, size_t bytes);
    void applyGain(uint8_t* output, size_t bytes);
    void updateOutputSignalStatsLocked(const uint8_t* output, size_t bytes);

    mutable std::mutex writeLock_;
    mutable std::mutex lock_;
    PcmOutputFormat outputFormat_;
    PcmInputFormat inputFormat_;
    std::vector<uint8_t> ring_;
    size_t readIndex_ = 0;
    size_t writeIndex_ = 0;
    size_t levelBytes_ = 0;
    double resamplePosition_ = 0.0;
    bool hasPreviousInputFrame_ = false;
    std::vector<float> previousInputFrame_;
    std::vector<uint8_t> conversionBuffer_;
    int64_t inputBytes_ = 0;
    int64_t outputBytes_ = 0;
    int64_t droppedBytes_ = 0;
    int64_t underrunBytes_ = 0;
    int64_t zeroFillBytes_ = 0;
    int64_t pausedZeroFillBytes_ = 0;
    int64_t signalOutputFrames_ = 0;
    int64_t silentOutputFrames_ = 0;
    int64_t signalOutputBytes_ = 0;
    int64_t backpressureEvents_ = 0;
    int64_t backpressureTotalUs_ = 0;
    int64_t backpressureStartedAtUs_ = 0;
    int64_t backpressureMaxUs_ = 0;
    size_t maxLevelBytes_ = 0;
    float outputPeak_ = 0.0f;
    float lastOutputPeak_ = 0.0f;
    std::atomic<float> targetGain_ { 1.0f };
    std::atomic<float> appliedGain_ { 1.0f };
    float gainRampTarget_ = 1.0f;
    int gainRampFramesRemaining_ = 0;
};

} // namespace neri::usb
