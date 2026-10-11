package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBackTargetSceneTest {

    private val artist = NeteaseArtistSummary(id = 7L, name = "Artist")
    private val album = AlbumSummary(id = 9L, name = "Album", picUrl = "", size = 10)

    @Test
    fun `library artist album goes back to its artist scene`() {
        val selected = LibrarySelectedItem.NeteaseArtistAlbum(artist, album)

        assertEquals(LibraryNavigationScene(selected, 2), libraryNavigationScene(selected, emptyList()))
        assertEquals(
            LibraryNavigationScene(LibrarySelectedItem.NeteaseArtist(artist), 1),
            resolveLibraryBackScene(selected, emptyList())
        )
    }

    @Test
    fun `library creator stack goes back to the previous creator with its depth`() {
        val first = LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 1L, name = "First"))
        val second = LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 2L, name = "Second"))
        val current = LibrarySelectedItem.BiliUploader(BiliUploaderSummary(mid = 3L, name = "Current"))
        val parents = listOf(first, second)

        assertEquals(LibraryNavigationScene(current, 3), libraryNavigationScene(current, parents))
        assertEquals(LibraryNavigationScene(second, 2), resolveLibraryBackScene(current, parents))
    }

    @Test
    fun `library top level detail goes back to the library root`() {
        val selected = LibrarySelectedItem.Local(playlistId = 4L)

        assertEquals(LibraryNavigationScene(null, 0), resolveLibraryBackScene(selected, emptyList()))
        assertEquals(LibraryNavigationScene(null, 0), libraryNavigationScene(null, emptyList()))
    }

    @Test
    fun `settings back gesture only seeks once the requested page has settled`() {
        assertTrue(
            isSettingsScreenSettled(
                screenState = SettingsScreenState.DownloadManager,
                requestedState = SettingsScreenState.DownloadManager,
                renderedScreenStates = setOf(SettingsScreenState.DownloadManager)
            )
        )
        assertFalse(
            isSettingsScreenSettled(
                screenState = SettingsScreenState.DownloadManager,
                requestedState = SettingsScreenState.DownloadManager,
                renderedScreenStates = setOf(
                    SettingsScreenState.Settings,
                    SettingsScreenState.DownloadManager
                )
            )
        )
        assertFalse(
            isSettingsScreenSettled(
                screenState = SettingsScreenState.DownloadManager,
                requestedState = SettingsScreenState.DownloadProgress,
                renderedScreenStates = setOf(SettingsScreenState.DownloadManager)
            )
        )
    }
}
