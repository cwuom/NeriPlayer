package moe.ouom.neriplayer.data.storage

import java.io.File
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUsageSummaryTest {

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
            roomStats = DownloadIndexStorageStats(
                databaseRecordCount = 118,
                allocatedPageBytes = 8_192L
            )
        )

        assertEquals(8_224L, usage.sizeBytes)
        assertEquals(1, usage.fileCount)
        assertEquals(118, usage.databaseRecordCount)
    }

    @Test
    fun databaseAttributionDoesNotDoubleCountDownloadIndexPages() {
        val attribution = normalizeDatabaseStorageAttribution(
            platformCacheStats = mapOf(
                "netease" to PlatformPlaylistCacheStorageStats(
                    cacheRecordCount = 2,
                    allocatedPageBytes = 700L
                )
            ),
            downloadIndexStorageStats = DownloadIndexStorageStats(
                databaseRecordCount = 118,
                allocatedPageBytes = 800L
            ),
            databaseBytes = 1_000L
        )
        val attributedBytes = attribution.platformCacheStats.values.sumOf {
            it.allocatedPageBytes
        } + attribution.downloadIndexStorageStats.allocatedPageBytes

        assertEquals(1_000L, attributedBytes)
        assertEquals(
            0L,
            databaseUsageStats(
                databaseStats = FileStats(sizeBytes = 1_000L, fileCount = 1),
                attributedDatabaseBytes = attributedBytes
            ).sizeBytes
        )
    }

    private fun storedEntry(
        name: String,
        sizeBytes: Long
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "content://downloads/$name",
            mediaUri = "content://downloads/$name",
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = 0L
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
