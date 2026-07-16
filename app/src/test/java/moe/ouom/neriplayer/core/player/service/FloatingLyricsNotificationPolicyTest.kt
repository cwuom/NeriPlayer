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
}
