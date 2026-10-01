package moe.ouom.neriplayer.api.bilibili.stream

import java.net.URI

fun isBiliStreamHost(host: String): Boolean {
    val normalized = host.trim().lowercase()
    if (normalized.isBlank()) return false
    return normalized.contains("bilivideo.") || normalized.endsWith(".mountaintoys.cn")
}

fun isBiliStreamUrl(url: String): Boolean =
    runCatching { URI(url).host.orEmpty() }
        .getOrNull()
        ?.let(::isBiliStreamHost) == true

private fun scoreBiliStreamUrl(url: String): Int {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    return when {
        host.startsWith("upos-") && host.contains("bilivideo.") -> 3
        host.contains("bilivideo.") -> 2
        host.endsWith(".mountaintoys.cn") -> 1
        else -> 0
    }
}

fun prioritizeBiliStreamUrls(primaryUrl: String, backupUrls: List<String>): List<String> {
    val deduped = buildList {
        add(primaryUrl)
        addAll(backupUrls)
    }.map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    return deduped.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<String>> { scoreBiliStreamUrl(it.value) }
                .thenBy { it.index }
        )
        .map { it.value }
}
