#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace neri::dsp {

constexpr double kPi = 3.14159265358979323846;
constexpr float kMinGain = 1.0e-9f;

inline float dbToGain(float db) {
    return std::pow(10.0f, db / 20.0f);
}

inline float gainToDb(float gain) {
    return 20.0f * std::log10(std::max(gain, kMinGain));
}

inline float clampf(float value, float low, float high) {
    return std::min(std::max(value, low), high);
}

inline float onePoleCoefficient(double sampleRate, double frequencyHz) {
    if (sampleRate <= 0.0 || frequencyHz <= 0.0) {
        return 1.0f;
    }
    return static_cast<float>(1.0 - std::exp(-2.0 * kPi * frequencyHz / sampleRate));
}

inline float timeCoefficient(double sampleRate, double milliseconds) {
    if (sampleRate <= 0.0 || milliseconds <= 0.0) {
        return 1.0f;
    }
    return static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * milliseconds * 0.001)));
}

// 只处理接近满幅的样本，正常电平保持透明
inline float softClip(float x) {
    constexpr float kKnee = 0.98f;
    const float magnitude = std::fabs(x);
    if (magnitude <= kKnee) {
        return x;
    }
    const float excess = (magnitude - kKnee) / (1.0f - kKnee);
    const float shaped = kKnee + (1.0f - kKnee) * std::tanh(excess);
    return std::copysign(shaped, x);
}

enum class FilterType : int {
    Peak = 0,
    LowShelf = 1,
    HighShelf = 2,
    LowPass = 3,
    HighPass = 4,
    Notch = 5,
    BandPass = 6,
};

FilterType filterTypeFromParam(float value);

struct BiquadCoefficients {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
};

BiquadCoefficients designBiquad(
    FilterType type,
    double sampleRate,
    double frequencyHz,
    double gainDb,
    double q
);

struct BiquadState {
    double z1 = 0.0;
    double z2 = 0.0;

    void reset() {
        z1 = 0.0;
        z2 = 0.0;
    }

    float process(const BiquadCoefficients& c, float input) {
        const double x = input;
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        return static_cast<float>(y);
    }

    void processBlock(const BiquadCoefficients& c, float* data, int frames) {
        double s1 = z1;
        double s2 = z2;
        for (int i = 0; i < frames; ++i) {
            const double x = data[i];
            const double y = c.b0 * x + s1;
            s1 = c.b1 * x - c.a1 * y + s2;
            s2 = c.b2 * x - c.a2 * y;
            data[i] = static_cast<float>(y);
        }
        z1 = s1;
        z2 = s2;
    }
};

// 立体声共用系数的二阶滤波器
struct StereoBiquad {
    BiquadCoefficients coefficients;
    BiquadState left;
    BiquadState right;

    void reset() {
        left.reset();
        right.reset();
    }

    // 两个声道交错计算，让两条递归依赖链并行
    void processBlock(float* l, float* r, int frames) {
        const BiquadCoefficients c = coefficients;
        double l1 = left.z1;
        double l2 = left.z2;
        double r1 = right.z1;
        double r2 = right.z2;
        for (int i = 0; i < frames; ++i) {
            const double xl = l[i];
            const double xr = r[i];
            const double yl = c.b0 * xl + l1;
            const double yr = c.b0 * xr + r1;
            l1 = c.b1 * xl - c.a1 * yl + l2;
            r1 = c.b1 * xr - c.a1 * yr + r2;
            l2 = c.b2 * xl - c.a2 * yl;
            r2 = c.b2 * xr - c.a2 * yr;
            l[i] = static_cast<float>(yl);
            r[i] = static_cast<float>(yr);
        }
        left.z1 = l1;
        left.z2 = l2;
        right.z1 = r1;
        right.z2 = r2;
    }
};

class SmoothedValue {
public:
    void setCoefficient(float coefficient) {
        coefficient_ = clampf(coefficient, 0.0f, 1.0f);
    }

    void setTarget(float target) {
        target_ = target;
    }

    void snap(float value) {
        target_ = value;
        current_ = value;
    }

    // 每个处理块推进一次，返回当前值是否发生变化
    bool advance() {
        if (current_ == target_) {
            return false;
        }
        const float next = current_ + (target_ - current_) * coefficient_;
        const float tolerance = 1.0e-4f * std::max(1.0f, std::fabs(target_));
        current_ = std::fabs(target_ - next) <= tolerance ? target_ : next;
        return true;
    }

    float value() const {
        return current_;
    }

    float target() const {
        return target_;
    }

    bool settled() const {
        return current_ == target_;
    }

private:
    float current_ = 0.0f;
    float target_ = 0.0f;
    float coefficient_ = 1.0f;
};

class DelayLine {
public:
    void resize(int maxDelaySamples) {
        int size = 1;
        while (size < maxDelaySamples + 2) {
            size <<= 1;
        }
        buffer_.assign(static_cast<size_t>(size), 0.0f);
        mask_ = size - 1;
        write_ = 0;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        write_ = 0;
    }

    void push(float value) {
        if (buffer_.empty()) {
            return;
        }
        buffer_[static_cast<size_t>(write_)] = value;
        write_ = (write_ + 1) & mask_;
    }

    // delay=1 表示上一次写入的样本
    float read(int delaySamples) const {
        if (buffer_.empty()) {
            return 0.0f;
        }
        const int index = (write_ - std::max(1, delaySamples)) & mask_;
        return buffer_[static_cast<size_t>(index)];
    }

    int capacity() const {
        return static_cast<int>(buffer_.size()) - 2;
    }

private:
    std::vector<float> buffer_;
    int mask_ = 0;
    int write_ = 0;
};

class EnvelopeFollower {
public:
    void configure(double sampleRate, double attackMs, double releaseMs) {
        attack_ = timeCoefficient(sampleRate, attackMs);
        release_ = timeCoefficient(sampleRate, releaseMs);
    }

    void reset() {
        value_ = 0.0f;
    }

    float process(float input) {
        const float magnitude = std::fabs(input);
        const float coefficient = magnitude > value_ ? attack_ : release_;
        value_ += (magnitude - value_) * coefficient;
        return value_;
    }

private:
    float attack_ = 1.0f;
    float release_ = 1.0f;
    float value_ = 0.0f;
};

inline void applyGainRamp(float* data, int frames, float startGain, float endGain) {
    if (startGain == endGain) {
        if (startGain == 1.0f) {
            return;
        }
        for (int i = 0; i < frames; ++i) {
            data[i] *= startGain;
        }
        return;
    }
    const float step = (endGain - startGain) / static_cast<float>(std::max(frames, 1));
    float gain = startGain;
    for (int i = 0; i < frames; ++i) {
        gain += step;
        data[i] *= gain;
    }
}

} // namespace neri::dsp
