package moe.ouom.neriplayer.core.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaUrlPresenceTest {
    @Test
    fun `queue stop log reports only a nonblank media URL`() {
        assertFalse(hasNonBlankMediaUrl(null))
        assertFalse(hasNonBlankMediaUrl(""))
        assertFalse(hasNonBlankMediaUrl(" \t\n"))
        assertTrue(hasNonBlankMediaUrl("https://example.test/track"))
    }
}
