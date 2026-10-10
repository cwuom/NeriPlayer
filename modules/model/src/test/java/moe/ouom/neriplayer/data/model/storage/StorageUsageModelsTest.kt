package moe.ouom.neriplayer.data.model.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUsageModelsTest {
    private val none = StorageCacheClearOptions(audioCache = false, imageCache = false)

    @Test
    fun `nothing selected needs no clearing`() {
        assertFalse(none.hasSelection)
        assertFalse(none.needsPlayerCacheClear)
        assertFalse(none.needsExtraCacheClear)
        assertFalse(none.hasPlatformCacheSelection)
    }

    @Test
    fun `player caches are cleared by the player only`() {
        listOf(none.copy(audioCache = true), none.copy(imageCache = true)).forEach { options ->
            assertTrue(options.toString(), options.hasSelection)
            assertTrue(options.toString(), options.needsPlayerCacheClear)
            assertFalse(options.toString(), options.needsExtraCacheClear)
        }
    }

    @Test
    fun `file and log selections need the extra cache clear`() {
        listOf(
            none.copy(downloadStaging = true),
            none.copy(sharedMedia = true),
            none.copy(lyricsCache = true),
            none.copy(logFiles = true),
            none.copy(crashLogs = true)
        ).forEach { options ->
            assertTrue(options.toString(), options.hasSelection)
            assertFalse(options.toString(), options.needsPlayerCacheClear)
            assertTrue(options.toString(), options.needsExtraCacheClear)
            assertFalse(options.toString(), options.hasPlatformCacheSelection)
        }
    }

    @Test
    fun `platform playlist caches count as extra cache selections`() {
        listOf(
            none.copy(neteasePlaylistCache = true),
            none.copy(biliFavoriteCache = true),
            none.copy(biliArchiveCache = true),
            none.copy(youtubePlaylistCache = true)
        ).forEach { options ->
            assertTrue(options.toString(), options.hasPlatformCacheSelection)
            assertTrue(options.toString(), options.hasSelection)
            assertTrue(options.toString(), options.needsExtraCacheClear)
            assertFalse(options.toString(), options.needsPlayerCacheClear)
        }
    }

    @Test
    fun `usage totals add up per section and per cache kind`() {
        val audio = item(sizeBytes = 300L, fileCount = 3, kind = StorageUsageItemKind.AudioCache, cacheKind = StorageCacheKind.Audio)
        val logs = item(sizeBytes = 20L, fileCount = 2, kind = StorageUsageItemKind.LogFiles, cacheKind = StorageCacheKind.LogFiles)
        val music = item(sizeBytes = 5_000L, fileCount = 1, kind = StorageUsageItemKind.DownloadedMusic, cacheKind = null)
        val summary = StorageUsageSummary(
            listOf(
                StorageUsageSection("Cache", listOf(audio, logs)),
                StorageUsageSection("Downloads", listOf(music)),
                StorageUsageSection("Empty", emptyList())
            )
        )

        assertEquals(listOf(320L, 5_000L, 0L), summary.sections.map { it.sizeBytes })
        assertEquals(listOf(5, 1, 0), summary.sections.map { it.fileCount })
        assertEquals(5_320L, summary.totalSizeBytes)
        assertEquals(6, summary.totalFileCount)
        assertEquals(320L, summary.cleanableSizeBytes)
        assertEquals(20L, summary.sizeOf(StorageCacheKind.LogFiles))
        assertEquals(0L, StorageUsageSummary.Empty.totalSizeBytes)
    }

    private fun item(
        sizeBytes: Long,
        fileCount: Int,
        kind: StorageUsageItemKind,
        cacheKind: StorageCacheKind?
    ) = StorageUsageItem(
        title = kind.name,
        description = "",
        path = null,
        sizeBytes = sizeBytes,
        fileCount = fileCount,
        kind = kind,
        cacheKind = cacheKind
    )
}
