package moe.ouom.neriplayer.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixedLrcTimelineTest {
    @Test
    fun `mixed enhanced and ordinary lines share one complete timeline`() {
        val content = "[00:01.00]<00:01.00>Hello\n[00:03.00]World"
        for (parse in listOf(::parseNeteaseLrc, ::parseNeteaseLyricsAuto)) {
            val lines = parse(content)
            assertEquals(listOf("Hello", "World"), lines.map { it.text })
            assertEquals(3_000L, lines.first().endTimeMs)
            assertEquals(3_000L, lines.first().words!!.single().endTimeMs)
            assertNull(lines.last().words)
        }
    }

    @Test
    fun `repeated enhanced line timestamps shift the entire word timeline`() {
        val lines = parseNeteaseLrc(
            "[00:01.00][00:03.00]<00:01.20>Hello<00:01.80> world<00:02.00>"
        )
        assertEquals(listOf("Hello world", "Hello world"), lines.map { it.text })
        assertEquals(listOf(1_000L, 3_000L), lines.map { it.startTimeMs })
        assertEquals(listOf(2_000L, 4_000L), lines.map { it.endTimeMs })
        assertEquals(listOf(1_200L, 1_800L), lines.first().words!!.map { it.startTimeMs })
        assertEquals(listOf(3_200L, 3_800L), lines.last().words!!.map { it.startTimeMs })
    }

    @Test
    fun `enhanced word fallback skips simultaneous ordinary translation`() {
        val lines = parseNeteaseLrc(
            "[00:01.00]<00:01.00>Hello\n[00:01.00]你好\n[00:03.00]World"
        )
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals("你好", lines.first().translation)
        assertEquals(3_000L, lines.first().words!!.single().endTimeMs)
    }

    @Test
    fun `blank terminal marker bounds enhanced word fallback`() {
        val lines = parseNeteaseLrc("[00:01.00]<00:01.00>Hello\n[00:02.50]")
        assertEquals(2_500L, lines.single().endTimeMs)
        assertEquals(2_500L, lines.single().words!!.single().endTimeMs)
    }
}
