#pragma once

#include "usb/feedback/usb_feedback_rate_math.h"
#include "usb/feedback/usb_feedback_types.h"

#include <cstdint>

namespace neri::usb::feedback {

struct FeedbackPacketSchedulerSnapshot {
    bool configured = false;
    FeedbackRateQ32 rateQ32 = 0;
    uint32_t phaseQ32 = 0;
    uint32_t frameBytes = 0;
    uint32_t endpointCapacityBytes = 0;
    uint64_t scheduledPackets = 0;
    uint64_t scheduledFrames = 0;
    uint32_t minimumPacketFrames = 0;
    uint32_t maximumPacketFrames = 0;
    uint64_t rateUpdates = 0;
    uint64_t capacityRejects = 0;
};

class FeedbackPacketScheduler {
public:
    FeedbackMathStatus configure(
        FeedbackRateQ32 initialRateQ32,
        uint32_t frameBytes,
        uint32_t endpointCapacityBytes
    );

    FeedbackMathStatus updateRate(FeedbackRateQ32 rateQ32);

    FeedbackPacketPlan next();

    [[nodiscard]] FeedbackProjection project(uint64_t intervalCount) const;

    void resetPhase();

    [[nodiscard]] FeedbackPacketSchedulerSnapshot snapshot() const;

private:
    [[nodiscard]] FeedbackMathStatus validateRate(FeedbackRateQ32 rateQ32) const;

    FeedbackRateQ32 rateQ32_ = 0;
    uint32_t phaseQ32_ = 0;
    uint32_t frameBytes_ = 0;
    uint32_t endpointCapacityBytes_ = 0;
    uint32_t endpointCapacityFrames_ = 0;
    bool configured_ = false;
    uint64_t scheduledPackets_ = 0;
    uint64_t scheduledFrames_ = 0;
    uint32_t minimumPacketFrames_ = 0;
    uint32_t maximumPacketFrames_ = 0;
    uint64_t rateUpdates_ = 0;
    uint64_t capacityRejects_ = 0;
};

} // namespace neri::usb::feedback
