#pragma once

namespace neri::dsp {

// Kotlin 侧 NeriDspParams 必须与这里的下标逐项一致，改动时同时提升版本号
constexpr int kParamLayoutVersion = 1;
constexpr int kGraphicBandCount = 10;
constexpr int kParametricBandCount = 10;
constexpr int kParametricBandStride = 5;

enum ParametricBandField : int {
    kBandEnabled = 0,
    kBandType = 1,
    kBandFrequencyHz = 2,
    kBandGainDb = 3,
    kBandQ = 4,
};

enum Param : int {
    kMasterEnabled = 0,
    kQualityMode = 1,
    kPreampDb = 2,
    kOutputGainDb = 3,
    kLimiterEnabled = 4,
    kLimiterCeilingDb = 5,
    kLimiterReleaseMs = 6,
    kDitherEnabled = 7,
    kGraphicEqEnabled = 8,
    kGraphicEqGain0 = 9,
    kParametricEqEnabled = kGraphicEqGain0 + kGraphicBandCount,
    kParametricBand0 = kParametricEqEnabled + 1,
    kBassGainDb = kParametricBand0 + kParametricBandCount * kParametricBandStride,
    kBassFrequencyHz,
    kTrebleGainDb,
    kTrebleFrequencyHz,
    kVirtualBassAmount,
    kVirtualBassFrequencyHz,
    kWarmthAmount,
    kExciterAmount,
    kExciterFrequencyHz,
    kVocalClarityAmount,
    kVocalRemovalAmount,
    kStereoWidth,
    kMonoBassFrequencyHz,
    kMonoMix,
    kChannelSwap,
    kCrossfeedAmount,
    kSurroundAmount,
    kReverbAmount,
    kReverbRoomSize,
    kReverbDamping,
    kReverbPreDelayMs,
    kCompressorEnabled,
    kCompressorThresholdDb,
    kCompressorRatio,
    kCompressorAttackMs,
    kCompressorReleaseMs,
    kCompressorKneeDb,
    kCompressorMakeupDb,
    kSpeakerEnabled,
    kSpeakerHighPassHz,
    kSpeakerBassHarmonics,
    kSpeakerLoudness,
    kSpeakerClarity,
    kSpeakerStereoExpand,
    kParamCount
};

static_assert(kParamCount == 104, "parameter layout changed: update Kotlin NeriDspParams");
static_assert(kBassGainDb == 70 && kStereoWidth == 81, "parameter layout changed: update Kotlin NeriDspParams");
static_assert(kCompressorEnabled == 91 && kSpeakerEnabled == 98, "parameter layout changed: update Kotlin NeriDspParams");

enum class QualityMode : int {
    Eco = 0,
    Balanced = 1,
    High = 2,
};

inline constexpr float kGraphicBandFrequenciesHz[kGraphicBandCount] = {
    31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
};

} // namespace neri::dsp
