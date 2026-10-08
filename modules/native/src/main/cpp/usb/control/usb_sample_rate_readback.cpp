#include "usb/control/usb_sample_rate_readback.h"

namespace neri::usb::control {
namespace {

constexpr int kSettleDelaysMs[] = { 5, 10, 20 };
constexpr int kSettleAttempts = static_cast<int>(sizeof(kSettleDelaysMs) / sizeof(kSettleDelaysMs[0]));

} // namespace

SampleRateReadback classifySampleRateReadback(int requestedRate, int reportedRate, int attempt) {
    if (reportedRate == requestedRate) return SampleRateReadback::Verified;
    if (reportedRate <= 0) return SampleRateReadback::AcceptUnverified;
    return attempt < kSettleAttempts ? SampleRateReadback::Settle : SampleRateReadback::AcceptUnverified;
}

int sampleRateSettleDelayMs(int attempt) {
    if (attempt < 0) return kSettleDelaysMs[0];
    return attempt < kSettleAttempts ? kSettleDelaysMs[attempt] : kSettleDelaysMs[kSettleAttempts - 1];
}

} // namespace neri::usb::control
