#include "usb/feedback/usb_feedback_packet_scheduler.h"

#include <algorithm>
#include <limits>

namespace neri::usb::feedback {

FeedbackMathStatus FeedbackPacketScheduler::configure(
    FeedbackRateQ32 initialRateQ32,
    uint32_t frameBytes,
    uint32_t endpointCapacityBytes
) {
    configured_ = false;
    rateQ32_ = 0;
    phaseQ32_ = 0;
    frameBytes_ = 0;
    endpointCapacityBytes_ = 0;
    endpointCapacityFrames_ = 0;
    scheduledPackets_ = 0;
    scheduledFrames_ = 0;
    minimumPacketFrames_ = 0;
    maximumPacketFrames_ = 0;
    rateUpdates_ = 0;
    capacityRejects_ = 0;
    if (frameBytes == 0 || frameBytes > 32U || endpointCapacityBytes == 0) {
        return FeedbackMathStatus::InvalidArgument;
    }
    const uint32_t capacityFrames = endpointCapacityBytes / frameBytes;
    if (capacityFrames == 0) {
        return FeedbackMathStatus::CapacityExceeded;
    }
    frameBytes_ = frameBytes;
    endpointCapacityBytes_ = endpointCapacityBytes;
    endpointCapacityFrames_ = capacityFrames;
    const FeedbackMathStatus rateStatus = validateRate(initialRateQ32);
    if (rateStatus != FeedbackMathStatus::Ok) {
        frameBytes_ = 0;
        endpointCapacityBytes_ = 0;
        endpointCapacityFrames_ = 0;
        return rateStatus;
    }

    rateQ32_ = initialRateQ32;
    phaseQ32_ = 0;
    scheduledPackets_ = 0;
    scheduledFrames_ = 0;
    minimumPacketFrames_ = 0;
    maximumPacketFrames_ = 0;
    rateUpdates_ = 0;
    capacityRejects_ = 0;
    configured_ = true;
    return FeedbackMathStatus::Ok;
}

FeedbackMathStatus FeedbackPacketScheduler::updateRate(FeedbackRateQ32 rateQ32) {
    if (!configured_) {
        return FeedbackMathStatus::NotReady;
    }
    const FeedbackMathStatus status = validateRate(rateQ32);
    if (status != FeedbackMathStatus::Ok) {
        if (status == FeedbackMathStatus::CapacityExceeded &&
            capacityRejects_ != std::numeric_limits<uint64_t>::max()) {
            ++capacityRejects_;
        }
        return status;
    }
    if (rateUpdates_ == std::numeric_limits<uint64_t>::max()) {
        return FeedbackMathStatus::Overflow;
    }
    rateQ32_ = rateQ32;
    ++rateUpdates_;
    return FeedbackMathStatus::Ok;
}

FeedbackPacketPlan FeedbackPacketScheduler::next() {
    FeedbackPacketPlan result;
    if (!configured_) {
        result.status = FeedbackMathStatus::NotReady;
        return result;
    }
    const FeedbackProjection projection = projectScheduledFrames(rateQ32_, phaseQ32_, 1);
    if (projection.status != FeedbackMathStatus::Ok ||
        projection.totalFrames > endpointCapacityFrames_) {
        result.status = projection.status == FeedbackMathStatus::Ok
            ? FeedbackMathStatus::CapacityExceeded
            : projection.status;
        if (result.status == FeedbackMathStatus::CapacityExceeded &&
            capacityRejects_ != std::numeric_limits<uint64_t>::max()) {
            ++capacityRejects_;
        }
        return result;
    }
    const uint64_t bytes = projection.totalFrames * frameBytes_;
    if (bytes > endpointCapacityBytes_ || bytes > std::numeric_limits<uint32_t>::max()) {
        result.status = FeedbackMathStatus::CapacityExceeded;
        if (capacityRejects_ != std::numeric_limits<uint64_t>::max()) {
            ++capacityRejects_;
        }
        return result;
    }
    if (scheduledPackets_ == std::numeric_limits<uint64_t>::max() ||
        projection.totalFrames >
            std::numeric_limits<uint64_t>::max() - scheduledFrames_) {
        result.status = FeedbackMathStatus::Overflow;
        return result;
    }

    phaseQ32_ = projection.remainderQ32;
    ++scheduledPackets_;
    scheduledFrames_ += projection.totalFrames;
    const uint32_t frames = static_cast<uint32_t>(projection.totalFrames);
    minimumPacketFrames_ = scheduledPackets_ == 1
        ? frames
        : std::min(minimumPacketFrames_, frames);
    maximumPacketFrames_ = std::max(maximumPacketFrames_, frames);
    result.status = FeedbackMathStatus::Ok;
    result.frames = frames;
    result.bytes = static_cast<uint32_t>(bytes);
    return result;
}

FeedbackProjection FeedbackPacketScheduler::project(uint64_t intervalCount) const {
    if (!configured_) {
        return FeedbackProjection {};
    }
    return projectScheduledFrames(rateQ32_, phaseQ32_, intervalCount);
}

void FeedbackPacketScheduler::resetPhase() {
    phaseQ32_ = 0;
}

FeedbackPacketSchedulerSnapshot FeedbackPacketScheduler::snapshot() const {
    return FeedbackPacketSchedulerSnapshot {
        configured_,
        rateQ32_,
        phaseQ32_,
        frameBytes_,
        endpointCapacityBytes_,
        scheduledPackets_,
        scheduledFrames_,
        minimumPacketFrames_,
        maximumPacketFrames_,
        rateUpdates_,
        capacityRejects_
    };
}

FeedbackMathStatus FeedbackPacketScheduler::validateRate(
    FeedbackRateQ32 rateQ32
) const {
    if (rateQ32 == 0 || rateQ32 > kMaxSupportedRateQ32) {
        return FeedbackMathStatus::OutOfRange;
    }
    const uint64_t wholeFrames = rateQ32 >> 32U;
    const uint64_t fractionalFrames = (rateQ32 & (kQ32One - 1U)) == 0 ? 0 : 1;
    const uint64_t maximumFrames = wholeFrames + fractionalFrames;
    if (maximumFrames > endpointCapacityFrames_) {
        return FeedbackMathStatus::CapacityExceeded;
    }
    return FeedbackMathStatus::Ok;
}

} // namespace neri::usb::feedback
