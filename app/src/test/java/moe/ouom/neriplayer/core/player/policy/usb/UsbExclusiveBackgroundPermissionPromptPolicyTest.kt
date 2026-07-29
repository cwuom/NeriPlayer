package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveBackgroundPermissionPromptPolicyTest {

    @Test
    fun `enabled existing USB session prompts when background behavior is not allowed`() {
        assertTrue(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = true,
                appResumed = true,
                promptSuppressed = false,
                backgroundBehaviorAllowed = false,
                promptHandledInCurrentSession = false
            )
        )
    }

    @Test
    fun `allowed background behavior does not prompt`() {
        assertFalse(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = true,
                appResumed = true,
                promptSuppressed = false,
                backgroundBehaviorAllowed = true,
                promptHandledInCurrentSession = false
            )
        )
    }

    @Test
    fun `inactive USB session does not prompt`() {
        assertFalse(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = false,
                appResumed = true,
                promptSuppressed = false,
                backgroundBehaviorAllowed = false,
                promptHandledInCurrentSession = false
            )
        )
        assertFalse(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = true,
                appResumed = false,
                promptSuppressed = false,
                backgroundBehaviorAllowed = false,
                promptHandledInCurrentSession = false
            )
        )
    }

    @Test
    fun `explicit dismissal and handled session prevent duplicate prompts`() {
        assertFalse(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = true,
                appResumed = true,
                promptSuppressed = true,
                backgroundBehaviorAllowed = false,
                promptHandledInCurrentSession = false
            )
        )
        assertFalse(
            shouldPromptForUsbExclusiveBackgroundPermission(
                usbExclusiveEnabled = true,
                appResumed = true,
                promptSuppressed = false,
                backgroundBehaviorAllowed = false,
                promptHandledInCurrentSession = true
            )
        )
    }
}
