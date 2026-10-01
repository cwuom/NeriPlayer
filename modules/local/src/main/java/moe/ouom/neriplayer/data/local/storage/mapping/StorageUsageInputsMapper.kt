package moe.ouom.neriplayer.data.local.storage.mapping

import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry

import moe.ouom.neriplayer.data.local.storage.accounting.managedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StorageLibraryEntry
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats

import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats

fun DownloadLibrarySnapshot.toManagedDownloadLibraryUsage():
    ManagedDownloadLibraryUsage {
    return managedDownloadLibraryUsage(
        audioEntries = audioEntries.map { it.toStorageLibraryEntry() },
        lyricEntries = lyricEntriesByName.values.map { it.toStorageLibraryEntry() },
        coverEntries = coverEntriesByName.values.map { it.toStorageLibraryEntry() },
        metadataEntries = metadataEntriesByAudioName.values.map { it.toStorageLibraryEntry() }
    )
}

fun DownloadLibraryEntry.toStorageLibraryEntry(): StorageLibraryEntry =
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

fun PlatformPlaylistCacheStorageStats.toStorageUsageStats(): StoragePlatformCacheStats =
    StoragePlatformCacheStats(cacheRecordCount = cacheRecordCount, allocatedPageBytes = allocatedPageBytes)
