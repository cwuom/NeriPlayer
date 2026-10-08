#include "dsp/neri_dsp_engine.h"

#include <chrono>
#include <cstring>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace neri::dsp {
namespace {

constexpr int kMaxBlockFrames = 128;
constexpr double kSmoothingTimeSeconds = 0.025;
constexpr float kGraphicBandQ = 1.41f;

// 处理期间把非规格化浮点冲成 0，避免混响和滤波器尾音拖慢小核
class ScopedDenormalFlush {
public:
    ScopedDenormalFlush() {
#if defined(__aarch64__)
        asm volatile("mrs %0, fpcr" : "=r"(saved_));
        const uint64_t flushed = saved_ | (uint64_t{1} << 24);
        asm volatile("msr fpcr, %0" : : "r"(flushed));
#elif defined(__x86_64__) || defined(__i386__)
        saved_ = _mm_getcsr();
        _mm_setcsr(saved_ | 0x8040u);
#endif
    }

    ~ScopedDenormalFlush() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" : : "r"(saved_));
#elif defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(saved_);
#endif
    }

    ScopedDenormalFlush(const ScopedDenormalFlush&) = delete;
    ScopedDenormalFlush& operator=(const ScopedDenormalFlush&) = delete;

private:
#if defined(__aarch64__)
    uint64_t saved_ = 0;
#elif defined(__x86_64__) || defined(__i386__)
    unsigned int saved_ = 0;
#endif
};

int bytesPerSample(int encoding) {
    switch (encoding) {
        case kEncodingPcm16:
            return 2;
        case kEncodingPcm24:
            return 3;
        case kEncodingPcm32:
        case kEncodingPcmFloat:
            return 4;
        default:
            return 0;
    }
}

int blockFramesFor(QualityMode quality) {
    return quality == QualityMode::High ? 32 : 64;
}

QualityMode qualityFromParam(float value) {
    const int mode = static_cast<int>(std::lround(value));
    if (mode <= static_cast<int>(QualityMode::Eco)) {
        return QualityMode::Eco;
    }
    if (mode >= static_cast<int>(QualityMode::High)) {
        return QualityMode::High;
    }
    return QualityMode::Balanced;
}

bool flag(float value) {
    return value > 0.5f;
}

} // namespace

bool Engine::configure(int sampleRate, int channelCount, int encoding) {
    const int sampleBytes = bytesPerSample(encoding);
    if (sampleRate < 8000 || sampleRate > 768000 || channelCount < 1 || channelCount > 2 || sampleBytes == 0) {
        sampleRate_ = 0;
        bytesPerSample_ = 0;
        return false;
    }
    sampleRate_ = sampleRate;
    channels_ = channelCount;
    encoding_ = encoding;
    bytesPerSample_ = sampleBytes;
    for (auto* buffer : {&left_, &right_, &dryLeft_, &dryRight_, &mid_}) {
        buffer->assign(kMaxBlockFrames, 0.0f);
    }
    rebuildStages();
    if (hasParams_) {
        applyParams();
        snapStages();
        mix_.snap(mix_.target());
    }
    stats_.sampleRate = sampleRate;
    return true;
}

void Engine::rebuildStages() {
    blockFrames_ = blockFramesFor(quality_);
    context_.sampleRate = static_cast<double>(sampleRate_);
    context_.quality = quality_;
    context_.smoothing = static_cast<float>(
        1.0 - std::exp(-static_cast<double>(blockFrames_) / (context_.sampleRate * kSmoothingTimeSeconds))
    );
    preamp_.setCoefficient(context_.smoothing);
    mix_.setCoefficient(context_.smoothing);
    preamp_.snap(1.0f);
    mix_.snap(0.0f);
    graphicEq_.configure(context_, kGraphicBandCount);
    parametricEq_.configure(context_, kParametricBandCount);
    tone_.configure(context_, 2);
    vocalClarity_.configure(context_, 3);
    virtualBass_.configure(context_);
    warmth_.configure(context_);
    exciter_.configure(context_);
    stereo_.configure(context_);
    crossfeed_.configure(context_);
    surround_.configure(context_);
    reverb_.configure(context_);
    compressor_.configure(context_);
    speaker_.configure(context_);
    output_.configure(context_);
    shapingError_.fill(0.0f);
}

bool Engine::setParams(const float* params, int count) {
    if (params == nullptr || count != kParamCount) {
        return false;
    }
    for (int i = 0; i < count; ++i) {
        if (!std::isfinite(params[i])) {
            return false;
        }
    }
    const bool wasIdle = idle();
    const QualityMode previousQuality = quality_;
    std::copy(params, params + count, params_.begin());
    hasParams_ = true;
    quality_ = qualityFromParam(params_[kQualityMode]);
    if (!configured()) {
        return true;
    }
    if (quality_ != previousQuality) {
        const float mix = mix_.value();
        rebuildStages();
        mix_.snap(mix);
    }
    applyParams();
    if (wasIdle && mix_.target() > 0.0f) {
        // 从直通恢复时让各模块直接落到目标值，淡入由总混合比负责
        resetStages();
        snapStages();
    }
    return true;
}

void Engine::applyParams() {
    const auto p = [this](int index) { return params_[static_cast<size_t>(index)]; };
    preamp_.setTarget(dbToGain(clampf(p(kPreampDb), -24.0f, 12.0f)));
    const bool graphicEnabled = flag(p(kGraphicEqEnabled));
    for (int band = 0; band < kGraphicBandCount; ++band) {
        graphicEq_.setBand(band, FilterBandTarget{
            graphicEnabled,
            FilterType::Peak,
            kGraphicBandFrequenciesHz[band],
            clampf(p(kGraphicEqGain0 + band), -15.0f, 15.0f),
            kGraphicBandQ
        });
    }
    const bool parametricEnabled = flag(p(kParametricEqEnabled));
    for (int band = 0; band < kParametricBandCount; ++band) {
        const int base = kParametricBand0 + band * kParametricBandStride;
        parametricEq_.setBand(band, FilterBandTarget{
            parametricEnabled && flag(p(base + kBandEnabled)),
            filterTypeFromParam(p(base + kBandType)),
            clampf(p(base + kBandFrequencyHz), 10.0f, 30000.0f),
            clampf(p(base + kBandGainDb), -24.0f, 24.0f),
            clampf(p(base + kBandQ), 0.1f, 24.0f)
        });
    }
    const float bassDb = clampf(p(kBassGainDb), -15.0f, 15.0f);
    const float trebleDb = clampf(p(kTrebleGainDb), -15.0f, 15.0f);
    tone_.setBand(0, FilterBandTarget{
        bassDb != 0.0f, FilterType::LowShelf, clampf(p(kBassFrequencyHz), 30.0f, 400.0f), bassDb, 0.707f
    });
    tone_.setBand(1, FilterBandTarget{
        trebleDb != 0.0f, FilterType::HighShelf, clampf(p(kTrebleFrequencyHz), 1000.0f, 16000.0f), trebleDb, 0.707f
    });
    virtualBass_.setTargets(p(kVirtualBassAmount), p(kVirtualBassFrequencyHz));
    warmth_.setTargets(p(kWarmthAmount));
    exciter_.setTargets(p(kExciterAmount), p(kExciterFrequencyHz));
    const float clarity = clampf(p(kVocalClarityAmount), 0.0f, 1.0f);
    const bool clarityOn = clarity > 0.0f;
    vocalClarity_.setBand(0, FilterBandTarget{clarityOn, FilterType::Peak, 3200.0f, 5.0f * clarity, 0.9f});
    vocalClarity_.setBand(1, FilterBandTarget{clarityOn, FilterType::Peak, 280.0f, -2.5f * clarity, 1.0f});
    vocalClarity_.setBand(2, FilterBandTarget{clarityOn, FilterType::HighShelf, 10000.0f, 1.5f * clarity, 0.707f});
    stereo_.setTargets(
        p(kVocalRemovalAmount),
        p(kStereoWidth),
        p(kMonoBassFrequencyHz),
        flag(p(kMonoMix)),
        flag(p(kChannelSwap))
    );
    crossfeed_.setTargets(p(kCrossfeedAmount));
    surround_.setTargets(p(kSurroundAmount));
    reverb_.setTargets(p(kReverbAmount), p(kReverbRoomSize), p(kReverbDamping), p(kReverbPreDelayMs));
    compressor_.setTargets(CompressorSettings{
        flag(p(kCompressorEnabled)),
        p(kCompressorThresholdDb),
        p(kCompressorRatio),
        p(kCompressorAttackMs),
        p(kCompressorReleaseMs),
        p(kCompressorKneeDb),
        p(kCompressorMakeupDb)
    });
    speaker_.setTargets(SpeakerSettings{
        flag(p(kSpeakerEnabled)),
        p(kSpeakerHighPassHz),
        p(kSpeakerBassHarmonics),
        p(kSpeakerLoudness),
        p(kSpeakerClarity),
        p(kSpeakerStereoExpand)
    });
    output_.setTargets(
        p(kOutputGainDb),
        flag(p(kLimiterEnabled)),
        p(kLimiterCeilingDb),
        p(kLimiterReleaseMs)
    );
    ditherEnabled_ = flag(p(kDitherEnabled));
    mix_.setTarget(flag(p(kMasterEnabled)) ? 1.0f : 0.0f);
}

void Engine::resetStages() {
    graphicEq_.reset();
    parametricEq_.reset();
    tone_.reset();
    vocalClarity_.reset();
    virtualBass_.reset();
    warmth_.reset();
    exciter_.reset();
    stereo_.reset();
    crossfeed_.reset();
    surround_.reset();
    reverb_.reset();
    compressor_.reset();
    speaker_.reset();
    output_.reset();
    shapingError_.fill(0.0f);
}

void Engine::snapStages() {
    preamp_.snap(preamp_.target());
    graphicEq_.snapToTargets();
    parametricEq_.snapToTargets();
    tone_.snapToTargets();
    vocalClarity_.snapToTargets();
    virtualBass_.snapToTargets();
    warmth_.snapToTargets();
    exciter_.snapToTargets();
    stereo_.snapToTargets();
    crossfeed_.snapToTargets();
    surround_.snapToTargets();
    reverb_.snapToTargets();
    speaker_.snapToTargets();
    output_.snapToTargets();
}

void Engine::reset() {
    if (!configured()) {
        return;
    }
    resetStages();
    snapStages();
}

void Engine::applyVocalClarity(int frames) {
    if (!vocalClarity_.active()) {
        return;
    }
    for (int i = 0; i < frames; ++i) {
        mid_[static_cast<size_t>(i)] = 0.5f * (left_[static_cast<size_t>(i)] + right_[static_cast<size_t>(i)]);
    }
    vocalClarity_.processMono(mid_.data(), frames);
    for (int i = 0; i < frames; ++i) {
        const auto index = static_cast<size_t>(i);
        const float delta = mid_[index] - 0.5f * (left_[index] + right_[index]);
        left_[index] += delta;
        right_[index] += delta;
    }
}

void Engine::runChain(int frames) {
    float* left = left_.data();
    float* right = right_.data();
    const float preampStart = preamp_.value();
    preamp_.advance();
    const float preampEnd = preamp_.value();
    applyGainRamp(left, frames, preampStart, preampEnd);
    applyGainRamp(right, frames, preampStart, preampEnd);
    graphicEq_.process(left, right, frames);
    parametricEq_.process(left, right, frames);
    tone_.process(left, right, frames);
    virtualBass_.process(left, right, frames);
    warmth_.process(left, right, frames);
    exciter_.process(left, right, frames);
    applyVocalClarity(frames);
    stereo_.process(left, right, frames);
    crossfeed_.process(left, right, frames);
    surround_.process(left, right, frames);
    reverb_.process(left, right, frames);
    speaker_.process(left, right, frames);
    compressor_.process(left, right, frames);
    output_.process(left, right, frames);
}

bool Engine::blockFinite(int frames) const {
    for (int i = 0; i < frames; ++i) {
        const auto index = static_cast<size_t>(i);
        if (!std::isfinite(left_[index]) || !std::isfinite(right_[index])) {
            return false;
        }
    }
    return true;
}

float Engine::readSample(const uint8_t* source) const {
    switch (encoding_) {
        case kEncodingPcm16: {
            int16_t value = 0;
            std::memcpy(&value, source, sizeof(value));
            return static_cast<float>(value) * (1.0f / 32768.0f);
        }
        case kEncodingPcm24: {
            const uint32_t raw = static_cast<uint32_t>(source[0]) |
                (static_cast<uint32_t>(source[1]) << 8) |
                (static_cast<uint32_t>(source[2]) << 16);
            const int32_t value = static_cast<int32_t>(raw << 8) / 256;
            return static_cast<float>(value) * (1.0f / 8388608.0f);
        }
        case kEncodingPcm32: {
            int32_t value = 0;
            std::memcpy(&value, source, sizeof(value));
            return static_cast<float>(static_cast<double>(value) * (1.0 / 2147483648.0));
        }
        case kEncodingPcmFloat:
        default: {
            float value = 0.0f;
            std::memcpy(&value, source, sizeof(value));
            return std::isfinite(value) ? value : 0.0f;
        }
    }
}

void Engine::decode(const uint8_t* input, int frames) {
    const int stride = frameBytes();
    for (int i = 0; i < frames; ++i) {
        const uint8_t* frame = input + static_cast<size_t>(i) * static_cast<size_t>(stride);
        const float l = readSample(frame);
        const float r = channels_ > 1 ? readSample(frame + bytesPerSample_) : l;
        left_[static_cast<size_t>(i)] = l;
        right_[static_cast<size_t>(i)] = r;
    }
}

float Engine::triangularDither() {
    ditherSeed_ = ditherSeed_ * 1664525u + 1013904223u;
    const float first = static_cast<float>(ditherSeed_ >> 8) * (1.0f / 16777216.0f);
    ditherSeed_ = ditherSeed_ * 1664525u + 1013904223u;
    const float second = static_cast<float>(ditherSeed_ >> 8) * (1.0f / 16777216.0f);
    return first - second;
}

void Engine::writeSample(uint8_t* target, float value, int channel, bool ditherAllowed) {
    switch (encoding_) {
        case kEncodingPcm16: {
            float scaled = value * 32768.0f;
            if (ditherAllowed && ditherEnabled_) {
                auto& error = shapingError_[static_cast<size_t>(channel)];
                const float shaped = quality_ == QualityMode::High ? scaled - error : scaled;
                const float quantized = std::nearbyint(shaped + triangularDither());
                error = quality_ == QualityMode::High ? clampf(quantized - shaped, -2.0f, 2.0f) : 0.0f;
                scaled = quantized;
            }
            const auto sample = static_cast<int16_t>(std::lround(clampf(scaled, -32768.0f, 32767.0f)));
            std::memcpy(target, &sample, sizeof(sample));
            return;
        }
        case kEncodingPcm24: {
            const auto sample = static_cast<int32_t>(
                std::lround(clampf(value * 8388608.0f, -8388608.0f, 8388607.0f))
            );
            const auto bits = static_cast<uint32_t>(sample);
            target[0] = static_cast<uint8_t>(bits & 0xffu);
            target[1] = static_cast<uint8_t>((bits >> 8) & 0xffu);
            target[2] = static_cast<uint8_t>((bits >> 16) & 0xffu);
            return;
        }
        case kEncodingPcm32: {
            const double scaled = std::clamp(
                static_cast<double>(value) * 2147483648.0, -2147483648.0, 2147483647.0
            );
            const auto sample = static_cast<int32_t>(std::llround(scaled));
            std::memcpy(target, &sample, sizeof(sample));
            return;
        }
        case kEncodingPcmFloat:
        default: {
            const float sample = clampf(value, -1.0f, 1.0f);
            std::memcpy(target, &sample, sizeof(sample));
            return;
        }
    }
}

void Engine::encode(uint8_t* output, int frames, bool ditherAllowed) {
    const int stride = frameBytes();
    for (int i = 0; i < frames; ++i) {
        uint8_t* frame = output + static_cast<size_t>(i) * static_cast<size_t>(stride);
        const auto index = static_cast<size_t>(i);
        if (channels_ == 1) {
            writeSample(frame, 0.5f * (left_[index] + right_[index]), 0, ditherAllowed);
        } else {
            writeSample(frame, left_[index], 0, ditherAllowed);
            writeSample(frame + bytesPerSample_, right_[index], 1, ditherAllowed);
        }
    }
}

int Engine::process(const uint8_t* input, uint8_t* output, size_t bytes) {
    if (!configured() || input == nullptr || output == nullptr) {
        return -1;
    }
    const size_t stride = static_cast<size_t>(frameBytes());
    const int totalFrames = static_cast<int>(bytes / stride);
    if (idle()) {
        if (output != input) {
            std::memmove(output, input, bytes);
        }
        return kStatusIdle;
    }
    ScopedDenormalFlush denormalFlush;
    const auto start = std::chrono::steady_clock::now();
    int status = kStatusProcessed;
    for (int offset = 0; offset < totalFrames; offset += blockFrames_) {
        const int frames = std::min(blockFrames_, totalFrames - offset);
        const size_t byteOffset = static_cast<size_t>(offset) * stride;
        decode(input + byteOffset, frames);
        std::copy(left_.begin(), left_.begin() + frames, dryLeft_.begin());
        std::copy(right_.begin(), right_.begin() + frames, dryRight_.begin());
        const float mixStart = mix_.value();
        mix_.advance();
        const float mixEnd = mix_.value();
        runChain(frames);
        if (!blockFinite(frames)) {
            resetStages();
            std::copy(dryLeft_.begin(), dryLeft_.begin() + frames, left_.begin());
            std::copy(dryRight_.begin(), dryRight_.begin() + frames, right_.begin());
            stats_.recoveries += 1;
            status |= kStatusRecovered;
        } else if (mixStart != 1.0f || mixEnd != 1.0f) {
            const float step = (mixEnd - mixStart) / static_cast<float>(frames);
            float mix = mixStart;
            for (int i = 0; i < frames; ++i) {
                const auto index = static_cast<size_t>(i);
                mix += step;
                left_[index] = dryLeft_[index] + mix * (left_[index] - dryLeft_[index]);
                right_[index] = dryRight_[index] + mix * (right_[index] - dryRight_[index]);
            }
        }
        encode(output + byteOffset, frames, mixEnd > 0.0f);
    }
    const auto elapsed = std::chrono::steady_clock::now() - start;
    stats_.processedFrames += totalFrames;
    stats_.processingNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
    if (idle()) {
        status |= kStatusIdle;
    }
    return status;
}

int Engine::computeActiveMask() const {
    int mask = 0;
    if (graphicEq_.active() || parametricEq_.active()) mask |= kStageEqualizer;
    if (tone_.active()) mask |= kStageTone;
    if (virtualBass_.active()) mask |= kStageBass;
    if (warmth_.active() || exciter_.active() || vocalClarity_.active()) mask |= kStageColor;
    if (stereo_.active() || crossfeed_.active()) mask |= kStageStereo;
    if (surround_.active()) mask |= kStageSpace;
    if (reverb_.active()) mask |= kStageReverb;
    if (compressor_.active()) mask |= kStageDynamics;
    if (speaker_.active()) mask |= kStageSpeaker;
    if (!idle()) mask |= kStageOutput;
    return mask;
}

EngineStats Engine::takeStats() {
    EngineStats snapshot = stats_;
    snapshot.maxLimiterReductionDb = output_.takeMaxReductionDb();
    snapshot.limiterEvents = output_.takeLimiterEvents();
    snapshot.compressorReductionDb = -compressor_.reductionDb();
    snapshot.activeStageMask = computeActiveMask();
    snapshot.sampleRate = sampleRate_;
    stats_.processedFrames = 0;
    stats_.processingNanos = 0;
    stats_.recoveries = 0;
    return snapshot;
}

} // namespace neri::dsp
