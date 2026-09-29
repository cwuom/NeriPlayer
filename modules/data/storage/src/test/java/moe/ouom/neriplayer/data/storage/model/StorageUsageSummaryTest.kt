package moe.ouom.neriplayer.data.storage.model

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.storage.accounting.databaseUsageStats
import moe.ouom.neriplayer.data.storage.accounting.downloadIndexUsageStats
import moe.ouom.neriplayer.data.storage.accounting.managedDownloadLibraryUsage
import moe.ouom.neriplayer.data.storage.accounting.normalizeDatabaseStorageAttribution
import moe.ouom.neriplayer.data.storage.policy.storageScanOrDefault
import moe.ouom.neriplayer.data.storage.scan.statsOf
import moe.ouom.neriplayer.data.storage.source.knownAppDataRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUsageSummaryTest {

    @Test
    fun everyCacheSelectionRoutesToItsOwner() {
        val none = StorageCacheClearOptions(audioCache = false, imageCache = false)
        assertFalse(none.hasSelection)
        assertFalse(none.needsPlayerCacheClear)
        assertFalse(none.needsExtraCacheClear)
        assertFalse(none.hasPlatformCacheSelection)
        val selections = listOf(
            Triple(none.copy(audioCache = true), true, false),
            Triple(none.copy(imageCache = true), true, false),
            Triple(none.copy(downloadStaging = true), false, false),
            Triple(none.copy(sharedMedia = true), false, false),
            Triple(none.copy(lyricsCache = true), false, false),
            Triple(none.copy(neteasePlaylistCache = true), false, true),
            Triple(none.copy(biliFavoriteCache = true), false, true),
            Triple(none.copy(biliArchiveCache = true), false, true),
            Triple(none.copy(youtubePlaylistCache = true), false, true),
            Triple(none.copy(logFiles = true), false, false),
            Triple(none.copy(crashLogs = true), false, false)
        )
        selections.forEach { (options, playerOwned, platformOwned) ->
            assertTrue(options.hasSelection)
            assertEquals(playerOwned, options.needsPlayerCacheClear)
            assertEquals(!playerOwned, options.needsExtraCacheClear)
            assertEquals(platformOwned, options.hasPlatformCacheSelection)
        }
    }

    @Test
    fun selectedPlatformCacheRequiresExtraCleanup() {
        val options = StorageCacheClearOptions(
            audioCache = false,
            imageCache = false,
            biliArchiveCache = true
        )

        assertTrue(options.hasSelection)
        assertTrue(options.hasPlatformCacheSelection)
        assertTrue(options.needsExtraCacheClear)
        assertFalse(options.needsPlayerCacheClear)
    }

    @Test
    fun diagnosticFilesAreCleanableWithoutClearingPlayerCache() {
        val options = StorageCacheClearOptions(
            audioCache = false,
            imageCache = false,
            logFiles = true,
            crashLogs = true
        )

        assertTrue(options.hasSelection)
        assertTrue(options.needsExtraCacheClear)
        assertFalse(options.needsPlayerCacheClear)
    }

    @Test
    fun cleanableSizeCountsAllGranularCacheKindsOnly() {
        val summary = StorageUsageSummary(
            sections = listOf(
                StorageUsageSection(
                    title = "cache",
                    items = listOf(
                        usageItem(StorageCacheKind.Audio, 100L),
                        usageItem(StorageCacheKind.NeteasePlaylist, 50L),
                        usageItem(cacheKind = null, sizeBytes = 1_000L)
                    )
                )
            )
        )

        assertEquals(150L, summary.cleanableSizeBytes)
        assertEquals(1_150L, summary.totalSizeBytes)
    }

    @Test
    fun appDataStatsExcludeDiagnosticDirectories() {
        val root = Files.createTempDirectory("neriplayer-storage-usage").toFile()
        try {
            File(root, "settings.json").writeBytes(ByteArray(3))
            val logDir = File(root, "logs").apply { mkdirs() }
            File(logDir, "current.log").writeBytes(ByteArray(5))
            val crashDir = File(root, "crashes").apply { mkdirs() }
            File(crashDir, "crash.log").writeBytes(ByteArray(7))

            val excludedRoots = knownAppDataRoots(
                platformCacheDirs = emptyList(),
                downloadStagingDirs = emptyList(),
                localCoverDir = File(root, "covers"),
                backgroundDir = File(root, "background"),
                downloadMetadataFiles = emptyList(),
                playlistDataFiles = emptyList(),
                logDir = logDir,
                crashDir = crashDir
            )
            val stats = statsOf(root, excludedRoots = excludedRoots)

            assertEquals(3L, stats.sizeBytes)
            assertEquals(1, stats.fileCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadLibraryUsageCountsLyricSidecarsFromDocumentTreeSnapshot() {
        val usage = managedDownloadLibraryUsage(
            audioEntries = listOf(storedEntry("song.m4a", 3_000L)),
            lyricEntries = listOf(
                storedEntry(".nomedia", 0L),
                storedEntry("song.lrc", 120L),
                storedEntry("song_trans.lrc", 80L)
            ),
            coverEntries = listOf(storedEntry("song.jpg", 300L)),
            metadataEntries = listOf(storedEntry("song.m4a.meta.json", 40L))
        )

        assertEquals(3_000L, usage.audioFiles.sizeBytes)
        assertEquals(1, usage.audioFiles.fileCount)
        assertEquals(200L, usage.lyricFiles.sizeBytes)
        assertEquals(2, usage.lyricFiles.fileCount)
        assertEquals(300L, usage.coverFiles.sizeBytes)
        assertEquals(1, usage.coverFiles.fileCount)
        assertEquals(40L, usage.metadataFiles.sizeBytes)
        assertEquals(1, usage.metadataFiles.fileCount)
        assertTrue(usage.localFiles.isEmpty())
    }

    @Test
    fun downloadIndexUsageKeepsRoomRecordsSeparateFromLegacyFiles() {
        val usage = downloadIndexUsageStats(
            fileStats = FileStats(sizeBytes = 32L, fileCount = 1),
            roomStats = StorageDownloadIndexStats(
                databaseRecordCount = 118,
                allocatedPageBytes = 8_192L
            )
        )

        assertEquals(8_224L, usage.sizeBytes)
        assertEquals(1, usage.fileCount)
        assertEquals(118, usage.databaseRecordCount)
    }

    @Test
    fun downloadLibraryDeduplicatesFallbackIdentitiesAndExcludesMarkers() {
        val referenced = storedEntry("a.m4a", 5).copy(localFilePath = "/songs/a.m4a")
        val mediaOnly = storedEntry("b.m4a", 7).copy(reference = "", localFilePath = "/songs/b.m4a")
        val nameOnly = storedEntry("fallback.m4a", -2).copy(reference = "", mediaUri = "")
        val usage = managedDownloadLibraryUsage(
            audioEntries = listOf(
                referenced,
                referenced.copy(name = "duplicate", mediaUri = "different", sizeBytes = 900),
                mediaOnly,
                mediaOnly.copy(reference = " ", name = "duplicate", sizeBytes = 900),
                nameOnly,
                nameOnly.copy(sizeBytes = 900),
                storedEntry("folder", 900).copy(isDirectory = true),
                storedEntry(".nomedia", 900)
            ),
            lyricEntries = emptyList(),
            coverEntries = emptyList(),
            metadataEntries = emptyList()
        )

        assertEquals(FileStats(12, 3), usage.audioFiles)
        assertEquals(listOf(File("/songs/a.m4a"), File("/songs/b.m4a")), usage.localFiles)
    }

    @Test
    fun databaseAttributionDoesNotDoubleCountDownloadIndexPages() {
        val attribution = normalizeDatabaseStorageAttribution(
            platformCacheStats = mapOf(
                "netease" to StoragePlatformCacheStats(
                    cacheRecordCount = 2,
                    allocatedPageBytes = 700L
                )
            ),
            downloadIndexStorageStats = StorageDownloadIndexStats(
                databaseRecordCount = 118,
                allocatedPageBytes = 800L
            ),
            databaseBytes = 1_000L
        )
        val attributedBytes = attribution.platformCacheStats.values.sumOf {
            it.allocatedPageBytes
        } + attribution.downloadIndexStorageStats.allocatedPageBytes

        assertEquals(1_000L, attributedBytes)
        assertEquals(466L, attribution.platformCacheStats.getValue("netease").allocatedPageBytes)
        assertEquals(534L, attribution.downloadIndexStorageStats.allocatedPageBytes)
        assertEquals(
            0L,
            databaseUsageStats(
                databaseStats = FileStats(sizeBytes = 1_000L, fileCount = 1),
                attributedDatabaseBytes = attributedBytes
            ).sizeBytes
        )
    }

    @Test
    fun databaseAttributionPreservesUnallocatedAndWithinBudgetStats() {
        val index = StorageDownloadIndexStats(4, 40)
        val platform = mapOf("netease" to StoragePlatformCacheStats(2, 30))
        val withinBudget = normalizeDatabaseStorageAttribution(platform, index, 100)
        val unknownPhysicalSize = normalizeDatabaseStorageAttribution(platform, index, 0)
        val empty = normalizeDatabaseStorageAttribution(emptyMap(), StorageDownloadIndexStats.Empty, 100)

        assertEquals(platform, withinBudget.platformCacheStats)
        assertEquals(index, withinBudget.downloadIndexStorageStats)
        assertEquals(withinBudget, unknownPhysicalSize)
        assertEquals(emptyMap<String, StoragePlatformCacheStats>(), empty.platformCacheStats)
        assertEquals(0L, empty.downloadIndexStorageStats.allocatedPageBytes)
        assertEquals(FileStats(0, 1), databaseUsageStats(FileStats(100, 1), 200))
        assertEquals(FileStats(100, 1), databaseUsageStats(FileStats(100, 1), -10))
    }

    @Test
    fun storageScanFallsBackForNonCancellationFailure() = runBlocking {
        assertEquals(
            "fallback",
            storageScanOrDefault("fallback") {
                throw IllegalStateException("storage read failed")
            }
        )
    }

    @Test
    fun storageScanRethrowsCancellation() {
        val cancellation = CancellationException("storage scan cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                storageScanOrDefault("fallback") {
                    throw cancellation
                }
            }
        }

        assertSame(cancellation, thrown)
    }

    private fun storedEntry(
        name: String,
        sizeBytes: Long
    ): StorageLibraryEntry {
        return StorageLibraryEntry(
            name = name,
            reference = "content://downloads/$name",
            mediaUri = "content://downloads/$name",
            localFilePath = null,
            sizeBytes = sizeBytes
        )
    }

    private fun usageItem(
        cacheKind: StorageCacheKind?,
        sizeBytes: Long
    ): StorageUsageItem {
        return StorageUsageItem(
            title = "item",
            description = "description",
            path = null,
            sizeBytes = sizeBytes,
            fileCount = 1,
            kind = StorageUsageItemKind.AudioCache,
            cacheKind = cacheKind
        )
    }
}
