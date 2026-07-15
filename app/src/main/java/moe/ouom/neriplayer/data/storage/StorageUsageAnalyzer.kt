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
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

enum class StorageCacheKind {
    Audio,
    Image,
    DownloadStaging,
    SharedMedia,
    PlatformList
}

data class StorageCacheClearOptions(
    val audioCache: Boolean = true,
    val imageCache: Boolean = true,
    val downloadStaging: Boolean = false,
    val sharedMedia: Boolean = false,
    val platformList: Boolean = false
) {
    val hasSelection: Boolean
        get() = audioCache || imageCache || downloadStaging || sharedMedia || platformList

    val needsPlayerCacheClear: Boolean
        get() = audioCache || imageCache

    val needsExtraCacheClear: Boolean
        get() = downloadStaging || sharedMedia || platformList
}

data class StorageUsageItem(
    val title: String,
    val description: String,
    val path: String?,
    val sizeBytes: Long,
    val fileCount: Int,
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
    val deletedFiles: Int
)

suspend fun analyzeStorageUsage(context: Context): StorageUsageSummary = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val filesDir = appContext.filesDir
    val cacheDir = appContext.cacheDir
    val externalFilesDir = appContext.getExternalFilesDir(null)

    val mediaCacheDir = File(cacheDir, DIR_MEDIA_CACHE)
    val imageCacheDir = File(cacheDir, DIR_IMAGE_CACHE)
    val downloadStagingDirs = downloadStagingDirs(filesDir, cacheDir)
    val sharedMediaDir = File(cacheDir, DIR_SHARED_MEDIA_EXPORTS)
    val platformCacheDirs = platformCacheDirs(filesDir)
    val localCoverDir = File(filesDir, DIR_LOCAL_AUDIO_COVERS)
    val backgroundDir = File(filesDir, DIR_CUSTOM_BACKGROUND)
    val downloadMetadataFiles = downloadMetadataFiles(filesDir)
    val playlistDataFiles = playlistDataFiles(filesDir)
    val logDir = externalFilesDir?.let { File(it, DIR_LOGS) }
    val crashDir = externalFilesDir?.let { File(it, DIR_CRASHES) }

    val downloadableAudio = runCatching {
        ManagedDownloadStorage.listDownloadedAudio(appContext)
    }.getOrDefault(emptyList())

    val cacheKnownRoots = listOf(
        mediaCacheDir,
        imageCacheDir,
        sharedMediaDir
    ) + downloadStagingDirs
    val filesKnownRoots = platformCacheDirs +
        downloadStagingDirs +
        localCoverDir +
        backgroundDir +
        downloadMetadataFiles +
        playlistDataFiles

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
                        cacheKind = StorageCacheKind.Audio
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_image_cache,
                        descriptionRes = R.string.storage_desc_image_cache,
                        file = imageCacheDir,
                        cacheKind = StorageCacheKind.Image
                    ),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_download_staging,
                        descriptionRes = R.string.storage_desc_download_staging,
                        files = downloadStagingDirs,
                        cacheKind = StorageCacheKind.DownloadStaging
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_shared_media,
                        descriptionRes = R.string.storage_desc_shared_media,
                        file = sharedMediaDir,
                        cacheKind = StorageCacheKind.SharedMedia
                    ),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_platform_list_cache,
                        descriptionRes = R.string.storage_desc_platform_list_cache,
                        files = platformCacheDirs,
                        cacheKind = StorageCacheKind.PlatformList
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_other_cache,
                        descriptionRes = R.string.storage_desc_other_cache,
                        file = cacheDir,
                        excludedRoots = cacheKnownRoots
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
                        sizeBytes = downloadableAudio.sumOf { it.sizeBytes },
                        fileCount = downloadableAudio.size
                    ),
                    downloadedLyricUsageItem(appContext),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_download_index,
                        descriptionRes = R.string.storage_desc_download_index,
                        files = downloadMetadataFiles
                    )
                )
            ),
            StorageUsageSection(
                title = appContext.getString(R.string.storage_group_diagnostics),
                items = listOf(
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_log_files,
                        descriptionRes = R.string.storage_desc_log_files,
                        file = logDir
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_crash_logs,
                        descriptionRes = R.string.storage_desc_crash_logs,
                        file = crashDir
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
                        file = localCoverDir
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_custom_background,
                        descriptionRes = R.string.storage_desc_custom_background,
                        file = backgroundDir
                    ),
                    aggregateUsageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_playlist_data,
                        descriptionRes = R.string.storage_desc_playlist_data,
                        files = playlistDataFiles
                    ),
                    usageItem(
                        context = appContext,
                        titleRes = R.string.storage_type_app_data,
                        descriptionRes = R.string.storage_desc_app_data,
                        file = filesDir,
                        excludedRoots = filesKnownRoots
                    )
                )
            )
        )
    )
}

private fun downloadedLyricUsageItem(
    context: Context
): StorageUsageItem {
    val lyricsDir = defaultDownloadLyricsDir(context)
    val stats = statsOf(lyricsDir)
    return StorageUsageItem(
        title = context.getString(R.string.storage_type_downloaded_lyrics),
        description = context.getString(R.string.storage_desc_downloaded_lyrics),
        path = lyricsDir.absolutePath,
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount
    )
}

suspend fun clearExtraStorageCaches(
    context: Context,
    options: StorageCacheClearOptions
): ExtraCacheClearResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val targets = buildList {
        if (options.downloadStaging) addAll(downloadStagingDirs(appContext.filesDir, appContext.cacheDir))
        if (options.sharedMedia) add(File(appContext.cacheDir, DIR_SHARED_MEDIA_EXPORTS))
        if (options.platformList) addAll(platformCacheDirs(appContext.filesDir))
    }

    var freedBytes = 0L
    var deletedFiles = 0
    var success = true
    targets.forEach { target ->
        val before = statsOf(target)
        if (before.fileCount == 0 && before.sizeBytes == 0L) {
            return@forEach
        }
        val deleted = runCatching {
            val deleted = target.deleteRecursively()
            if (deleted) {
                target.mkdirs()
            }
            deleted
        }.getOrElse {
            false
        }
        if (deleted) {
            freedBytes += before.sizeBytes
            deletedFiles += before.fileCount
        } else {
            success = false
        }
    }
    ExtraCacheClearResult(
        success = success,
        freedBytes = freedBytes,
        deletedFiles = deletedFiles
    )
}

private fun usageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    file: File?,
    cacheKind: StorageCacheKind? = null,
    excludedRoots: List<File> = emptyList()
): StorageUsageItem {
    val stats = statsOf(file, excludedRoots)
    return StorageUsageItem(
        title = context.getString(titleRes),
        description = context.getString(descriptionRes),
        path = file?.absolutePath,
        sizeBytes = stats.sizeBytes,
        fileCount = stats.fileCount,
        cacheKind = cacheKind
    )
}

private fun aggregateUsageItem(
    context: Context,
    titleRes: Int,
    descriptionRes: Int,
    files: List<File>,
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
        cacheKind = cacheKind
    )
}

private data class FileStats(
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

private fun statsOf(file: File?, excludedRoots: List<File> = emptyList()): FileStats {
    if (file == null || !file.exists()) return FileStats.Empty
    return runCatching {
        if (file.isFile) {
            FileStats(file.length(), 1)
        } else {
            file.walkTopDown()
                .filter { entry -> entry.isFile && excludedRoots.none { entry.isUnder(it) } }
                .fold(FileStats.Empty) { acc, entry ->
                    acc + FileStats(entry.length(), 1)
                }
        }
    }.getOrDefault(FileStats.Empty)
}

private fun File.isUnder(root: File): Boolean {
    val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val filePath = runCatching { canonicalPath }.getOrDefault(absolutePath)
    return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
}

private fun platformCacheDirs(filesDir: File): List<File> {
    return listOf(
        File(filesDir, DIR_BILI_FAVORITE_CACHE),
        File(filesDir, DIR_NETEASE_PLAYLIST_CACHE),
        File(filesDir, DIR_YOUTUBE_PLAYLIST_CACHE)
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
        File(filesDir, FILE_DOWNLOADED_SONG_CATALOG)
    )
}

private fun playlistDataFiles(filesDir: File): List<File> {
    return listOf(
        File(filesDir, FILE_LOCAL_PLAYLISTS),
        File(filesDir, FILE_FAVORITE_PLAYLISTS),
        File(filesDir, FILE_PLAYLIST_USAGE)
    )
}

private fun defaultDownloadLyricsDir(context: Context): File {
    val baseDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        ?: context.filesDir
    return File(File(baseDir, DIR_DOWNLOAD_ROOT), DIR_DOWNLOAD_LYRICS)
}

private const val DIR_MEDIA_CACHE = "media_cache"
private const val DIR_IMAGE_CACHE = "image_cache"
private const val DIR_DOWNLOAD_STAGING = "download_staging"
private const val DIR_SHARED_MEDIA_EXPORTS = "shared_media_exports"
private const val DIR_DOWNLOAD_ROOT = "NeriPlayer"
private const val DIR_DOWNLOAD_LYRICS = "Lyrics"
private const val DIR_BILI_FAVORITE_CACHE = "bili_favorite_cache"
private const val DIR_NETEASE_PLAYLIST_CACHE = "netease_playlist_cache"
private const val DIR_YOUTUBE_PLAYLIST_CACHE = "youtube_music_playlist_cache"
private const val DIR_LOCAL_AUDIO_COVERS = "local_audio_covers"
private const val DIR_CUSTOM_BACKGROUND = "custom_background"
private const val DIR_LOGS = "logs"
private const val DIR_CRASHES = "crashes"
private const val FILE_MANAGED_DOWNLOAD_SNAPSHOT = "managed_download_snapshot_v1.json"
private const val FILE_PENDING_DOWNLOAD_QUEUE = "pending_download_queue_v1.json"
private const val FILE_CANCELLED_DOWNLOAD_KEYS = "cancelled_download_keys_v1.json"
private const val FILE_DOWNLOADED_SONG_CATALOG = "downloaded_song_catalog_v3.json"
private const val FILE_LOCAL_PLAYLISTS = "local_playlists.json"
private const val FILE_FAVORITE_PLAYLISTS = "favorite_playlists.json"
private const val FILE_PLAYLIST_USAGE = "playlist_usage.json"
