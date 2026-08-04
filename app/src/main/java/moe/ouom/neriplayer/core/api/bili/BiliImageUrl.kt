package moe.ouom.neriplayer.core.api.bili

import java.net.URI

private val BILI_IMAGE_HOST_SUFFIXES = listOf(
    ".hdslb.com",
    ".biliimg.com"
)

fun buildBiliThumbnailUrl(
    imageUrl: String,
    width: Int,
    height: Int
): String {
    if (imageUrl.isBlank() || width <= 0 || height <= 0) return imageUrl

    val normalizedUrl = imageUrl.trim().let { url ->
        if (url.startsWith("//")) "https:$url" else url
    }
    val host = runCatching { URI(normalizedUrl).host }
        .getOrNull()
        ?.lowercase()
        ?: return imageUrl
    if (BILI_IMAGE_HOST_SUFFIXES.none { suffix -> host.endsWith(suffix) }) {
        return imageUrl
    }

    val suffixStart = normalizedUrl.indexOfFirst { character ->
        character == '?' || character == '#'
    }.takeIf { index -> index >= 0 } ?: normalizedUrl.length
    val sourceWithoutQuery = normalizedUrl.substring(0, suffixStart)
    val requestSuffix = normalizedUrl.substring(suffixStart)
    val lastPathSeparator = sourceWithoutQuery.lastIndexOf('/')
    val existingOperationStart = sourceWithoutQuery.lastIndexOf('@')
    val sourceWithoutOperation = if (existingOperationStart > lastPathSeparator) {
        sourceWithoutQuery.substring(0, existingOperationStart)
    } else {
        sourceWithoutQuery
    }
    return "$sourceWithoutOperation@${width}w_${height}h_1c.webp$requestSuffix"
}
