#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace neri::usb::uac2 {

enum class ClockEntityKind {
    Unknown,
    ClockSource,
    ClockSelector,
    ClockMultiplier,
    Terminal
};

enum class ClockGraphStatus {
    Valid,
    InvalidInput,
    DuplicateEntity,
    MissingTerminal,
    MissingEntity,
    AmbiguousPath,
    CrossFunctionAmbiguous,
    Cycle,
    DepthExceeded,
    InvalidMultiplier
};

enum class ClockValidityState {
    NotAdvertised,
    Unchecked,
    Valid,
    Invalid,
    IoError
};

struct ClockEntity {
    int id = 0;
    ClockEntityKind kind = ClockEntityKind::Unknown;
    std::vector<int> sourceIds;
    int selectedSourceIndex = -1;
    uint64_t multiplierNumerator = 1;
    uint64_t multiplierDenominator = 1;
    bool validityControlAdvertised = false;
    ClockValidityState validityState = ClockValidityState::NotAdvertised;
};

struct AudioFunctionClockGraph {
    int audioControlInterface = -1;
    std::vector<ClockEntity> entities;
};

struct ClockGraphResult {
    ClockGraphStatus status = ClockGraphStatus::InvalidInput;
    int audioControlInterface = -1;
    int terminalLink = 0;
    int finalClockSourceId = 0;
    uint64_t multiplierNumerator = 1;
    uint64_t multiplierDenominator = 1;
    ClockValidityState validity = ClockValidityState::Unchecked;
    std::vector<int> traversedEntities;
    std::string reason;
};

ClockGraphResult resolveClockGraph(
    const std::vector<AudioFunctionClockGraph>& functions,
    int terminalLink,
    size_t maxDepth = 16
);

const char* clockGraphStatusName(ClockGraphStatus status);
const char* clockValidityStateName(ClockValidityState state);

} // namespace neri::usb::uac2
