package moe.ouom.neriplayer.util.io

import java.io.File

internal fun clearAllFiles(
    files: Iterable<File>,
    clearFile: (File) -> Boolean
): Boolean {
    var allFilesCleared = true
    files.forEach { file ->
        if (!clearFile(file)) {
            allFilesCleared = false
        }
    }
    return allFilesCleared
}
