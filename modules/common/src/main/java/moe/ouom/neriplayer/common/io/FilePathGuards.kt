package moe.ouom.neriplayer.common.io

import java.io.File

fun isFileInsideDirectory(file: File, directory: File): Boolean {
    val filePath = runCatching { file.canonicalFile.toPath() }
        .getOrElse { file.absoluteFile.toPath() }
    val directoryPath = runCatching { directory.canonicalFile.toPath() }
        .getOrElse { directory.absoluteFile.toPath() }
    return filePath.startsWith(directoryPath)
}
