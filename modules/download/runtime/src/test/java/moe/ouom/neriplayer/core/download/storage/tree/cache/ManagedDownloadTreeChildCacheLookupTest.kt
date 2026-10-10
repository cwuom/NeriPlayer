package moe.ouom.neriplayer.core.download.storage.tree.cache

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class ManagedDownloadTreeChildCacheLookupTest {

    @Test
    fun `cached child lookup requires a fresh complete snapshot`() {
        val cache = ManagedDownloadTreeChildCache()
        val song = child("song.mp3", uri("content://p/song"))
        assertNull(cache.cachedChild("parent", "song.mp3", nowMs = 1L, maxCacheAgeMs = 100L))

        cache.rememberChildren("parent", listOf(song), refreshedAtMs = 10L, isComplete = true)

        assertEquals(song, cache.cachedChild("parent", "song.mp3", nowMs = 110L, maxCacheAgeMs = 100L))
        assertNull(cache.cachedChild("parent", "missing.mp3", nowMs = 20L, maxCacheAgeMs = 100L))
        assertNull(cache.cachedChild("parent", "song.mp3", nowMs = 20L, maxCacheAgeMs = 0L))
        assertNull(cache.cachedChild("parent", "song.mp3", nowMs = 111L, maxCacheAgeMs = 100L))

        cache.rememberChildren("parent", emptyList(), refreshedAtMs = 30L, isComplete = false)

        assertNull(cache.cachedChild("parent", "song.mp3", nowMs = 31L, maxCacheAgeMs = 100L))
    }

    @Test
    fun `peek lookups only expose incomplete snapshots when asked to`() {
        val cache = ManagedDownloadTreeChildCache()
        val song = child("song.mp3", uri("content://p/song"))
        cache.rememberChildren("parent", listOf(song), refreshedAtMs = 1L, isComplete = true)

        assertEquals(song, cache.peekChild("parent", "song.mp3", includeIncomplete = false))
        assertEquals(song, cache.peekChildByReference("parent", "content://p/song", includeIncomplete = false))
        assertNull(cache.peekChild("unknown", "song.mp3", includeIncomplete = true))
        assertNull(cache.peekChildByReference("unknown", "content://p/song", includeIncomplete = true))

        cache.rememberChildren("parent", emptyList(), refreshedAtMs = 2L, isComplete = false)

        assertNull(cache.peekChild("parent", "song.mp3", includeIncomplete = false))
        assertNull(cache.peekChildByReference("parent", "content://p/song", includeIncomplete = false))
        assertEquals(song, cache.peekChild("parent", "song.mp3", includeIncomplete = true))
        assertEquals(song, cache.peekChildByReference("parent", "content://p/song", includeIncomplete = true))
    }

    @Test
    fun `oversized parents bypass every lookup until a refresh fits`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 1)
        val first = child("first.mp3", uri("content://p/first"))
        cache.rememberChildren(
            "parent",
            listOf(first, child("second.mp3", uri("content://p/second"))),
            refreshedAtMs = 1L,
            isComplete = true
        )

        cache.rememberChild("parent", first, refreshedAtMs = 2L)

        assertNull(cache.cachedChildren("parent", nowMs = 2L, maxCacheAgeMs = 100L))
        assertNull(cache.cachedChild("parent", "first.mp3", nowMs = 2L, maxCacheAgeMs = 100L))
        assertNull(cache.peekChild("parent", "first.mp3", includeIncomplete = true))
        assertNull(cache.peekChildByReference("parent", "content://p/first", includeIncomplete = true))
        assertNull(cache.peekAllChildren("parent"))

        cache.rememberChildren("parent", listOf(first), refreshedAtMs = 3L, isComplete = true)

        assertEquals(listOf(first), cache.cachedChildren("parent", nowMs = 3L, maxCacheAgeMs = 100L)?.toList())
    }

    @Test
    fun `expired reservations drop only names that never materialized`() {
        val cache = ManagedDownloadTreeChildCache()
        cache.rememberChildren("parent", listOf(child("other.mp3", uri("content://p/other"))), 1L, isComplete = true)
        cache.rememberChildName("parent", "song.mp3", refreshedAtMs = 1L, isReservation = true)
        cache.rememberChildName("parent", "ghost.mp3", refreshedAtMs = 1L, isReservation = true)
        cache.rememberChildren("parent", listOf(child("song.mp3", uri("content://p/song"))), 2L, isComplete = false)

        assertEquals(
            setOf("other.mp3", "song.mp3", "ghost.mp3"),
            cache.rememberChildren("parent", emptyList(), refreshedAtMs = 3L, isComplete = false)
        )
        assertEquals(
            setOf("other.mp3", "song.mp3"),
            cache.rememberChildren("parent", emptyList(), refreshedAtMs = 600_002L, isComplete = false)
        )
    }

    @Test
    fun `oversized parent markers are bounded to the parent budget`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 1)
        val oversizedListing = listOf(child("a.mp3", uri("content://p/a")), child("b.mp3", uri("content://p/b")))
        val parents = (0..ManagedDownloadTreeChildCache.MAX_CACHED_PARENT_COUNT).map { "parent-$it" }
        parents.forEach { parent ->
            cache.rememberChildren(parent, oversizedListing, refreshedAtMs = 1L, isComplete = true)
        }

        val released = parents.filter { parent ->
            cache.rememberChild(parent, child("solo.mp3", uri("content://p/$parent/solo")), refreshedAtMs = 2L)
            cache.peekAllChildren(parent) != null
        }

        assertEquals(1, released.size)
        assertNotEquals(parents.last(), released.single())
    }

    private fun child(name: String, documentUri: Uri): QueriedTreeChild {
        return QueriedTreeChild(
            name = name,
            documentUri = documentUri,
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = false
        )
    }

    private fun uri(value: String): Uri {
        return mock(Uri::class.java).also { uri ->
            doReturn(value).`when`(uri).toString()
        }
    }
}
