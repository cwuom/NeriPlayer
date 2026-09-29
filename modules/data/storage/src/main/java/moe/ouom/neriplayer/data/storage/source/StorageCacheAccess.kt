package moe.ouom.neriplayer.data.storage.source

import java.io.File
import moe.ouom.neriplayer.data.model.storage.FileStats
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind

interface StorageCacheFileAccess {
    fun stats(file: File): FileStats
    fun clear(file: File, kind: StorageCacheKind): Boolean
}

interface StoragePlatformCacheAccess {
    suspend fun allocatedBytes(platforms: List<String>): Long
    suspend fun clear(platforms: List<String>)
}
