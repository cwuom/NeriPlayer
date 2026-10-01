#include "usb/uac2/usb_uac2_clock_graph.h"

#include <algorithm>
#include <limits>
#include <map>

namespace neri::usb::uac2 {
namespace {

struct PathResult {
    ClockGraphStatus status = ClockGraphStatus::InvalidInput;
    int sourceId = 0;
    uint64_t numerator = 1;
    uint64_t denominator = 1;
    ClockValidityState validity = ClockValidityState::Unchecked;
    std::vector<int> path;
    std::string reason;
};

uint64_t greatestCommonDivisor(uint64_t left, uint64_t right) {
    while (right != 0) {
        const uint64_t remainder = left % right;
        left = right;
        right = remainder;
    }
    return left;
}

bool checkedMultiply(uint64_t left, uint64_t right, uint64_t* output) {
    if (output == nullptr ||
        (left != 0 && right > std::numeric_limits<uint64_t>::max() / left)) {
        return false;
    }
    *output = left * right;
    return true;
}

bool checkedAddPath(
    const std::vector<int>& path,
    int id,
    std::vector<int>* output
) {
    if (output == nullptr || std::find(path.begin(), path.end(), id) != path.end()) {
        return false;
    }
    *output = path;
    output->push_back(id);
    return true;
}

PathResult invalidPath(ClockGraphStatus status, const char* reason) {
    PathResult result;
    result.status = status;
    result.reason = reason;
    return result;
}

PathResult walkEntity(
    const std::map<int, const ClockEntity*>& entities,
    int entityId,
    const std::vector<int>& path,
    size_t maxDepth
) {
    if (path.size() >= maxDepth) {
        return invalidPath(ClockGraphStatus::DepthExceeded, "clock_graph_depth_exceeded");
    }
    const auto found = entities.find(entityId);
    if (found == entities.end()) {
        return invalidPath(ClockGraphStatus::MissingEntity, "clock_graph_entity_missing");
    }
    std::vector<int> nextPath;
    if (!checkedAddPath(path, entityId, &nextPath)) {
        return invalidPath(ClockGraphStatus::Cycle, "clock_graph_cycle");
    }
    const ClockEntity& entity = *found->second;
    switch (entity.kind) {
        case ClockEntityKind::ClockSource: {
            if (!entity.sourceIds.empty()) {
                return invalidPath(
                    ClockGraphStatus::InvalidInput,
                    "clock_source_has_input"
                );
            }
            if (entity.validityControlAdvertised &&
                entity.validityState == ClockValidityState::NotAdvertised) {
                return invalidPath(
                    ClockGraphStatus::InvalidInput,
                    "clock_validity_state_inconsistent"
                );
            }
            if (!entity.validityControlAdvertised &&
                entity.validityState != ClockValidityState::NotAdvertised) {
                return invalidPath(
                    ClockGraphStatus::InvalidInput,
                    "clock_validity_state_inconsistent"
                );
            }
            PathResult result;
            result.status = ClockGraphStatus::Valid;
            result.sourceId = entity.id;
            result.path = std::move(nextPath);
            result.validity = entity.validityControlAdvertised
                ? entity.validityState
                : ClockValidityState::NotAdvertised;
            return result;
        }
        case ClockEntityKind::Terminal: {
            if (entity.sourceIds.size() != 1U) {
                return invalidPath(
                    entity.sourceIds.empty()
                        ? ClockGraphStatus::MissingEntity
                        : ClockGraphStatus::AmbiguousPath,
                    entity.sourceIds.empty()
                        ? "terminal_clock_source_missing"
                        : "terminal_clock_source_ambiguous"
                );
            }
            return walkEntity(entities, entity.sourceIds.front(), nextPath, maxDepth);
        }
        case ClockEntityKind::ClockSelector: {
            if (entity.sourceIds.empty()) {
                return invalidPath(
                    ClockGraphStatus::MissingEntity,
                    "clock_selector_pin_missing"
                );
            }
            size_t selectedIndex = 0;
            if (entity.selectedSourceIndex >= 0) {
                selectedIndex = static_cast<size_t>(entity.selectedSourceIndex);
                if (selectedIndex >= entity.sourceIds.size()) {
                    return invalidPath(
                        ClockGraphStatus::InvalidInput,
                        "clock_selector_pin_invalid"
                    );
                }
            } else if (entity.sourceIds.size() != 1U) {
                return invalidPath(
                    ClockGraphStatus::AmbiguousPath,
                    "clock_selector_pin_unselected"
                );
            }
            return walkEntity(
                entities,
                entity.sourceIds[selectedIndex],
                nextPath,
                maxDepth
            );
        }
        case ClockEntityKind::ClockMultiplier: {
            if (entity.sourceIds.size() != 1U) {
                return invalidPath(
                    entity.sourceIds.empty()
                        ? ClockGraphStatus::MissingEntity
                        : ClockGraphStatus::AmbiguousPath,
                    entity.sourceIds.empty()
                        ? "clock_multiplier_input_missing"
                        : "clock_multiplier_input_ambiguous"
                );
            }
            if (entity.multiplierNumerator == 0 ||
                entity.multiplierDenominator == 0) {
                return invalidPath(
                    ClockGraphStatus::InvalidMultiplier,
                    "clock_multiplier_ratio_invalid"
                );
            }
            PathResult result = walkEntity(
                entities,
                entity.sourceIds.front(),
                nextPath,
                maxDepth
            );
            if (result.status != ClockGraphStatus::Valid) {
                return result;
            }
            const uint64_t numeratorGcd = greatestCommonDivisor(
                result.numerator,
                entity.multiplierDenominator
            );
            const uint64_t denominatorGcd = greatestCommonDivisor(
                entity.multiplierNumerator,
                result.denominator
            );
            const uint64_t leftNumerator = result.numerator / numeratorGcd;
            const uint64_t rightNumerator =
                entity.multiplierNumerator / denominatorGcd;
            const uint64_t leftDenominator = result.denominator / denominatorGcd;
            const uint64_t rightDenominator =
                entity.multiplierDenominator / numeratorGcd;
            if (!checkedMultiply(leftNumerator, rightNumerator, &result.numerator) ||
                !checkedMultiply(leftDenominator, rightDenominator, &result.denominator)) {
                return invalidPath(
                    ClockGraphStatus::InvalidMultiplier,
                    "clock_multiplier_ratio_overflow"
                );
            }
            const uint64_t ratioGcd = greatestCommonDivisor(
                result.numerator,
                result.denominator
            );
            result.numerator /= ratioGcd;
            result.denominator /= ratioGcd;
            return result;
        }
        case ClockEntityKind::Unknown:
            return invalidPath(
                ClockGraphStatus::InvalidInput,
                "clock_entity_kind_unknown"
            );
    }
    return invalidPath(ClockGraphStatus::InvalidInput, "clock_entity_kind_unknown");
}

PathResult resolveFunction(
    const AudioFunctionClockGraph& function,
    int terminalLink,
    size_t maxDepth
) {
    if (function.audioControlInterface < 0) {
        return invalidPath(
            ClockGraphStatus::InvalidInput,
            "audio_control_interface_invalid"
        );
    }
    std::map<int, const ClockEntity*> entities;
    for (const ClockEntity& entity : function.entities) {
        if (entity.id <= 0 || entity.kind == ClockEntityKind::Unknown) {
            return invalidPath(
                ClockGraphStatus::InvalidInput,
                "clock_entity_invalid"
            );
        }
        if (!entities.emplace(entity.id, &entity).second) {
            return invalidPath(
                ClockGraphStatus::DuplicateEntity,
                "clock_entity_duplicate"
            );
        }
    }
    const auto terminal = std::find_if(
        function.entities.begin(),
        function.entities.end(),
        [terminalLink](const ClockEntity& entity) {
            return entity.kind == ClockEntityKind::Terminal &&
                entity.id == terminalLink;
        }
    );
    if (terminal == function.entities.end()) {
        return invalidPath(
            ClockGraphStatus::MissingTerminal,
            "clock_terminal_missing"
        );
    }
    return walkEntity(entities, terminalLink, {}, maxDepth);
}

} // namespace

ClockGraphResult resolveClockGraph(
    const std::vector<AudioFunctionClockGraph>& functions,
    int terminalLink,
    size_t maxDepth
) {
    ClockGraphResult result;
    result.terminalLink = terminalLink;
    if (terminalLink <= 0 || functions.empty() || maxDepth == 0 || maxDepth > 64U) {
        result.status = ClockGraphStatus::InvalidInput;
        result.reason = "clock_graph_input_invalid";
        return result;
    }
    std::map<int, size_t> audioControlInterfaces;
    for (size_t index = 0; index < functions.size(); ++index) {
        if (functions[index].audioControlInterface < 0) {
            result.status = ClockGraphStatus::InvalidInput;
            result.reason = "audio_control_interface_invalid";
            return result;
        }
        if (!audioControlInterfaces.emplace(
                functions[index].audioControlInterface,
                index
            ).second) {
            result.status = ClockGraphStatus::CrossFunctionAmbiguous;
            result.reason = "audio_control_interface_duplicate";
            return result;
        }
    }

    size_t matchingFunctions = 0;
    size_t matchingIndex = 0;
    for (size_t index = 0; index < functions.size(); ++index) {
        const bool hasTerminal = std::any_of(
            functions[index].entities.begin(),
            functions[index].entities.end(),
            [terminalLink](const ClockEntity& entity) {
                return entity.kind == ClockEntityKind::Terminal &&
                    entity.id == terminalLink;
            }
        );
        if (hasTerminal) {
            ++matchingFunctions;
            matchingIndex = index;
        }
    }
    if (matchingFunctions == 0) {
        result.status = ClockGraphStatus::MissingTerminal;
        result.reason = "clock_terminal_missing";
        return result;
    }
    if (matchingFunctions != 1U) {
        result.status = ClockGraphStatus::CrossFunctionAmbiguous;
        result.reason = "clock_terminal_cross_function_ambiguous";
        return result;
    }

    const AudioFunctionClockGraph& function = functions[matchingIndex];
    const PathResult path = resolveFunction(function, terminalLink, maxDepth);
    result.status = path.status;
    result.audioControlInterface = function.audioControlInterface;
    result.finalClockSourceId = path.sourceId;
    result.multiplierNumerator = path.numerator;
    result.multiplierDenominator = path.denominator;
    result.validity = path.validity;
    result.traversedEntities = path.path;
    result.reason = path.reason;
    return result;
}

const char* clockGraphStatusName(ClockGraphStatus status) {
    switch (status) {
        case ClockGraphStatus::Valid:
            return "valid";
        case ClockGraphStatus::InvalidInput:
            return "invalid_input";
        case ClockGraphStatus::DuplicateEntity:
            return "duplicate_entity";
        case ClockGraphStatus::MissingTerminal:
            return "missing_terminal";
        case ClockGraphStatus::MissingEntity:
            return "missing_entity";
        case ClockGraphStatus::AmbiguousPath:
            return "ambiguous_path";
        case ClockGraphStatus::CrossFunctionAmbiguous:
            return "cross_function_ambiguous";
        case ClockGraphStatus::Cycle:
            return "cycle";
        case ClockGraphStatus::DepthExceeded:
            return "depth_exceeded";
        case ClockGraphStatus::InvalidMultiplier:
            return "invalid_multiplier";
    }
    return "unknown";
}

const char* clockValidityStateName(ClockValidityState state) {
    switch (state) {
        case ClockValidityState::NotAdvertised:
            return "not_advertised";
        case ClockValidityState::Unchecked:
            return "unchecked";
        case ClockValidityState::Valid:
            return "valid";
        case ClockValidityState::Invalid:
            return "invalid";
        case ClockValidityState::IoError:
            return "io_error";
    }
    return "unknown";
}

} // namespace neri::usb::uac2
