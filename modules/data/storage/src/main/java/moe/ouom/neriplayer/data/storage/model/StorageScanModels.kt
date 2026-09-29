package moe.ouom.neriplayer.data.storage.model

data class StorageUsageMeasurement(
    val stats: FileStats,
    val path: String? = null,
    val databaseRecordCount: Int? = null
)

data class StorageUsageSnapshot(val items: Map<StorageUsageItemKind, StorageUsageMeasurement>)
