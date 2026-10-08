#pragma once

#include "usb/uac2/usb_uac2_format.h"

#include <cstddef>
#include <cstdint>
#include <functional>
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
    // 倍频比没从设备读到时，经过这个倍频器的路径换算不出上游时钟源的频率
    bool multiplierRatioKnown = true;
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

// 返回选择器当前输入脚（从 0 计）；读不到时返回负数，按第一个输入脚处理
using ClockSelectorPinReader = std::function<int(int selectorId, int pinCount)>;

// 终端经时钟选择器/倍频器连到时钟源时，按选择器当前输入脚走到最终时钟源。
// 描述符不带倍频比，经过倍频器的路径返回 InvalidMultiplier，不把终端采样率当成上游时钟源的频率
ClockGraphResult resolveTerminalClockSource(
    int audioControlInterface,
    int terminalLink,
    int terminalClockId,
    const std::vector<int>& clockSourceIds,
    const std::vector<ClockRouting>& routings,
    const ClockSelectorPinReader& readSelectorPin
);

const char* clockGraphStatusName(ClockGraphStatus status);
const char* clockValidityStateName(ClockValidityState state);

} // namespace neri::usb::uac2
