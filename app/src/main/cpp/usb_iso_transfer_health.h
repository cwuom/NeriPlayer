#pragma once

#include <algorithm>

namespace neri::usb {

constexpr int kIsoPacketErrorFailureScore = 8;

inline int updateIsoPacketErrorScore(
    int currentScore,
    int failedPacketCount
) {
    if (failedPacketCount <= 0) {
        return std::max(0, currentScore - 1);
    }
    return std::min(
        kIsoPacketErrorFailureScore,
        currentScore + failedPacketCount
    );
}

inline bool shouldFailForIsoPacketErrors(int errorScore) {
    return errorScore >= kIsoPacketErrorFailureScore;
}

} // namespace neri::usb
