package moe.ouom.neriplayer.data.storage

import java.io.File

internal data class FileStats(
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

internal fun knownAppDataRoots(
    platformCacheDirs: List<File>,
    downloadStagingDirs: List<File>,
    localCoverDir: File,
    backgroundDir: File,
    downloadMetadataFiles: List<File>,
    playlistDataFiles: List<File>,
    logDir: File,
    crashDir: File,
    downloadedStorageFiles: List<File> = emptyList()
): List<File> {
    return platformCacheDirs +
        downloadStagingDirs +
        localCoverDir +
        backgroundDir +
        downloadMetadataFiles +
        playlistDataFiles +
        logDir +
        crashDir +
        downloadedStorageFiles
}

internal fun statsOf(file: File?, excludedRoots: List<File> = emptyList()): FileStats {
    if (file == null || !file.exists()) return FileStats.Empty
    return runCatching {
        if (file.isFile) FileStats(file.length(), 1) else directoryStats(file, excludedRoots)
    }.getOrDefault(FileStats.Empty)
}

internal fun statsOfFiles(files: List<File>): FileStats =
    files.fold(FileStats.Empty) { total, file -> total + statsOf(file) }

private fun directoryStats(directory: File, excludedRoots: List<File>): FileStats {
    val excludedPaths = excludedRoots.map { it.absolutePath }
    return directory.walkTopDown()
        .onEnter { it.isOutside(excludedPaths) }
        .filter { it.isFile && it.isOutside(excludedPaths) }
        .fold(FileStats.Empty) { total, file -> total + FileStats(file.length(), 1) }
}

private fun File.isOutside(excludedPaths: List<String>): Boolean =
    excludedPaths.none { absolutePath == it || absolutePath.startsWith("$it${File.separator}") }
