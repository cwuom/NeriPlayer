#include "usb/feedback/usb_feedback_rate_math.h"

#include <array>
#include <cstddef>
#include <limits>

namespace neri::usb::feedback {
namespace {

bool checkedMultiply(uint64_t left, uint64_t right, uint64_t* output) {
    if (output == nullptr) {
        return false;
    }
    if (left != 0 && right > std::numeric_limits<uint64_t>::max() / left) {
        return false;
    }
    *output = left * right;
    return true;
}

bool checkedAdd(uint64_t left, uint64_t right, uint64_t* output) {
    if (output == nullptr ||
        right > std::numeric_limits<uint64_t>::max() - left) {
        return false;
    }
    *output = left + right;
    return true;
}

uint64_t greatestCommonDivisor(uint64_t left, uint64_t right) {
    while (right != 0) {
        const uint64_t remainder = left % right;
        left = right;
        right = remainder;
    }
    return left;
}

FeedbackMathStatus divideRounded(
    uint64_t numerator,
    uint64_t denominator,
    FeedbackRateQ32* output
) {
    if (output == nullptr || denominator == 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    const uint64_t quotient = numerator / denominator;
    const uint64_t remainder = numerator % denominator;
    const uint64_t roundingThreshold =
        (denominator / 2U) + (denominator % 2U);
    const uint64_t rounded = quotient +
        (remainder != 0 && remainder >= roundingThreshold ? 1U : 0U);
    *output = rounded;
    return FeedbackMathStatus::Ok;
}

bool addModulo(
    uint64_t left,
    uint64_t right,
    uint64_t modulus,
    uint64_t* remainder,
    uint64_t* quotientCarry
) {
    if (remainder == nullptr || quotientCarry == nullptr || modulus == 0 ||
        left >= modulus || right >= modulus) {
        return false;
    }
    if (left >= modulus - right) {
        *remainder = left - (modulus - right);
        *quotientCarry = 1;
    } else {
        *remainder = left + right;
        *quotientCarry = 0;
    }
    return true;
}

bool multiplyDivideRoundedSmallMultiplier(
    uint64_t multiplicand,
    uint32_t multiplier,
    uint64_t divisor,
    uint64_t* output
) {
    if (output == nullptr || divisor == 0 || multiplicand >= divisor) {
        return false;
    }
    uint64_t quotient = 0;
    uint64_t remainder = 0;
    for (int bit = 31; bit >= 0; --bit) {
        if (quotient > std::numeric_limits<uint64_t>::max() / 2U) {
            return false;
        }
        quotient *= 2U;
        uint64_t carry = 0;
        if (!addModulo(remainder, remainder, divisor, &remainder, &carry) ||
            !checkedAdd(quotient, carry, &quotient)) {
            return false;
        }
        if ((multiplier & (UINT32_C(1) << bit)) != 0) {
            if (!addModulo(
                    remainder,
                    multiplicand,
                    divisor,
                    &remainder,
                    &carry
                ) || !checkedAdd(quotient, carry, &quotient)) {
                return false;
            }
        }
    }
    const uint64_t roundingThreshold = (divisor / 2U) + (divisor % 2U);
    if (remainder != 0 && remainder >= roundingThreshold &&
        !checkedAdd(quotient, 1, &quotient)) {
        return false;
    }
    *output = quotient;
    return true;
}

constexpr size_t kWideWordCount = 6;

struct WideUnsigned {
    WideUnsigned() = default;

    explicit WideUnsigned(uint64_t value) {
        words[0] = static_cast<uint32_t>(value);
        words[1] = static_cast<uint32_t>(value >> 32U);
    }

    bool multiply(uint32_t factor) {
        uint64_t carry = 0;
        for (uint32_t& word : words) {
            const uint64_t product =
                static_cast<uint64_t>(word) * factor + carry;
            word = static_cast<uint32_t>(product);
            carry = product >> 32U;
        }
        return carry == 0;
    }

    bool shiftLeft(uint32_t bits) {
        for (uint32_t index = 0; index < bits; ++index) {
            if (!shiftLeftOne()) {
                return false;
            }
        }
        return true;
    }

    bool shiftLeftOne() {
        uint32_t carry = 0;
        for (uint32_t& word : words) {
            const uint32_t nextCarry = word >> 31U;
            word = static_cast<uint32_t>((word << 1U) | carry);
            carry = nextCarry;
        }
        return carry == 0;
    }

    [[nodiscard]] bool isZero() const {
        for (const uint32_t word : words) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    [[nodiscard]] bool bitAt(size_t index) const {
        const size_t wordIndex = index / 32U;
        const uint32_t bitIndex = static_cast<uint32_t>(index % 32U);
        return (words[wordIndex] & (UINT32_C(1) << bitIndex)) != 0;
    }

    void setBit(size_t index) {
        const size_t wordIndex = index / 32U;
        const uint32_t bitIndex = static_cast<uint32_t>(index % 32U);
        words[wordIndex] |= UINT32_C(1) << bitIndex;
    }

    bool subtract(const WideUnsigned& value) {
        if (compare(*this, value) < 0) {
            return false;
        }
        uint64_t borrow = 0;
        constexpr uint64_t kWordBase = UINT64_C(1) << 32U;
        for (size_t index = 0; index < words.size(); ++index) {
            const uint64_t minuend = words[index];
            const uint64_t subtrahend =
                static_cast<uint64_t>(value.words[index]) + borrow;
            if (minuend < subtrahend) {
                words[index] = static_cast<uint32_t>(
                    kWordBase + minuend - subtrahend
                );
                borrow = 1;
            } else {
                words[index] = static_cast<uint32_t>(minuend - subtrahend);
                borrow = 0;
            }
        }
        return borrow == 0;
    }

    bool increment() {
        for (uint32_t& word : words) {
            if (word != UINT32_MAX) {
                ++word;
                return true;
            }
            word = 0;
        }
        return false;
    }

    bool toUint64(uint64_t* output) const {
        if (output == nullptr) {
            return false;
        }
        for (size_t index = 2; index < words.size(); ++index) {
            if (words[index] != 0) {
                return false;
            }
        }
        *output = static_cast<uint64_t>(words[0]) |
            (static_cast<uint64_t>(words[1]) << 32U);
        return true;
    }

    static int compare(const WideUnsigned& left, const WideUnsigned& right) {
        for (size_t index = kWideWordCount; index > 0; --index) {
            const uint32_t leftWord = left.words[index - 1U];
            const uint32_t rightWord = right.words[index - 1U];
            if (leftWord != rightWord) {
                return leftWord < rightWord ? -1 : 1;
            }
        }
        return 0;
    }

    std::array<uint32_t, kWideWordCount> words {};
};

bool divideRoundedWide(
    const WideUnsigned& numerator,
    const WideUnsigned& denominator,
    WideUnsigned* output
) {
    if (output == nullptr || denominator.isZero()) {
        return false;
    }
    WideUnsigned quotient;
    WideUnsigned remainder;
    for (size_t bit = kWideWordCount * 32U; bit > 0; --bit) {
        if (!remainder.shiftLeftOne()) {
            return false;
        }
        if (numerator.bitAt(bit - 1U)) {
            remainder.words[0] |= 1U;
        }
        if (WideUnsigned::compare(remainder, denominator) >= 0) {
            if (!remainder.subtract(denominator)) {
                return false;
            }
            quotient.setBit(bit - 1U);
        }
    }
    if (!remainder.isZero()) {
        WideUnsigned doubledRemainder = remainder;
        if (!doubledRemainder.shiftLeftOne()) {
            return false;
        }
        if (WideUnsigned::compare(doubledRemainder, denominator) >= 0 &&
            !quotient.increment()) {
            return false;
        }
    }
    *output = quotient;
    return true;
}

FeedbackMathStatus normalizeFeedbackRateQ32Wide(
    uint64_t rawValue,
    uint8_t fractionalBits,
    uint32_t sourceIntervalsPerSecond,
    uint32_t audioIntervalsPerSecond,
    uint32_t scaleNumerator,
    uint32_t scaleDenominator,
    FeedbackRateQ32* output
) {
    WideUnsigned numerator(rawValue);
    WideUnsigned denominator(1U);
    if (!numerator.multiply(sourceIntervalsPerSecond) ||
        !numerator.multiply(scaleNumerator) || !numerator.shiftLeft(32U) ||
        !denominator.shiftLeft(fractionalBits) ||
        !denominator.multiply(audioIntervalsPerSecond) ||
        !denominator.multiply(scaleDenominator)) {
        return FeedbackMathStatus::Overflow;
    }
    WideUnsigned quotient;
    if (!divideRoundedWide(numerator, denominator, &quotient)) {
        return FeedbackMathStatus::Overflow;
    }
    FeedbackRateQ32 normalized = 0;
    if (!quotient.toUint64(&normalized)) {
        return FeedbackMathStatus::Overflow;
    }
    if (normalized == 0 || normalized > kMaxSupportedRateQ32) {
        return FeedbackMathStatus::OutOfRange;
    }
    *output = normalized;
    return FeedbackMathStatus::Ok;
}

} // namespace

FeedbackMathStatus makeFeedbackRateQ32(
    uint32_t sampleRate,
    uint32_t intervalsPerSecond,
    FeedbackRateQ32* output
) {
    if (output == nullptr || sampleRate == 0 || intervalsPerSecond == 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    if (sampleRate > kMaxSupportedSampleRate || intervalsPerSecond > 8000U) {
        return FeedbackMathStatus::OutOfRange;
    }
    uint64_t numerator = 0;
    if (!checkedMultiply(sampleRate, kQ32One, &numerator)) {
        return FeedbackMathStatus::Overflow;
    }
    return divideRounded(numerator, intervalsPerSecond, output);
}

FeedbackMathStatus normalizeFeedbackRateQ32(
    uint64_t rawValue,
    uint8_t fractionalBits,
    uint32_t sourceIntervalsPerSecond,
    uint32_t audioIntervalsPerSecond,
    uint32_t scaleNumerator,
    uint32_t scaleDenominator,
    FeedbackRateQ32* output
) {
    if (output == nullptr || rawValue == 0 || fractionalBits > 63 ||
        sourceIntervalsPerSecond == 0 || audioIntervalsPerSecond == 0 ||
        scaleNumerator == 0 || scaleDenominator == 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    if (sourceIntervalsPerSecond > 8000U || audioIntervalsPerSecond > 8000U) {
        return FeedbackMathStatus::OutOfRange;
    }

    std::array<uint64_t, 4> numeratorFactors {
        rawValue,
        sourceIntervalsPerSecond,
        scaleNumerator,
        kQ32One
    };
    std::array<uint64_t, 3> denominatorFactors {
        UINT64_C(1) << fractionalBits,
        audioIntervalsPerSecond,
        scaleDenominator
    };
    for (uint64_t& numeratorFactor : numeratorFactors) {
        for (uint64_t& denominatorFactor : denominatorFactors) {
            const uint64_t divisor = greatestCommonDivisor(
                numeratorFactor,
                denominatorFactor
            );
            numeratorFactor /= divisor;
            denominatorFactor /= divisor;
        }
    }

    uint64_t numerator = 1;
    for (const uint64_t factor : numeratorFactors) {
        if (!checkedMultiply(numerator, factor, &numerator)) {
            return normalizeFeedbackRateQ32Wide(
                rawValue,
                fractionalBits,
                sourceIntervalsPerSecond,
                audioIntervalsPerSecond,
                scaleNumerator,
                scaleDenominator,
                output
            );
        }
    }
    uint64_t denominator = 1;
    for (const uint64_t factor : denominatorFactors) {
        if (!checkedMultiply(denominator, factor, &denominator)) {
            return normalizeFeedbackRateQ32Wide(
                rawValue,
                fractionalBits,
                sourceIntervalsPerSecond,
                audioIntervalsPerSecond,
                scaleNumerator,
                scaleDenominator,
                output
            );
        }
    }
    const FeedbackMathStatus status = divideRounded(numerator, denominator, output);
    if (status != FeedbackMathStatus::Ok || *output == 0 ||
        *output > kMaxSupportedRateQ32) {
        return status == FeedbackMathStatus::Ok
            ? FeedbackMathStatus::OutOfRange
            : status;
    }
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus computeRateDeltaPpm(
    FeedbackRateQ32 sample,
    FeedbackRateQ32 reference,
    uint32_t* outputPpm
) {
    if (outputPpm == nullptr || sample == 0 || reference == 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    const FeedbackRateQ32 difference = sample >= reference
        ? sample - reference
        : reference - sample;
    const uint64_t whole = difference / reference;
    if (whole > std::numeric_limits<uint32_t>::max() / 1000000U) {
        *outputPpm = std::numeric_limits<uint32_t>::max();
        return FeedbackMathStatus::OutOfRange;
    }
    uint64_t fractional = 0;
    if (!multiplyDivideRoundedSmallMultiplier(
            difference % reference,
            1000000U,
            reference,
            &fractional
        )) {
        return FeedbackMathStatus::Overflow;
    }
    uint64_t ppm = whole * 1000000U;
    if (!checkedAdd(ppm, fractional, &ppm) ||
        ppm > std::numeric_limits<uint32_t>::max()) {
        *outputPpm = std::numeric_limits<uint32_t>::max();
        return FeedbackMathStatus::OutOfRange;
    }
    *outputPpm = static_cast<uint32_t>(ppm);
    return FeedbackMathStatus::Ok;
}

FeedbackProjection projectScheduledFrames(
    FeedbackRateQ32 rateQ32,
    uint32_t phaseQ32,
    uint64_t intervalCount
) {
    FeedbackProjection result;
    if (rateQ32 == 0) {
        result.status = FeedbackMathStatus::InvalidArgument;
        return result;
    }

    constexpr uint64_t kQ32Mask = kQ32One - 1U;
    const uint64_t wholeFramesPerInterval = rateQ32 >> 32U;
    const uint64_t fractionalRate = rateQ32 & kQ32Mask;
    const uint64_t intervalHigh = intervalCount >> 32U;
    const uint64_t intervalLow = intervalCount & kQ32Mask;

    uint64_t wholeFrames = 0;
    if (!checkedMultiply(
            wholeFramesPerInterval,
            intervalCount,
            &wholeFrames
        )) {
        result.status = FeedbackMathStatus::Overflow;
        return result;
    }
    uint64_t fractionalHighFrames = 0;
    if (!checkedMultiply(
            fractionalRate,
            intervalHigh,
            &fractionalHighFrames
        )) {
        result.status = FeedbackMathStatus::Overflow;
        return result;
    }
    const uint64_t lowProduct = fractionalRate * intervalLow;
    const uint64_t lowRemainderSum = (lowProduct & kQ32Mask) + phaseQ32;
    uint64_t fractionalFrames = fractionalHighFrames;
    if (!checkedAdd(
            fractionalFrames,
            lowProduct >> 32U,
            &fractionalFrames
        ) || !checkedAdd(
            fractionalFrames,
            lowRemainderSum >> 32U,
            &fractionalFrames
        ) || !checkedAdd(
            wholeFrames,
            fractionalFrames,
            &result.totalFrames
        )) {
        result.status = FeedbackMathStatus::Overflow;
        return result;
    }
    result.status = FeedbackMathStatus::Ok;
    result.remainderQ32 = static_cast<uint32_t>(lowRemainderSum & kQ32Mask);
    return result;
}

} // namespace neri::usb::feedback
