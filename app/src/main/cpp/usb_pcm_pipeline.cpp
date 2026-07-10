#include "usb_pcm_pipeline.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace neri::usb {
namespace {

constexpr int kGainRampDurationMs = 5;
constexpr double kPositionEpsilon = 0.000000001;

bool canRender(double position, int inputFrames, bool hasPreviousFrame) {
    if (inputFrames <= 0 || position < -1.0 - kPositionEpsilon) {
        return false;
    }
    if (position < 0.0) {
        return hasPreviousFrame;
    }
    const int leftIndex = static_cast<int>(std::floor(position));
    if (leftIndex < 0 || leftIndex >= inputFrames) {
        return false;
    }
    const double fraction = position - static_cast<double>(leftIndex);
    return fraction <= kPositionEpsilon || leftIndex + 1 < inputFrames;
}

size_t countOutputFrames(
    int inputFrames,
    double initialPosition,
    bool hasPreviousFrame,
    double ratio,
    size_t stopAfter
) {
    size_t outputFrames = 0;
    double position = initialPosition;
    while (canRender(position, inputFrames, hasPreviousFrame)) {
        ++outputFrames;
        if (outputFrames > stopAfter) {
            break;
        }
        position += ratio;
    }
    return outputFrames;
}

} // namespace

bool PcmPipeline::configure(const PcmPipelineConfig& config, std::string* error) {
    if (error != nullptr) {
        error->clear();
    }
    const int inputBytesPerSample = bytesPerSampleForEncoding(config.input.encoding);
    if (inputBytesPerSample <= 0 || config.input.sampleRate <= 0 ||
        config.input.channelCount <= 0 || config.output.sampleRate <= 0 ||
        config.output.channelCount <= 0 || config.output.subslotBytes <= 0 ||
        config.output.frameBytes <= 0) {
        if (error != nullptr) {
            *error = "unsupported_player_pcm_format";
        }
        return false;
    }
    const int64_t requestedBytes =
        static_cast<int64_t>(config.output.sampleRate) * config.output.frameBytes *
        config.ringDurationMs / 1000;
    const int64_t transferFloor =
        static_cast<int64_t>(config.transferBytes) * config.transferCount * 3;
    const size_t ringBytes = static_cast<size_t>(std::max<int64_t>(
        config.output.frameBytes,
        std::min<int64_t>(std::numeric_limits<int32_t>::max(),
            std::max(transferFloor, requestedBytes))
    ));

    std::lock_guard<std::mutex> guard(lock_);
    outputFormat_ = config.output;
    inputFormat_ = config.input;
    ring_.assign(ringBytes, 0);
    readIndex_ = 0;
    writeIndex_ = 0;
    levelBytes_ = 0;
    resamplePosition_ = 0.0;
    hasPreviousInputFrame_ = false;
    previousInputFrame_.assign(static_cast<size_t>(config.input.channelCount), 0.0f);
    inputBytes_ = 0;
    outputBytes_ = 0;
    droppedBytes_ = 0;
    underrunBytes_ = 0;
    zeroFillBytes_ = 0;
    pausedZeroFillBytes_ = 0;
    const float target = std::clamp(targetGain_.load(), 0.0f, 1.0f);
    appliedGain_.store(target);
    gainRampTarget_ = target;
    gainRampFramesRemaining_ = 0;
    return true;
}

size_t PcmPipeline::freeBytesLocked() const {
    return ring_.empty() ? 0 : ring_.size() - levelBytes_;
}

size_t PcmPipeline::writeRingLocked(const uint8_t* input, size_t bytes) {
    const size_t writable = std::min(bytes, freeBytesLocked());
    if (input == nullptr || writable == 0) {
        return 0;
    }
    const size_t first = std::min(writable, ring_.size() - writeIndex_);
    std::memcpy(ring_.data() + writeIndex_, input, first);
    const size_t second = writable - first;
    if (second > 0) {
        std::memcpy(ring_.data(), input + first, second);
    }
    writeIndex_ = (writeIndex_ + writable) % ring_.size();
    levelBytes_ += writable;
    return writable;
}

size_t PcmPipeline::readRingLocked(uint8_t* output, size_t bytes) {
    const size_t readable = std::min(bytes, levelBytes_);
    if (output == nullptr || readable == 0) {
        return 0;
    }
    const size_t first = std::min(readable, ring_.size() - readIndex_);
    std::memcpy(output, ring_.data() + readIndex_, first);
    const size_t second = readable - first;
    if (second > 0) {
        std::memcpy(output + first, ring_.data(), second);
    }
    readIndex_ = (readIndex_ + readable) % ring_.size();
    levelBytes_ -= readable;
    return readable;
}

size_t PcmPipeline::write(const uint8_t* input, size_t inputBytes, std::string* error) {
    if (error != nullptr) {
        error->clear();
    }
    if (input == nullptr || inputBytes == 0) {
        return 0;
    }
    const int inputSampleBytes = bytesPerSampleForEncoding(inputFormat_.encoding);
    const int inputChannels = std::max(1, inputFormat_.channelCount);
    const int inputFrameBytes = inputSampleBytes * inputChannels;
    if (inputSampleBytes <= 0 || inputFrameBytes <= 0 || outputFormat_.frameBytes <= 0) {
        if (error != nullptr) {
            *error = "unsupported_player_pcm_encoding";
        }
        return 0;
    }
    int inputFrames = static_cast<int>(inputBytes / static_cast<size_t>(inputFrameBytes));
    if (inputFrames <= 0) {
        return 0;
    }
    size_t freeOutputFrames = 0;
    {
        std::lock_guard<std::mutex> guard(lock_);
        freeOutputFrames = freeBytesLocked() / static_cast<size_t>(outputFormat_.frameBytes);
    }
    if (freeOutputFrames == 0) {
        return 0;
    }

    const double ratio = static_cast<double>(inputFormat_.sampleRate) /
        static_cast<double>(outputFormat_.sampleRate);
    if (inputFormat_.sampleRate == outputFormat_.sampleRate) {
        inputFrames = std::min(
            inputFrames,
            static_cast<int>(std::min<size_t>(
                freeOutputFrames,
                static_cast<size_t>(std::numeric_limits<int32_t>::max())
            ))
        );
    } else {
        int low = 1;
        int high = inputFrames;
        int best = 0;
        while (low <= high) {
            const int candidate = low + (high - low) / 2;
            const size_t outputFrames = countOutputFrames(
                candidate,
                resamplePosition_,
                hasPreviousInputFrame_,
                ratio,
                freeOutputFrames
            );
            if (outputFrames <= freeOutputFrames) {
                best = candidate;
                low = candidate + 1;
            } else {
                high = candidate - 1;
            }
        }
        inputFrames = best;
    }
    if (inputFrames <= 0) {
        return 0;
    }

    double localPosition = resamplePosition_;
    bool localHasPrevious = hasPreviousInputFrame_;
    std::vector<float> localPrevious = previousInputFrame_;
    std::vector<uint8_t> output;
    output.reserve(freeOutputFrames * static_cast<size_t>(outputFormat_.frameBytes));

    auto readFrameSample = [&](int frameIndex, int channel) {
        const int mappedChannel = std::min(channel, inputChannels - 1);
        if (frameIndex < 0) {
            if (!localHasPrevious || localPrevious.empty()) {
                return 0.0f;
            }
            return localPrevious[static_cast<size_t>(
                std::min(mappedChannel, static_cast<int>(localPrevious.size()) - 1)
            )];
        }
        const uint8_t* frame = input + static_cast<size_t>(frameIndex) * inputFrameBytes;
        return readEncodedPcmSample(
            frame + mappedChannel * inputSampleBytes,
            inputFormat_.encoding
        );
    };
    auto appendOutputFrame = [&](double position) {
        int leftIndex = -1;
        int rightIndex = 0;
        double fraction = position + 1.0;
        if (position >= 0.0) {
            leftIndex = static_cast<int>(std::floor(position));
            fraction = position - static_cast<double>(leftIndex);
            rightIndex = fraction <= kPositionEpsilon ? leftIndex : leftIndex + 1;
        }
        const size_t outputOffset = output.size();
        output.resize(outputOffset + static_cast<size_t>(outputFormat_.frameBytes), 0);
        uint8_t* outputFrame = output.data() + outputOffset;
        for (int channel = 0; channel < outputFormat_.channelCount; ++channel) {
            const float left = readFrameSample(leftIndex, channel);
            const float right = readFrameSample(rightIndex, channel);
            const float mixed = left + static_cast<float>((right - left) * fraction);
            writeIntegerPcmSample(
                outputFrame + channel * outputFormat_.subslotBytes,
                outputFormat_.subslotBytes,
                outputFormat_.bitsPerSample,
                mixed
            );
        }
    };

    if (inputFormat_.sampleRate == outputFormat_.sampleRate) {
        for (int frame = 0; frame < inputFrames; ++frame) {
            appendOutputFrame(static_cast<double>(frame));
        }
        localPosition = 0.0;
    } else {
        while (canRender(localPosition, inputFrames, localHasPrevious)) {
            appendOutputFrame(localPosition);
            localPosition += ratio;
        }
        localPosition -= static_cast<double>(inputFrames);
        if (std::abs(localPosition) < kPositionEpsilon) {
            localPosition = 0.0;
        }
    }

    localPrevious.assign(static_cast<size_t>(inputChannels), 0.0f);
    const uint8_t* finalFrame = input + static_cast<size_t>(inputFrames - 1) * inputFrameBytes;
    for (int channel = 0; channel < inputChannels; ++channel) {
        localPrevious[static_cast<size_t>(channel)] = readEncodedPcmSample(
            finalFrame + channel * inputSampleBytes,
            inputFormat_.encoding
        );
    }

    std::lock_guard<std::mutex> guard(lock_);
    if (freeBytesLocked() < output.size()) {
        return 0;
    }
    const size_t written = output.empty() ? 0 : writeRingLocked(output.data(), output.size());
    resamplePosition_ = localPosition;
    previousInputFrame_ = std::move(localPrevious);
    hasPreviousInputFrame_ = true;
    const size_t consumedBytes = static_cast<size_t>(inputFrames * inputFrameBytes);
    inputBytes_ += static_cast<int64_t>(consumedBytes);
    return written == output.size() ? consumedBytes : 0;
}

void PcmPipeline::applyGain(uint8_t* output, size_t bytes) {
    const int frames = outputFormat_.frameBytes > 0
        ? static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes))
        : 0;
    if (output == nullptr || frames <= 0) {
        return;
    }
    float applied = appliedGain_.load();
    const float target = std::clamp(targetGain_.load(), 0.0f, 1.0f);
    if (std::abs(target - gainRampTarget_) > 0.000001f) {
        gainRampTarget_ = target;
        gainRampFramesRemaining_ = std::max(
            1,
            outputFormat_.sampleRate * kGainRampDurationMs / 1000
        );
    }
    for (int frame = 0; frame < frames; ++frame) {
        if (gainRampFramesRemaining_ > 0) {
            applied += (gainRampTarget_ - applied) /
                static_cast<float>(gainRampFramesRemaining_--);
        } else {
            applied = gainRampTarget_;
        }
        uint8_t* outputFrame = output + static_cast<size_t>(frame) * outputFormat_.frameBytes;
        for (int channel = 0; channel < outputFormat_.channelCount; ++channel) {
            uint8_t* sample = outputFrame + channel * outputFormat_.subslotBytes;
            writeIntegerPcmSample(
                sample,
                outputFormat_.subslotBytes,
                outputFormat_.bitsPerSample,
                readIntegerPcmSample(
                    sample,
                    outputFormat_.subslotBytes,
                    outputFormat_.bitsPerSample
                ) * applied
            );
        }
    }
    appliedGain_.store(applied);
}

size_t PcmPipeline::fill(uint8_t* output, size_t bytes, bool playbackEnabled) {
    if (output == nullptr || bytes == 0) {
        return 0;
    }
    std::memset(output, 0, bytes);
    size_t read = 0;
    {
        std::lock_guard<std::mutex> guard(lock_);
        if (playbackEnabled) {
            read = readRingLocked(output, bytes);
            underrunBytes_ += static_cast<int64_t>(bytes - read);
            zeroFillBytes_ += static_cast<int64_t>(bytes - read);
        } else {
            pausedZeroFillBytes_ += static_cast<int64_t>(bytes);
        }
        outputBytes_ += static_cast<int64_t>(bytes);
    }
    applyGain(output, bytes);
    return read;
}

void PcmPipeline::clear() {
    std::lock_guard<std::mutex> guard(lock_);
    readIndex_ = 0;
    writeIndex_ = 0;
    levelBytes_ = 0;
    resamplePosition_ = 0.0;
    hasPreviousInputFrame_ = false;
    std::fill(previousInputFrame_.begin(), previousInputFrame_.end(), 0.0f);
}

void PcmPipeline::resetCounters() {
    std::lock_guard<std::mutex> guard(lock_);
    inputBytes_ = 0;
    outputBytes_ = 0;
    droppedBytes_ = 0;
    underrunBytes_ = 0;
    zeroFillBytes_ = 0;
    pausedZeroFillBytes_ = 0;
}

void PcmPipeline::addDroppedFrames(int64_t frames) {
    if (frames <= 0) {
        return;
    }
    std::lock_guard<std::mutex> guard(lock_);
    droppedBytes_ += frames * outputFormat_.frameBytes;
}

void PcmPipeline::setTargetGain(float gain) {
    targetGain_.store(std::clamp(gain, 0.0f, 1.0f));
}

size_t PcmPipeline::queuedFrames() const {
    std::lock_guard<std::mutex> guard(lock_);
    return outputFormat_.frameBytes > 0
        ? levelBytes_ / static_cast<size_t>(outputFormat_.frameBytes)
        : 0;
}

PcmPipelineSnapshot PcmPipeline::snapshot() const {
    std::lock_guard<std::mutex> guard(lock_);
    return {
        levelBytes_,
        ring_.size(),
        inputBytes_,
        outputBytes_,
        droppedBytes_,
        underrunBytes_,
        zeroFillBytes_,
        pausedZeroFillBytes_,
        targetGain_.load(),
        appliedGain_.load()
    };
}

} // namespace neri::usb
