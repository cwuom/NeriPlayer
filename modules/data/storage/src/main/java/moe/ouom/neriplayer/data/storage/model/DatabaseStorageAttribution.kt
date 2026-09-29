package moe.ouom.neriplayer.data.storage.model

internal data class DatabaseStorageAttribution(
    val platformCacheStats: Map<String, StoragePlatformCacheStats>,
    val downloadIndexStorageStats: StorageDownloadIndexStats
)
