#include "usb_pcm_resampler.h"

#include <algorithm>
#include <climits>
#include <cmath>

namespace neri::usb {
namespace {

// 每侧 32 个过零点、Kaiser β=9：阻带约 -90 dB，44.1 kHz 时通带平坦到约 19 kHz
constexpr int kZeroCrossings = 32;
constexpr int kMaximumHalfTaps = 320;
constexpr int kPhases = 128;
constexpr double kCutoffScale = 0.96;
constexpr double kKaiserBeta = 9.0;
constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
    const double half = x / 2.0;
    double sum = 1.0;
    double term = 1.0;
    for (int k = 1; k < 64; ++k) {
        const double factor = half / static_cast<double>(k);
        term *= factor * factor;
        sum += term;
        if (term < sum * 1e-17) {
            break;
        }
    }
    return sum;
}

double kaiser(double x) {
    const double ratio = 1.0 - x * x;
    if (ratio <= 0.0) {
        return 0.0;
    }
    return besselI0(kKaiserBeta * std::sqrt(ratio)) / besselI0(kKaiserBeta);
}

double sinc(double x) {
    if (std::abs(x) < 1e-12) {
        return 1.0;
    }
    const double angle = kPi * x;
    return std::sin(angle) / angle;
}

} // namespace

std::shared_ptr<const PcmResampler::Kernel> PcmResampler::buildKernel(int inputRate, int outputRate) {
    auto kernel = std::make_shared<Kernel>();
    // 截止频率以每个输入样本的周期数表示，取两侧较低奈奎斯特频率的 96%
    const double cutoff = 0.5 * kCutoffScale *
        std::min(1.0, static_cast<double>(outputRate) / static_cast<double>(inputRate));
    const int halfTaps = std::clamp(
        static_cast<int>(std::ceil(kZeroCrossings / (2.0 * cutoff))),
        kZeroCrossings,
        kMaximumHalfTaps
    );
    const int taps = 2 * halfTaps;
    kernel->halfTaps = halfTaps;
    kernel->phases = kPhases;
    kernel->coefficients.assign(static_cast<size_t>(kPhases + 1) * static_cast<size_t>(taps), 0.0f);
    kernel->deltas.assign(static_cast<size_t>(kPhases) * static_cast<size_t>(taps), 0.0f);
    std::vector<double> row(static_cast<size_t>(taps), 0.0);
    for (int phase = 0; phase <= kPhases; ++phase) {
        const double offset = static_cast<double>(phase) / static_cast<double>(kPhases);
        double sum = 0.0;
        for (int tap = 0; tap < taps; ++tap) {
            const double t = static_cast<double>(tap - halfTaps + 1) - offset;
            const double value = 2.0 * cutoff * sinc(2.0 * cutoff * t) *
                kaiser(t / static_cast<double>(halfTaps));
            row[static_cast<size_t>(tap)] = value;
            sum += value;
        }
        float* coefficients = kernel->coefficients.data() +
            static_cast<size_t>(phase) * static_cast<size_t>(taps);
        for (int tap = 0; tap < taps; ++tap) {
            // 每个相位单独归一化，直流增益严格为 1
            coefficients[tap] = static_cast<float>(row[static_cast<size_t>(tap)] / sum);
        }
    }
    for (int phase = 0; phase < kPhases; ++phase) {
        const size_t row0 = static_cast<size_t>(phase) * static_cast<size_t>(taps);
        const size_t row1 = row0 + static_cast<size_t>(taps);
        for (int tap = 0; tap < taps; ++tap) {
            const auto index = static_cast<size_t>(tap);
            kernel->deltas[row0 + index] =
                kernel->coefficients[row1 + index] - kernel->coefficients[row0 + index];
        }
    }
    return kernel;
}

bool PcmResampler::configure(int inputRate, int outputRate, int channels) {
    if (inputRate <= 0 || outputRate <= 0 || channels <= 0 || inputRate == outputRate) {
        kernel_.reset();
        inputRate_ = 0;
        outputRate_ = 0;
        channels_ = 0;
        reset();
        return false;
    }
    if (kernel_ == nullptr || inputRate != inputRate_ || outputRate != outputRate_) {
        kernel_ = buildKernel(inputRate, outputRate);
    }
    inputRate_ = inputRate;
    outputRate_ = outputRate;
    channels_ = channels;
    scratch_.assign(static_cast<size_t>(2 * kernel_->halfTaps), 0.0f);
    reset();
    return true;
}

void PcmResampler::reset() {
    fraction_ = 0;
    if (kernel_ == nullptr || channels_ <= 0) {
        fifo_.clear();
        base_ = 0;
        return;
    }
    // 第一帧真实输入落在 halfTaps - 1，前面补零作为滤波历史
    const int history = kernel_->halfTaps - 1;
    fifo_.assign(static_cast<size_t>(history) * static_cast<size_t>(channels_), 0.0f);
    base_ = history;
}

bool PcmResampler::configured() const {
    return kernel_ != nullptr;
}

int PcmResampler::halfTaps() const {
    return kernel_ != nullptr ? kernel_->halfTaps : 0;
}

int64_t PcmResampler::fifoFrames() const {
    return channels_ > 0 ? static_cast<int64_t>(fifo_.size() / static_cast<size_t>(channels_)) : 0;
}

int PcmResampler::inputFramesForOutput(int availableInputFrames, size_t maxOutputFrames) const {
    if (kernel_ == nullptr || availableInputFrames <= 0) {
        return 0;
    }
    const auto maxOutput = static_cast<int64_t>(std::min<size_t>(maxOutputFrames, INT32_MAX));
    // 产出 k 帧要求 base + floor((fraction + (k-1)*in)/out) + halfTaps 都落在已有输入内，反解可接收的输入帧数
    const int64_t limit = (maxOutput * inputRate_ + fraction_) / outputRate_ +
        kernel_->halfTaps + base_ - fifoFrames();
    return static_cast<int>(std::clamp<int64_t>(limit, 0, availableInputFrames));
}

size_t PcmResampler::process(const float* input, int inputFrames, std::vector<float>* output) {
    if (kernel_ == nullptr || output == nullptr) {
        return 0;
    }
    if (input != nullptr && inputFrames > 0) {
        fifo_.insert(
            fifo_.end(),
            input,
            input + static_cast<size_t>(inputFrames) * static_cast<size_t>(channels_)
        );
    }
    const int halfTaps = kernel_->halfTaps;
    size_t produced = 0;
    while (base_ + halfTaps <= fifoFrames() - 1) {
        emitFrame(output);
        ++produced;
        fraction_ += inputRate_;
        base_ += fraction_ / outputRate_;
        fraction_ %= outputRate_;
    }
    const int64_t drop = std::min(base_ - (halfTaps - 1), fifoFrames());
    if (drop > 0) {
        fifo_.erase(
            fifo_.begin(),
            fifo_.begin() + static_cast<std::ptrdiff_t>(drop * channels_)
        );
        base_ -= drop;
    }
    return produced;
}

size_t PcmResampler::drain(std::vector<float>* output) {
    if (kernel_ == nullptr || output == nullptr) {
        return 0;
    }
    const std::vector<float> silence(
        static_cast<size_t>(kernel_->halfTaps) * static_cast<size_t>(channels_),
        0.0f
    );
    const size_t produced = process(silence.data(), kernel_->halfTaps, output);
    reset();
    return produced;
}

void PcmResampler::emitFrame(std::vector<float>* output) {
    const Kernel& kernel = *kernel_;
    const int taps = 2 * kernel.halfTaps;
    const double position = static_cast<double>(fraction_) * kernel.phases /
        static_cast<double>(outputRate_);
    const int phase = std::min(static_cast<int>(position), kernel.phases - 1);
    const auto blend = static_cast<float>(position - phase);
    const float* coefficients = kernel.coefficients.data() +
        static_cast<size_t>(phase) * static_cast<size_t>(taps);
    const float* deltas = kernel.deltas.data() + static_cast<size_t>(phase) * static_cast<size_t>(taps);
    for (int tap = 0; tap < taps; ++tap) {
        scratch_[static_cast<size_t>(tap)] = coefficients[tap] + blend * deltas[tap];
    }
    const float* first = fifo_.data() +
        static_cast<size_t>(base_ - kernel.halfTaps + 1) * static_cast<size_t>(channels_);
    for (int channel = 0; channel < channels_; ++channel) {
        const float* sample = first + channel;
        float sum = 0.0f;
        for (int tap = 0; tap < taps; ++tap) {
            sum += sample[static_cast<size_t>(tap) * static_cast<size_t>(channels_)] *
                scratch_[static_cast<size_t>(tap)];
        }
        output->push_back(sum);
    }
}

} // namespace neri::usb
