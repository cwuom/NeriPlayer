package moe.ouom.neriplayer.core.player.runtime.prefetch

import org.junit.Assert.*
import org.junit.Test

class ServerMediaPrefetchPolicyTest {
    @Test fun `only buffered playing media on unmetered networks can prefetch`() {
        assertTrue(canPrefetchServerMedia(true, true, true, true, 5000, 100000))
        assertFalse(canPrefetchServerMedia(false, true, true, true, 5000, 100000))
        assertFalse(canPrefetchServerMedia(true, false, true, true, 5000, 100000))
        assertFalse(canPrefetchServerMedia(true, true, false, true, 5000, 100000))
        assertFalse(canPrefetchServerMedia(true, true, true, false, 5000, 100000))
        assertFalse(canPrefetchServerMedia(true, true, true, true, 4999, 100000))
        assertTrue(canPrefetchServerMedia(true, true, true, true, 2000, 2000))
        assertFalse(canPrefetchServerMedia(true, true, true, true, 1999, 2000))
    }

    @Test fun `byte budget respects short files and cache capacity`() {
        assertEquals(100, serverMediaPrefetchBytes(100L, 1024 * 1024))
        assertEquals(128 * 1024, serverMediaPrefetchBytes(null, 1024 * 1024))
        assertEquals(GENERIC_MEDIA_PREFETCH_BYTES, serverMediaPrefetchBytes(null, -1))
    }
}
