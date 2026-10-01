package moe.ouom.neriplayer.data.storage.accounting

import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats

internal data class DatabaseStorageAttribution(
    val platformCacheStats: Map<String, StoragePlatformCacheStats>,
    val downloadIndexStorageStats: StorageDownloadIndexStats
)
