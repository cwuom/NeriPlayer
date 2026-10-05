package moe.ouom.neriplayer.ui.screen.playlist.insert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistInsertPolicyTest {
    private val source = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `move up uses final one based position`() {
        val preview = requireNotNull(createPlaylistInsertPreview(source, setOf("d", "e"), 2))

        assertEquals(listOf("a", "d", "e", "b", "c", "f"), preview.orderedKeys)
        assertEquals(listOf("d", "e"), preview.movedKeys)
        assertEquals(2, preview.startPosition)
    }

    @Test
    fun `move down uses final position after removing selection`() {
        val preview = requireNotNull(createPlaylistInsertPreview(source, setOf("b", "c"), 4))

        assertEquals(listOf("a", "d", "e", "b", "c", "f"), preview.orderedKeys)
    }

    @Test
    fun `non consecutive selection keeps source order regardless of selection order`() {
        val preview = requireNotNull(
            createPlaylistInsertPreview(source, linkedSetOf("e", "b", "d"), 2)
        )

        assertEquals(listOf("b", "d", "e"), preview.movedKeys)
        assertEquals(listOf("a", "b", "d", "e", "c", "f"), preview.orderedKeys)
    }

    @Test
    fun `first and last insertion positions are inclusive`() {
        assertEquals(
            listOf("c", "e", "a", "b", "d", "f"),
            createPlaylistInsertPreview(source, setOf("c", "e"), 1)?.orderedKeys
        )
        assertEquals(
            listOf("a", "b", "d", "f", "c", "e"),
            createPlaylistInsertPreview(source, setOf("c", "e"), 5)?.orderedKeys
        )
    }

    @Test
    fun `moving all songs only accepts position one and keeps their order`() {
        assertEquals(source, createPlaylistInsertPreview(source, source.toSet(), 1)?.orderedKeys)
        assertNull(createPlaylistInsertPreview(source, source.toSet(), 2))
    }

    @Test
    fun `every legal position preserves membership and unselected order`() {
        val selectedKeys = setOf("b", "d")
        for (position in 1..5) {
            val preview = requireNotNull(createPlaylistInsertPreview(source, selectedKeys, position))
            assertEquals(source.size, preview.orderedKeys.size)
            assertEquals(source.toSet(), preview.orderedKeys.toSet())
            assertEquals(listOf("a", "c", "e", "f"), preview.orderedKeys.filterNot(selectedKeys::contains))
            assertEquals(listOf("b", "d"), preview.orderedKeys.subList(position - 1, position + 1))
        }
    }

    @Test
    fun `preview takes a snapshot and never mutates its source`() {
        val mutableSource = source.toMutableList()
        val mutableSelection = mutableSetOf("c", "e")
        val preview = requireNotNull(createPlaylistInsertPreview(mutableSource, mutableSelection, 1))

        assertEquals(source, mutableSource)
        mutableSource.clear()
        mutableSelection.clear()

        assertEquals(source, preview.sourceKeys)
        assertEquals(setOf("c", "e"), preview.selectedKeys)
        assertEquals(listOf("c", "e", "a", "b", "d", "f"), preview.orderedKeys)
    }

    @Test
    fun `invalid selections ranges and ambiguous identities reject preview`() {
        assertNull(createPlaylistInsertPreview(source, emptySet(), 1))
        assertNull(createPlaylistInsertPreview(source, setOf("missing"), 1))
        assertNull(createPlaylistInsertPreview(source, setOf("b", "c"), 0))
        assertNull(createPlaylistInsertPreview(source, setOf("b", "c"), 6))
        assertNull(createPlaylistInsertPreview(listOf("a", "a"), setOf("a"), 1))
        assertNull(createPlaylistInsertPreview(listOf("a", ""), setOf("a"), 1))
        assertNull(createPlaylistInsertPreview(emptyList(), emptySet(), 1))
    }

    @Test
    fun `input requires an integer in the final start range`() {
        listOf("", " ", "0", "-1", "6", "1.5", "abc", "2147483648").forEach { input ->
            assertNull(resolvePlaylistInsertPosition(input, 6, 2))
        }
        assertEquals(1, resolvePlaylistInsertPosition("1", 6, 2))
        assertEquals(5, resolvePlaylistInsertPosition(" 5 ", 6, 2))
        assertNull(resolvePlaylistInsertPosition("1", 6, 0))
        assertNull(resolvePlaylistInsertPosition("1", 6, 7))
    }

    @Test
    fun `changed order membership or selection invalidates preview`() {
        val preview = requireNotNull(createPlaylistInsertPreview(source, setOf("c", "e"), 2))

        assertTrue(isPlaylistInsertPreviewCurrent(preview, source, setOf("e", "c")))
        assertFalse(isPlaylistInsertPreviewCurrent(preview, source.reversed()))
        assertFalse(isPlaylistInsertPreviewCurrent(preview, source.dropLast(1)))
        assertFalse(isPlaylistInsertPreviewCurrent(preview, source + "g"))
        assertFalse(isPlaylistInsertPreviewCurrent(preview, source, setOf("c")))
    }

    @Test
    fun `tampered result or position cannot be confirmed`() {
        val preview = requireNotNull(createPlaylistInsertPreview(source, setOf("c", "e"), 2))

        assertFalse(isPlaylistInsertPreviewCurrent(preview.copy(orderedKeys = source), source))
        assertFalse(isPlaylistInsertPreviewCurrent(preview.copy(movedKeys = listOf("e", "c")), source))
        assertFalse(isPlaylistInsertPreviewCurrent(preview.copy(startPosition = 0), source))
    }
}
