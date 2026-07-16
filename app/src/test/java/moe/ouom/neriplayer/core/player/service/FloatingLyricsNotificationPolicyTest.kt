package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricsNotificationPolicyTest {

    @Test
    fun `effective state follows the persisted setting`() {
        assertTrue(
            isFloatingLyricsEffectivelyEnabled(
                enabled = true,
            )
        )
        assertFalse(
            isFloatingLyricsEffectivelyEnabled(
                enabled = false,
            )
        )
    }

    @Test
    fun `toggle target persists the opposite state`() {
        assertFalse(nextFloatingLyricsEnabled(currentEnabled = true))
        assertTrue(nextFloatingLyricsEnabled(currentEnabled = false))
    }

    @Test
    fun `legacy hide action always targets disabled state`() {
        assertFalse(
            resolveFloatingLyricsExternalTargetEnabled(
                currentEnabled = true,
                legacyHideAction = true,
            )
        )
        assertFalse(
            resolveFloatingLyricsExternalTargetEnabled(
                currentEnabled = false,
                legacyHideAction = true,
            )
        )
    }
}
