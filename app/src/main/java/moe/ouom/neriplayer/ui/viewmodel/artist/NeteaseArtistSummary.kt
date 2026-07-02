package moe.ouom.neriplayer.ui.viewmodel.artist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NeteaseArtistSummary(
    val id: Long,
    val name: String
) : Parcelable
