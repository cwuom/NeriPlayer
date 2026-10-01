package moe.ouom.neriplayer.data.local.storage.scan

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.storage.cleanup.StorageCacheCleaner
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.local.storage.source.StorageCacheFileAccess
import moe.ouom.neriplayer.data.local.storage.source.StorageLocations
import moe.ouom.neriplayer.data.local.storage.source.StoragePlatformCacheAccess
import moe.ouom.neriplayer.data.local.storage.source.StorageUsageSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUsageComponentsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun scannerCountsFilesAndDatabasePagesWithoutInitializingApplication() = runBlocking {
        val locations = locations()
        write(locations.mediaCacheDir, "audio", 20)
        write(locations.filesDir, "preferences", 3)
        write(File(locations.filesDir, "netease_playlist_cache"), "legacy", 7)
        locations.databaseFiles.single().writeBytes(ByteArray(100))
        val source = TestSource(
            platformStats = mapOf("netease" to StoragePlatformCacheStats(2, 30L)),
            indexStats = StorageDownloadIndexStats(4, 40L)
        )

        val snapshot = StorageUsageScanner(locations, source).scan()

        assertEquals(FileStats(20, 1), snapshot.items.getValue(StorageUsageItemKind.AudioCache).stats)
        assertEquals(FileStats(3, 1), snapshot.items.getValue(StorageUsageItemKind.AppData).stats)
        assertEquals(37L, snapshot.items.getValue(StorageUsageItemKind.NeteasePlaylistCache).stats.sizeBytes)
        assertEquals(2, snapshot.items.getValue(StorageUsageItemKind.NeteasePlaylistCache).databaseRecordCount)
        assertEquals(40L, snapshot.items.getValue(StorageUsageItemKind.DownloadIndex).stats.sizeBytes)
        assertEquals(30L, snapshot.items.getValue(StorageUsageItemKind.Database).stats.sizeBytes)
    }

    @Test
    fun scannerAttributesHostProvidedMetadataFilesOnlyToDownloadIndex() = runBlocking {
        val locations = locations()
        locations.downloadMetadataFiles.single().writeBytes(ByteArray(20))

        val snapshot = StorageUsageScanner(locations, TestSource()).scan()

        assertEquals(FileStats(20, 1), snapshot.items.getValue(StorageUsageItemKind.DownloadIndex).stats)
        assertEquals(FileStats.Empty, snapshot.items.getValue(StorageUsageItemKind.AppData).stats)
    }

    @Test
    fun sourceFailureUsesFallbackAndCancellationPropagates() = runBlocking<Unit> {
        val source = object : StorageUsageSource {
            override suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage = error("unavailable")
            override suspend fun platformCacheStats(): Map<String, StoragePlatformCacheStats> = emptyMap()
            override suspend fun downloadIndexStats(): StorageDownloadIndexStats = StorageDownloadIndexStats.Empty
        }
        val snapshot = StorageUsageScanner(locations(), source).scan()
        assertEquals(FileStats.Empty, snapshot.items.getValue(StorageUsageItemKind.DownloadedMusic).stats)

        val cancelled = object : StorageUsageSource by source {
            override suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage {
                throw CancellationException("cancelled")
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { StorageUsageScanner(locations(), cancelled).scan() }
        }
    }

    @Test
    fun cleanerSeparatesPhysicalBytesFromReusableRoomPages() = runBlocking {
        val locations = locations()
        write(locations.sharedMediaDir, "shared", 7)
        write(File(locations.filesDir, "netease_playlist_cache"), "legacy", 11)
        val untouched = write(locations.localCoverDir, "cover", 13)
        val platforms = TestPlatformCaches()
        val files = TestCacheFiles()

        val result = StorageCacheCleaner(locations, files, platforms).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false,
                sharedMedia = true, neteasePlaylistCache = true)
        )

        assertEquals(ExtraCacheClearResult(true, 18, 80, 2), result)
        assertEquals(listOf("netease"), platforms.cleared)
        assertTrue(untouched.exists())
        assertTrue(locations.sharedMediaDir.isDirectory)
        assertEquals(setOf(StorageCacheKind.SharedMedia, StorageCacheKind.NeteasePlaylist), files.cleared.toSet())
    }

    @Test
    fun cleanerContinuesAfterFileFailureAndReportsPartialResult() = runBlocking {
        val locations = locations()
        val retained = write(locations.sharedMediaDir, "shared", 7)
        write(locations.lyricsCacheDir, "lyrics", 11)
        val files = TestCacheFiles(failKind = StorageCacheKind.SharedMedia)

        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(sharedMedia = true, lyricsCache = true)
        )

        assertEquals(ExtraCacheClearResult(false, 11, 0, 1), result)
        assertTrue(retained.exists())
    }

    @Test
    fun cleanerDoesNotTouchPlatformStorageWithoutSelection() = runBlocking {
        val platforms = object : StoragePlatformCacheAccess {
            override suspend fun allocatedBytes(platforms: List<String>): Long = error("unexpected read")
            override suspend fun clear(platforms: List<String>) = error("unexpected clear")
        }
        val result = StorageCacheCleaner(locations(), TestCacheFiles(), platforms)
            .clear(StorageCacheClearOptions())

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), result)
    }

    @Test
    fun cleanerReportsDatabaseFailureWithoutClaimingFreedPages() = runBlocking {
        val platforms = object : StoragePlatformCacheAccess {
            override suspend fun allocatedBytes(platforms: List<String>): Long = 80
            override suspend fun clear(platforms: List<String>) = error("database locked")
        }
        val result = StorageCacheCleaner(locations(), TestCacheFiles(), platforms)
            .clear(StorageCacheClearOptions(biliArchiveCache = true))

        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), result)
    }

    @Test
    fun cleanerRethrowsCancellation() {
        val platforms = object : StoragePlatformCacheAccess {
            override suspend fun allocatedBytes(platforms: List<String>): Long = 80
            override suspend fun clear(platforms: List<String>) { throw CancellationException("cancelled") }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                StorageCacheCleaner(locations(), TestCacheFiles(), platforms)
                    .clear(StorageCacheClearOptions(biliArchiveCache = true))
            }
        }
    }

    @Test
    fun directoryExclusionDoesNotMatchSiblingPrefix() {
        val root = temporary.newFolder()
        val excluded = File(root, "cache")
        write(excluded, "ignored", 5)
        write(File(root, "cache-backup"), "retained", 7)

        assertEquals(FileStats(7, 1), statsOf(root, listOf(excluded)))
        assertEquals(FileStats.Empty, statsOf(null))
        assertEquals(FileStats.Empty, statsOf(File(root, "missing")))
        assertEquals(FileStats(5, 1), statsOf(File(excluded, "ignored")))
    }

    private fun locations(): StorageLocations {
        val root = temporary.newFolder()
        return StorageLocations(
            filesDir = File(root, "files").apply { mkdirs() },
            cacheDir = File(root, "cache").apply { mkdirs() },
            diagnosticsDir = File(root, "external").apply { mkdirs() },
            databaseFiles = listOf(File(root, "database")),
            downloadMetadataFiles = listOf(File(root, "files/host_snapshot.json"))
        )
    }

    private fun write(directory: File, name: String, bytes: Int): File {
        directory.mkdirs()
        return File(directory, name).apply { writeBytes(ByteArray(bytes)) }
    }

    private class TestSource(
        private val platformStats: Map<String, StoragePlatformCacheStats> = emptyMap(),
        private val indexStats: StorageDownloadIndexStats = StorageDownloadIndexStats.Empty
    ) : StorageUsageSource {
        override suspend fun downloadLibraryUsage() = ManagedDownloadLibraryUsage.Empty
        override suspend fun platformCacheStats() = platformStats
        override suspend fun downloadIndexStats() = indexStats
    }

    private class TestPlatformCaches : StoragePlatformCacheAccess {
        var cleared: List<String> = emptyList()
        override suspend fun allocatedBytes(platforms: List<String>): Long = 80
        override suspend fun clear(platforms: List<String>) { cleared = platforms }
    }

    private class TestCacheFiles(private val failKind: StorageCacheKind? = null) : StorageCacheFileAccess {
        val cleared = mutableListOf<StorageCacheKind>()
        override fun stats(file: File) = statsOf(file)
        override fun clear(file: File, kind: StorageCacheKind): Boolean {
            cleared += kind
            if (kind == failKind) return false
            return file.deleteRecursively().also { if (it) file.mkdirs() }
        }
    }
}
