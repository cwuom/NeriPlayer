package moe.ouom.neriplayer.ui.screen.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPersonalizationContentTest {
    @Test
    fun bottomPlaybackControlsHideAndDisableToolbarDock() {
        assertEquals(
            ToolbarDockSwitchState(checked = false, enabled = false, showExplanation = true),
            resolveToolbarDockSwitchState(toolbarDockEnabled = true, controlsAtBottom = true)
        )
        assertEquals(
            ToolbarDockSwitchState(checked = false, enabled = false, showExplanation = true),
            resolveToolbarDockSwitchState(toolbarDockEnabled = false, controlsAtBottom = true)
        )
        assertEquals(
            ToolbarDockSwitchState(checked = true, enabled = true, showExplanation = false),
            resolveToolbarDockSwitchState(toolbarDockEnabled = true, controlsAtBottom = false)
        )
        assertEquals(
            ToolbarDockSwitchState(checked = false, enabled = true, showExplanation = false),
            resolveToolbarDockSwitchState(toolbarDockEnabled = false, controlsAtBottom = false)
        )
    }

    @Test
    fun disabledSwitchDoesNotExposeRowClick() {
        assertNull(switchRowClick(enabled = false, checked = true) { })
    }

    @Test
    fun enabledSwitchTogglesFromItsCurrentValue() {
        val changes = mutableListOf<Boolean>()

        switchRowClick(enabled = true, checked = false, onCheckedChange = changes::add)?.invoke()
        switchRowClick(enabled = true, checked = true, onCheckedChange = changes::add)?.invoke()

        assertEquals(listOf(true, false), changes)
    }
}
