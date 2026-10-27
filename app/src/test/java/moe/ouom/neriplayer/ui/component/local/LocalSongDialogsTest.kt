package moe.ouom.neriplayer.ui.component.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.common.R as CoreCommonR

class LocalSongDialogsTest {
    @Test
    fun `null local media details resolve to unavailable error`() {
        val state = resolveLocalSongDetailsLoadState(
            details = null,
            unavailableMessage = UiText.Resource(CoreCommonR.string.local_song_details_unavailable)
        )

        assertNull(state.details)
        assertEquals(UiText.Resource(CoreCommonR.string.local_song_details_unavailable), state.error)
    }
}
