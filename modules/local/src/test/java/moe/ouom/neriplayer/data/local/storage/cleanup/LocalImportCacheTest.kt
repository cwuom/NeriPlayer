package moe.ouom.neriplayer.data.local.storage.cleanup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.FileStats
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImportCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun onlyUnreferencedGeneratedCoversAreCountedAndRemoved() = runBlocking {
        val directory = temporary.newFolder()
        val referenced = cover(directory, 'a', 7)
        val original = cover(directory, 'b', 11)
        val orphan = cover(directory, 'c', 13)
        val unknown = File(directory, "selected.jpg").apply { writeBytes(ByteArray(17)) }
        val nested = cover(File(directory, "keep").apply { mkdirs() }, 'd', 19)
        var invalidations = 0
        val cache = LocalImportCache(directory, { setOf(referenced.toURI().toString(), original.path) }) { invalidations++ }

        assertEquals(FileStats(13, 1), cache.scan())
        assertEquals(ExtraCacheClearResult(true, 13, 0, 1), cache.clear())

        assertFalse(orphan.exists())
        listOf(referenced, original, unknown, nested).forEach { assertTrue(it.exists()) }
        assertEquals(1, invalidations)
    }

    @Test
    fun unavailableReferencesPreserveEveryFile() = runBlocking {
        val directory = temporary.newFolder()
        val orphan = cover(directory, 'a', 7)
        val cache = LocalImportCache(directory, { throw IOException("repository unavailable") }) {}

        assertEquals(FileStats.Empty, cache.scan())
        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), cache.clear())
        assertEquals(7L, orphan.length())
    }

    @Test
    fun filesCreatedOrReplacedDuringReferenceLoadingArePreserved() = runBlocking {
        val directory = temporary.newFolder()
        val replaced = cover(directory, 'a', 7)
        val cache = LocalImportCache(directory, {
            replaced.writeBytes(ByteArray(11))
            cover(directory, 'b', 13)
            emptySet()
        }) {}

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertEquals(11L, replaced.length())
        assertEquals(13L, File(directory, "${"b".repeat(64)}.jpg").length())
    }

    @Test
    fun aFileRemovedDuringReferenceLoadingDoesNotCountAsFreedSpace() = runBlocking {
        val directory = temporary.newFolder()
        val disappearing = cover(directory, 'a', 7)
        val cache = LocalImportCache(directory, {
            assertTrue(disappearing.delete())
            emptySet()
        }) {}

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertFalse(disappearing.exists())
    }

    @Test
    fun replacingTheCacheRootWithALinkStopsCleanup() = runBlocking {
        val directory = File(temporary.newFolder(), "local_audio_covers").apply { mkdirs() }
        cover(directory, 'a', 7)
        val moved = File(directory.parentFile, "moved")
        val cache = LocalImportCache(directory, {
            assertTrue(directory.renameTo(moved))
            Files.createSymbolicLink(directory.toPath(), moved.toPath())
            emptySet()
        }) {}

        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), cache.clear())
        assertEquals(7L, File(moved, "${"a".repeat(64)}.jpg").length())
        assertTrue(Files.isSymbolicLink(directory.toPath()))
    }

    @Test
    fun symbolicLinksAndTheirTargetsArePreserved() = runBlocking {
        val directory = temporary.newFolder()
        val outside = temporary.newFolder()
        val target = cover(outside, 'a', 7)
        val linked = File(directory, "${"a".repeat(64)}.jpg")
        Files.createSymbolicLink(linked.toPath(), target.toPath())
        val cache = LocalImportCache(directory, { emptySet() }) {}

        assertEquals(FileStats.Empty, cache.scan())
        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertEquals(7L, target.length())
        val rootLink = File(temporary.newFolder(), "local_audio_covers")
        Files.createSymbolicLink(rootLink.toPath(), outside.toPath())
        assertEquals(FileStats.Empty, LocalImportCache(rootLink, { emptySet() }) {}.scan())
        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), LocalImportCache(rootLink, { emptySet() }) {}.clear())
        assertTrue(target.exists())
    }

    @Test
    fun cancellationDoesNotDeleteCandidates() {
        val directory = temporary.newFolder()
        val orphan = cover(directory, 'a', 7)
        val cache = LocalImportCache(directory, { throw CancellationException("cancelled") }) {}

        assertThrows(CancellationException::class.java) { runBlocking { cache.clear() } }
        assertTrue(orphan.exists())
    }

    @Test
    fun missingDirectoryStillClearsTemporaryReadIndexes() = runBlocking {
        var invalidations = 0
        val cache = LocalImportCache(File(temporary.root, "missing"), { emptySet() }) { invalidations++ }

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertEquals(1, invalidations)
    }

    @Test
    fun aChangedReferenceGenerationStopsDeletionBeforeTheFirstFile() = runBlocking {
        val directory = temporary.newFolder()
        val cached = cover(directory, 'a', 7)
        val cache = LocalImportCache(directory, { emptySet() }, referenceSnapshotValid = { false }) {}

        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), cache.clear())
        assertTrue(cached.exists())
    }

    private fun cover(directory: File, key: Char, bytes: Int): File =
        File(directory, "${key.toString().repeat(64)}.jpg").apply { writeBytes(ByteArray(bytes)) }
}
