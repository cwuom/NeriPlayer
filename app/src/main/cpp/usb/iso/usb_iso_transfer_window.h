#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace neri::usb {

constexpr int kDefaultIsoPacketsPerTransfer = 32;
constexpr int kMinimumIsoTransferCount = 8;
constexpr int kMaximumIsoPacketsPerTransfer = 128;
constexpr int kMaximumIsoTransferCount = 256;
constexpr int kMaximumHighSpeedIsoTransferDurationMs = 8;
// Android USB hosts have shown completion stalls when a player PCM stream
// keeps dozens of isochronous requests in flight. Keep the transport window
// bounded; long scheduling tolerance belongs in the PCM ring instead.
constexpr int kMaximumActiveIsoTransferCount = 16;
constexpr int kMaximumReserveActivationsPerWrite = 8;

struct IsoTransferWindowPlan {
    int packetsPerTransfer = kDefaultIsoPacketsPerTransfer;
    int baselineTransferCount = kMinimumIsoTransferCount;
    int reserveTransferCount = kMinimumIsoTransferCount;
};

inline int isoPacketsPerTransfer(int intervalsPerSecond) {
    const int normalizedIntervals = std::max(1, intervalsPerSecond);
    const int intervalRatio = std::max(1, normalizedIntervals / 1000);
    const int scaledPackets = std::clamp(
        kDefaultIsoPacketsPerTransfer * intervalRatio,
        kDefaultIsoPacketsPerTransfer,
        kMaximumIsoPacketsPerTransfer
    );
    if (normalizedIntervals <= 1000) {
        return scaledPackets;
    }
    const int maxPacketsForShortHighSpeedTransfer = static_cast<int>(
        std::clamp<int64_t>(
            (static_cast<int64_t>(normalizedIntervals) *
                kMaximumHighSpeedIsoTransferDurationMs) / 1000,
            kDefaultIsoPacketsPerTransfer,
            kMaximumIsoPacketsPerTransfer
        )
    );
    return std::min(scaledPackets, maxPacketsForShortHighSpeedTransfer);
}

inline int isoTransferCountForDuration(
    int intervalsPerSecond,
    int packetsPerTransfer,
    int durationMs
) {
    const int64_t normalizedIntervals = std::max(1, intervalsPerSecond);
    const int64_t normalizedPackets = std::max(1, packetsPerTransfer);
    const int64_t normalizedDurationMs = std::max(1, durationMs);
    const int64_t targetPackets = std::max<int64_t>(
        normalizedPackets,
        (normalizedIntervals * normalizedDurationMs + 999) / 1000
    );
    const int64_t requiredTransfers =
        (targetPackets + normalizedPackets - 1) / normalizedPackets;
    return static_cast<int>(std::clamp<int64_t>(
        requiredTransfers,
        kMinimumIsoTransferCount,
        kMaximumIsoTransferCount
    ));
}

inline IsoTransferWindowPlan planIsoTransferWindow(
    int intervalsPerSecond,
    int packetsPerTransfer,
    int baselineTransferCount,
    int reserveDurationMs
) {
    const int normalizedPackets = std::clamp(
        packetsPerTransfer,
        1,
        kMaximumIsoPacketsPerTransfer
    );
    const int normalizedBaseline = std::clamp(
        baselineTransferCount,
        kMinimumIsoTransferCount,
        kMaximumActiveIsoTransferCount
    );
    const int reserveTransferCount = std::clamp(
        isoTransferCountForDuration(
            intervalsPerSecond,
            normalizedPackets,
            reserveDurationMs
        ),
        normalizedBaseline,
        kMaximumActiveIsoTransferCount
    );
    return IsoTransferWindowPlan {
        normalizedPackets,
        normalizedBaseline,
        reserveTransferCount
    };
}

inline IsoTransferWindowPlan planIsoTransferWindow(
    int intervalsPerSecond,
    int baselineDurationMs,
    int reserveDurationMs
) {
    const int packetsPerTransfer = isoPacketsPerTransfer(intervalsPerSecond);
    const int baselineTransferCount = isoTransferCountForDuration(
        intervalsPerSecond,
        packetsPerTransfer,
        baselineDurationMs
    );
    return planIsoTransferWindow(
        intervalsPerSecond,
        packetsPerTransfer,
        baselineTransferCount,
        std::max(baselineDurationMs, reserveDurationMs)
    );
}

inline int isoTransferTargetCount(
    bool,
    int,
    int,
    int,
    int baselineTransferCount,
    int reserveTransferCount
) {
    const int normalizedBaseline = std::clamp(
        baselineTransferCount,
        kMinimumIsoTransferCount,
        kMaximumActiveIsoTransferCount
    );
    const int normalizedReserve = std::clamp(
        reserveTransferCount,
        normalizedBaseline,
        kMaximumActiveIsoTransferCount
    );
    // The PCM ring owns foreground/background scheduling tolerance. Expanding
    // submitted USB transfers after warmup caused no-feedback UAC1/UAC2
    // endpoints to stop completing requests, while explicit-feedback routes
    // already use a fixed transfer set. Keep every supported route at its
    // protocol-selected baseline until a separate clocked reserve scheduler
    // is available.
    return std::min(normalizedBaseline, normalizedReserve);
}

inline int isoReserveActivationBudget(
    size_t queuedPcmBytes,
    int transferBytes,
    int protectedTransferCount,
    int activeTransferCount,
    int targetTransferCount
) {
    if (transferBytes <= 0 || targetTransferCount <= 0 ||
        activeTransferCount >= targetTransferCount) {
        return 0;
    }
    const size_t fullTransfersAvailable =
        queuedPcmBytes / static_cast<size_t>(transferBytes);
    const size_t protectedTransfers = static_cast<size_t>(
        std::max(0, protectedTransferCount)
    );
    const size_t activatableTransfers = fullTransfersAvailable > protectedTransfers
        ? fullTransfersAvailable - protectedTransfers
        : 0;
    const int missingTransfers = targetTransferCount - activeTransferCount;
    return std::max(0, std::min({
        static_cast<int>(std::min<size_t>(
            activatableTransfers,
            static_cast<size_t>(kMaximumIsoTransferCount)
        )),
        missingTransfers,
        kMaximumReserveActivationsPerWrite
    }));
}

inline bool isoReserveWarmupComplete(
    int completedTransferCount,
    int baselineTransferCount
) {
    return completedTransferCount >= std::max(1, baselineTransferCount);
}

inline int isoReserveActivationBudgetAfterWarmup(
    int completedTransferCount,
    size_t queuedPcmBytes,
    int transferBytes,
    int baselineTransferCount,
    int activeTransferCount,
    int targetTransferCount
) {
    if (!isoReserveWarmupComplete(
            completedTransferCount,
            baselineTransferCount
        )) {
        return 0;
    }
    return isoReserveActivationBudget(
        queuedPcmBytes,
        transferBytes,
        baselineTransferCount,
        activeTransferCount,
        targetTransferCount
    );
}

inline bool shouldRetireIsoReserveTransfer(
    int transferSlot,
    int baselineTransferCount,
    int activeTransferCount,
    int targetTransferCount
) {
    return transferSlot >= baselineTransferCount &&
        activeTransferCount > targetTransferCount;
}

} // namespace neri::usb
