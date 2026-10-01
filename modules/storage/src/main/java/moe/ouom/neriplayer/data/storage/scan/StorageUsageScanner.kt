package moe.ouom.neriplayer.data.storage.scan

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import moe.ouom.neriplayer.data.storage.accounting.databaseUsageStats
import moe.ouom.neriplayer.data.storage.accounting.downloadIndexUsageStats
import moe.ouom.neriplayer.data.storage.accounting.normalizeDatabaseStorageAttribution
import moe.ouom.neriplayer.data.storage.accounting.DatabaseStorageAttribution
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageMeasurement
import moe.ouom.neriplayer.data.model.storage.StorageUsageSnapshot
import moe.ouom.neriplayer.data.storage.policy.storageScanOrDefault
import moe.ouom.neriplayer.data.storage.source.StorageLocations
import moe.ouom.neriplayer.data.storage.source.StorageUsageSource
import moe.ouom.neriplayer.data.storage.source.storageCacheItemKinds
import moe.ouom.neriplayer.data.storage.source.storagePlatformCaches

class StorageUsageScanner(
    private val locations: StorageLocations,
    private val source: StorageUsageSource
) {
    suspend fun scan(): StorageUsageSnapshot = coroutineScope {
        val library = async { storageScanOrDefault(ManagedDownloadLibraryUsage.Empty) { source.downloadLibraryUsage() } }
        val platform = async { storageScanOrDefault(emptyMap()) { source.platformCacheStats() } }
        val index = async { storageScanOrDefault(StorageDownloadIndexStats.Empty) { source.downloadIndexStats() } }
        val database = async { statsOfFiles(locations.databaseFiles) }
        assembleSnapshot(library.await(), platform.await(), index.await(), database.await())
    }

    private fun assembleSnapshot(
        library: ManagedDownloadLibraryUsage,
        platform: Map<String, StoragePlatformCacheStats>,
        index: StorageDownloadIndexStats,
        database: FileStats
    ): StorageUsageSnapshot {
        val attributed = normalizeDatabaseStorageAttribution(platform, index, database.sizeBytes)
        val items = cacheMeasurements(attributed.platformCacheStats).toMutableMap()
        items.putAll(libraryMeasurements(library, attributed.downloadIndexStorageStats))
        items.putAll(appDataMeasurements(library, attributed, database))
        items[StorageUsageItemKind.OtherCache] = StorageUsageMeasurement(
            statsOf(locations.cacheDir, locations.cacheExclusions()), locations.cacheDir.absolutePath
        )
        return StorageUsageSnapshot(items.toMap())
    }

    private fun cacheMeasurements(platform: Map<String, StoragePlatformCacheStats>) =
        locations.cacheFiles.entries.associate { (kind, files) ->
            storageCacheItemKinds.getValue(kind) to cacheMeasurement(files, platform[storagePlatformCaches[kind]])
        }

    private fun cacheMeasurement(files: List<File>, room: StoragePlatformCacheStats?): StorageUsageMeasurement {
        val stats = statsOfFiles(files)
        return StorageUsageMeasurement(
            stats = stats.copy(sizeBytes = stats.sizeBytes + (room?.allocatedPageBytes ?: 0L)),
            path = files.joinToString("\n") { it.absolutePath },
            databaseRecordCount = room?.cacheRecordCount
        )
    }

    private fun libraryMeasurements(library: ManagedDownloadLibraryUsage, index: StorageDownloadIndexStats):
        Map<StorageUsageItemKind, StorageUsageMeasurement> {
        val indexStats = downloadIndexUsageStats(library.metadataFiles + statsOfFiles(locations.downloadMetadataFiles), index)
        return mapOf(
            StorageUsageItemKind.DownloadedMusic to StorageUsageMeasurement(library.audioFiles),
            StorageUsageItemKind.DownloadedLyrics to StorageUsageMeasurement(library.lyricFiles),
            StorageUsageItemKind.DownloadedCovers to StorageUsageMeasurement(library.coverFiles),
            StorageUsageItemKind.DownloadIndex to StorageUsageMeasurement(
                FileStats(indexStats.sizeBytes, indexStats.fileCount), databaseRecordCount = indexStats.databaseRecordCount
            )
        )
    }

    private fun appDataMeasurements(library: ManagedDownloadLibraryUsage, attributed: DatabaseStorageAttribution, database: FileStats):
        Map<StorageUsageItemKind, StorageUsageMeasurement> {
        val attributedBytes = attributed.platformCacheStats.values.sumOf { it.allocatedPageBytes } +
            attributed.downloadIndexStorageStats.allocatedPageBytes
        return mapOf(
            StorageUsageItemKind.LocalCovers to fileMeasurement(locations.localCoverDir),
            StorageUsageItemKind.CustomBackground to fileMeasurement(locations.backgroundDir),
            StorageUsageItemKind.LegacyMigrationFiles to StorageUsageMeasurement(
                statsOfFiles(locations.playlistDataFiles), locations.playlistDataFiles.joinToString("\n") { it.absolutePath }
            ),
            StorageUsageItemKind.Database to StorageUsageMeasurement(databaseUsageStats(database, attributedBytes)),
            StorageUsageItemKind.AppData to StorageUsageMeasurement(
                statsOf(locations.filesDir, locations.appDataExclusions(library.localFiles)), locations.filesDir.absolutePath
            )
        )
    }

    private fun fileMeasurement(file: File) = StorageUsageMeasurement(statsOf(file), file.absolutePath)
}
