package moe.ouom.neriplayer.data.local.storage.scan

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.storage.FileStats

fun statsOf(file: File?, excludedRoots: List<File> = emptyList()): FileStats {
    if (file == null) return FileStats.Empty
    return try {
        measuredStatsOf(file, excludedRoots)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        FileStats.Empty
    }
}

internal fun measuredStatsOf(file: File, excludedRoots: List<File> = emptyList()): FileStats {
    val attributes = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return FileStats.Empty
    }
    return when {
        attributes.isSymbolicLink -> FileStats.Empty
        attributes.isRegularFile -> FileStats(attributes.size(), 1)
        attributes.isDirectory -> directoryStats(file, excludedRoots)
        else -> FileStats.Empty
    }
}

internal fun statsOfFiles(files: List<File>): FileStats =
    files.fold(FileStats.Empty) { total, file -> total + measuredStatsOf(file) }

private fun directoryStats(directory: File, excludedRoots: List<File>): FileStats {
    val excludedPaths = excludedRoots.map { it.absolutePath }
    return directory.walkTopDown()
        .onFail { file, error ->
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                throw error
            } catch (_: NoSuchFileException) {
                // 扫描期间缓存目录可能被所属功能正常淘汰
            }
        }
        .onEnter { !Files.isSymbolicLink(it.toPath()) && it.isOutside(excludedPaths) }
        .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile && it.isOutside(excludedPaths) }
        .fold(FileStats.Empty) { total, file -> total + measuredStatsOf(file) }
}

private fun File.isOutside(excludedPaths: List<String>): Boolean =
    excludedPaths.none { absolutePath == it || absolutePath.startsWith("$it${File.separator}") }
