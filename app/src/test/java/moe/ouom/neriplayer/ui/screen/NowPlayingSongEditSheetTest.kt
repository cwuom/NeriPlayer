package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongActionAvailability
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongCoverPreviewState
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongActionAvailability
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongActionFontSize
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongCoverRenderer
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldUseCompactEditSongLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSongEditSheetTest {
    @Test
    fun `compact editing is limited to short landscape phone windows`() {
        assertTrue(shouldUseCompactEditSongLayout(360, true, 360.dp))
        assertTrue(shouldUseCompactEditSongLayout(599, true, 300.dp))
        assertTrue(shouldUseCompactEditSongLayout(360, true, 479.dp))
        assertFalse(shouldUseCompactEditSongLayout(360, true, 480.dp))
        assertFalse(shouldUseCompactEditSongLayout(360, false, 300.dp))
        assertFalse(shouldUseCompactEditSongLayout(600, true, 360.dp))
        assertFalse(shouldUseCompactEditSongLayout(800, true, 800.dp))
    }

    @Test
    fun `edit actions retain compact labels below 420 dp`() {
        assertEquals(11.sp, editSongActionFontSize(419.dp))
        assertEquals(13.sp, editSongActionFontSize(420.dp))
    }

    @Test
    fun `restore save and cover import disable only the affected edit actions`() {
        assertEquals(
            EditSongActionAvailability(true, true),
            editSongActionAvailability(false, false, false)
        )
        assertEquals(
            EditSongActionAvailability(false, false),
            editSongActionAvailability(true, false, false)
        )
        assertEquals(
            EditSongActionAvailability(false, false),
            editSongActionAvailability(false, true, false)
        )
        assertEquals(
            EditSongActionAvailability(true, false),
            editSongActionAvailability(false, false, true)
        )
    }

    @Test
    fun `cover preview remains described while saving but never opens a disabled picker`() {
        val replaceable = EditSongCoverPreviewState("content://cover/8", false, true, true)
        assertEquals(true, replaceable.clickable)
        assertEquals(false, replaceable.copy(enabled = false).clickable)
        assertEquals(false, replaceable.copy(canReplaceFromFile = false).clickable)
    }

    @Test
    fun `cover preview selects stable image or placeholder rendering by reference presence`() {
        val base = EditSongCoverPreviewState("", false, true, true)
        val placeholder = editSongCoverRenderer(base)
        val image = editSongCoverRenderer(base.copy(coverUrl = "content://cover/8"))
        assertNotSame(placeholder, image)
        assertSame(placeholder, editSongCoverRenderer(base.copy(offlineMode = true)))
        assertSame(image, editSongCoverRenderer(base.copy(coverUrl = "https://cover/remote")))
    }
}
