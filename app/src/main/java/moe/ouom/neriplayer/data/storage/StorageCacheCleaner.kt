package moe.ouom.neriplayer.data.storage

import java.io.File

internal interface StorageCacheFileAccess {
    fun stats(file: File): FileStats
    fun clear(file: File, kind: StorageCacheKind): Boolean
}

internal interface StoragePlatformCacheAccess {
    suspend fun allocatedBytes(platforms: List<String>): Long
    suspend fun clear(platforms: List<String>)
}

internal class StorageCacheCleaner(
    private val locations: StorageLocations,
    private val files: StorageCacheFileAccess,
    private val platforms: StoragePlatformCacheAccess
) {
    suspend fun clear(options: StorageCacheClearOptions): ExtraCacheClearResult {
        val selected = selectedExtraCaches(options)
        val fileResult = selected.fold(emptyResult()) { total, kind -> total + clearFiles(kind) }
        val selectedPlatforms = selected.mapNotNull(storagePlatformCaches::get)
        return fileResult + clearPlatforms(selectedPlatforms)
    }

    private suspend fun clearFiles(kind: StorageCacheKind): ExtraCacheClearResult =
        locations.cacheFiles.getValue(kind).fold(emptyResult()) { total, file -> total + clearFile(file, kind) }

    private suspend fun clearFile(file: File, kind: StorageCacheKind): ExtraCacheClearResult {
        val before = files.stats(file)
        if (before == FileStats.Empty) return emptyResult()
        val deleted = storageScanOrDefault(false) { files.clear(file, kind) }
        return if (deleted) ExtraCacheClearResult(true, before.sizeBytes, 0, before.fileCount)
        else emptyResult(success = false)
    }

    private suspend fun clearPlatforms(selected: List<String>): ExtraCacheClearResult {
        if (selected.isEmpty()) return emptyResult()
        val before = storageScanOrDefault(0L) { platforms.allocatedBytes(selected) }
        return storageScanOrDefault(emptyResult(success = false)) {
            platforms.clear(selected)
            ExtraCacheClearResult(true, 0, before, 0)
        }
    }

    private fun emptyResult(success: Boolean = true) = ExtraCacheClearResult(success, 0, 0, 0)

    private operator fun ExtraCacheClearResult.plus(other: ExtraCacheClearResult) = ExtraCacheClearResult(
        success = success && other.success,
        freedBytes = freedBytes + other.freedBytes,
        roomBytesMadeReusable = roomBytesMadeReusable + other.roomBytesMadeReusable,
        deletedFiles = deletedFiles + other.deletedFiles
    )
}

private fun selectedExtraCaches(options: StorageCacheClearOptions): List<StorageCacheKind> = listOf(
    StorageCacheKind.DownloadStaging to options.downloadStaging,
    StorageCacheKind.SharedMedia to options.sharedMedia,
    StorageCacheKind.Lyrics to options.lyricsCache,
    StorageCacheKind.LogFiles to options.logFiles,
    StorageCacheKind.CrashLogs to options.crashLogs,
    StorageCacheKind.NeteasePlaylist to options.neteasePlaylistCache,
    StorageCacheKind.BiliFavorite to options.biliFavoriteCache,
    StorageCacheKind.BiliArchive to options.biliArchiveCache,
    StorageCacheKind.YouTubePlaylist to options.youtubePlaylistCache
).filter { it.second }.map { it.first }
