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
