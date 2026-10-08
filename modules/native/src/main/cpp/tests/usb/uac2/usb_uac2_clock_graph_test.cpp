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

    multiplier.multiplierDenominator = 1;
    multiplier.multiplierRatioKnown = false;
    result = neri::usb::uac2::resolveClockGraph(
        { functionWith({ terminal(3, 10), multiplier, source(4) }) },
        3
    );
    assert(result.status == ClockGraphStatus::InvalidMultiplier);
    assert(result.reason == "clock_multiplier_ratio_unknown");
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

void resolvesXmosStyleSelectorBetweenTerminalAndSources() {
    // XMOS 参考固件：USB 输入终端 2 -> 时钟选择器 40 -> 内部时钟 41 / S/PDIF 42
    constexpr uint8_t selectorDescriptor[] = { 9, 0x24, 0x0B, 40, 2, 41, 42, 0x03, 0 };
    constexpr uint8_t multiplierDescriptor[] = { 7, 0x24, 0x0C, 50, 41, 0x00, 0 };
    neri::usb::uac2::ClockRouting selector;
    neri::usb::uac2::ClockRouting multiplier;
    std::string error;
    assert(neri::usb::uac2::parseClockRoutingDescriptor(selectorDescriptor, 9, &selector, &error));
    assert(selector.selector && selector.id == 40);
    assert((selector.sourceIds == std::vector<int> { 41, 42 }));
    assert(neri::usb::uac2::parseClockRoutingDescriptor(multiplierDescriptor, 7, &multiplier, &error));
    assert(!multiplier.selector && (multiplier.sourceIds == std::vector<int> { 41 }));

    constexpr uint8_t truncatedSelector[] = { 6, 0x24, 0x0B, 40, 2, 41 };
    constexpr uint8_t clockSource[] = { 8, 0x24, 0x0A, 41, 0x01, 0x07, 0, 0 };
    neri::usb::uac2::ClockRouting rejected;
    assert(!neri::usb::uac2::parseClockRoutingDescriptor(truncatedSelector, 6, &rejected, &error));
    assert(!neri::usb::uac2::parseClockRoutingDescriptor(clockSource, 8, &rejected, &error));

    int pinReads = 0;
    const auto unreadable = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 40, { 41, 42 }, { selector },
        [&pinReads](int selectorId, int pinCount) {
            ++pinReads;
            assert(selectorId == 40 && pinCount == 2);
            return -1;
        }
    );
    assert(unreadable.status == ClockGraphStatus::Valid);
    assert(unreadable.finalClockSourceId == 41);
    assert(pinReads == 1);

    const auto external = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 40, { 41, 42 }, { selector },
        [](int, int) { return 1; }
    );
    assert(external.finalClockSourceId == 42);

    // 描述符里没有倍频比，不能把终端采样率当成时钟源 41 的频率写下去
    const auto viaMultiplier = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 50, { 41 }, { multiplier }, nullptr
    );
    assert(viaMultiplier.status == ClockGraphStatus::InvalidMultiplier);
    assert(viaMultiplier.reason == "clock_multiplier_ratio_unknown");
    assert(viaMultiplier.finalClockSourceId == 0);

    neri::usb::uac2::ClockRouting selectorBeforeMultiplier;
    selectorBeforeMultiplier.id = 40;
    selectorBeforeMultiplier.selector = true;
    selectorBeforeMultiplier.sourceIds = { 41, 50 };
    const auto directPin = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 40, { 41 }, { selectorBeforeMultiplier, multiplier },
        [](int, int) { return 0; }
    );
    assert(directPin.status == ClockGraphStatus::Valid);
    assert(directPin.finalClockSourceId == 41);
    const auto multipliedPin = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 40, { 41 }, { selectorBeforeMultiplier, multiplier },
        [](int, int) { return 1; }
    );
    assert(multipliedPin.status == ClockGraphStatus::InvalidMultiplier);

    neri::usb::uac2::ClockRouting dangling;
    dangling.id = 40;
    dangling.selector = true;
    dangling.sourceIds = { 99 };
    const auto missing = neri::usb::uac2::resolveTerminalClockSource(
        0, 2, 40, { 41 }, { dangling }, nullptr
    );
    assert(missing.status == ClockGraphStatus::MissingEntity);
}

} // namespace

int main() {
    resolvesXmosStyleSelectorBetweenTerminalAndSources();
    resolvesDirectSourceAndPreservesValidityState();
    resolvesSelectedSelectorPinAndRejectsUnselectedMultiplePins();
    resolvesMultiplierRatioWithReduction();
    rejectsMissingCycleDuplicateAndInvalidMultiplier();
    rejectsCrossFunctionAmbiguityAndDepthLimit();
    preservesAdvertisedValidityObservation();
    rejectsInconsistentValidityAdvertisement();
    return 0;
}
