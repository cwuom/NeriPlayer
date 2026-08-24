package moe.ouom.neriplayer.core.download.storage.tree.cache

import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.query.ManagedDownloadTreeChildQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadTreeChildCacheTest {
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
}
