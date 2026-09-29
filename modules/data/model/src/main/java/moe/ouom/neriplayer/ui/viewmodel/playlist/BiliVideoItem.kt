package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Bilibili 视频条目数据模型 */
@Parcelize
data class BiliVideoItem(
    val id: Long, // avid
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long = 0L,
    val coverUrl: String,
    val durationSec: Int
) : Parcelable
