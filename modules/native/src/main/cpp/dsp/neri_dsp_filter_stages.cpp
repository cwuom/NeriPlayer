#include "dsp/neri_dsp_stages.h"

namespace neri::dsp {
namespace {

bool isGainFilter(FilterType type) {
    return type == FilterType::Peak || type == FilterType::LowShelf || type == FilterType::HighShelf;
}

float rampValue(SmoothedValue& value, float* endValue) {
    const float start = value.value();
    value.advance();
    *endValue = value.value();
    return start;
}

} // namespace

void FilterBankStage::configure(const StageContext& context, int bandCount) {
    context_ = context;
    bands_.assign(static_cast<size_t>(std::max(bandCount, 0)), Band{});
    for (auto& band : bands_) {
        band.frequency.setCoefficient(context.smoothing);
        band.gain.setCoefficient(context.smoothing);
        band.q.setCoefficient(context.smoothing);
        band.frequency.snap(band.target.frequencyHz);
        band.gain.snap(0.0f);
        band.q.snap(band.target.q);
    }
}

void FilterBankStage::setBand(int index, const FilterBandTarget& target) {
    if (index < 0 || index >= static_cast<int>(bands_.size())) {
        return;
    }
    Band& band = bands_[static_cast<size_t>(index)];
    const bool typeChanged = target.type != band.appliedType;
    band.target = target;
    const float gainTarget = target.enabled ? target.gainDb : 0.0f;
    band.frequency.setTarget(target.frequencyHz);
    band.gain.setTarget(gainTarget);
    band.q.setTarget(target.q);
    if (typeChanged) {
        band.appliedType = target.type;
        band.frequency.snap(target.frequencyHz);
        band.gain.snap(gainTarget);
        band.q.snap(target.q);
        band.running = false;
    }
}

void FilterBankStage::snapToTargets() {
    for (auto& band : bands_) {
        band.frequency.snap(band.frequency.target());
        band.gain.snap(band.gain.target());
        band.q.snap(band.q.target());
        band.running = false;
    }
}

void FilterBankStage::reset() {
    for (auto& band : bands_) {
        band.filter.reset();
        band.running = false;
    }
}

bool FilterBankStage::neutral(const Band& band) {
    if (!isGainFilter(band.appliedType)) {
        return !band.target.enabled;
    }
    return band.gain.settled() && band.gain.value() == 0.0f;
}

bool FilterBankStage::active() const {
    for (const auto& band : bands_) {
        if (!neutral(band)) {
            return true;
        }
    }
    return false;
}

bool FilterBankStage::boosting() const {
    for (const auto& band : bands_) {
        if (isGainFilter(band.appliedType) && (band.gain.value() > 0.0f || band.gain.target() > 0.0f)) {
            return true;
        }
    }
    return false;
}

bool FilterBankStage::advanceBand(Band& band) {
    bool changed = band.frequency.advance();
    changed = band.gain.advance() || changed;
    changed = band.q.advance() || changed;
    return changed;
}

void FilterBankStage::redesign(Band& band) {
    band.filter.coefficients = designBiquad(
        band.appliedType,
        context_.sampleRate,
        band.frequency.value(),
        band.gain.value(),
        band.q.value()
    );
}

void FilterBankStage::process(float* left, float* right, int frames) {
    for (auto& band : bands_) {
        bool changed = advanceBand(band);
        if (neutral(band)) {
            band.running = false;
            continue;
        }
        if (!band.running) {
            band.filter.reset();
            band.running = true;
            changed = true;
        }
        if (changed) {
            redesign(band);
        }
        if (right != nullptr) {
            band.filter.processBlock(left, right, frames);
        } else {
            band.filter.left.processBlock(band.filter.coefficients, left, frames);
        }
    }
}

void FilterBankStage::processMono(float* data, int frames) {
    process(data, nullptr, frames);
}

namespace {

// 增强部分叠到原信号上不越过约 -1.2 dBFS，留一点给后级的温暖、激励等小增益
constexpr float kEnhancementCeiling = 0.87f;
// 保持时间覆盖约 25 Hz 的一个周期，收量在低频周期内保持不变
constexpr double kEnhancementHoldSeconds = 0.04;
constexpr double kEnhancementReleaseMs = 250.0;
constexpr float kSubsonicTransparentHz = 5.0f;

// 让 x + g * added 不越过上限的最大 g；原信号自己已经越线时为 0，交给输出限幅器
float enhancementAllowance(float dry, float added) {
    const float magnitude = std::fabs(added);
    if (magnitude < 1.0e-7f) {
        return 1.0f;
    }
    const float limit = kEnhancementCeiling - (added > 0.0f ? dry : -dry);
    return clampf(limit / magnitude, 0.0f, 1.0f);
}

} // namespace

float bassSubsonicFrequencyHz(float gainDb, float frequencyHz) {
    if (gainDb <= 0.0f) {
        return 0.0f;
    }
    const float target = clampf(0.25f * frequencyHz, 16.0f, 30.0f);
    const float engage = clampf(gainDb / 4.0f, 0.0f, 1.0f);
    return kSubsonicTransparentHz + (target - kSubsonicTransparentHz) * engage;
}

void BassBoostStage::configure(const StageContext& context) {
    context_ = context;
    gainDb_.setCoefficient(context.smoothing);
    frequency_.setCoefficient(context.smoothing);
    gainDb_.snap(0.0f);
    frequency_.snap(100.0f);
    designedFrequencyHz_ = -1.0f;
    designedSubsonicHz_ = -1.0f;
    running_ = false;
    reset();
}

void BassBoostStage::setTargets(float gainDb, float frequencyHz) {
    gainDb_.setTarget(gainDb);
    frequency_.setTarget(frequencyHz);
}

void BassBoostStage::snapToTargets() {
    gainDb_.snap(gainDb_.target());
    frequency_.snap(frequency_.target());
    running_ = false;
}

void BassBoostStage::reset() {
    shelf_.reset();
    subsonic_.reset();
}

bool BassBoostStage::active() const {
    return gainDb_.value() != 0.0f || gainDb_.target() != 0.0f;
}

bool BassBoostStage::boosting() const {
    return gainDb_.value() > 0.0f || gainDb_.target() > 0.0f;
}

void BassBoostStage::redesign(float subsonicHz) {
    shelf_.coefficients = designBiquad(
        FilterType::LowShelf, context_.sampleRate, frequency_.value(), gainDb_.value(), 0.707
    );
    if (subsonicHz > 0.0f && subsonicHz != designedSubsonicHz_) {
        subsonic_.coefficients = designBiquad(FilterType::HighPass, context_.sampleRate, subsonicHz, 0.0, 0.707);
    }
    designedSubsonicHz_ = subsonicHz;
    designedGainDb_ = gainDb_.value();
    designedFrequencyHz_ = frequency_.value();
}

void BassBoostStage::processSubsonic(float* left, float* right, int frames) {
    const bool gainChanged = gainDb_.advance();
    const bool frequencyChanged = frequency_.advance();
    if (!active()) {
        running_ = false;
        subsonicHz_ = 0.0f;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
        designedFrequencyHz_ = -1.0f;
    }
    subsonicHz_ = bassSubsonicFrequencyHz(gainDb_.value(), frequency_.value());
    if (gainChanged || frequencyChanged || designedFrequencyHz_ != frequency_.value() ||
        designedGainDb_ != gainDb_.value() || subsonicHz_ != designedSubsonicHz_) {
        redesign(subsonicHz_);
    }
    if (subsonicHz_ > 0.0f) {
        subsonic_.processBlock(left, right, frames);
    } else {
        subsonic_.reset();
    }
}

void BassBoostStage::processShelf(float* left, float* right, int frames) {
    if (!running_) {
        return;
    }
    shelf_.processBlock(left, right, frames);
}

void EnhancementLimiter::configure(const StageContext& context) {
    release_ = timeCoefficient(context.sampleRate, kEnhancementReleaseMs);
    holdSamples_ = static_cast<int>(std::lround(context.sampleRate * kEnhancementHoldSeconds));
    reset();
}

void EnhancementLimiter::reset() {
    gain_ = 1.0f;
    holdRemaining_ = 0;
    capturedFrames_ = 0;
}

void EnhancementLimiter::capture(const float* left, const float* right, int frames) {
    capturedFrames_ = std::min(frames, kMaxFrames);
    std::copy(left, left + capturedFrames_, dryLeft_.begin());
    std::copy(right, right + capturedFrames_, dryRight_.begin());
}

void EnhancementLimiter::apply(float* left, float* right, int frames) {
    const int limited = std::min(frames, capturedFrames_);
    for (int i = 0; i < limited; ++i) {
        const auto index = static_cast<size_t>(i);
        const float addedLeft = left[i] - dryLeft_[index];
        const float addedRight = right[i] - dryRight_[index];
        const float target = std::min(
            enhancementAllowance(dryLeft_[index], addedLeft),
            enhancementAllowance(dryRight_[index], addedRight)
        );
        if (target < gain_) {
            gain_ = target;
            holdRemaining_ = holdSamples_;
        } else if (target <= gain_ * 1.002f) {
            holdRemaining_ = holdSamples_;
        } else if (holdRemaining_ > 0) {
            --holdRemaining_;
        } else {
            gain_ += (target - gain_) * release_;
        }
        left[i] = dryLeft_[index] + gain_ * addedLeft;
        right[i] = dryRight_[index] + gain_ * addedRight;
    }
    capturedFrames_ = 0;
}

void HarmonicBassStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    envelope_.configure(context.sampleRate, 6.0, 90.0);
    designedFrequencyHz_ = -1.0f;
    reset();
}

void HarmonicBassStage::setTargets(float amount, float frequencyHz) {
    amount_.setTarget(clampf(amount, 0.0f, 1.0f));
    frequencyHz_ = clampf(frequencyHz, 40.0f, 300.0f);
}

void HarmonicBassStage::snapToTargets() {
    amount_.snap(amount_.target());
}

void HarmonicBassStage::reset() {
    extractA_.reset();
    extractB_.reset();
    highPass_.reset();
    lowPass_.reset();
    envelope_.reset();
}

bool HarmonicBassStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f;
}

void HarmonicBassStage::redesign() {
    const double sampleRate = context_.sampleRate;
    extract_ = designBiquad(FilterType::LowPass, sampleRate, frequencyHz_, 0.0, 0.707);
    harmonicHighPass_ = designBiquad(FilterType::HighPass, sampleRate, frequencyHz_ * 1.2, 0.0, 0.707);
    harmonicLowPass_ = designBiquad(FilterType::LowPass, sampleRate, frequencyHz_ * 6.0, 0.0, 0.707);
    designedFrequencyHz_ = frequencyHz_;
}

void HarmonicBassStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
    }
    if (designedFrequencyHz_ != frequencyHz_) {
        redesign();
    }
    float endAmount = 0.0f;
    const float startAmount = rampValue(amount_, &endAmount);
    const float step = (endAmount - startAmount) / static_cast<float>(std::max(frames, 1));
    float amount = startAmount;
    constexpr float kHarmonicGain = 1.6f;
    for (int i = 0; i < frames; ++i) {
        amount += step;
        const float mid = 0.5f * (left[i] + right[i]);
        const float low = extractB_.process(extract_, extractA_.process(extract_, mid));
        const float envelope = envelope_.process(low);
        const float normalized = clampf(low / (envelope + 1.0e-5f), -3.0f, 3.0f);
        const float shaped = 0.7f * std::tanh(1.6f * normalized) + 0.3f * normalized * normalized;
        const float harmonics = lowPass_.process(
            harmonicLowPass_,
            highPass_.process(harmonicHighPass_, shaped * envelope)
        );
        const float added = amount * kHarmonicGain * harmonics;
        left[i] += added;
        right[i] += added;
    }
}

void WarmthStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    tilt_.configure(context, 2);
    dcCoefficient_ = onePoleCoefficient(context.sampleRate, 12.0);
    reset();
}

void WarmthStage::setTargets(float amount) {
    const float clamped = clampf(amount, 0.0f, 1.0f);
    amount_.setTarget(clamped);
    tilt_.setBand(0, FilterBandTarget{clamped > 0.0f, FilterType::Peak, 220.0f, 1.8f * clamped, 0.8f});
    tilt_.setBand(1, FilterBandTarget{clamped > 0.0f, FilterType::HighShelf, 9000.0f, -2.5f * clamped, 0.707f});
}

void WarmthStage::snapToTargets() {
    amount_.snap(amount_.target());
    tilt_.snapToTargets();
}

void WarmthStage::reset() {
    tilt_.reset();
    dcInput_.fill(0.0f);
    dcOutput_.fill(0.0f);
}

bool WarmthStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f || tilt_.active();
}

void WarmthStage::process(float* left, float* right, int frames) {
    if (!active()) {
        return;
    }
    tilt_.process(left, right, frames);
    float endAmount = 0.0f;
    const float startAmount = rampValue(amount_, &endAmount);
    const float amount = 0.5f * (startAmount + endAmount);
    if (amount <= 0.0f) {
        return;
    }
    const float drive = 1.0f + 2.5f * amount;
    const float bias = 0.08f * amount;
    const float biasTanh = std::tanh(drive * bias);
    const float normalization = 1.0f / (drive * (1.0f - biasTanh * biasTanh));
    const float wet = 0.45f * amount;
    float* channels[2] = {left, right};
    for (int channel = 0; channel < 2; ++channel) {
        float* data = channels[channel];
        float lastInput = dcInput_[static_cast<size_t>(channel)];
        float lastOutput = dcOutput_[static_cast<size_t>(channel)];
        for (int i = 0; i < frames; ++i) {
            const float x = data[i];
            const float shaped = (std::tanh(drive * (x + bias)) - biasTanh) * normalization;
            const float mixed = x + wet * (shaped - x);
            // 非对称饱和会带出直流，用一阶高通去掉
            const float blocked = mixed - lastInput + (1.0f - dcCoefficient_) * lastOutput;
            lastInput = mixed;
            lastOutput = blocked;
            data[i] = blocked;
        }
        dcInput_[static_cast<size_t>(channel)] = lastInput;
        dcOutput_[static_cast<size_t>(channel)] = lastOutput;
    }
}

void ExciterStage::configure(const StageContext& context) {
    context_ = context;
    amount_.setCoefficient(context.smoothing);
    designedFrequencyHz_ = -1.0f;
    reset();
}

void ExciterStage::setTargets(float amount, float frequencyHz) {
    amount_.setTarget(clampf(amount, 0.0f, 1.0f));
    frequencyHz_ = clampf(frequencyHz, 1500.0f, 12000.0f);
}

void ExciterStage::snapToTargets() {
    amount_.snap(amount_.target());
}

void ExciterStage::reset() {
    for (auto& state : bandState_) {
        state.reset();
    }
    for (auto& state : harmonicState_) {
        state.reset();
    }
}

bool ExciterStage::active() const {
    return amount_.value() > 0.0f || amount_.target() > 0.0f;
}

void ExciterStage::process(float* left, float* right, int frames) {
    if (!active()) {
        running_ = false;
        return;
    }
    if (!running_) {
        reset();
        running_ = true;
    }
    if (designedFrequencyHz_ != frequencyHz_) {
        highPass_ = designBiquad(FilterType::HighPass, context_.sampleRate, frequencyHz_, 0.0, 0.707);
        designedFrequencyHz_ = frequencyHz_;
    }
    float endAmount = 0.0f;
    const float startAmount = rampValue(amount_, &endAmount);
    const float amount = 0.5f * (startAmount + endAmount);
    const float drive = 2.0f + 8.0f * amount;
    float* channels[2] = {left, right};
    for (int channel = 0; channel < 2; ++channel) {
        float* data = channels[channel];
        BiquadState& band = bandState_[static_cast<size_t>(channel)];
        BiquadState& harmonic = harmonicState_[static_cast<size_t>(channel)];
        for (int i = 0; i < frames; ++i) {
            const float high = band.process(highPass_, data[i]);
            const float saturated = std::tanh(drive * high);
            const float generated = (saturated + 0.3f * saturated * saturated) / drive - high;
            const float harmonics = harmonic.process(highPass_, generated);
            data[i] += amount * (0.35f * high + 0.9f * harmonics);
        }
    }
}

} // namespace neri::dsp
