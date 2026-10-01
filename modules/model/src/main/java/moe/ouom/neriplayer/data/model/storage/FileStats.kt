package moe.ouom.neriplayer.data.model.storage

data class FileStats(
    val sizeBytes: Long,
    val fileCount: Int
) {
    operator fun plus(other: FileStats): FileStats {
        return FileStats(
            sizeBytes = sizeBytes + other.sizeBytes,
            fileCount = fileCount + other.fileCount
        )
    }

    companion object {
        val Empty = FileStats(0L, 0)
    }
}
