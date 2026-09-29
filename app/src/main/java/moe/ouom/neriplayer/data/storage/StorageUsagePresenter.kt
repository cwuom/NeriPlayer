package moe.ouom.neriplayer.data.storage

import moe.ouom.neriplayer.data.storage.model.DownloadIndexUsageStats
import moe.ouom.neriplayer.data.storage.model.StorageUsageItem
import moe.ouom.neriplayer.data.storage.model.StorageUsageItemKind
import moe.ouom.neriplayer.data.storage.model.StorageUsageMeasurement
import moe.ouom.neriplayer.data.storage.model.StorageUsageSection
import moe.ouom.neriplayer.data.storage.model.StorageUsageSnapshot
import moe.ouom.neriplayer.data.storage.model.StorageUsageSummary
import moe.ouom.neriplayer.data.storage.source.storageCacheItemKinds

import android.content.res.Resources
import androidx.annotation.StringRes
import moe.ouom.neriplayer.R

internal class StorageUsagePresenter(private val resources: Resources) {
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
            databaseRecordCount = measurement.databaseRecordCount.takeUnless { text.kind == StorageUsageItemKind.DownloadIndex },
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
            R.plurals.storage_details_download_index_record_and_file_count,
            stats.databaseRecordCount,
            stats.databaseRecordCount,
            stats.fileCount
        )
    } else {
        resources.getQuantityString(
            R.plurals.storage_details_download_index_record_count,
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

private val cacheKinds = storageCacheItemKinds.entries.associate { it.value to it.key }

private val sections = listOf(
    SectionText(R.string.storage_group_cleanable_cache, listOf(
        ItemText(StorageUsageItemKind.AudioCache, R.string.storage_type_audio_cache, R.string.storage_desc_audio_cache),
        ItemText(StorageUsageItemKind.ImageCache, R.string.storage_type_image_cache, R.string.storage_desc_image_cache),
        ItemText(StorageUsageItemKind.DownloadStaging, R.string.storage_type_download_staging, R.string.storage_desc_download_staging),
        ItemText(StorageUsageItemKind.SharedMedia, R.string.storage_type_shared_media, R.string.storage_desc_shared_media),
        ItemText(StorageUsageItemKind.LyricsCache, R.string.storage_type_lyrics_cache, R.string.storage_desc_lyrics_cache),
        ItemText(StorageUsageItemKind.NeteasePlaylistCache, R.string.storage_type_netease_playlist_cache, R.string.storage_desc_netease_playlist_cache),
        ItemText(StorageUsageItemKind.BiliFavoriteCache, R.string.storage_type_bili_favorite_cache, R.string.storage_desc_bili_favorite_cache),
        ItemText(StorageUsageItemKind.BiliArchiveCache, R.string.storage_type_bili_archive_cache, R.string.storage_desc_bili_archive_cache),
        ItemText(StorageUsageItemKind.YouTubePlaylistCache, R.string.storage_type_youtube_playlist_cache, R.string.storage_desc_youtube_playlist_cache),
        ItemText(StorageUsageItemKind.OtherCache, R.string.storage_type_other_cache, R.string.storage_desc_other_cache),
        ItemText(StorageUsageItemKind.LogFiles, R.string.storage_type_log_files, R.string.storage_desc_log_files),
        ItemText(StorageUsageItemKind.CrashLogs, R.string.storage_type_crash_logs, R.string.storage_desc_crash_logs)
    )),
    SectionText(R.string.storage_group_downloads, listOf(
        ItemText(StorageUsageItemKind.DownloadedMusic, R.string.storage_type_downloaded_music, R.string.storage_desc_downloaded_music),
        ItemText(StorageUsageItemKind.DownloadedLyrics, R.string.storage_type_downloaded_lyrics, R.string.storage_desc_downloaded_lyrics),
        ItemText(StorageUsageItemKind.DownloadedCovers, R.string.storage_type_downloaded_covers, R.string.storage_desc_downloaded_covers),
        ItemText(StorageUsageItemKind.DownloadIndex, R.string.storage_type_download_index, R.string.storage_desc_download_index)
    )),
    SectionText(R.string.storage_group_app_data, listOf(
        ItemText(StorageUsageItemKind.LocalCovers, R.string.storage_type_local_covers, R.string.storage_desc_local_covers),
        ItemText(StorageUsageItemKind.CustomBackground, R.string.storage_type_custom_background, R.string.storage_desc_custom_background),
        ItemText(StorageUsageItemKind.LegacyMigrationFiles, R.string.storage_type_legacy_migration_files, R.string.storage_desc_legacy_migration_files),
        ItemText(StorageUsageItemKind.Database, R.string.storage_type_database, R.string.storage_desc_database),
        ItemText(StorageUsageItemKind.AppData, R.string.storage_type_app_data, R.string.storage_desc_app_data)
    ))
)
