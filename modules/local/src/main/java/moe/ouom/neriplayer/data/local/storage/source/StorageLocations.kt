package moe.ouom.neriplayer.data.local.storage.source

import java.io.File
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind

const val LYRICS_CACHE_DIRECTORY_NAME = "lyrics_cache"

val storagePlatformCaches = mapOf(
    StorageCacheKind.NeteasePlaylist to "netease",
    StorageCacheKind.BiliFavorite to "bili_favorite",
    StorageCacheKind.BiliArchive to "bili_archive",
    StorageCacheKind.YouTubePlaylist to "youtube_music"
)

val storageCacheItemKinds = mapOf(
    StorageCacheKind.Audio to StorageUsageItemKind.AudioCache,
    StorageCacheKind.Image to StorageUsageItemKind.ImageCache,
    StorageCacheKind.DownloadStaging to StorageUsageItemKind.DownloadStaging,
    StorageCacheKind.SharedMedia to StorageUsageItemKind.SharedMedia,
    StorageCacheKind.Lyrics to StorageUsageItemKind.LyricsCache,
    StorageCacheKind.NeteasePlaylist to StorageUsageItemKind.NeteasePlaylistCache,
    StorageCacheKind.BiliFavorite to StorageUsageItemKind.BiliFavoriteCache,
    StorageCacheKind.BiliArchive to StorageUsageItemKind.BiliArchiveCache,
    StorageCacheKind.YouTubePlaylist to StorageUsageItemKind.YouTubePlaylistCache,
    StorageCacheKind.LogFiles to StorageUsageItemKind.LogFiles,
    StorageCacheKind.CrashLogs to StorageUsageItemKind.CrashLogs
)

class StorageLocations(
    val filesDir: File,
    val cacheDir: File,
    diagnosticsDir: File,
    val databaseFiles: List<File>,
    val downloadMetadataFiles: List<File>
) {
    val mediaCacheDir = File(cacheDir, "media_cache")
    val imageCacheDir = File(cacheDir, "image_cache")
    val downloadStagingDirs = listOf(File(filesDir, "download_staging"), File(cacheDir, "download_staging"))
        .distinctBy { it.absolutePath }
    val sharedMediaDir = File(cacheDir, "shared_media_exports")
    val lyricsCacheDir = File(filesDir, LYRICS_CACHE_DIRECTORY_NAME)
    val localCoverDir = File(filesDir, "local_audio_covers")
    val backgroundDir = File(filesDir, "custom_background")
    val logDir = File(diagnosticsDir, "logs")
    val crashDir = File(diagnosticsDir, "crashes")
    val playlistDataFiles = listOf(
        "local_playlists.json", "favorite_playlists.json", "playlist_usage.json"
    ).map { File(filesDir, it) }
    val cacheFiles = mapOf(
        StorageCacheKind.Audio to listOf(mediaCacheDir),
        StorageCacheKind.Image to listOf(imageCacheDir),
        StorageCacheKind.DownloadStaging to downloadStagingDirs,
        StorageCacheKind.SharedMedia to listOf(sharedMediaDir),
        StorageCacheKind.Lyrics to listOf(lyricsCacheDir),
        StorageCacheKind.NeteasePlaylist to listOf(File(filesDir, "netease_playlist_cache")),
        StorageCacheKind.BiliFavorite to listOf(File(filesDir, "bili_favorite_cache")),
        StorageCacheKind.BiliArchive to listOf(File(filesDir, "bili_archive_cache")),
        StorageCacheKind.YouTubePlaylist to listOf(File(filesDir, "youtube_music_playlist_cache")),
        StorageCacheKind.LogFiles to listOf(logDir),
        StorageCacheKind.CrashLogs to listOf(crashDir)
    )

    internal fun cacheExclusions(): List<File> =
        listOf(mediaCacheDir, imageCacheDir, sharedMediaDir, lyricsCacheDir) + downloadStagingDirs

    internal fun appDataExclusions(downloadedFiles: List<File>): List<File> = knownAppDataRoots(
        platformCacheDirs = storagePlatformCaches.keys.flatMap { cacheFiles.getValue(it) },
        downloadStagingDirs = downloadStagingDirs,
        localCoverDir = localCoverDir,
        backgroundDir = backgroundDir,
        downloadMetadataFiles = downloadMetadataFiles,
        playlistDataFiles = playlistDataFiles,
        logDir = logDir,
        crashDir = crashDir,
        downloadedStorageFiles = downloadedFiles
    ) + lyricsCacheDir
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
