package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSearchFocusPolicyTest {
    @Test
    fun requestsFocusOnlyWhenSearchIsOpenOutsideSelectionMode() {
        assertTrue(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                autoShowKeyboard = true
            )
        )
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = false,
                selectionMode = false,
                autoShowKeyboard = true
            )
        )
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = true,
                autoShowKeyboard = true
            )
        )
    }

    @Test
    fun doesNotRequestFocusWhenAutomaticKeyboardIsDisabled() {
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                autoShowKeyboard = false
            )
        )
    }
}
