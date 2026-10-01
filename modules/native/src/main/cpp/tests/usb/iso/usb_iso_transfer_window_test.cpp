#include "usb/iso/usb_iso_transfer_window.h"

#include <cassert>

namespace {

void verifiesUac1FullSpeedBaselineStartup() {
    using namespace neri::usb;

    const IsoTransferWindowPlan uac1FullSpeed = planIsoTransferWindow(1000, 320, 3000);
    assert(uac1FullSpeed.packetsPerTransfer == 32);
    assert(uac1FullSpeed.baselineTransferCount == 10);
    assert(uac1FullSpeed.reserveTransferCount == 16);
    assert(isoTransferTargetCount(false, 1000, 32, 320, 10, 64) == 10);
    assert(!isoReserveWarmupComplete(0, 10));
    assert(!isoReserveWarmupComplete(9, 10));
    assert(isoReserveWarmupComplete(10, 10));
}

void verifiesUac1PauseReplayRestartsWarmup() {
    using namespace neri::usb;

    constexpr int baselineTransferCount = 10;
    constexpr int transferBytes = 6144;
    assert(isoReserveWarmupComplete(240, baselineTransferCount));
    assert(!isoReserveWarmupComplete(0, baselineTransferCount));
    assert(isoReserveActivationBudgetAfterWarmup(
        0,
        64U * transferBytes,
        transferBytes,
        baselineTransferCount,
        baselineTransferCount,
        64
    ) == 0);
    assert(isoReserveActivationBudgetAfterWarmup(
        baselineTransferCount,
        64U * transferBytes,
        transferBytes,
        baselineTransferCount,
        baselineTransferCount,
        baselineTransferCount
    ) == 0);
}

void verifiesUac1LifecycleWindowBoundaryAndSafetyWaterline() {
    using namespace neri::usb;

    constexpr int baselineTransferCount = 10;
    constexpr int transferBytes = 6144;
    const int backgroundTarget = isoTransferTargetCount(
        false,
        1000,
        32,
        3000,
        baselineTransferCount,
        64
    );
    const int foregroundTarget = isoTransferTargetCount(
        false,
        1000,
        32,
        400,
        baselineTransferCount,
        64
    );
    assert(backgroundTarget == baselineTransferCount);
    assert(foregroundTarget == baselineTransferCount);
    assert(shouldRetireIsoReserveTransfer(15, 10, 16, foregroundTarget));
    assert(!shouldRetireIsoReserveTransfer(9, 10, 64, foregroundTarget));
    assert(isoReserveActivationBudget(
        18U * transferBytes,
        transferBytes,
        baselineTransferCount,
        baselineTransferCount,
        backgroundTarget
    ) == 0);
    assert(isoReserveActivationBudget(
        10U * transferBytes,
        transferBytes,
        baselineTransferCount,
        baselineTransferCount,
        backgroundTarget
    ) == 0);
}

void verifiesUacTransportModeRegressionMatrix() {
    using namespace neri::usb;

    // UAC1 adaptive/synchronous routes have no explicit feedback clock.
    const IsoTransferWindowPlan uac1NoFeedback = planIsoTransferWindow(1000, 320, 3000);
    assert(uac1NoFeedback.packetsPerTransfer == 32);
    assert(uac1NoFeedback.baselineTransferCount == 10);
    assert(isoTransferTargetCount(false, 1000, 32, 400, 10, 16) == 10);
    assert(isoTransferTargetCount(false, 1000, 32, 3000, 10, 16) == 10);

    // High-speed adaptive/synchronous endpoints use short, fixed requests so
    // the host continues to deliver completions while keeping the active set
    // bounded. This covers the KM-HIFI completion-stall regression.
    const IsoTransferWindowPlan highSpeed = planIsoTransferWindow(8000, 160, 3000);
    assert(highSpeed.packetsPerTransfer == 64);
    assert(highSpeed.baselineTransferCount == 16);
    assert(highSpeed.reserveTransferCount == 16);
    assert(isoPacketsPerTransfer(4000) == 32);
    assert(isoPacketsPerTransfer(8000) == 64);
    assert(isoTransferTargetCount(false, 8000, 64, 400, 16, 16) == 16);
    assert(isoTransferTargetCount(false, 8000, 64, 3000, 16, 16) == 16);
    assert(isoReserveActivationBudgetAfterWarmup(
        16,
        18U * 12288U,
        12288,
        16,
        16,
        16
    ) == 0);

    // UAC2 asynchronous explicit-feedback routes retain their independent,
    // fixed audio-transfer set.
    const IsoTransferWindowPlan explicitFeedback = planIsoTransferWindow(
        8000,
        8,
        16,
        3000
    );
    assert(explicitFeedback.packetsPerTransfer == 8);
    assert(explicitFeedback.baselineTransferCount == 16);
    assert(explicitFeedback.reserveTransferCount == 16);
    assert(isoTransferTargetCount(true, 8000, 8, 100, 16, 64) == 16);
    assert(isoTransferTargetCount(true, 8000, 8, 3000, 16, 64) == 16);

    assert(isoTransferCountForDuration(8000, 128, 12000) == 256);
    assert(isoTransferCountForDuration(8000, 128, 400) == 25);
    assert(isoTransferCountForDuration(1000, 32, 1500) == 47);
    assert(isoReserveActivationBudget(18 * 6144, 6144, 10, 10, 16) == 6);
    assert(isoReserveActivationBudget(13 * 6144, 6144, 10, 14, 16) == 2);
    assert(isoReserveActivationBudget(10 * 6144, 6144, 10, 10, 16) == 0);
    assert(isoReserveActivationBudget(9 * 6144, 6144, 10, 10, 16) == 0);
    assert(isoReserveActivationBudget(18 * 6144, 6144, 10, 16, 16) == 0);
    assert(isoReserveActivationBudget(18 * 6144, 6144, 10, 20, 16) == 0);
    assert(isoReserveActivationBudget(18 * 6144, 6144, 10, 0, 0) == 0);
    assert(isoTransferTargetCount(false, 1000, 32, 3000, -1, 1000) == 8);

    assert(isoReserveWarmupComplete(11, 10));

    const int activatedAfterWarmup = isoReserveActivationBudget(
        18U * 6144U,
        6144,
        10,
        10,
        10
    );
    assert(activatedAfterWarmup == 0);

    assert(shouldRetireIsoReserveTransfer(15, 10, 16, 10));
    assert(!shouldRetireIsoReserveTransfer(9, 10, 16, 10));
    assert(!shouldRetireIsoReserveTransfer(10, 10, 10, 10));
}

} // namespace

int main() {
    verifiesUac1FullSpeedBaselineStartup();
    verifiesUac1PauseReplayRestartsWarmup();
    verifiesUac1LifecycleWindowBoundaryAndSafetyWaterline();
    verifiesUacTransportModeRegressionMatrix();
    return 0;
}
