package moe.ouom.neriplayer.data.sync.identity

import java.util.Locale
import moe.ouom.neriplayer.api.youtube.transport.extractYouTubeMusicVideoId

internal fun trimmedSyncIdentityPart(value: String?): String? = nonBlankSyncIdentityPart(value?.trim())

private fun nonBlankSyncIdentityPart(value: String?): String? = if (value.isNullOrBlank()) null else value

internal fun explicitSyncChannelId(value: String?): String? {
    val normalized = trimmedSyncIdentityPart(value) ?: return null
    return normalizeChannelAlias(normalized.lowercase(Locale.US))
}

internal fun inferredSyncChannelId(album: String, mediaUri: String?, inferNetease: Boolean): String? = when {
    extractYouTubeMusicVideoId(mediaUri) != null -> "youtube_music"
    album.startsWith("Bilibili", ignoreCase = true) -> "bilibili"
    album.startsWith("Netease", ignoreCase = true) -> "netease"
    inferNetease && mediaUri.isNullOrBlank() -> "netease"
    else -> null
}

internal fun legacyAudioId(id: Long): String? = if (id == 0L) null else id.toString()

internal fun legacyBiliSubAudioId(album: String): String =
    nonBlankSyncIdentityPart(album.substringAfter('|', "").substringBefore('|')).orEmpty()
