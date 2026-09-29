package moe.ouom.neriplayer.data.storage.scan

import java.io.File
import moe.ouom.neriplayer.data.model.storage.FileStats

fun statsOf(file: File?, excludedRoots: List<File> = emptyList()): FileStats {
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
