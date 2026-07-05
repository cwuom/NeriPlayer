package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.runtime.mutableStateListOf
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
    fun `snapshot reversed list survives source mutations`() {
        val source = mutableStateListOf("first", "second", "third")

        val reversedSnapshot = snapshotReversedList(source)
        source.clear()
        source.addAll(listOf("fourth", "fifth"))

        assertEquals(listOf("third", "second", "first"), reversedSnapshot)
        assertEquals(listOf("fifth", "fourth"), snapshotReversedList(source))
    }
}
