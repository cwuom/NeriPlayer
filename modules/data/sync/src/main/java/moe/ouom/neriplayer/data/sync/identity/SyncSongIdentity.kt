package moe.ouom.neriplayer.data.sync.identity

import java.util.Locale
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.api.youtube.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.api.youtube.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.api.youtube.transport.stableYouTubeMusicId

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"
private const val BILIBILI_IDENTITY_HINT = "Bilibili"

fun SyncSong.identity(): SongIdentity {
    normalizedRemoteIdentity()?.let { return it }
    return SongIdentity(
        id = extractYouTubeMusicVideoId(mediaUri)?.let(::stableYouTubeMusicId) ?: id,
        album = extractYouTubeMusicVideoId(mediaUri)?.let { YOUTUBE_MUSIC_IDENTITY_ALBUM } ?: album,
        mediaUri = extractYouTubeMusicVideoId(mediaUri)?.let { buildYouTubeMusicMediaUri(it) } ?: mediaUri
    )
}

fun SyncSong.stableKey(): String = identity().stableKey()

fun SyncSong.sameIdentityAs(other: SyncSong?): Boolean {
    return other != null && identity() == other.identity()
}

private fun SyncSong.normalizedRemoteIdentity(): SongIdentity? {
    val videoId = extractYouTubeMusicVideoId(mediaUri)
    if (videoId != null) {
        return SongIdentity(
            id = stableYouTubeMusicId(videoId),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
    }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = true
    )
    val audio = audioId?.trim()?.takeIf { it.isNotBlank() } ?: id.takeIf { it != 0L }?.toString()
    if (channel == null || audio == null) return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) {
        return SongIdentity(
            id = stableYouTubeMusicId(audio),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(audio)
        )
    }

    return SongIdentity(
        id = stableRemoteIdentityId(
            channel = channel,
            audio = audio,
            subAudio = normalizedSubAudioId(channel, subAudioId, album)
        ),
        album = channel,
        mediaUri = null
    )
}

fun normalizedChannelId(
    rawChannelId: String?,
    album: String,
    mediaUri: String?,
    inferNeteaseForBlankRemote: Boolean
): String? {
    val channel = rawChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.US)
        ?.let(::normalizeChannelAlias)
    if (channel != null) return channel

    return when {
        extractYouTubeMusicVideoId(mediaUri) != null -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        album.startsWith(BILIBILI_IDENTITY_HINT, ignoreCase = true) -> "bilibili"
        album.startsWith("Netease", ignoreCase = true) -> "netease"
        inferNeteaseForBlankRemote && mediaUri.isNullOrBlank() -> "netease"
        else -> null
    }
}

fun normalizeChannelAlias(channel: String): String {
    return when (channel) {
        "youtube", "ytmusic", "youtubemusic" -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        else -> channel
    }
}

fun normalizedSubAudioId(
    channel: String,
    rawSubAudioId: String?,
    album: String
): String {
    val explicitSubAudioId = rawSubAudioId?.trim()?.takeIf { it.isNotBlank() }
    if (channel != "bilibili") return ""
    return explicitSubAudioId ?: album
        .substringAfter('|', "")
        .substringBefore('|')
        .takeIf { it.isNotBlank() }
        .orEmpty()
}

fun stableRemoteIdentityId(channel: String, audio: String, subAudio: String): Long {
    return when {
        channel == "netease" -> audio.toLongOrNull() ?: stableYouTubeMusicId("$channel|$audio")
        else -> stableYouTubeMusicId("$channel|$audio|$subAudio")
    }
}
