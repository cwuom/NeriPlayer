package moe.ouom.neriplayer.data.sync.identity

import moe.ouom.neriplayer.data.model.server.ServerSongRef

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"

fun SyncSong.identity(): SongIdentity {
    val serverRef = ServerSongRef.fromMediaUri(mediaUri)
        ?: ServerSongRef.fromAudioId(audioId)?.takeIf { channelId == ServerSongRef.CHANNEL }
    serverRef?.let { return SongIdentity(it.numericId, ServerSongRef.CHANNEL, it.mediaUri) }
    return normalizedRemoteIdentity() ?: SongIdentity(id, album, mediaUri)
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
    val audio = trimmedSyncIdentityPart(audioId) ?: legacyAudioId(id)
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
    val channel = explicitSyncChannelId(rawChannelId)
    if (channel != null) return channel
    return inferredSyncChannelId(album, mediaUri, inferNeteaseForBlankRemote)
}

fun normalizeChannelAlias(channel: String): String {
    return youtubeChannelAliases[channel] ?: channel
}

fun normalizedSubAudioId(
    channel: String,
    rawSubAudioId: String?,
    album: String
): String {
    if (channel != "bilibili") return ""
    return trimmedSyncIdentityPart(rawSubAudioId) ?: legacyBiliSubAudioId(album)
}

private val youtubeChannelAliases = mapOf(
    "youtube" to YOUTUBE_MUSIC_IDENTITY_ALBUM,
    "ytmusic" to YOUTUBE_MUSIC_IDENTITY_ALBUM,
    "youtubemusic" to YOUTUBE_MUSIC_IDENTITY_ALBUM
)

fun stableRemoteIdentityId(channel: String, audio: String, subAudio: String): Long {
    return when {
        channel == "netease" -> audio.toLongOrNull() ?: stableYouTubeMusicId("$channel|$audio")
        else -> stableYouTubeMusicId("$channel|$audio|$subAudio")
    }
}
