package moe.ouom.neriplayer.ui.screen.playlist

import org.burnoutcrew.reorderable.ItemPosition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistReorderPolicyTest {

    @Test
    fun `fixed playlist items cannot become drag targets`() {
        val canDragOver = localPlaylistCanDragOver { true }
        val draggingSong = ItemPosition(4, "song-key")

        LOCAL_PLAYLIST_FIXED_ITEM_KEYS.forEachIndexed { index, key ->
            assertFalse(key, canDragOver(ItemPosition(index, key), draggingSong))
        }
    }

    @Test
    fun `another song remains an allowed drag target`() {
        val canDragOver = localPlaylistCanDragOver { true }

        assertTrue(canDragOver(ItemPosition(3, "other-song"), ItemPosition(4, "song-key")))
    }

    @Test
    fun `disabled reordering rejects song targets`() {
        val canDragOver = localPlaylistCanDragOver { false }

        assertFalse(canDragOver(ItemPosition(3, "other-song"), ItemPosition(4, "song-key")))
    }

    @Test
    fun `target permission reads the current reordering mode`() {
        var canReorder = true
        val canDragOver = localPlaylistCanDragOver { canReorder }
        val target = ItemPosition(3, "other-song")
        val dragging = ItemPosition(4, "song-key")

        assertTrue(canDragOver(target, dragging))
        canReorder = false
        assertFalse(canDragOver(target, dragging))
    }

    @Test
    fun `library callback checks its first argument as the target`() {
        val canDragOver = localPlaylistCanDragOver { true }

        assertTrue(
            canDragOver(
                ItemPosition(3, "other-song"),
                ItemPosition(0, LOCAL_PLAYLIST_HEADER_KEY)
            )
        )
    }
}
