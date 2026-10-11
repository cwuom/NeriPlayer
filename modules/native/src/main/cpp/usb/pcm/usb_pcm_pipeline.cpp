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
    PcmResampler newResampler;
    try {
        newRing.assign(ringBytes, 0);
        newResampler.configure(
            config.input.sampleRate,
            config.output.sampleRate,
            config.output.channelCount
        );
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
    resampler_ = std::move(newResampler);
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

bool PcmPipeline::canCopyIntegerFrames(
    int inputSampleBytes,
    int inputFrameBytes
) const {
    return inputFormat_.sampleRate == outputFormat_.sampleRate &&
        inputFormat_.channelCount == outputFormat_.channelCount &&
        isLittleEndianIntegerPcmEncoding(inputFormat_.encoding) &&
        integerPcmBitsForEncoding(inputFormat_.encoding) == outputFormat_.bitsPerSample &&
        inputSampleBytes == outputFormat_.subslotBytes &&
        inputFrameBytes == outputFormat_.frameBytes &&
        outputFormat_.frameBytes ==
            outputFormat_.channelCount * outputFormat_.subslotBytes;
}

// 大端整数只换字节序或加宽时按整数移位，32 位样本经 float 会丢掉低位
bool PcmPipeline::canWidenBigEndianIntegerFrames() const {
    const int inputBits = integerPcmBitsForEncoding(inputFormat_.encoding);
    return inputFormat_.sampleRate == outputFormat_.sampleRate &&
        inputFormat_.channelCount == outputFormat_.channelCount &&
        isBigEndianIntegerPcmEncoding(inputFormat_.encoding) &&
        inputBits > 0 &&
        inputBits <= outputFormat_.bitsPerSample &&
        outputFormat_.bitsPerSample <= std::min(32, outputFormat_.subslotBytes * 8);
}

void PcmPipeline::widenIntegerFrame(
    const uint8_t* source,
    uint8_t* target,
    int inputSampleBytes
) const {
    const int shift = outputFormat_.bitsPerSample - integerPcmBitsForEncoding(inputFormat_.encoding);
    for (int channel = 0; channel < outputFormat_.channelCount; ++channel) {
        const int64_t value = readEncodedIntegerPcmSample(
            source + channel * inputSampleBytes,
            inputFormat_.encoding
        );
        writeIntegerPcmValue(
            target + channel * outputFormat_.subslotBytes,
            outputFormat_.subslotBytes,
            outputFormat_.bitsPerSample,
            value * (int64_t { 1 } << shift)
        );
    }
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

    if (canCopyIntegerFrames(inputSampleBytes, inputFrameBytes)) {
        inputFrames = std::min(
            inputFrames,
            static_cast<int>(std::min<size_t>(
                freeOutputFrames,
                static_cast<size_t>(std::numeric_limits<int32_t>::max())
            ))
        );
        const size_t copyBytes = static_cast<size_t>(inputFrames) *
            static_cast<size_t>(inputFrameBytes);
        std::lock_guard<std::mutex> guard(lock_);
        if (freeBytesLocked() < copyBytes) {
            beginBackpressureLocked(monotonicMicros());
            return 0;
        }
        endBackpressureLocked(monotonicMicros());
        const size_t written = writeRingLocked(input, copyBytes);
        if (written != copyBytes) {
            return 0;
        }
        inputBytes_ += static_cast<int64_t>(copyBytes);
        return copyBytes;
    }

    if (inputFormat_.sampleRate != outputFormat_.sampleRate) {
        return writeResampled(
            input,
            inputFrames,
            inputSampleBytes,
            inputFrameBytes,
            freeOutputFrames,
            error
        );
    }
    inputFrames = std::min(
        inputFrames,
        static_cast<int>(std::min<size_t>(
            freeOutputFrames,
            static_cast<size_t>(std::numeric_limits<int32_t>::max())
        ))
    );
    return writeConverted(input, inputFrames, inputSampleBytes, inputFrameBytes, error);
}

size_t PcmPipeline::writeConverted(
    const uint8_t* input,
    int inputFrames,
    int inputSampleBytes,
    int inputFrameBytes,
    std::string* error
) {
    try {
        conversionBuffer_.assign(
            static_cast<size_t>(inputFrames) * static_cast<size_t>(outputFormat_.frameBytes),
            0
        );
    } catch (const std::bad_alloc&) {
        if (error != nullptr) {
            *error = "pcm_conversion_allocation_failed";
        }
        return 0;
    }
    const bool widenInteger = canWidenBigEndianIntegerFrames();
    for (int frame = 0; frame < inputFrames; ++frame) {
        const uint8_t* source = input + static_cast<size_t>(frame) * static_cast<size_t>(inputFrameBytes);
        uint8_t* target = conversionBuffer_.data() +
            static_cast<size_t>(frame) * static_cast<size_t>(outputFormat_.frameBytes);
        if (widenInteger) {
            widenIntegerFrame(source, target, inputSampleBytes);
            continue;
        }
        for (int channel = 0; channel < outputFormat_.channelCount; ++channel) {
            writeIntegerPcmSample(
                target + channel * outputFormat_.subslotBytes,
                outputFormat_.subslotBytes,
                outputFormat_.bitsPerSample,
                inputSampleFor(source, inputSampleBytes, channel)
            );
        }
    }
    const size_t consumedBytes = static_cast<size_t>(inputFrames) * static_cast<size_t>(inputFrameBytes);
    return commitConverted(consumedBytes) ? consumedBytes : 0;
}

size_t PcmPipeline::writeResampled(
    const uint8_t* input,
    int inputFrames,
    int inputSampleBytes,
    int inputFrameBytes,
    size_t freeOutputFrames,
    std::string* error
) {
    const int frames = resampler_.inputFramesForOutput(inputFrames, freeOutputFrames);
    if (frames <= 0) {
        return 0;
    }
    const int outputChannels = outputFormat_.channelCount;
    PcmResampler rollback;
    size_t produced = 0;
    try {
        rollback = resampler_;
        resampleInput_.resize(static_cast<size_t>(frames) * static_cast<size_t>(outputChannels));
        for (int frame = 0; frame < frames; ++frame) {
            const uint8_t* source = input + static_cast<size_t>(frame) * static_cast<size_t>(inputFrameBytes);
            float* target = resampleInput_.data() + static_cast<size_t>(frame) * static_cast<size_t>(outputChannels);
            for (int channel = 0; channel < outputChannels; ++channel) {
                target[channel] = inputSampleFor(source, inputSampleBytes, channel);
            }
        }
        resampleOutput_.clear();
        produced = resampler_.process(resampleInput_.data(), frames, &resampleOutput_);
        conversionBuffer_.assign(produced * static_cast<size_t>(outputFormat_.frameBytes), 0);
    } catch (const std::bad_alloc&) {
        resampler_ = std::move(rollback);
        if (error != nullptr) {
            *error = "pcm_conversion_allocation_failed";
        }
        return 0;
    }
    encodeResampledOutput(produced);
    const size_t consumedBytes = static_cast<size_t>(frames) * static_cast<size_t>(inputFrameBytes);
    if (!commitConverted(consumedBytes)) {
        resampler_ = std::move(rollback);
        return 0;
    }
    return consumedBytes;
}

bool PcmPipeline::drainResampler(std::string* error) {
    std::lock_guard<std::mutex> writeGuard(writeLock_);
    if (error != nullptr) {
        error->clear();
    }
    if (!resampler_.configured() || outputFormat_.frameBytes <= 0) {
        return true;
    }
    PcmResampler rollback;
    size_t produced = 0;
    try {
        rollback = resampler_;
        resampleOutput_.clear();
        produced = resampler_.drain(&resampleOutput_);
        conversionBuffer_.assign(produced * static_cast<size_t>(outputFormat_.frameBytes), 0);
    } catch (const std::bad_alloc&) {
        resampler_ = std::move(rollback);
        if (error != nullptr) {
            *error = "pcm_conversion_allocation_failed";
        }
        return false;
    }
    if (produced == 0) {
        return true;
    }
    encodeResampledOutput(produced);
    if (!commitConverted(0)) {
        resampler_ = std::move(rollback);
        return false;
    }
    return true;
}

void PcmPipeline::encodeResampledOutput(size_t frames) {
    const int outputChannels = outputFormat_.channelCount;
    for (size_t frame = 0; frame < frames; ++frame) {
        uint8_t* target = conversionBuffer_.data() + frame * static_cast<size_t>(outputFormat_.frameBytes);
        const float* samples = resampleOutput_.data() + frame * static_cast<size_t>(outputChannels);
        for (int channel = 0; channel < outputChannels; ++channel) {
            writeIntegerPcmSample(
                target + channel * outputFormat_.subslotBytes,
                outputFormat_.subslotBytes,
                outputFormat_.bitsPerSample,
                samples[channel]
            );
        }
    }
}

// 单声道复制到左右，立体声送单声道设备取平均；设备多出的声道补零，不复制最后一个声道
float PcmPipeline::inputSampleFor(
    const uint8_t* frame,
    int inputSampleBytes,
    int outputChannel
) const {
    const int inputChannels = std::max(1, inputFormat_.channelCount);
    if (outputFormat_.channelCount == 1 && inputChannels >= 2) {
        return 0.5f * (
            readEncodedPcmSample(frame, inputFormat_.encoding) +
            readEncodedPcmSample(frame + inputSampleBytes, inputFormat_.encoding)
        );
    }
    const int sourceChannel = inputChannels == 1
        ? (outputChannel < 2 ? 0 : -1)
        : (outputChannel < inputChannels ? outputChannel : -1);
    if (sourceChannel < 0) {
        return 0.0f;
    }
    return readEncodedPcmSample(frame + sourceChannel * inputSampleBytes, inputFormat_.encoding);
}

bool PcmPipeline::commitConverted(size_t consumedBytes) {
    std::lock_guard<std::mutex> guard(lock_);
    if (freeBytesLocked() < conversionBuffer_.size()) {
        beginBackpressureLocked(monotonicMicros());
        return false;
    }
    endBackpressureLocked(monotonicMicros());
    const size_t written = conversionBuffer_.empty()
        ? 0
        : writeRingLocked(conversionBuffer_.data(), conversionBuffer_.size());
    if (written != conversionBuffer_.size()) {
        return false;
    }
    inputBytes_ += static_cast<int64_t>(consumedBytes);
    return true;
}

void PcmPipeline::applyGain(uint8_t* output, size_t bytes) {
    const int frames = outputFormat_.frameBytes > 0
        ? static_cast<int>(bytes / static_cast<size_t>(outputFormat_.frameBytes))
        : 0;
    if (output == nullptr || frames <= 0 || applyBitPerfectGain(output, bytes)) {
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
    if (gainRampFramesRemaining_ == 0 &&
        std::abs(applied - 1.0f) <= 0.000001f &&
        std::abs(gainRampTarget_ - 1.0f) <= 0.000001f) {
        return;
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

bool PcmPipeline::applyBitPerfectGain(uint8_t* output, size_t bytes) {
    if (!bitPerfect_.load()) {
        return false;
    }
    const bool muted = targetGain_.load() <= 0.000001f;
    if (muted) {
        std::memset(output, 0, bytes);
    }
    const float applied = muted ? 0.0f : 1.0f;
    appliedGain_.store(applied);
    // 退出比特完美后从当前实际增益开始斜坡，避免增益跳变
    gainRampTarget_ = applied;
    gainRampFramesRemaining_ = 0;
    return true;
}

void PcmPipeline::fadeOutTrailingFrames(uint8_t* output, size_t bytes) const {
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
            if (!bitPerfect_.load()) {
                fadeOutTrailingFrames(output, read);
            }
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
    resampler_.reset();
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

void PcmPipeline::setBitPerfect(bool enabled) {
    bitPerfect_.store(enabled);
}

bool PcmPipeline::bitPerfect() const {
    return bitPerfect_.load();
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
    if (bitPerfect_.load()) {
        transportStartRampFramesRemaining_.store(0);
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
