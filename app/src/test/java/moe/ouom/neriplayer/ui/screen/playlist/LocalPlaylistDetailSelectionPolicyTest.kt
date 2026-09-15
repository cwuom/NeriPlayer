package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.runtime.mutableStateListOf
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistDetailSelectionPolicyTest {

    @Test
    fun `selecting filtered results only adds displayed songs`() {
        val result = toggleDisplayedSongSelection(
            selectedKeys = emptySet(),
            displayedKeys = setOf("album-a-first", "album-a-second")
        )

        assertEquals(setOf("album-a-first", "album-a-second"), result)
    }

    @Test
    fun `selecting filtered results keeps existing hidden selections`() {
        val result = toggleDisplayedSongSelection(
            selectedKeys = setOf("hidden-song"),
            displayedKeys = setOf("album-a-first", "album-a-second")
        )

        assertEquals(setOf("hidden-song", "album-a-first", "album-a-second"), result)
    }

    @Test
    fun `deselecting filtered results only removes displayed songs`() {
        val result = toggleDisplayedSongSelection(
            selectedKeys = setOf("hidden-song", "album-a-first", "album-a-second"),
            displayedKeys = setOf("album-a-first", "album-a-second")
        )

        assertEquals(setOf("hidden-song"), result)
    }

    @Test
    fun `empty filtered results keep selection unchanged`() {
        val selectedKeys = setOf("hidden-song")

        val result = toggleDisplayedSongSelection(
            selectedKeys = selectedKeys,
            displayedKeys = emptySet()
        )

        assertEquals(selectedKeys, result)
    }

    @Test
    fun `displayed selection state accepts hidden extra selections`() {
        assertTrue(
            areDisplayedSongKeysSelected(
                selectedKeys = setOf("hidden-song", "album-a-first"),
                displayedKeys = setOf("album-a-first")
            )
        )
        assertFalse(
            areDisplayedSongKeysSelected(
                selectedKeys = setOf("hidden-song"),
                displayedKeys = setOf("album-a-first")
            )
        )
    }

    @Test
    fun `batch action keeps every selected song including local sources`() {
        val local = song(id = 1, name = "local").copy(
            mediaUri = "content://media/external/audio/1"
        )
        val remote = song(id = 2, name = "remote")
        val songs = listOf(local, remote, song(id = 3, name = "unselected"))

        val selected = selectedSongsInSourceOrder(
            songs = songs,
            selectedKeys = setOf(local.stableKey(), remote.stableKey())
        )

        assertEquals(listOf(local, remote), selected)
    }

    @Test
    fun `filtering the visible list does not remove hidden selections`() {
        val visible = song(id = 1, name = "visible")
        val hiddenBySearch = song(id = 2, name = "hidden")
        val selectedKeys = setOf(visible.stableKey(), hiddenBySearch.stableKey())

        val retained = retainExistingSongSelectionKeys(
            songs = listOf(visible, hiddenBySearch),
            selectedKeys = selectedKeys
        )

        assertEquals(selectedKeys, retained)
    }

    @Test
    fun `selection cleanup only drops songs removed from the source`() {
        val existing = song(id = 1, name = "existing")
        val removed = song(id = 2, name = "removed")

        val retained = retainExistingSongSelectionKeys(
            songs = listOf(existing),
            selectedKeys = setOf(existing.stableKey(), removed.stableKey())
        )

        assertEquals(setOf(existing.stableKey()), retained)
    }

    @Test
    fun `snapshot display order list survives source mutations`() {
        val source = mutableStateListOf("first", "second", "third")

        val displayOrderSnapshot = snapshotDisplayOrderList(source)
        source.clear()
        source.addAll(listOf("fourth", "fifth"))

        assertEquals(listOf("first", "second", "third"), displayOrderSnapshot)
        assertEquals(listOf("fourth", "fifth"), snapshotDisplayOrderList(source))
    }

    @Test
    fun `exporting selected local songs keeps target display order`() {
        val storedSongs = listOf(
            song(id = 1, name = "newest"),
            song(id = 2, name = "middle"),
            song(id = 3, name = "oldest")
        )
        val selectedKeys = snapshotDisplayOrderList(storedSongs)
            .take(2)
            .mapTo(mutableSetOf()) { it.stableKey() }

        val exportedSongs = selectedStoredLocalSongsForExport(storedSongs, selectedKeys)

        assertEquals(listOf("newest", "middle"), exportedSongs.map { it.name })
        assertEquals(listOf("newest", "middle"), snapshotDisplayOrderList(exportedSongs).map { it.name })
    }

    @Test
    fun `local files tabs keep manually added and downloaded sources independent`() {
        val manuallyAddedOnly = song(id = 1, name = "manual")
        val manuallyAddedDownloaded = song(id = 2, name = "manual-downloaded")
        val downloadedOnly = song(id = 3, name = "downloaded")
        val manuallyAddedSongs = listOf(manuallyAddedOnly, manuallyAddedDownloaded)
        val downloadedSongs = listOf(manuallyAddedDownloaded, downloadedOnly)

        assertEquals(
            manuallyAddedSongs,
            localFilesSongsForTab(
                manuallyAddedSongs = manuallyAddedSongs,
                downloadedSongs = downloadedSongs,
                tab = LocalFilesSongTab.MANUALLY_ADDED
            )
        )
        assertEquals(
            downloadedSongs,
            localFilesSongsForTab(
                manuallyAddedSongs = manuallyAddedSongs,
                downloadedSongs = downloadedSongs,
                tab = LocalFilesSongTab.DOWNLOADED
            )
        )
    }

    private fun song(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 0L,
            coverUrl = null
        )
    }
}
