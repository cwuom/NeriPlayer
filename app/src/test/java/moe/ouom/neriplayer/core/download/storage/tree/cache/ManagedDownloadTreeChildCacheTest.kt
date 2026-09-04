package moe.ouom.neriplayer.core.download.storage.tree.cache

import android.net.Uri
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
    fun `oversized directory is returned but not retained in cache`() {
        val cache = ManagedDownloadTreeChildCache()
        val children = (0..ManagedDownloadTreeChildCache.MAX_CACHED_CHILDREN_PER_PARENT)
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
        val cache = ManagedDownloadTreeChildCache()
        val previous = child("known.mp3")
        cache.rememberChildren("parent", listOf(previous), 1L, isComplete = true)
        val partial = (0..ManagedDownloadTreeChildCache.MAX_CACHED_CHILDREN_PER_PARENT)
            .map { index -> child("partial-$index.mp3") }

        cache.rememberChildren("parent", partial, 2L, isComplete = false)

        assertEquals(
            listOf("known.mp3"),
            cache.peekAllChildren("parent")?.map(QueriedTreeChild::name)?.toList()
        )
    }

    @Test
    fun `small refresh re-enables cache after oversized directory`() {
        val cache = ManagedDownloadTreeChildCache()
        val oversized = (0..ManagedDownloadTreeChildCache.MAX_CACHED_CHILDREN_PER_PARENT)
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
    }

    @Test
    fun `same URI rename at per parent limit remains cacheable`() {
        val cache = ManagedDownloadTreeChildCache()
        val children = (0 until ManagedDownloadTreeChildCache.MAX_CACHED_CHILDREN_PER_PARENT)
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
