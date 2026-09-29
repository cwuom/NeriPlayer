package moe.ouom.neriplayer.data.storage.source

import moe.ouom.neriplayer.data.storage.model.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.storage.model.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.storage.model.StoragePlatformCacheStats

interface StorageUsageSource {
    suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage
    suspend fun platformCacheStats(): Map<String, StoragePlatformCacheStats>
    suspend fun downloadIndexStats(): StorageDownloadIndexStats
}
