#include "dsp/neri_dsp_engine.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <limits>
#include <vector>

using namespace neri::dsp;

namespace {

constexpr int kSampleRate = 48000;

#define CHECK(condition)                                                                  \
    do {                                                                                  \
        if (!(condition)) {                                                               \
            std::fprintf(stderr, "%s:%d CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            return false;                                                                 \
        }                                                                                 \
    } while (0)

using Params = std::array<float, kParamCount>;

Params neutralParams() {
    Params p{};
    p[kMasterEnabled] = 1.0f;
    p[kQualityMode] = 1.0f;
    p[kLimiterCeilingDb] = -1.0f;
    p[kLimiterReleaseMs] = 80.0f;
    for (int band = 0; band < kParametricBandCount; ++band) {
        const int base = kParametricBand0 + band * kParametricBandStride;
        p[base + kBandFrequencyHz] = 1000.0f;
        p[base + kBandQ] = 0.707f;
    }
    p[kBassFrequencyHz] = 100.0f;
    p[kTrebleFrequencyHz] = 8000.0f;
    p[kVirtualBassFrequencyHz] = 100.0f;
    p[kExciterFrequencyHz] = 5000.0f;
    p[kStereoWidth] = 1.0f;
    p[kReverbRoomSize] = 0.5f;
    p[kReverbDamping] = 0.5f;
    p[kCompressorThresholdDb] = -18.0f;
    p[kCompressorRatio] = 2.0f;
    p[kCompressorAttackMs] = 10.0f;
    p[kCompressorReleaseMs] = 150.0f;
    p[kCompressorKneeDb] = 6.0f;
    p[kSpeakerHighPassHz] = 150.0f;
    return p;
}

std::vector<float> stereoSine(float frequencyHz, float amplitude, int frames) {
    std::vector<float> samples(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float value = amplitude * static_cast<float>(std::sin(2.0 * kPi * frequencyHz * i / kSampleRate));
        samples[static_cast<size_t>(i) * 2] = value;
        samples[static_cast<size_t>(i) * 2 + 1] = value;
    }
    return samples;
}

std::vector<float> processFloat(Engine& engine, const std::vector<float>& input) {
    std::vector<float> output(input.size());
    const size_t bytes = input.size() * sizeof(float);
    engine.process(
        reinterpret_cast<const uint8_t*>(input.data()),
        reinterpret_cast<uint8_t*>(output.data()),
        bytes
    );
    return output;
}

double rms(const std::vector<float>& samples, size_t startSample) {
    double sum = 0.0;
    size_t count = 0;
    for (size_t i = startSample; i < samples.size(); ++i) {
        sum += static_cast<double>(samples[i]) * samples[i];
        ++count;
    }
    return count == 0 ? 0.0 : std::sqrt(sum / static_cast<double>(count));
}

float peak(const std::vector<float>& samples, size_t startSample) {
    float value = 0.0f;
    for (size_t i = startSample; i < samples.size(); ++i) {
        value = std::max(value, std::fabs(samples[i]));
    }
    return value;
}

bool configured(Engine& engine, const Params& params, int channels = 2, int encoding = kEncodingPcmFloat) {
    CHECK(engine.setParams(params.data(), kParamCount));
    CHECK(engine.configure(kSampleRate, channels, encoding));
    return true;
}

bool testDisabledEngineIsBitExactPassthrough() {
    Params params = neutralParams();
    params[kMasterEnabled] = 0.0f;
    params[kGraphicEqEnabled] = 1.0f;
    params[kGraphicEqGain0 + 5] = 9.0f;
    Engine engine;
    CHECK(configured(engine, params, 2, kEncodingPcm16));
    std::vector<int16_t> input(4096);
    for (size_t i = 0; i < input.size(); ++i) {
        input[i] = static_cast<int16_t>((static_cast<int>(i) * 7919) % 65536 - 32768);
    }
    std::vector<int16_t> output(input.size());
    const int status = engine.process(
        reinterpret_cast<const uint8_t*>(input.data()),
        reinterpret_cast<uint8_t*>(output.data()),
        input.size() * sizeof(int16_t)
    );
    CHECK((status & Engine::kStatusIdle) != 0);
    CHECK(std::memcmp(input.data(), output.data(), input.size() * sizeof(int16_t)) == 0);
    return true;
}

bool testNeutralEnabledChainIsTransparentForFloat() {
    Engine engine;
    CHECK(configured(engine, neutralParams()));
    const auto input = stereoSine(440.0f, 0.5f, 8192);
    const auto output = processFloat(engine, input);
    for (size_t i = 0; i < input.size(); ++i) {
        CHECK(std::fabs(input[i] - output[i]) < 1.0e-6f);
    }
    return true;
}

bool testGraphicBandBoostMatchesRequestedGain() {
    Params params = neutralParams();
    params[kGraphicEqEnabled] = 1.0f;
    params[kGraphicEqGain0 + 5] = 6.0f;
    Engine engine;
    CHECK(configured(engine, params));
    const auto input = stereoSine(1000.0f, 0.05f, kSampleRate);
    const auto output = processFloat(engine, input);
    const double gainDb = 20.0 * std::log10(rms(output, kSampleRate / 2) / rms(input, kSampleRate / 2));
    CHECK(std::fabs(gainDb - 6.0) < 0.6);
    return true;
}

bool testParameterChangeIsSmoothedWithoutJumps() {
    Params params = neutralParams();
    params[kGraphicEqEnabled] = 1.0f;
    Engine engine;
    CHECK(configured(engine, params));
    auto input = stereoSine(250.0f, 0.2f, 4096);
    processFloat(engine, input);
    params[kGraphicEqGain0 + 3] = 12.0f;
    CHECK(engine.setParams(params.data(), kParamCount));
    const auto output = processFloat(engine, input);
    float largestStep = 0.0f;
    for (size_t i = 2; i < output.size(); i += 2) {
        largestStep = std::max(largestStep, std::fabs(output[i] - output[i - 2]));
    }
    // 250 Hz、+12 dB 的正弦相邻样本差不超过约 0.026，平滑失败时会出现明显跳变
    CHECK(largestStep < 0.05f);
    return true;
}

bool testLimiterKeepsPeaksBelowCeiling() {
    Params params = neutralParams();
    params[kOutputGainDb] = 12.0f;
    params[kLimiterEnabled] = 1.0f;
    params[kLimiterCeilingDb] = -1.0f;
    Engine engine;
    CHECK(configured(engine, params));
    const auto output = processFloat(engine, stereoSine(200.0f, 0.5f, kSampleRate));
    CHECK(peak(output, 0) <= dbToGain(-1.0f) + 1.0e-4f);
    const EngineStats stats = engine.takeStats();
    CHECK(stats.maxLimiterReductionDb > 5.0f);
    CHECK(stats.limiterEvents > 0);
    CHECK(stats.processedFrames == kSampleRate);
    return true;
}

bool testSpeakerHighPassRemovesUnplayableBass() {
    Params params = neutralParams();
    params[kSpeakerEnabled] = 1.0f;
    params[kSpeakerHighPassHz] = 200.0f;
    Engine engine;
    CHECK(configured(engine, params));
    const auto input = stereoSine(45.0f, 0.3f, kSampleRate);
    const auto output = processFloat(engine, input);
    const double attenuationDb = 20.0 * std::log10(rms(output, kSampleRate / 2) / rms(input, kSampleRate / 2));
    CHECK(attenuationDb < -20.0);
    return true;
}

bool testZeroWidthCollapsesToMono() {
    Params params = neutralParams();
    params[kStereoWidth] = 0.0f;
    Engine engine;
    CHECK(configured(engine, params));
    std::vector<float> input(4096);
    for (size_t i = 0; i < input.size(); i += 2) {
        input[i] = 0.4f;
        input[i + 1] = -0.2f;
    }
    const auto output = processFloat(engine, input);
    for (size_t i = 2048; i < output.size(); i += 2) {
        CHECK(std::fabs(output[i] - output[i + 1]) < 1.0e-4f);
    }
    return true;
}

bool testIntegerFormatsRoundTripWhenNeutral() {
    for (const int encoding : {kEncodingPcm16, kEncodingPcm24, kEncodingPcm32}) {
        Engine engine;
        CHECK(configured(engine, neutralParams(), 2, encoding));
        const int sampleBytes = encoding == kEncodingPcm16 ? 2 : (encoding == kEncodingPcm24 ? 3 : 4);
        std::vector<uint8_t> input(static_cast<size_t>(sampleBytes) * 2 * 1024);
        for (size_t i = 0; i < input.size(); ++i) {
            input[i] = static_cast<uint8_t>((i * 37 + 11) & 0xff);
        }
        // 最高字节控制在小幅度，避免触到软削波阈值
        for (size_t i = static_cast<size_t>(sampleBytes) - 1; i < input.size(); i += static_cast<size_t>(sampleBytes)) {
            input[i] = static_cast<uint8_t>(input[i] & 0x3f);
        }
        std::vector<uint8_t> output(input.size());
        engine.process(input.data(), output.data(), input.size());
        if (encoding == kEncodingPcm32) {
            for (size_t i = 0; i < input.size(); i += 4) {
                int32_t a = 0;
                int32_t b = 0;
                std::memcpy(&a, &input[i], 4);
                std::memcpy(&b, &output[i], 4);
                CHECK(std::abs(static_cast<int64_t>(a) - b) <= 256);
            }
        } else {
            CHECK(std::memcmp(input.data(), output.data(), input.size()) == 0);
        }
    }
    return true;
}

bool testMonoInputIsSupported() {
    Params params = neutralParams();
    params[kGraphicEqEnabled] = 1.0f;
    params[kGraphicEqGain0 + 5] = 6.0f;
    Engine engine;
    CHECK(configured(engine, params, 1, kEncodingPcmFloat));
    std::vector<float> input(kSampleRate);
    for (int i = 0; i < kSampleRate; ++i) {
        input[static_cast<size_t>(i)] = 0.05f * static_cast<float>(std::sin(2.0 * kPi * 1000.0 * i / kSampleRate));
    }
    const auto output = processFloat(engine, input);
    const double gainDb = 20.0 * std::log10(rms(output, kSampleRate / 2) / rms(input, kSampleRate / 2));
    CHECK(std::fabs(gainDb - 6.0) < 0.6);
    return true;
}

bool testEverythingAtExtremesStaysFiniteAndBounded() {
    for (const float quality : {0.0f, 1.0f, 2.0f}) {
        Params params = neutralParams();
        params[kQualityMode] = quality;
        params[kPreampDb] = 12.0f;
        params[kGraphicEqEnabled] = 1.0f;
        for (int band = 0; band < kGraphicBandCount; ++band) {
            params[kGraphicEqGain0 + band] = band % 2 == 0 ? 15.0f : -15.0f;
        }
        params[kParametricEqEnabled] = 1.0f;
        for (int band = 0; band < kParametricBandCount; ++band) {
            const int base = kParametricBand0 + band * kParametricBandStride;
            params[base + kBandEnabled] = 1.0f;
            params[base + kBandType] = static_cast<float>(band % 7);
            params[base + kBandFrequencyHz] = 30.0f * static_cast<float>(band + 1) * static_cast<float>(band + 1);
            params[base + kBandGainDb] = 24.0f;
            params[base + kBandQ] = band % 2 == 0 ? 24.0f : 0.1f;
        }
        params[kBassGainDb] = 15.0f;
        params[kTrebleGainDb] = 15.0f;
        params[kVirtualBassAmount] = 1.0f;
        params[kWarmthAmount] = 1.0f;
        params[kExciterAmount] = 1.0f;
        params[kVocalClarityAmount] = 1.0f;
        params[kVocalRemovalAmount] = 1.0f;
        params[kStereoWidth] = 2.0f;
        params[kMonoBassFrequencyHz] = 120.0f;
        params[kCrossfeedAmount] = 1.0f;
        params[kSurroundAmount] = 1.0f;
        params[kReverbAmount] = 1.0f;
        params[kReverbRoomSize] = 1.0f;
        params[kReverbDamping] = 0.0f;
        params[kReverbPreDelayMs] = 120.0f;
        params[kCompressorEnabled] = 1.0f;
        params[kCompressorRatio] = 20.0f;
        params[kCompressorThresholdDb] = -60.0f;
        params[kCompressorMakeupDb] = 24.0f;
        params[kSpeakerEnabled] = 1.0f;
        params[kSpeakerBassHarmonics] = 1.0f;
        params[kSpeakerLoudness] = 1.0f;
        params[kSpeakerClarity] = 1.0f;
        params[kSpeakerStereoExpand] = 1.0f;
        params[kOutputGainDb] = 24.0f;
        params[kLimiterEnabled] = 1.0f;
        params[kDitherEnabled] = 1.0f;
        Engine engine;
        CHECK(configured(engine, params));
        uint32_t seed = 1234567u;
        std::vector<float> input(static_cast<size_t>(kSampleRate) * 2);
        for (int second = 0; second < 5; ++second) {
            for (auto& sample : input) {
                seed = seed * 1103515245u + 12345u;
                sample = (static_cast<float>(seed >> 8) / 16777216.0f - 0.5f) * 1.9f;
            }
            const auto output = processFloat(engine, input);
            for (const float sample : output) {
                CHECK(std::isfinite(sample));
                CHECK(std::fabs(sample) <= 1.0f);
            }
        }
    }
    return true;
}

bool testInvalidParamsAreRejected() {
    Engine engine;
    Params params = neutralParams();
    CHECK(!engine.setParams(params.data(), kParamCount - 1));
    params[kGraphicEqGain0] = std::numeric_limits<float>::quiet_NaN();
    CHECK(!engine.setParams(params.data(), kParamCount));
    CHECK(!engine.configure(kSampleRate, 6, kEncodingPcm16));
    CHECK(!engine.configure(kSampleRate, 2, 3));
    CHECK(engine.process(nullptr, nullptr, 0) == -1);
    return true;
}

bool testDisableFadesOutToIdle() {
    Params params = neutralParams();
    params[kGraphicEqEnabled] = 1.0f;
    params[kGraphicEqGain0 + 4] = 6.0f;
    Engine engine;
    CHECK(configured(engine, params));
    const auto input = stereoSine(500.0f, 0.1f, 4096);
    processFloat(engine, input);
    params[kMasterEnabled] = 0.0f;
    CHECK(engine.setParams(params.data(), kParamCount));
    CHECK(!engine.idle());
    for (int i = 0; i < 20 && !engine.idle(); ++i) {
        processFloat(engine, input);
    }
    CHECK(engine.idle());
    return true;
}

} // namespace

int main() {
    const std::array<std::pair<const char*, std::function<bool()>>, 12> tests = {{
        {"disabled_passthrough", testDisabledEngineIsBitExactPassthrough},
        {"neutral_transparent", testNeutralEnabledChainIsTransparentForFloat},
        {"graphic_band_gain", testGraphicBandBoostMatchesRequestedGain},
        {"smoothed_change", testParameterChangeIsSmoothedWithoutJumps},
        {"limiter_ceiling", testLimiterKeepsPeaksBelowCeiling},
        {"speaker_high_pass", testSpeakerHighPassRemovesUnplayableBass},
        {"zero_width_mono", testZeroWidthCollapsesToMono},
        {"integer_round_trip", testIntegerFormatsRoundTripWhenNeutral},
        {"mono_input", testMonoInputIsSupported},
        {"extremes_bounded", testEverythingAtExtremesStaysFiniteAndBounded},
        {"invalid_params", testInvalidParamsAreRejected},
        {"disable_fades_to_idle", testDisableFadesOutToIdle},
    }};
    int failures = 0;
    for (const auto& [name, test] : tests) {
        const bool passed = test();
        std::printf("%s %s\n", passed ? "PASS" : "FAIL", name);
        failures += passed ? 0 : 1;
    }
    return failures == 0 ? 0 : 1;
}
