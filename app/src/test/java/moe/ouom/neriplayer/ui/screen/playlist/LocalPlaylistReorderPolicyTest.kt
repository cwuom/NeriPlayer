package moe.ouom.neriplayer.ui.screen.playlist

import org.burnoutcrew.reorderable.ItemPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistReorderPolicyTest {

    @Test
    fun `handle inside the visible top band requests backward scrolling`() {
        assertEquals(
            -4f / 48f,
            localPlaylistReorderScrollFraction(44f, 0f, 600f, 48f),
            0.0001f
        )
    }

    @Test
    fun `downward scrolling starts beyond the bottom boundary`() {
        assertEquals(
            4f / 48f,
            localPlaylistReorderScrollFraction(508f, 0f, 504f, 48f),
            0.0001f
        )
    }

    @Test
    fun `handle in the middle does not start edge scrolling`() {
        assertEquals(0f, localPlaylistReorderScrollFraction(300f, 0f, 600f, 48f))
    }

    @Test
    fun `pointer outside either viewport edge keeps requesting scrolling`() {
        assertEquals(-1f, localPlaylistReorderScrollFraction(-10f, 0f, 600f, 48f))
        assertEquals(1f, localPlaylistReorderScrollFraction(648f, 0f, 600f, 48f))
    }

    @Test
    fun `a handle above or on the tab boundary does not scroll downward`() {
        assertEquals(0f, localPlaylistReorderScrollFraction(599f, 0f, 600f, 48f))
        assertEquals(0f, localPlaylistReorderScrollFraction(600f, 0f, 600f, 48f))
    }

    @Test
    fun `empty visible area does not request scrolling`() {
        assertEquals(0f, localPlaylistReorderScrollFraction(10f, 0f, 0f, 48f))
    }

    @Test
    fun `short visible areas keep top and bottom trigger bands separate`() {
        assertEquals(
            -2f / 3f,
            localPlaylistReorderScrollFraction(10f, 0f, 60f, 48f),
            0.0001f
        )
        assertEquals(0f, localPlaylistReorderScrollFraction(30f, 0f, 60f, 48f))
    }

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
