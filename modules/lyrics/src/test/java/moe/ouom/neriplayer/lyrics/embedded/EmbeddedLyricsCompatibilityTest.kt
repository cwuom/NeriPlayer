package moe.ouom.neriplayer.lyrics.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedLyricsCompatibilityTest {

    @Test
    fun `translation lines use their source timestamps in external LRC`() {
        val merged = mergeLyricsForExternalPlayers(
            lyrics = "[00:01.00]first\n[00:04.00]second",
            translatedLyrics = "[00:02.20]一\n[00:05.20]二"
        )

        assertEquals(
            "[00:01.00]first\n[00:01.00]一\n" +
                "[00:04.00]second\n[00:04.00]二",
            merged
        )
    }

    @Test
    fun `plain translation lines inherit nearby source timestamps`() {
        val merged = mergeLyricsForExternalPlayers(
            lyrics = "[00:01.00]first\n[00:02.00]second",
            translatedLyrics = "一\n二"
        )

        assertEquals(
            "[00:01.00]first\n[00:01.00]一\n" +
                "[00:02.00]second\n[00:02.00]二",
            merged
        )
    }

    @Test
    fun `plain lyrics are interleaved by line when neither side is timed`() {
        assertEquals(
            "first\n一\nsecond\n二",
            mergeLyricsForExternalPlayers(
                lyrics = "first\nsecond",
                translatedLyrics = "一\n二"
            )
        )
    }

    @Test
    fun `translation-only content remains available in the standard field`() {
        assertEquals(
            "[00:01.00]一",
            mergeLyricsForExternalPlayers(
                lyrics = null,
                translatedLyrics = "[00:01.00]一"
            )
        )
    }

    @Test
    fun `equal distance keeps translation order and matching consumes each candidate once`() {
        assertEquals(
            "[00:10]A\n[00:10]first\n[00:11]B\n[00:09]second",
            mergeLyricsForExternalPlayers(
                "[00:10]A\n[00:11]B",
                "[00:11]first\n[00:09]second"
            )
        )
    }

    @Test
    fun `translation tolerance includes 1500 milliseconds in either direction`() {
        assertEquals(
            "[00:10]A\n[00:10]early\n[00:20]B\n[00:20]late\n[00:30]C\n[00:31.501]outside",
            mergeLyricsForExternalPlayers(
                "[00:10]A\n[00:20]B\n[00:30]C",
                "[00:08.500]early\n[00:21.500]late\n[00:31.501]outside"
            )
        )
    }

    @Test
    fun `identical translation lines remain separate candidates`() {
        assertEquals(
            "[00:10]A\n[00:10]译\n[00:10]B\n[00:10]译\n[00:10]C",
            mergeLyricsForExternalPlayers(
                "[00:10]A\n[00:10]B\n[00:10]C",
                "[00:10]译\n[00:10]译"
            )
        )
    }

    @Test
    fun `closest candidate wins while unmatched translations keep their input order`() {
        assertEquals(
            "[00:10]A\n[00:10]exact\n[00:08.500]edge\n[00:10.001]near",
            mergeLyricsForExternalPlayers("[00:10]A", "[00:08.500]edge\n[00:10.001]near\n[00:10]exact")
        )
    }

    @Test
    fun `all source tokens are retained while embedded translation tokens are removed`() {
        assertEquals(
            "intro[00:20:00]a[00:10.0][00:10.000]b\n[00:20:00][00:10.0][00:10.000]译尾",
            mergeLyricsForExternalPlayers(
                "intro[00:20:00]a[00:10.0][00:10.000]b",
                "[00:20.25][00:99]  译[00:00.001]尾"
            )
        )
    }

    @Test
    fun `any timed translation disables automatic timestamps for every plain translation`() {
        assertEquals(
            "[00:01]A\n[00:10]B\n[00:10]timed\nplain one\nplain three",
            mergeLyricsForExternalPlayers(
                "[00:01]A\n[00:10]B",
                "plain one\n[00:10]timed\nplain three"
            )
        )
    }

    @Test
    fun `plain translations skip metadata and blank lines when inheriting timestamps`() {
        assertEquals(
            "[ar:o]\nintro\n[00:01]A\n[00:01]一\n\n[00:02]B\n[00:02]二\n[ar:t]\n\n[00:1]invalid\n三",
            mergeLyricsForExternalPlayers(
                "[ar:o]\nintro\n[00:01]A\n\n[00:02]B",
                "[ar:t]\n\n一\n[00:1]invalid\n二\n三"
            )
        )
    }

    @Test
    fun `plain lyrics interleave metadata and blank lines before retaining the translation tail`() {
        assertEquals(
            "[ar:o]\n[ar:t]\n\n译\nA\n\nextra",
            mergeLyricsForExternalPlayers("[ar:o]\n\nA", "[ar:t]\n译\n\nextra")
        )
    }

    @Test
    fun `short plain translation does not drop remaining source lines`() {
        assertEquals("A\n一\nB\nC", mergeLyricsForExternalPlayers("A\nB\nC", "一"))
    }

    @Test
    fun `bounded timestamp digits accept each fractional width and seconds above 59`() {
        val cases = listOf(
            Triple("[00:01.1]A", "[00:01.100]译", "[00:01.1]A\n[00:01.1]译"),
            Triple("[00:01.12]A", "[00:01.120]译", "[00:01.12]A\n[00:01.12]译"),
            Triple("[00:01.123]A", "[00:01:123]译", "[00:01.123]A\n[00:01.123]译"),
            Triple("[001:99:9][002:39.900]A", "[2:39.90]译", "[001:99:9][002:39.900]A\n[001:99:9][002:39.900]译"),
            Triple("[999:99.999]A", "[999:99:999]译", "[999:99.999]A\n[999:99.999]译")
        )
        cases.forEach { (original, translation, expected) ->
            assertEquals(expected, mergeLyricsForExternalPlayers(original, translation))
        }
    }

    @Test
    fun `invalid timestamp widths remain plain lyric content`() {
        assertEquals(
            "[0000:01]A\n一\n[00:1]B\n二\n[00:01.1234]C\n三",
            mergeLyricsForExternalPlayers("[0000:01]A\n[00:1]B\n[00:01.1234]C", "一\n二\n三")
        )
    }

    @Test
    fun `supplementary digits remain plain lyric content without throwing`() {
        val cases = listOf(
            "[𝟘𝟘:𝟘𝟙]A" to "[𝟘𝟘:𝟘𝟙]A\n译",
            "[00:𝟘𝟙]A" to "[00:𝟘𝟙]A\n译",
            "[00:01.𝟙]A" to "[00:01.𝟙]A\n译",
            "[00:01.𝟙2]A" to "[00:01.𝟙2]A\n译",
            "[𝟘:01]A" to "[𝟘:01]A\n译"
        )
        cases.forEach { (original, expected) ->
            assertEquals(expected, mergeLyricsForExternalPlayers(original, "译"))
        }
    }

    @Test
    fun `Unicode decimal timestamps align while retaining their original spelling`() {
        assertEquals(
            "[٠٠:٠١]A\n[٠٠:٠١]一\n[００:０２]B\n[００:０２]二",
            mergeLyricsForExternalPlayers("[٠٠:٠١]A\n[００:０２]B", "[00:01]一\n[00:02]二")
        )
        assertEquals(
            "[٠٠:٠١.٢]A\n[٠٠:٠١.٢]译",
            mergeLyricsForExternalPlayers("[٠٠:٠١.٢]A", "[00:01.200]译")
        )
    }

    @Test
    fun `timed translations remain at the end when source lyrics have no timestamps`() {
        assertEquals(
            "first\nsecond\n[00:01]一\n[00:02]二",
            mergeLyricsForExternalPlayers("first\nsecond", "[00:01]一\n[00:02]二")
        )
    }

    @Test
    fun `empty side fallback trims only the outer content and preserves internal line endings`() {
        assertNull(mergeLyricsForExternalPlayers(null, " \n "))
        assertNull(mergeLyricsForExternalPlayers("\t ", null))
        assertEquals("A\r\n B", mergeLyricsForExternalPlayers(" \nA\r\n B\n ", " "))
        assertEquals("一\r\n 二", mergeLyricsForExternalPlayers(null, " \n一\r\n 二\n "))
        assertEquals("A\n一\n B\n 二", mergeLyricsForExternalPlayers(" A\r\n B ", " 一\r\n 二 "))
    }

    @Test
    fun `standard lyric metadata aliases follow the audio container case insensitively`() {
        assertEquals(listOf("LYRICS", "UNSYNCEDLYRICS"), standardLyricsMetadataKeys("MP3"))
        listOf("m4a", "MP4", "aac").forEach { extension ->
            assertEquals(listOf("LYRICS", "DESCRIPTION"), standardLyricsMetadataKeys(extension))
        }
        assertEquals(listOf("LYRICS"), standardLyricsMetadataKeys("flac"))
        assertEquals(listOf("LYRICS"), standardLyricsMetadataKeys(null))
    }
}
