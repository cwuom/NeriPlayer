package moe.ouom.neriplayer.data.local.storage.scan

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.storage.cleanup.StorageCacheCleaner
import moe.ouom.neriplayer.data.local.storage.cleanup.LocalImportCache
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
    fun selectingLocalImportCacheDoesNotSilentlySkipCleanup() = runBlocking {
        val result = StorageCacheCleaner(locations(), TestCacheFiles(), TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, localImportCache = true)
        )

        // 缺少可靠引用来源时必须报告未清理，不能让选中的类型静默成功
        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), result)
    }

    @Test
    fun localImportCoversArePartitionedWithoutDoubleCountingOrDeletingReferencedFiles() = runBlocking {
        val locations = locations()
        val retained = write(locations.localCoverDir, "${"a".repeat(64)}.jpg", 7)
        val unused = write(locations.localCoverDir, "${"b".repeat(64)}.jpg", 11)
        val cache = LocalImportCache(locations.localCoverDir, { setOf(retained.toURI().toString()) }) {}
        val snapshot = StorageUsageScanner(locations, TestSource(), cache).scan()

        assertEquals(FileStats(11, 1), snapshot.items.getValue(StorageUsageItemKind.LocalImportCache).stats)
        assertEquals(FileStats(7, 1), snapshot.items.getValue(StorageUsageItemKind.LocalCovers).stats)
        assertEquals(FileStats.Empty, snapshot.items.getValue(StorageUsageItemKind.AppData).stats)
        assertEquals(18L, snapshot.items.values.sumOf { it.stats.sizeBytes })

        val result = StorageCacheCleaner(locations, TestCacheFiles(), TestPlatformCaches(), cache).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, localImportCache = true)
        )

        assertEquals(ExtraCacheClearResult(true, 11, 0, 1), result)
        assertTrue(retained.exists())
        assertFalse(unused.exists())
    }

    @Test
    fun disappearingFileDoesNotDiscardOtherFilesInTheDirectory() {
        val root = temporary.newFolder()
        val gone = write(root, "gone", 2)
        val kept = write(root, "kept", 7)
        val disappearing = object : File(gone.path) {
            override fun isFile(): Boolean = super.isFile().also { if (it) delete() }
        }
        val directory = object : File(root.path) {
            override fun listFiles(): Array<File> = arrayOf(disappearing, kept)
        }
        assertEquals(FileStats(7, 1), statsOf(directory))
    }

    @Test
    fun scannerPropagatesDirectoryReadFailuresInsteadOfReportingZero() = runBlocking<Unit> {
        val locations = locations()
        val brokenCache = object : File(locations.cacheDir.path) {
            override fun listFiles(): Array<File> = throw SecurityException("read denied")
        }
        val brokenLocations = StorageLocations(locations.filesDir, brokenCache, temporary.newFolder(),
            locations.databaseFiles, locations.downloadMetadataFiles)
        assertThrows(SecurityException::class.java) {
            runBlocking { StorageUsageScanner(brokenLocations, TestSource()).scan() }
        }
    }

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
    fun cleanerSelectsOnlyServerBrowseCacheWithoutClaimingPhysicalBytes() = runBlocking {
        val locations = locations()
        val retained = write(locations.sharedMediaDir, "shared", 7)
        val files = TestCacheFiles()
        val platforms = TestPlatformCaches()

        val result = StorageCacheCleaner(locations, files, platforms).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, serverBrowseCache = true)
        )

        assertEquals(ExtraCacheClearResult(true, 0, 80, 0), result)
        assertEquals(listOf("subsonic"), platforms.cleared)
        assertTrue(files.cleared.isEmpty())
        assertTrue(retained.exists())
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
    fun cleanerCountsFilesRemovedBeforeAReportedFailure() = runBlocking {
        val locations = locations()
        val removed = write(locations.sharedMediaDir, "removed", 7)
        val retained = write(locations.sharedMediaDir, "retained", 11)
        val files = object : StorageCacheFileAccess {
            override fun stats(file: File) = statsOf(file)
            override suspend fun clear(file: File, kind: StorageCacheKind): Boolean {
                check(removed.delete())
                return false
            }
        }

        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, sharedMedia = true)
        )

        assertEquals(ExtraCacheClearResult(false, 7, 0, 1), result)
        assertTrue(retained.exists())
    }

    @Test
    fun scannerDoesNotFollowSymbolicLinksOutsideTheScannedDirectory() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        write(root, "local", 7)
        write(outside, "private", 11)
        Files.createSymbolicLink(File(root, "linked-directory").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "linked-file").toPath(), File(outside, "private").toPath())

        assertEquals(FileStats(7, 1), statsOf(root))
    }

    @Test
    fun cleanerMeasuresRemainingFilesEvenWhenClearClaimsSuccess() = runBlocking {
        val locations = locations()
        val removed = write(locations.sharedMediaDir, "removed", 7)
        write(locations.sharedMediaDir, "retained", 11)
        val files = object : StorageCacheFileAccess {
            override fun stats(file: File) = statsOf(file)
            override suspend fun clear(file: File, kind: StorageCacheKind): Boolean = removed.delete()
        }

        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, sharedMedia = true)
        )

        assertEquals(ExtraCacheClearResult(false, 7, 0, 1), result)
    }

    @Test
    fun otherCacheScanAndCleanupUseTheSameAllowlist() = runBlocking {
        val locations = locations()
        val shareCard = write(File(locations.cacheDir, "lyric_share_cards"), "card.png", 7)
        val protected = listOf(
            "sync-v3", "backup-playback", "sync-deletions-volatile", "local_curation_covers",
            "youtube/ejs_webview", "unknown"
        ).map { write(File(locations.cacheDir, it), "working", 3) } +
            write(locations.cacheDir, "unknown.tmp", 5)
        val download = write(File(locations.cacheDir, "download_staging"), "resume", 11)
        val audio = write(locations.mediaCacheDir, "audio", 13)
        val image = write(locations.imageCacheDir, "image", 17)
        val shared = write(locations.sharedMediaDir, "shared", 19)

        val before = StorageUsageScanner(locations, TestSource()).scan()
        assertEquals(FileStats(7, 1), before.items.getValue(StorageUsageItemKind.OtherCache).stats)
        assertEquals(FileStats(23, 7), before.items.getValue(StorageUsageItemKind.ProtectedCache).stats)

        val result = StorageCacheCleaner(locations, TestCacheFiles(), TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, otherCache = true)
        )

        assertEquals(ExtraCacheClearResult(true, 7, 0, 1), result)
        assertFalse(shareCard.exists())
        (protected + listOf(download, audio, image, shared)).forEach { assertTrue(it.exists()) }
        val after = StorageUsageScanner(locations, TestSource()).scan()
        assertEquals(FileStats.Empty, after.items.getValue(StorageUsageItemKind.OtherCache).stats)
        assertEquals(FileStats(23, 7), after.items.getValue(StorageUsageItemKind.ProtectedCache).stats)
    }

    @Test
    fun otherCacheAllowlistAcceptsAnOrdinaryFileWithoutDeletingSiblings() = runBlocking {
        val locations = locations()
        write(locations.cacheDir, "lyric_share_cards", 7)
        val sibling = write(File(locations.cacheDir, "lyric_share_cards-backup"), "keep", 11)

        val result = StorageCacheCleaner(locations, TestCacheFiles(), TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, otherCache = true)
        )

        assertEquals(ExtraCacheClearResult(true, 7, 0, 1), result)
        assertEquals(11L, sibling.length())
        assertEquals(FileStats(11, 1), StorageUsageScanner(locations, TestSource()).scan()
            .items.getValue(StorageUsageItemKind.ProtectedCache).stats)
    }

    @Test
    fun otherCacheSymbolicLinkRootIsNeitherCountedNorCleared() = runBlocking {
        val locations = locations()
        val outside = temporary.newFolder()
        val privateFile = write(outside, "private", 11)
        val linked = File(locations.cacheDir, "lyric_share_cards")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val files = TestCacheFiles()

        val before = StorageUsageScanner(locations, TestSource()).scan()
        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, otherCache = true)
        )

        assertEquals(FileStats.Empty, before.items.getValue(StorageUsageItemKind.OtherCache).stats)
        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), result)
        assertTrue(files.cleared.isEmpty())
        assertEquals(11L, privateFile.length())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
    }

    @Test
    fun cleanerCountsPartialDeletionBeforeAnException() = runBlocking {
        val locations = locations()
        val removed = write(locations.sharedMediaDir, "removed", 7)
        write(locations.sharedMediaDir, "retained", 11)
        val files = object : StorageCacheFileAccess {
            override fun stats(file: File) = statsOf(file)
            override suspend fun clear(file: File, kind: StorageCacheKind): Boolean {
                check(removed.delete())
                error("failed after deleting one file")
            }
        }

        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, sharedMedia = true)
        )

        assertEquals(ExtraCacheClearResult(false, 7, 0, 1), result)
    }

    @Test
    fun cleanerPropagatesFileCleanupCancellation() {
        val locations = locations()
        write(locations.sharedMediaDir, "cached", 7)
        val files = object : StorageCacheFileAccess {
            override fun stats(file: File) = statsOf(file)
            override suspend fun clear(file: File, kind: StorageCacheKind): Boolean =
                throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
                    StorageCacheClearOptions(audioCache = false, imageCache = false, sharedMedia = true)
                )
            }
        }
    }

    @Test
    fun cleanerDoesNotClaimBytesWhenPostClearMeasurementFails() = runBlocking {
        val locations = locations()
        val cached = write(locations.sharedMediaDir, "cached", 7)
        var measurementCount = 0
        val files = object : StorageCacheFileAccess {
            override fun stats(file: File): FileStats {
                measurementCount += 1
                if (measurementCount == 2) error("measurement unavailable")
                return statsOf(file)
            }
            override suspend fun clear(file: File, kind: StorageCacheKind): Boolean = cached.delete()
        }

        val result = StorageCacheCleaner(locations, files, TestPlatformCaches()).clear(
            StorageCacheClearOptions(audioCache = false, imageCache = false, sharedMedia = true)
        )

        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), result)
    }

    @Test
    fun delayedStagingClearPreservesNewDownloadsAndRecoveryFiles() = runBlocking {
        val locations = locations()
        val recoveryFiles = locations.downloadStagingDirs.flatMap { directory ->
            listOf(
                write(File(directory, "waiting-operation"), "npdl_waiting_audio.m4a.download", 7),
                write(File(directory, "waiting-operation"), "npdl_waiting_audio.m4a.download.resume.json", 11),
                write(File(directory, "paused-operation"), "npdl_paused_audio.m4a.download.hls.json", 13)
            )
        }
        val shared = write(locations.sharedMediaDir, "shared", 17)
        val options = StorageCacheClearOptions(downloadStaging = true, sharedMedia = true)
        val clearStarted = CompletableDeferred<Unit>()
        val continueClear = CompletableDeferred<Unit>()
        val clearing = async {
            clearStarted.complete(Unit)
            continueClear.await()
            StorageCacheCleaner(locations, TestCacheFiles(), TestPlatformCaches()).clear(options)
        }
        clearStarted.await()
        val newDownloads = locations.downloadStagingDirs.map { directory ->
            write(File(directory, "new-operation"), "npdl_new_audio.m4a.download", 19)
        }
        continueClear.complete(Unit)
        val result = clearing.await()

        assertEquals(ExtraCacheClearResult(false, 17, 0, 1), result)
        assertFalse(shared.exists())
        recoveryFiles.forEach { assertTrue(it.exists()) }
        newDownloads.forEach { assertEquals(19L, it.length()) }
    }

    @Test
    fun cleanerRejectsStagingSelectionWithoutClaimingFreedSpace() = runBlocking {
        val result = StorageCacheCleaner(locations(), TestCacheFiles(), TestPlatformCaches())
            .clear(StorageCacheClearOptions(downloadStaging = true))

        assertEquals(ExtraCacheClearResult(false, 0, 0, 0), result)
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
        override suspend fun clear(file: File, kind: StorageCacheKind): Boolean {
            cleared += kind
            if (kind == failKind) return false
            return file.deleteRecursively().also { if (it) file.mkdirs() }
        }
    }
}
