package moe.ouom.neriplayer.core.download.storage.tree.cache

import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.operation.content.pendingMetadataCleanupRootEntries
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.query.ManagedDownloadTreeChildQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doReturn

class ManagedDownloadTreeChildCacheTest {
    @Test
    fun `pending cleanup maps only owned candidate names and never dereferences unrelated children`() {
        val unrelatedUri = mock(Uri::class.java)
        org.mockito.Mockito.doThrow(AssertionError("unrelated root entry converted"))
            .`when`(unrelatedUri).toString()
        val children = listOf(
            child("other.mp3", unrelatedUri),
            child("other.mp3.npmeta.json", unrelatedUri),
            child("song.mp3.npmeta.pending.json", uri("content://p/pending")),
            child("song.mp3.npmeta.pending (1).json", uri("content://p/numbered")),
            child("song.mp3.npdl_pending.owner.pending", uri("content://p/audio")),
            child("song.mp3.npmeta.pending.json", unrelatedUri).copy(isDirectory = true)
        )
        assertEquals(listOf("content://p/pending", "content://p/numbered", "content://p/audio"),
            pendingMetadataCleanupRootEntries(children, "song.mp3").map { it.reference })
    }

    @Test
    fun `same name replacement keeps the returned opaque reference without charging twice`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 2)
        cache.rememberChildren("a", listOf(child("same", uri("content://opaque/old"))), 1L, true)
        repeat(10) {
            cache.rememberChild("a", child("same", uri("content://opaque/$it")), 2L)
        }
        cache.rememberChild("a", child("second", uri("content://opaque/second")), 3L)
        assertEquals(2, cache.peekChildren("a")?.size)
        assertNull(cache.peekChildByReference("a", "content://opaque/old", true))
        assertEquals("content://opaque/9", cache.peekChild("a", "same", true)?.documentUri.toString())
        cache.forgetChildName("a", "same", 4L)
        cache.rememberChildName("a", "reserved", 5L, true)
        cache.rememberChild("a", child("reserved", uri("content://opaque/new")), 6L)
        assertEquals(2, cache.peekChildren("a")?.size)
    }

    @Test
    fun `two large roots share default budget without dropping reserved names`() {
        val cache = ManagedDownloadTreeChildCache()
        for (parent in listOf("a", "b")) {
            cache.rememberChildren(parent, (0 until 32767).map {
                child("song-$it", uri("content://$parent/$it"))
            }, 1L, true)
            cache.rememberChildName(parent, "pending", 2L, true)
        }
        for (parent in listOf("a", "b")) {
            assertEquals(32767, cache.peekChildren(parent)?.size)
            assertTrue(cache.cachedNames(parent, 2L, 100L, true)?.contains("pending") == true)
        }
    }

    @Test
    fun `parent pressure cannot evict active reservations`() {
        val cache = ManagedDownloadTreeChildCache()
        repeat(256) { cache.rememberChildName("p$it", "reserved", 1L, true) }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            cache.rememberChildName("overflow", "reserved", 2L, true)
        }
        repeat(256) {
            assertEquals(setOf("reserved"), cache.rememberChildren("p$it", emptyList(), 3L, true))
        }
        cache.forgetChildName("p0", "reserved", 4L)
        cache.rememberChildName("overflow", "reserved", 5L, true)
        assertEquals(setOf("reserved"), cache.rememberChildren("overflow", emptyList(), 6L, true))
    }

    @Test
    fun `shared budget evicts snapshots but pins reservations and releases deleted names`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 12)
        fun fill(parent: String, count: Int, time: Long) = cache.rememberChildren(parent,
            (0 until count).map { child("$parent-$it", uri("content://$parent/$it")) }, time, true)
        fill("a", 5, 1L)
        cache.rememberChildName("a", "reserved", 2L, true)
        fill("b", 6, 3L)
        cache.rememberChildName("b", "reserved-b", 4L, true)
        assertEquals(5, cache.peekChildren("a")?.size)
        assertNull(cache.peekChildren("b"))
        val names = fill("b", 5, 5L)
        assertTrue("reserved-b" in names)
        assertEquals(5, cache.peekChildren("b")?.size)
        cache.forgetChildName("a", "reserved", 6L)
        fill("c", 8, 7L)
        assertNull(cache.peekChildren("a"))
        assertNull(cache.peekChildren("c"))
        cache.forgetChildName("b", "reserved-b", 8L)
        fill("c", 8, 9L)
        assertEquals(8, cache.peekChildren("c")?.size)
        assertNull(cache.peekChildren("b"))
    }

    @Test
    fun `oversized refresh keeps bounded reservations and expiry releases capacity`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 4)
        cache.rememberChildren("a", emptyList(), 1L, true)
        repeat(4) { cache.rememberChildName("a", "r$it", 2L, true) }
        val oversized = (0..4).map { child("song-$it", uri("content://a/$it")) }
        val refreshed = cache.rememberChildren("a", oversized, 3L, true)
        assertTrue(refreshed.containsAll(listOf("r0", "r1", "r2", "r3")))
        assertNull(cache.peekChildren("a"))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            cache.rememberChildName("b", "cannot-reserve", 4L, true)
        }
        cache.rememberChildName("b", "after-expiry", 600_003L, true)
        val names = cache.rememberChildren("b", emptyList(), 600_004L, true)
        assertEquals(setOf("after-expiry"), names)
        cache.clear()
        assertEquals(emptySet<String>(), cache.rememberChildren("b", emptyList(), 600_005L, true))
    }

    @Test
    fun `complete snapshot ttl and incomplete absence stay conservative after borrowing`() {
        val cache = ManagedDownloadTreeChildCache()
        val children = (0 until 8193).map { child("s$it", uri("content://a/$it")) }
        cache.rememberChildren("a", children, 1L, true)
        assertEquals(8193, cache.cachedChildren("a", 10L, 10L)?.size)
        assertNull(cache.cachedChildren("a", 12L, 10L))
        cache.rememberChildren("a", emptyList(), 13L, false)
        assertNull(cache.cachedChildren("a", 13L, 10L))
        assertNull(cache.cachedNames("a", 13L, 10L, true))
        assertEquals(8193, cache.peekAllChildren("a")?.size)
    }

    @Test
    fun `large parent borrows shared budget across the old cliff`() {
        val cache = ManagedDownloadTreeChildCache()
        for (size in listOf(8191, 8192, 8193, 16384)) {
            val children = (0 until size).map { child("song-$it", uri("content://p/$it")) }
            cache.rememberChildren("large", children, 1L, true)
            cache.rememberChildName("large", "reserved", 2L, true)
            assertEquals(size, cache.peekChildren("large")?.size)
            assertTrue(cache.cachedNames("large", 2L, 100L, true)?.contains("reserved") == true)
            cache.rememberChild("large", child("reserved", uri("content://p/new")), 3L)
            assertEquals(size + 1, cache.peekChildren("large")?.size)
            cache.forgetChildName("large", "reserved", 4L)
        }
    }

    @Test
    fun `oversized directory is returned but not retained in cache`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 32)
        val children = (0..32)
            .map { index -> child("song-$index.mp3") }

        val names = cache.rememberChildren(
            cacheKey = "large-parent",
            children = children,
            refreshedAtMs = 1L,
            isComplete = true
        )

        assertEquals(children.size, names.size)
        assertNull(cache.peekAllChildren("large-parent"))
        assertNull(
            cache.cachedChildren(
                cacheKey = "large-parent",
                nowMs = 1L,
                maxCacheAgeMs = 1_000L
            )
        )
    }

    @Test
    fun `oversized incomplete refresh preserves previous snapshot for recovery`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 32)
        val previous = child("known.mp3")
        cache.rememberChildren("parent", listOf(previous), 1L, isComplete = true)
        val partial = (0..32)
            .map { index -> child("partial-$index.mp3") }

        cache.rememberChildren("parent", partial, 2L, isComplete = false)

        assertEquals(
            listOf("known.mp3"),
            cache.peekAllChildren("parent")?.map(QueriedTreeChild::name)?.toList()
        )
    }

    @Test
    fun `small refresh re-enables cache after oversized directory`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 32)
        val oversized = (0..32)
            .map { index -> child("song-$index.mp3") }
        cache.rememberChildren("parent", oversized, 1L, isComplete = true)

        val retained = child("retained.mp3")
        cache.rememberChildren("parent", listOf(retained), 2L, isComplete = true)

        assertEquals(
            listOf("retained.mp3"),
            cache.peekChildren("parent")?.map(QueriedTreeChild::name)?.toList()
        )
    }

    @Test
    fun `replacing an existing child does not consume total cache budget`() {
        val cache = ManagedDownloadTreeChildCache()
        val same = child("same.mp3")
        cache.rememberChild("parent", same, refreshedAtMs = 1L)

        repeat(ManagedDownloadTreeChildCache.MAX_CACHED_CHILDREN_TOTAL + 1) {
            cache.rememberChild("parent", same, refreshedAtMs = 2L)
        }
        cache.rememberChild("another-parent", child("other.mp3"), refreshedAtMs = 3L)

        assertTrue(cache.peekAllChildren("another-parent")?.isNotEmpty() == true)
    }

    @Test
    fun `missing provider size stays nullable until legacy entry mapping`() {
        val child = QueriedTreeChild(
            name = "unknown-size.bin",
            documentUri = mock(Uri::class.java),
            sizeBytes = null,
            lastModifiedMs = 0L,
            isDirectory = false
        )

        assertEquals(
            0L,
            ManagedDownloadStoredEntryMapper.fromTreeChild(child).sizeBytes
        )
    }

    @Test
    fun `incomplete refresh keeps cached children and reserved names`() {
        val cache = ManagedDownloadTreeChildCache()
        val previous = child("old.txt")
        cache.rememberChildren("parent", listOf(previous), 1L, isComplete = true)
        cache.rememberChildName("parent", "reserved.txt", 2L, isReservation = true)

        val names = cache.rememberChildren(
            cacheKey = "parent",
            children = listOf(child("new.txt")),
            refreshedAtMs = 3L,
            isComplete = false
        )

        assertEquals(
            setOf("old.txt", "new.txt"),
            cache.peekAllChildren("parent")?.map(QueriedTreeChild::name)?.toSet()
        )
        assertEquals(
            setOf("old.txt", "new.txt", "reserved.txt"),
            names
        )
    }

    @Test
    fun `same URI refreshed name replaces stale cached display name`() {
        val cache = ManagedDownloadTreeChildCache()
        val sameUri = mock(Uri::class.java)
        cache.rememberChildren(
            cacheKey = "parent",
            children = listOf(child("old.txt", sameUri)),
            refreshedAtMs = 1L,
            isComplete = true
        )

        cache.rememberChildren(
            cacheKey = "parent",
            children = listOf(child("new.txt", sameUri)),
            refreshedAtMs = 2L,
            isComplete = false
        )

        assertEquals(
            listOf("new.txt"),
            cache.peekAllChildren("parent")?.map(QueriedTreeChild::name)?.toList()
        )
    }

    @Test
    fun `remembered child with same URI replaces stale cached display name`() {
        val cache = ManagedDownloadTreeChildCache()
        val sameUri = mock(Uri::class.java)
        cache.rememberChildren(
            cacheKey = "parent",
            children = listOf(child("old.txt", sameUri)),
            refreshedAtMs = 1L,
            isComplete = true
        )

        cache.rememberChild(
            cacheKey = "parent",
            child = child("new.txt", sameUri),
            refreshedAtMs = 2L
        )

        assertEquals(
            listOf("new.txt"),
            cache.peekAllChildren("parent")?.map(QueriedTreeChild::name)?.toList()
        )
        assertNull(cache.peekChild("parent", "old.txt", includeIncomplete = true))
        assertEquals(
            "new.txt",
            cache.peekChildByReference("parent", sameUri.toString(), includeIncomplete = true)?.name
        )
    }

    @Test
    fun `forget by reference removes the direct name and reference indexes`() {
        val cache = ManagedDownloadTreeChildCache()
        val reference = uri("content://provider/song/known")
        cache.rememberChildren(
            cacheKey = "parent",
            children = listOf(child("known.mp3", reference)),
            refreshedAtMs = 1L,
            isComplete = true
        )

        cache.forgetChildrenByReference(setOf(reference.toString())) { cacheKey, childName ->
            cache.forgetChildName(cacheKey, childName, refreshedAtMs = 2L)
        }

        assertNull(cache.peekChild("parent", "known.mp3", includeIncomplete = true))
        assertNull(
            cache.peekChildByReference(
                "parent",
                reference.toString(),
                includeIncomplete = true
            )
        )
    }

    @Test
    fun `same URI rename at shared budget limit remains cacheable`() {
        val cache = ManagedDownloadTreeChildCache(maxCachedEntries = 32)
        val children = (0 until 32)
            .map { index ->
                child(
                    name = "song-$index.mp3",
                    documentUri = uri("content://provider/song/$index")
                )
            }
        cache.rememberChildren("parent", children, refreshedAtMs = 1L, isComplete = true)

        cache.rememberChild(
            cacheKey = "parent",
            child = child("renamed.mp3", children.first().documentUri),
            refreshedAtMs = 2L
        )

        val names = cache.peekAllChildren("parent")?.map(QueriedTreeChild::name).orEmpty()
        assertTrue("renamed.mp3" in names)
        assertFalse("song-0.mp3" in names)
    }

    @Test
    fun `provider loading and error extras are incomplete`() {
        assertTrue(ManagedDownloadTreeChildQuery.isCompleteQuery(false, null))
        assertFalse(ManagedDownloadTreeChildQuery.isCompleteQuery(true, null))
        assertFalse(ManagedDownloadTreeChildQuery.isCompleteQuery(false, "provider unavailable"))
    }

    private fun child(name: String, documentUri: Uri = mock(Uri::class.java)): QueriedTreeChild {
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
