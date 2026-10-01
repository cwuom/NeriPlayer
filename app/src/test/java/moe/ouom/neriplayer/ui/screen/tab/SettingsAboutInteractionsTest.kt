package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableIntStateOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.about.SettingsAboutVersionTapOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsAboutInteractionsTest {
    @Test
    fun `seventh version tap enables developer mode and resets the sequence`() {
        val count = mutableIntStateOf(0)
        var enableCount = 0
        val messages = mutableListOf<Int>()
        val owner = SettingsAboutVersionTapOwner(
            tapCount = count,
            devModeEnabled = false,
            onEnableDevMode = { enableCount++ },
            onMessage = messages::add
        )

        repeat(6) { owner.onVersionClick() }
        assertEquals(6, count.intValue)
        assertEquals(0, enableCount)
        assertEquals(emptyList<Int>(), messages)

        owner.onVersionClick()
        assertEquals(0, count.intValue)
        assertEquals(1, enableCount)
        assertEquals(listOf(CoreCommonR.string.debug_mode_opened), messages)
    }

    @Test
    fun `enabled developer mode reports status without counting taps`() {
        val count = mutableIntStateOf(3)
        val messages = mutableListOf<Int>()
        val owner = SettingsAboutVersionTapOwner(
            tapCount = count,
            devModeEnabled = true,
            onEnableDevMode = { error("already enabled") },
            onMessage = messages::add
        )

        owner.onVersionClick()
        assertEquals(3, count.intValue)
        assertEquals(listOf(CoreCommonR.string.debug_mode_enabled), messages)
    }
}
