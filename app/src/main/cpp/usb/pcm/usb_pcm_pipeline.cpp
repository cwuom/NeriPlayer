#include "usb_pcm_pipeline.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>

namespace neri::usb {
namespace {

constexpr int kGainRampDurationMs = 80;
constexpr int kUnderrunEdgeRampMs = 5;
constexpr double kPositionEpsilon = 0.000000001;
constexpr int64_t kMaximumRingBufferBytes = 64LL * 1024LL * 1024LL;

bool calculateRingBytes(
    const PcmOutputFormat& output,
    int ringDurationMs,
    int transferBytes,
    int transferCount,
    size_t* ringBytes,
    std::string* error
) {
    if (ringBytes == nullptr || output.sampleRate <= 0 || output.frameBytes <= 0 ||
        ringDurationMs <= 0 || transferBytes <= 0 || transferCount <= 0) {
        if (error != nullptr) {
            *error = "invalid_pcm_ring_configuration";
        }
        return false;
    }
    const int64_t requestedBytes =
        static_cast<int64_t>(output.sampleRate) * output.frameBytes * ringDurationMs / 1000;
    const int64_t transferFloor =
        static_cast<int64_t>(transferBytes) * transferCount * 3;
    int64_t boundedBytes = std::max<int64_t>(
        output.frameBytes,
        std::max(transferFloor, requestedBytes)
    );
    boundedBytes = std::min(boundedBytes, kMaximumRingBufferBytes);
    boundedBytes -= boundedBytes % output.frameBytes;
    if (boundedBytes < output.frameBytes) {
        boundedBytes = output.frameBytes;
    }
    *ringBytes = static_cast<size_t>(boundedBytes);
    return true;
}

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

int64_t monotonicMicros() {
    using Clock = std::chrono::steady_clock;
    return std::chrono::duration_cast<std::chrono::microseconds>(
        Clock::now().time_since_epoch()
    ).count();
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
    size_t ringBytes = 0;
    if (!calculateRingBytes(
            config.output,
            config.ringDurationMs,
            config.transferBytes,
            config.transferCount,
            &ringBytes,
            error
        )) {
        return false;
    }
    std::vector<uint8_t> newRing;
    std::vector<float> newPreviousInputFrame;
    try {
        newRing.assign(ringBytes, 0);
        newPreviousInputFrame.assign(static_cast<size_t>(config.input.channelCount), 0.0f);
    } catch (const std::bad_alloc&) {
        if (error != nullptr) {
            *error = "pcm_ring_allocation_failed";
        }
        return false;
    }

    std::lock_guard<std::mutex> writeGuard(writeLock_);
    std::lock_guard<std::mutex> guard(lock_);
    outputFormat_ = config.output;
    inputFormat_ = config.input;
    ring_.swap(newRing);
    readIndex_ = 0;
    writeIndex_ = 0;
    levelBytes_ = 0;
    resamplePosition_ = 0.0;
    hasPreviousInputFrame_ = false;
    previousInputFrame_.swap(newPreviousInputFrame);
    conversionBuffer_.clear();
    inputBytes_ = 0;
    outputBytes_ = 0;
    droppedBytes_ = 0;
    underrunBytes_ = 0;
    zeroFillBytes_ = 0;
    pausedZeroFillBytes_ = 0;
    signalOutputFrames_ = 0;
    silentOutputFrames_ = 0;
    signalOutputBytes_ = 0;
    backpressureEvents_ = 0;
    backpressureTotalUs_ = 0;
    backpressureStartedAtUs_ = 0;
    backpressureMaxUs_ = 0;
    maxLevelBytes_ = 0;
    outputPeak_ = 0.0f;
    lastOutputPeak_ = 0.0f;
    channel0OutputPeak_ = 0.0f;
    channel1OutputPeak_ = 0.0f;
    lastChannel0OutputPeak_ = 0.0f;
    lastChannel1OutputPeak_ = 0.0f;
    const float target = std::clamp(targetGain_.load(), 0.0f, 1.0f);
    appliedGain_.store(target);
    gainRampTarget_ = target;
    gainRampFramesRemaining_ = 0;
    transportStartRampFramesTotal_ = 0;
    transportStartRampFramesRemaining_.store(0);
    return true;
}

bool PcmPipeline::resizeRingDuration(
    int ringDurationMs,
    int transferBytes,
    int transferCount,
    std::string* error
) {
    if (error != nullptr) {
        error->clear();
    }
    std::lock_guard<std::mutex> writeGuard(writeLock_);
    PcmOutputFormat outputFormat;
    size_t currentRingBytes = 0;
    {
        std::lock_guard<std::mutex> guard(lock_);
        outputFormat = outputFormat_;
        currentRingBytes = ring_.size();
    }
    size_t ringBytes = 0;
    if (!calculateRingBytes(
            outputFormat,
            ringDurationMs,
            transferBytes,
            transferCount,
            &ringBytes,
            error
        )) {
        return false;
    }
    if (ringBytes == currentRingBytes) {
        return true;
    }

    std::vector<uint8_t> resizedRing;
    try {
        resizedRing.assign(ringBytes, 0);
    } catch (const std::bad_alloc&) {
        if (error != nullptr) {
            *error = "pcm_ring_allocation_failed";
        }
        return false;
    }
    std::lock_guard<std::mutex> guard(lock_);
    const size_t retainedBytes = std::min(levelBytes_, ringBytes);
    if (retainedBytes > 0 && !ring_.empty()) {
        const size_t first = std::min(retainedBytes, ring_.size() - readIndex_);
        std::memcpy(resizedRing.data(), ring_.data() + readIndex_, first);
        const size_t second = retainedBytes - first;
        if (second > 0) {
            std::memcpy(resizedRing.data() + first, ring_.data(), second);
        }
    }
    droppedBytes_ += static_cast<int64_t>(levelBytes_ - retainedBytes);
    ring_.swap(resizedRing);
    readIndex_ = 0;
    writeIndex_ = retainedBytes % ring_.size();
    levelBytes_ = retainedBytes;
    maxLevelBytes_ = std::max(retainedBytes, std::min(maxLevelBytes_, ring_.size()));
    if (freeBytesLocked() > 0) {
        endBackpressureLocked(monotonicMicros());
    }
    return true;
}

size_t PcmPipeline::freeBytesLocked() const {
    return ring_.empty() ? 0 : ring_.size() - levelBytes_;
}

void PcmPipeline::beginBackpressureLocked(int64_t nowUs) {
    if (backpressureStartedAtUs_ > 0) {
        return;
    }
    backpressureStartedAtUs_ = nowUs;
    ++backpressureEvents_;
    maxLevelBytes_ = std::max(maxLevelBytes_, levelBytes_);
}

void PcmPipeline::endBackpressureLocked(int64_t nowUs) {
    if (backpressureStartedAtUs_ <= 0) {
        return;
    }
    const int64_t elapsedUs = std::max<int64_t>(0, nowUs - backpressureStartedAtUs_);
    backpressureTotalUs_ += elapsedUs;
    backpressureMaxUs_ = std::max(backpressureMaxUs_, elapsedUs);
    backpressureStartedAtUs_ = 0;
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
    maxLevelBytes_ = std::max(maxLevelBytes_, levelBytes_);
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
    if (freeBytesLocked() > 0) {
        endBackpressureLocked(monotonicMicros());
    }
    return readable;
}

size_t PcmPipeline::write(const uint8_t* input, size_t inputBytes, std::string* error) {
    std::lock_guard<std::mutex> writeGuard(writeLock_);
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
        if (freeOutputFrames == 0) {
            beginBackpressureLocked(monotonicMicros());
        } else {
            endBackpressureLocked(monotonicMicros());
        }
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
    const size_t outputFrameCapacity = inputFormat_.sampleRate == outputFormat_.sampleRate
        ? static_cast<size_t>(inputFrames)
        : std::min(
            freeOutputFrames,
            countOutputFrames(
                inputFrames,
                localPosition,
                localHasPrevious,
                ratio,
                freeOutputFrames
            )
        );
    const size_t requiredOutputBytes =
        outputFrameCapacity * static_cast<size_t>(outputFormat_.frameBytes);
    try {
        conversionBuffer_.clear();
        conversionBuffer_.reserve(requiredOutputBytes);
    } catch (const std::bad_alloc&) {
        if (error != nullptr) {
            *error = "pcm_conversion_allocation_failed";
        }
        return 0;
    }
    auto& output = conversionBuffer_;

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
        beginBackpressureLocked(monotonicMicros());
        return 0;
    }
    endBackpressureLocked(monotonicMicros());
    const size_t written = output.empty() ? 0 : writeRingLocked(output.data(), output.size());
    resamplePosition_ = localPosition;
    previousInputFrame_ = std::move(localPrevious);
    hasPreviousInputFrame_ = true;
    const auto consumedBytes = static_cast<size_t>(inputFrames * inputFrameBytes);
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

void PcmPipeline::fadeOutTrailingFrames(uint8_t* output, size_t bytes) {
    const int frames = outputFormat_.frameBytes > 0
        ? static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes))
        : 0;
    if (output == nullptr || frames <= 0) {
        return;
    }
    const int rampFrames = std::clamp(
        outputFormat_.sampleRate * kUnderrunEdgeRampMs / 1000,
        1,
        frames
    );
    const int firstRampFrame = frames - rampFrames;
    for (int frame = firstRampFrame; frame < frames; ++frame) {
        const int remainingFrames = frames - frame - 1;
        const float gain = rampFrames > 1
            ? static_cast<float>(remainingFrames) / static_cast<float>(rampFrames - 1)
            : 0.0f;
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
                ) * gain
            );
        }
    }
}

void PcmPipeline::markSilentOutputLocked() {
    appliedGain_.store(0.0f);
    gainRampTarget_ = 0.0f;
    gainRampFramesRemaining_ = 0;
}

void PcmPipeline::updateOutputSignalStatsLocked(const uint8_t* output, size_t bytes) {
    const int frames = outputFormat_.frameBytes > 0
        ? static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes))
        : 0;
    if (output == nullptr || frames <= 0) {
        lastOutputPeak_ = 0.0f;
        lastChannel0OutputPeak_ = 0.0f;
        lastChannel1OutputPeak_ = 0.0f;
        return;
    }

    int64_t signalFrames = 0;
    int64_t signalBytes = 0;
    float peak = 0.0f;
    float channel0Peak = 0.0f;
    float channel1Peak = 0.0f;
    for (int frame = 0; frame < frames; ++frame) {
        bool frameHasSignal = false;
        const uint8_t* outputFrame = output + static_cast<size_t>(frame) * outputFormat_.frameBytes;
        for (int channel = 0; channel < outputFormat_.channelCount; ++channel) {
            const uint8_t* sample = outputFrame + channel * outputFormat_.subslotBytes;
            const float value = readIntegerPcmSample(
                sample,
                outputFormat_.subslotBytes,
                outputFormat_.bitsPerSample
            );
            const float absoluteValue = std::abs(value);
            peak = std::max(peak, absoluteValue);
            if (channel == 0) {
                channel0Peak = std::max(channel0Peak, absoluteValue);
            } else if (channel == 1) {
                channel1Peak = std::max(channel1Peak, absoluteValue);
            }
            if (absoluteValue > 0.000001f) {
                frameHasSignal = true;
            }
        }
        if (frameHasSignal) {
            ++signalFrames;
            signalBytes += outputFormat_.frameBytes;
        }
    }
    signalOutputFrames_ += signalFrames;
    silentOutputFrames_ += frames - signalFrames;
    signalOutputBytes_ += signalBytes;
    lastOutputPeak_ = peak;
    outputPeak_ = std::max(outputPeak_, peak);
    lastChannel0OutputPeak_ = channel0Peak;
    lastChannel1OutputPeak_ = channel1Peak;
    channel0OutputPeak_ = std::max(channel0OutputPeak_, channel0Peak);
    channel1OutputPeak_ = std::max(channel1OutputPeak_, channel1Peak);
}

size_t PcmPipeline::fill(uint8_t* output, size_t bytes, bool playbackEnabled) {
    if (output == nullptr || bytes == 0) {
        return 0;
    }
    std::memset(output, 0, bytes);
    size_t read = 0;
    std::lock_guard<std::mutex> guard(lock_);
    if (playbackEnabled) {
        read = readRingLocked(output, bytes);
        underrunBytes_ += static_cast<int64_t>(bytes - read);
        zeroFillBytes_ += static_cast<int64_t>(bytes - read);
    } else {
        pausedZeroFillBytes_ += static_cast<int64_t>(bytes);
    }
    outputBytes_ += static_cast<int64_t>(bytes);
    const bool silentOutput = !playbackEnabled || read == 0;
    const bool partialUnderrun = playbackEnabled && read > 0 && read < bytes;
    if (silentOutput) {
        markSilentOutputLocked();
        // 暂停或欠采样时缓冲区已知全零，跳过逐样本 decode 直接计数
        const int silentFrames = outputFormat_.frameBytes > 0
            ? static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes))
            : 0;
        silentOutputFrames_ += silentFrames;
        lastOutputPeak_ = 0.0f;
        lastChannel0OutputPeak_ = 0.0f;
        lastChannel1OutputPeak_ = 0.0f;
    } else {
        applyGain(output, partialUnderrun ? read : bytes);
        if (partialUnderrun) {
            fadeOutTrailingFrames(output, read);
            markSilentOutputLocked();
        }
        updateOutputSignalStatsLocked(output, bytes);
    }
    return read;
}

void PcmPipeline::clear() {
    std::lock_guard<std::mutex> writeGuard(writeLock_);
    std::lock_guard<std::mutex> guard(lock_);
    readIndex_ = 0;
    writeIndex_ = 0;
    levelBytes_ = 0;
    endBackpressureLocked(monotonicMicros());
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
    signalOutputFrames_ = 0;
    silentOutputFrames_ = 0;
    signalOutputBytes_ = 0;
    backpressureEvents_ = 0;
    backpressureTotalUs_ = 0;
    backpressureStartedAtUs_ = 0;
    backpressureMaxUs_ = 0;
    maxLevelBytes_ = levelBytes_;
    outputPeak_ = 0.0f;
    lastOutputPeak_ = 0.0f;
    channel0OutputPeak_ = 0.0f;
    channel1OutputPeak_ = 0.0f;
    lastChannel0OutputPeak_ = 0.0f;
    lastChannel1OutputPeak_ = 0.0f;
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

void PcmPipeline::armTransportStartRamp() {
    std::lock_guard<std::mutex> guard(lock_);
    transportStartRampFramesTotal_ = std::max(
        1,
        outputFormat_.sampleRate * kGainRampDurationMs / 1000
    );
    transportStartRampFramesRemaining_.store(transportStartRampFramesTotal_);
}

void PcmPipeline::applyTransportStartRamp(uint8_t* output, size_t bytes) {
    if (output == nullptr || transportStartRampFramesRemaining_.load() <= 0) {
        return;
    }
    std::lock_guard<std::mutex> guard(lock_);
    int remainingFrames = transportStartRampFramesRemaining_.load();
    if (outputFormat_.frameBytes <= 0 || remainingFrames <= 0) {
        return;
    }
    const int frames = static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes));
    for (int frame = 0; frame < frames && remainingFrames > 0; ++frame) {
        const int completedFrames = transportStartRampFramesTotal_ -
            remainingFrames;
        const float gain = transportStartRampFramesTotal_ > 1
            ? static_cast<float>(completedFrames) /
                static_cast<float>(transportStartRampFramesTotal_ - 1)
            : 0.0f;
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
                ) * gain
            );
        }
        --remainingFrames;
    }
    transportStartRampFramesRemaining_.store(remainingFrames);
}

size_t PcmPipeline::queuedFrames() const {
    std::lock_guard<std::mutex> guard(lock_);
    return outputFormat_.frameBytes > 0
        ? levelBytes_ / static_cast<size_t>(outputFormat_.frameBytes)
        : 0;
}

PcmPipelineSnapshot PcmPipeline::snapshot() const {
    std::lock_guard<std::mutex> guard(lock_);
    const int64_t nowUs = monotonicMicros();
    const int64_t currentBackpressureUs = backpressureStartedAtUs_ > 0
        ? std::max<int64_t>(0, nowUs - backpressureStartedAtUs_)
        : 0;
    const int64_t totalBackpressureUs = backpressureTotalUs_ + currentBackpressureUs;
    const int64_t maxBackpressureUs = std::max(backpressureMaxUs_, currentBackpressureUs);
    return {
        levelBytes_,
        ring_.size(),
        freeBytesLocked(),
        maxLevelBytes_,
        inputBytes_,
        outputBytes_,
        droppedBytes_,
        underrunBytes_,
        zeroFillBytes_,
        pausedZeroFillBytes_,
        signalOutputFrames_,
        silentOutputFrames_,
        signalOutputBytes_,
        backpressureEvents_,
        totalBackpressureUs,
        currentBackpressureUs,
        maxBackpressureUs,
        outputPeak_,
        lastOutputPeak_,
        channel0OutputPeak_,
        channel1OutputPeak_,
        lastChannel0OutputPeak_,
        lastChannel1OutputPeak_,
        targetGain_.load(),
        appliedGain_.load()
    };
}

} // namespace neri::usb
