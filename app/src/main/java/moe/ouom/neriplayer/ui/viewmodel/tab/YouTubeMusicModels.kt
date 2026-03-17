package moe.ouom.neriplayer.ui.viewmodel.tab

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class YouTubeMusicPlaylist(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int = 0
) : Parcelable
