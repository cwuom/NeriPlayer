package moe.ouom.neriplayer.ui.viewmodel.tab

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val picUrl: String,
    val playCount: Long,
    val trackCount: Int
) : Parcelable

@Parcelize
data class AlbumSummary(
    val id: Long,
    val name: String,
    val picUrl: String,
    val size: Int
) : Parcelable

enum class BiliPlaylistKind {
    CREATED_FAVORITE,
    COLLECTED_FAVORITE,
    COLLECTION,
    SERIES
}

@Parcelize
data class BiliPlaylist(
    val mediaId: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val count: Int,
    val coverUrl: String,
    val kind: BiliPlaylistKind = BiliPlaylistKind.CREATED_FAVORITE,
    val subtitle: String = ""
) : Parcelable
