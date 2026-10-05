package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLayoutItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveCompactEditSongCoverSize
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongLayoutChrome
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongLayoutPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSongLayoutPresentationTest {
    @Test
    fun `normal compact window keeps header and actions outside the scrolling form`() {
        val layout = resolveEditSongLayoutPresentation(true, 360.dp)
        assertEquals(listOf(EditSongLayoutItem.HEADER), layout.fixedHeader)
        assertEquals(listOf(EditSongLayoutItem.COVER_URL, EditSongLayoutItem.FIELDS), layout.scrollingItems)
        assertEquals(listOf(EditSongLayoutItem.ACTIONS), layout.fixedActions)
        assertEquals(8.dp, layout.chrome.spacing)
        assertEquals(20.dp, layout.chrome.horizontalPadding)
        assertEquals(8.dp, layout.chrome.verticalPadding)
        assertEquals(1f, layout.chrome.heightFraction, 0f)
    }

    @Test
    fun `short window moves only the header into the form until the action height threshold`() {
        val atHeaderBoundary = resolveEditSongLayoutPresentation(true, 240.dp)
        assertEquals(listOf(EditSongLayoutItem.HEADER), atHeaderBoundary.fixedHeader)
        listOf(239.dp, 180.dp, 160.dp).forEach { height ->
            val layout = resolveEditSongLayoutPresentation(true, height)
            assertTrue(layout.fixedHeader.isEmpty())
            assertEquals(listOf(EditSongLayoutItem.HEADER, EditSongLayoutItem.COVER_URL,
                EditSongLayoutItem.FIELDS), layout.scrollingItems)
            assertEquals(listOf(EditSongLayoutItem.ACTIONS), layout.fixedActions)
        }
    }

    @Test
    fun `keyboard budget keeps header fields and every action in one scrolling region`() {
        listOf(159.dp, 144.dp, 0.dp).forEach { height ->
            val layout = resolveEditSongLayoutPresentation(true, height)
            assertTrue(layout.fixedHeader.isEmpty())
            assertEquals(listOf(EditSongLayoutItem.HEADER, EditSongLayoutItem.COVER_URL,
                EditSongLayoutItem.FIELDS, EditSongLayoutItem.ACTIONS), layout.scrollingItems)
            assertTrue(layout.fixedActions.isEmpty())
        }
    }

    @Test
    fun `regular phone and tablet keep the full cover in the form and chrome fixed`() {
        listOf(144.dp, 360.dp, 800.dp, 1280.dp).forEach { height ->
            val layout = resolveEditSongLayoutPresentation(false, height)
            assertEquals(listOf(EditSongLayoutItem.HEADER), layout.fixedHeader)
            assertEquals(listOf(EditSongLayoutItem.COVER_URL, EditSongLayoutItem.COVER,
                EditSongLayoutItem.FIELDS), layout.scrollingItems)
            assertEquals(listOf(EditSongLayoutItem.ACTIONS), layout.fixedActions)
            assertEquals(12.dp, layout.chrome.spacing)
            assertEquals(24.dp, layout.chrome.horizontalPadding)
            assertEquals(16.dp, layout.chrome.verticalPadding)
            assertEquals(0.9f, layout.chrome.heightFraction, 0f)
        }
        assertEquals(resolveEditSongLayoutChrome(false), resolveEditSongLayoutPresentation(false, 800.dp).chrome)
        assertEquals(resolveEditSongLayoutChrome(true), resolveEditSongLayoutPresentation(true, 300.dp).chrome)
    }

    @Test
    fun `compact cover clamps the body budget independently of header and action placement`() {
        assertEquals(0.dp, resolveCompactEditSongCoverSize((-20).dp))
        assertEquals(0.dp, resolveCompactEditSongCoverSize(0.dp))
        assertEquals(48.dp, resolveCompactEditSongCoverSize(48.dp))
        assertEquals(96.dp, resolveCompactEditSongCoverSize(96.dp))
        assertEquals(96.dp, resolveCompactEditSongCoverSize(500.dp))
    }

    @Test
    fun `only regular form at its top can hand a downward drag to the parent sheet`() {
        val regular = resolveEditSongLayoutPresentation(false, 800.dp)
        val compact = resolveEditSongLayoutPresentation(true, 144.dp)
        assertTrue(regular.allowsParentDrag(0))
        assertFalse(regular.allowsParentDrag(1))
        assertFalse(regular.allowsParentDrag(-1))
        assertFalse(compact.allowsParentDrag(0))
        assertFalse(compact.allowsParentDrag(1))
    }
}
