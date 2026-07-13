package moe.ouom.neriplayer.core.player.policy.wake

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackWakeModePolicyTest {
    @Test
    fun `network streams keep network wake mode`() {
        assertEquals(C.WAKE_MODE_NETWORK, resolvePlaybackWakeMode("https://example.test/a.mp3"))
        assertEquals(C.WAKE_MODE_NETWORK, resolvePlaybackWakeMode("http://example.test/a.mp3"))
    }

    @Test
    fun `local and saf streams use local wake mode`() {
        assertEquals(C.WAKE_MODE_LOCAL, resolvePlaybackWakeMode("/sdcard/Music/a.flac"))
        assertEquals(C.WAKE_MODE_LOCAL, resolvePlaybackWakeMode("file:///sdcard/Music/a.flac"))
        assertEquals(C.WAKE_MODE_LOCAL, resolvePlaybackWakeMode("content://media/external/audio/1"))
        assertEquals(C.WAKE_MODE_LOCAL, resolvePlaybackWakeMode("android.resource://pkg/raw/a"))
    }

    @Test
    fun `offline cache pseudo url does not hold network wake lock`() {
        assertEquals(C.WAKE_MODE_LOCAL, resolvePlaybackWakeMode("http://offline.cache/song"))
    }

    @Test
    fun `blank url disables explicit wake mode`() {
        assertEquals(C.WAKE_MODE_NONE, resolvePlaybackWakeMode(" "))
        assertEquals(C.WAKE_MODE_NONE, resolvePlaybackWakeMode(null))
    }
}
