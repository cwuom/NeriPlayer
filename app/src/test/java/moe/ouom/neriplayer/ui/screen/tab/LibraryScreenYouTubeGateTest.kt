package moe.ouom.neriplayer.ui.screen.tab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenYouTubeGateTest {

    @Test
    fun `library tabs exclude YouTube when disabled`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = true,
            youtubeEnabled = false
        )

        assertFalse(tabs.contains(LibraryTab.YTMUSIC))
        assertTrue(tabs.contains(LibraryTab.NETEASE))
        assertTrue(tabs.contains(LibraryTab.BILI))
    }
}
