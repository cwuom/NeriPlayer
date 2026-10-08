#include "usb/control/usb_feature_unit.h"

#include <cassert>
#include <cstdint>

namespace {

using neri::usb::control::muteRestoreValue;

void restoresUnmutedOriginalAfterVolumeMute() {
    // 原本未静音：音量键拉到 0 补上的静音必须在关闭时撤销
    assert(muteRestoreValue(true, 0) == 0);
}

void restoresMutedOriginal() {
    assert(muteRestoreValue(true, 1) == 1);
    assert(muteRestoreValue(true, 0xFF) == 1);
}

void restoresUnknownOriginalAsUnmuted() {
    assert(muteRestoreValue(false, 1) == 0);
    assert(muteRestoreValue(false, 0) == 0);
}

} // namespace

int main() {
    restoresUnmutedOriginalAfterVolumeMute();
    restoresMutedOriginal();
    restoresUnknownOriginalAsUnmuted();
    return 0;
}
