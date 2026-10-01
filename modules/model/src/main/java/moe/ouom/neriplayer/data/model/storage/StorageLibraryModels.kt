package moe.ouom.neriplayer.data.model.storage

import java.io.File

data class ManagedDownloadLibraryUsage(
    val audioFiles: FileStats,
    val lyricFiles: FileStats,
    val coverFiles: FileStats,
    val metadataFiles: FileStats,
    val localFiles: List<File>
) {
    companion object {
        val Empty = ManagedDownloadLibraryUsage(
            audioFiles = FileStats.Empty,
            lyricFiles = FileStats.Empty,
            coverFiles = FileStats.Empty,
            metadataFiles = FileStats.Empty,
            localFiles = emptyList()
        )
    }
}

data class DownloadIndexUsageStats(
    val sizeBytes: Long,
    val fileCount: Int,
    val databaseRecordCount: Int
)
