package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.*
import org.junit.Test

class ColorOsLyricTimelineTest {
    private val lines = listOf(LyricEntry("line", 1000L, 2000L))

    @Test
    fun `A B A rejects the first A result and advances only real track generations`() {
        val owner = ColorOsLyricTimelineOwner()
        val firstA = owner.begin("a")
        owner.begin("b")
        val secondA = owner.begin("a")
        assertFalse(owner.publish(firstA, lines, emptyList(), 0L))
        assertTrue(owner.publish(secondA, lines, emptyList(), 0L))
        assertEquals(3L, owner.timeline.value!!.request.generation)
        val reload = owner.begin("a")
        assertEquals(3L, reload.generation)
        assertFalse(owner.publish(secondA, lines, emptyList(), 0L))
        assertNull(owner.timeline.value)
        assertTrue(owner.publish(reload, lines, emptyList(), 0L))
    }

    @Test
    fun `reload release and authoritative translations replace complete snapshots`() {
        val owner = ColorOsLyricTimelineOwner()
        val request = owner.begin("a")
        val mutableLines = lines.toMutableList()
        assertTrue(owner.publish(request, mutableLines, emptyList(), 0L))
        mutableLines.clear()
        assertEquals("line", owner.timeline.value!!.lyrics.single().text)
        owner.updateOffset(500L)
        assertEquals(500L, owner.timeline.value!!.offsetMs)
        assertTrue(owner.publish(request, lines, listOf(LyricEntry("translation", 1000L, 2000L)), 500L))
        assertEquals("translation", owner.timeline.value!!.translations.single().text)
        owner.begin(null)
        assertNull(owner.timeline.value)
        assertFalse(owner.publish(request, lines, emptyList(), 0L))
    }
}
