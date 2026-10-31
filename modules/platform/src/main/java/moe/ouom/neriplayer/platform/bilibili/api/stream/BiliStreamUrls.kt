package moe.ouom.neriplayer.platform.bilibili.api.stream

import java.net.URI
import moe.ouom.neriplayer.network.security.hostMatchesAnyDomain
import moe.ouom.neriplayer.network.security.hostMatchesDomain

private val BiliStreamDomains = listOf("bilivideo.com", "bilivideo.cn", "mountaintoys.cn")

fun isBiliStreamHost(host: String): Boolean {
    return hostMatchesAnyDomain(host, BiliStreamDomains)
}

fun isBiliStreamUrl(url: String): Boolean =
    runCatching { URI(url) }
        .getOrNull()
        ?.let { uri ->
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
                isBiliStreamHost(uri.host.orEmpty())
        } == true

private fun scoreBiliStreamUrl(url: String): Int {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    val bilivideo = hostMatchesDomain(host, "bilivideo.com") || hostMatchesDomain(host, "bilivideo.cn")
    return when {
        host.startsWith("upos-") && bilivideo -> 3
        bilivideo -> 2
        hostMatchesDomain(host, "mountaintoys.cn") -> 1
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
