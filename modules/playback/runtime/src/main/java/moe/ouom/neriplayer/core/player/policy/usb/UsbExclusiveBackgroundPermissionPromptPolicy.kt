package moe.ouom.neriplayer.core.player.policy.usb

fun shouldPromptForUsbExclusiveBackgroundPermission(
    usbExclusiveEnabled: Boolean,
    appResumed: Boolean,
    promptSuppressed: Boolean,
    backgroundBehaviorAllowed: Boolean,
    promptHandledInCurrentSession: Boolean
): Boolean {
    return usbExclusiveEnabled &&
        appResumed &&
        !promptSuppressed &&
        !backgroundBehaviorAllowed &&
        !promptHandledInCurrentSession
}
