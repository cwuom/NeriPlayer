package moe.ouom.neriplayer.core.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWordTimingTest {
    @Test
    fun `blank lyrics have no editable word timing`() {
        for (lyric in listOf("", " \n\t")) {
            assertFalse(hasEditableLyricWordTiming(lyric))
        }
    }

    @Test
    fun `plain text and line timestamps do not imply word timing`() {
        for (lyric in listOf("hello", "[00:01.00]hello")) {
            assertFalse(hasEditableLyricWordTiming(lyric))
        }
    }

    @Test
    fun `malformed timed markup is rejected without escaping the parser`() {
        assertFalse(hasEditableLyricWordTiming("<tt><body><p begin=\"invalid\">hello"))
    }

    @Test
    fun `yrc and enhanced lrc preserve editable word timing`() {
        assertTrue(hasEditableLyricWordTiming("[1000,900](1000,300,0)爱(1300,600,0)你"))
        assertTrue(hasEditableLyricWordTiming("[00:01.00]<00:01.00>hello<00:02.00> world"))
    }
}
