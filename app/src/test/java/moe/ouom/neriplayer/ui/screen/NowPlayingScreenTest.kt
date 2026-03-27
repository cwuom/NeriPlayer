package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingScreenTest {

    @Test
    fun `download action remains visible when completed task exists but local file is gone`() {
        assertFalse(shouldHideDownloadActionForSong(hasLocalDownload = false))
    }

    @Test
    fun `download action hides only when actual local download exists`() {
        assertTrue(shouldHideDownloadActionForSong(hasLocalDownload = true))
    }
}
