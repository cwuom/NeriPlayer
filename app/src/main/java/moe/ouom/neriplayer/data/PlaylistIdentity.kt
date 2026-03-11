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

fun SongIdentity.stableKey(): String = buildString {
    append(id)
    append('|')
    append(album)
    append('|')
    append(mediaUri.orEmpty())
}

fun SongItem.identity(): SongIdentity = SongIdentity(
    id = id,
    album = album,
    mediaUri = localFilePath ?: mediaUri
)

fun SongItem.stableKey(): String = identity().stableKey()

fun SyncSong.identity(): SongIdentity = SongIdentity(
    id = id,
    album = album,
    mediaUri = mediaUri
)

fun SyncSong.stableKey(): String = identity().stableKey()

fun SongItem.sameIdentityAs(other: SongItem?): Boolean {
    return other != null && identity() == other.identity()
}

fun SyncSong.sameIdentityAs(other: SyncSong?): Boolean {
    return other != null && identity() == other.identity()
}
