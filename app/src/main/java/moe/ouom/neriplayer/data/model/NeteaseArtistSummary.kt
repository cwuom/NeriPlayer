package moe.ouom.neriplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NeteaseArtistSummary(
    val id: Long,
    val name: String
) : Parcelable
