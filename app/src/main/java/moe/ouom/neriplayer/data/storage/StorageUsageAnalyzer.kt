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
 * File: moe.ouom.neriplayer.data.storage/StorageUsageAnalyzer
 * Created: 2026/7/9
 */

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexRoomStore
import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats

enum class StorageCacheKind {
    Audio,
    Image,
    DownloadStaging,
    SharedMedia,
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
    val neteasePlaylistCache: Boolean = false,
    val biliFavoriteCache: Boolean = false,
    val biliArchiveCache: Boolean = false,
    val youtubePlaylistCache: Boolean = false,
    val logFiles: Boolean = false,
    val crashLogs: Boolean = false
) {
    val hasSelection: Boolean
        get() = audioCache || imageCache || downloadStaging || sharedMedia ||
            hasPlatformCacheSelection || logFiles || crashLogs

    val needsPlayerCacheClear: Boolean
        get() = audioCache || imageCache

    val needsExtraCacheClear: Boolean
        get() = downloadStaging || sharedMedia || hasPlatformCacheSelection ||
            logFiles || crashLogs

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

private data class StorageUsageScanInputs(
    val downloadLibraryUsage: ManagedDownloadLibraryUsage,
    val platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    val downloadIndexStorageStats: DownloadIndexStorageStats,
    val databaseFileStats: FileStats
)

suspend fun analyzeStorageUsage(context: Context): StorageUsageSummary = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val filesDir = appContext.filesDir
    val cacheDir = appContext.cacheDir
    val diagnosticsBaseDir = appContext.getExternalFilesDir(null) ?: filesDir

    val mediaCacheDir = File(cacheDir, DIR_MEDIA_CACHE)
    val imageCacheDir = File(cacheDir, DIR_IMAGE_CACHE)
    val downloadStagingDirs = downloadStagingDirs(filesDir, cacheDir)
    val sharedMediaDir = File(cacheDir, DIR_SHARED_MEDIA_EXPORTS)
    val platformCacheDirs = platformCacheDirs(filesDir)
    val databaseFiles = databaseFiles(appContext)
    val localCoverDir = File(filesDir, DIR_LOCAL_AUDIO_COVERS)
    val backgroundDir = File(filesDir, DIR_CUSTOM_BACKGROUND)
    val downloadMetadataFiles = downloadMetadataFiles(filesDir)
    val playlistDataFiles = playlistDataFiles(filesDir)
    val logDir = File(diagnosticsBaseDir, DIR_LOGS)
    val crashDir = File(diagnosticsBaseDir, DIR_CRASHES)

    val scanInputs = coroutineScope {
        val downloadLibraryUsage = async {
            runCatching {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = appContext,
                    forceRefresh = true
                ).toManagedDownloadLibraryUsage()
            }.getOrDefault(ManagedDownloadLibraryUsage.Empty)
        }
        val platformCacheStats = async {
            runCatching {
                val roomStore = PlatformPlaylistCacheRoomStore(
                    NeriUserDataDatabase.getInstance(appContext)
                )
                roomStore.storageStats(PLATFORM_CACHE_PLATFORMS)
            }.getOrDefault(emptyMap())
        }
        val downloadIndexStorageStats = async {
            runCatching {
                DownloadIndexRoomStore(
                    NeriUserDataDatabase.getInstance(appContext)
                ).storageStats()
            }.getOrDefault(DownloadIndexStorageStats.Empty)
        }
        val databaseFileStats = async {
            databaseFiles.fold(FileStats.Empty) { acc, file ->
                acc + statsOf(file)
            }
        }
        StorageUsageScanInputs(
            downloadLibraryUsage = downloadLibraryUsage.await(),
            platformCacheStats = platformCacheStats.await(),
            downloadIndexStorageStats = downloadIndexStorageStats.await(),
            databaseFileStats = databaseFileStats.await()
        )
    }
    val databaseAttribution = normalizeDatabaseStorageAttribution(
        platformCacheStats = scanInputs.platformCacheStats,
        downloadIndexStorageStats = scanInputs.downloadIndexStorageStats,
        databaseBytes = scanInputs.databaseFileStats.sizeBytes
    )
    val platformCacheStats = databaseAttribution.platformCacheStats
    val downloadIndexStorageStats = databaseAttribution.downloadIndexStorageStats
    val attributedDatabaseBytes = platformCacheStats.values.sumOf {
        it.allocatedPageBytes
    } + downloadIndexStorageStats.allocatedPageBytes
    val databaseUsageStats = databaseUsageStats(
        databaseStats = scanInputs.databaseFileStats,
        attributedDatabaseBytes = attributedDatabaseBytes
    )
    val downloadIndexFileStats = scanInputs.downloadLibraryUsage.metadataFiles +
        downloadMetadataFiles.fold(FileStats.Empty) { acc, file ->
            acc + statsOf(file)
        }

    val cacheKnownRoots = listOf(
        mediaCacheDir,
        imageCacheDir,
        sharedMediaDir
    ) + downloadStagingDirs
    val filesKnownRoots = knownAppDataRoots(
        platformCacheDirs = platformCacheDirs,
        downloadStagingDirs = downloadStagingDirs,
        localCoverDir = localCoverDir,
        backgroundDir = backgroundDir,
        downloadMetadataFiles = downloadMetadataFiles,
        playlistDataFiles = playlistDataFiles,
        logDir = logDir,
        crashDir = crashDir,
        downloadedStorageFiles = scanInputs.downloadLibraryUsage.localFiles
    )

    StorageUsageSummary(
        sections = listOf(
            StorageUsageSection(
                title = appContext.getString(R.string.storage_group_cleanable_cache),
                items = listOf(
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_audio_cache,
                        descriptionRes = R.string.storage_desc_audio_cache,
                        file = mediaCacheDir,
                        kind = StorageUsageItemKind.AudioCache,
                        cacheKind = StorageCacheKind.Audio
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_image_cache,
                        descriptionRes = R.string.storage_desc_image_cache,
                        file = imageCacheDir,
                        kind = StorageUsageItemKind.ImageCache,
                        cacheKind = StorageCacheKind.Image
                    ),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_download_staging,
                        descriptionRes = R.string.storage_desc_download_staging,
                        files = downloadStagingDirs,
                        kind = StorageUsageItemKind.DownloadStaging,
                        cacheKind = StorageCacheKind.DownloadStaging
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_shared_media,
                        descriptionRes = R.string.storage_desc_shared_media,
                        file = sharedMediaDir,
                        kind = StorageUsageItemKind.SharedMedia,
                        cacheKind = StorageCacheKind.SharedMedia
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_netease_playlist_cache,
                        descriptionRes = R.string.storage_desc_netease_playlist_cache,
                        file = File(filesDir, DIR_NETEASE_PLAYLIST_CACHE),
                        kind = StorageUsageItemKind.NeteasePlaylistCache,
                        databaseRecordCount = platformCacheStats[PLATFORM_NETEASE]
                            ?.cacheRecordCount,
                        databasePageBytes = platformCacheStats[PLATFORM_NETEASE]
                            ?.allocatedPageBytes ?: 0L,
                        cacheKind = StorageCacheKind.NeteasePlaylist
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_bili_favorite_cache,
                        descriptionRes = R.string.storage_desc_bili_favorite_cache,
                        file = File(filesDir, DIR_BILI_FAVORITE_CACHE),
                        kind = StorageUsageItemKind.BiliFavoriteCache,
                        databaseRecordCount = platformCacheStats[PLATFORM_BILI_FAVORITE]
                            ?.cacheRecordCount,
                        databasePageBytes = platformCacheStats[PLATFORM_BILI_FAVORITE]
                            ?.allocatedPageBytes ?: 0L,
                        cacheKind = StorageCacheKind.BiliFavorite
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_bili_archive_cache,
                        descriptionRes = R.string.storage_desc_bili_archive_cache,
                        file = File(filesDir, DIR_BILI_ARCHIVE_CACHE),
                        kind = StorageUsageItemKind.BiliArchiveCache,
                        databaseRecordCount = platformCacheStats[PLATFORM_BILI_ARCHIVE]
                            ?.cacheRecordCount,
                        databasePageBytes = platformCacheStats[PLATFORM_BILI_ARCHIVE]
                            ?.allocatedPageBytes ?: 0L,
                        cacheKind = StorageCacheKind.BiliArchive
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_youtube_playlist_cache,
                        descriptionRes = R.string.storage_desc_youtube_playlist_cache,
                        file = File(filesDir, DIR_YOUTUBE_PLAYLIST_CACHE),
                        kind = StorageUsageItemKind.YouTubePlaylistCache,
                        databaseRecordCount = platformCacheStats[PLATFORM_YOUTUBE]
                            ?.cacheRecordCount,
                        databasePageBytes = platformCacheStats[PLATFORM_YOUTUBE]
                            ?.allocatedPageBytes ?: 0L,
                        cacheKind = StorageCacheKind.YouTubePlaylist
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_other_cache,
                        descriptionRes = R.string.storage_desc_other_cache,
                        file = cacheDir,
                        kind = StorageUsageItemKind.OtherCache,
                        excludedRoots = cacheKnownRoots
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_log_files,
                        descriptionRes = R.string.storage_desc_log_files,
                        file = logDir,
                        kind = StorageUsageItemKind.LogFiles,
                        cacheKind = StorageCacheKind.LogFiles
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_crash_logs,
                        descriptionRes = R.string.storage_desc_crash_logs,
                        file = crashDir,
                        kind = StorageUsageItemKind.CrashLogs,
                        cacheKind = StorageCacheKind.CrashLogs
                    )
                )
            ),
            StorageUsageSection(
                title = appContext.getString(R.string.storage_group_downloads),
                items = listOf(
                    StorageUsageItem(
                        title = appContext.getString(R.string.storage_type_downloaded_music),
                        description = appContext.getString(R.string.storage_desc_downloaded_music),
                        path = null,
                        sizeBytes = scanInputs.downloadLibraryUsage.audioFiles.sizeBytes,
                        fileCount = scanInputs.downloadLibraryUsage.audioFiles.fileCount,
                        kind = StorageUsageItemKind.DownloadedMusic
                    ),
                    downloadedLibraryUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_downloaded_lyrics,
                        descriptionRes = R.string.storage_desc_downloaded_lyrics,
                        stats = scanInputs.downloadLibraryUsage.lyricFiles,
                        kind = StorageUsageItemKind.DownloadedLyrics
                    ),
                    downloadedLibraryUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_downloaded_covers,
                        descriptionRes = R.string.storage_desc_downloaded_covers,
                        stats = scanInputs.downloadLibraryUsage.coverFiles,
                        kind = StorageUsageItemKind.DownloadedCovers
                    ),
                    downloadIndexUsageItem(
                        context = appContext,
                        fileStats = downloadIndexFileStats,
                        roomStats = downloadIndexStorageStats
                    )
                )
            ),
            StorageUsageSection(
                title = appContext.getString(R.string.storage_group_app_data),
                items = listOf(
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_local_covers,
                        descriptionRes = R.string.storage_desc_local_covers,
                        file = localCoverDir,
                        kind = StorageUsageItemKind.LocalCovers
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_custom_background,
                        descriptionRes = R.string.storage_desc_custom_background,
                        file = backgroundDir,
                        kind = StorageUsageItemKind.CustomBackground
                    ),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_legacy_migration_files,
                        descriptionRes = R.string.storage_desc_legacy_migration_files,
                        files = playlistDataFiles,
                        kind = StorageUsageItemKind.LegacyMigrationFiles
                    ),
                    databaseUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_database,
                        descriptionRes = R.string.storage_desc_database,
                        stats = databaseUsageStats
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_app_data,
                        descriptionRes = R.string.storage_desc_app_data,
                        file = filesDir,
                        kind = StorageUsageItemKind.AppData,
                        excludedRoots = filesKnownRoots
                    )
                )
            )
        )
    )
}

private fun downloadedLibraryUsageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    stats: FileStats,
    kind: StorageUsageItemKind
): StorageUsageItem {
    return StorageUsageItem(
        title = context.getString(titleRes),
        description = context.getString(descriptionRes),
        path = null,
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount,
        kind = kind
    )
}

private fun downloadIndexUsageItem(
    context: Context,
    fileStats: FileStats,
    roomStats: DownloadIndexStorageStats
): StorageUsageItem {
    val stats = downloadIndexUsageStats(fileStats, roomStats)
    return StorageUsageItem(
        title = context.getString(R.string.storage_type_download_index),
        description = context.getString(R.string.storage_desc_download_index),
        path = null,
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount,
        kind = StorageUsageItemKind.DownloadIndex,
        countDescription = downloadIndexCountDescription(context, stats)
    )
}

private fun downloadIndexCountDescription(
    context: Context,
    stats: DownloadIndexUsageStats
): String? {
    if (stats.databaseRecordCount <= 0) return null
    return if (stats.fileCount > 0) {
        context.resources.getQuantityString(
            R.plurals.storage_details_download_index_record_and_file_count,
            stats.databaseRecordCount,
            stats.databaseRecordCount,
            stats.fileCount
        )
    } else {
        context.resources.getQuantityString(
            R.plurals.storage_details_download_index_record_count,
            stats.databaseRecordCount
        )
    }
}

suspend fun clearExtraStorageCaches(
    context: Context,
    options: StorageCacheClearOptions
): ExtraCacheClearResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val targets = buildList {
        if (options.downloadStaging) addAll(downloadStagingDirs(appContext.filesDir, appContext.cacheDir))
        if (options.sharedMedia) add(File(appContext.cacheDir, DIR_SHARED_MEDIA_EXPORTS))
        if (options.logFiles) add(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, DIR_LOGS))
        if (options.crashLogs) {
            add(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, DIR_CRASHES))
        }
        if (options.neteasePlaylistCache) {
            add(File(appContext.filesDir, DIR_NETEASE_PLAYLIST_CACHE))
        }
        if (options.biliFavoriteCache) {
            add(File(appContext.filesDir, DIR_BILI_FAVORITE_CACHE))
        }
        if (options.biliArchiveCache) {
            add(File(appContext.filesDir, DIR_BILI_ARCHIVE_CACHE))
        }
        if (options.youtubePlaylistCache) {
            add(File(appContext.filesDir, DIR_YOUTUBE_PLAYLIST_CACHE))
        }
    }

    val selectedPlatforms = buildList {
        if (options.neteasePlaylistCache) add(PLATFORM_NETEASE)
        if (options.biliFavoriteCache) add(PLATFORM_BILI_FAVORITE)
        if (options.biliArchiveCache) add(PLATFORM_BILI_ARCHIVE)
        if (options.youtubePlaylistCache) add(PLATFORM_YOUTUBE)
    }
    val roomStore = if (selectedPlatforms.isNotEmpty()) {
        runCatching {
            PlatformPlaylistCacheRoomStore(NeriUserDataDatabase.getInstance(appContext))
        }.getOrNull()
    } else {
        null
    }
    val roomBytesBeforeClear = roomStore?.let { store ->
        runCatching {
            store.storageStats(selectedPlatforms).values.sumOf { it.allocatedPageBytes }
        }.getOrDefault(0L)
    } ?: 0L

    var freedBytes = 0L
    var deletedFiles = 0
    var success = true
    targets.forEach { target ->
        val before = statsOf(target)
        if (before.fileCount == 0 && before.sizeBytes == 0L) {
            return@forEach
        }
        val deleted = when {
            options.logFiles && target.name == DIR_LOGS ->
                NPLogger.clearLogFiles(appContext)
            options.crashLogs && target.name == DIR_CRASHES ->
                ExceptionHandler.clearCrashLogs(appContext)
            else -> runCatching {
                val deleted = target.deleteRecursively()
                if (deleted) {
                    target.mkdirs()
                }
                deleted
            }.getOrElse { false }
        }
        if (deleted) {
            freedBytes += before.sizeBytes
            deletedFiles += before.fileCount
        } else {
            success = false
        }
    }
    var roomBytesMadeReusable = 0L
    if (selectedPlatforms.isNotEmpty() && roomStore != null) {
        runCatching {
            roomStore.clearSelected(selectedPlatforms)
            roomBytesMadeReusable = roomBytesBeforeClear
        }.onFailure {
            success = false
        }
    }
    ExtraCacheClearResult(
        success = success,
        freedBytes = freedBytes,
        roomBytesMadeReusable = roomBytesMadeReusable,
        deletedFiles = deletedFiles
    )
}

private fun usageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    file: File?,
    kind: StorageUsageItemKind,
    databaseRecordCount: Int? = null,
    databasePageBytes: Long = 0L,
    cacheKind: StorageCacheKind? = null,
    excludedRoots: List<File> = emptyList()
): StorageUsageItem {
    val stats = statsOf(file, excludedRoots)
    return StorageUsageItem(
        title = context.getString(titleRes),
        description = context.getString(descriptionRes),
        path = file?.absolutePath,
        sizeBytes = stats.sizeBytes + databasePageBytes,
        fileCount = stats.fileCount,
        kind = kind,
        databaseRecordCount = databaseRecordCount,
        cacheKind = cacheKind
    )
}

private fun aggregateUsageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    files: List<File>,
    kind: StorageUsageItemKind,
    cacheKind: StorageCacheKind? = null
): StorageUsageItem {
    val stats = files.fold(FileStats.Empty) { acc, file ->
        acc + statsOf(file)
    }
    return StorageUsageItem(
        title = context.getString(titleRes),
        description = context.getString(descriptionRes),
        path = files.joinToString(separator = "\n") { it.absolutePath },
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount,
        kind = kind,
        cacheKind = cacheKind
    )
}

private fun databaseUsageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    stats: FileStats
): StorageUsageItem {
    return StorageUsageItem(
        title = context.getString(titleRes),
        description = context.getString(descriptionRes),
        path = null,
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount,
        kind = StorageUsageItemKind.Database
    )
}

internal data class FileStats(
    val sizeBytes: Long,
    val fileCount: Int
) {
    operator fun plus(other: FileStats): FileStats {
        return FileStats(
            sizeBytes = sizeBytes + other.sizeBytes,
            fileCount = fileCount + other.fileCount
        )
    }

    companion object {
        val Empty = FileStats(0L, 0)
    }
}

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

private fun ManagedDownloadStorage.StoredEntry.usageIdentity(): String {
    return reference.takeIf(String::isNotBlank)
        ?: mediaUri.takeIf(String::isNotBlank)
        ?: name
}

internal data class DatabaseStorageAttribution(
    val platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    val downloadIndexStorageStats: DownloadIndexStorageStats
)

internal fun normalizeDatabaseStorageAttribution(
    platformCacheStats: Map<String, PlatformPlaylistCacheStorageStats>,
    downloadIndexStorageStats: DownloadIndexStorageStats,
    databaseBytes: Long
): DatabaseStorageAttribution {
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
    val totalAllocatedBytes = allocations.values.sum()
    if (totalAllocatedBytes <= 0L || databaseBytes <= 0L || totalAllocatedBytes <= databaseBytes) {
        return DatabaseStorageAttribution(
            platformCacheStats = platformCacheStats,
            downloadIndexStorageStats = downloadIndexStorageStats
        )
    }

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
    return DatabaseStorageAttribution(
        platformCacheStats = platformCacheStats.mapValues { (platform, stats) ->
            stats.copy(
                allocatedPageBytes = normalizedBytes["platform:$platform"] ?: 0L
            )
        },
        downloadIndexStorageStats = downloadIndexStorageStats.copy(
            allocatedPageBytes = normalizedBytes["download_index"] ?: 0L
        )
    )
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

internal fun knownAppDataRoots(
    platformCacheDirs: List<File>,
    downloadStagingDirs: List<File>,
    localCoverDir: File,
    backgroundDir: File,
    downloadMetadataFiles: List<File>,
    playlistDataFiles: List<File>,
    logDir: File,
    crashDir: File,
    downloadedStorageFiles: List<File> = emptyList()
): List<File> {
    return platformCacheDirs +
        downloadStagingDirs +
        localCoverDir +
        backgroundDir +
        downloadMetadataFiles +
        playlistDataFiles +
        logDir +
        crashDir +
        downloadedStorageFiles
}

internal fun statsOf(file: File?, excludedRoots: List<File> = emptyList()): FileStats {
    if (file == null || !file.exists()) return FileStats.Empty
    val excludedRootPaths = excludedRoots.map { root -> root.absolutePath }
    return runCatching {
        if (file.isFile) {
            FileStats(file.length(), 1)
        } else {
            file.walkTopDown()
                .onEnter { directory ->
                    excludedRootPaths.none { directory.isUnder(it) }
                }
                .filter { entry ->
                    entry.isFile && excludedRootPaths.none { entry.isUnder(it) }
                }
                .fold(FileStats.Empty) { acc, entry ->
                    acc + FileStats(entry.length(), 1)
                }
        }
    }.getOrDefault(FileStats.Empty)
}

private fun File.isUnder(rootPath: String): Boolean {
    val filePath = absolutePath
    return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
}

private fun platformCacheDirs(filesDir: File): List<File> {
    return listOf(
        File(filesDir, DIR_BILI_FAVORITE_CACHE),
        File(filesDir, DIR_BILI_ARCHIVE_CACHE),
        File(filesDir, DIR_NETEASE_PLAYLIST_CACHE),
        File(filesDir, DIR_YOUTUBE_PLAYLIST_CACHE)
    )
}

private fun databaseFiles(context: Context): List<File> {
    val databaseFile = context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME)
    return listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm")
    )
}

private fun downloadStagingDirs(filesDir: File, cacheDir: File): List<File> {
    return listOf(
        File(filesDir, DIR_DOWNLOAD_STAGING),
        File(cacheDir, DIR_DOWNLOAD_STAGING)
    ).distinctBy { file -> file.absolutePath }
}

private fun downloadMetadataFiles(filesDir: File): List<File> {
    return listOf(
        File(filesDir, FILE_MANAGED_DOWNLOAD_SNAPSHOT),
        File(filesDir, FILE_PENDING_DOWNLOAD_QUEUE),
        File(filesDir, FILE_CANCELLED_DOWNLOAD_KEYS),
        File(filesDir, FILE_DOWNLOADED_SONG_CATALOG_V3),
        File(filesDir, FILE_DOWNLOADED_SONG_CATALOG_V4)
    )
}

private fun playlistDataFiles(filesDir: File): List<File> {
    return listOf(
        File(filesDir, FILE_LOCAL_PLAYLISTS),
        File(filesDir, FILE_FAVORITE_PLAYLISTS),
        File(filesDir, FILE_PLAYLIST_USAGE)
    )
}

private const val DIR_MEDIA_CACHE = "media_cache"
private const val DIR_IMAGE_CACHE = "image_cache"
private const val DIR_DOWNLOAD_STAGING = "download_staging"
private const val DIR_SHARED_MEDIA_EXPORTS = "shared_media_exports"
private const val DIR_BILI_FAVORITE_CACHE = "bili_favorite_cache"
private const val DIR_BILI_ARCHIVE_CACHE = "bili_archive_cache"
private const val DIR_NETEASE_PLAYLIST_CACHE = "netease_playlist_cache"
private const val DIR_YOUTUBE_PLAYLIST_CACHE = "youtube_music_playlist_cache"
private const val DIR_LOCAL_AUDIO_COVERS = "local_audio_covers"
private const val DIR_CUSTOM_BACKGROUND = "custom_background"
private const val DIR_LOGS = "logs"
private const val DIR_CRASHES = "crashes"
private const val FILE_MANAGED_DOWNLOAD_SNAPSHOT = "managed_download_snapshot_v1.json"
private const val FILE_PENDING_DOWNLOAD_QUEUE = "pending_download_queue_v1.json"
private const val FILE_CANCELLED_DOWNLOAD_KEYS = "cancelled_download_keys_v1.json"
private const val FILE_DOWNLOADED_SONG_CATALOG_V3 = "downloaded_song_catalog_v3.json"
private const val FILE_DOWNLOADED_SONG_CATALOG_V4 = "downloaded_song_catalog_v4.json"
private const val FILE_LOCAL_PLAYLISTS = "local_playlists.json"
private const val FILE_FAVORITE_PLAYLISTS = "favorite_playlists.json"
private const val FILE_PLAYLIST_USAGE = "playlist_usage.json"

private const val PLATFORM_NETEASE = "netease"
private const val PLATFORM_BILI_FAVORITE = "bili_favorite"
private const val PLATFORM_BILI_ARCHIVE = "bili_archive"
private const val PLATFORM_YOUTUBE = "youtube_music"

private val PLATFORM_CACHE_PLATFORMS = listOf(
    PLATFORM_NETEASE,
    PLATFORM_BILI_FAVORITE,
    PLATFORM_BILI_ARCHIVE,
    PLATFORM_YOUTUBE
)
