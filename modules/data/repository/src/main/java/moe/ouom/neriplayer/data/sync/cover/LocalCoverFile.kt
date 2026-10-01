package moe.ouom.neriplayer.data.sync.cover

import java.io.File
import java.net.URI

internal fun localCoverFile(url: String): File? {
    if (url.startsWith("/")) return File(url)
    if (!url.startsWith("file:", ignoreCase = true)) return null
    return runCatching { File(URI(url)) }.getOrNull()
}

internal fun missingLocalCoverFile(url: String): Boolean =
    localCoverFile(url)?.let { !it.exists() } == true
