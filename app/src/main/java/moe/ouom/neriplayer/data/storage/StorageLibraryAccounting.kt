package moe.ouom.neriplayer.data.storage

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.storage/StorageLibraryAccounting
 * Created: 2026/7/9
 */

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats

internal data class ManagedDownloadLibraryUsage(
    val audioFiles: FileStats,
    val lyricFiles: FileStats,
    val coverFiles: FileStats,
    val metadataFiles: FileStats,
    val localFiles: List<File>
) {
    companion object {
        val Empty = ManagedDownloadLibraryUsage(
            audioFiles = FileStats.Empty,
            lyricFiles = FileStats.Empty,
            coverFiles = FileStats.Empty,
            metadataFiles = FileStats.Empty,
            localFiles = emptyList()
        )
    }
}

internal data class DownloadIndexUsageStats(
    val sizeBytes: Long,
    val fileCount: Int,
    val databaseRecordCount: Int
)

internal fun ManagedDownloadStorage.DownloadLibrarySnapshot.toManagedDownloadLibraryUsage():
    ManagedDownloadLibraryUsage {
    return managedDownloadLibraryUsage(
        audioEntries = audioEntries,
        lyricEntries = lyricEntriesByName.values,
        coverEntries = coverEntriesByName.values,
        metadataEntries = metadataEntriesByAudioName.values
    )
}

internal fun managedDownloadLibraryUsage(
    audioEntries: Collection<ManagedDownloadStorage.StoredEntry>,
    lyricEntries: Collection<ManagedDownloadStorage.StoredEntry>,
    coverEntries: Collection<ManagedDownloadStorage.StoredEntry>,
    metadataEntries: Collection<ManagedDownloadStorage.StoredEntry>
): ManagedDownloadLibraryUsage {
    val allEntries = audioEntries + lyricEntries + coverEntries + metadataEntries
    return ManagedDownloadLibraryUsage(
        audioFiles = storedEntryStats(audioEntries),
        lyricFiles = storedEntryStats(lyricEntries),
        coverFiles = storedEntryStats(coverEntries),
        metadataFiles = storedEntryStats(metadataEntries),
        localFiles = managedStoredEntries(allEntries)
            .mapNotNull(ManagedDownloadStorage.StoredEntry::localFilePath)
            .map(::File)
            .distinctBy { file -> file.absolutePath }
    )
}

internal fun downloadIndexUsageStats(
    fileStats: FileStats,
    roomStats: DownloadIndexStorageStats
): DownloadIndexUsageStats {
    return DownloadIndexUsageStats(
        sizeBytes = fileStats.sizeBytes + roomStats.allocatedPageBytes,
        fileCount = fileStats.fileCount,
        databaseRecordCount = roomStats.databaseRecordCount
    )
}

private fun storedEntryStats(
    entries: Collection<ManagedDownloadStorage.StoredEntry>
): FileStats {
    return managedStoredEntries(entries).fold(FileStats.Empty) { stats, entry ->
        stats + FileStats(entry.sizeBytes.coerceAtLeast(0L), 1)
    }
}

private fun managedStoredEntries(
    entries: Collection<ManagedDownloadStorage.StoredEntry>
): List<ManagedDownloadStorage.StoredEntry> {
    return entries
        .asSequence()
        .filterNot { entry -> entry.isDirectory || entry.name == NO_MEDIA_FILE_NAME }
        .distinctBy(ManagedDownloadStorage.StoredEntry::usageIdentity)
        .toList()
}

private fun ManagedDownloadStorage.StoredEntry.usageIdentity(): String = when {
    reference.isNotBlank() -> reference
    mediaUri.isNotBlank() -> mediaUri
    else -> name
}
