#pragma once

#include "dsp/neri_dsp_params.h"
#include "dsp/neri_dsp_primitives.h"

#include <array>
#include <vector>

namespace neri::dsp {

struct StageContext {
    double sampleRate = 48000.0;
    QualityMode quality = QualityMode::Balanced;
    // 每个处理块对参数做一次指数逼近，约 30 ms 完成过渡
    float smoothing = 1.0f;
};

struct FilterBandTarget {
    bool enabled = false;
    FilterType type = FilterType::Peak;
    float frequencyHz = 1000.0f;
    float gainDb = 0.0f;
    float q = 0.707f;
};

// 多段二阶滤波器组：参数变化时按块平滑，并跳过增益为 0 的段
class FilterBankStage {
public:
    void configure(const StageContext& context, int bandCount);
    void setBand(int index, const FilterBandTarget& target);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);
    void processMono(float* data, int frames);

private:
    struct Band {
        FilterBandTarget target;
        FilterType appliedType = FilterType::Peak;
        SmoothedValue frequency;
        SmoothedValue gain;
        SmoothedValue q;
        StereoBiquad filter;
        bool running = false;
    };

    bool advanceBand(Band& band);
    void redesign(Band& band);
    static bool neutral(const Band& band);

    StageContext context_;
    std::vector<Band> bands_;
};

class HarmonicBassStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount, float frequencyHz);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    void redesign();

    StageContext context_;
    SmoothedValue amount_;
    bool running_ = false;
    float frequencyHz_ = 100.0f;
    float designedFrequencyHz_ = -1.0f;
    BiquadCoefficients extract_;
    BiquadCoefficients harmonicHighPass_;
    BiquadCoefficients harmonicLowPass_;
    BiquadState extractA_;
    BiquadState extractB_;
    BiquadState highPass_;
    BiquadState lowPass_;
    EnvelopeFollower envelope_;
};

class WarmthStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    StageContext context_;
    SmoothedValue amount_;
    FilterBankStage tilt_;
    float dcCoefficient_ = 0.0f;
    std::array<float, 2> dcInput_{};
    std::array<float, 2> dcOutput_{};
};

class ExciterStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount, float frequencyHz);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    StageContext context_;
    SmoothedValue amount_;
    bool running_ = false;
    float frequencyHz_ = 5000.0f;
    float designedFrequencyHz_ = -1.0f;
    BiquadCoefficients highPass_;
    std::array<BiquadState, 2> bandState_{};
    std::array<BiquadState, 2> harmonicState_{};
};

class StereoImageStage {
public:
    void configure(const StageContext& context);
    void setTargets(
        float vocalRemoval,
        float width,
        float monoBassHz,
        bool monoMix,
        bool channelSwap
    );
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    void redesignMonoBass();

    StageContext context_;
    SmoothedValue vocalRemoval_;
    SmoothedValue width_;
    bool vocalRunning_ = false;
    float monoBassHz_ = 0.0f;
    float designedMonoBassHz_ = -1.0f;
    bool monoMix_ = false;
    bool channelSwap_ = false;
    BiquadCoefficients vocalLowPass_;
    BiquadState vocalLowState_;
    BiquadCoefficients sideHighPass_;
    BiquadState sideHighState_;
};

class CrossfeedStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    StageContext context_;
    SmoothedValue amount_;
    float lowPassCoefficient_ = 1.0f;
    int delaySamples_ = 1;
    bool running_ = false;
    DelayLine leftDelay_;
    DelayLine rightDelay_;
    float crossIntoLeft_ = 0.0f;
    float crossIntoRight_ = 0.0f;
    float ownLeft_ = 0.0f;
    float ownRight_ = 0.0f;
};

class SurroundStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    static constexpr int kTapCount = 6;

    StageContext context_;
    SmoothedValue amount_;
    bool running_ = false;
    DelayLine leftDelay_;
    DelayLine rightDelay_;
    std::array<int, kTapCount> tapDelays_{};
    float toneLowPass_ = 1.0f;
    float toneHighPass_ = 0.0f;
    std::array<float, 2> lowState_{};
    std::array<float, 2> highState_{};
};

class ReverbStage {
public:
    void configure(const StageContext& context);
    void setTargets(float amount, float roomSize, float damping, float preDelayMs);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    static constexpr int kCombCount = 8;
    static constexpr int kAllPassCount = 4;

    struct Comb {
        std::vector<float> buffer;
        int index = 0;
        float filterStore = 0.0f;
    };

    struct AllPass {
        std::vector<float> buffer;
        int index = 0;
    };

    float processChannel(float input, std::array<Comb, kCombCount>& combs,
                         std::array<AllPass, kAllPassCount>& allPasses);

    StageContext context_;
    SmoothedValue amount_;
    float roomSize_ = 0.5f;
    float damping_ = 0.5f;
    int preDelaySamples_ = 0;
    int activeCombs_ = kCombCount;
    int activeAllPasses_ = kAllPassCount;
    float wetScale_ = 1.0f;
    bool running_ = false;
    DelayLine preDelay_;
    std::array<Comb, kCombCount> leftCombs_{};
    std::array<Comb, kCombCount> rightCombs_{};
    std::array<AllPass, kAllPassCount> leftAllPasses_{};
    std::array<AllPass, kAllPassCount> rightAllPasses_{};
    float inputHighPass_ = 0.0f;
    float inputLowState_ = 0.0f;
};

struct CompressorSettings {
    bool enabled = false;
    float thresholdDb = -18.0f;
    float ratio = 2.0f;
    float attackMs = 10.0f;
    float releaseMs = 150.0f;
    float kneeDb = 6.0f;
    float makeupDb = 0.0f;
};

class CompressorCore {
public:
    void configure(double sampleRate, const CompressorSettings& settings, int detectorStride);
    void reset();
    // 返回该样本应施加的线性增益
    float gainFor(float level);
    float lastReductionDb() const {
        return reductionDb_;
    }

private:
    float computeTargetDb(float inputDb) const;

    CompressorSettings settings_;
    float attack_ = 1.0f;
    float release_ = 1.0f;
    float reductionDb_ = 0.0f;
    float appliedGain_ = 1.0f;
    float makeupGain_ = 1.0f;
    int detectorStride_ = 1;
    int strideCounter_ = 0;
};

class CompressorStage {
public:
    void configure(const StageContext& context);
    void setTargets(const CompressorSettings& settings);
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);
    float reductionDb() const {
        return core_.lastReductionDb();
    }

private:
    StageContext context_;
    CompressorSettings settings_;
    CompressorCore core_;
};

struct SpeakerSettings {
    bool enabled = false;
    float highPassHz = 150.0f;
    float bassHarmonics = 0.0f;
    float loudness = 0.0f;
    float clarity = 0.0f;
    float stereoExpand = 0.0f;
};

class SpeakerStage {
public:
    void configure(const StageContext& context);
    void setTargets(const SpeakerSettings& settings);
    void snapToTargets();
    void reset();
    bool active() const;
    void process(float* left, float* right, int frames);

private:
    void redesign();
    void processLoudness(float* left, float* right, int frames);

    StageContext context_;
    SpeakerSettings settings_;
    float designedHighPassHz_ = -1.0f;
    float designedLoudness_ = -1.0f;
    SmoothedValue expand_;
    StereoBiquad highPassA_;
    StereoBiquad highPassB_;
    HarmonicBassStage harmonics_;
    FilterBankStage clarity_;
    std::array<StereoBiquad, 2> crossoverLow_{};
    std::array<StereoBiquad, 2> crossoverHigh_{};
    CompressorCore lowBand_;
    CompressorCore highBand_;
    std::vector<float> lowLeft_;
    std::vector<float> lowRight_;
};

class OutputStage {
public:
    void configure(const StageContext& context);
    void setTargets(float outputGainDb, bool limiterEnabled, float ceilingDb, float releaseMs);
    void snapToTargets();
    void reset();
    void process(float* left, float* right, int frames);
    float takeMaxReductionDb();
    int64_t takeLimiterEvents();

private:
    StageContext context_;
    SmoothedValue gain_;
    bool limiterEnabled_ = true;
    float ceiling_ = 0.97f;
    float release_ = 1.0f;
    float limiterGain_ = 1.0f;
    float maxReductionDb_ = 0.0f;
    int64_t limiterEvents_ = 0;
};

} // namespace neri::dsp
