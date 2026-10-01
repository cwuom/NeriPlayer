package moe.ouom.neriplayer.data.storage

import moe.ouom.neriplayer.data.local.database.store.DownloadIndexStorageStats
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStorageStats
import moe.ouom.neriplayer.data.model.storage.StorageDownloadIndexStats
import moe.ouom.neriplayer.data.model.storage.StoragePlatformCacheStats
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageInputsMapperTest {
    @Test
    fun databaseAdaptersPreserveRecordCountsAndAllocatedPages() {
        assertEquals(
            StorageDownloadIndexStats(118, 8192),
            DownloadIndexStorageStats(118, 8192).toStorageUsageStats()
        )
        assertEquals(
            StoragePlatformCacheStats(3, 4096),
            PlatformPlaylistCacheStorageStats(3, 4096).toStorageUsageStats()
        )
    }
}
