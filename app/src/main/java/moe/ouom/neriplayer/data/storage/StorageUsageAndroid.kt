package moe.ouom.neriplayer.data.storage

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore

internal fun storageLocations(context: Context): StorageLocations {
    val databaseFile = context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME)
    return StorageLocations(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        diagnosticsDir = context.getExternalFilesDir(null) ?: context.filesDir,
        databaseFiles = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
    )
}

internal class AndroidStorageUsageSource(private val context: Context) : StorageUsageSource {
    override suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage =
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            .toManagedDownloadLibraryUsage()

    override suspend fun platformCacheStats() =
        PlatformPlaylistCacheRoomStore(NeriUserDataDatabase.getInstance(context))
            .storageStats(storagePlatformCaches.values.toList())

    override suspend fun downloadIndexStats() =
        DownloadIndexRoomStore(NeriUserDataDatabase.getInstance(context)).storageStats()
}

internal class AndroidStoragePlatformCaches(private val context: Context) : StoragePlatformCacheAccess {
    private val store by lazy { PlatformPlaylistCacheRoomStore(NeriUserDataDatabase.getInstance(context)) }

    override suspend fun allocatedBytes(platforms: List<String>): Long =
        store.storageStats(platforms).values.sumOf { it.allocatedPageBytes }

    override suspend fun clear(platforms: List<String>) = store.clearSelected(platforms)
}

internal class AndroidStorageCacheFiles(private val context: Context) : StorageCacheFileAccess {
    override fun stats(file: File): FileStats = statsOf(file)

    override fun clear(file: File, kind: StorageCacheKind): Boolean = when (kind) {
        StorageCacheKind.LogFiles -> NPLogger.clearLogFiles(context)
        StorageCacheKind.CrashLogs -> ExceptionHandler.clearCrashLogs(context)
        else -> clearDirectory(file)
    }

    private fun clearDirectory(file: File): Boolean = runCatching {
        file.deleteRecursively().also { deleted -> if (deleted) file.mkdirs() }
    }.getOrDefault(false)
}
