#include "dsp/neri_dsp_stages.h"

namespace neri::dsp {
namespace {

constexpr int kMaxSpeakerBlockFrames = 512;
constexpr double kSpeakerCrossoverHz = 1200.0;
constexpr double kButterworthQ1 = 0.5412;
constexpr double kButterworthQ2 = 1.3066;

CompressorSettings sanitize(const CompressorSettings& input) {
    CompressorSettings settings = input;
    settings.thresholdDb = clampf(settings.thresholdDb, -60.0f, 0.0f);
    settings.ratio = clampf(settings.ratio, 1.0f, 20.0f);
    settings.attackMs = clampf(settings.attackMs, 0.1f, 200.0f);
    settings.releaseMs = clampf(settings.releaseMs, 5.0f, 2000.0f);
    settings.kneeDb = clampf(settings.kneeDb, 0.0f, 24.0f);
    settings.makeupDb = clampf(settings.makeupDb, 0.0f, 24.0f);
    return settings;
}

int detectorStrideFor(QualityMode quality) {
    return quality == QualityMode::Eco ? 4 : 1;
}

CompressorSettings speakerBandSettings(float loudness, float ratioBoost) {
    CompressorSettings settings;
    settings.enabled = true;
    settings.thresholdDb = -8.0f - 22.0f * loudness;
    settings.ratio = 2.5f + ratioBoost * loudness;
    settings.attackMs = 4.0f;
    settings.releaseMs = 140.0f;
    settings.kneeDb = 6.0f;
    settings.makeupDb = 0.55f * -settings.thresholdDb * (1.0f - 1.0f / settings.ratio);
    return settings;
}

} // namespace

void CompressorCore::configure(double sampleRate, const CompressorSettings& settings, int detectorStride) {
    settings_ = sanitize(settings);
    detectorStride_ = std::max(1, detectorStride);
    const double detectorRate = sampleRate / static_cast<double>(detectorStride_);
    attack_ = timeCoefficient(detectorRate, settings_.attackMs);
    release_ = timeCoefficient(detectorRate, settings_.releaseMs);
    makeupGain_ = dbToGain(settings_.makeupDb);
}

void CompressorCore::reset() {
    reductionDb_ = 0.0f;
    appliedGain_ = makeupGain_;
    strideCounter_ = 0;
}

float CompressorCore::computeTargetDb(float inputDb) const {
    const float over = inputDb - settings_.thresholdDb;
    const float knee = settings_.kneeDb;
    const float slope = 1.0f / settings_.ratio - 1.0f;
    if (knee > 0.0f && 2.0f * std::fabs(over) <= knee) {
        const float x = over + 0.5f * knee;
        return inputDb + slope * x * x / (2.0f * knee);
    }
    if (over <= 0.0f) {
        return inputDb;
    }
    return inputDb + slope * over;
}

float CompressorCore::gainFor(float level) {
    if (strideCounter_ == 0) {
        const float inputDb = gainToDb(level);
        const float targetReduction = computeTargetDb(inputDb) - inputDb;
        const float coefficient = targetReduction < reductionDb_ ? attack_ : release_;
        reductionDb_ += (targetReduction - reductionDb_) * coefficient;
        appliedGain_ = dbToGain(reductionDb_) * makeupGain_;
    }
    strideCounter_ = (strideCounter_ + 1) % detectorStride_;
    return appliedGain_;
}

void CompressorStage::configure(const StageContext& context) {
    context_ = context;
    core_.configure(context.sampleRate, settings_, detectorStrideFor(context.quality));
    reset();
}

void CompressorStage::setTargets(const CompressorSettings& settings) {
    const CompressorSettings sanitized = sanitize(settings);
    const bool changed = sanitized.enabled != settings_.enabled ||
        sanitized.thresholdDb != settings_.thresholdDb ||
        sanitized.ratio != settings_.ratio ||
        sanitized.attackMs != settings_.attackMs ||
        sanitized.releaseMs != settings_.releaseMs ||
        sanitized.kneeDb != settings_.kneeDb ||
        sanitized.makeupDb != settings_.makeupDb;
    const bool enabling = sanitized.enabled && !settings_.enabled;
    settings_ = sanitized;
    if (changed) {
        core_.configure(context_.sampleRate, settings_, detectorStrideFor(context_.quality));
    }
    if (enabling) {
        core_.reset();
    }
}

void CompressorStage::reset() {
    core_.reset();
}

bool CompressorStage::active() const {
    return settings_.enabled;
}

void CompressorStage::process(float* left, float* right, int frames) {
    if (!settings_.enabled) {
        return;
    }
    for (int i = 0; i < frames; ++i) {
        const float level = std::max(std::fabs(left[i]), std::fabs(right[i]));
        const float gain = core_.gainFor(level);
        left[i] *= gain;
        right[i] *= gain;
    }
}

void SpeakerStage::configure(const StageContext& context) {
    context_ = context;
    expand_.setCoefficient(context.smoothing);
    harmonics_.configure(context);
    clarity_.configure(context, 2);
    lowLeft_.assign(kMaxSpeakerBlockFrames, 0.0f);
    lowRight_.assign(kMaxSpeakerBlockFrames, 0.0f);
    designedHighPassHz_ = -1.0f;
    designedLoudness_ = -1.0f;
    reset();
}

void SpeakerStage::setTargets(const SpeakerSettings& input) {
    SpeakerSettings settings = input;
    settings.highPassHz = clampf(settings.highPassHz, 0.0f, 500.0f);
    settings.bassHarmonics = clampf(settings.bassHarmonics, 0.0f, 1.0f);
    settings.loudness = clampf(settings.loudness, 0.0f, 1.0f);
    settings.clarity = clampf(settings.clarity, 0.0f, 1.0f);
    settings.stereoExpand = clampf(settings.stereoExpand, 0.0f, 1.0f);
    const bool enabling = settings.enabled && !settings_.enabled;
    settings_ = settings;
    const float on = settings.enabled ? 1.0f : 0.0f;
    const float harmonicFrequency = clampf(std::max(settings.highPassHz, 60.0f) * 1.4f, 70.0f, 260.0f);
    harmonics_.setTargets(on * settings.bassHarmonics, harmonicFrequency);
    const bool clarityOn = settings.enabled && settings.clarity > 0.0f;
    clarity_.setBand(0, FilterBandTarget{clarityOn, FilterType::Peak, 2800.0f, 4.0f * settings.clarity, 0.8f});
    clarity_.setBand(1, FilterBandTarget{clarityOn, FilterType::HighShelf, 8000.0f, 3.0f * settings.clarity, 0.707f});
    expand_.setTarget(on * settings.stereoExpand);
    if (enabling) {
        designedHighPassHz_ = -1.0f;
        designedLoudness_ = -1.0f;
    }
}

void SpeakerStage::snapToTargets() {
    expand_.snap(expand_.target());
    harmonics_.snapToTargets();
    clarity_.snapToTargets();
}

void SpeakerStage::reset() {
    highPassA_.reset();
    highPassB_.reset();
    harmonics_.reset();
    clarity_.reset();
    for (auto& filter : crossoverLow_) {
        filter.reset();
    }
    for (auto& filter : crossoverHigh_) {
        filter.reset();
    }
    lowBand_.reset();
    highBand_.reset();
}

bool SpeakerStage::active() const {
    return settings_.enabled || harmonics_.active() || clarity_.active() ||
        expand_.value() > 0.0f || expand_.target() > 0.0f;
}

void SpeakerStage::redesign() {
    const double sampleRate = context_.sampleRate;
    if (designedHighPassHz_ != settings_.highPassHz) {
        if (settings_.highPassHz >= 20.0f) {
            highPassA_.coefficients = designBiquad(FilterType::HighPass, sampleRate, settings_.highPassHz, 0.0, kButterworthQ1);
            highPassB_.coefficients = designBiquad(FilterType::HighPass, sampleRate, settings_.highPassHz, 0.0, kButterworthQ2);
        }
        highPassA_.reset();
        highPassB_.reset();
        designedHighPassHz_ = settings_.highPassHz;
    }
    if (designedLoudness_ != settings_.loudness) {
        for (auto& filter : crossoverLow_) {
            filter.coefficients = designBiquad(FilterType::LowPass, sampleRate, kSpeakerCrossoverHz, 0.0, 0.7071);
        }
        for (auto& filter : crossoverHigh_) {
            filter.coefficients = designBiquad(FilterType::HighPass, sampleRate, kSpeakerCrossoverHz, 0.0, 0.7071);
        }
        const int stride = detectorStrideFor(context_.quality);
        lowBand_.configure(sampleRate, speakerBandSettings(settings_.loudness, 1.5f), stride);
        highBand_.configure(sampleRate, speakerBandSettings(settings_.loudness, 2.5f), stride);
        designedLoudness_ = settings_.loudness;
    }
}

void SpeakerStage::processLoudness(float* left, float* right, int frames) {
    std::copy(left, left + frames, lowLeft_.begin());
    std::copy(right, right + frames, lowRight_.begin());
    for (auto& filter : crossoverLow_) {
        filter.processBlock(lowLeft_.data(), lowRight_.data(), frames);
    }
    for (auto& filter : crossoverHigh_) {
        filter.processBlock(left, right, frames);
    }
    for (int i = 0; i < frames; ++i) {
        const auto index = static_cast<size_t>(i);
        const float lowGain = lowBand_.gainFor(std::max(std::fabs(lowLeft_[index]), std::fabs(lowRight_[index])));
        const float highGain = highBand_.gainFor(std::max(std::fabs(left[i]), std::fabs(right[i])));
        left[i] = left[i] * highGain + lowLeft_[index] * lowGain;
        right[i] = right[i] * highGain + lowRight_[index] * lowGain;
    }
}

void SpeakerStage::process(float* left, float* right, int frames) {
    if (!active()) {
        return;
    }
    if (settings_.enabled) {
        redesign();
        if (settings_.highPassHz >= 20.0f) {
            highPassA_.processBlock(left, right, frames);
            highPassB_.processBlock(left, right, frames);
        }
    }
    harmonics_.process(left, right, frames);
    clarity_.process(left, right, frames);
    const float expandStart = expand_.value();
    expand_.advance();
    const float expandEnd = expand_.value();
    if (expandStart > 0.0f || expandEnd > 0.0f) {
        const float step = (expandEnd - expandStart) / static_cast<float>(std::max(frames, 1));
        float expand = expandStart;
        for (int i = 0; i < frames; ++i) {
            expand += step;
            const float mid = 0.5f * (left[i] + right[i]);
            const float side = 0.5f * (left[i] - right[i]) * (1.0f + 0.6f * expand);
            left[i] = mid + side;
            right[i] = mid - side;
        }
    }
    if (settings_.enabled && settings_.loudness > 0.0f && frames <= kMaxSpeakerBlockFrames) {
        processLoudness(left, right, frames);
    }
}

void OutputStage::configure(const StageContext& context) {
    context_ = context;
    gain_.setCoefficient(context.smoothing);
    gain_.snap(1.0f);
    reset();
}

void OutputStage::setTargets(float outputGainDb, bool limiterEnabled, float ceilingDb, float releaseMs) {
    gain_.setTarget(dbToGain(clampf(outputGainDb, -24.0f, 24.0f)));
    limiterEnabled_ = limiterEnabled;
    ceiling_ = dbToGain(clampf(ceilingDb, -12.0f, 0.0f));
    release_ = timeCoefficient(context_.sampleRate, clampf(releaseMs, 5.0f, 1000.0f));
}

void OutputStage::snapToTargets() {
    gain_.snap(gain_.target());
}

void OutputStage::reset() {
    limiterGain_ = 1.0f;
}

void OutputStage::process(float* left, float* right, int frames) {
    const float gainStart = gain_.value();
    gain_.advance();
    const float gainEnd = gain_.value();
    applyGainRamp(left, frames, gainStart, gainEnd);
    applyGainRamp(right, frames, gainStart, gainEnd);
    if (limiterEnabled_) {
        float minimumGain = limiterGain_;
        for (int i = 0; i < frames; ++i) {
            const float peak = std::max(std::fabs(left[i]), std::fabs(right[i]));
            const float target = peak > ceiling_ ? ceiling_ / peak : 1.0f;
            if (target < limiterGain_) {
                limiterGain_ = target;
                limiterEvents_ += 1;
            } else {
                limiterGain_ += (target - limiterGain_) * release_;
            }
            minimumGain = std::min(minimumGain, limiterGain_);
            left[i] *= limiterGain_;
            right[i] *= limiterGain_;
        }
        maxReductionDb_ = std::max(maxReductionDb_, -gainToDb(minimumGain));
    }
    for (int i = 0; i < frames; ++i) {
        left[i] = softClip(left[i]);
        right[i] = softClip(right[i]);
    }
}

float OutputStage::takeMaxReductionDb() {
    const float value = maxReductionDb_;
    maxReductionDb_ = 0.0f;
    return value;
}

int64_t OutputStage::takeLimiterEvents() {
    const int64_t value = limiterEvents_;
    limiterEvents_ = 0;
    return value;
}

} // namespace neri::dsp
