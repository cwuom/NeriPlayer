package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.Assert.*
import org.junit.Test

class SubsonicLyricsCacheTest {
    private val first = ServerSongRef("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "track")
    private val second = ServerSongRef("9e8a8fc4-1e35-4b5a-884d-8bb2423cc792", "track")
    private val lyrics = listOf(LyricEntry("cached line", 1000, 2000))

    @Test fun `same track ids on different accounts stay separate`() {
        val cache = SubsonicLyricsCache()
        cache.put(first, lyrics)
        assertEquals(lyrics, cache.get(first))
        assertNull(cache.get(second))
    }

    @Test fun `empty results cannot erase successful lyrics`() {
        val cache = SubsonicLyricsCache()
        cache.put(first, lyrics)
        cache.put(first, emptyList())
        cache.put(first, listOf(LyricEntry("", 0, 0)))
        assertEquals(lyrics, cache.get(first))
    }

    @Test fun `expired entries require a fresh fetch`() {
        var time = 0L
        val cache = SubsonicLyricsCache(ttlNanos = 100, now = { time })
        cache.put(first, lyrics)
        time = 99
        assertEquals(lyrics, cache.get(first))
        time = 100
        assertNull(cache.get(first))
    }

    @Test fun `successful empty lyrics expire after sixty seconds`() {
        var time = 0L
        val cache = SubsonicLyricsCache(now = { time })
        cache.put(first, emptyList())
        assertEquals(emptyList<LyricEntry>(), cache.get(first))
        time = 59_999_999_999L
        assertEquals(emptyList<LyricEntry>(), cache.get(first))
        time = 60_000_000_000L
        assertNull(cache.get(first))
    }

    @Test fun `successful empty lyrics stay isolated by profile track and revision`() {
        val cache = SubsonicLyricsCache()
        cache.put(first, emptyList(), revision = 3L)
        assertEquals(emptyList<LyricEntry>(), cache.get(first, revision = 3L))
        assertNull(cache.get(second, revision = 3L))
        assertNull(cache.get(first.copy(songId = "another track"), revision = 3L))
        assertNull(cache.get(first, revision = 4L))
    }

    @Test fun `explicit retry invalidates only the requested cached track`() {
        val cache = SubsonicLyricsCache()
        cache.put(first, emptyList())
        cache.put(second, lyrics)
        cache.remove(first)
        assertNull(cache.get(first))
        assertEquals(lyrics, cache.get(second))
    }

    @Test fun `removing a profile clears both positive and negative lyrics`() {
        val cache = SubsonicLyricsCache()
        val otherTrack = first.copy(songId = "another track")
        cache.put(first, emptyList())
        cache.put(otherTrack, lyrics)
        cache.put(second, emptyList())
        cache.removeProfile(first.profileId)
        assertNull(cache.get(first))
        assertNull(cache.get(otherTrack))
        assertEquals(emptyList<LyricEntry>(), cache.get(second))
    }

    @Test fun `bounded cache evicts least recently used entry`() {
        val cache = SubsonicLyricsCache(capacity = 2)
        val third = first.copy(songId = "another track")
        cache.put(first, lyrics)
        cache.put(second, lyrics)
        cache.get(first)
        cache.put(third, lyrics)
        assertNull(cache.get(second))
        assertEquals(lyrics, cache.get(first))
        assertEquals(lyrics, cache.get(third))
    }
}
