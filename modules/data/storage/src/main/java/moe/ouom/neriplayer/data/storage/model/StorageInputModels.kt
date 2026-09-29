package moe.ouom.neriplayer.data.storage.model

data class StorageLibraryEntry(
    val name: String,
    val reference: String,
    val mediaUri: String,
    val localFilePath: String?,
    val sizeBytes: Long,
    val isDirectory: Boolean = false
)

data class StoragePlatformCacheStats(
    val cacheRecordCount: Int,
    val allocatedPageBytes: Long
)

data class StorageDownloadIndexStats(
    val databaseRecordCount: Int,
    val allocatedPageBytes: Long
) {
    companion object {
        val Empty = StorageDownloadIndexStats(databaseRecordCount = 0, allocatedPageBytes = 0)
    }
}
