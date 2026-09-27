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
 * File: moe.ouom.neriplayer.data.storage/StorageDatabaseAccounting
 * Created: 2026/7/9
 */

import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats

internal data class DatabaseStorageAttribution(
    val platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    val downloadIndexStorageStats: DownloadIndexStorageStats
)

internal fun normalizeDatabaseStorageAttribution(
    platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    downloadIndexStorageStats: DownloadIndexStorageStats,
    databaseBytes: Long
): DatabaseStorageAttribution {
    val allocations = databaseAllocations(platformCacheStats, downloadIndexStorageStats)
    val normalizedBytes = normalizeAllocations(allocations, databaseBytes)
        ?: return DatabaseStorageAttribution(platformCacheStats, downloadIndexStorageStats)
    return DatabaseStorageAttribution(
        platformCacheStats = platformCacheStats.mapValues { (platform, stats) ->
            stats.copy(allocatedPageBytes = normalizedBytes["platform:$platform"] ?: 0L)
        },
        downloadIndexStorageStats = downloadIndexStorageStats.copy(
            allocatedPageBytes = normalizedBytes["download_index"] ?: 0L
        )
    )
}

private fun databaseAllocations(
    platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    downloadIndexStorageStats: DownloadIndexStorageStats
): Map<String, Long> {
    val allocations = buildMap {
        platformCacheStats
            .filterValues { it.allocatedPageBytes > 0L }
            .toSortedMap()
            .forEach { (platform, stats) ->
                put("platform:$platform", stats.allocatedPageBytes)
            }
        if (downloadIndexStorageStats.allocatedPageBytes > 0L) {
            put("download_index", downloadIndexStorageStats.allocatedPageBytes)
        }
    }
    return allocations
}

private fun normalizeAllocations(allocations: Map<String, Long>, databaseBytes: Long): Map<String, Long>? {
    val totalAllocatedBytes = allocations.values.sum()
    if (totalAllocatedBytes <= 0L || databaseBytes <= 0L || totalAllocatedBytes <= databaseBytes) return null
    return scaleAllocations(allocations, databaseBytes, totalAllocatedBytes)
}

private fun scaleAllocations(allocations: Map<String, Long>, databaseBytes: Long, totalAllocatedBytes: Long): Map<String, Long> {
    var remainingBytes = databaseBytes
    var remainingAllocatedBytes = totalAllocatedBytes
    val normalizedBytes = buildMap {
        allocations.entries.forEachIndexed { index, (key, allocatedBytes) ->
            val normalized = if (index == allocations.size - 1) {
                remainingBytes
            } else {
                (remainingBytes.toDouble() * allocatedBytes / remainingAllocatedBytes)
                    .toLong()
                    .coerceIn(0L, remainingBytes)
            }
            put(key, normalized)
            remainingBytes -= normalized
            remainingAllocatedBytes -= allocatedBytes
        }
    }
    return normalizedBytes
}

internal fun databaseUsageStats(
    databaseStats: FileStats,
    attributedDatabaseBytes: Long
): FileStats {
    val attributedBytes = attributedDatabaseBytes.coerceIn(0L, databaseStats.sizeBytes)
    return FileStats(
        sizeBytes = databaseStats.sizeBytes - attributedBytes,
        fileCount = databaseStats.fileCount
    )
}
