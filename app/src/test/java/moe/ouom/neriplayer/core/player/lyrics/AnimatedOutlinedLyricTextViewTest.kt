package moe.ouom.neriplayer.core.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedOutlinedLyricTextViewTest {

    @Test
    fun longLyricsRevealFasterPerCharacter() {
        val shortLine = "0123456789"
        val longLine = "01234567890123456789"

        val shortDuration = AnimatedOutlinedLyricTextView.resolveRevealDurationMs(shortLine)
        val longDuration = AnimatedOutlinedLyricTextView.resolveRevealDurationMs(longLine)

        assertEquals(360L, shortDuration)
        assertEquals(540L, longDuration)
        assertTrue(longDuration.toFloat() / longLine.length < shortDuration.toFloat() / shortLine.length)
    }

    @Test
    fun veryLongLyricsUseTheBoundedRevealDuration() {
        assertEquals(
            900L,
            AnimatedOutlinedLyricTextView.resolveRevealDurationMs("0123456789".repeat(5))
        )
    }
}
