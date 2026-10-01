package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.identity

internal fun SyncSong.toMergeCandidate(): SongMergeCandidate {
    return SongMergeCandidate(
        id = id,
        identity = identity(),
        channelAudioKey = channelAudioKey(this),
        sourceHint = sourceHint(this),
        normalizedName = name.normalizedText(),
        normalizedArtist = artist.normalizedText()
    )
}

internal data class SongMergeCandidate(
    val id: Long,
    val identity: SongIdentity,
    val channelAudioKey: String?,
    val sourceHint: String?,
    val normalizedName: String,
    val normalizedArtist: String
) {
    val fallbackKey: FallbackKey? =
        if (id != 0L && normalizedName.isNotEmpty()) {
            FallbackKey(id, normalizedName, normalizedArtist)
        } else {
            null
        }
}

internal data class FallbackKey(
    val id: Long,
    val normalizedName: String,
    val normalizedArtist: String
)

private fun channelAudioKey(song: SyncSong): String? {
    val channel = normalizedMergeChannel(song.channelId)
    val audio = nonEmptyMergePart(song.audioId)
    if (channel == null || audio == null) return null
    val subAudio = song.subAudioId?.trim().orEmpty()
    return "$channel|$audio|$subAudio"
}

private fun sourceHint(song: SyncSong): String? {
    val channel = normalizedMergeChannel(song.channelId)
    if (channel != null) return channel

    val album = song.album.trim().lowercase()
    return when {
        album.startsWith("netease") -> "netease"
        album.startsWith("bilibili") -> "bilibili"
        hasYouTubeSource(song.mediaUri) -> "youtube"
        else -> null
    }
}

private fun nonEmptyMergePart(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    return if (trimmed.isEmpty()) null else trimmed
}

private fun normalizedMergeChannel(value: String?): String? = nonEmptyMergePart(value)?.lowercase()

private fun hasYouTubeSource(value: String?): Boolean = value?.contains("youtube", ignoreCase = true) == true

private fun String.normalizedText(): String {
    return trim().lowercase()
}
