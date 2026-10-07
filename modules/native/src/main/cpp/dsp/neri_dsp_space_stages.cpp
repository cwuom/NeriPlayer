#include "dsp/neri_dsp_stages.h"

namespace neri::dsp {
namespace {

constexpr float kCrossfeedMaxGain = 0.5f;
constexpr double kCrossfeedLowPassHz = 700.0;
constexpr double kCrossfeedDelayMs = 0.28;
constexpr double kSurroundMaxDelayMs = 32.0;
constexpr std::array<float, 6> kSurroundTapMs = {5.3f, 8.9f, 13.7f, 17.1f, 23.3f, 29.9f};
constexpr std::array<float, 6> kSurroundTapGain = {0.32f, -0.27f, 0.22f, -0.18f, 0.14f, -0.10f};
constexpr std::array<int, 8> kCombTuning = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr std::array<int, 4> kAllPassTuning = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
constexpr float kReverbInputGain = 0.015f;
constexpr float kReverbMaxPreDelayMs = 120.0f;

int scaledLength(int base44100, double sampleRate) {
    return std::max(1, static_cast<int>(std::lround(base44100 * sampleRate / 44100.0)));
}

float lerp(float start, float end, int index, int frames) {
    return start + (end - start) * static_cast<float>(index + 1) / static_cast<float>(std::max(frames, 1));
}

float advanceRange(SmoothedValue& value, float* endValue) {
    const float start = value.value();
    value.advance();
    *endValue = value.value();
    return start;
}

} // namespace

void StereoImageStage::configure(const StageContext& context) {
    context_ = context;
    vocalRemoval_.setCoefficient(context.smoothing);
    width_.setCoefficient(context.smoothing);
    width_.snap(1.0f);
    vocalLowPass_ = designBiquad(FilterType::LowPass, context.sampleRate, 160.0, 0.0, 0.707);
    designedMonoBassHz_ = -1.0f;
    reset();
}

void StereoImageStage::setTargets(
    float vocalRemoval,
    float width,
    float monoBassHz,
    bool monoMix,
    bool channelSwap
) {
    vocalRemoval_.setTarget(clampf(vocalRemoval, 0.0f, 1.0f));
    width_.setTarget(clampf(width, 0.0f, 2.0f));
    monoBassHz_ = monoBassHz >= 20.0f ? clampf(monoBassHz, 20.0f, 300.0f) : 0.0f;
    monoMix_ = monoMix;
    channelSwap_ = channelSwap;
}

void StereoImageStage::snapToTargets() {
    vocalRemoval_.snap(vocalRemoval_.target());
    width_.snap(width_.target());
}

void StereoImageStage::reset() {
    vocalLowState_.reset();
    sideHighState_.reset();
    vocalRunning_ = false;
}

bool StereoImageStage::active() const {
    return vocalRemoval_.value() > 0.0f || vocalRemoval_.target() > 0.0f ||
        width_.value() != 1.0f || width_.target() != 1.0f ||
        monoBassHz_ > 0.0f || monoMix_ || channelSwap_;
}

void StereoImageStage::redesignMonoBass() {
    if (monoBassHz_ > 0.0f) {
        sideHighPass_ = designBiquad(FilterType::HighPass, context_.sampleRate, monoBassHz_, 0.0, 0.707);
    }
    sideHighState_.reset();
    designedMonoBassHz_ = monoBassHz_;
}

void StereoImageStage::process(float* left, float* right, int frames) {
    if (!active()) {
        return;
    }
    if (designedMonoBassHz_ != monoBassHz_) {
        redesignMonoBass();
    }
    float vocalEnd = 0.0f;
    const float vocalStart = advanceRange(vocalRemoval_, &vocalEnd);
    float widthEnd = 1.0f;
    const float widthStart = advanceRange(width_, &widthEnd);
    const bool vocalActive = vocalStart > 0.0f || vocalEnd > 0.0f;
    if (vocalActive && !vocalRunning_) {
        vocalLowState_.reset();
    }
    vocalRunning_ = vocalActive;
    for (int i = 0; i < frames; ++i) {
        float l = left[i];
        float r = right[i];
        if (vocalActive) {
            // 只减去中置声道的中高频，保留鼓和贝斯的低频
            const float mid = 0.5f * (l + r);
            const float midHigh = mid - vocalLowState_.process(vocalLowPass_, mid);
            const float vocal = lerp(vocalStart, vocalEnd, i, frames);
            l -= vocal * midHigh;
            r -= vocal * midHigh;
        }
        const float mid = 0.5f * (l + r);
        float side = 0.5f * (l - r);
        if (monoBassHz_ > 0.0f) {
            side = sideHighState_.process(sideHighPass_, side);
        }
        side *= monoMix_ ? 0.0f : lerp(widthStart, widthEnd, i, frames);
        l = mid + side;
        r = mid - side;
        left[i] = channelSwap_ ? r : l;
        right[i] = channelSwap_ ? l : r;
    }
}

void CrossfeedStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    lowPassCoefficient_ = onePoleCoefficient(context.sampleRate, kCrossfeedLowPassHz);
    delaySamples_ = std::max(1, static_cast<int>(std::lround(context.sampleRate * kCrossfeedDelayMs * 0.001)));
    leftDelay_.resize(delaySamples_ + 1);
    rightDelay_.resize(delaySamples_ + 1);
    reset();
}

void CrossfeedStage::setTargets(float amount) {
    amount_.setTarget(clampf(amount, 0.0f, 1.0f));
}

void CrossfeedStage::snapToTargets() {
    amount_.snap(amount_.target());
}

void CrossfeedStage::reset() {
    leftDelay_.reset();
    rightDelay_.reset();
    crossIntoLeft_ = 0.0f;
    crossIntoRight_ = 0.0f;
    ownLeft_ = 0.0f;
    ownRight_ = 0.0f;
}

bool CrossfeedStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f;
}

void CrossfeedStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
    }
    float amountEnd = 0.0f;
    const float amountStart = advanceRange(amount_, &amountEnd);
    const float a = lowPassCoefficient_;
    for (int i = 0; i < frames; ++i) {
        const float l = left[i];
        const float r = right[i];
        leftDelay_.push(l);
        rightDelay_.push(r);
        crossIntoLeft_ += a * (rightDelay_.read(delaySamples_) - crossIntoLeft_);
        crossIntoRight_ += a * (leftDelay_.read(delaySamples_) - crossIntoRight_);
        ownLeft_ += a * (l - ownLeft_);
        ownRight_ += a * (r - ownRight_);
        // 只混入两耳低频的差值，居中的人声和整体响度保持不变
        const float gain = kCrossfeedMaxGain * lerp(amountStart, amountEnd, i, frames);
        left[i] = l + gain * (crossIntoLeft_ - ownLeft_);
        right[i] = r + gain * (crossIntoRight_ - ownRight_);
    }
}

void SurroundStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    const int maxDelay = static_cast<int>(std::ceil(context.sampleRate * kSurroundMaxDelayMs * 0.001));
    leftDelay_.resize(maxDelay);
    rightDelay_.resize(maxDelay);
    for (int tap = 0; tap < kTapCount; ++tap) {
        tapDelays_[static_cast<size_t>(tap)] = std::max(
            1,
            static_cast<int>(std::lround(context.sampleRate * kSurroundTapMs[static_cast<size_t>(tap)] * 0.001))
        );
    }
    toneLowPass_ = onePoleCoefficient(context.sampleRate, 6500.0);
    toneHighPass_ = onePoleCoefficient(context.sampleRate, 220.0);
    reset();
}

void SurroundStage::setTargets(float amount) {
    amount_.setTarget(clampf(amount, 0.0f, 1.0f));
}

void SurroundStage::snapToTargets() {
    amount_.snap(amount_.target());
}

void SurroundStage::reset() {
    leftDelay_.reset();
    rightDelay_.reset();
    lowState_.fill(0.0f);
    highState_.fill(0.0f);
}

bool SurroundStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f;
}

void SurroundStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
    }
    float amountEnd = 0.0f;
    const float amountStart = advanceRange(amount_, &amountEnd);
    for (int i = 0; i < frames; ++i) {
        const float amount = lerp(amountStart, amountEnd, i, frames);
        const float l = left[i];
        const float r = right[i];
        float toned[2] = {l, r};
        for (int channel = 0; channel < 2; ++channel) {
            auto index = static_cast<size_t>(channel);
            lowState_[index] += toneLowPass_ * (toned[channel] - lowState_[index]);
            highState_[index] += toneHighPass_ * (lowState_[index] - highState_[index]);
            toned[channel] = lowState_[index] - highState_[index];
        }
        leftDelay_.push(toned[0]);
        rightDelay_.push(toned[1]);
        float wetLeft = 0.0f;
        float wetRight = 0.0f;
        for (int tap = 0; tap < kTapCount; ++tap) {
            const auto index = static_cast<size_t>(tap);
            const int delay = tapDelays_[index];
            const float gain = kSurroundTapGain[index];
            const bool cross = (tap % 2) == 0;
            wetLeft += gain * (cross ? rightDelay_.read(delay) : leftDelay_.read(delay));
            wetRight += gain * (cross ? leftDelay_.read(delay) : rightDelay_.read(delay));
        }
        const float mid = 0.5f * (l + r);
        const float side = 0.5f * (l - r) * (1.0f + 0.5f * amount);
        left[i] = mid + side + 0.6f * amount * wetLeft;
        right[i] = mid - side + 0.6f * amount * wetRight;
    }
}

void ReverbStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    const bool eco = context.quality == QualityMode::Eco;
    activeCombs_ = eco ? kCombCount / 2 : kCombCount;
    activeAllPasses_ = eco ? kAllPassCount / 2 : kAllPassCount;
    wetScale_ = eco ? 1.41f : 1.0f;
    for (int i = 0; i < kCombCount; ++i) {
        const auto index = static_cast<size_t>(i);
        leftCombs_[index].buffer.assign(static_cast<size_t>(scaledLength(kCombTuning[index], context.sampleRate)), 0.0f);
        rightCombs_[index].buffer.assign(
            static_cast<size_t>(scaledLength(kCombTuning[index] + kStereoSpread, context.sampleRate)),
            0.0f
        );
    }
    for (int i = 0; i < kAllPassCount; ++i) {
        const auto index = static_cast<size_t>(i);
        leftAllPasses_[index].buffer.assign(
            static_cast<size_t>(scaledLength(kAllPassTuning[index], context.sampleRate)),
            0.0f
        );
        rightAllPasses_[index].buffer.assign(
            static_cast<size_t>(scaledLength(kAllPassTuning[index] + kStereoSpread, context.sampleRate)),
            0.0f
        );
    }
    preDelay_.resize(static_cast<int>(std::ceil(context.sampleRate * kReverbMaxPreDelayMs * 0.001)));
    inputHighPass_ = onePoleCoefficient(context.sampleRate, 160.0);
    reset();
}

void ReverbStage::setTargets(float amount, float roomSize, float damping, float preDelayMs) {
    amount_.setTarget(clampf(amount, 0.0f, 1.0f));
    roomSize_ = clampf(roomSize, 0.0f, 1.0f);
    damping_ = clampf(damping, 0.0f, 1.0f);
    const float delayMs = clampf(preDelayMs, 0.0f, kReverbMaxPreDelayMs);
    preDelaySamples_ = std::min(
        preDelay_.capacity(),
        static_cast<int>(std::lround(context_.sampleRate * delayMs * 0.001))
    );
}

void ReverbStage::snapToTargets() {
    amount_.snap(amount_.target());
}

void ReverbStage::reset() {
    for (auto* combs : {&leftCombs_, &rightCombs_}) {
        for (auto& comb : *combs) {
            std::fill(comb.buffer.begin(), comb.buffer.end(), 0.0f);
            comb.index = 0;
            comb.filterStore = 0.0f;
        }
    }
    for (auto* allPasses : {&leftAllPasses_, &rightAllPasses_}) {
        for (auto& allPass : *allPasses) {
            std::fill(allPass.buffer.begin(), allPass.buffer.end(), 0.0f);
            allPass.index = 0;
        }
    }
    preDelay_.reset();
    inputLowState_ = 0.0f;
}

bool ReverbStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f;
}

float ReverbStage::processChannel(
    float input,
    std::array<Comb, kCombCount>& combs,
    std::array<AllPass, kAllPassCount>& allPasses
) {
    const float feedback = 0.7f + 0.28f * roomSize_;
    const float damp = damping_ * 0.4f;
    float output = 0.0f;
    for (int i = 0; i < activeCombs_; ++i) {
        Comb& comb = combs[static_cast<size_t>(i)];
        const float delayed = comb.buffer[static_cast<size_t>(comb.index)];
        comb.filterStore = delayed * (1.0f - damp) + comb.filterStore * damp;
        comb.buffer[static_cast<size_t>(comb.index)] = input + comb.filterStore * feedback;
        comb.index = (comb.index + 1) % static_cast<int>(comb.buffer.size());
        output += delayed;
    }
    for (int i = 0; i < activeAllPasses_; ++i) {
        AllPass& allPass = allPasses[static_cast<size_t>(i)];
        const float delayed = allPass.buffer[static_cast<size_t>(allPass.index)];
        allPass.buffer[static_cast<size_t>(allPass.index)] = output + delayed * 0.5f;
        allPass.index = (allPass.index + 1) % static_cast<int>(allPass.buffer.size());
        output = delayed - output;
    }
    return output;
}

void ReverbStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
    }
    float amountEnd = 0.0f;
    const float amountStart = advanceRange(amount_, &amountEnd);
    for (int i = 0; i < frames; ++i) {
        const float mono = (left[i] + right[i]) * kReverbInputGain;
        inputLowState_ += inputHighPass_ * (mono - inputLowState_);
        float input = mono - inputLowState_;
        if (preDelaySamples_ > 0) {
            preDelay_.push(input);
            input = preDelay_.read(preDelaySamples_);
        }
        const float wetLeft = processChannel(input, leftCombs_, leftAllPasses_);
        const float wetRight = processChannel(input, rightCombs_, rightAllPasses_);
        const float wet = 1.5f * wetScale_ * lerp(amountStart, amountEnd, i, frames);
        left[i] += wet * wetLeft;
        right[i] += wet * wetRight;
    }
}

} // namespace neri::dsp
