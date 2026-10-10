#include "dsp/neri_dsp_primitives.h"

namespace neri::dsp {
namespace {

constexpr double kMinQ = 0.1;
constexpr double kMaxQ = 24.0;
constexpr double kMaxNyquistRatio = 0.45;

BiquadCoefficients normalize(double b0, double b1, double b2, double a0, double a1, double a2) {
    BiquadCoefficients result;
    if (a0 == 0.0 || !std::isfinite(a0)) {
        return result;
    }
    result.b0 = b0 / a0;
    result.b1 = b1 / a0;
    result.b2 = b2 / a0;
    result.a1 = a1 / a0;
    result.a2 = a2 / a0;
    return result;
}

BiquadCoefficients shelf(bool low, double w0, double gainDb, double q) {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * alpha;
    if (low) {
        return normalize(
            a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cosW),
            a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha),
            (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha,
            -2.0 * ((a - 1.0) + (a + 1.0) * cosW),
            (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
        );
    }
    return normalize(
        a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha),
        -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
        a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha),
        (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha,
        2.0 * ((a - 1.0) - (a + 1.0) * cosW),
        (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
    );
}

} // namespace

FilterType filterTypeFromParam(float value) {
    const int type = static_cast<int>(std::lround(value));
    if (type < static_cast<int>(FilterType::Peak) || type > static_cast<int>(FilterType::BandPass)) {
        return FilterType::Peak;
    }
    return static_cast<FilterType>(type);
}

BiquadCoefficients designBiquad(
    FilterType type,
    double sampleRate,
    double frequencyHz,
    double gainDb,
    double q
) {
    if (sampleRate <= 0.0 || !std::isfinite(frequencyHz) || !std::isfinite(gainDb) || !std::isfinite(q)) {
        return BiquadCoefficients{};
    }
    const double frequency = std::clamp(frequencyHz, 5.0, sampleRate * kMaxNyquistRatio);
    const double safeQ = std::clamp(q, kMinQ, kMaxQ);
    const double w0 = 2.0 * kPi * frequency / sampleRate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * safeQ);
    switch (type) {
        case FilterType::LowShelf:
            return shelf(true, w0, gainDb, safeQ);
        case FilterType::HighShelf:
            return shelf(false, w0, gainDb, safeQ);
        case FilterType::LowPass:
            return normalize(
                (1.0 - cosW) * 0.5, 1.0 - cosW, (1.0 - cosW) * 0.5,
                1.0 + alpha, -2.0 * cosW, 1.0 - alpha
            );
        case FilterType::HighPass:
            return normalize(
                (1.0 + cosW) * 0.5, -(1.0 + cosW), (1.0 + cosW) * 0.5,
                1.0 + alpha, -2.0 * cosW, 1.0 - alpha
            );
        case FilterType::Notch:
            return normalize(1.0, -2.0 * cosW, 1.0, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha);
        case FilterType::BandPass:
            return normalize(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha);
        case FilterType::Peak:
        default: {
            const double a = std::pow(10.0, gainDb / 40.0);
            return normalize(
                1.0 + alpha * a, -2.0 * cosW, 1.0 - alpha * a,
                1.0 + alpha / a, -2.0 * cosW, 1.0 - alpha / a
            );
        }
    }
}

} // namespace neri::dsp
