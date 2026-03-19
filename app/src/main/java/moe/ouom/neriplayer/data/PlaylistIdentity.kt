package moe.ouom.neriplayer.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.github.SyncSong
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

@Parcelize
data class SongIdentity(
    val id: Long,
    val album: String,
    val mediaUri: String?
) : Parcelable

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"

fun SongIdentity.stableKey(): String = buildString {
    append(id)
    append('|')
    append(album)
    append('|')
    append(mediaUri.orEmpty())
}

fun SongItem.identity(): SongIdentity = SongIdentity(
    id = normalizedYouTubeMusicId(this) ?: id,
    album = normalizedYouTubeMusicAlbum(this),
    mediaUri = normalizedIdentityMediaUri(this)
)

fun SongItem.stableKey(): String = identity().stableKey()

fun SyncSong.identity(): SongIdentity = SongIdentity(
    id = extractYouTubeMusicVideoId(mediaUri)?.let(::stableYouTubeMusicId) ?: id,
    album = extractYouTubeMusicVideoId(mediaUri)?.let { YOUTUBE_MUSIC_IDENTITY_ALBUM } ?: album,
    mediaUri = extractYouTubeMusicVideoId(mediaUri)?.let { buildYouTubeMusicMediaUri(it) } ?: mediaUri
)

fun SyncSong.stableKey(): String = identity().stableKey()

fun SongItem.sameIdentityAs(other: SongItem?): Boolean {
    return other != null && identity() == other.identity()
}

fun SyncSong.sameIdentityAs(other: SyncSong?): Boolean {
    return other != null && identity() == other.identity()
}

private fun normalizedYouTubeMusicId(song: SongItem): Long? {
    return extractYouTubeMusicVideoId(song.mediaUri)?.let(::stableYouTubeMusicId)
}

private fun normalizedYouTubeMusicAlbum(song: SongItem): String {
    return if (extractYouTubeMusicVideoId(song.mediaUri) != null) {
        YOUTUBE_MUSIC_IDENTITY_ALBUM
    } else {
        LocalSongSupport.identityAlbumKey(song)
    }
}

private fun normalizedIdentityMediaUri(song: SongItem): String? {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    return if (videoId != null) {
        buildYouTubeMusicMediaUri(videoId)
    } else {
        song.localFilePath ?: song.mediaUri
    }
}
