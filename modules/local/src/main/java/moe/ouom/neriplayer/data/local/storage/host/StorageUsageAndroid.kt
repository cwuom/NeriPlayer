package moe.ouom.neriplayer.data.local.storage.host

import moe.ouom.neriplayer.data.local.storage.mapping.toManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.local.storage.mapping.toStorageUsageStats
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess

import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.local.storage.scan.statsOf
import moe.ouom.neriplayer.data.local.storage.source.StorageCacheFileAccess
import moe.ouom.neriplayer.data.local.storage.source.StorageLocations
import moe.ouom.neriplayer.data.local.storage.source.StoragePlatformCacheAccess
import moe.ouom.neriplayer.data.local.storage.source.StorageUsageSource
import moe.ouom.neriplayer.data.local.storage.source.storagePlatformCaches

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore

internal fun storageLocations(context: Context): StorageLocations {
    val databaseFile = context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME)
    return StorageLocations(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        diagnosticsDir = context.getExternalFilesDir(null) ?: context.filesDir,
        databaseFiles = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm")),
        downloadMetadataFiles = listOf(
            "managed_download_snapshot_v1.json", LocalMediaHostAccess.downloads.snapshotCacheFileName,
            "pending_download_queue_v1.json", "cancelled_download_keys_v1.json",
            "downloaded_song_catalog_v3.json", "downloaded_song_catalog_v4.json"
        ).map { File(context.filesDir, it) }
    )
}

internal class AndroidStorageUsageSource(private val context: Context) : StorageUsageSource {
    override suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage =
        LocalMediaHostAccess.downloads.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            .toManagedDownloadLibraryUsage()

    override suspend fun platformCacheStats() =
        PlatformPlaylistCacheRoomStore(NeriUserDataDatabase.getInstance(context))
            .storageStats(storagePlatformCaches.values.toList())
            .mapValues { (_, stats) -> stats.toStorageUsageStats() }

    override suspend fun downloadIndexStats() =
        DownloadIndexRoomStore(NeriUserDataDatabase.getInstance(context)).storageStats()
            .toStorageUsageStats()
}

internal class AndroidStoragePlatformCaches(private val context: Context) : StoragePlatformCacheAccess {
    private val store by lazy { PlatformPlaylistCacheRoomStore(NeriUserDataDatabase.getInstance(context)) }

    override suspend fun allocatedBytes(platforms: List<String>): Long =
        store.storageStats(platforms).values.sumOf { it.allocatedPageBytes }

    override suspend fun clear(platforms: List<String>) = store.clearSelected(platforms)
}

class AndroidStorageCacheFiles(private val context: Context) : StorageCacheFileAccess {
    override fun stats(file: File): FileStats = statsOf(file)

    override fun clear(file: File, kind: StorageCacheKind): Boolean = when (kind) {
        StorageCacheKind.LogFiles -> NPLogger.clearLogFiles(context)
        StorageCacheKind.CrashLogs -> LocalMediaHostAccess.crashLogs.clear(context)
        else -> clearDirectory(file)
    }

    private fun clearDirectory(file: File): Boolean = runCatching {
        file.deleteRecursively().also { deleted -> if (deleted) file.mkdirs() }
    }.getOrDefault(false)
}
