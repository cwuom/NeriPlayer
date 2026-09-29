package moe.ouom.neriplayer.data.model.bilibili.playback

import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage

data class ResolvedBiliSong(
    val avid: Long,
    val cid: Long,
    val videoInfo: VideoBasicInfo,
    val pageInfo: VideoPage?
)
