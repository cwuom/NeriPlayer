package moe.ouom.neriplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableSettingKeysTest {

    @Test
    fun `status bar lyrics stays available on devices that support it`() {
        assertTrue(
            resolveUnavailableSettingKeys(statusBarLyricsSupported = true).isEmpty()
        )
    }

    @Test
    fun `status bar lyrics is hidden on devices that do not support it`() {
        assertEquals(
            setOf(STATUS_BAR_LYRICS_SETTING_KEY),
            resolveUnavailableSettingKeys(statusBarLyricsSupported = false)
        )
    }
}
