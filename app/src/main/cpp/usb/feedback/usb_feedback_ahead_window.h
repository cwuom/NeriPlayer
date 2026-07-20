#pragma once

#include "usb/feedback/usb_feedback_types.h"

#include <cstdint>
#include <limits>

namespace neri::usb::feedback {

struct FeedbackAheadWindowInput {
    uint64_t submittedIntervals = 0;
    uint64_t completedIntervals = 0;
    uint64_t transferIntervals = 0;
    uint64_t maxAheadIntervals = 0;
};

struct FeedbackAheadWindowResult {
    FeedbackAheadWindowStatus status = FeedbackAheadWindowStatus::InvalidArgument;
    uint64_t currentAheadIntervals = 0;
    uint64_t projectedAheadIntervals = 0;
    uint64_t remainingIntervals = 0;
};

inline FeedbackAheadWindowResult checkFeedbackAheadWindow(
    const FeedbackAheadWindowInput& input
) {
    FeedbackAheadWindowResult result;
    if (input.transferIntervals == 0 || input.maxAheadIntervals == 0) {
        return result;
    }
    if (input.completedIntervals > input.submittedIntervals) {
        result.status = FeedbackAheadWindowStatus::CounterOrder;
        return result;
    }
    result.currentAheadIntervals = input.submittedIntervals - input.completedIntervals;
    if (result.currentAheadIntervals > input.maxAheadIntervals ||
        input.transferIntervals > input.maxAheadIntervals) {
        result.status = FeedbackAheadWindowStatus::CapacityExceeded;
        return result;
    }
    if (input.transferIntervals >
        std::numeric_limits<uint64_t>::max() - result.currentAheadIntervals) {
        result.status = FeedbackAheadWindowStatus::Overflow;
        return result;
    }
    result.projectedAheadIntervals =
        result.currentAheadIntervals + input.transferIntervals;
    if (result.projectedAheadIntervals > input.maxAheadIntervals) {
        result.status = FeedbackAheadWindowStatus::CapacityExceeded;
        result.remainingIntervals =
            input.maxAheadIntervals - result.currentAheadIntervals;
        return result;
    }
    result.status = FeedbackAheadWindowStatus::Ok;
    result.remainingIntervals =
        input.maxAheadIntervals - result.projectedAheadIntervals;
    return result;
}

} // namespace neri::usb::feedback
