package moe.ouom.neriplayer.data.storage

import java.io.File
import java.nio.file.Files
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
