package moe.ouom.neriplayer.data.local.storage.presentation

import moe.ouom.neriplayer.data.model.storage.DownloadIndexUsageStats
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItem
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageMeasurement
import moe.ouom.neriplayer.data.model.storage.StorageUsageSection
import moe.ouom.neriplayer.data.model.storage.StorageUsageSnapshot
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.data.local.storage.source.storageCacheItemKinds

import android.content.res.Resources
import androidx.annotation.StringRes
import moe.ouom.neriplayer.common.R as CoreCommonR

class StorageUsagePresenter(private val resources: Resources) {
    fun present(snapshot: StorageUsageSnapshot): StorageUsageSummary = StorageUsageSummary(
        sections.map { section ->
            StorageUsageSection(resources.getString(section.title), section.items.map { item(it, snapshot) })
        }
    )

    private fun item(text: ItemText, snapshot: StorageUsageSnapshot): StorageUsageItem {
        val measurement = snapshot.items.getValue(text.kind)
        return StorageUsageItem(
            title = resources.getString(text.title),
            description = resources.getString(text.description),
            path = measurement.path,
            sizeBytes = measurement.stats.sizeBytes,
            fileCount = measurement.stats.fileCount,
            kind = text.kind,
            databaseRecordCount = if (text.kind == StorageUsageItemKind.DownloadIndex) null else measurement.databaseRecordCount,
            countDescription = countDescription(text.kind, measurement),
            cacheKind = cacheKinds[text.kind]
        )
    }

    private fun countDescription(kind: StorageUsageItemKind, measurement: StorageUsageMeasurement): String? {
        if (kind != StorageUsageItemKind.DownloadIndex) return null
        return downloadIndexCountDescription(resources, DownloadIndexUsageStats(
            measurement.stats.sizeBytes, measurement.stats.fileCount, measurement.databaseRecordCount ?: 0
        ))
    }
}

internal fun downloadIndexCountDescription(
    resources: Resources,
    stats: DownloadIndexUsageStats
): String? {
    if (stats.databaseRecordCount <= 0) return null
    return if (stats.fileCount > 0) {
        resources.getQuantityString(
            CoreCommonR.plurals.storage_details_download_index_record_and_file_count,
            stats.databaseRecordCount,
            stats.databaseRecordCount,
            stats.fileCount
        )
    } else {
        resources.getQuantityString(
            CoreCommonR.plurals.storage_details_download_index_record_count,
            stats.databaseRecordCount,
            stats.databaseRecordCount
        )
    }
}

private data class ItemText(
    val kind: StorageUsageItemKind,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int
)

private data class SectionText(@param:StringRes val title: Int, val items: List<ItemText>)

private val cacheKinds = storageCacheItemKinds.entries
    .filter { it.key != StorageCacheKind.DownloadStaging }
    .associate { it.value to it.key }

private val sections = listOf(
    SectionText(CoreCommonR.string.storage_group_cleanable_cache, listOf(
        ItemText(StorageUsageItemKind.AudioCache, CoreCommonR.string.storage_type_audio_cache, CoreCommonR.string.storage_desc_audio_cache),
        ItemText(StorageUsageItemKind.ImageCache, CoreCommonR.string.storage_type_image_cache, CoreCommonR.string.storage_desc_image_cache),
        ItemText(StorageUsageItemKind.SharedMedia, CoreCommonR.string.storage_type_shared_media, CoreCommonR.string.storage_desc_shared_media),
        ItemText(StorageUsageItemKind.LyricsCache, CoreCommonR.string.storage_type_lyrics_cache, CoreCommonR.string.storage_desc_lyrics_cache),
        ItemText(StorageUsageItemKind.NeteasePlaylistCache, CoreCommonR.string.storage_type_netease_playlist_cache, CoreCommonR.string.storage_desc_netease_playlist_cache),
        ItemText(StorageUsageItemKind.BiliFavoriteCache, CoreCommonR.string.storage_type_bili_favorite_cache, CoreCommonR.string.storage_desc_bili_favorite_cache),
        ItemText(StorageUsageItemKind.BiliArchiveCache, CoreCommonR.string.storage_type_bili_archive_cache, CoreCommonR.string.storage_desc_bili_archive_cache),
        ItemText(StorageUsageItemKind.YouTubePlaylistCache, CoreCommonR.string.storage_type_youtube_playlist_cache, CoreCommonR.string.storage_desc_youtube_playlist_cache),
        ItemText(StorageUsageItemKind.ServerBrowseCache, CoreCommonR.string.storage_type_server_browse_cache, CoreCommonR.string.storage_desc_server_browse_cache),
        ItemText(StorageUsageItemKind.OtherCache, CoreCommonR.string.storage_type_other_cache, CoreCommonR.string.storage_desc_other_cache),
        ItemText(StorageUsageItemKind.LogFiles, CoreCommonR.string.storage_type_log_files, CoreCommonR.string.storage_desc_log_files),
        ItemText(StorageUsageItemKind.CrashLogs, CoreCommonR.string.storage_type_crash_logs, CoreCommonR.string.storage_desc_crash_logs)
    )),
    SectionText(CoreCommonR.string.storage_group_downloads, listOf(
        ItemText(StorageUsageItemKind.DownloadStaging, CoreCommonR.string.storage_type_download_staging, CoreCommonR.string.storage_desc_download_staging),
        ItemText(StorageUsageItemKind.DownloadedMusic, CoreCommonR.string.storage_type_downloaded_music, CoreCommonR.string.storage_desc_downloaded_music),
        ItemText(StorageUsageItemKind.DownloadedLyrics, CoreCommonR.string.storage_type_downloaded_lyrics, CoreCommonR.string.storage_desc_downloaded_lyrics),
        ItemText(StorageUsageItemKind.DownloadedCovers, CoreCommonR.string.storage_type_downloaded_covers, CoreCommonR.string.storage_desc_downloaded_covers),
        ItemText(StorageUsageItemKind.DownloadIndex, CoreCommonR.string.storage_type_download_index, CoreCommonR.string.storage_desc_download_index)
    )),
    SectionText(CoreCommonR.string.storage_group_app_data, listOf(
        ItemText(StorageUsageItemKind.LocalCovers, CoreCommonR.string.storage_type_local_covers, CoreCommonR.string.storage_desc_local_covers),
        ItemText(StorageUsageItemKind.CustomBackground, CoreCommonR.string.storage_type_custom_background, CoreCommonR.string.storage_desc_custom_background),
        ItemText(StorageUsageItemKind.LegacyMigrationFiles, CoreCommonR.string.storage_type_legacy_migration_files, CoreCommonR.string.storage_desc_legacy_migration_files),
        ItemText(StorageUsageItemKind.Database, CoreCommonR.string.storage_type_database, CoreCommonR.string.storage_desc_database),
        ItemText(StorageUsageItemKind.AppData, CoreCommonR.string.storage_type_app_data, CoreCommonR.string.storage_desc_app_data)
    ))
)
