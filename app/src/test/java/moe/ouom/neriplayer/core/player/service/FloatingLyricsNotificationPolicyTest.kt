package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricsNotificationPolicyTest {

    @Test
    fun `effective state is disabled when setting is off or temporarily hidden`() {
        assertTrue(
            isFloatingLyricsEffectivelyEnabled(
                enabled = true,
                temporarilyHidden = false,
            )
        )
        assertFalse(
            isFloatingLyricsEffectivelyEnabled(
                enabled = false,
                temporarilyHidden = false,
            )
        )
        assertFalse(
            isFloatingLyricsEffectivelyEnabled(
                enabled = true,
                temporarilyHidden = true,
            )
        )

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
