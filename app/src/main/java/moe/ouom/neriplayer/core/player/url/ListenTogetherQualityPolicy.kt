package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.listentogether.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES

private val NETEASE_SHARE_DEFAULT_GROUPS = listOf(
    listOf("exhigh", "higher", "standard"),
    listOf("lossless"),
    listOf("sky")
)

internal const val MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES = 2
internal const val MAX_LISTEN_TOGETHER_YOUTUBE_STREAM_URL_CANDIDATES = 1

private val BILI_HIGH_FALLBACK_ORDER = listOf("high", "medium", "low")

internal fun maxListenTogetherStreamUrlCandidates(source: PlaybackAudioSource): Int {
    return when (source) {
        PlaybackAudioSource.NETEASE -> MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
        PlaybackAudioSource.BILIBILI -> MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES
        PlaybackAudioSource.YOUTUBE_MUSIC -> MAX_LISTEN_TOGETHER_YOUTUBE_STREAM_URL_CANDIDATES
        PlaybackAudioSource.LOCAL -> 0
    }
}

internal fun buildListenTogetherNeteaseQualityGroups(
    preferredQualityKey: String
): List<List<String>> {
    val normalizedPreferred = normalizeNeteaseQualityKey(preferredQualityKey) ?: "exhigh"
    val preferredGroup = if (normalizedPreferred == "exhigh") {
        NETEASE_SHARE_DEFAULT_GROUPS.first()
    } else {
        listOf(normalizedPreferred)
    }
    return buildList {
        add(preferredGroup)
        NETEASE_SHARE_DEFAULT_GROUPS.forEach { group ->
            if (group != preferredGroup) add(group)
        }
    }.distinct().take(3)
}

internal fun tryRegisterNeteaseListenTogetherQualityCandidate(
    resolvedQualityKeys: MutableSet<String>,
    actualQualityKey: String
): Boolean = resolvedQualityKeys.add(actualQualityKey)

internal fun buildListenTogetherBiliQualityOrder(
    preferredQualityKey: String,
    availableQualityKeys: Set<String>
): List<String> {
    val preferred = preferredQualityKey.trim().lowercase().ifBlank { "high" }
    val selected = linkedSetOf<String>()
    fun addFirstAvailable(qualityKeys: List<String>): String? {
        val quality = qualityKeys.firstOrNull { it in availableQualityKeys } ?: return null
        selected += quality
        return quality
    }

    if (preferred != "high") {
        addFirstAvailable(listOf(preferred))
    }
    val primaryQuality = addFirstAvailable(BILI_HIGH_FALLBACK_ORDER)
    if (selected.size < MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES) {
        addFirstAvailable(listOf("lossless"))
    }
    if (
        selected.size < MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES &&
            "high" !in availableQualityKeys &&
            primaryQuality != null
    ) {
        addFirstAvailable(
            BILI_HIGH_FALLBACK_ORDER.dropWhile { it != primaryQuality }.drop(1)
        )
    }
    return selected.take(MAX_LISTEN_TOGETHER_BILI_STREAM_URL_CANDIDATES)
}

internal fun buildListenTogetherYouTubeQualityOrder(
    preferredQualityKey: String
): List<String> {
    val preferred = preferredQualityKey.trim().lowercase().ifBlank { "high" }
    return listOf(preferred)
}
