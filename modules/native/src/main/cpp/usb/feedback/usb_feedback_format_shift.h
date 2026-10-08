#pragma once

#include "usb/feedback/usb_feedback_types.h"

#include <climits>

namespace neri::usb::feedback {

// Many high-speed DACs report feedback in 10.14 instead of 16.16, or count
// frames per 1 ms frame instead of per microframe. As snd-usb-audio does, the
// first report picks the power-of-two shift that brings it near the nominal
// rate; the shift sticks until a report leaves the plausible band again.
class FeedbackFormatShift final {
public:
    static constexpr int kUnknown = INT_MIN;
    static constexpr int kMaximumShift = 8;

    void reset() noexcept {
        shift_ = kUnknown;
    }

    [[nodiscard]] int shift() const noexcept {
        return shift_;
    }

    // Returns the corrected rate, or 0 when the report is not plausible.
    FeedbackRateQ32 apply(FeedbackRateQ32 rateQ32, FeedbackRateQ32 nominalQ32) noexcept {
        if (rateQ32 == 0 || nominalQ32 == 0) {
            return 0;
        }
        if (shift_ == kUnknown) {
            shift_ = detect(rateQ32, nominalQ32);
        }
        const FeedbackRateQ32 corrected = shift_ >= 0
            ? rateQ32 << shift_
            : rateQ32 >> -shift_;
        if (!plausible(corrected, nominalQ32)) {
            shift_ = kUnknown;
            return 0;
        }
        return corrected;
    }

private:
    static bool plausible(FeedbackRateQ32 rateQ32, FeedbackRateQ32 nominalQ32) noexcept {
        return rateQ32 >= nominalQ32 - nominalQ32 / 4 &&
            rateQ32 <= nominalQ32 + nominalQ32 / 2;
    }

    static int detect(FeedbackRateQ32 rateQ32, FeedbackRateQ32 nominalQ32) noexcept {
        int shift = 0;
        while (rateQ32 < nominalQ32 - nominalQ32 / 4 && shift < kMaximumShift) {
            rateQ32 <<= 1;
            ++shift;
        }
        while (rateQ32 > nominalQ32 + nominalQ32 / 2 && shift > -kMaximumShift) {
            rateQ32 >>= 1;
            --shift;
        }
        return shift;
    }

    int shift_ = kUnknown;
};

} // namespace neri::usb::feedback
