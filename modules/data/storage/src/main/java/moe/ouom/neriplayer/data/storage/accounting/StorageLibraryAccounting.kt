package moe.ouom.neriplayer.data.storage.accounting

import java.io.File
import moe.ouom.neriplayer.data.storage.model.DownloadIndexUsageStats
import moe.ouom.neriplayer.data.storage.model.FileStats
import moe.ouom.neriplayer.data.storage.model.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.storage.model.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.storage.model.StorageLibraryEntry

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
 * File: moe.ouom.neriplayer.data.storage.accounting/StorageLibraryAccounting
 * Created: 2026/7/9
 */

fun managedDownloadLibraryUsage(
    audioEntries: Collection<StorageLibraryEntry>,
    lyricEntries: Collection<StorageLibraryEntry>,
    coverEntries: Collection<StorageLibraryEntry>,
    metadataEntries: Collection<StorageLibraryEntry>
): ManagedDownloadLibraryUsage {
    val allEntries = audioEntries + lyricEntries + coverEntries + metadataEntries
    return ManagedDownloadLibraryUsage(
        audioFiles = storedEntryStats(audioEntries),
        lyricFiles = storedEntryStats(lyricEntries),
        coverFiles = storedEntryStats(coverEntries),
        metadataFiles = storedEntryStats(metadataEntries),
        localFiles = managedStoredEntries(allEntries)
            .mapNotNull(StorageLibraryEntry::localFilePath)
            .map(::File)
            .distinctBy { file -> file.absolutePath }
    )
}

internal fun downloadIndexUsageStats(
    fileStats: FileStats,
    roomStats: StorageDownloadIndexStats
): DownloadIndexUsageStats {
    return DownloadIndexUsageStats(
        sizeBytes = fileStats.sizeBytes + roomStats.allocatedPageBytes,
        fileCount = fileStats.fileCount,
        databaseRecordCount = roomStats.databaseRecordCount
    )
}

private fun storedEntryStats(
    entries: Collection<StorageLibraryEntry>
): FileStats {
    return managedStoredEntries(entries).fold(FileStats.Empty) { stats, entry ->
        stats + FileStats(entry.sizeBytes.coerceAtLeast(0L), 1)
    }
}

private fun managedStoredEntries(
    entries: Collection<StorageLibraryEntry>
): List<StorageLibraryEntry> {
    return entries
        .asSequence()
        .filterNot { entry -> entry.isDirectory || entry.name == ".nomedia" }
        .distinctBy(StorageLibraryEntry::usageIdentity)
        .toList()
}

private fun StorageLibraryEntry.usageIdentity(): String = when {
    reference.isNotBlank() -> reference
    mediaUri.isNotBlank() -> mediaUri
    else -> name
}
