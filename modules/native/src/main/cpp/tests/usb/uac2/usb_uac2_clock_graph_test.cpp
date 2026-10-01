#include "usb/uac2/usb_uac2_clock_graph.h"

#include <cassert>
#include <cstdint>
#include <string>
#include <vector>

namespace {

using neri::usb::uac2::AudioFunctionClockGraph;
using neri::usb::uac2::ClockEntity;
using neri::usb::uac2::ClockEntityKind;
using neri::usb::uac2::ClockGraphStatus;
using neri::usb::uac2::ClockValidityState;

ClockEntity source(int id, bool validityAdvertised = false) {
    ClockEntity entity;
    entity.id = id;
    entity.kind = ClockEntityKind::ClockSource;
    entity.validityControlAdvertised = validityAdvertised;
    entity.validityState = validityAdvertised
        ? ClockValidityState::Unchecked
        : ClockValidityState::NotAdvertised;
    return entity;
}

ClockEntity terminal(int id, int sourceId) {
    ClockEntity entity;
    entity.id = id;
    entity.kind = ClockEntityKind::Terminal;
    entity.sourceIds.push_back(sourceId);
    return entity;
}

AudioFunctionClockGraph functionWith(std::vector<ClockEntity> entities) {
    return AudioFunctionClockGraph { 1, std::move(entities) };
}

void resolvesDirectSourceAndPreservesValidityState() {
    const auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 4), source(4) }) },
        3
    );
    assert(result.status == ClockGraphStatus::Valid);
    assert(result.audioControlInterface == 1);
    assert(result.finalClockSourceId == 4);
    assert(result.traversedEntities == std::vector<int>({ 3, 4 }));
    assert(result.validity == ClockValidityState::NotAdvertised);
}

void resolvesSelectedSelectorPinAndRejectsUnselectedMultiplePins() {
    ClockEntity selector;
    selector.id = 10;
    selector.kind = ClockEntityKind::ClockSelector;
    selector.sourceIds = { 4, 5 };
    selector.selectedSourceIndex = 1;
    auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 10), selector, source(4), source(5) }) },
        3
    );
    assert(result.status == ClockGraphStatus::Valid);
    assert(result.finalClockSourceId == 5);

    selector.selectedSourceIndex = -1;
    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 10), selector, source(4), source(5) }) },
        3
    );
    assert(result.status == ClockGraphStatus::AmbiguousPath);
    assert(result.reason == "clock_selector_pin_unselected");
}

void resolvesMultiplierRatioWithReduction() {
    ClockEntity multiplier;
    multiplier.id = 10;
    multiplier.kind = ClockEntityKind::ClockMultiplier;
    multiplier.sourceIds = { 4 };
    multiplier.multiplierNumerator = 6;
    multiplier.multiplierDenominator = 9;
    const auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 10), multiplier, source(4) }) },
        3
    );
    assert(result.status == ClockGraphStatus::Valid);
    assert(result.finalClockSourceId == 4);
    assert(result.multiplierNumerator == 2);
    assert(result.multiplierDenominator == 3);
}

void rejectsMissingCycleDuplicateAndInvalidMultiplier() {
    auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 99) }) },
        3
    );
    assert(result.status == ClockGraphStatus::MissingEntity);

    ClockEntity first = terminal(3, 10);
    ClockEntity second = terminal(10, 3);
    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ first, second }) },
        3
    );
    assert(result.status == ClockGraphStatus::Cycle);

    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 4), source(4), source(4) }) },
        3
    );
    assert(result.status == ClockGraphStatus::DuplicateEntity);

    ClockEntity multiplier;
    multiplier.id = 10;
    multiplier.kind = ClockEntityKind::ClockMultiplier;
    multiplier.sourceIds = { 4 };
    multiplier.multiplierDenominator = 0;
    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 10), multiplier, source(4) }) },
        3
    );
    assert(result.status == ClockGraphStatus::InvalidMultiplier);
}

void rejectsCrossFunctionAmbiguityAndDepthLimit() {
    const auto function = functionWith({ terminal(3, 4), source(4) });
    auto result = neri::usb::uac2::resolveClockGraph(
        { function, function },
        3
    );
    assert(result.status == ClockGraphStatus::CrossFunctionAmbiguous);

    result = neri::usb::uac2::resolveClockGraph(
        { function },
        3,
        1
    );
    assert(result.status == ClockGraphStatus::DepthExceeded);

    AudioFunctionClockGraph duplicateInterface = function;
    duplicateInterface.entities = { terminal(7, 8), source(8) };
    result = neri::usb::uac2::resolveClockGraph(
        { function, duplicateInterface },
        3
    );
    assert(result.status == ClockGraphStatus::CrossFunctionAmbiguous);
    assert(result.reason == "audio_control_interface_duplicate");
}

void preservesAdvertisedValidityObservation() {
    const auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 4), source(4, true) }) },
        3
    );
    assert(result.status == ClockGraphStatus::Valid);
    assert(result.validity == ClockValidityState::Unchecked);
    assert(std::string(neri::usb::uac2::clockValidityStateName(
        result.validity
    )) == "unchecked");
}

void rejectsInconsistentValidityAdvertisement() {
    ClockEntity advertised = source(4, true);
    advertised.validityState = ClockValidityState::NotAdvertised;
    auto result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 4), advertised }) },
        3
    );
    assert(result.status == ClockGraphStatus::InvalidInput);
    assert(result.reason == "clock_validity_state_inconsistent");

    ClockEntity notAdvertised = source(4);
    notAdvertised.validityState = ClockValidityState::Unchecked;
    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 4), notAdvertised }) },
        3
    );
    assert(result.status == ClockGraphStatus::InvalidInput);
    assert(result.reason == "clock_validity_state_inconsistent");
}

} // namespace

int main() {
    resolvesDirectSourceAndPreservesValidityState();
    resolvesSelectedSelectorPinAndRejectsUnselectedMultiplePins();
    resolvesMultiplierRatioWithReduction();
    rejectsMissingCycleDuplicateAndInvalidMultiplier();
    rejectsCrossFunctionAmbiguityAndDepthLimit();
    preservesAdvertisedValidityObservation();
    rejectsInconsistentValidityAdvertisement();
    return 0;
}
