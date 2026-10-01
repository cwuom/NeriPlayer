package moe.ouom.neriplayer.data.storage.source

import moe.ouom.neriplayer.data.model.storage.ManagedDownloadLibraryUsage
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats

interface StorageUsageSource {
    suspend fun downloadLibraryUsage(): ManagedDownloadLibraryUsage
    suspend fun platformCacheStats(): Map<String, StoragePlatformCacheStats>
    suspend fun downloadIndexStats(): StorageDownloadIndexStats
}
