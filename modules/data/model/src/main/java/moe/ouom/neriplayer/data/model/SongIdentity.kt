package moe.ouom.neriplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

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
