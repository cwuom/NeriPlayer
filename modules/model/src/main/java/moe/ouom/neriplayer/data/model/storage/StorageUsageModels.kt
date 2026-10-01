package moe.ouom.neriplayer.data.model.storage

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
 * File: moe.ouom.neriplayer.data.storage.model/StorageUsageModels
 * Created: 2026/7/9
 */

enum class StorageCacheKind {
    Audio,
    Image,
    DownloadStaging,
    SharedMedia,
    Lyrics,
    NeteasePlaylist,
    BiliFavorite,
    BiliArchive,
    YouTubePlaylist,
    LogFiles,
    CrashLogs
}

enum class StorageUsageItemKind {
    AudioCache,
    ImageCache,
    DownloadStaging,
    SharedMedia,
    LyricsCache,
    NeteasePlaylistCache,
    BiliFavoriteCache,
    BiliArchiveCache,
    YouTubePlaylistCache,
    OtherCache,
    DownloadedMusic,
    DownloadedLyrics,
    DownloadedCovers,
    DownloadIndex,
    LogFiles,
    CrashLogs,
    LocalCovers,
    CustomBackground,
    LegacyMigrationFiles,
    Database,
    AppData
}

data class StorageCacheClearOptions(
    val audioCache: Boolean = true,
    val imageCache: Boolean = true,
    val downloadStaging: Boolean = false,
    val sharedMedia: Boolean = false,
    val lyricsCache: Boolean = false,
    val neteasePlaylistCache: Boolean = false,
    val biliFavoriteCache: Boolean = false,
    val biliArchiveCache: Boolean = false,
    val youtubePlaylistCache: Boolean = false,
    val logFiles: Boolean = false,
    val crashLogs: Boolean = false
) {
    val hasSelection: Boolean
        get() = audioCache || imageCache || downloadStaging || sharedMedia ||
            lyricsCache || hasPlatformCacheSelection || logFiles || crashLogs

    val needsPlayerCacheClear: Boolean
        get() = audioCache || imageCache

    val needsExtraCacheClear: Boolean
        get() = downloadStaging || sharedMedia || hasPlatformCacheSelection ||
            lyricsCache || logFiles || crashLogs

    val hasPlatformCacheSelection: Boolean
        get() = neteasePlaylistCache || biliFavoriteCache || biliArchiveCache ||
            youtubePlaylistCache
}

data class StorageUsageItem(
    val title: String,
    val description: String,
    val path: String?,
    val sizeBytes: Long,
    val fileCount: Int,
    val kind: StorageUsageItemKind,
    val databaseRecordCount: Int? = null,
    val countDescription: String? = null,
    val cacheKind: StorageCacheKind? = null
)

data class StorageUsageSection(
    val title: String,
    val items: List<StorageUsageItem>
) {
    val sizeBytes: Long = items.sumOf { it.sizeBytes }
    val fileCount: Int = items.sumOf { it.fileCount }
}

data class StorageUsageSummary(
    val sections: List<StorageUsageSection>
) {
    val totalSizeBytes: Long = sections.sumOf { it.sizeBytes }
    val totalFileCount: Int = sections.sumOf { it.fileCount }

    val cleanableSizeBytes: Long
        get() = StorageCacheKind.entries.sumOf(::sizeOf)

    fun sizeOf(kind: StorageCacheKind): Long {
        return sections.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.cacheKind == kind }
            .sumOf { it.sizeBytes }
    }

    companion object {
        val Empty = StorageUsageSummary(emptyList())
    }
}

data class ExtraCacheClearResult(
    val success: Boolean,
    val freedBytes: Long,
    val roomBytesMadeReusable: Long,
    val deletedFiles: Int
)
