package moe.ouom.neriplayer.data.platform.bili.playback.model

import moe.ouom.neriplayer.api.bilibili.model.video.VideoBasicInfo
import moe.ouom.neriplayer.api.bilibili.model.video.VideoPage

data class ResolvedBiliSong(
    val avid: Long,
    val cid: Long,
    val videoInfo: VideoBasicInfo,
    val pageInfo: VideoPage?
)
