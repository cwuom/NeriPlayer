package moe.ouom.neriplayer.platform.lyrics.matching

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableLyricSanitizerEdgeLineTest {

    @Test
    fun `credit prefixes only count near the edges of the lyric`() {
        val lyrics = """
            [ti:Song Title]
            [00:00.00]【作词】
            [00:00.50](Lyrics by) Someone
            [00:01.00]
            [00:02.00]Line 4
            [00:03.00]Line 5
            [00:04.00]Line 6
            [00:05.00]Line 7
            [00:06.00]Composer: Middle Credit
            [00:07.00](Lyrics by) Not Edge
            [00:08.00]Line 10
            [00:09.00]Line 11
            [00:10.00]Line 12
            [00:11.00]Line 13
        """.trimIndent()

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = "   ",
            title = "Song Title",
            artist = "Artist Name"
        )

        assertEquals(
            """
                [00:01.00]
                [00:02.00]Line 4
                [00:03.00]Line 5
                [00:04.00]Line 6
                [00:05.00]Line 7
                [00:07.00](Lyrics by) Not Edge
                [00:08.00]Line 10
                [00:09.00]Line 11
                [00:10.00]Line 12
                [00:11.00]Line 13
            """.trimIndent(),
            sanitized.lyrics
        )
        assertEquals("   ", sanitized.translatedLyrics)
    }

    @Test
    fun `translation lines follow removed main lines only when timestamps stay aligned`() {
        val lyrics = """
            [0,2000](0,2000,0)Song Title - Artist Name
            [2000,1000](2000,1000,0)作词：Writer
            Lyrics by: Plain Writer
            [3000,1000](3000,1000,0)作曲：Composer
            [4000,1000](4000,1000,0)Real line one
            [5000,1000](5000,1000,0)Real line two
        """.trimIndent()
        val translatedLyrics = """
            [00:00.10]歌名翻译
            [00:02.50]作词者
            [00:03.00]词作者翻译
            作曲者翻译
            [00:04.00]第一行
            [00:05.00]第二行
        """.trimIndent()

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = lyrics,
            translatedLyrics = translatedLyrics,
            title = "Song Title",
            artist = "Artist Name"
        )

        assertEquals(
            "[4000,1000](4000,1000,0)Real line one\n[5000,1000](5000,1000,0)Real line two",
            sanitized.lyrics
        )
        assertEquals(
            "[00:02.50]作词者\n[00:03.00]词作者翻译\n作曲者翻译\n[00:04.00]第一行\n[00:05.00]第二行",
            sanitized.translatedLyrics
        )
    }

    @Test
    fun `translation with a different line count is never paired with main removals`() {
        val translatedLyrics = "[00:00.00]歌名翻译\r\n[00:04.00]第一行"

        val sanitized = sanitizeMatchedEditableLyrics(
            lyrics = "[0,2000](0,2000,0)Song Title - Artist Name\n" +
                "[2000,1000](2000,1000,0)作词：Writer\n" +
                "[4000,1000](4000,1000,0)Real line one",
            translatedLyrics = translatedLyrics,
            title = "Song Title",
            artist = "Artist Name"
        )

        assertEquals("[4000,1000](4000,1000,0)Real line one", sanitized.lyrics)
        assertEquals(translatedLyrics, sanitized.translatedLyrics)
    }
}
