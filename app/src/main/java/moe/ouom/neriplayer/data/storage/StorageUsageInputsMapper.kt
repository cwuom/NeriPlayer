package moe.ouom.neriplayer.data.storage

import moe.ouom.neriplayer.data.storage.accounting.managedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StorageLibraryEntry
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats

internal fun ManagedDownloadStorage.DownloadLibrarySnapshot.toManagedDownloadLibraryUsage():
    ManagedDownloadLibraryUsage {
    return managedDownloadLibraryUsage(
        audioEntries = audioEntries.map { it.toStorageLibraryEntry() },
        lyricEntries = lyricEntriesByName.values.map { it.toStorageLibraryEntry() },
        coverEntries = coverEntriesByName.values.map { it.toStorageLibraryEntry() },
        metadataEntries = metadataEntriesByAudioName.values.map { it.toStorageLibraryEntry() }
    )
}

internal fun ManagedDownloadStorage.StoredEntry.toStorageLibraryEntry(): StorageLibraryEntry =
    StorageLibraryEntry(
        name = name,
        reference = reference,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        sizeBytes = sizeBytes,
        isDirectory = isDirectory
    )

internal fun DownloadIndexStorageStats.toStorageUsageStats(): StorageDownloadIndexStats =
    StorageDownloadIndexStats(databaseRecordCount = databaseRecordCount, allocatedPageBytes = allocatedPageBytes)

internal fun PlatformPlaylistCacheStorageStats.toStorageUsageStats(): StoragePlatformCacheStats =
    StoragePlatformCacheStats(cacheRecordCount = cacheRecordCount, allocatedPageBytes = allocatedPageBytes)
