package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.BILI
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.FAVORITE
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.LOCAL
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.NETEASE
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.NETEASEALBUM
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.QQMUSIC
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab.YTMUSIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTabOrderTest {

    @Test
    fun `international users see YouTube Music before Netease`() {
        assertEquals(
            listOf(LOCAL, FAVORITE, YTMUSIC, NETEASE, BILI, QQMUSIC),
            libraryTabDisplayOrder(isInternational = true, youtubeEnabled = true)
        )
    }

    @Test
    fun `domestic users see Netease before YouTube Music`() {
        assertEquals(
            listOf(LOCAL, FAVORITE, NETEASE, YTMUSIC, BILI, QQMUSIC),
            libraryTabDisplayOrder(isInternational = false, youtubeEnabled = true)
        )
    }

    @Test
    fun `disabled YouTube removes its tab for every region`() {
        val expected = listOf(LOCAL, FAVORITE, NETEASE, BILI, QQMUSIC)

        assertEquals(expected, libraryTabDisplayOrder(isInternational = true, youtubeEnabled = false))
        assertEquals(expected, libraryTabDisplayOrder(isInternational = false, youtubeEnabled = false))
    }

    @Test
    fun `remote catalogue tabs are refreshable including the Netease album sub tab`() {
        listOf(BILI, YTMUSIC, NETEASE, NETEASEALBUM).forEach { tab ->
            assertTrue("$tab should be refreshable", tab.isRefreshable())
        }
    }

    @Test
    fun `local tabs and a missing tab are not refreshable`() {
        listOf(LOCAL, FAVORITE, QQMUSIC, null).forEach { tab ->
            assertFalse("$tab should not be refreshable", tab.isRefreshable())
        }
    }
}
