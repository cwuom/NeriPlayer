#pragma once

#include "dsp/neri_dsp_stages.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace neri::dsp {

// 与 Android AudioFormat 编码常量一致
enum PcmEncoding : int {
    kEncodingPcm16 = 2,
    kEncodingPcmFloat = 4,
    kEncodingPcm24 = 21,
    kEncodingPcm32 = 22,
};

enum ActiveStage : int {
    kStageEqualizer = 1 << 0,
    kStageTone = 1 << 1,
    kStageBass = 1 << 2,
    kStageColor = 1 << 3,
    kStageStereo = 1 << 4,
    kStageSpace = 1 << 5,
    kStageReverb = 1 << 6,
    kStageDynamics = 1 << 7,
    kStageSpeaker = 1 << 8,
    kStageOutput = 1 << 9,
};

struct EngineStats {
    int64_t processedFrames = 0;
    int64_t processingNanos = 0;
    float maxLimiterReductionDb = 0.0f;
    int64_t limiterEvents = 0;
    float compressorReductionDb = 0.0f;
    int activeStageMask = 0;
    int sampleRate = 0;
    int64_t recoveries = 0;
};

class Engine final {
public:
    static constexpr int kStatusIdle = 1;
    static constexpr int kStatusProcessed = 2;
    static constexpr int kStatusRecovered = 4;

    bool configure(int sampleRate, int channelCount, int encoding);
    bool setParams(const float* params, int count);
    void reset();
    // 输出与输入字节数相同、零延迟；返回 kStatus* 位组合，未配置时返回 -1
    int process(const uint8_t* input, uint8_t* output, size_t bytes);
    EngineStats takeStats();

    bool configured() const {
        return sampleRate_ > 0 && bytesPerSample_ > 0;
    }

    bool idle() const {
        return mix_.value() == 0.0f && mix_.target() == 0.0f;
    }

    int frameBytes() const {
        return channels_ * bytesPerSample_;
    }

private:
    void rebuildStages();
    void applyParams();
    void resetStages();
    void snapStages();
    void runChain(int frames);
    void applyVocalClarity(int frames);
    bool blockFinite(int frames) const;
    void decode(const uint8_t* input, int frames);
    void encode(uint8_t* output, int frames, bool ditherAllowed);
    float readSample(const uint8_t* source) const;
    void writeSample(uint8_t* target, float value, int channel, bool ditherAllowed);
    float triangularDither();
    int computeActiveMask() const;

    std::array<float, kParamCount> params_{};
    bool hasParams_ = false;
    int sampleRate_ = 0;
    int channels_ = 0;
    int encoding_ = 0;
    int bytesPerSample_ = 0;
    QualityMode quality_ = QualityMode::Balanced;
    int blockFrames_ = 64;
    bool ditherEnabled_ = false;
    StageContext context_;

    SmoothedValue preamp_;
    SmoothedValue mix_;
    FilterBankStage graphicEq_;
    FilterBankStage parametricEq_;
    FilterBankStage tone_;
    FilterBankStage vocalClarity_;
    HarmonicBassStage virtualBass_;
    WarmthStage warmth_;
    ExciterStage exciter_;
    StereoImageStage stereo_;
    CrossfeedStage crossfeed_;
    SurroundStage surround_;
    ReverbStage reverb_;
    CompressorStage compressor_;
    SpeakerStage speaker_;
    OutputStage output_;

    std::vector<float> left_;
    std::vector<float> right_;
    std::vector<float> dryLeft_;
    std::vector<float> dryRight_;
    std::vector<float> mid_;
    uint32_t ditherSeed_ = 0x9e3779b9u;
    std::array<float, 2> shapingError_{};
    EngineStats stats_;
};

} // namespace neri::dsp
