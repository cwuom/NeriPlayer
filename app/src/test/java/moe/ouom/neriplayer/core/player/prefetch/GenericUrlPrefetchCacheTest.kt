package moe.ouom.neriplayer.core.player.prefetch

import moe.ouom.neriplayer.core.player.model.SongUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericUrlPrefetchCacheTest {
    @Test
    fun consume_returnsFreshEntryOnlyOnce() {
        val cache = GenericUrlPrefetchCache(ttlMs = 90L)
        val result = SongUrlResult.Success(url = "https://audio.example/track")

        cache.put(key = "track", result = result, nowMs = 10L)

        assertEquals(result, cache.consume(key = "track", nowMs = 99L))
        assertNull(cache.consume(key = "track", nowMs = 99L))
    }

    @Test
    fun consume_discardsExpiredEntry() {
        val cache = GenericUrlPrefetchCache(ttlMs = 90L)
        cache.put(
            key = "track",
            result = SongUrlResult.Success(url = "https://audio.example/track"),
            nowMs = 10L
        )

        assertNull(cache.consume(key = "track", nowMs = 100L))
        assertFalse(cache.containsFresh(key = "track", nowMs = 100L))
    }

    @Test
    fun containsFresh_removesExpiredEntry() {
        val cache = GenericUrlPrefetchCache(ttlMs = 90L)
        cache.put(
            key = "track",
            result = SongUrlResult.Success(url = "https://audio.example/track"),
            nowMs = 10L
        )

        assertTrue(cache.containsFresh(key = "track", nowMs = 99L))
        assertFalse(cache.containsFresh(key = "track", nowMs = 100L))
        assertNull(cache.consume(key = "track", nowMs = 99L))
    }

    @Test
    fun constructor_rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException::class.java) {
            GenericUrlPrefetchCache(ttlMs = 0L)
        }
    }

    @Test
    fun put_evictsOldestEntryWhenCapacityIsReached() {
        val cache = GenericUrlPrefetchCache(ttlMs = 90L, maxEntries = 2)
        cache.put("first", SongUrlResult.Success("https://audio.example/first"), nowMs = 10L)
        cache.put("second", SongUrlResult.Success("https://audio.example/second"), nowMs = 20L)

        cache.put("third", SongUrlResult.Success("https://audio.example/third"), nowMs = 30L)

        assertNull(cache.consume("first", nowMs = 31L))
        assertTrue(cache.containsFresh("second", nowMs = 31L))
        assertTrue(cache.containsFresh("third", nowMs = 31L))
    }
}
