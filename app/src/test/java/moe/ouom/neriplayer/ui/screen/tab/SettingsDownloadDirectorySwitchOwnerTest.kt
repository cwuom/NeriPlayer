package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableStateOf
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectorySwitchOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDownloadDirectorySwitchOwnerTest {
    @Test
    fun `available request opens warning and confirmation launches picker once`() {
        val warning = mutableStateOf(false)
        var launches = 0
        val owner = DownloadDirectorySwitchOwner(warning, { false }, { launches++ })

        owner.requestPick()
        assertTrue(warning.value)
        owner.confirmWarning()

        assertFalse(warning.value)
        assertEquals(1, launches)
    }

    @Test
    fun `blocked request and stale confirmation never launch picker`() {
        val warning = mutableStateOf(false)
        var launches = 0
        val owner = DownloadDirectorySwitchOwner(warning, { true }, { launches++ })

        owner.requestPick()
        assertFalse(warning.value)
        warning.value = true
        owner.confirmWarning()

        assertFalse(warning.value)
        assertEquals(0, launches)
    }

    @Test
    fun `dismissal closes warning without launching picker`() {
        val warning = mutableStateOf(true)
        val owner = DownloadDirectorySwitchOwner(warning, { false }, { error("unexpected launch") })

        owner.dismissWarning()

        assertFalse(warning.value)
    }
}
