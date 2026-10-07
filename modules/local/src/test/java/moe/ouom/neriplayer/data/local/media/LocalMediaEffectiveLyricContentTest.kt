package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaEffectiveLyricContentTest {
    @Test
    fun `sidecar lyrics win over embedded lyrics even when empty`() {
        assertEquals("[00:01]sidecar", LocalMediaSupport.resolveEffectiveLocalLyricContent("[00:01]sidecar", "[00:01]embedded"))
        assertEquals("", LocalMediaSupport.resolveEffectiveLocalLyricContent("", "[00:01]embedded"))
    }

    @Test
    fun `embedded lyrics are used only when they contain text`() {
        assertEquals("[00:01]embedded", LocalMediaSupport.resolveEffectiveLocalLyricContent(null, "[00:01]embedded"))
        assertNull(LocalMediaSupport.resolveEffectiveLocalLyricContent(null, " \n "))
        assertNull(LocalMediaSupport.resolveEffectiveLocalLyricContent(null, null))
    }

    @Test
    fun `lyric paths are reported only when content was resolved`() {
        assertEquals("/music/song.lrc", LocalMediaSupport.resolveEffectiveLocalLyricPath("/music/song.lrc", ""))
        assertNull(LocalMediaSupport.resolveEffectiveLocalLyricPath("/music/song.lrc", null))
        assertNull(LocalMediaSupport.resolveEffectiveLocalLyricPath(null, "[00:01]sidecar"))
    }
}
