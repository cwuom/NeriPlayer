package moe.ouom.neriplayer.ui.component.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSongDialogsTest {
    @Test
    fun `null local media details resolve to unavailable error`() {
        val state = resolveLocalSongDetailsLoadState(
            details = null,
            unavailableMessage = "unavailable"
        )

        assertNull(state.details)
        assertEquals("unavailable", state.error)
    }
}
