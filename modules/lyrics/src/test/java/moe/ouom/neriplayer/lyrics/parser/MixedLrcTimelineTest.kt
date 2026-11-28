package moe.ouom.neriplayer.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `inline timestamps inside an enhanced line split text instead of leaking`() {
        val lines = parseNeteaseLrc("[00:01.00]<00:01.20>Hello<00:01.80>[00:02.00]World")

        assertEquals(listOf("HelloWorld"), lines.map { it.text })
        assertEquals(listOf(1_200L, 2_000L), lines.single().words!!.map { it.startTimeMs })
        assertTrue(lines.single().text.none { it == '[' || it == '<' })
    }

    @Test
    fun `markup-only enhanced segment before an inline timestamp stays hidden`() {
        val lines = parseNeteaseLrc("[00:01.00]<00:01.00>[00:02.00]World")

        assertEquals(listOf("World"), lines.map { it.text })
        assertEquals(listOf(2_000L), lines.single().words!!.map { it.startTimeMs })
    }

    @Test
    fun `markup-only trailing enhanced segment does not extend the line`() {
        val lines = parseNeteaseLrc("[00:01.00]<00:01.00>Hello[00:02.00]<00:02.00>")

        assertEquals(listOf("Hello"), lines.map { it.text })
        assertEquals(2_000L, lines.single().endTimeMs)
        assertEquals(listOf(2_000L), lines.single().words!!.map { it.endTimeMs })
    }

    @Test
    fun `interior enhanced segment keeps its whitespace prefix`() {
        val lines = parseNeteaseLrc("[00:01.00]<00:01.00>Hello[00:02.00] <00:02.00>world")

        assertEquals(listOf("Hello world"), lines.map { it.text })
        assertEquals(listOf("Hello"), parseNeteaseLrc("[00:01.00] <00:01.00>Hello").map { it.text })
    }

    @Test
    fun `yrc word text keeps parentheses`() {
        val lines = parseNeteaseYrc("[1000,3000](1000,1000,0)Hello (world)(2000,2000,0) again")

        assertEquals("Hello (world) again", lines.single().text)
        assertEquals(listOf(13, 6), lines.single().words!!.map { it.charCount })
    }
}
