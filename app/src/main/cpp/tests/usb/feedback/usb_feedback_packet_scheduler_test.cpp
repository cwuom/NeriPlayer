#include "usb/feedback/usb_feedback_packet_scheduler.h"

#include <cassert>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <random>

namespace {

using neri::usb::feedback::FeedbackMathStatus;
using neri::usb::feedback::FeedbackPacketScheduler;
using neri::usb::feedback::FeedbackRateQ32;
using neri::usb::feedback::kQ32One;

uint64_t configuredSeed() {
    const char* value = std::getenv("NERI_USB_TEST_SEED");
    return value == nullptr ? UINT64_C(324508639) : std::strtoull(value, nullptr, 10);
}

void requireStress(
    bool condition,
    uint64_t seed,
    int iteration,
    const char* check
) {
    if (condition) {
        return;
    }
    std::fprintf(
        stderr,
        "seed=%llu iteration=%d check=%s\n",
        static_cast<unsigned long long>(seed),
        iteration,
        check
    );
    std::abort();
}

FeedbackRateQ32 rateFor(uint32_t sampleRate, uint32_t intervalsPerSecond) {
    FeedbackRateQ32 rate = 0;
    assert(neri::usb::feedback::makeFeedbackRateQ32(
        sampleRate,
        intervalsPerSecond,
        &rate
    ) == FeedbackMathStatus::Ok);
    return rate;
}

void conservesFramesForFortyFourPointOneKilohertz() {
    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(rateFor(44100, 1000), 4, 45U * 4U) ==
        FeedbackMathStatus::Ok);
    uint64_t frames = 0;
    for (int packet = 0; packet < 1000; ++packet) {
        const auto plan = scheduler.next();
        assert(plan.status == FeedbackMathStatus::Ok);
        assert(plan.frames == 44U || plan.frames == 45U);
        assert(plan.bytes == plan.frames * 4U);
        frames += plan.frames;
    }
    assert(frames == 44100U);
    const auto snapshot = scheduler.snapshot();
    assert(snapshot.scheduledFrames == frames);
    assert(snapshot.minimumPacketFrames == 44U);
    assert(snapshot.maximumPacketFrames == 45U);
}

void rateUpdatesPreservePhaseAndCapacityFailuresDoNotMutateIt() {
    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(rateFor(44100, 1000), 8, 48U * 8U) ==
        FeedbackMathStatus::Ok);
    assert(scheduler.next().frames == 44U);
    const uint32_t phaseBeforeUpdate = scheduler.snapshot().phaseQ32;
    assert(phaseBeforeUpdate != 0);
    assert(scheduler.updateRate(rateFor(48000, 1000)) ==
        FeedbackMathStatus::Ok);
    assert(scheduler.next().frames == 48U);
    assert(scheduler.snapshot().phaseQ32 == phaseBeforeUpdate);

    const auto beforeReject = scheduler.snapshot();
    assert(scheduler.updateRate(rateFor(96000, 1000)) ==
        FeedbackMathStatus::CapacityExceeded);
    const auto afterReject = scheduler.snapshot();
    assert(afterReject.rateQ32 == beforeReject.rateQ32);
    assert(afterReject.phaseQ32 == beforeReject.phaseQ32);
    assert(afterReject.capacityRejects == beforeReject.capacityRejects + 1U);
}

void projectsSeventyTwoHoursInConstantTime() {
    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(rateFor(48000, 8000), 8, 6U * 8U) ==
        FeedbackMathStatus::Ok);
    constexpr uint64_t seconds = 72U * 60U * 60U;
    const auto projection = scheduler.project(seconds * 8000U);
    assert(projection.status == FeedbackMathStatus::Ok);
    assert(projection.totalFrames == seconds * 48000U);
    assert(projection.remainderQ32 == 0);
}

void fixedSeedStressMatchesExactQ32Accumulation() {
    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(UINT64_C(6) * kQ32One, 8, 7U * 8U) ==
        FeedbackMathStatus::Ok);
    const uint64_t seed = configuredSeed();
    std::mt19937_64 random(seed);
    std::uniform_int_distribution<int64_t> delta(
        -static_cast<int64_t>(kQ32One / 4U),
        static_cast<int64_t>(kQ32One / 4U)
    );
    uint32_t expectedPhase = 0;
    uint64_t expectedFrames = 0;
    for (int iteration = 0; iteration < 100000; ++iteration) {
        const int64_t signedRate =
            static_cast<int64_t>(UINT64_C(6) * kQ32One) + delta(random);
        const FeedbackRateQ32 rate = static_cast<FeedbackRateQ32>(signedRate);
        requireStress(
            scheduler.updateRate(rate) == FeedbackMathStatus::Ok,
            seed,
            iteration,
            "update_rate"
        );
        const uint64_t total = rate + expectedPhase;
        const uint32_t expectedPacketFrames = static_cast<uint32_t>(total >> 32U);
        expectedPhase = static_cast<uint32_t>(total & (kQ32One - 1U));
        const auto plan = scheduler.next();
        requireStress(
            plan.status == FeedbackMathStatus::Ok,
            seed,
            iteration,
            "next_status"
        );
        requireStress(
            plan.frames == expectedPacketFrames,
            seed,
            iteration,
            "packet_frames"
        );
        requireStress(
            scheduler.snapshot().phaseQ32 == expectedPhase,
            seed,
            iteration,
            "phase"
        );
        expectedFrames += expectedPacketFrames;
    }
    assert(scheduler.snapshot().scheduledFrames == expectedFrames);
}

void rejectsInvalidConfigurationAndCounterOverflowProjection() {
    FeedbackPacketScheduler scheduler;
    assert(scheduler.configure(0, 8, 48) == FeedbackMathStatus::OutOfRange);
    assert(scheduler.configure(UINT64_C(48) * kQ32One, 8, 47) ==
        FeedbackMathStatus::CapacityExceeded);
    const auto projection = neri::usb::feedback::projectScheduledFrames(
        std::numeric_limits<uint64_t>::max(),
        0,
        std::numeric_limits<uint64_t>::max()
    );
    assert(projection.status == FeedbackMathStatus::Overflow);
}

} // namespace

int main() {
    conservesFramesForFortyFourPointOneKilohertz();
    rateUpdatesPreservePhaseAndCapacityFailuresDoNotMutateIt();
    projectsSeventyTwoHoursInConstantTime();
    fixedSeedStressMatchesExactQ32Accumulation();
    rejectsInvalidConfigurationAndCounterOverflowProjection();
    return 0;
}
